package com.babylon.chat.iq;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.text.Html;
import android.text.InputFilter;
import android.util.Base64;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.*;

import androidx.core.graphics.PathParser;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;

/** منطق وتصميم المجموعات المشترك (منقول من index.html): الثيم، الصلاحيات، رسائل النظام، الأفاتار، الأيقونات، الحوارات. */
final class GroupsKit {
    static final int GROUP_MAX = 50;
    static final int[] GCOLORS = {0xFFE17076, 0xFFFAA774, 0xFFA695E7, 0xFF7BC862, 0xFF6EC9CB, 0xFF65AADD, 0xFFEE7AAE};
    static final int DANGER = 0xFFF0605D;

    // ---------- أيقونات (نفس مسارات الـ HTML) ----------
    static final String IC_USERS = "M16 20v-1.5a3.5 3.5 0 0 0-3.5-3.5h-5A3.5 3.5 0 0 0 4 18.5V20M10 11.5a3.2 3.2 0 1 0 0-6.4 3.2 3.2 0 0 0 0 6.4M20 20v-1.5a3.5 3.5 0 0 0-2.6-3.4M14.6 5.3a3.2 3.2 0 0 1 0 6.2";
    static final String IC_BACK = "m9 6 6 6-6 6", IC_CHEV = "m15 6-6 6 6 6", IC_CHECK = "m5 12.5 4.5 4.5L19 7.5", IC_PLUS = "M12 5v14M5 12h14";
    static final String IC_SEARCH = "M10.8 4a6.8 6.8 0 1 0 0 13.6 6.8 6.8 0 0 0 0-13.6M16 16l4.5 4.5";
    static final String IC_PIN = "M12 17v5M8 3h8l-1 6 3 3.5V14H6v-1.5L9 9z", IC_SEND = "M22 2 11 13M22 2l-7 20-4-9-9-4z";
    static final String IC_PEN = "M4 20h4L19 9l-4-4L4 16zM13.5 6.5l4 4", IC_LOCK = "M5 11h14v9H5zM8 11V8a4 4 0 0 1 8 0v3";
    static final String IC_CAM = "M4 8h3l2-2.5h6L17 8h3v11H4zM12 11a3.2 3.2 0 1 0 0 6.4 3.2 3.2 0 0 0 0-6.4";
    static final String IC_CAM_FILL = "M9.3 4h5.4c.35 0 .67.18.86.47L16.7 6.3H19A2.5 2.5 0 0 1 21.5 8.8v8.7A2.5 2.5 0 0 1 19 20H5a2.5 2.5 0 0 1-2.5-2.5V8.8A2.5 2.5 0 0 1 5 6.3h2.3l1.14-1.83A1 1 0 0 1 9.3 4ZM12 8.9a4.1 4.1 0 1 0 0 8.2 4.1 4.1 0 0 0 0-8.2ZM12 11.2a1.8 1.8 0 1 1 0 3.6 1.8 1.8 0 0 1 0-3.6Z";
    static final String IC_LEAVE = "M15 4h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-3M10 16l-4-4 4-4M6 12h10";
    static final String IC_BELL = "M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4", IC_BELL_OFF = "M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4M3 3l18 18";
    static final String IC_SHIELD = "M12 22s8-4 8-11V5l-8-3-8 3v6c0 7 8 11 8 11ZM9 12l2 2 4-4";
    static final String IC_KEY = "M13 15a5 5 0 1 0-10 0 5 5 0 0 0 10 0M11.5 11.5l8-8L22 6l-2 2 2 2-3 3-2-2-2 2";
    static final String IC_BAN = "M21 12a9 9 0 1 0-18 0 9 9 0 0 0 18 0M5.6 5.6l12.8 12.8";
    static final String IC_MSG = "M21 12a8 8 0 0 1-11.6 7.1L4 20.5l1.4-4.6A8 8 0 1 1 21 12z", IC_PROFILE = "M16 8a4 4 0 1 0-8 0 4 4 0 0 0 8 0M4 20a8 8 0 0 1 16 0";
    static final String IC_MENTION = "M16 12a4 4 0 1 0-8 0 4 4 0 0 0 8 0M16 8v5a3 3 0 0 0 6 0v-1a10 10 0 1 0-4 8";
    static final String IC_MIC = "M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3M5 11a7 7 0 0 0 14 0M12 18v3";
    static final String IC_IMAGE = "M4 5h16v14H4zM8.5 10a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3M4 17l5-5 4 4 3-3 4 4";
    static final String IC_MORE = "M12 5.2v.01M12 12v.01M12 18.8v.01", IC_CLOSE = "M6 6l12 12M18 6 6 18", IC_COPY = "M9 9h11v11H9zM5 15V4h11";
    static final String IC_TRASH = "M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13", IC_REPLY = "M9 14 4 9l5-5M4 9h9a7 7 0 0 1 7 7v2", IC_PLAY = "M8 5v14l11-7z", IC_PAUSE = "M8 5v14M16 5v14";

    static FirebaseFirestore db() { return FirebaseFirestore.getInstance(); }
    static String me() { return FirebaseAuth.getInstance().getCurrentUser() != null ? FirebaseAuth.getInstance().getCurrentUser().getUid() : ""; }

