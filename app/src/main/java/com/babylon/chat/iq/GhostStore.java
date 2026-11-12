package com.babylon.chat.iq;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;

/**
 * رسائل «وهمية» لمن حظره الطرف الآخر: تُخزَّن على جهازه فقط ولا تُكتب في Firestore إطلاقاً،
 * فلا تصل لمن حظره (لا رسالة ولا عدّاد غير مقروء ولا إشعار) بينما تظهر عند المرسل كأنها أُرسلت.
 */
final class GhostStore {
    private GhostStore() { }

    private static final String PREF = "babylon_ghost";
    private static final int MAX = 300, MAX_DATA = 300_000;

    private static SharedPreferences sp(Context c) { return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE); }

    static synchronized void add(Context c, String chatId, String id, Map<String, Object> msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", id);
            for (Map.Entry<String, Object> e : msg.entrySet()) {
                Object v = e.getValue();
                if (v instanceof String && "data".equals(e.getKey()) && ((String) v).length() > MAX_DATA) return;   // وسائط ضخمة: تظهر الآن فقط ولا تُحفظ
                if (v instanceof String || v instanceof Number || v instanceof Boolean) o.put(e.getKey(), v);
            }
            JSONArray old = list(c, chatId), arr = new JSONArray();
            for (int i = Math.max(0, old.length() - (MAX - 1)); i < old.length(); i++) arr.put(old.get(i));
            arr.put(o);
            sp(c).edit().putString(chatId, arr.toString()).apply();
        } catch (Throwable ignored) { }
    }

    static synchronized JSONArray list(Context c, String chatId) {
        try { String s = sp(c).getString(chatId, null); return s == null ? new JSONArray() : new JSONArray(s); }
        catch (Throwable t) { return new JSONArray(); }
    }

    /** آخر رسالة وهمية: {المعاينة، الوقت} أو null. */
    static String[] last(Context c, String chatId) {
        try {
            JSONArray a = list(c, chatId);
            if (a.length() == 0) return null;
            JSONObject o = a.getJSONObject(a.length() - 1);
            String t = o.optString("type", "text");
            String pv = "image".equals(t) ? ("image/gif".equals(o.optString("mime")) ? "🎞 GIF" : "📷 صورة") : "audio".equals(t) ? "🎤 رسالة صوتية" : o.optString("text", "");
            return new String[]{pv, String.valueOf(o.optLong("timestamp", 0L))};
        } catch (Throwable t) { return null; }
    }
}
