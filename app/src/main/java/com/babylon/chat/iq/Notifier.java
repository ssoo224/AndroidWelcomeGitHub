package com.babylon.chat.iq;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.util.Base64;

import androidx.core.app.NotificationManagerCompat;


import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * أدوات إشعارات الرسائل (شكل تيليجرام: صورة المرسل + اسمه + نص الرسالة + زرّا «رد» و«اعتبارها مقروءة»).
 *  - قنوات الإشعارات + تشغيل/إيقاف خدمة الاستماع لـ Firestore (بدون FCM وبدون سيرفر)
 *  - حالة التطبيق (أمامي/خلفي) والمحادثة المفتوحة: لا إشعار لرسالة وصلت في محادثة تنظر إليها الآن
 *  - تجهيز صورة المرسل الدائرية (base64 / رابط / حرف أول بدون صورة)
 */
final class Notifier {
    private Notifier() { }

    static final String CHANNEL_ID = "babylon_messages";
    static final String GROUP_KEY = "babylon_msgs_group";
    static final int SUMMARY_ID = 1;
    static final int GOLD = 0xFFC99A1C;

    // ------------------------------------------------------------------ الحالة (تُحدَّث من BabylonApp و MassActivity)
    private static volatile int startedActivities = 0;
    private static volatile String openChatId = "";

    static void activityStarted() { startedActivities++; }
    static void activityStopped() { if (startedActivities > 0) startedActivities--; }
    static boolean isForeground() { return startedActivities > 0; }

    static void setOpenChat(String chatId) { openChatId = chatId == null ? "" : chatId; }
    static boolean isChatOpen(String chatId) { return chatId != null && !chatId.isEmpty() && chatId.equals(openChatId); }

    // ------------------------------------------------------------------ معرّف الإشعار لكل محادثة
    static String tagFor(String chatId) { return "chat:" + chatId; }
    static int idFor(String chatId) { return 100 + Math.abs(chatId.hashCode() % 1_000_000); }

    static String chatIdOf(String a, String b) { return a.compareTo(b) < 0 ? a + "_" + b : b + "_" + a; }

    /** يزيل إشعار محادثة (عند فتحها أو اعتبارها مقروءة). */
    static void cancelChat(Context c, String chatId) {
        try {
            NotificationManagerCompat nm = NotificationManagerCompat.from(c);
            nm.cancel(tagFor(chatId), idFor(chatId));
            // لا إشعارات أخرى؟ أزل ملخّص المجموعة أيضاً
            if (Build.VERSION.SDK_INT >= 23) {
                NotificationManager m = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
                int left = 0;
                for (android.service.notification.StatusBarNotification s : m.getActiveNotifications())
                    if (s.getId() != SUMMARY_ID && GROUP_KEY.equals(s.getNotification().getGroup())) left++;
                if (left == 0) nm.cancel(SUMMARY_ID);
            }
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ القناة
    static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm.getNotificationChannel(SERVICE_CHANNEL) == null) {   // إشعار الخدمة الدائمة: صامت ومصغّر
                NotificationChannel sc = new NotificationChannel(SERVICE_CHANNEL, "التشغيل في الخلفية", NotificationManager.IMPORTANCE_MIN);
                sc.setDescription("يبقي التطبيق متصلاً لاستقبال الرسائل");
                sc.setShowBadge(false);
                nm.createNotificationChannel(sc);
            }
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "الرسائل", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("إشعارات الرسائل الجديدة");
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[]{0, 220, 120, 220});
            ch.enableLights(true);
            ch.setLightColor(GOLD);
            ch.setLockscreenVisibility(android.app.Notification.VISIBILITY_PRIVATE);
            ch.setShowBadge(true);
            Uri snd = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            ch.setSound(snd, new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            nm.createNotificationChannel(ch);
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ خدمة الاستماع (بدون سيرفر)
    static final String SERVICE_CHANNEL = "babylon_service";

    /** يشغّل خدمة الاستماع لـ Firestore كي تصل الإشعارات والتطبيق في الخلفية. يُستدعى والتطبيق ظاهر (قيد أندرويد 12+). */
    static void register(Context c, final String uid) {
        if (uid == null || uid.isEmpty()) return;
        ensureChannel(c);
        try { MessageListenerService.start(c); } catch (Throwable ignored) { }
    }

    /** عند تسجيل الخروج: إيقاف الخدمة وإزالة كل الإشعارات. */
    static void unregister(Context c, final String uid) {
        try { c.stopService(new android.content.Intent(c, MessageListenerService.class)); } catch (Throwable ignored) { }
        try { NotificationManagerCompat.from(c).cancelAll(); } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ الصورة الدائرية
    /** صورة المرسل كدائرة: base64 أو رابط أو (إن لم توجد) دائرة ذهبية بأول حرف من الاسم. استدعها من خيط خلفي فقط. */
    static Bitmap avatar(String photo, String name, int px) {
        Bitmap src = null;
        try { src = decode(photo, px); } catch (Throwable ignored) { }
        if (src == null) return letterAvatar(name, px);
        return circle(src, px);
    }

    private static Bitmap decode(String photo, int px) throws Exception {
        if (photo == null || photo.isEmpty()) return null;
        byte[] data;
        if (photo.startsWith("http")) {
            HttpURLConnection c = (HttpURLConnection) new URL(photo).openConnection();
            c.setConnectTimeout(5000); c.setReadTimeout(6000);
            InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            data = bos.toByteArray();
        } else {
            String b = photo;
            int comma = b.indexOf(',');
            if (b.startsWith("data:") && comma > 0) b = b.substring(comma + 1);
            data = Base64.decode(b, Base64.DEFAULT);
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        int s = 1;
        while (o.outWidth / (s * 2) >= px && o.outHeight / (s * 2) >= px) s *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = s;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }

    private static Bitmap circle(Bitmap src, int px) {
        int w = src.getWidth(), h = src.getHeight(), side = Math.min(w, h);
        Rect from = new Rect((w - side) / 2, (h - side) / 2, (w - side) / 2 + side, (h - side) / 2 + side);
        Bitmap out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        cv.drawARGB(0, 0, 0, 0);
        cv.drawCircle(px / 2f, px / 2f, px / 2f, p);
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        cv.drawBitmap(src, from, new RectF(0, 0, px, px), p);
        return out;
    }

    private static Bitmap letterAvatar(String name, int px) {
        Bitmap out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(GOLD);
        cv.drawCircle(px / 2f, px / 2f, px / 2f, p);
        p.setColor(0xFFFFFFFF);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(px * 0.46f);
        p.setTextAlign(Paint.Align.CENTER);
        String ch = (name == null || name.trim().isEmpty()) ? "B" : name.trim().substring(0, 1).toUpperCase();
        float y = px / 2f - (p.descent() + p.ascent()) / 2f;
        cv.drawText(ch, px / 2f, y, p);
        return out;
    }
}
