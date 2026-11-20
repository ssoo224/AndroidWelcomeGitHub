package com.babylon.chat.iq;

import android.app.Activity;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.util.Base64;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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

/** منطق المجموعات المشترك (منقول من index.html): الصلاحيات، الألقاب، رسائل النظام، الأفاتار، الأيقونات. */
final class GroupsKit {
    static final int BG = 0xFF0E1621, CARD = 0xFF17212B, GOLD = 0xFFE4C45B, GOLD2 = 0xFFC99A1C,
            SUB = 0xFF8798AA, TEXT = 0xFFF1F5F9, CHIP = 0x17FFFFFF, DANGER = 0xFFF0605D, SEP = 0x14FFFFFF;
    static final int GROUP_MAX = 50;
    static final int[] GCOLORS = {0xFFE17076, 0xFFFAA774, 0xFFA695E7, 0xFF7BC862, 0xFF6EC9CB, 0xFF65AADD, 0xFFEE7AAE};

    // أيقونات SVG (نفس مسارات الويب)
    static final String IC_USERS = "M16 20v-1.5a3.5 3.5 0 0 0-3.5-3.5h-5A3.5 3.5 0 0 0 4 18.5V20M10 11.5a3.2 3.2 0 1 0 0-6.4 3.2 3.2 0 0 0 0 6.4M20 20v-1.5a3.5 3.5 0 0 0-2.6-3.4M14.6 5.3a3.2 3.2 0 0 1 0 6.2";
    static final String IC_USER_PLUS = "M15 20v-1.5a3.5 3.5 0 0 0-3.5-3.5h-5A3.5 3.5 0 0 0 3 18.5V20M9 11.5a3.2 3.2 0 1 0 0-6.4 3.2 3.2 0 0 0 0 6.4M19 8v6M16 11h6";
    static final String IC_BACK = "m9 6 6 6-6 6";
    static final String IC_CHECK = "m5 12.5 4.5 4.5L19 7.5";
    static final String IC_SEARCH = "M10.8 4a6.8 6.8 0 1 0 0 13.6 6.8 6.8 0 0 0 0-13.6M16 16l4.5 4.5";
    static final String IC_PIN = "M12 17v5M8 3h8l-1 6 3 3.5V14H6v-1.5L9 9z";
    static final String IC_SEND = "M22 2 11 13M22 2l-7 20-4-9-9-4z";
    static final String IC_EDIT = "m14 5 5 5M4 20l4.5-1 11-11a2.12 2.12 0 0 0-3-3l-11 11L4 20Z";
    static final String IC_LOCK = "M5 11h14v9H5zM8 11V8a4 4 0 0 1 8 0v3";
    static final String IC_CAM = "M4 8h3l2-2.5h6L17 8h3v11H4zM12 11a3.2 3.2 0 1 0 0 6.4 3.2 3.2 0 0 0 0-6.4";
    static final String IC_EXIT = "M10 4H5v16h5M15 8l4 4-4 4M19 12H9";
    static final String IC_CLOSE = "M6 6l12 12M18 6 6 18";

    static final FirebaseFirestore db() { return FirebaseFirestore.getInstance(); }
    static String me() { return FirebaseAuth.getInstance().getCurrentUser() != null ? FirebaseAuth.getInstance().getCurrentUser().getUid() : ""; }
    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    // ---------- المستخدمون ----------
    static final class U { String id, name = "مستخدم", username = "", photo = ""; }
    static final Map<String, U> GU = new HashMap<>();

    static U user(String uid) { U u = GU.get(uid); if (u == null) { u = new U(); u.id = uid; } return u; }
    static String userName(String uid) { return uid.equals(me()) ? "أنت" : user(uid).name; }

