package com.babylon.chat.iq;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * قاعدة SQLite المحلية (داخل ذاكرة التطبيق) — تحل محل LocalCache وGhostStore:
 *   messages       الرسائل (آخر 50 لكل محادثة + الرسائل الوهمية ghost=1). الصور/الصوت تُحفظ ملفات في MediaFiles ويُخزَّن مسارها فقط.
 *   conversations  قائمة المحادثات
 *   contacts       جهات الدردشة (الأسماء المخصصة)
 *   profile        بيانات المستخدم (JSON) ووقت آخر مزامنة
 *   chat_meta      هل خزن المحادثة «كامل» (يحوي كل رسائلها الظاهرة)
 * الكتابة في خيط خلفي واحد بالترتيب، والقراءة مباشرة.
 */
final class LocalDb {

    static final int MAX_MSGS = 50;     // آخر 50 رسالة لكل محادثة تُعرض من الجهاز فوراً
    private static final int MAX_GHOST = 300;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static volatile SQLiteDatabase db;
    private static String curUid;

    private LocalDb() { }

    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context c, String name) { super(c, name, null, 1); }

        @Override public void onCreate(SQLiteDatabase d) {
            d.execSQL("CREATE TABLE messages(" +
                    "chat_id TEXT NOT NULL, id TEXT NOT NULL, ghost INTEGER NOT NULL DEFAULT 0, " +
                    "type TEXT, sender TEXT, text TEXT, ts INTEGER NOT NULL DEFAULT 0, " +
                    "media_path TEXT, data_prefix TEXT, mime TEXT, duration INTEGER, " +
                    "file_url TEXT, file_name TEXT, file_size INTEGER, " +
                    "is_read INTEGER, forwarded INTEGER, reactions TEXT, extra TEXT, " +
                    "PRIMARY KEY(chat_id, id))");
            d.execSQL("CREATE INDEX idx_messages_chat_ts ON messages(chat_id, ts)");
            d.execSQL("CREATE TABLE conversations(uid TEXT PRIMARY KEY, cid TEXT, name TEXT, last TEXT, unread TEXT, ts INTEGER, pos INTEGER)");
            d.execSQL("CREATE TABLE contacts(uid TEXT PRIMARY KEY, name TEXT NOT NULL)");
            d.execSQL("CREATE TABLE profile(id INTEGER PRIMARY KEY, json TEXT, synced_at INTEGER)");
            d.execSQL("CREATE TABLE chat_meta(chat_id TEXT PRIMARY KEY, complete INTEGER NOT NULL DEFAULT 0)");
        }

        @Override public void onUpgrade(SQLiteDatabase d, int o, int n) { }
    }

    /** يُستدعى مرة واحدة بعد معرفة uid؛ لكل حساب قاعدة خاصة حتى لا تختلط بيانات الحسابات. */
    static synchronized void init(Context c, String uid) {
        try {
            Context a = c.getApplicationContext();
            MediaFiles.init(a);
            if (db != null && uid != null && uid.equals(curUid)) return;
            SQLiteDatabase old = db;
            db = new Helper(a, "babylon_" + safeName(uid) + ".db").getWritableDatabase();
            curUid = uid;
            if (old != null) { try { old.close(); } catch (Throwable ignored) { } }
            LegacyStorage.migrateGhosts(a, uid);
        } catch (Throwable t) { db = null; }
    }

    private static String safeName(String s) {
        return s == null ? "_" : s.replaceAll("[^A-Za-z0-9_\\-]", "_");
    }

    private static void run(Runnable r) {
        try { IO.execute(r); } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ تحويل JSON <-> صف
    private static final HashSet<String> HANDLED = new HashSet<>(Arrays.asList(
            "id", "type", "text", "data", "duration", "fileUrl", "fileName", "fileSize",
            "sender", "timestamp", "read", "forwarded", "reactions", "mime"));

    private static void insertRow(SQLiteDatabase d, String chatId, JSONObject o, int ghost, String reusePath, String reusePfx) throws Exception {
        String id = o.optString("id");
        String type = o.optString("type", "text");
        ContentValues cv = new ContentValues();
        cv.put("chat_id", chatId);
        cv.put("id", id);
        cv.put("ghost", ghost);
        cv.put("type", type);
        if (o.has("sender")) cv.put("sender", o.optString("sender"));
        if (o.has("text")) cv.put("text", o.optString("text"));
        cv.put("ts", o.optLong("timestamp", 0L));
        if (o.has("duration")) cv.put("duration", o.optLong("duration", 0L));
        if (o.has("fileUrl")) cv.put("file_url", o.optString("fileUrl"));
        if (o.has("fileName")) cv.put("file_name", o.optString("fileName"));
        if (o.has("fileSize")) cv.put("file_size", o.optLong("fileSize", 0L));
        if (o.has("read")) cv.put("is_read", o.optBoolean("read") ? 1 : 0);
        if (o.has("forwarded")) cv.put("forwarded", o.optBoolean("forwarded") ? 1 : 0);
        if (o.has("mime")) cv.put("mime", o.optString("mime"));
        JSONObject rx = o.optJSONObject("reactions");
        if (rx != null && rx.length() > 0) cv.put("reactions", rx.toString());

        JSONObject extra = new JSONObject();
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (!HANDLED.contains(k)) extra.put(k, o.get(k));
        }
        if (o.has("data")) {
            String data = o.optString("data", "");
            String path = reusePath, pfx = reusePfx;
            File rf = path == null ? null : MediaFiles.resolve(path);
            if (rf == null || !rf.isFile()) {
                path = null; pfx = null;
                if (!data.startsWith("http") && !data.isEmpty()) {
                    path = MediaFiles.saveMessageMedia(chatId, id, type, o.optString("mime", null), data);
                    pfx = data.startsWith("data:") && data.contains(",") ? data.substring(0, data.indexOf(',') + 1) : "";
                }
            }
            if (path != null) { cv.put("media_path", path); cv.put("data_prefix", pfx == null ? "" : pfx); }
            else extra.put("data", data);   // لا يمكن حفظه ملفاً (رابط/نص غير base64): يبقى كما هو
        }
        if (extra.length() > 0) cv.put("extra", extra.toString());
        d.insertWithOnConflict("messages", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private static final String[] MSG_COLS = {"id", "type", "sender", "text", "ts", "media_path", "data_prefix", "mime", "duration",
            "file_url", "file_name", "file_size", "is_read", "forwarded", "reactions", "extra"};

    /** يعيد null إن كان الصف وسائط وملفه مفقود (فلا يُعرض فارغاً). */
    private static JSONObject rowToJson(Cursor c) {
        try {
            JSONObject o = new JSONObject();
            JSONObject extra = c.isNull(15) ? null : new JSONObject(c.getString(15));
            o.put("id", c.getString(0));
            o.put("type", c.isNull(1) ? "text" : c.getString(1));
            if (!c.isNull(2)) o.put("sender", c.getString(2));
            if (!c.isNull(3)) o.put("text", c.getString(3));
            long ts = c.getLong(4);
            if (ts > 0) o.put("timestamp", ts);
            if (!c.isNull(5)) {
                String b64 = MediaFiles.readAsBase64(c.getString(5));
                if (b64 == null) return null;
                o.put("data", (c.isNull(6) ? "" : c.getString(6)) + b64);
            }
            if (!c.isNull(7)) o.put("mime", c.getString(7));
            if (!c.isNull(8)) o.put("duration", c.getLong(8));
            if (!c.isNull(9)) o.put("fileUrl", c.getString(9));
            if (!c.isNull(10)) o.put("fileName", c.getString(10));
            if (!c.isNull(11)) o.put("fileSize", c.getLong(11));
            if (!c.isNull(12)) o.put("read", c.getInt(12) != 0);
            if (!c.isNull(13)) o.put("forwarded", c.getInt(13) != 0);
            if (!c.isNull(14)) o.put("reactions", new JSONObject(c.getString(14)));
            if (extra != null) {
                Iterator<String> it = extra.keys();
                while (it.hasNext()) { String k = it.next(); if (!o.has(k)) o.put(k, extra.get(k)); }
            }
            return o;
        } catch (Throwable t) { return null; }
    }

    private static String colsSql() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < MSG_COLS.length; i++) { if (i > 0) sb.append(','); sb.append(MSG_COLS[i]); }
        return sb.toString();
    }

    private static void deleteMediaFiles(List<String> paths) {
        for (String p : paths) MediaFiles.delete(p);
    }

    // ------------------------------------------------------------------ بيانات المستخدم
    /** null إن لم يُحفظ شيء بعد. الحقل "_t" = وقت آخر مزامنة ناجحة مع السيرفر (ms). */
    static JSONObject getMyProfile() {
        SQLiteDatabase d = db;
        if (d == null) return null;
        try (Cursor c = d.rawQuery("SELECT json FROM profile WHERE id=1", null)) {
            if (c.moveToFirst() && !c.isNull(0)) return new JSONObject(c.getString(0));
        } catch (Throwable ignored) { }
        return null;
    }

    /** serverSync=true: البيانات جاءت من السيرفر الآن فيُحدَّث "_t"؛ وإلا يبقى الوقت القديم. */
    static void putMyProfile(JSONObject j, boolean serverSync) {
        try {
            long t = System.currentTimeMillis();
            if (!serverSync) {
                JSONObject old = getMyProfile();
                t = old != null ? old.optLong("_t", 0L) : 0L;
            }
            j.put("_t", t);
            final String s = j.toString();
            final long ft = t;
            run(() -> {
                SQLiteDatabase d = db;
                if (d == null) return;
                try {
                    ContentValues cv = new ContentValues();
                    cv.put("id", 1); cv.put("json", s); cv.put("synced_at", ft);
                    d.insertWithOnConflict("profile", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                } catch (Throwable ignored) { }
            });
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ قائمة المحادثات
    static JSONArray getChats() {
        SQLiteDatabase d = db;
        if (d == null) return null;
        try (Cursor c = d.rawQuery("SELECT uid,cid,name,last,unread,ts FROM conversations ORDER BY pos", null)) {
            if (c.getCount() == 0) return null;
            JSONArray a = new JSONArray();
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("uid", c.getString(0));
                o.put("cid", c.isNull(1) ? "" : c.getString(1));
                o.put("name", c.isNull(2) ? "?" : c.getString(2));
                o.put("last", c.isNull(3) ? "" : c.getString(3));
                o.put("unread", c.isNull(4) ? "0" : c.getString(4));
                o.put("ts", c.getLong(5));
                a.put(o);
            }
            return a;
        } catch (Throwable t) { return null; }
    }

    static void putChats(final JSONArray a) {
        if (a == null) return;
        run(() -> {
            SQLiteDatabase d = db;
            if (d == null) return;
            try {
                d.beginTransaction();
                try {
                    d.delete("conversations", null, null);
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject o = a.optJSONObject(i);
                        if (o == null || o.optString("uid").isEmpty()) continue;
                        ContentValues cv = new ContentValues();
                        cv.put("uid", o.optString("uid"));
                        cv.put("cid", o.optString("cid", ""));
                        cv.put("name", o.optString("name", "?"));
                        cv.put("last", o.optString("last", ""));
                        cv.put("unread", o.optString("unread", "0"));
                        cv.put("ts", o.optLong("ts", 0L));
                        cv.put("pos", i);
                        d.insertWithOnConflict("conversations", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                    }
                    d.setTransactionSuccessful();
                } finally { d.endTransaction(); }
            } catch (Throwable ignored) { }
        });
    }

    // ------------------------------------------------------------------ جهات الدردشة
    static HashMap<String, String> getContacts() {
        HashMap<String, String> m = new HashMap<>();
        SQLiteDatabase d = db;
        if (d == null) return m;
        try (Cursor c = d.rawQuery("SELECT uid,name FROM contacts", null)) {
            while (c.moveToNext()) m.put(c.getString(0), c.getString(1));
        } catch (Throwable ignored) { }
        return m;
    }

    /** يستبدل كل الجهات بالقائمة الواردة (تُستدعى عند وصول لقطة السيرفر). */
    static void putContacts(Map<String, String> src) {
        final HashMap<String, String> copy = new HashMap<>(src);
        run(() -> {
            SQLiteDatabase d = db;
            if (d == null) return;
            try {
                d.beginTransaction();
                try {
                    d.delete("contacts", null, null);
                    for (Map.Entry<String, String> e : copy.entrySet()) {
                        ContentValues cv = new ContentValues();
                        cv.put("uid", e.getKey()); cv.put("name", e.getValue());
                        d.insertWithOnConflict("contacts", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                    }
                    d.setTransactionSuccessful();
                } finally { d.endTransaction(); }
            } catch (Throwable ignored) { }
        });
    }

    // ------------------------------------------------------------------ رسائل كل محادثة
    /** هل خزن المحادثة «كامل» (يحوي كل رسائلها الظاهرة)؟ */
    static boolean isComplete(String chatId) {
        SQLiteDatabase d = db;
        if (d == null || chatId == null) return false;
        try (Cursor c = d.rawQuery("SELECT complete FROM chat_meta WHERE chat_id=?", new String[]{chatId})) {
            return c.moveToFirst() && c.getInt(0) != 0;
        } catch (Throwable t) { return false; }
    }

    /** الرسائل المحفوظة (تصاعدياً بالوقت) أو null إن لم يُحفظ شيء لهذه المحادثة بعد. */
    static JSONArray getMessages(String chatId) {
        SQLiteDatabase d = db;
        if (d == null || chatId == null) return null;
        try {
            boolean has;
            try (Cursor c = d.rawQuery("SELECT 1 FROM chat_meta WHERE chat_id=?", new String[]{chatId})) { has = c.moveToFirst(); }
            if (!has) return null;
            JSONArray a = new JSONArray();
            try (Cursor c = d.rawQuery("SELECT " + colsSql() + " FROM messages WHERE chat_id=? AND ghost=0 ORDER BY ts, rowid", new String[]{chatId})) {
                while (c.moveToNext()) { JSONObject o = rowToJson(c); if (o != null) a.put(o); }
            }
            return a;
        } catch (Throwable t) { return null; }
    }

    /**
     * يحفظ آخر MAX_MSGS (50) رسالة (المصفوفة مرتبة تصاعدياً بالوقت) ويحدّد هل الخزن كامل.
     * الصور/الصوتيات تُكتب ملفات في مجلدات الوسائط الداخلية، وما لم يعد مستخدماً منها يُحذف.
     */
    static void putMessages(final String chatId, final JSONArray a, final boolean complete) {
        if (chatId == null || a == null) return;
        final int from = Math.max(0, a.length() - MAX_MSGS);
        run(() -> {
            SQLiteDatabase d = db;
            if (d == null) return;
            ArrayList<String> stale = new ArrayList<>();
            try {
                HashMap<String, String[]> old = new HashMap<>();
                try (Cursor c = d.rawQuery("SELECT id,media_path,data_prefix FROM messages WHERE chat_id=? AND ghost=0", new String[]{chatId})) {
                    while (c.moveToNext()) old.put(c.getString(0), new String[]{c.isNull(1) ? null : c.getString(1), c.isNull(2) ? "" : c.getString(2)});
                }
                HashSet<String> keep = new HashSet<>();
                d.beginTransaction();
                try {
                    d.delete("messages", "chat_id=? AND ghost=0", new String[]{chatId});
                    for (int i = from; i < a.length(); i++) {
                        JSONObject o = a.optJSONObject(i);
                        if (o == null || o.optString("id").isEmpty()) continue;
                        String[] r = old.get(o.optString("id"));
                        insertRow(d, chatId, o, 0, r == null ? null : r[0], r == null ? null : r[1]);
                        try (Cursor c = d.rawQuery("SELECT media_path FROM messages WHERE chat_id=? AND id=?", new String[]{chatId, o.optString("id")})) {
                            if (c.moveToFirst() && !c.isNull(0)) keep.add(c.getString(0));
                        }
                    }
                    ContentValues cv = new ContentValues();
                    cv.put("chat_id", chatId); cv.put("complete", complete ? 1 : 0);
                    d.insertWithOnConflict("chat_meta", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                    d.setTransactionSuccessful();
                } finally { d.endTransaction(); }
                for (String[] r : old.values()) if (r[0] != null && !keep.contains(r[0])) stale.add(r[0]);
            } catch (Throwable ignored) { }
            deleteMediaFiles(stale);
        });
    }

    /** نتيجة الدمج: هل بقي الخزن «كاملاً» (يحوي كل رسائل المحادثة الظاهرة). */
    interface MergeDone { void done(boolean stillComplete); }

    /**
     * يضيف رسائل نصية واردة (وصلت والمحادثة مغلقة) إلى خزن محادثة موجود، بالترتيب ومن غير تكرار.
     * otherIds: معرّفات رسائل غير نصية وردت أيضاً: إن لم تكن في الخزن أصلاً يصير الخزن «ناقصاً».
     * إن تجاوز العدد MAX_MSGS يُقصّ الأقدم ويصير الخزن «ناقصاً» (لا يُعرض من الجهاز حتى تُفتح المحادثة وتُحفظ من جديد).
     */
    static void mergeIncoming(final String chatId, final List<JSONObject> add, final List<String> otherIds, final MergeDone cb) {
        if (chatId == null) return;
        run(() -> {
            SQLiteDatabase d = db;
            boolean complete = true;
            ArrayList<String> stale = new ArrayList<>();
            try {
                if (d == null) { if (cb != null) cb.done(false); return; }
                boolean has;
                try (Cursor c = d.rawQuery("SELECT 1 FROM chat_meta WHERE chat_id=?", new String[]{chatId})) { has = c.moveToFirst(); }
                if (!has) { if (cb != null) cb.done(false); return; }
                HashSet<String> ids = new HashSet<>();
                try (Cursor c = d.rawQuery("SELECT id FROM messages WHERE chat_id=? AND ghost=0", new String[]{chatId})) {
                    while (c.moveToNext()) ids.add(c.getString(0));
                }
                if (otherIds != null) for (String id : otherIds) if (!ids.contains(id)) complete = false;
                d.beginTransaction();
                try {
                    if (add != null) for (JSONObject o : add) {
                        String id = o.optString("id");
                        if (id.isEmpty() || ids.contains(id)) continue;
                        insertRow(d, chatId, o, 0, null, null);
                        ids.add(id);
                    }
                    int total = 0;
                    try (Cursor c = d.rawQuery("SELECT COUNT(*) FROM messages WHERE chat_id=? AND ghost=0", new String[]{chatId})) { if (c.moveToFirst()) total = c.getInt(0); }
                    if (total > MAX_MSGS) {   // يُقصّ الأقدم فقط: يبقى الخزن «آخر MAX_MSGS رسالة» صالحاً للعرض الفوري
                        try (Cursor c = d.rawQuery("SELECT id,media_path FROM messages WHERE chat_id=? AND ghost=0 ORDER BY ts, rowid LIMIT " + (total - MAX_MSGS),
                                new String[]{chatId})) {
                            while (c.moveToNext()) {
                                d.delete("messages", "chat_id=? AND id=?", new String[]{chatId, c.getString(0)});
                                if (!c.isNull(1)) stale.add(c.getString(1));
                            }
                        }
                    }
                    if (!complete) {
                        ContentValues cv = new ContentValues();
                        cv.put("chat_id", chatId); cv.put("complete", 0);
                        d.insertWithOnConflict("chat_meta", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                    }
                    d.setTransactionSuccessful();
                } finally { d.endTransaction(); }
            } catch (Throwable t) { complete = false; }
            deleteMediaFiles(stale);
            if (cb != null) cb.done(complete);
        });
    }

    /** يحذف كل ما حُفظ محلياً لمحادثة (رسائلها العادية والوهمية وملفات وسائطها) عند حذفها. */
    static void removeChat(final String chatId) {
        if (chatId == null) return;
        run(() -> {
            SQLiteDatabase d = db;
            if (d == null) return;
            ArrayList<String> files = new ArrayList<>();
            try {
                try (Cursor c = d.rawQuery("SELECT media_path FROM messages WHERE chat_id=? AND media_path IS NOT NULL", new String[]{chatId})) {
                    while (c.moveToNext()) files.add(c.getString(0));
                }
                d.delete("messages", "chat_id=?", new String[]{chatId});
                d.delete("chat_meta", "chat_id=?", new String[]{chatId});
            } catch (Throwable ignored) { }
            deleteMediaFiles(files);
        });
    }

    // ------------------------------------------------------------------ رسائل «وهمية» (من حظره الطرف الآخر)
    /** تُخزَّن على الجهاز فقط ولا تُكتب في Firestore إطلاقاً. */
    static synchronized void addGhost(String chatId, String id, Map<String, Object> msg) {
        SQLiteDatabase d = db;
        if (d == null || chatId == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("id", id);
            for (Map.Entry<String, Object> e : msg.entrySet()) {
                Object v = e.getValue();
                if (v instanceof String || v instanceof Number || v instanceof Boolean) o.put(e.getKey(), v);
            }
            insertRow(d, chatId, o, 1, null, null);
            ArrayList<String> stale = new ArrayList<>();
            try (Cursor c = d.rawQuery("SELECT id,media_path FROM messages WHERE chat_id=? AND ghost=1 ORDER BY ts DESC, rowid DESC LIMIT -1 OFFSET " + MAX_GHOST,
                    new String[]{chatId})) {
                while (c.moveToNext()) {
                    d.delete("messages", "chat_id=? AND id=?", new String[]{chatId, c.getString(0)});
                    if (!c.isNull(1)) stale.add(c.getString(1));
                }
            }
            deleteMediaFiles(stale);
        } catch (Throwable ignored) { }
    }

    static synchronized JSONArray listGhosts(String chatId) {
        JSONArray a = new JSONArray();
        SQLiteDatabase d = db;
        if (d == null || chatId == null) return a;
        try (Cursor c = d.rawQuery("SELECT " + colsSql() + " FROM messages WHERE chat_id=? AND ghost=1 ORDER BY ts, rowid", new String[]{chatId})) {
            while (c.moveToNext()) { JSONObject o = rowToJson(c); if (o != null) a.put(o); }
        } catch (Throwable ignored) { }
        return a;
    }

    /** آخر رسالة وهمية: {المعاينة، الوقت} أو null. */
    static synchronized String[] lastGhost(String chatId) {
        SQLiteDatabase d = db;
        if (d == null || chatId == null) return null;
        try (Cursor c = d.rawQuery("SELECT type,mime,text,ts FROM messages WHERE chat_id=? AND ghost=1 ORDER BY ts DESC, rowid DESC LIMIT 1", new String[]{chatId})) {
            if (!c.moveToFirst()) return null;
            String t = c.isNull(0) ? "text" : c.getString(0);
            String mime = c.isNull(1) ? "" : c.getString(1);
            String pv = "image".equals(t) ? ("image/gif".equals(mime) ? "🎞 GIF" : "📷 صورة") : "audio".equals(t) ? "🎤 رسالة صوتية" : (c.isNull(2) ? "" : c.getString(2));
            return new String[]{pv, String.valueOf(c.getLong(3))};
        } catch (Throwable t) { return null; }
    }

    // ------------------------------------------------------------------ مسح (تسجيل الخروج)
    static void clearAll() {
        run(() -> {
            SQLiteDatabase d = db;
            if (d == null) return;
            ArrayList<String> files = new ArrayList<>();
            try {
                try (Cursor c = d.rawQuery("SELECT media_path FROM messages WHERE media_path IS NOT NULL", null)) {
                    while (c.moveToNext()) files.add(c.getString(0));
                }
                d.beginTransaction();
                try {
                    for (String t : new String[]{"messages", "conversations", "contacts", "profile", "chat_meta"}) d.delete(t, null, null);
                    d.setTransactionSuccessful();
                } finally { d.endTransaction(); }
            } catch (Throwable ignored) { }
            deleteMediaFiles(files);
        });
    }

    // ------------------------------------------------------------------ للترحيل من الخزن القديم
    /** يدرج رسالة وهمية قديمة (من SharedPreferences) إن لم تكن موجودة. */
    static synchronized void importGhost(String chatId, JSONObject o) {
        SQLiteDatabase d = db;
        if (d == null) return;
        try { insertRow(d, chatId, o, 1, null, null); } catch (Throwable ignored) { }
    }
}
