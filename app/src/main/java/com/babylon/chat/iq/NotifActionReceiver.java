package com.babylon.chat.iq;

import androidx.core.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.firestore.WriteBatch;
import com.google.firebase.firestore.DocumentSnapshot;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * أزرار الإشعار:
 *  «رد»              ← يرسل رسالة نصية في المحادثة بنفس صيغة التطبيق (chats/{id}/messages + ميتا المحادثة)
 *  «اعتبارها مقروءة» ← يضع read=true على رسائل المرسل غير المقروءة ويزيل الإشعار
 */
public class NotifActionReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(final Context ctx, final Intent intent) {
        final String action = intent.getAction();
        final String chatId = intent.getStringExtra(MsgNotifier.X_CHAT);
        final String senderId = intent.getStringExtra(MsgNotifier.X_SENDER);
        if (action == null || chatId == null || senderId == null) return;

        // نص الرد من خانة الإدخال داخل الإشعار
        CharSequence typed = null;
        if (MsgNotifier.ACT_REPLY.equals(action)) {
            Bundle r = RemoteInput.getResultsFromIntent(intent);
            if (r != null) typed = r.getCharSequence(MsgNotifier.KEY_REPLY);
        }
        final String replyText = typed == null ? "" : typed.toString().trim();

        final PendingResult pr = goAsync();   // العمل على الشبكة يحتاج ثواني بعد onReceive
        new Thread(() -> {
            try {
                FirebaseUser me = FirebaseAuth.getInstance().getCurrentUser();
                if (me == null) { Notifier.cancelChat(ctx, chatId); return; }
                FirebaseFirestore db = FirebaseFirestore.getInstance();

                if (MsgNotifier.ACT_READ.equals(action)) {
                    markRead(db, chatId, senderId);
                    Notifier.cancelChat(ctx, chatId);
                } else if (MsgNotifier.ACT_REPLY.equals(action)) {
                    if (replyText.isEmpty()) { Notifier.cancelChat(ctx, chatId); return; }
                    boolean sent = sendReply(db, me.getUid(), chatId, senderId, replyText);
                    if (sent) {
                        markRead(db, chatId, senderId);                                     // الرد يعني أنك قرأت ما قبله
                        MsgNotifier.appendOwnReply(ctx, chatId, replyText);     // يظهر ردّك داخل الإشعار
                    } else {
                        Notifier.cancelChat(ctx, chatId);                                   // محظور: لا نرسل
                    }
                }
            } catch (Throwable ignored) {
                try { Notifier.cancelChat(ctx, chatId); } catch (Throwable t) { }
            } finally {
                pr.finish();
            }
        }).start();
    }

    /** نفس حقول MassActivity.send(): type/text/sender/timestamp/read + ميتا المحادثة. يعيد false إن كان أحد الطرفين حاظراً. */
    private static boolean sendReply(FirebaseFirestore db, String myUid, String chatId, String otherUid, String text) throws Exception {
        try {   // أنا حظرته، أو هو حظرني: لا إرسال من الإشعار
            DocumentSnapshot b1 = Tasks.await(db.collection("users").document(myUid).collection("blocks").document(otherUid).get(), 5, TimeUnit.SECONDS);
            if (b1 != null && b1.exists()) return false;
            DocumentSnapshot b2 = Tasks.await(db.collection("users").document(otherUid).collection("blocks").document(myUid).get(), 5, TimeUnit.SECONDS);
            if (b2 != null && b2.exists()) return false;
        } catch (Throwable ignored) { }

        long now = System.currentTimeMillis();
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "text");
        msg.put("text", text);
        msg.put("sender", myUid);
        msg.put("timestamp", now);
        msg.put("read", false);

        Map<String, Object> chatDoc = new HashMap<>();
        chatDoc.put("participants", Arrays.asList(myUid, otherUid));
        chatDoc.put("lastMessage", text);
        chatDoc.put("lastMessageAt", now);
        chatDoc.put("lastSender", myUid);
        chatDoc.put("lastStoryReply", false);

        DocumentReference msgRef = db.collection("chats").document(chatId).collection("messages").document();
        WriteBatch wb = db.batch();
        wb.set(msgRef, msg);
        wb.set(db.collection("chats").document(chatId), chatDoc, SetOptions.merge());
        try { Tasks.await(wb.commit(), 9, TimeUnit.SECONDS); } catch (java.util.concurrent.TimeoutException e) { /* محفوظة محلياً وتُرسل عند عودة الشبكة */ }
        return true;
    }

    private static void markRead(FirebaseFirestore db, String chatId, String senderId) {
        try {
            QuerySnapshot qs = Tasks.await(db.collection("chats").document(chatId).collection("messages")
                    .whereEqualTo("sender", senderId).whereEqualTo("read", false).limit(200).get(), 8, TimeUnit.SECONDS);
            if (qs == null || qs.isEmpty()) return;
            WriteBatch wb = db.batch();
            for (DocumentSnapshot d : qs.getDocuments()) wb.update(d.getReference(), "read", true);
            try { Tasks.await(wb.commit(), 8, TimeUnit.SECONDS); } catch (java.util.concurrent.TimeoutException ignored) { }
        } catch (Throwable ignored) { }
    }
}
