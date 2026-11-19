package com.babylon.chat.iq;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.Person;
import androidx.core.app.RemoteInput;
import androidx.core.graphics.drawable.IconCompat;


import java.util.List;

/**
 * يبني إشعار الرسالة بشكل تيليجرام (يستدعيه MessageListenerService):
 * صورة المرسل الدائرية + اسمه + نص الرسالة، وتحته زرّا «رد» (إدخال مباشر من الإشعار) و«اعتبارها مقروءة».
 * النقر على الإشعار يفتح المحادثة نفسها.
 */
final class MsgNotifier {
    private MsgNotifier() { }

    static final String EXTRA_UID = "bb_open_uid";
    static final String EXTRA_NAME = "bb_open_name";
    static final String ACT_REPLY = "com.babylon.chat.iq.NOTIF_REPLY";
    static final String ACT_READ = "com.babylon.chat.iq.NOTIF_READ";
    static final String KEY_REPLY = "bb_reply_text";
    static final String X_CHAT = "chatId", X_SENDER = "senderId", X_NAME = "senderName";
    private static final int MAX_LINES = 8;

    // ================================================================== بناء الإشعار
    static void show(Context c, String chatId, String senderId, String name, String photo, String text, long ts) {
        Notifier.ensureChannel(c);
        NotificationManagerCompat nm = NotificationManagerCompat.from(c);
        if (!nm.areNotificationsEnabled()) return;

        final int px = Math.round(c.getResources().getDisplayMetrics().density * 64);
        Bitmap av = Notifier.avatar(photo, name, px);

        Person sender = new Person.Builder().setName(name).setKey(senderId).setIcon(IconCompat.createWithBitmap(av)).build();
        NotificationCompat.MessagingStyle style = trimmed(existingStyle(c, chatId));
        style.addMessage(text, ts, sender);

        Notification n = base(c, chatId, senderId, name, av, style, ts, false).build();
        try {
            nm.notify(Notifier.tagFor(chatId), Notifier.idFor(chatId), n);
            nm.notify(Notifier.SUMMARY_ID, summary(c));
        } catch (SecurityException ignored) { }   // إذن POST_NOTIFICATIONS غير ممنوح
    }