    static void loadUsers(Collection<String> ids, Runnable done) {
        final List<String> need = new ArrayList<>();
        for (String id : ids) if (id != null && !GU.containsKey(id)) need.add(id);
        if (need.isEmpty()) { done.run(); return; }
        final int[] left = {need.size()};
        for (final String id : need) {
            db().collection("users").document(id).get().addOnCompleteListener(t -> {
                U u = new U(); u.id = id;
                if (t.isSuccessful() && t.getResult() != null && t.getResult().exists()) fillUser(u, t.getResult());
                GU.put(id, u);
                if (--left[0] == 0) done.run();
            });
        }
    }
    static void fillUser(U u, DocumentSnapshot d) {
        String n = d.getString("name"); if (n == null || n.isEmpty()) n = d.getString("displayName");
        if (n != null && !n.isEmpty()) u.name = n;
        String un = d.getString("username"); if (un != null) u.username = un;
        String ph = d.getString("photo"); if (ph == null || ph.isEmpty()) ph = d.getString("avatarUrl");
        if (ph != null) u.photo = ph;
    }
    static String myName() { U u = GU.get(me()); return u != null ? u.name : "مستخدم"; }

    // ---------- بيانات المجموعة (Map من Firestore) ----------
    @SuppressWarnings("unchecked")
    static List<String> parts(Map<String, Object> d) {
        Object o = d.get("participants"); List<String> r = new ArrayList<>();
        if (o instanceof List) for (Object x : (List<Object>) o) r.add(String.valueOf(x));
        return r;
    }
    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Map<String, Object> d, String k) {
        Object o = d.get(k); return o instanceof Map ? (Map<String, Object>) o : new HashMap<String, Object>();
    }
    static String s(Map<String, Object> d, String k) { Object o = d.get(k); return o == null ? "" : String.valueOf(o); }
    static long l(Object o) { return o instanceof Number ? ((Number) o).longValue() : 0L; }
    static boolean isTrue(Object o) { return Boolean.TRUE.equals(o); }

    static String role(String uid, Map<String, Object> d) {
        if (uid.equals(s(d, "ownerId"))) return "owner";
        return map(d, "admins").containsKey(uid) ? "admin" : "member";
    }
    static boolean right(String right, String uid, Map<String, Object> d) {
        String r = role(uid, d);
        if (r.equals("owner")) return true;
        if (r.equals("admin")) { Object a = map(d, "admins").get(uid); return a instanceof Map && isTrue(((Map<?, ?>) a).get(right)); }
        if (right.equals("invite") || right.equals("pin") || right.equals("info")) return isTrue(map(d, "perms").get(right));
        return false;
    }
    static boolean canSend(String kind, String uid, Map<String, Object> d) {
        if (!role(uid, d).equals("member")) return true;
        if (isTrue(map(d, "muted").get(uid))) return false;
        return !Boolean.FALSE.equals(map(d, "perms").get(kind));
    }
    static String title(String uid, Map<String, Object> d) {
        String t = s(map(d, "titles"), uid); if (!t.isEmpty()) return t;
        String r = role(uid, d); return r.equals("owner") ? "المالك" : r.equals("admin") ? "مشرف" : "";
    }
    static final String[][] ADMIN_RIGHTS = {{"info", "تغيير معلومات المجموعة"}, {"delete", "حذف رسائل الأعضاء"}, {"ban", "تقييد الأعضاء وحظرهم"}, {"invite", "إضافة أعضاء"}, {"pin", "تثبيت الرسائل"}};
    static final String[][] MEMBER_PERMS = {{"text", "إرسال الرسائل"}, {"media", "إرسال الصور"}, {"voice", "إرسال البصمات الصوتية"}, {"invite", "إضافة أعضاء"}, {"pin", "تثبيت الرسائل"}, {"info", "تغيير معلومات المجموعة"}};
    static final boolean[] MEMBER_PERMS_DEF = {true, true, true, false, false, false};

    static String memberWord(int n) { return n == 1 ? "عضو واحد" : n == 2 ? "عضوان" : n <= 10 ? n + " أعضاء" : n + " عضواً"; }
    static String fmtTime(long ts) { return new SimpleDateFormat("h:mm", Locale.getDefault()).format(new Date(ts)); }

    // ---------- رسائل النظام ----------
    static String sysText(Map<String, Object> m) {
        Map<String, Object> sy = map(m, "sys");
        if (sy.isEmpty()) return s(m, "text");
        String by = s(sy, "by"); boolean mine = by.equals(me()); String nm = userName(by);
        List<String> names = new ArrayList<>();
        Object to = sy.get("to"); if (to instanceof List) for (Object x : (List<?>) to) names.add(userName(String.valueOf(x)));
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
        StringBuilder sb = new StringBuilder();
        if (to != null) for (String u : to) { if (sb.length() > 0) sb.append("، "); sb.append(user(u).name); }
        String lst = sb.toString();
        switch (k) {
            case "create": return name + " أنشأ المجموعة"; case "add": return name + " أضاف " + lst;
            case "remove": return name + " أزال " + lst; case "ban": return name + " أزال وحظر " + lst;
            case "leave": return name + " غادر المجموعة"; case "rename": return name + " غيّر اسم المجموعة";
            case "photo": return name + " غيّر صورة المجموعة"; case "about": return name + " غيّر وصف المجموعة";
            case "pin": return name + " ثبّت رسالة"; case "promote": return name + " عيّن " + lst + " مشرفاً";
            case "demote": return name + " أزال " + lst + " من الإشراف"; case "perms": return name + " غيّر صلاحيات الأعضاء";
        }
        return "";
    }
    static String join(List<String> l, String sep) { StringBuilder b = new StringBuilder(); for (int i = 0; i < l.size(); i++) { if (i > 0) b.append(sep); b.append(l.get(i)); } return b.toString(); }

    /** يضيف رسالة نظام ويرجع حقول التحديث (updatedAt + lastMsg) لدمجها في تحديث المجموعة. */
    static Map<String, Object> addSystem(String chatId, String k, List<String> to, Map<String, Object> extra) {
        long now = System.currentTimeMillis();
        Map<String, Object> sys = new HashMap<>(); sys.put("k", k); sys.put("by", me());
        if (to != null) sys.put("to", to);
        if (extra != null) sys.putAll(extra);
        String text = sysPreview(k, myName(), to);
        Map<String, Object> msg = new HashMap<>();
        msg.put("system", true); msg.put("sys", sys); msg.put("text", text); msg.put("senderId", me()); msg.put("createdAt", now);
        db().collection("chats").document(chatId).collection("messages").add(msg);
        Map<String, Object> lm = new HashMap<>(); lm.put("system", true); lm.put("text", text); lm.put("senderId", me()); lm.put("at", now);
        Map<String, Object> up = new HashMap<>(); up.put("updatedAt", now); up.put("lastMsg", lm);
        return up;
    }

    // ---------- واجهات مساعدة ----------
    static GradientDrawable round(int color, float radiusDp, Context c) { GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(c, radiusDp)); return g; }
    static TextView tv(Context c, String t, float sp, int color, boolean bold) {
        TextView v = new TextView(c); v.setText(t); v.setTextSize(sp); v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD); return v;
    }
    static void rtl(View v) { v.setLayoutDirection(View.LAYOUT_DIRECTION_RTL); }
    static void style(Activity a) {
        a.getWindow().setStatusBarColor(BG); a.getWindow().setNavigationBarColor(BG);
        a.getWindow().getDecorView().setSystemUiVisibility(0);
    }
    static LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }
    static void toast(Context c, String s) { try { if (c instanceof Activity) AppToast.show((Activity) c, s, true); else Toast.makeText(c, s, Toast.LENGTH_SHORT).show(); } catch (Throwable t) { Toast.makeText(c, s, Toast.LENGTH_SHORT).show(); } }

    /** أيقونة SVG: مسار 24x24 بخط. */
    static final class Ic extends View {
        private final Path path; private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Ic(Context c, String d, int color, float sizeDp) {
            super(c); Path pp; try { pp = PathParser.createPathFromPathData(d); } catch (Throwable t) { pp = new Path(); } path = pp;
            p.setStyle(Paint.Style.STROKE); p.setColor(color); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
            setLayoutParams(new ViewGroup.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        }
        void setColor(int c) { p.setColor(c); invalidate(); }
        @Override protected void onDraw(Canvas cv) {
            float sc = Math.min(getWidth(), getHeight()) / 24f;
            cv.save(); cv.scale(sc, sc); p.setStrokeWidth(2f); cv.drawPath(path, p); cv.restore();
        }
    }
    static View icon(Context c, String d, int color, float sizeDp) { return new Ic(c, d, color, sizeDp); }

    // ---------- الأفاتار ----------
    private static final LruCache<String, Bitmap> BMP = new LruCache<>(40);
    static final class Av extends View {
        private String label = "؟", photo = "", seed = ""; private boolean group; private Bitmap bmp;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        Av(Context c, float sizeDp) { super(c); setLayoutParams(new ViewGroup.LayoutParams(dp(c, sizeDp), dp(c, sizeDp))); tp.setColor(Color.WHITE); tp.setTextAlign(Paint.Align.CENTER); tp.setFakeBoldText(true); tp.setShadowLayer(2, 0, 1, 0x40000000); }
        void setGroup(Map<String, Object> d) { group = true; label = first(s(d, "name")); seed = ""; setPhoto(s(d, "photo")); }
        void setUser(String uid) { group = false; U u = user(uid); label = first(u.name); seed = uid; setPhoto(u.photo); }
        private static String first(String n) { return n == null || n.isEmpty() ? "؟" : n.substring(0, n.offsetByCodePoints(0, 1)).toUpperCase(); }
        private void setPhoto(final String ph) {
            photo = ph == null ? "" : ph; bmp = null; invalidate(); if (photo.isEmpty()) return;
            Bitmap c = BMP.get(photo); if (c != null) { bmp = c; return; }
            final String want = photo;
            new Thread(() -> {
                Bitmap b = null;
                try {
                    if (want.startsWith("data:")) { byte[] x = Base64.decode(want.substring(want.indexOf(',') + 1), Base64.DEFAULT); b = BitmapFactory.decodeByteArray(x, 0, x.length); }
                    else { HttpURLConnection cn = (HttpURLConnection) new URL(want).openConnection(); cn.setConnectTimeout(8000); cn.setReadTimeout(8000); InputStream in = cn.getInputStream(); b = BitmapFactory.decodeStream(in); in.close(); }
                } catch (Throwable ignored) { }
                if (b != null) { BMP.put(want, b); final Bitmap fb = b; post(() -> { if (want.equals(photo)) { bmp = fb; invalidate(); } }); }
            }).start();
        }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), r = Math.min(w, h) / 2f;
            if (bmp != null) {
                BitmapShader sh = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                Matrix m = new Matrix(); float s = Math.max(w / bmp.getWidth(), h / bmp.getHeight());
                m.setScale(s, s); m.postTranslate((w - bmp.getWidth() * s) / 2, (h - bmp.getHeight() * s) / 2); sh.setLocalMatrix(m);
                p.setShader(sh); cv.drawCircle(w / 2, h / 2, r, p); p.setShader(null); return;
            }
            if (group) p.setShader(new LinearGradient(0, 0, w, h, new int[]{0xFFD4AF37, 0xFFB88A20, 0xFF1769AA}, new float[]{0, .58f, 1}, Shader.TileMode.CLAMP));
            else { int a = hash(seed); p.setShader(new LinearGradient(0, 0, w, h, GCOLORS[a % 7], GCOLORS[(a >>> 5) % 7], Shader.TileMode.CLAMP)); }
            cv.drawCircle(w / 2, h / 2, r, p); p.setShader(null);
            tp.setTextSize(r * 0.95f); cv.drawText(label, w / 2, h / 2 - (tp.descent() + tp.ascent()) / 2, tp);
        }
    }
    static int hash(String s) { int h = (int) 2166136261L; for (int i = 0; i < s.length(); i++) { h ^= s.charAt(i); h *= 16777619; } return h & 0x7fffffff; }
    static int colorOf(String uid) { return GCOLORS[hash(uid) % GCOLORS.length]; }
    static Bitmap decodeSquare(android.content.ContentResolver cr, android.net.Uri uri, int size) {
        try {
            InputStream in = cr.openInputStream(uri); Bitmap b = BitmapFactory.decodeStream(in); in.close(); if (b == null) return null;
            int s = Math.min(b.getWidth(), b.getHeight()); Bitmap sq = Bitmap.createBitmap(b, (b.getWidth() - s) / 2, (b.getHeight() - s) / 2, s, s);
            return Bitmap.createScaledBitmap(sq, size, size, true);
        } catch (Throwable t) { return null; }
    }
    static String toDataUrl(Bitmap b) { java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.JPEG, 82, o); return "data:image/jpeg;base64," + Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP); }
}
