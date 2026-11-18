package com.babylon.chat.iq;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * إشعارات الرسائل بدون سيرفر: خدمة أمامية تبقي مستمع Firestore واحداً على محادثاتي
 * (chats where participants contains myUid). عند تغيّر محادثة وآخر مرسل فيها غيري وآخر رسالة أحدث مما أُشعِر به،
 * يُبنى إشعار تيليجرام من حقول المحادثة نفسها (lastMessage/lastMessageAt/lastSender) ومن ملف المرسل users/{uid}.
 * لا إشعار لمحادثة مفتوحة أمامك ولا للمكتومين ولا للمحظورين.
 */
public class MessageListenerService extends Service {
    private static final int FG_ID = 2;
    private static final String PREFS = "babylon_notif";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final HashMap<String, Long> notified = new HashMap<>();   // chatId ← آخر lastMessageAt تم الإشعار به/تجاهله
    private ListenerRegistration reg;
    private String listeningUid = "";
    private long baseline;
    private SharedPreferences sp;

    static void start(Context c) {
        Intent i = new Intent(c, MessageListenerService.class);
        if (Build.VERSION.SDK_INT >= 26) ContextCompat.startForegroundService(c, i); else c.startService(i);
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        sp = AppSettings.prefs(this, PREFS);
        baseline = sp.getLong("baseline", 0L);
        if (baseline == 0L) { baseline = System.currentTimeMillis(); sp.edit().putLong("baseline", baseline).apply(); }   // لا إشعارات لرسائل قديمة عند أول تشغيل
        for (String k : sp.getAll().keySet())
            if (k.startsWith("n_")) notified.put(k.substring(2), sp.getLong(k, 0L));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notifier.ensureChannel(this);
        goForeground();
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) { stopForegroundCompat(); stopSelf(); return START_NOT_STICKY; }
        if (!u.getUid().equals(listeningUid)) attach(u.getUid());
        return START_STICKY;
    }

    private void goForeground() {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MassActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification n = new NotificationCompat.Builder(this, Notifier.SERVICE_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_message)
                .setContentTitle("Babylon Chat")
                .setContentText("متصل لاستقبال الرسائل")
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true).setShowWhen(false)
                .setContentIntent(pi).build();
        try {
            if (Build.VERSION.SDK_INT >= 34)
                startForeground(FG_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING);
            else startForeground(FG_ID, n);
        } catch (Throwable ignored) { }
    }

    private void stopForegroundCompat() {
        try { if (Build.VERSION.SDK_INT >= 24) stopForeground(Service.STOP_FOREGROUND_REMOVE); else stopForeground(true); } catch (Throwable ignored) { }
    }

    private void attach(final String myUid) {
        if (reg != null) { reg.remove(); reg = null; }
        listeningUid = myUid;
        reg = FirebaseFirestore.getInstance().collection("chats").whereArrayContains("participants", myUid)
                .addSnapshotListener((qs, e) -> {
                    if (qs == null) return;
                    for (DocumentChange ch : qs.getDocumentChanges()) {
                        if (ch.getType() == DocumentChange.Type.REMOVED) continue;
                        final DocumentSnapshot d = ch.getDocument();
                        io.execute(() -> { try { handle(myUid, d); } catch (Throwable ignored) { } });
                    }
                });
    }

    /** يقرر الإشعار لمحادثة تغيّرت. يعمل على خيط خلفي واحد (تسلسل الرسائل محفوظ). */
    private void handle(String myUid, DocumentSnapshot d) throws Exception {
        final String chatId = d.getId();
        Long la = d.getLong("lastMessageAt");
        String last = d.getString("lastSender");
        if (la == null || last == null || last.isEmpty()) return;

        long seen = Math.max(baseline, notified.containsKey(chatId) ? notified.get(chatId) : 0L);
        if (la <= seen) return;                                   // أُشعِر بها أو أقدم من أول تشغيل
        notified.put(chatId, la);
        sp.edit().putLong("n_" + chatId, la).apply();

        if (last.equals(myUid)) return;                           // رسالتي أنا
        if (Notifier.isForeground() && Notifier.isChatOpen(chatId)) return;   // المحادثة مفتوحة أمامي
        if (AppSettings.prefs(this, "babylon_ui").getStringSet("muted_users", new java.util.HashSet<String>()).contains(last)) return;   // مكتوم

        FirebaseFirestore db = FirebaseFirestore.getInstance();
        try {   // أنا حاظره
            DocumentSnapshot b = Tasks.await(db.collection("users").document(myUid).collection("blocks").document(last).get(), 5, TimeUnit.SECONDS);
            if (b != null && b.exists()) return;
        } catch (Throwable ignored) { }

        String text = d.getString("lastMessage");
        if (text == null || text.trim().isEmpty()) text = "رسالة جديدة";
        if (text.length() > 300) text = text.substring(0, 300) + "…";

        String name = "", photo = "";
        try {   // اسم المرسل وصورته (الصورة كبيرة base64 فتُقرأ من ملفه)
            DocumentSnapshot us = Tasks.await(db.collection("users").document(last).get(), 7, TimeUnit.SECONDS);
            if (us != null && us.exists()) {
                String n = us.getString("name");
                if (n == null || n.trim().isEmpty()) n = us.getString("username");
                if (n != null) name = n.trim();
                if (!Boolean.TRUE.equals(us.getBoolean("hideAvatar"))) {
                    String ph = us.getString("photo");
                    if (ph == null || ph.isEmpty()) ph = us.getString("avatarUrl");
                    if (ph != null && !ph.isEmpty()) {
                        photo = ph;
                        if (!ph.startsWith("http") && ph.length() <= 64) {   // معرّف صورة داخل users/{uid}/photos
                            try {
                                DocumentSnapshot pd = Tasks.await(db.collection("users").document(last).collection("photos").document(ph).get(), 5, TimeUnit.SECONDS);
                                String bb = pd == null ? null : pd.getString("b");
                                photo = bb == null ? "" : bb;
                            } catch (Throwable t) { photo = ""; }
                        }
                    }
                }
            }
        } catch (Throwable ignored) { }
        if (name.isEmpty()) name = "Babylon Chat";

        MsgNotifier.show(this, chatId, last, name, photo, text, la);
    }

    @Override
    public void onDestroy() {
        if (reg != null) { reg.remove(); reg = null; }
        io.shutdown();
        super.onDestroy();
    }
}