    static NotificationCompat.Builder base(Context c, String chatId, String senderId, String name, Bitmap av,
                                           NotificationCompat.MessagingStyle style, long ts, boolean quiet) {
        final int rc = Math.abs(chatId.hashCode() % 100000) * 4;
        final int immutable = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        final int mutable = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;

        // النقر: يفتح المحادثة
        Intent open = new Intent(c, MassActivity.class)
                .setAction("bb.open." + chatId)
                .putExtra(EXTRA_UID, senderId).putExtra(EXTRA_NAME, name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(c, rc, open, PendingIntent.FLAG_UPDATE_CURRENT | immutable);

        // زر «رد»: إدخال نص من الإشعار نفسه
        RemoteInput ri = new RemoteInput.Builder(KEY_REPLY).setLabel("اكتب رداً...").build();
        Intent reply = actionIntent(c, ACT_REPLY, chatId, senderId, name);
        PendingIntent replyPi = PendingIntent.getBroadcast(c, rc + 1, reply, PendingIntent.FLAG_UPDATE_CURRENT | mutable);
        NotificationCompat.Action replyAct = new NotificationCompat.Action.Builder(R.drawable.ic_reply_16, "رد", replyPi)
                .addRemoteInput(ri)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .setShowsUserInterface(false)
                .setAllowGeneratedReplies(true)
                .build();

        // زر «اعتبارها مقروءة»
        Intent read = actionIntent(c, ACT_READ, chatId, senderId, name);
        PendingIntent readPi = PendingIntent.getBroadcast(c, rc + 2, read, PendingIntent.FLAG_UPDATE_CURRENT | immutable);
        NotificationCompat.Action readAct = new NotificationCompat.Action.Builder(R.drawable.ic_check_16, "اعتبارها مقروءة", readPi)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                .setShowsUserInterface(false)
                .build();

        Notification pub = new NotificationCompat.Builder(c, Notifier.channelFor(c))
                .setSmallIcon(R.drawable.ic_stat_message).setColor(Notifier.GOLD)
                .setContentTitle("Babylon Chat").setContentText("رسالة جديدة").build();

        return new NotificationCompat.Builder(c, Notifier.channelFor(c))
                .setSmallIcon(R.drawable.ic_stat_message)
                .setColor(Notifier.GOLD)
                .setLargeIcon(av)
                .setStyle(style)
                .setContentTitle(name)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setSound(Notifier.channelFor(c).equals(Notifier.CHANNEL_ID) ? Notifier.soundUri(c) : null)   // صوت التطبيق الخاص على أندرويد 7 وأقل (الأحدث يتبع القناة)
                .setDefaults(NotificationCompat.DEFAULT_VIBRATE | NotificationCompat.DEFAULT_LIGHTS)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(pub)
                .setWhen(ts).setShowWhen(true)
                .setAutoCancel(true)
                .setOnlyAlertOnce(quiet)
                .setGroup(Notifier.GROUP_KEY)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                .setContentIntent(openPi)
                .addAction(replyAct)
                .addAction(readAct);
    }

    private static Intent actionIntent(Context c, String action, String chatId, String senderId, String name) {
        return new Intent(c, NotifActionReceiver.class).setAction(action)
                .putExtra(X_CHAT, chatId).putExtra(X_SENDER, senderId).putExtra(X_NAME, name);
    }

    /** ملخّص المجموعة: يجمع إشعارات عدة محادثات تحت إشعار واحد (مثل تيليجرام). */
    private static Notification summary(Context c) {
        return new NotificationCompat.Builder(c, Notifier.channelFor(c))
                .setSmallIcon(R.drawable.ic_stat_message)
                .setColor(Notifier.GOLD)
                .setContentTitle("Babylon Chat")
                .setContentText("رسائل جديدة")
                .setStyle(new NotificationCompat.InboxStyle().setSummaryText("رسائل جديدة"))
                .setGroup(Notifier.GROUP_KEY)
                .setGroupSummary(true)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                .setAutoCancel(true)
                .build();
    }

    // ================================================================== أسطر المحادثة
    /** يعيد أسلوب الرسائل من الإشعار الظاهر لهذه المحادثة (فتتجمّع الرسائل المتتالية في إشعار واحد)، أو جديداً. */
    static NotificationCompat.MessagingStyle existingStyle(Context c, String chatId) {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                NotificationManager m = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
                for (android.service.notification.StatusBarNotification s : m.getActiveNotifications()) {
                    if (s.getId() == Notifier.idFor(chatId) && Notifier.tagFor(chatId).equals(s.getTag())) {
                        NotificationCompat.MessagingStyle st = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(s.getNotification());
                        if (st != null) return st;
                    }
                }
            }
        } catch (Throwable ignored) { }
        return new NotificationCompat.MessagingStyle(new Person.Builder().setName("أنا").setKey("me").build());
    }

    /** يُبقي آخر (MAX_LINES-1) رسائل فقط ليتّسع المكان لرسالة جديدة. */
    private static NotificationCompat.MessagingStyle trimmed(NotificationCompat.MessagingStyle old) {
        List<NotificationCompat.MessagingStyle.Message> ms = old.getMessages();
        if (ms.size() < MAX_LINES) return old;
        NotificationCompat.MessagingStyle fresh = new NotificationCompat.MessagingStyle(old.getUser());
        for (int i = ms.size() - (MAX_LINES - 1); i < ms.size(); i++) fresh.addMessage(ms.get(i));
        return fresh;
    }

    /** بعد «رد» من الإشعار: يضيف ردّك في الإشعار نفسه (يوقف دوّارة الانتظار) دون إعادة تنبيه. */
    static void appendOwnReply(Context c, String chatId, String senderId, String name, String text) {
        try {
            if (Build.VERSION.SDK_INT < 23) { Notifier.cancelChat(c, chatId); return; }
            NotificationManager m = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            for (android.service.notification.StatusBarNotification s : m.getActiveNotifications()) {
                if (s.getId() == Notifier.idFor(chatId) && Notifier.tagFor(chatId).equals(s.getTag())) {
                    Notification old = s.getNotification();
                    NotificationCompat.MessagingStyle st = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(old);
                    if (st == null) break;
                    st = trimmed(st);
                    st.addMessage(text, System.currentTimeMillis(), (Person) null);   // null = أنا
                    Bitmap av = largeIconOf(c, old, name);
                    Notification n = base(c, chatId, senderId, name, av, st, System.currentTimeMillis(), true).build();
                    NotificationManagerCompat.from(c).notify(Notifier.tagFor(chatId), Notifier.idFor(chatId), n);
                    return;
                }
            }
        } catch (Throwable ignored) { }
        Notifier.cancelChat(c, chatId);
    }

    /** صورة المرسل من الإشعار الظاهر (EXTRA_LARGE_ICON)، وإلا دائرة الحرف الأول. */
    private static Bitmap largeIconOf(Context c, Notification n, String name) {
        final int px = Math.round(c.getResources().getDisplayMetrics().density * 64);
        try {
            android.os.Parcelable p = n.extras == null ? null : n.extras.getParcelable(NotificationCompat.EXTRA_LARGE_ICON);
            if (p instanceof Bitmap) return (Bitmap) p;
            if (Build.VERSION.SDK_INT >= 23 && p instanceof android.graphics.drawable.Icon) {
                android.graphics.drawable.Drawable d = ((android.graphics.drawable.Icon) p).loadDrawable(c);
                if (d != null) {
                    Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
                    android.graphics.Canvas cv = new android.graphics.Canvas(b);
                    d.setBounds(0, 0, px, px);
                    d.draw(cv);
                    return b;
                }
            }
        } catch (Throwable ignored) { }
        return Notifier.avatar(null, name, px);
    }
}
