package com.babylon.chat.iq;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * إعدادات التطبيق في ملف JSON واحد داخل ذاكرة التطبيق (filesDir/settings.json) بدل SharedPreferences.
 * الشكل: { "اسم_المجموعة": { "المفتاح": قيمة, ... }, ... } — كل مجموعة تقابل اسم SharedPreferences القديم.
 * الواجهة {@link #prefs(Context, String)} تعيد كائناً يطبّق SharedPreferences فلا تتغير طريقة القراءة/الكتابة في بقية الشيفرة.
 * القراءة من الذاكرة، والكتابة إلى القرص في خيط خلفي (ذرّياً عبر ملف مؤقت).
 */
final class AppSettings {
    private AppSettings() { }

    private static final String FILE = "settings.json";
    private static final Object LOCK = new Object();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final HashMap<String, Section> sections = new HashMap<>();
    private static JSONObject root = new JSONObject();
    private static File file;
    private static boolean ready = false;

    /** يُستدعى من Application.onCreate (ويُستدعى تلقائياً عند أول prefs()). */
    static void init(Context c) {
        synchronized (LOCK) {
            if (ready) return;
            Context a = c.getApplicationContext();
            file = new File(a.getFilesDir(), FILE);
            boolean existed = file.isFile();
            if (existed) {
                try {
                    String s = readFile(file);
                    if (s != null && !s.isEmpty()) root = new JSONObject(s);
                } catch (Throwable t) { root = new JSONObject(); }
            }
            ready = true;
            if (!existed) {
                try { LegacyStorage.importOldPrefs(a, root); } catch (Throwable ignored) { }
                saveLocked();
            }
        }
    }

    static SharedPreferences prefs(Context c, String name) {
        init(c);
        synchronized (LOCK) {
            Section s = sections.get(name);
            if (s == null) { s = new Section(name); sections.put(name, s); }
            return s;
        }
    }

    // ------------------------------------------------------------------ قرص
    private static String readFile(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(256, f.length()));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (Throwable t) { return null; }
    }

    private static void saveLocked() {
        final File f = file;
        if (f == null) return;
        final String data;
        try { data = root.toString(); } catch (Throwable t) { return; }
        try {
            IO.execute(() -> {
                try {
                    File tmp = new File(f.getPath() + ".tmp");
                    try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(data.getBytes("UTF-8")); }
                    if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f); }
                } catch (Throwable ignored) { }
            });
        } catch (Throwable ignored) { }
    }

    /** ينفّذ المهمة في خيط الكتابة نفسه بعد أن تُكتب كل التعديلات المعلّقة على القرص. */
    static void runAfterSave(Runnable r) {
        try { IO.execute(r); } catch (Throwable ignored) { }
    }

    /** يمسح كل الإعدادات (اختياري). */
    static void clearAll() {
        synchronized (LOCK) { root = new JSONObject(); saveLocked(); }
    }

    private static JSONObject obj(String name) {
        JSONObject o = root.optJSONObject(name);
        if (o == null) { o = new JSONObject(); try { root.put(name, o); } catch (Throwable ignored) { } }
        return o;
    }

    // ------------------------------------------------------------------ واجهة SharedPreferences
    private static final class Section implements SharedPreferences {
        final String name;
        Section(String name) { this.name = name; }

        @Override public Map<String, ?> getAll() {
            synchronized (LOCK) {
                HashMap<String, Object> m = new HashMap<>();
                JSONObject o = obj(name);
                Iterator<String> it = o.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    Object v = o.opt(k);
                    if (v instanceof JSONArray) v = toSet((JSONArray) v);
                    if (v != null && v != JSONObject.NULL) m.put(k, v);
                }
                return m;
            }
        }

        private static Set<String> toSet(JSONArray a) {
            HashSet<String> s = new HashSet<>();
            for (int i = 0; i < a.length(); i++) s.add(a.optString(i));
            return s;
        }

        @Override public String getString(String key, String def) {
            synchronized (LOCK) { Object v = obj(name).opt(key); return v instanceof String ? (String) v : def; }
        }
        @Override public Set<String> getStringSet(String key, Set<String> def) {
            synchronized (LOCK) { Object v = obj(name).opt(key); return v instanceof JSONArray ? toSet((JSONArray) v) : def; }
        }
        @Override public int getInt(String key, int def) {
            synchronized (LOCK) { Object v = obj(name).opt(key); return v instanceof Number ? ((Number) v).intValue() : def; }
        }
        @Override public long getLong(String key, long def) {
            synchronized (LOCK) { Object v = obj(name).opt(key); return v instanceof Number ? ((Number) v).longValue() : def; }
        }
        @Override public float getFloat(String key, float def) {
            synchronized (LOCK) { Object v = obj(name).opt(key); return v instanceof Number ? ((Number) v).floatValue() : def; }
        }
        @Override public boolean getBoolean(String key, boolean def) {
            synchronized (LOCK) { Object v = obj(name).opt(key); return v instanceof Boolean ? (Boolean) v : def; }
        }
        @Override public boolean contains(String key) {
            synchronized (LOCK) { return obj(name).has(key); }
        }
        @Override public Editor edit() { return new Ed(name); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }
    }

    private static final Object REMOVE = new Object();

    private static final class Ed implements SharedPreferences.Editor {
        final String name;
        final HashMap<String, Object> changes = new HashMap<>();
        boolean clear = false;
        Ed(String name) { this.name = name; }

        @Override public SharedPreferences.Editor putString(String k, String v) { changes.put(k, v == null ? REMOVE : v); return this; }
        @Override public SharedPreferences.Editor putStringSet(String k, Set<String> v) {
            if (v == null) { changes.put(k, REMOVE); return this; }
            JSONArray a = new JSONArray();
            for (String s : v) a.put(s);
            changes.put(k, a);
            return this;
        }
        @Override public SharedPreferences.Editor putInt(String k, int v) { changes.put(k, v); return this; }
        @Override public SharedPreferences.Editor putLong(String k, long v) { changes.put(k, v); return this; }
        @Override public SharedPreferences.Editor putFloat(String k, float v) { changes.put(k, (double) v); return this; }
        @Override public SharedPreferences.Editor putBoolean(String k, boolean v) { changes.put(k, v); return this; }
        @Override public SharedPreferences.Editor remove(String k) { changes.put(k, REMOVE); return this; }
        @Override public SharedPreferences.Editor clear() { clear = true; changes.clear(); return this; }

        @Override public boolean commit() { flush(); return true; }
        @Override public void apply() { flush(); }

        private void flush() {
            synchronized (LOCK) {
                JSONObject o = obj(name);
                try {
                    if (clear) {
                        root.put(name, new JSONObject());
                        o = obj(name);
                    }
                    for (Map.Entry<String, Object> e : changes.entrySet()) {
                        if (e.getValue() == REMOVE) o.remove(e.getKey());
                        else o.put(e.getKey(), e.getValue());
                    }
                } catch (Throwable ignored) { }
                saveLocked();
            }
        }
    }
}
