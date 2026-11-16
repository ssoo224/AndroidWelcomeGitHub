package com.babylon.chat.iq;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Map;
import java.util.Set;

/**
 * ترحيل لمرة واحدة من التخزين القديم (SharedPreferences + local_cache + مجلدات الوسائط المتفرقة) إلى التخزين الجديد،
 * ثم حذف القديم من الجهاز. لا يُستخدم هذا الصنف في أي مكان آخر.
 */
final class LegacyStorage {
    private LegacyStorage() { }

    private static boolean isSettingsPrefs(String n) {
        return n.startsWith("babylon_") && !n.equals("babylon_ghost")
                || n.startsWith("story_seen_") || n.equals("story_react") || n.equals("crash_log");
    }

    private static File prefsDir(Context c) {
        return new File(c.getApplicationInfo().dataDir, "shared_prefs");
    }

    /** ينسخ إعدادات SharedPreferences القديمة إلى ملف JSON الجديد (دون أعلام خزن الرسائل القديم cache_complete_*). */
    static void importOldPrefs(Context c, JSONObject root) {
        File[] fs = prefsDir(c).listFiles();
        if (fs == null) return;
        for (File f : fs) {
            String fn = f.getName();
            if (!fn.endsWith(".xml")) continue;
            String name = fn.substring(0, fn.length() - 4);
            if (!isSettingsPrefs(name)) continue;
            try {
                SharedPreferences sp = c.getSharedPreferences(name, Context.MODE_PRIVATE);
                JSONObject sec = root.optJSONObject(name);
                if (sec == null) { sec = new JSONObject(); root.put(name, sec); }
                for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
                    String k = e.getKey();
                    if (k.startsWith("cache_complete_")) continue;
                    Object v = e.getValue();
                    if (v instanceof Set) {
                        JSONArray a = new JSONArray();
                        for (Object o : (Set<?>) v) a.put(String.valueOf(o));
                        sec.put(k, a);
                    } else if (v instanceof Float) sec.put(k, ((Float) v).doubleValue());
                    else if (v != null) sec.put(k, v);
                }
            } catch (Throwable ignored) { }
        }
    }

    /** يحذف الملفات القديمة بعد التأكد من حفظ الإعدادات الجديدة، وينقل مجلدات الوسائط القديمة إلى media/. */
    static void cleanup(final Context c) {
        final Context a = c.getApplicationContext();
        // نقل مجلدات الوسائط القديمة إلى أماكنها الجديدة (صور، أصوات) أو حذفها
        File fd = a.getFilesDir();
        MediaFiles.moveOrDrop(new File(fd, "avatars"), MediaFiles.avatars());
        MediaFiles.moveOrDrop(new File(fd, "voice"), MediaFiles.voice());
        MediaFiles.moveOrDrop(new File(fd, "emoji_noto"), MediaFiles.emoji());
        // بعد أن يُكتب settings.json على القرص نحذف الباقي في الخيط الخلفي نفسه
        AppSettings.runAfterSave(() -> {
            try {
                MediaFiles.deleteTree(new File(a.getFilesDir(), "local_cache"));   // خزن JSON القديم للرسائل والمحادثات
                File[] fs = prefsDir(a).listFiles();
                if (fs != null) for (File f : fs) {
                    String fn = f.getName();
                    if (!fn.endsWith(".xml")) continue;
                    String name = fn.substring(0, fn.length() - 4);
                    if (!isSettingsPrefs(name)) continue;
                    try { a.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit(); } catch (Throwable ignored) { }
                    f.delete();
                }
                File[] cf = a.getCacheDir().listFiles();   // ملفات تشغيل الصوت المؤقتة القديمة
                if (cf != null) for (File f : cf) if (f.getName().startsWith("play_")) f.delete();
                File[] pf = MediaFiles.played().listFiles();   // ملفات تشغيل الصوت المؤقتة: تُحذف بعد 3 أيام
                long limit = System.currentTimeMillis() - 3L * 24 * 3600 * 1000;
                if (pf != null) for (File f : pf) if (f.lastModified() < limit) f.delete();
            } catch (Throwable ignored) { }
        });
    }

    /** الرسائل الوهمية القديمة (SharedPreferences "babylon_ghost") تُنقل إلى جدول messages ثم يُحذف الملف القديم. */
    static void migrateGhosts(Context c, String uid) {
        try {
            File f = new File(prefsDir(c), "babylon_ghost.xml");
            if (!f.isFile()) return;
            SharedPreferences sp = c.getSharedPreferences("babylon_ghost", Context.MODE_PRIVATE);
            for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
                String chatId = e.getKey();
                if (uid != null && !chatId.contains(uid)) continue;   // معرّف المحادثة = uid1_uid2
                try {
                    JSONArray arr = new JSONArray(String.valueOf(e.getValue()));
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o != null && !o.optString("id").isEmpty()) LocalDb.importGhost(chatId, o);
                    }
                    sp.edit().remove(chatId).commit();
                } catch (Throwable ignored) { }
            }
            if (sp.getAll().isEmpty()) { sp.edit().clear().commit(); f.delete(); }
        } catch (Throwable ignored) { }
    }
}
