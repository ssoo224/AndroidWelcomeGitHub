package com.babylon.chat.iq;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * خزن محلي (ملفات JSON داخل filesDir) لتقليل استهلاك الإنترنت وإظهار الواجهة فوراً عند ضعف الشبكة:
 *  - بيانات المستخدم (الاسم، اسم المستخدم، النبذة، المحافظة، البريد، الصورة الشخصية، الغلاف، تاريخ الانضمام)
 *  - قائمة المحادثات (اسم الطرف الآخر، آخر رسالة، وقتها، غير المقروءة)
 *  - آخر 50 رسالة لكل محادثة (نص + صور/صوتيات حتى حجم معقول) داخل مساحة التطبيق وتُمسح مع مسح بيانات التطبيق
 * الكتابة تتم في خيط خلفي واحد (بالترتيب)، والقراءة سريعة ومباشرة.
 */
final class LocalCache {

    static final int MAX_MSGS = 50;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static File dir;

    private LocalCache() { }

    /** يُستدعى مرة واحدة بعد معرفة uid؛ لكل حساب مجلد خاص حتى لا تختلط بيانات الحسابات. */
    static synchronized void init(Context c, String uid) {
        try {
            File d = new File(c.getApplicationContext().getFilesDir(), "local_cache/" + safeName(uid));
            if (!d.exists()) d.mkdirs();
            dir = d;
        } catch (Throwable t) { dir = null; }
    }

    private static String safeName(String s) {
        return s == null ? "_" : s.replaceAll("[^A-Za-z0-9_\\-]", "_");
    }

    // ------------------------------------------------------------------ ملفات
    private static String read(String name) {
        File d = dir;
        if (d == null) return null;
        File f = new File(d, name);
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(256, f.length()));
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (Throwable t) { return null; }
    }

    private static void writeAsync(final String name, final String data) {
        final File d = dir;
        if (d == null) return;
        try {
            IO.execute(() -> {
                try {
                    File tmp = new File(d, name + ".tmp");
                    try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(data.getBytes("UTF-8")); }
                    File f = new File(d, name);
                    if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f); }
                } catch (Throwable ignored) { }
            });
        } catch (Throwable ignored) { }
    }

    private static JSONObject readObj(String name) {
        try { String s = read(name); return s == null ? null : new JSONObject(s); } catch (Throwable t) { return null; }
    }

    private static JSONArray readArr(String name) {
        try { String s = read(name); return s == null ? null : new JSONArray(s); } catch (Throwable t) { return null; }
    }

    // ------------------------------------------------------------------ بيانات المستخدم
    /** null إن لم يُحفظ شيء بعد. الحقل "_t" = وقت آخر مزامنة ناجحة مع السيرفر (ms). */
    static JSONObject getMyProfile() { return readObj("me.json"); }

    /** serverSync=true: البيانات جاءت من السيرفر الآن فيُحدَّث "_t"؛ وإلا يبقى الوقت القديم. */
    static void putMyProfile(JSONObject j, boolean serverSync) {
        try {
            long t = System.currentTimeMillis();
            if (!serverSync) {
                JSONObject old = getMyProfile();
                t = old != null ? old.optLong("_t", 0L) : 0L;
            }
            j.put("_t", t);
            writeAsync("me.json", j.toString());
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ قائمة المحادثات
    static JSONArray getChats() { return readArr("chats.json"); }

    static void putChats(JSONArray a) {
        try { writeAsync("chats.json", a.toString()); } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ رسائل كل محادثة
    static JSONArray getMessages(String chatId) { return readArr("msgs_" + safeName(chatId) + ".json"); }

    /** يحفظ آخر MAX_MSGS عنصر فقط (المصفوفة مرتبة تصاعدياً بالوقت). */
    static void putMessages(String chatId, JSONArray a) {
        try {
            JSONArray out = a;
            if (a.length() > MAX_MSGS) {
                out = new JSONArray();
                for (int i = a.length() - MAX_MSGS; i < a.length(); i++) out.put(a.get(i));
            }
            writeAsync("msgs_" + safeName(chatId) + ".json", out.toString());
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ مسح (تسجيل الخروج)
    static void clearAll() {
        final File d = dir;
        if (d == null) return;
        try {
            IO.execute(() -> {
                try {
                    File[] fs = d.listFiles();
                    if (fs != null) for (File f : fs) f.delete();
                } catch (Throwable ignored) { }
            });
        } catch (Throwable ignored) { }
    }
}