    // ---------- الثيم (يقرأ نفس إعدادات التطبيق: داكن/فاتح، حجم الخط، ثيم المحادثة) ----------
    static final class Th {
        boolean dark; float s, dens; int bg, card, text, sub, sep, chip, gold;
        ChatThemes.Pal pal; boolean babylon;
        int crSurface, crCard, crLine, crInk, crMuted;
        int u(float v) { return Math.round(v * dens); }              // dp ثابت
        int k(float v) { return Math.round(v * s * dens); }          // dp × --bb-s
        float sp(float v) { return v * s; }                           // نص × --bb-s
    }
    static Th th(Context c) {
        Th t = new Th(); SharedPreferences sp = AppSettings.prefs(c, "babylon_ui");
        t.dark = sp.getBoolean("dark", false);
        int font = Math.max(ChatThemes.FONT_MIN, Math.min(ChatThemes.FONT_MAX, sp.getInt("font", ChatThemes.FONT_DEF)));
        t.s = font / (float) ChatThemes.FONT_DEF; t.dens = c.getResources().getDisplayMetrics().density;
        if (t.dark) { t.bg = 0xFF0E1621; t.card = 0xFF17212B; t.text = 0xFFF1F5F9; t.sub = 0xFF8798AA; t.sep = 0x0FFFFFFF; t.chip = 0xFF1D2A38; t.gold = 0xFFE4C45B; }
        else { t.bg = 0xFFF4F6FA; t.card = 0xFFFFFFFF; t.text = 0xFF16202B; t.sub = 0xFF6B7A8C; t.sep = 0x1216202B; t.chip = 0xFFE8EDF4; t.gold = 0xFFC99A1C; }
        String id = ChatThemes.byId(sp.getString("chatTheme", ChatThemes.DEFAULT_ID)).id;
        t.pal = ChatThemes.themeFor(id, t.dark); t.babylon = ChatThemes.isBabylon(id);
        if (t.babylon) {
            if (t.dark) { t.crSurface = 0xFF17212B; t.crCard = 0xFF1E2A38; t.crLine = 0x14FFFFFF; t.crInk = 0xFFF4F7FB; t.crMuted = 0xFF8A9AAB; }
            else { t.crSurface = 0xFFEEF2F8; t.crCard = 0xFFFFFFFF; t.crLine = 0xFFE1E8F1; t.crInk = 0xFF17212B; t.crMuted = 0xFF718096; }
        } else { t.crSurface = t.pal.chatBg; t.crCard = t.pal.other; t.crLine = t.pal.line; t.crInk = t.pal.textOther; t.crMuted = t.pal.metaOther; }
        return t;
    }
    static void window(Activity a, Th t, int statusColor) {
        a.getWindow().setStatusBarColor(statusColor); a.getWindow().setNavigationBarColor(statusColor);
        int f = t.dark ? 0 : (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | (android.os.Build.VERSION.SDK_INT >= 26 ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0));
        a.getWindow().getDecorView().setSystemUiVisibility(f);
    }

    // ---------- المستخدمون ----------
    static final class U { String id, name = "مستخدم", username = "", photo = ""; }
    static final Map<String, U> GU = new HashMap<>();
    static U user(String uid) { U u = GU.get(uid); if (u == null) { u = new U(); u.id = uid; } return u; }
    static String userName(String uid) { return uid.equals(me()) ? "أنت" : user(uid).name; }
    static String myName() { U u = GU.get(me()); return u != null ? u.name : "مستخدم"; }
    static void loadUsers(Collection<String> ids, Runnable done) {
        final List<String> need = new ArrayList<>();
        for (String id : ids) if (id != null && !GU.containsKey(id)) need.add(id);
        if (need.isEmpty()) { done.run(); return; }
        final int[] left = {need.size()};
        for (final String id : need) db().collection("users").document(id).get().addOnCompleteListener(t -> {
            U u = new U(); u.id = id;
            if (t.isSuccessful() && t.getResult() != null && t.getResult().exists()) fillUser(u, t.getResult());
            GU.put(id, u); if (--left[0] == 0) done.run();
        });
    }
    static void fillUser(U u, DocumentSnapshot d) {
        String n = d.getString("name"); if (n == null || n.isEmpty()) n = d.getString("displayName");
        if (n != null && !n.isEmpty()) u.name = n;
        String un = d.getString("username"); if (un != null) u.username = un;
        String ph = d.getString("photo"); if (ph == null || ph.isEmpty()) ph = d.getString("avatarUrl");
        if (ph != null) u.photo = ph;
    }

