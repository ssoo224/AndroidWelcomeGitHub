package com.babylon.chat.iq;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.*;

import static com.babylon.chat.iq.GroupsKit.*;

/** معلومات المجموعة + الشاشات الفرعية (المشرفون، محرر المشرف، الصلاحيات، المحظورون، اختيار مشرف) بنفس تصميم الـ HTML. */
public class GroupInfoActivity extends Activity {
    private String chatId; private Map<String, Object> d = new HashMap<>(); private ListenerRegistration reg; private Th t;
    private FrameLayout stack; private LinearLayout infoBody; private ScrollView infoScroll;
    private final ArrayList<View> subs = new ArrayList<>(); private Runnable subRefresh;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b); t = th(this); window(this, t, t.bg); chatId = getIntent().getStringExtra("chatId"); if (chatId == null) { finish(); return; }
        stack = new FrameLayout(this); stack.setBackgroundColor(t.bg); rtl(stack); stack.setFitsSystemWindows(true); setContentView(stack);
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        col.addView(topBar("معلومات المجموعة", null, v -> finish()));
        infoScroll = new ScrollView(this); infoScroll.setOverScrollMode(View.OVER_SCROLL_NEVER); infoBody = new LinearLayout(this); infoBody.setOrientation(LinearLayout.VERTICAL); infoScroll.addView(infoBody); col.addView(infoScroll, lpw(-1, 0, 1));
        stack.addView(col, new FrameLayout.LayoutParams(-1, -1));
        reg = db().collection("chats").document(chatId).addSnapshotListener((doc, e) -> {
            if (doc == null) return; if (!doc.exists() || !parts(doc.getData()).contains(me())) { finish(); return; }
            d = doc.getData(); List<String> ids = new ArrayList<>(parts(d)); ids.addAll(strList(d, "banned")); loadUsers(ids, () -> { render(); if (subRefresh != null) subRefresh.run(); String pu = getIntent().getStringExtra("popupUid"); if (pu != null) { getIntent().removeExtra("popupUid"); if (parts(d).contains(pu)) memberAction(pu, true); } });
        });
    }
    @Override protected void onDestroy() { super.onDestroy(); if (reg != null) reg.remove(); }
    @Override public void onBackPressed() { if (!subs.isEmpty()) closeSub(); else super.onBackPressed(); }

    // ---------- هياكل مشتركة ----------
    private View topBar(String title, String sub, View.OnClickListener back) {
        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL); top.setPadding(t.u(8), t.u(10), t.u(8), t.u(8));
        FrameLayout bk = new FrameLayout(this); bk.addView(icon(this, IC_BACK, t.text, 22), center(this, 22)); bk.setOnClickListener(back); top.addView(bk, lp(t.k(42), t.k(42)));
        LinearLayout tt = new LinearLayout(this); tt.setOrientation(LinearLayout.VERTICAL); tt.setPadding(t.u(4), 0, 0, 0); tt.addView(tv(this, t, title, 18, t.text, true));
        if (sub != null) { TextView s = tv(this, t, sub, 12.5f, t.sub, false); LinearLayout.LayoutParams sp = lp(-2, -2); sp.topMargin = t.u(1); tt.addView(s, sp); } top.addView(tt); return top;
    }
    private LinearLayout card(LinearLayout parent) { LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setBackground(round(t.card, t.u(18))); LinearLayout.LayoutParams p = lp(-1, -2); p.setMargins(t.u(12), t.u(12), t.u(12), 0); parent.addView(c, p); return c; }
    private void head(LinearLayout card, String text, String count) {
        LinearLayout h = new LinearLayout(this); h.setPadding(t.u(16), t.u(13), t.u(16), t.u(5)); h.addView(tv(this, t, text, 13, t.gold, true));
        if (count != null) { TextView c = tv(this, t, count, 13, t.sub, true); c.setPadding(t.u(4), 0, 0, 0); h.addView(c); } card.addView(h);
    }
    private LinearLayout row(LinearLayout card, String ic, int icColor, int icBg, String label, int labelColor, String val, boolean chev, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(this); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(t.u(16), t.u(14), t.u(16), t.u(14));
        if (ic != null) { FrameLayout f = new FrameLayout(this); f.setBackground(oval(icBg)); f.addView(icon(this, ic, icColor, 20), center(this, 20)); r.addView(f, lp(t.k(36), t.k(36))); }
        TextView l = tv(this, t, label, 15.5f, labelColor, false); l.setPadding(ic != null ? t.u(13) : 0, 0, 0, 0); r.addView(l, lpw(0, -2, 1));
        if (val != null) r.addView(tv(this, t, val, 13.5f, t.sub, false));
        if (chev) { Ic c = icon(this, IC_CHEV, t.sub, 18); c.setAlpha(.5f); LinearLayout.LayoutParams cp = lp(t.u(18), t.u(18)); cp.setMarginStart(t.u(8)); r.addView(c, cp); }
        if (click != null) r.setOnClickListener(click); card.addView(r); return r;
    }
    private void sepLine(LinearLayout card) { View s = new View(this); s.setBackgroundColor(t.sep); card.addView(s, lp(-1, 1)); }
    private TextView note(LinearLayout parent, String text) { TextView n = tv(this, t, text, 13, t.sub, false); n.setLineSpacing(0, 1.3f); LinearLayout.LayoutParams p = lp(-1, -2); p.setMargins(t.u(22), t.u(10), t.u(22), 0); parent.addView(n, p); return n; }
    private void upd(Map<String, Object> m) { db().collection("chats").document(chatId).update(m).addOnFailureListener(e -> toast(this, "تعذر تنفيذ العملية")); }
    private void upd(String k, Object v) { Map<String, Object> m = new HashMap<>(); m.put(k, v); upd(m); }
    private int rank(String u) { return u.equals(s(d, "ownerId")) ? 0 : (map(d, "admins").containsKey(u) ? 1 : 2); }
    private View roleTag(String u) { String tt = title(u, d); if (tt.isEmpty()) return null; TextView v = tv(this, t, tt, 12, role(u, d).equals("member") ? t.sub : t.gold, false); v.setSingleLine(true); v.setEllipsize(android.text.TextUtils.TruncateAt.END); v.setMaxWidth(Math.round(getResources().getDisplayMetrics().widthPixels * .42f)); return v; }
    private LinearLayout memberRow(LinearLayout card, String u, String subOverride, boolean tag, String tagText) {
        U g = user(u); LinearLayout r = new LinearLayout(this); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(t.u(16), t.u(9), t.u(16), t.u(9));
        Av av = new Av(this, 48 * t.s); av.setUser(u); r.addView(av, lp(t.k(48), t.k(48)));
        LinearLayout x = new LinearLayout(this); x.setOrientation(LinearLayout.VERTICAL); x.setPadding(t.u(13), 0, t.u(13), 0);
        boolean muted = isTrue(map(d, "muted").get(u)) && rank(u) == 2;
        String nm = g.name + (u.equals(me()) ? "  (أنت)" : "") + (muted ? "  · مقيّد" : ""); TextView n = tv(this, t, nm, 15.5f, t.text, true); n.setSingleLine(true); n.setEllipsize(android.text.TextUtils.TruncateAt.END); x.addView(n);
        String sub = subOverride != null ? subOverride : (g.username.isEmpty() ? "" : "@" + g.username);
        if (!sub.isEmpty()) { TextView sv = tv(this, t, sub, 13, t.sub, false); LinearLayout.LayoutParams sp = lp(-2, -2); sp.topMargin = t.u(1); x.addView(sv, sp); }
        r.addView(x, lpw(0, -2, 1));
        if (tag) { View tg = tagText != null ? tv(this, t, tagText, 12, t.sub, false) : roleTag(u); if (tg != null) r.addView(tg); }
        card.addView(r); return r;
    }

    // ---------- الشاشة الرئيسية ----------
    private void render() {
        final int st = infoScroll.getScrollY(); infoBody.removeAllViews(); final String me = me(); final boolean owner = s(d, "ownerId").equals(me), canInfo = right("info", me, d), canInvite = right("invite", me, d), canBan = right("ban", me, d), isStaff = !role(me, d).equals("member");
        final List<String> ps = parts(d); boolean muted = isMuted(this, chatId);
        // الهيرو
        LinearLayout hero = new LinearLayout(this); hero.setOrientation(LinearLayout.VERTICAL); hero.setGravity(Gravity.CENTER_HORIZONTAL); hero.setPadding(t.u(16), t.u(14), t.u(16), t.u(6));
        FrameLayout avw = new FrameLayout(this); avw.setClipChildren(false); Av av = new Av(this, 104 * t.s); av.setGroup(d); avw.addView(av, new FrameLayout.LayoutParams(t.k(104), t.k(104)));
        if (canInfo) {
            FrameLayout cam = new FrameLayout(this); GradientDrawable_set(cam); cam.addView(new Ic(this, IC_CAM_FILL, 0xFFFFFFFF, 18, true), center(this, 18));
            avw.addView(cam, new FrameLayout.LayoutParams(t.k(34), t.k(34), Gravity.BOTTOM | Gravity.LEFT)); avw.setOnClickListener(v -> { Intent i = new Intent(Intent.ACTION_GET_CONTENT); i.setType("image/*"); startActivityForResult(i, 5); });
        }
        LinearLayout.LayoutParams ap = lp(t.k(104), t.k(104)); ap.bottomMargin = t.u(12); hero.addView(avw, ap);
        LinearLayout nm = new LinearLayout(this); nm.setGravity(Gravity.CENTER_VERTICAL); TextView ntv = tv(this, t, s(d, "name").isEmpty() ? "مجموعة" : s(d, "name"), 21, t.text, true); nm.addView(ntv);
        if (canInfo) { Ic pen = icon(this, IC_PEN, t.text, 16); pen.setAlpha(.55f); LinearLayout.LayoutParams pp = lp(t.u(16), t.u(16)); pp.setMarginStart(t.u(7)); nm.addView(pen, pp);
            nm.setOnClickListener(v -> ask(this, t, "اسم المجموعة", null, s(d, "name"), "اسم المجموعة", 40, false, "حفظ", "إلغاء", (ok, val) -> { if (!ok) return; if (val.isEmpty()) { toast(this, "الاسم لا يمكن أن يكون فارغاً"); return; } if (val.equals(s(d, "name"))) return; Map<String, Object> up = addSystem(chatId, "rename", null, Collections.<String, Object>singletonMap("name", val)); up.put("name", val); upd(up); })); }
        hero.addView(nm); TextView sub = tv(this, t, memberWord(ps.size()), 13.5f, t.sub, false); LinearLayout.LayoutParams sp = lp(-2, -2); sp.topMargin = t.u(3); hero.addView(sub, sp); infoBody.addView(hero);
        // الإشعارات
        LinearLayout c1 = card(infoBody); row(c1, muted ? IC_BELL_OFF : IC_BELL, 0xFFD4AF37, 0x24D4AF37, "الإشعارات", t.text, muted ? "مكتومة" : "مفعّلة", false, v -> { setMuted(this, chatId, !isMuted(this, chatId)); toast(this, isMuted(this, chatId) ? "تم كتم هذه المجموعة على جهازك" : "تم إلغاء كتم المحادثة"); render(); });
        // الوصف
        if (!s(d, "about").isEmpty() || canInfo) {
            LinearLayout c = card(infoBody); head(c, "الوصف", null); TextView ab = tv(this, t, s(d, "about").isEmpty() ? "أضف وصفاً للمجموعة" : s(d, "about"), 15, s(d, "about").isEmpty() ? t.gold : t.text, false); ab.setLineSpacing(0, 1.4f); ab.setPadding(t.u(16), t.u(4), t.u(16), t.u(16)); c.addView(ab);
            if (canInfo) ab.setOnClickListener(v -> ask(this, t, "وصف المجموعة", null, s(d, "about"), "اكتب وصفاً للمجموعة", 255, true, "حفظ", "إلغاء", (ok, val) -> { if (!ok || val.equals(s(d, "about"))) return; Map<String, Object> up = addSystem(chatId, "about", null, null); up.put("about", val); upd(up); toast(this, "تم تحديث الوصف"); }));
        }
        // الإدارة
        if (isStaff) {
            LinearLayout c = card(infoBody); head(c, "الإدارة", null); int admins = 0; for (String u : ps) if (rank(u) <= 1) admins++; int bannedN = strList(d, "banned").size(); int permsOn = 0; Map<String, Object> pm = map(d, "perms"); for (int i = 0; i < MEMBER_PERMS.length; i++) { Object v = pm.get(MEMBER_PERMS[i][0]); if (v == null ? MEMBER_DEF[i] : isTrue(v)) permsOn++; }
            row(c, IC_SHIELD, 0xFF3B82F6, 0x243B82F6, "المشرفون", t.text, String.valueOf(admins), true, v -> openAdmins());
            if (canBan) { row(c, IC_KEY, 0xFFD4AF37, 0x24D4AF37, "صلاحيات الأعضاء", t.text, permsOn + "/" + MEMBER_PERMS.length, true, v -> openPerms()); row(c, IC_BAN, 0xFFEF4444, 0x21EF4444, "المحظورون", t.text, String.valueOf(bannedN), true, v -> openBanned()); }
        }
        // الأعضاء
        LinearLayout mc = card(infoBody); head(mc, "الأعضاء", String.valueOf(ps.size()));
        if (canInvite && ps.size() < GROUP_MAX) row(mc, IC_PLUS, t.gold, 0x29E4C45B, "إضافة أعضاء", t.gold, null, false, v -> { Intent i = new Intent(this, GroupPickerActivity.class); i.putExtra("mode", "add"); i.putExtra("chatId", chatId); startActivity(i); });
        List<String> sorted = new ArrayList<>(ps); Collections.sort(sorted, (a, bb) -> { int ra = rank(a), rb = rank(bb); return ra != rb ? ra - rb : user(a).name.compareTo(user(bb).name); });
        for (final String u : sorted) memberRow(mc, u, null, true, null).setOnClickListener(v -> memberAction(u, false));
        // مغادرة
        LinearLayout lc = card(infoBody); row(lc, IC_LEAVE, 0xFFEF4444, 0x21EF4444, "مغادرة المجموعة", DANGER, null, false, v -> leave());
        infoBody.addView(new View(this), lp(1, t.k(30))); infoScroll.post(() -> infoScroll.scrollTo(0, st));
    }
    private void GradientDrawable_set(FrameLayout cam) { android.graphics.drawable.GradientDrawable g = goldGrad(0, true); g.setStroke(t.u(3), t.bg); cam.setBackground(g); }

    // ---------- الشاشات الفرعية ----------
    private LinearLayout openSub(String title, String sub) {
        closeSubTop(); FrameLayout ov = new FrameLayout(this); ov.setBackgroundColor(t.bg); ov.setClickable(true);
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); col.addView(topBar(title, sub, v -> closeSub()));
        ScrollView sv = new ScrollView(this); sv.setOverScrollMode(View.OVER_SCROLL_NEVER); LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); sv.addView(body); col.addView(sv, lpw(-1, 0, 1));
        ov.addView(col, new FrameLayout.LayoutParams(-1, -1)); ov.setTag(body); stack.addView(ov, new FrameLayout.LayoutParams(-1, -1)); subs.add(ov);
        ov.setAlpha(0f); ov.setTranslationY(t.u(14)); ov.animate().alpha(1f).translationY(0).setDuration(220).start(); return body;
    }
    private void closeSubTop() { /* الشاشة الفرعية تُستبدل: نزيل الحالية فقط عندما تكون بنفس المستوى */ }
    private void closeSub() { if (subs.isEmpty()) return; View v = subs.remove(subs.size() - 1); stack.removeView(v); subRefresh = null; }
    private void closeAllSubs() { while (!subs.isEmpty()) closeSub(); }

    private void openAdmins() {
        final boolean owner = s(d, "ownerId").equals(me()); final LinearLayout body = openSub("المشرفون", "الإدارة");
        subRefresh = () -> {
            body.removeAllViews(); List<String> ids = new ArrayList<>(); for (String u : parts(d)) if (u.equals(s(d, "ownerId")) || map(d, "admins").containsKey(u)) ids.add(u);
            Collections.sort(ids, (a, b) -> a.equals(s(d, "ownerId")) ? -1 : b.equals(s(d, "ownerId")) ? 1 : 0); LinearLayout c = card(body);
            if (owner) row(c, IC_PLUS, t.gold, 0x29E4C45B, "إضافة مشرف", t.gold, null, false, v -> pickAdmin());
            for (final String u : ids) {
                String rs; if (u.equals(s(d, "ownerId"))) rs = "كل الصلاحيات"; else { int n = 0; Object a = map(d, "admins").get(u); for (String[] r : ADMIN_RIGHTS) if (a instanceof Map && isTrue(((Map<?, ?>) a).get(r[0]))) n++; rs = n + " من " + ADMIN_RIGHTS.length + " صلاحيات"; }
                memberRow(c, u, rs, true, null).setOnClickListener(v -> { if (u.equals(s(d, "ownerId"))) { if (owner) changeTitle(u); return; } if (owner) adminEditor(u, false); else if (u.equals(me())) changeTitle(u); });
            }
            note(body, "المشرفون يستطيعون إدارة المجموعة حسب الصلاحيات التي تمنحها لهم. فقط المالك يعيّن المشرفين ويزيلهم.");
        };
        subRefresh.run();
    }
    private void pickAdmin() {
        LinearLayout body = openSub("اختر عضواً", "تعيين مشرف"); subRefresh = null; List<String> ids = new ArrayList<>(); for (String u : parts(d)) if (!u.equals(s(d, "ownerId")) && !map(d, "admins").containsKey(u)) ids.add(u);
        if (ids.isEmpty()) { TextView e = tv(this, t, "لا يوجد أعضاء متاحون للتعيين.", 14, t.sub, false); e.setGravity(Gravity.CENTER); e.setPadding(t.u(24), t.u(40), t.u(24), 0); body.addView(e); return; }
        for (final String u : ids) memberRow(body, u, null, false, null).setOnClickListener(v -> adminEditor(u, true));
    }
    private void adminEditor(final String uid, final boolean isNew) {
        final U g = user(uid); final boolean[] cur = new boolean[ADMIN_RIGHTS.length]; Object ex = isNew ? null : map(d, "admins").get(uid);
        for (int i = 0; i < cur.length; i++) cur[i] = ex instanceof Map && ((Map<?, ?>) ex).containsKey(ADMIN_RIGHTS[i][0]) ? isTrue(((Map<?, ?>) ex).get(ADMIN_RIGHTS[i][0])) : ADMIN_DEF[i];
        final LinearLayout body = openSub(isNew ? "تعيين مشرف" : "صلاحيات المشرف", g.name); subRefresh = null;
        LinearLayout hero = new LinearLayout(this); hero.setOrientation(LinearLayout.VERTICAL); hero.setGravity(Gravity.CENTER_HORIZONTAL); hero.setPadding(t.u(16), t.u(14), t.u(16), 0);
        Av av = new Av(this, 84 * t.s); av.setUser(uid); hero.addView(av, lp(t.k(84), t.k(84))); TextView hn = tv(this, t, g.name, 19, t.text, true); LinearLayout.LayoutParams hp = lp(-2, -2); hp.topMargin = t.u(10); hero.addView(hn, hp); body.addView(hero);
        LinearLayout tc = card(body); head(tc, "اللقب المخصص", null); final EditText et = new EditText(this); et.setText(s(map(d, "titles"), uid)); et.setHint("مثال: المدير، الأستاذ، حارس الشلة"); et.setHintTextColor(t.sub & 0x00FFFFFF | 0x66000000); et.setTextColor(t.text); et.setTextSize(15.5f * t.s); et.setSingleLine(true);
        et.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(16)}); android.graphics.drawable.GradientDrawable eb = round(t.dark ? t.chip : 0xFFFBFCFE, t.u(12)); eb.setStroke(Math.max(1, t.u(1.5f)), t.dark ? 0x0FFFFFFF : 0xFFE8EDF3); et.setBackground(eb); et.setPadding(t.u(13), t.u(11), t.u(13), t.u(11));
        LinearLayout.LayoutParams ep = lp(-1, -2); ep.setMargins(t.u(14), t.u(4), t.u(14), t.u(14)); tc.addView(et, ep);
        LinearLayout rc = card(body); head(rc, "ما يستطيع هذا المشرف فعله", null); final Sw[] sws = new Sw[cur.length];
        for (int i = 0; i < cur.length; i++) { final int k = i; LinearLayout r = row(rc, null, 0, 0, ADMIN_RIGHTS[i][1], t.text, null, false, null); sws[i] = new Sw(this, t, cur[i]); r.addView(sws[i]); r.setOnClickListener(v -> { cur[k] = !cur[k]; sws[k].set(cur[k]); }); }
        if (!isNew) { LinearLayout dc = card(body); row(dc, null, 0, 0, "إزالة من الإشراف", DANGER, null, false, v -> confirm(this, t, "إزالة من الإشراف", "هل تريد إزالة <b>" + esc(g.name) + "</b> من الإشراف؟", "إزالة", (ok, x) -> { if (!ok) return;
            Map<String, Object> up = addSystem(chatId, "demote", Collections.singletonList(uid), null); up.put("admins." + uid, FieldValue.delete()); if (!s(map(d, "titles"), uid).isEmpty()) up.put("titles." + uid, FieldValue.delete()); upd(up); toast(this, "تمت إزالة المشرف"); closeSub(); closeAllSubs(); openAdmins(); })); }
        body.addView(new View(this), lp(1, t.k(100)));
        FrameLayout fab = fab(IC_CHECK, true); FrameLayout host = (FrameLayout) subs.get(subs.size() - 1); host.addView(fab, fabLp());
        fab.setOnClickListener(v -> { String title = et.getText().toString().trim(); Map<String, Object> up = new HashMap<>(); Map<String, Object> rights = new HashMap<>(); for (int i = 0; i < cur.length; i++) rights.put(ADMIN_RIGHTS[i][0], cur[i]); up.put("admins." + uid, rights);
            if (!title.isEmpty()) up.put("titles." + uid, title); else if (!s(map(d, "titles"), uid).isEmpty()) up.put("titles." + uid, FieldValue.delete());
            if (isNew) up.putAll(addSystem(chatId, "promote", Collections.singletonList(uid), null)); upd(up); toast(this, isNew ? "تم تعيين المشرف" : "تم حفظ الصلاحيات"); closeAllSubs(); openAdmins(); });
    }
    private void changeTitle(final String uid) {
        ask(this, t, "اللقب", uid.equals(me()) ? null : esc(user(uid).name), s(map(d, "titles"), uid), "اللقب (اختياري)", 16, false, "حفظ", "إلغاء", (ok, v) -> { if (!ok) return; upd("titles." + uid, v.isEmpty() ? FieldValue.delete() : v); toast(this, "تم تحديث اللقب"); });
    }
    private void openPerms() {
        final LinearLayout body = openSub("صلاحيات الأعضاء", "ما الذي يستطيع الأعضاء فعله");
        subRefresh = () -> {
            body.removeAllViews(); LinearLayout c = card(body); head(c, "الأعضاء يستطيعون", null); final Map<String, Object> pm = map(d, "perms");
            for (int i = 0; i < MEMBER_PERMS.length; i++) { final String k = MEMBER_PERMS[i][0]; Object v = pm.get(k); final boolean on = v == null ? MEMBER_DEF[i] : isTrue(v); LinearLayout r = row(c, null, 0, 0, MEMBER_PERMS[i][1], t.text, null, false, null); Sw sw = new Sw(this, t, on); r.addView(sw);
                r.setOnClickListener(x -> { Map<String, Object> up = addSystem(chatId, "perms", null, null); up.put("perms." + k, !on); upd(up); }); }
            note(body, "هذه الصلاحيات تخص الأعضاء العاديين فقط، أما المالك والمشرفون فيستطيعون الإرسال دائماً. يمكنك أيضاً تقييد عضو معيّن من قائمة الأعضاء.");
        };
        subRefresh.run();
    }
    private void openBanned() {
        final LinearLayout body = openSub("المحظورون", "لا يمكن إضافتهم مرة أخرى");
        subRefresh = () -> {
            body.removeAllViews(); List<String> ids = strList(d, "banned");
            if (ids.isEmpty()) { TextView e = tv(this, t, "لا يوجد أعضاء محظورون.", 14, t.sub, false); e.setGravity(Gravity.CENTER); e.setPadding(t.u(24), t.u(40), t.u(24), 0); body.addView(e); return; }
            LinearLayout c = card(body);
            for (final String u : ids) memberRow(c, u, null, true, "إلغاء الحظر").setOnClickListener(v -> confirmGold(this, t, "إلغاء الحظر", "السماح لـ <b>" + esc(user(u).name) + "</b> بالانضمام مجدداً؟", "إلغاء الحظر", "تراجع", (ok, x) -> { if (!ok) return; upd("banned", FieldValue.arrayRemove(u)); toast(this, "تم إلغاء الحظر"); }));
        };
        loadUsers(strList(d, "banned"), () -> subRefresh.run());
    }
    private FrameLayout fab(String ic, boolean on) { FrameLayout f = goldFab(this, t, ic, 2.6f); f.setAlpha(on ? 1f : .45f); return f; }
    private FrameLayout.LayoutParams fabLp() { FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(t.k(54), t.k(54), Gravity.BOTTOM | Gravity.LEFT); p.leftMargin = t.u(18); p.bottomMargin = t.u(22); return p; }
    static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    // ---------- إجراءات العضو ----------
    private void memberAction(final String uid, boolean fromChat) {
        final String me = me(); final String myRole = role(me, d), tRole = role(uid, d); final boolean isOwner = myRole.equals("owner"); List<String[]> acts = new ArrayList<>();
        if (isOwner && !uid.equals(me)) acts.add(tRole.equals("admin") ? new String[]{"adm", "تعديل صلاحيات المشرف"} : new String[]{"prom", "تعيين كمشرف"});
        if (isOwner || (myRole.equals("admin") && uid.equals(me))) acts.add(new String[]{"title", "تغيير اللقب"});
        boolean canAct = !uid.equals(me) && !tRole.equals("owner") && right("ban", me, d) && (tRole.equals("member") || isOwner);
        if (canAct && tRole.equals("member")) acts.add(new String[]{"mute", isTrue(map(d, "muted").get(uid)) ? "إلغاء تقييد الكتابة" : "تقييد من الكتابة"});
        if (canAct) { acts.add(new String[]{"remove", "إزالة من المجموعة", "danger"}); acts.add(new String[]{"ban", "إزالة وحظر", "danger"}); }
        memberPopup(this, t, uid, d, acts, fromChat, key -> {
            switch (key) {
                case "msg": { Intent i = new Intent(this, MassActivity.class); i.putExtra(MsgNotifier.EXTRA_UID, uid); i.putExtra(MsgNotifier.EXTRA_NAME, user(uid).name); i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP); startActivity(i); break; }
                case "profile": { if (uid.equals(me)) { toast(this, "هذا ملفك الشخصي"); break; } String un = user(uid).username; if (un.isEmpty()) { toast(this, "لا يوجد اسم مستخدم لهذا العضو"); break; }
                    Intent i = new Intent(this, MassActivity.class); i.setData(android.net.Uri.parse("babylon://u/" + un)); i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP); startActivity(i); break; }
                case "mention": { String un = user(uid).username; if (un.isEmpty()) { toast(this, "لا يوجد اسم مستخدم لهذا العضو"); break; } Intent r = new Intent(); r.putExtra("mention", un); setResult(RESULT_OK, r); finish(); break; }
                case "prom": case "adm": adminEditor(uid, key.equals("prom")); break;
                case "title": changeTitle(uid); break;
                case "mute": { boolean on = !isTrue(map(d, "muted").get(uid)); upd("muted." + uid, on ? (Object) true : FieldValue.delete()); toast(this, on ? "تم تقييد العضو من الكتابة" : "تم إلغاء التقييد"); break; }
                case "remove": case "ban": {
                    final boolean ban = key.equals("ban"); final String nm = esc(user(uid).name);
                    confirm(this, t, ban ? "إزالة وحظر" : "إزالة العضو", "هل تريد " + (ban ? "إزالة وحظر " : "إزالة ") + "<b>" + nm + "</b> " + (ban ? "من المجموعة؟ لن يستطيع أحد إضافته مجدداً قبل رفع الحظر." : "من المجموعة؟"), ban ? "إزالة وحظر" : "إزالة", (ok, x) -> {
                        if (!ok) return; Map<String, Object> up = addSystem(chatId, ban ? "ban" : "remove", Collections.singletonList(uid), null); up.put("participants", FieldValue.arrayRemove(uid)); if (ban) up.put("banned", FieldValue.arrayUnion(uid));
                        if (!s(map(d, "titles"), uid).isEmpty()) up.put("titles." + uid, FieldValue.delete()); if (isTrue(map(d, "muted").get(uid))) up.put("muted." + uid, FieldValue.delete()); if (map(d, "admins").containsKey(uid) && isOwner) up.put("admins." + uid, FieldValue.delete());
                        upd(up); toast(this, ban ? "تمت الإزالة والحظر" : "تمت إزالة العضو"); });
                    break; }
            }
        });
    }
    private void leave() {
        final String me = me(); final boolean owner = s(d, "ownerId").equals(me); final List<String> rest = new ArrayList<>(parts(d)); rest.remove(me);
        confirm(this, t, "مغادرة المجموعة", "هل تريد مغادرة <b>" + esc(s(d, "name")) + "</b>؟" + (owner && !rest.isEmpty() ? " ستنتقل ملكية المجموعة لعضو آخر." : ""), "مغادرة", (ok, x) -> {
            if (!ok) return; if (rest.isEmpty()) { db().collection("chats").document(chatId).delete().addOnCompleteListener(tk -> finish()); return; }
            Map<String, Object> up = addSystem(chatId, "leave", null, null); up.put("participants", FieldValue.arrayRemove(me)); up.put("admins." + me, FieldValue.delete());
            if (owner) { String nw = rest.get(0); for (String u : rest) if (map(d, "admins").containsKey(u)) { nw = u; break; } up.put("ownerId", nw); up.put("admins." + nw, FieldValue.delete()); }
            db().collection("chats").document(chatId).update(up).addOnCompleteListener(tk -> { toast(this, "غادرت المجموعة"); finish(); }); });
    }
    @Override protected void onActivityResult(int rc, int res, Intent in) {
        super.onActivityResult(rc, res, in);
        if (rc == 5 && res == RESULT_OK && in != null && in.getData() != null) { Bitmap bm = decodeSquare(getContentResolver(), in.getData(), 320); if (bm == null) { toast(this, "تعذر تجهيز الصورة"); return; }
            Map<String, Object> up = addSystem(chatId, "photo", null, null); up.put("photo", toDataUrl(bm)); upd(up); toast(this, "تم تغيير صورة المجموعة"); }
    }
}
