package com.babylon.chat.iq;

import android.app.Activity;
import android.app.AlertDialog;
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

/** معلومات المجموعة: الاسم والصورة، الأعضاء بالألقاب، إدارة المشرفين والصلاحيات والحظر، المغادرة. */
public class GroupInfoActivity extends Activity {
    private String chatId; private Map<String, Object> d = new HashMap<>(); private ListenerRegistration reg; private LinearLayout body; private boolean loaded;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b); style(this); chatId = getIntent().getStringExtra("chatId"); if (chatId == null) { finish(); return; }
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG); rtl(root); root.setFitsSystemWindows(true);
        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL); top.setPadding(dp(this, 8), dp(this, 10), dp(this, 8), dp(this, 8));
        FrameLayout bk = new FrameLayout(this); bk.addView(icon(this, IC_BACK, TEXT, 22), new FrameLayout.LayoutParams(dp(this, 22), dp(this, 22), Gravity.CENTER)); bk.setOnClickListener(v -> finish());
        top.addView(bk, new LinearLayout.LayoutParams(dp(this, 42), dp(this, 42))); top.addView(tv(this, "معلومات المجموعة", 18, TEXT, true)); root.addView(top);
        ScrollView sv = new ScrollView(this); body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); sv.addView(body); root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        reg = db().collection("chats").document(chatId).addSnapshotListener((doc, e) -> {
            if (doc == null) return;
            if (!doc.exists() || !parts(doc.getData()).contains(me())) { finish(); return; }
            d = doc.getData(); loadUsers(parts(d), this::render);
        });
    }
    @Override protected void onDestroy() { super.onDestroy(); if (reg != null) reg.remove(); }
    private void upd(Map<String, Object> m) { db().collection("chats").document(chatId).update(m).addOnFailureListener(e -> toast(this, "تعذر تنفيذ العملية")); }
    private void upd(String k, Object v) { upd(Collections.singletonMap(k, v)); }

    private void render() {
        body.removeAllViews(); final String me = me(); final boolean owner = s(d, "ownerId").equals(me), canInfo = right("info", me, d), canInvite = right("invite", me, d), canBan = right("ban", me, d);
        List<String> ps = parts(d);
        // الهيرو
        LinearLayout hero = new LinearLayout(this); hero.setOrientation(LinearLayout.VERTICAL); hero.setGravity(Gravity.CENTER_HORIZONTAL); hero.setPadding(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 6));
        FrameLayout avw = new FrameLayout(this); Av av = new Av(this, 104); av.setGroup(d); avw.addView(av);
        if (canInfo) { FrameLayout cam = new FrameLayout(this); android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, new int[]{0xFFE6C566, 0xFFD3A93B, 0xFFBC8E24}); g.setShape(android.graphics.drawable.GradientDrawable.OVAL); g.setStroke(dp(this, 3), BG); cam.setBackground(g);
            cam.addView(icon(this, IC_CAM, 0xFFFFFFFF, 17), new FrameLayout.LayoutParams(dp(this, 17), dp(this, 17), Gravity.CENTER)); avw.addView(cam, new FrameLayout.LayoutParams(dp(this, 34), dp(this, 34), Gravity.BOTTOM | Gravity.LEFT));
            avw.setOnClickListener(v -> { Intent i = new Intent(Intent.ACTION_GET_CONTENT); i.setType("image/*"); startActivityForResult(i, 5); }); }
        hero.addView(avw, new LinearLayout.LayoutParams(dp(this, 104), dp(this, 104)));
        LinearLayout nm = new LinearLayout(this); nm.setGravity(Gravity.CENTER); nm.setPadding(0, dp(this, 12), 0, 0);
        nm.addView(tv(this, s(d, "name"), 21, TEXT, true));
        if (canInfo) { View e = icon(this, IC_EDIT, SUB, 16); e.setAlpha(.7f); LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(dp(this, 16), dp(this, 16)); ep.setMargins(dp(this, 7), 0, 0, 0); nm.addView(e, ep);
            nm.setOnClickListener(v -> ask("اسم المجموعة", s(d, "name"), 40, val -> { if (val.isEmpty() || val.equals(s(d, "name"))) return; Map<String, Object> up = addSystem(chatId, "rename", null, Collections.<String, Object>singletonMap("name", val)); up.put("name", val); upd(up); })); }
        hero.addView(nm);
        hero.addView(tv(this, memberWord(ps.size()), 13.5f, SUB, false)); body.addView(hero);

        // وصف
        if (canInfo || !s(d, "about").isEmpty()) {
            LinearLayout c = card(); LinearLayout r = row(null, s(d, "about").isEmpty() ? "إضافة وصف" : s(d, "about"), canInfo ? GOLD : TEXT, null);
            if (canInfo) r.setOnClickListener(v -> ask("وصف المجموعة", s(d, "about"), 255, val -> { Map<String, Object> up = addSystem(chatId, "about", null, null); up.put("about", val); upd(up); })); c.addView(r); body.addView(c);
        }
        // الأعضاء
        LinearLayout mc = card(); TextView hd = tv(this, "الأعضاء  " + ps.size(), 13, GOLD, true); hd.setPadding(dp(this, 16), dp(this, 13), dp(this, 16), dp(this, 5)); mc.addView(hd);
        if (canInvite && ps.size() < GROUP_MAX) { LinearLayout r = row(IC_USER_PLUS, "إضافة أعضاء", GOLD, null); r.setOnClickListener(v -> { Intent i = new Intent(this, GroupPickerActivity.class); i.putExtra("mode", "add"); i.putExtra("chatId", chatId); startActivity(i); }); mc.addView(r); }
        List<String> sorted = new ArrayList<>(ps);
        Collections.sort(sorted, (a, b) -> { int ra = rank(a), rb = rank(b); return ra != rb ? ra - rb : user(a).name.compareTo(user(b).name); });
        for (final String u : sorted) {
            LinearLayout r = new LinearLayout(this); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(dp(this, 16), dp(this, 9), dp(this, 16), dp(this, 9));
            Av a = new Av(this, 46); a.setUser(u); r.addView(a);
            LinearLayout t = new LinearLayout(this); t.setOrientation(LinearLayout.VERTICAL); t.setPadding(dp(this, 13), 0, 0, 0);
            t.addView(tv(this, userName(u), 15.5f, TEXT, true)); if (!user(u).username.isEmpty()) t.addView(tv(this, "@" + user(u).username, 13, SUB, false)); r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
            String tt = title(u, d); if (!tt.isEmpty()) r.addView(tv(this, tt, 12, role(u, d).equals("member") ? SUB : GOLD, false));
            if (!u.equals(me)) r.setOnClickListener(v -> memberMenu(u)); mc.addView(r);
        }
        body.addView(mc);
        // صلاحيات الأعضاء (للمالك)
        if (owner) { LinearLayout c = card(); LinearLayout r = row(null, "صلاحيات الأعضاء", TEXT, null); r.setOnClickListener(v -> permsDialog()); c.addView(r); body.addView(c); }
        // مغادرة
        LinearLayout lc = card(); LinearLayout lr = row(IC_EXIT, "مغادرة المجموعة", DANGER, null); lr.setOnClickListener(v -> leave()); lc.addView(lr); body.addView(lc);
        View sp = new View(this); body.addView(sp, new LinearLayout.LayoutParams(1, dp(this, 30)));
    }
    private int rank(String u) { return u.equals(s(d, "ownerId")) ? 0 : (map(d, "admins").containsKey(u) ? 1 : 2); }
    private LinearLayout card() { LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setBackground(round(CARD, 18, this)); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(dp(this, 12), dp(this, 12), dp(this, 12), 0); c.setLayoutParams(p); return c; }
    private LinearLayout row(String ic, String label, int color, String val) {
        LinearLayout r = new LinearLayout(this); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 14));
        if (ic != null) { FrameLayout f = new FrameLayout(this); f.setBackground(round(0x12FFFFFF, 18, this)); f.addView(icon(this, ic, color, 18), new FrameLayout.LayoutParams(dp(this, 18), dp(this, 18), Gravity.CENTER)); r.addView(f, new LinearLayout.LayoutParams(dp(this, 36), dp(this, 36))); }
        TextView t = tv(this, label, 15.5f, color, false); t.setPadding(ic != null ? dp(this, 13) : 0, 0, 0, 0); r.addView(t, new LinearLayout.LayoutParams(0, -2, 1)); if (val != null) r.addView(tv(this, val, 13.5f, SUB, false)); return r;
    }
    private interface Val { void ok(String v); }
    private void ask(String title, String init, int max, final Val cb) {
        final EditText e = new EditText(this); e.setText(init); e.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(max)}); e.setSelection(e.length());
        new AlertDialog.Builder(this).setTitle(title).setView(e).setPositiveButton("حفظ", (a, b) -> cb.ok(e.getText().toString().trim())).setNegativeButton("إلغاء", null).show();
    }

    // ---------- إدارة عضو ----------
    private void memberMenu(final String u) {
        final String me = me(); String r = role(u, d), myR = role(me, d); List<String> it = new ArrayList<>(); final List<Runnable> act = new ArrayList<>();
        boolean owner = myR.equals("owner");
        if (owner && r.equals("member")) { it.add("تعيين مشرفاً"); act.add(() -> promote(u)); }
        if (owner && r.equals("admin")) { it.add("إزالة من الإشراف"); act.add(() -> demote(u)); it.add("صلاحيات المشرف"); act.add(() -> adminRights(u)); }
        if (owner && !r.equals("owner")) { it.add("تعيين لقب"); act.add(() -> ask("اللقب المخصص", s(map(d, "titles"), u), 20, v -> upd("titles." + u, v.isEmpty() ? FieldValue.delete() : v))); }
        boolean canBan = right("ban", me, d) && !r.equals("owner") && (owner || r.equals("member"));
        if (canBan) {
            boolean muted = isTrue(map(d, "muted").get(u));
            if (r.equals("member")) { it.add(muted ? "إلغاء التقييد" : "تقييد من الكتابة"); act.add(() -> upd("muted." + u, muted ? FieldValue.delete() : true)); }
            it.add("إزالة من المجموعة"); act.add(() -> removeMember(u, false)); it.add("إزالة وحظر"); act.add(() -> removeMember(u, true));
        }
        if (it.isEmpty()) return;
        final String[] arr = it.toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle(user(u).name).setItems(arr, (a, w) -> act.get(w).run()).show();
    }
    private void promote(String u) {
        Map<String, Object> rights = new HashMap<>(); rights.put("info", false); rights.put("delete", true); rights.put("ban", true); rights.put("invite", true); rights.put("pin", true);
        Map<String, Object> up = addSystem(chatId, "promote", Collections.singletonList(u), null); up.put("admins." + u, rights); upd(up);
    }
    private void demote(String u) { Map<String, Object> up = addSystem(chatId, "demote", Collections.singletonList(u), null); up.put("admins." + u, FieldValue.delete()); upd(up); }
    private void removeMember(final String u, final boolean ban) {
        new AlertDialog.Builder(this).setTitle(ban ? "إزالة وحظر" : "إزالة العضو").setMessage("هل تريد " + (ban ? "إزالة وحظر " : "إزالة ") + user(u).name + "؟")
            .setPositiveButton("تأكيد", (a, b) -> { Map<String, Object> up = addSystem(chatId, ban ? "ban" : "remove", Collections.singletonList(u), null);
                up.put("participants", FieldValue.arrayRemove(u)); up.put("admins." + u, FieldValue.delete()); if (ban) up.put("banned", FieldValue.arrayUnion(u)); upd(up); })
            .setNegativeButton("إلغاء", null).show();
    }
    private void adminRights(final String u) {
        final String[][] R = ADMIN_RIGHTS; final boolean[] on = new boolean[R.length]; String[] labels = new String[R.length]; Object cur = map(d, "admins").get(u);
        for (int i = 0; i < R.length; i++) { labels[i] = R[i][1]; on[i] = cur instanceof Map && isTrue(((Map<?, ?>) cur).get(R[i][0])); }
        new AlertDialog.Builder(this).setTitle("صلاحيات المشرف").setMultiChoiceItems(labels, on, (dg, w, c) -> on[w] = c)
            .setPositiveButton("حفظ", (a, b) -> { Map<String, Object> m = new HashMap<>(); for (int i = 0; i < R.length; i++) m.put(R[i][0], on[i]); upd("admins." + u, m); }).setNegativeButton("إلغاء", null).show();
    }
    private void permsDialog() {
        final String[][] P = MEMBER_PERMS; final boolean[] on = new boolean[P.length]; String[] labels = new String[P.length]; Map<String, Object> cur = map(d, "perms");
        for (int i = 0; i < P.length; i++) { labels[i] = P[i][1]; Object v = cur.get(P[i][0]); on[i] = v == null ? MEMBER_PERMS_DEF[i] : isTrue(v); }
        new AlertDialog.Builder(this).setTitle("صلاحيات الأعضاء").setMultiChoiceItems(labels, on, (dg, w, c) -> on[w] = c)
            .setPositiveButton("حفظ", (a, b) -> { Map<String, Object> m = new HashMap<>(); for (int i = 0; i < P.length; i++) m.put(P[i][0], on[i]);
                Map<String, Object> up = addSystem(chatId, "perms", null, null); up.put("perms", m); upd(up); }).setNegativeButton("إلغاء", null).show();
    }
    private void leave() {
        final String me = me(); final boolean owner = s(d, "ownerId").equals(me); final List<String> rest = new ArrayList<>(parts(d)); rest.remove(me);
        new AlertDialog.Builder(this).setTitle("مغادرة المجموعة").setMessage("هل تريد مغادرة «" + s(d, "name") + "»؟" + (owner && !rest.isEmpty() ? " ستنتقل ملكية المجموعة لعضو آخر." : ""))
            .setPositiveButton("مغادرة", (a, b) -> {
                if (rest.isEmpty()) { db().collection("chats").document(chatId).delete().addOnCompleteListener(t -> finish()); return; }
                Map<String, Object> up = addSystem(chatId, "leave", null, null); up.put("participants", FieldValue.arrayRemove(me));
                if (owner) { String nw = rest.get(0); for (String u : rest) if (map(d, "admins").containsKey(u)) { nw = u; break; } up.put("ownerId", nw); up.put("admins." + nw, FieldValue.delete()); }
                db().collection("chats").document(chatId).update(up).addOnCompleteListener(t -> { toast(this, "غادرت المجموعة"); setResult(RESULT_OK); finish(); });
            }).setNegativeButton("إلغاء", null).show();
    }
    @Override protected void onActivityResult(int rc, int res, Intent in) {
        super.onActivityResult(rc, res, in);
        if (rc == 5 && res == RESULT_OK && in != null && in.getData() != null) {
            Bitmap bm = decodeSquare(getContentResolver(), in.getData(), 320); if (bm == null) { toast(this, "تعذر تجهيز الصورة"); return; }
            Map<String, Object> up = addSystem(chatId, "photo", null, null); up.put("photo", toDataUrl(bm)); upd(up);
        }
    }
}