    // ---------- بيانات المجموعة ----------
    @SuppressWarnings("unchecked") static List<String> parts(Map<String, Object> d) { Object o = d.get("participants"); List<String> r = new ArrayList<>(); if (o instanceof List) for (Object x : (List<Object>) o) r.add(String.valueOf(x)); return r; }
    @SuppressWarnings("unchecked") static List<String> strList(Map<String, Object> d, String k) { Object o = d.get(k); List<String> r = new ArrayList<>(); if (o instanceof List) for (Object x : (List<Object>) o) r.add(String.valueOf(x)); return r; }
    @SuppressWarnings("unchecked") static Map<String, Object> map(Map<String, Object> d, String k) { Object o = d.get(k); return o instanceof Map ? (Map<String, Object>) o : new HashMap<String, Object>(); }
    static String s(Map<String, Object> d, String k) { Object o = d.get(k); return o == null ? "" : String.valueOf(o); }
    static long l(Object o) { return o instanceof Number ? ((Number) o).longValue() : 0L; }
    static boolean isTrue(Object o) { return Boolean.TRUE.equals(o); }
    static String role(String uid, Map<String, Object> d) { if (uid.equals(s(d, "ownerId"))) return "owner"; return map(d, "admins").containsKey(uid) ? "admin" : "member"; }
    static boolean right(String right, String uid, Map<String, Object> d) {
        String r = role(uid, d); if (r.equals("owner")) return true;
        if (r.equals("admin")) { Object a = map(d, "admins").get(uid); return a instanceof Map && isTrue(((Map<?, ?>) a).get(right)); }
        if (right.equals("invite") || right.equals("pin") || right.equals("info")) return isTrue(map(d, "perms").get(right));
        return false;
    }
    static boolean canSend(String kind, String uid, Map<String, Object> d) {
        if (!role(uid, d).equals("member")) return true;
        if (isTrue(map(d, "muted").get(uid))) return false;
        return !Boolean.FALSE.equals(map(d, "perms").get(kind));
    }
    static String title(String uid, Map<String, Object> d) { String t = s(map(d, "titles"), uid); if (!t.isEmpty()) return t; String r = role(uid, d); return r.equals("owner") ? "المالك" : r.equals("admin") ? "مشرف" : ""; }
    static final String[][] ADMIN_RIGHTS = {{"info", "تغيير معلومات المجموعة"}, {"delete", "حذف رسائل الأعضاء"}, {"ban", "تقييد الأعضاء وحظرهم"}, {"invite", "إضافة أعضاء"}, {"pin", "تثبيت الرسائل"}};
    static final boolean[] ADMIN_DEF = {false, true, true, true, true};
    static final String[][] MEMBER_PERMS = {{"text", "إرسال الرسائل"}, {"media", "إرسال الصور"}, {"voice", "إرسال البصمات الصوتية"}, {"invite", "إضافة أعضاء"}, {"pin", "تثبيت الرسائل"}, {"info", "تغيير معلومات المجموعة"}};
    static final boolean[] MEMBER_DEF = {true, true, true, false, false, false};
    static String memberWord(int n) { return n == 1 ? "عضو واحد" : n == 2 ? "عضوان" : n <= 10 ? n + " أعضاء" : n + " عضواً"; }
    static String fmtTime(long ts) { return new SimpleDateFormat("h:mm", Locale.getDefault()).format(new Date(ts)); }
    static long fnv(String s) { long h = 2166136261L; for (int i = 0; i < s.length(); i++) h = ((h ^ s.charAt(i)) * 16777619L) & 0xFFFFFFFFL; return h; }
    static int colorOf(String uid) { return GCOLORS[(int) (fnv(uid) % GCOLORS.length)]; }

    // كتم المحادثة على الجهاز (babylon_muted_users)
    static boolean isMuted(Context c, String chatId) { return c.getSharedPreferences("babylon_groups", 0).getStringSet("muted", new HashSet<String>()).contains(chatId); }
    static void setMuted(Context c, String chatId, boolean on) { SharedPreferences sp = c.getSharedPreferences("babylon_groups", 0); Set<String> st = new HashSet<>(sp.getStringSet("muted", new HashSet<String>())); if (on) st.add(chatId); else st.remove(chatId); sp.edit().putStringSet("muted", st).apply(); }

