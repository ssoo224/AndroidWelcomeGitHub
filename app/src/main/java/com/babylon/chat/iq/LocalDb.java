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
 *   messages       كل رسائل كل محادثة (بلا حد) + الرسائل الوهمية ghost=1. الصور/الصوت تُحفظ ملفات في MediaFiles ويُخزَّن مسارها فقط.
 *   conversations  قائمة المحادثات
 *   contacts       جهات الدردشة (الأسماء المخصصة)
 *   profile        بيانات المستخدم (JSON) ووقت آخر مزامنة
 *   chat_meta      complete: نُزّل كل تاريخ المحادثة مرة واحدة؛ synced_ts: آخر وقت أكّده السيرفر (منه يبدأ الاستماع عند الدخول)
 * الكتابة في خيط خلفي واحد بالترتيب، والقراءة مباشرة.
 */
final class LocalDb {

    /** عند الدخول تُعاد مزامنة آخر هذا العدد من الرسائل (تفاعلات/قراءة/حذف رسائل حديثة)، وما قبلها يُقرأ من الجهاز فقط. */
    static final int RESYNC_WINDOW = 50;
    private static final int MAX_GHOST = 300;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static volatile SQLiteDatabase db;
    private static String curUid;

    private LocalDb() { }

    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context c, String name) { super(c, name, null, 2); }

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
            d.execSQL(CHAT_META_SQL);
        }

        @Override public void onUpgrade(SQLiteDatabase d, int o, int n) {
            if (o < 2) {   // الإصدار 1 كان يحفظ آخر 50 رسالة فقط: يُمسح ليُبنى الخزن الكامل مرة واحدة عند أول دخول لكل محادثة
                try {
                    ArrayList<String> files = new ArrayList<>();
                    try (Cursor c = d.rawQuery("SELECT media_path FROM messages WHERE ghost=0 AND media_path IS NOT NULL", null)) {
                        while (c.moveToNext()) files.add(c.getString(0));
                    }
                    d.delete("messages", "ghost=0", null);
                    d.execSQL("DROP TABLE IF EXISTS chat_meta");
                    d.execSQL(CHAT_META_SQL);
                    for (String p : files) MediaFiles.delete(p);
                } catch (Throwable ignored) { }
            }
        }
    }

    private static final String CHAT_META_SQL =
            "CREATE TABLE chat_meta(chat_id TEXT PRIMARY KEY, complete INTEGER NOT NULL DEFAULT 0, synced_ts INTEGER NOT NULL DEFAULT 0)";

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

    /** يعيد المسار النسبي لملف الوسائط المحفوظ (أو null). */
    private static String insertRow(SQLiteDatabase d, String chatId, JSONObject o, int ghost, String reusePath, String reusePfx) throws Exception {
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
        String savedPath = null;
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
            if (path != null) { cv.put("media_path", path); cv.put("data_prefix", pfx == null ? "" : pfx); savedPath = path; }
            else extra.put("data", data);   // لا يمكن حفظه ملفاً (رابط/نص غير base64): يبقى كما هو
        }
        if (extra.length() > 0) cv.put("extra", extra.toString());
        d.insertWithOnConflict("messages", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        return savedPath;
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

    /**
     * -1: خزن المحادثة غير كامل (أول دخول، أو بعد الترقية) فيُجلب كل شيء من السيرفر مرة واحدة.
     * غير ذلك: الوقت الذي يبدأ منه الاستماع عند الدخول = الأقدم بين «آخر مزامنة أكّدها السيرفر» و«وقت الرسالة رقم 50 من الآخر»،
     * فيصل كل جديد أثناء الغياب + تعديلات آخر 50 رسالة (تفاعل/قراءة/حذف). 0 = المحادثة قصيرة: اسأل عن كلها.
     */
    static long resumeStart(String chatId) {
        SQLiteDatabase d = db;
        if (d == null || chatId == null) return -1L;
        try {
            long synced;
            try (Cursor c = d.rawQuery("SELECT complete, synced_ts FROM chat_meta WHERE chat_id=?", new String[]{chatId})) {
                if (!c.moveToFirst() || c.getInt(0) == 0) return -1L;
                synced = c.getLong(1);
            }
            if (synced <= 0) return -1L;
            try (Cursor c = d.rawQuery("SELECT ts FROM messages WHERE chat_id=? AND ghost=0 ORDER BY ts DESC, id DESC LIMIT 1 OFFSET " + (RESYNC_WINDOW - 1), new String[]{chatId})) {
                if (!c.moveToFirst()) return 0L;
                return Math.max(0L, Math.min(synced, c.getLong(0)));
            }
        } catch (Throwable t) { return -1L; }
    }

    /** صفحة رسائل (تصاعدياً بالوقت) + مؤشر الصفحة الأقدم التالية. */
    static final class Page {
        final JSONArray msgs = new JSONArray();
        long curTs;        // وقت أقدم صف قُرئ (للصفحة التالية)
        String curId;      // معرّفه
        boolean more;      // هل يوجد أقدم منه؟
    }

    /** قراءة خفيفة (بلا وسائط) لآخر رسائل المحادثة لرسم هيكل التحميل بنفس جهات وأحجام الرسائل: صفوف {sender, type, طول النص} تصاعدياً. */
    static ArrayList<Object[]> skeletonRows(String chatId, int limit) {
        ArrayList<Object[]> out = new ArrayList<>();
        SQLiteDatabase d = db;
        if (d == null || chatId == null) return out;
        try (Cursor c = d.rawQuery("SELECT sender,type,text FROM messages WHERE chat_id=? AND ghost=0 ORDER BY ts DESC, id DESC LIMIT " + limit, new String[]{chatId})) {
            while (c.moveToNext()) {
                String tx = c.isNull(2) ? "" : c.getString(2);
                out.add(0, new Object[]{c.isNull(0) ? "" : c.getString(0), c.isNull(1) ? "text" : c.getString(1), Integer.valueOf(tx.length())});
            }
        } catch (Throwable ignored) { }
        return out;
    }

    /**
     * الرسائل الأقدم من (beforeTs, beforeId) — أو الأحدث إن كان beforeId=null — بحد limit، مرتبة تصاعدياً.
     * القراءة على صفحات تمنع تحميل آلاف الصور في الذاكرة دفعة واحدة؛ ملف الوسائط يُقرأ فقط للصفحة المطلوبة.
     */
    static Page messagesBefore(String chatId, long beforeTs, String beforeId, int limit) {
        SQLiteDatabase d = db;
        Page pg = new Page();
        if (d == null || chatId == null) return pg;
        try {
            String sql = "SELECT " + colsSql() + " FROM messages WHERE chat_id=? AND ghost=0"
                    + (beforeId == null ? "" : " AND (ts < ? OR (ts = ? AND id < ?))")
                    + " ORDER BY ts DESC, id DESC LIMIT " + limit;
            String[] args = beforeId == null ? new String[]{chatId}
                    : new String[]{chatId, String.valueOf(beforeTs), String.valueOf(beforeTs), beforeId};
            ArrayList<JSONObject> rev = new ArrayList<>();
            int raw = 0;
            try (Cursor c = d.rawQuery(sql, args)) {
                while (c.moveToNext()) {
                    raw++;
                    pg.curTs = c.getLong(4); pg.curId = c.getString(0);
                    JSONObject o = rowToJson(c);
                    if (o != null) rev.add(o);
                }
            }
            for (int i = rev.size() - 1; i >= 0; i--) pg.msgs.put(rev.get(i));
            pg.more = raw >= limit;
        } catch (Throwable ignored) { }
        return pg;
    }

    /**
     * أول تنزيل كامل لمحادثة من السيرفر: يحفظ كل الرسائل على دفعات (بلا حد 50) ثم يعلّم الخزن «كاملاً».
     * syncedTs = أحدث وقت رسالة أكّده السيرفر؛ منه يبدأ الاستماع في المرات القادمة.
     */
    static void putAllMessages(final String chatId, final List<JSONObject> all, final long syncedTs) {
        if (chatId == null || all == null) return;
        run(() -> {
            SQLiteDatabase d = db;
            if (d == null) return;
            ArrayList<String> stale = new ArrayList<>();
            try {
                HashMap<String, String[]> old = new HashMap<>();
                try (Cursor c = d.rawQuery("SELECT id,media_path,data_prefix FROM messages WHERE chat_id=? AND ghost=0", new String[]{chatId})) {
                    while (c.moveToNext()) old.put(c.getString(0), new String[]{c.isNull(1) ? null : c.getString(1), c.isNull(2) ? "" : c.getString(2)});
                }
                HashSet<String> ids = new HashSet<>(), keep = new HashSet<>();
                final int BATCH = 100;   // معاملات صغيرة: لا تُقفل القاعدة طويلاً أثناء كتابة ملفات الصور
                for (int i = 0; i < all.size(); i += BATCH) {
                    d.beginTransaction();
                    try {
                        for (int j = i; j < Math.min(all.size(), i + BATCH); j++) {
                            JSONObject o = all.get(j);
                            String id = o.optString("id");
                            if (id.isEmpty()) continue;
                            String[] r = old.get(id);
                            String path = insertRow(d, chatId, o, 0, r == null ? null : r[0], r == null ? null : r[1]);
                            ids.add(id);
                            if (path != null) keep.add(path);
                        }
                        d.setTransactionSuccessful();
                    } finally { d.endTransaction(); }
                }
                d.beginTransaction();
                try {
                    for (String oid : old.keySet()) {   // ما لم يعد في السيرفر
                        if (!ids.contains(oid)) d.delete("messages", "chat_id=? AND id=? AND ghost=0", new String[]{chatId, oid});
                    }
                    ContentValues cv = new ContentValues();
                    cv.put("chat_id", chatId); cv.put("complete", 1); cv.put("synced_ts", Math.max(0L, syncedTs));
                    d.insertWithOnConflict("chat_meta", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                    d.setTransactionSuccessful();
                } finally { d.endTransaction(); }
                for (String[] r : old.values()) if (r[0] != null && !keep.contains(r[0])) stale.add(r[0]);
            } catch (Throwable ignored) { }
            deleteMediaFiles(stale);
        });
    }

    /**
     * تغييرات لقطة السيرفر بعد التنزيل الكامل: يضيف/يحدّث الرسائل (ويحتفظ بملف الوسائط الموجود)، ويحذف المحذوفة،
     * ويرفع synced_ts (إن كان > 0 أي اللقطة من السيرفر لا من كاش Firestore). لا يفعل شيئاً إن لم يكن الخزن كاملاً.
     */
    static void upsertMessages(final String chatId, final List<JSONObject> add, final List<String> removed, final long syncedTs) {
        upsertMessages(chatId, add, removed, syncedTs, false);
    }

    /** force=true: يحفظ الرسائل حتى لو لم يُعلَّم الخزن «كاملاً» بعد (أثناء التنزيل الأول على مراحل). */
    static void upsertMessages(final String chatId, final List<JSONObject> add, final List<String> removed, final long syncedTs, final boolean force) {
        if (chatId == null) return;
        run(() -> {
            SQLiteDatabase d = db;
            if (d == null) return;
            ArrayList<String> stale = new ArrayList<>();
            try {
                if (!force && !isComplete(chatId)) return;
                d.beginTransaction();
                try {
                    if (add != null) for (JSONObject o : add) {
                        String id = o.optString("id");
                        if (id.isEmpty()) continue;
                        String rp = null, rf = null;
                        if (o.has("data")) {
                            try (Cursor c = d.rawQuery("SELECT media_path,data_prefix FROM messages WHERE chat_id=? AND id=? AND ghost=0", new String[]{chatId, id})) {
                                if (c.moveToFirst() && !c.isNull(0)) { rp = c.getString(0); rf = c.isNull(1) ? "" : c.getString(1); }
                            }
                        }
                        insertRow(d, chatId, o, 0, rp, rf);
                    }
                    if (removed != null) for (String id : removed) {
                        try (Cursor c = d.rawQuery("SELECT media_path FROM messages WHERE chat_id=? AND id=? AND ghost=0", new String[]{chatId, id})) {
                            if (c.moveToFirst() && !c.isNull(0)) stale.add(c.getString(0));
                        }
                        d.delete("messages", "chat_id=? AND id=? AND ghost=0", new String[]{chatId, id});
                    }
                    if (syncedTs > 0) d.execSQL("UPDATE chat_meta SET synced_ts=? WHERE chat_id=? AND synced_ts<?", new Object[]{syncedTs, chatId, syncedTs});
                    d.setTransactionSuccessful();
                } finally { d.endTransaction(); }
            } catch (Throwable ignored) { }
            deleteMediaFiles(stale);
        });
    }

    /** يعلّم خزن المحادثة «كاملاً» بعد انتهاء التنزيل التدريجي للتاريخ في الخلفية. syncedTs = أحدث وقت أكّده السيرفر. */
    static void markComplete(final String chatId, final long syncedTs) {
        if (chatId == null) return;
        run(() -> {
            SQLiteDatabase d = db;
            if (d == null) return;
            try {
                long keep = 0L;
                try (Cursor c = d.rawQuery("SELECT synced_ts FROM chat_meta WHERE chat_id=?", new String[]{chatId})) {
                    if (c.moveToFirst()) keep = c.getLong(0);
                }
                ContentValues cv = new ContentValues();
                cv.put("chat_id", chatId); cv.put("complete", 1); cv.put("synced_ts", Math.max(0L, Math.max(keep, syncedTs)));
                d.insertWithOnConflict("chat_meta", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            } catch (Throwable ignored) { }
        });
    }

    /** نتيجة الدمج (للتوافق). */
    interface MergeDone { void done(boolean stillComplete); }

    /**
     * يضيف رسائل واردة (وصلت والمحادثة مغلقة) إلى خزن محادثة كامل، بالترتيب ومن غير تكرار — بلا قصّ للأقدم.
     * لا يرفع synced_ts عمداً: عند فتح المحادثة يستمع Firestore لكل ما بعده فيلتقط ما لم يُحفظ هنا (رسائلي من جهاز آخر، ملفات...).
     */
    static void mergeIncoming(final String chatId, final List<JSONObject> add, final List<String> otherIds, final MergeDone cb) {
        if (chatId == null) return;
        run(() -> {
            SQLiteDatabase d = db;
            boolean ok = true;
            try {
                if (d == null || !isComplete(chatId)) { if (cb != null) cb.done(false); return; }
                HashSet<String> ids = new HashSet<>();
                try (Cursor c = d.rawQuery("SELECT id FROM messages WHERE chat_id=? AND ghost=0", new String[]{chatId})) {
                    while (c.moveToNext()) ids.add(c.getString(0));
                }
                d.beginTransaction();
                try {
                    if (add != null) for (JSONObject o : add) {
                        String id = o.optString("id");
                        if (id.isEmpty() || ids.contains(id)) continue;
                        insertRow(d, chatId, o, 0, null, null);
                        ids.add(id);
                    }
                    d.setTransactionSuccessful();
                } finally { d.endTransaction(); }
            } catch (Throwable t) { ok = false; }
            if (cb != null) cb.done(ok);
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