    // ---------- رسائل النظام ----------
    static String join(List<String> l, String sep) { StringBuilder b = new StringBuilder(); for (int i = 0; i < l.size(); i++) { if (i > 0) b.append(sep); b.append(l.get(i)); } return b.toString(); }
    static String sysText(Map<String, Object> m) {
        Map<String, Object> sy = map(m, "sys"); if (sy.isEmpty()) return s(m, "text");
        String by = s(sy, "by"); boolean mine = by.equals(me()); String nm = userName(by);
        List<String> names = new ArrayList<>(); Object to = sy.get("to"); if (to instanceof List) for (Object x : (List<?>) to) names.add(userName(String.valueOf(x)));
        String lst = names.size() > 1 ? join(names.subList(0, names.size() - 1), "، ") + " و" + names.get(names.size() - 1) : (names.isEmpty() ? "" : names.get(0));
        switch (s(sy, "k")) {
            case "create": return mine ? "أنت أنشأت المجموعة" : nm + " أنشأ المجموعة";
            case "add": return (mine ? "أنت أضفت " : nm + " أضاف ") + lst;
            case "remove": return (mine ? "أنت أزلت " : nm + " أزال ") + lst;
            case "ban": return (mine ? "أنت أزلت وحظرت " : nm + " أزال وحظر ") + lst;
            case "leave": return mine ? "أنت غادرت المجموعة" : nm + " غادر المجموعة";
            case "rename": return (mine ? "أنت غيّرت" : nm + " غيّر") + " اسم المجموعة إلى «" + s(sy, "name") + "»";
            case "photo": return (mine ? "أنت غيّرت" : nm + " غيّر") + " صورة المجموعة";
            case "about": return (mine ? "أنت غيّرت" : nm + " غيّر") + " وصف المجموعة";
            case "pin": return (mine ? "أنت ثبّتّ" : nm + " ثبّت") + " رسالة";
            case "promote": return (mine ? "أنت عيّنت " : nm + " عيّن ") + lst + " مشرفاً";
            case "demote": return (mine ? "أنت أزلت " : nm + " أزال ") + lst + " من الإشراف";
            case "perms": return (mine ? "أنت غيّرت" : nm + " غيّر") + " صلاحيات الأعضاء";
        }
        return s(m, "text");
    }
    static String sysPreview(String k, String name, List<String> to) {
        StringBuilder sb = new StringBuilder(); if (to != null) for (String u : to) { if (sb.length() > 0) sb.append("، "); sb.append(user(u).name); } String lst = sb.toString();
        switch (k) {
            case "create": return name + " أنشأ المجموعة"; case "add": return name + " أضاف " + lst; case "remove": return name + " أزال " + lst; case "ban": return name + " أزال وحظر " + lst;
            case "leave": return name + " غادر المجموعة"; case "rename": return name + " غيّر اسم المجموعة"; case "photo": return name + " غيّر صورة المجموعة"; case "about": return name + " غيّر وصف المجموعة";
            case "pin": return name + " ثبّت رسالة"; case "promote": return name + " عيّن " + lst + " مشرفاً"; case "demote": return name + " أزال " + lst + " من الإشراف"; case "perms": return name + " غيّر صلاحيات الأعضاء";
        }
        return "";
    }
    static Map<String, Object> addSystem(String chatId, String k, List<String> to, Map<String, Object> extra) {
        long now = System.currentTimeMillis(); Map<String, Object> sys = new HashMap<>(); sys.put("k", k); sys.put("by", me()); if (to != null) sys.put("to", to); if (extra != null) sys.putAll(extra);
        String text = sysPreview(k, myName(), to); Map<String, Object> msg = new HashMap<>();
        msg.put("system", true); msg.put("sys", sys); msg.put("text", text); msg.put("senderId", me()); msg.put("createdAt", now);
        db().collection("chats").document(chatId).collection("messages").add(msg);
        Map<String, Object> lm = new HashMap<>(); lm.put("system", true); lm.put("text", text); lm.put("senderId", me()); lm.put("at", now);
        Map<String, Object> up = new HashMap<>(); up.put("updatedAt", now); up.put("lastMsg", lm); return up;
    }

    // ---------- أدوات واجهة ----------
    static GradientDrawable round(int color, float radiusPx) { GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(radiusPx); return g; }
    static GradientDrawable oval(int color) { GradientDrawable g = new GradientDrawable(); g.setShape(GradientDrawable.OVAL); g.setColor(color); return g; }
    static GradientDrawable goldGrad(float radiusPx, boolean oval) { GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFFE6C566, 0xFFD3A93B, 0xFFBC8E24}); if (oval) g.setShape(GradientDrawable.OVAL); else g.setCornerRadius(radiusPx); return g; }
    static TextView tv(Context c, Th t, CharSequence s, float sp, int color, boolean bold) { TextView v = new TextView(c); v.setText(s); v.setTextSize(sp * t.s); v.setTextColor(color); v.setIncludeFontPadding(false); if (bold) v.setTypeface(Typeface.DEFAULT_BOLD); return v; }
    static void rtl(View v) { v.setLayoutDirection(View.LAYOUT_DIRECTION_RTL); }
    static void toast(Context c, String s) { try { if (c instanceof Activity) AppToast.show((Activity) c, s, true); else Toast.makeText(c, s, Toast.LENGTH_SHORT).show(); } catch (Throwable t) { Toast.makeText(c, s, Toast.LENGTH_SHORT).show(); } }
    static LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }
    static LinearLayout.LayoutParams lpw(int w, int h, float wt) { return new LinearLayout.LayoutParams(w, h, wt); }
    static void press(final View v, final int pressedTint) {
        v.setOnTouchListener((x, e) -> { if (e.getAction() == android.view.MotionEvent.ACTION_DOWN) v.setAlpha(.82f); else if (e.getAction() == android.view.MotionEvent.ACTION_UP || e.getAction() == android.view.MotionEvent.ACTION_CANCEL) v.setAlpha(1f); return false; });
    }

    /** أيقونة SVG (خط أو تعبئة even-odd). */
    static final class Ic extends View {
        private final Path path; private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); private final boolean fill; private float sw = 2f;
        Ic(Context c, String d, int color, float sizeDp, boolean fill) {
            super(c); Path pp; try { pp = PathParser.createPathFromPathData(d); } catch (Throwable t) { pp = new Path(); } path = pp; this.fill = fill;
            if (fill) { pp.setFillType(Path.FillType.EVEN_ODD); p.setStyle(Paint.Style.FILL); } else { p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND); }
            p.setColor(color); setLayoutParams(new ViewGroup.LayoutParams(Math.round(sizeDp * c.getResources().getDisplayMetrics().density), Math.round(sizeDp * c.getResources().getDisplayMetrics().density)));
        }
        Ic width(float w) { sw = w; return this; }
        void setColor(int c) { p.setColor(c); invalidate(); }
        @Override protected void onDraw(Canvas cv) { float sc = Math.min(getWidth(), getHeight()) / 24f; cv.save(); cv.scale(sc, sc); if (!fill) p.setStrokeWidth(sw); cv.drawPath(path, p); cv.restore(); }
    }
    static Ic icon(Context c, String d, int color, float sizeDp) { return new Ic(c, d, color, sizeDp, false); }
    static FrameLayout.LayoutParams center(Context c, float sizeDp) { int px = Math.round(sizeDp * c.getResources().getDisplayMetrics().density); return new FrameLayout.LayoutParams(px, px, Gravity.CENTER); }

    /** مفتاح التبديل .sw (46×28، ذهبي عند التفعيل). */
    static final class Sw extends View {
        private boolean on; private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); private final int gold; private float pos; private final float dens, sc;
        Sw(Context c, Th t, boolean on) { super(c); this.on = on; gold = t.gold; pos = on ? 1f : 0f; dens = t.dens; sc = t.s; setLayoutParams(new ViewGroup.LayoutParams(t.k(46), t.k(28))); }
        void set(boolean v) { on = v; android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(pos, v ? 1f : 0f); a.setDuration(200); a.addUpdateListener(x -> { pos = (Float) x.getAnimatedValue(); invalidate(); }); a.start(); }
        boolean isOn() { return on; }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(); p.setColor(on ? gold : 0x738798AA); c.drawRoundRect(0, 0, w, h, h / 2f, h / 2f, p);
            float r = 11f * sc * dens, m = 3f * dens; float xOff = m + r; float xOn = w - m - r;   // RTL: مطفأ = يمين، مفعّل = يسار (translateX(-18))
            float cx = xOff + (xOn - xOff) * (1f - pos); cx = w - cx;
            p.setColor(0x59000000); c.drawCircle(cx, h / 2f + dens, r, p); p.setColor(0xFFFFFFFF); c.drawCircle(cx, h / 2f, r, p);
        }
    }

    // ---------- الأفاتار ----------
    private static final LruCache<String, Bitmap> BMP = new LruCache<>(60);
    static final class Av extends View {
        private String label = "؟", photo = "", seed = ""; private boolean group; private Bitmap bmp; private int ring = 0, ringGap = 0, ringW = 0, gapColor = 0;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        Av(Context c, float sizeDp) { super(c); int px = Math.round(sizeDp * c.getResources().getDisplayMetrics().density); setLayoutParams(new ViewGroup.LayoutParams(px, px)); tp.setColor(Color.WHITE); tp.setTextAlign(Paint.Align.CENTER); tp.setFakeBoldText(true); tp.setShadowLayer(2, 0, 1, 0x40000000); }
        Av ring(int color, int gapPx, int widthPx, int gapColor) { ring = color; ringGap = gapPx; ringW = widthPx; this.gapColor = gapColor; invalidate(); return this; }
        Av setGroup(Map<String, Object> d) { group = true; label = first(s(d, "name")); seed = ""; setPhoto(s(d, "photo")); return this; }
        Av setUser(String uid) { group = false; U u = user(uid); label = first(u.name); seed = uid; setPhoto(u.photo); return this; }
        static String first(String n) { return n == null || n.isEmpty() ? "؟" : n.substring(0, n.offsetByCodePoints(0, 1)).toUpperCase(); }
        private void setPhoto(final String ph) {
            photo = ph == null ? "" : ph; bmp = null; invalidate(); if (photo.isEmpty()) return; Bitmap c = BMP.get(photo); if (c != null) { bmp = c; return; } final String want = photo;
            loadBitmap(want, 400, b -> post(() -> { if (want.equals(photo)) { bmp = b; invalidate(); } }));
        }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(); float r0 = Math.min(w, h) / 2f; float r = r0 - (ring != 0 ? ringGap + ringW : 0);
            if (ring != 0) { p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(ring); cv.drawCircle(w / 2, h / 2, r0, p); p.setColor(gapColor); cv.drawCircle(w / 2, h / 2, r0 - ringW, p); }
            if (bmp != null) { BitmapShader sh = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP); Matrix m = new Matrix(); float s = Math.max(2 * r / bmp.getWidth(), 2 * r / bmp.getHeight()); m.setScale(s, s); m.postTranslate(w / 2 - bmp.getWidth() * s / 2, h / 2 - bmp.getHeight() * s / 2); sh.setLocalMatrix(m); p.setShader(sh); cv.drawCircle(w / 2, h / 2, r, p); p.setShader(null); return; }
            if (group) p.setShader(new LinearGradient(0, 0, w, h, new int[]{0xFFD4AF37, 0xFFB88A20, 0xFF1769AA}, new float[]{0, .58f, 1}, Shader.TileMode.CLAMP));
            else { long a = fnv(seed); p.setShader(new LinearGradient(0, 0, w, h, GCOLORS[(int) (a % 7)], GCOLORS[(int) ((a >>> 5) % 7)], Shader.TileMode.CLAMP)); }
            cv.drawCircle(w / 2, h / 2, r, p); p.setShader(null); tp.setTextSize(r * 0.95f); cv.drawText(label, w / 2, h / 2 - (tp.descent() + tp.ascent()) / 2, tp);
        }
    }
    interface BmpCb { void got(Bitmap b); }
    static void loadBitmap(final String src, final int maxSide, final BmpCb cb) {
        Bitmap c = BMP.get(src); if (c != null) { cb.got(c); return; }
        new Thread(() -> {
            Bitmap b = null;
            try {
                byte[] raw;
                if (src.startsWith("data:")) raw = Base64.decode(src.substring(src.indexOf(',') + 1), Base64.DEFAULT);
                else { HttpURLConnection cn = (HttpURLConnection) new URL(src).openConnection(); cn.setConnectTimeout(8000); cn.setReadTimeout(12000); InputStream in = cn.getInputStream(); java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) o.write(buf, 0, n); in.close(); raw = o.toByteArray(); }
                BitmapFactory.Options op = new BitmapFactory.Options(); op.inJustDecodeBounds = true; BitmapFactory.decodeByteArray(raw, 0, raw.length, op);
                int ss = 1; while (Math.max(op.outWidth, op.outHeight) / (ss * 2) >= maxSide) ss *= 2; op = new BitmapFactory.Options(); op.inSampleSize = ss; b = BitmapFactory.decodeByteArray(raw, 0, raw.length, op);
            } catch (Throwable ignored) { }
            if (b != null) { BMP.put(src, b); cb.got(b); }
        }).start();
    }
    static Bitmap decodeSquare(android.content.ContentResolver cr, android.net.Uri uri, int size) {
        try { InputStream in = cr.openInputStream(uri); Bitmap b = BitmapFactory.decodeStream(in); in.close(); if (b == null) return null; int s = Math.min(b.getWidth(), b.getHeight()); Bitmap sq = Bitmap.createBitmap(b, (b.getWidth() - s) / 2, (b.getHeight() - s) / 2, s, s); return Bitmap.createScaledBitmap(sq, size, size, true); } catch (Throwable t) { return null; }
    }
    static String toDataUrl(Bitmap b) { java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.JPEG, 82, o); return "data:image/jpeg;base64," + Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP); }

    // ---------- الحوارات (نفس .cl-dlg.cl-del النهائي: أيقونة دائرية، عنوان وسط، أزرار مملوءة) ----------
    interface AskCb { void done(boolean ok, String value); }
    static final String IC_WARN = "M12 9v4m0 4h.01M10.3 3.9 2.8 17a2 2 0 0 0 1.7 3h15a2 2 0 0 0 1.7-3l-7.5-13.1a2 2 0 0 0-3.4 0Z";
    static final String IC_EDIT = "m14 5 5 5M4 20l4.5-1 11-11a2.12 2.12 0 0 0-3-3l-11 11L4 20Z";
    static final String IC_INFO = "M12 11v5M12 8h.01M21 12a9 9 0 1 0-18 0 9 9 0 0 0 18 0";
    static final String IC_LIST = "M4 7h16M4 12h16M4 17h16";
    /** مفتاح الذهب المتدرج للأزرار الدائرية (.gx-fab النهائي: 54 × 54، أيقونة بيضاء). */
    static FrameLayout goldFab(Context c, Th t, String icon, float stroke) {
        FrameLayout f = new FrameLayout(c); f.setBackground(goldGrad(0, true)); f.addView(new Ic(c, icon, 0xFFFFFFFF, 26, false).width(stroke), center(c, 26)); f.setElevation(t.u(8)); return f;
    }
    static Dialog ask(Activity a, Th t, String title, String bodyHtml, String inputInit, String hint, int max, boolean multiline, String okText, String cancelText, AskCb cb) {
        return askX(a, t, title, bodyHtml, inputInit, hint, max, multiline, okText, cancelText, false, inputInit != null ? IC_EDIT : IC_INFO, cb);
    }
    static Dialog askX(Activity a, Th t, String title, String bodyHtml, String inputInit, String hint, int max, boolean multiline, String okText, String cancelText, boolean danger, String icon, AskCb cb) {
        final Dialog d = new Dialog(a); d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final int gold = t.dark ? 0xFFE4C45B : 0xFFC99A1C, tone = danger ? (t.dark ? 0xFFF0605D : 0xFFE5484D) : gold;
        LinearLayout card = new LinearLayout(a); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(t.u(20), t.u(26), t.u(20), t.u(18)); rtl(card);
        // الخط الذهبي العلوي (3dp)
        FrameLayout shell = new FrameLayout(a); GradientDrawable sbg = round(t.card, t.u(26)); sbg.setStroke(Math.max(1, t.u(1)), t.dark ? 0x1FFFFFFF : 0x2416202B); shell.setBackground(sbg); shell.setClipToOutline(true); shell.addView(card, new FrameLayout.LayoutParams(-1, -2));
        View line = new View(a); GradientDrawable lg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0, gold, gold, 0}); line.setBackground(lg); line.setAlpha(.85f); shell.addView(line, new FrameLayout.LayoutParams(-1, t.u(3), Gravity.TOP));
        if (icon != null) {
            FrameLayout ringW = new FrameLayout(a); ringW.setBackground(oval((tone & 0x00FFFFFF) | 0x0D000000)); FrameLayout ic = new FrameLayout(a); ic.setBackground(oval((tone & 0x00FFFFFF) | (danger ? (t.dark ? 0x24000000 : 0x1C000000) : (t.dark ? 0x24000000 : 0x21000000))));
            ic.addView(new Ic(a, icon, tone, 28 * t.s, false).width(1.9f), center(a, 28 * t.s)); ringW.addView(ic, new FrameLayout.LayoutParams(t.k(62), t.k(62), Gravity.CENTER));
            LinearLayout.LayoutParams rp = lp(t.k(62) + t.u(16), t.k(62) + t.u(16)); rp.gravity = Gravity.CENTER_HORIZONTAL; rp.bottomMargin = t.u(6); card.addView(ringW, rp);
        }
        TextView h = tv(a, t, title, 19, t.text, true); h.setGravity(Gravity.CENTER); card.addView(h, lp(-1, -2));
        if (bodyHtml != null && !bodyHtml.isEmpty()) { TextView b = tv(a, t, Html.fromHtml(bodyHtml.replace("<b>", "<b><font color='" + String.format("#%06X", 0xFFFFFF & gold) + "'>").replace("</b>", "</font></b>")), 14.5f, t.sub, false); b.setGravity(Gravity.CENTER); b.setLineSpacing(0, 1.5f); LinearLayout.LayoutParams bp = lp(-1, -2); bp.topMargin = t.u(8); card.addView(b, bp); }
        EditText e = null;
        if (inputInit != null) {
            e = new EditText(a); e.setText(inputInit); e.setHint(hint); e.setHintTextColor(t.sub & 0x00FFFFFF | 0x80000000); e.setTextColor(t.text); e.setTextSize((multiline ? 16f : 22f) * t.s); e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});
            GradientDrawable eb = round(t.chip, t.u(16)); eb.setStroke(Math.max(1, t.u(1.5f)), 0); e.setBackground(eb); e.setPadding(t.u(14), t.u(12), t.u(14), t.u(12)); e.setSelection(e.length()); e.setTypeface(Typeface.DEFAULT_BOLD);
            if (multiline) { e.setMinLines(3); e.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL); } else { e.setSingleLine(true); e.setGravity(Gravity.CENTER); }
            LinearLayout.LayoutParams ep = lp(-1, -2); ep.topMargin = t.u(16); card.addView(e, ep);
        }
        LinearLayout row = new LinearLayout(a); row.setOrientation(LinearLayout.HORIZONTAL); LinearLayout.LayoutParams rp = lp(-1, -2); rp.topMargin = t.u(16); card.addView(row, rp);
        final EditText fe = e;
        TextView ok = tv(a, t, okText, 15.5f, danger ? 0xFFFFFFFF : 0xFF16202B, true); ok.setGravity(Gravity.CENTER); ok.setPadding(0, t.u(13), 0, t.u(13));
        GradientDrawable okBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, danger ? new int[]{0xFFF0605D, 0xFFD94545} : (t.dark ? new int[]{0xFFE4C45B, 0xFFC99A1C} : new int[]{0xFFE4B93F, 0xFFC99A1C})); okBg.setCornerRadius(t.u(16)); ok.setBackground(okBg); ok.setElevation(t.u(3));
        ok.setOnClickListener(v -> { d.dismiss(); cb.done(true, fe == null ? "" : fe.getText().toString().trim()); });
        TextView no = tv(a, t, cancelText, 15.5f, t.text, true); no.setGravity(Gravity.CENTER); no.setPadding(0, t.u(13), 0, t.u(13)); no.setBackground(round(t.chip, t.u(16)));
        no.setOnClickListener(v -> { d.dismiss(); cb.done(false, ""); });
        LinearLayout.LayoutParams p1 = lpw(0, -2, 1), p2 = lpw(0, -2, 1); p2.setMarginEnd(t.u(10)); row.addView(ok, p1); row.addView(no, p2);
        d.setContentView(shell); Window w = d.getWindow(); w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0)); w.setDimAmount(.52f); w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setLayout(Math.min(t.u(340), a.getResources().getDisplayMetrics().widthPixels - t.u(44)), ViewGroup.LayoutParams.WRAP_CONTENT); if (inputInit != null) w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show(); return d;
    }
    static void confirm(Activity a, Th t, String title, String bodyHtml, String ok, AskCb cb) { askX(a, t, title, bodyHtml, null, null, 0, false, ok, "إلغاء", true, IC_WARN, cb); }
    static void confirmGold(Activity a, Th t, String title, String bodyHtml, String ok, String cancel, AskCb cb) { askX(a, t, title, bodyHtml, null, null, 0, false, ok, cancel, false, IC_INFO, cb); }

    /** نافذة العضو (.up-wrap): رأس 64 + صندوق أزرار بفاصل 4. */
    interface PopCb { void pick(String key); }
    static void memberPopup(final Activity a, final Th t, String uid, Map<String, Object> d, List<String[]> mgmt, boolean fromChat, final PopCb cb) {
        final Dialog dg = new Dialog(a); dg.requestWindowFeature(Window.FEATURE_NO_TITLE); String me = me(); U g = user(uid);
        LinearLayout wrap = new LinearLayout(a); wrap.setOrientation(LinearLayout.VERTICAL); rtl(wrap);
        LinearLayout head = new LinearLayout(a); head.setGravity(Gravity.CENTER_VERTICAL); head.setBackground(round(t.card, t.u(20))); head.setPadding(t.u(14), 0, t.u(14), 0);
        Av av = new Av(a, 50 * t.s); av.setUser(uid); av.ring(t.gold, t.u(2), t.u(2), t.card); head.addView(av);
        LinearLayout tx = new LinearLayout(a); tx.setOrientation(LinearLayout.VERTICAL); tx.setPadding(t.u(12), 0, 0, 0);
        TextView nm = tv(a, t, g.name + (uid.equals(me) ? "  (أنت)" : ""), 16, t.text, true); nm.setSingleLine(true); nm.setEllipsize(android.text.TextUtils.TruncateAt.END); tx.addView(nm);
        String tt = title(uid, d); String sub = (g.username.isEmpty() ? "" : "@" + g.username) + (tt.isEmpty() ? "" : (g.username.isEmpty() ? "" : " · ") + tt);
        if (!sub.isEmpty()) { TextView st = tv(a, t, sub, 12.5f, t.sub, false); st.setSingleLine(true); LinearLayout.LayoutParams sp = lp(-2, -2); sp.topMargin = t.u(2); tx.addView(st, sp); }
        head.addView(tx, lpw(0, -2, 1)); wrap.addView(head, lp(-1, t.u(64)));
        LinearLayout box = new LinearLayout(a); box.setOrientation(LinearLayout.VERTICAL); box.setBackground(round(t.card, t.u(20))); LinearLayout.LayoutParams bp = lp(-1, -2); bp.topMargin = t.u(4); wrap.addView(box, bp);
        List<String[]> main = new ArrayList<>();
        if (!uid.equals(me)) main.add(new String[]{"msg", "إرسال رسالة", IC_MSG}); main.add(new String[]{"profile", "عرض الملف الشخصي", IC_PROFILE}); if (fromChat && !uid.equals(me)) main.add(new String[]{"mention", "إشارة", IC_MENTION});
        boolean first = true;
        for (String[] b : main) { box.addView(popRow(a, t, b[1], b[2], false, !first, () -> { dg.dismiss(); cb.pick(b[0]); })); first = false; }
        if (!mgmt.isEmpty()) {
            TextView sec = tv(a, t, "إدارة العضو", 12, t.sub, true); sec.setPadding(t.u(16), t.u(10), t.u(16), t.u(6)); box.addView(sec);
            for (final String[] b : mgmt) box.addView(popRow(a, t, b[1], null, "danger".equals(b.length > 2 ? b[2] : ""), true, () -> { dg.dismiss(); cb.pick(b[0]); }));
        }
        dg.setContentView(wrap); Window w = dg.getWindow(); w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0)); w.setDimAmount(.52f); w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setLayout(Math.min(t.u(340), a.getResources().getDisplayMetrics().widthPixels - t.u(44)), ViewGroup.LayoutParams.WRAP_CONTENT); dg.show();
    }
    static View popRow(Activity a, Th t, String text, String ic, boolean danger, boolean sep, final Runnable click) {
        LinearLayout r = new LinearLayout(a); r.setGravity(Gravity.CENTER_VERTICAL); r.setMinimumHeight(t.k(54)); r.setPadding(t.u(16), 0, t.u(16), 0);
        int col = danger ? 0xFFE5484D : t.text;
        FrameLayout box = new FrameLayout(a);
        if (ic != null) box.addView(icon(a, ic, t.gold, 21), center(a, 21)); else { View dot = new View(a); dot.setBackground(oval(danger ? 0xFFE5484D : (t.gold & 0x00FFFFFF | 0xB3000000))); FrameLayout.LayoutParams dp = new FrameLayout.LayoutParams(t.u(6), t.u(6), Gravity.CENTER); box.addView(dot, dp); }
        r.addView(box, lp(t.k(24), t.k(24))); TextView tx = tv(a, t, text, 15.5f, col, true); tx.setPadding(t.u(13), 0, 0, 0); r.addView(tx, lpw(0, -2, 1)); r.setOnClickListener(v -> click.run());
        LinearLayout w = new LinearLayout(a); w.setOrientation(LinearLayout.VERTICAL); if (sep) { View s = new View(a); s.setBackgroundColor(t.sep); w.addView(s, lp(-1, 1)); } w.addView(r); return w;
    }
}
