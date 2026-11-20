package com.babylon.chat.iq;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.util.*;

import static com.babylon.chat.iq.GroupsKit.*;

/** مجموعة جديدة (اختيار الأعضاء ← الاسم والصورة) أو إضافة أعضاء — نفس تصميم #grpPick في الـ HTML. */
public class GroupPickerActivity extends Activity {
    private String mode = "create", chatId; private Map<String, Object> meta = new HashMap<>(); private Th t;
    private final List<String> selected = new ArrayList<>(), extra = new ArrayList<>(); private final Set<String> existing = new HashSet<>();
    private List<String> contacts = new ArrayList<>(); private String q = "", name = "", photo = ""; private int step = 1; private boolean creating;
    private FrameLayout root; private LinearLayout listBox, chipsBox; private HorizontalScrollView chipsScroll; private TextView subTv; private FrameLayout fab;
    private final android.os.Handler h = new android.os.Handler(); private Runnable remote;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b); t = th(this); window(this, t, t.bg);
        mode = "add".equals(getIntent().getStringExtra("mode")) ? "add" : "create"; chatId = getIntent().getStringExtra("chatId");
        root = new FrameLayout(this); root.setBackgroundColor(t.bg); rtl(root); root.setFitsSystemWindows(true); setContentView(root);
        TextView ld = tv(this, t, "جاري التحميل…", 14, t.sub, false); root.addView(ld, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        existing.add(me());
        Runnable go = () -> db().collection("chats").whereArrayContains("participants", me()).get().addOnCompleteListener(tk -> {
            List<Object[]> arr = new ArrayList<>();
            if (tk.isSuccessful()) for (QueryDocumentSnapshot d : tk.getResult()) { Map<String, Object> m = d.getData(); if ("group".equals(m.get("type"))) continue;
                for (String u : parts(m)) if (!existing.contains(u)) { boolean dup = false; for (Object[] a : arr) if (a[0].equals(u)) dup = true; if (!dup) arr.add(new Object[]{u, Math.max(l(m.get("updatedAt")), l(m.get("lastTime")))}); } }
            Collections.sort(arr, (x, y) -> Long.compare((Long) y[1], (Long) x[1])); contacts = new ArrayList<>(); for (Object[] a : arr) contacts.add((String) a[0]);
            List<String> need = new ArrayList<>(contacts); need.add(me()); loadUsers(need, () -> { root.removeAllViews(); step1(); });
        });
        if (mode.equals("add") && chatId != null) db().collection("chats").document(chatId).get().addOnSuccessListener(d -> { if (d.exists() && d.getData() != null) meta = d.getData(); existing.addAll(parts(meta)); existing.addAll(strList(meta, "banned")); go.run(); });
        else go.run();
    }
    @Override public void onBackPressed() { if (step == 2) step1(); else super.onBackPressed(); }
    private int left() { return GROUP_MAX - existing.size() - selected.size(); }
    private void toggle(String uid) { int i = selected.indexOf(uid); if (i >= 0) selected.remove(i); else { if (left() <= 0) { toast(this, "الحد الأقصى " + GROUP_MAX + " عضواً"); return; } selected.add(uid); } }

    private View topBar(String title, String sub, View.OnClickListener back) {
        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL); top.setPadding(t.u(8), t.u(10), t.u(8), t.u(8));
        FrameLayout bk = new FrameLayout(this); bk.addView(icon(this, IC_BACK, t.text, 22), center(this, 22)); bk.setOnClickListener(back); top.addView(bk, lp(t.k(42), t.k(42)));
        LinearLayout tt = new LinearLayout(this); tt.setOrientation(LinearLayout.VERTICAL); tt.setPadding(t.u(4), 0, 0, 0); tt.addView(tv(this, t, title, 18, t.text, true));
        subTv = tv(this, t, sub, 12.5f, t.sub, false); LinearLayout.LayoutParams sp = lp(-2, -2); sp.topMargin = t.u(1); tt.addView(subTv, sp); top.addView(tt); return top;
    }
    private FrameLayout makeFab(String ic) { FrameLayout f = new FrameLayout(this); f.setBackground(oval(t.gold)); f.addView(icon(this, ic, 0xFF1A1A1A, 26).width(2.6f), center(this, 26)); f.setElevation(t.u(8));
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(t.k(58), t.k(58), Gravity.BOTTOM | Gravity.LEFT); p.leftMargin = t.u(18); p.bottomMargin = t.u(22); root.addView(f, p); return f; }

    // ---------- الخطوة 1 ----------
    private void step1() {
        step = 1; root.removeAllViews(); LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        col.addView(topBar(mode.equals("add") ? "إضافة أعضاء" : "مجموعة جديدة", "اختر الأعضاء", v -> finish()));
        chipsScroll = new HorizontalScrollView(this); chipsScroll.setHorizontalScrollBarEnabled(false); chipsBox = new LinearLayout(this); chipsBox.setPadding(t.u(14), t.u(4), t.u(14), t.u(8)); chipsScroll.addView(chipsBox); col.addView(chipsScroll);
        LinearLayout sr = new LinearLayout(this); sr.setGravity(Gravity.CENTER_VERTICAL); sr.setPadding(t.u(13), 0, t.u(13), 0); sr.setBackground(round(t.chip, t.u(15))); sr.addView(icon(this, IC_SEARCH, t.sub, 19));
        EditText se = new EditText(this); se.setHint("بحث أو اكتب اسم المستخدم"); se.setHintTextColor(t.sub); se.setTextColor(t.text); se.setTextSize(15 * t.s); se.setBackground(null); se.setSingleLine(true); se.setText(q); se.setPadding(t.u(9), t.u(12), 0, t.u(12)); sr.addView(se, lpw(0, -2, 1));
        LinearLayout.LayoutParams slp = lp(-1, -2); slp.setMargins(t.u(14), t.u(2), t.u(14), t.u(8)); col.addView(sr, slp);
        ScrollView sv = new ScrollView(this); listBox = new LinearLayout(this); listBox.setOrientation(LinearLayout.VERTICAL); listBox.setPadding(0, 0, 0, t.u(110)); sv.addView(listBox); col.addView(sv, lpw(-1, 0, 1));
        root.addView(col, new FrameLayout.LayoutParams(-1, -1)); fab = makeFab(mode.equals("add") ? IC_CHECK : IC_BACK);
        fab.setOnClickListener(v -> { if (selected.isEmpty()) { toast(this, "اختر عضواً واحداً على الأقل"); return; } if (mode.equals("add")) doAdd(); else step2(); });
        se.addTextChangedListener(new TextWatcher() { public void beforeTextChanged(CharSequence a, int b, int c, int d) { } public void onTextChanged(CharSequence a, int b, int c, int d) { } public void afterTextChanged(Editable e) { q = e.toString(); renderList(); searchRemote(); } });
        renderChips(); renderList();
    }
    private void searchRemote() {
        if (remote != null) h.removeCallbacks(remote); final String qq = q.trim().replaceFirst("^@", "").toLowerCase(); if (qq.length() < 3) return;
        remote = () -> db().collection("users").whereEqualTo("username", qq).limit(1).get().addOnSuccessListener(qs -> { for (DocumentSnapshot d : qs.getDocuments()) { final String uid = d.getId(); if (existing.contains(uid) || extra.contains(uid) || contacts.contains(uid)) return; U u = new U(); u.id = uid; fillUser(u, d); GU.put(uid, u); extra.add(uid); if (step == 1) renderList(); } });
        h.postDelayed(remote, 380);
    }
    private void renderChips() {
        if (chipsBox == null) return; chipsBox.removeAllViews(); chipsScroll.setVisibility(selected.isEmpty() ? View.GONE : View.VISIBLE);
        for (final String u : selected) {
            LinearLayout c = new LinearLayout(this); c.setGravity(Gravity.CENTER_VERTICAL); c.setBackground(round(t.chip, t.u(22))); c.setPadding(t.u(11), t.u(4), t.u(5), t.u(4));
            Av av = new Av(this, 26 * t.s); av.setUser(u); c.addView(av, lp(t.k(26), t.k(26))); TextView n = tv(this, t, user(u).name, 13.5f, t.text, false); n.setPadding(t.u(7), 0, t.u(4), 0); c.addView(n);
            TextView x = tv(this, t, "×", 17, t.sub, false); x.setAlpha(.6f); c.addView(x); c.setOnClickListener(v -> { toggle(u); renderChips(); renderList(); });
            LinearLayout.LayoutParams p = lp(-2, -2); p.setMarginEnd(t.u(8)); chipsBox.addView(c, p);
        }
        if (subTv != null) subTv.setText(selected.isEmpty() ? "اختر الأعضاء" : selected.size() + " من " + (GROUP_MAX - existing.size()));
        if (fab != null) { fab.setAlpha(selected.isEmpty() ? .45f : 1f); fab.setScaleX(selected.isEmpty() ? .92f : 1f); fab.setScaleY(selected.isEmpty() ? .92f : 1f); }
    }
    private void renderList() {
        if (listBox == null) return; listBox.removeAllViews(); String qq = q.trim().replaceFirst("^@", "").toLowerCase();
        List<String> ids = new ArrayList<>(contacts); for (String u : extra) if (!ids.contains(u)) ids.add(u);
        if (!qq.isEmpty()) { List<String> f = new ArrayList<>(); for (String u : ids) { U g = user(u); if (g.name.toLowerCase().contains(qq) || g.username.toLowerCase().contains(qq)) f.add(u); } ids = f; }
        if (ids.isEmpty()) { TextView e = tv(this, t, qq.isEmpty() ? "ما عندك جهات دردشة بعد. ابدأ محادثة مع شخص أولاً." : "لا توجد نتائج. اكتب اليوزر كاملاً للبحث عن مستخدم جديد.", 14, t.sub, false); e.setGravity(Gravity.CENTER); e.setLineSpacing(0, 1.3f); e.setPadding(t.u(24), t.u(40), t.u(24), 0); listBox.addView(e); return; }
        if (qq.isEmpty()) { TextView sec = tv(this, t, "جهات الدردشة", 13, t.gold, true); sec.setPadding(t.u(18), t.u(12), t.u(18), t.u(6)); listBox.addView(sec); }
        for (final String u : ids) {
            final U g = user(u); boolean on = selected.contains(u); LinearLayout r = new LinearLayout(this); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(t.u(16), t.u(9), t.u(16), t.u(9));
            Av av = new Av(this, 48 * t.s); av.setUser(u); r.addView(av, lp(t.k(48), t.k(48))); LinearLayout x = new LinearLayout(this); x.setOrientation(LinearLayout.VERTICAL); x.setPadding(t.u(13), 0, t.u(13), 0);
            x.addView(tv(this, t, g.name, 15.5f, t.text, true)); if (!g.username.isEmpty()) { TextView us = tv(this, t, "@" + g.username, 13, t.sub, false); LinearLayout.LayoutParams up = lp(-2, -2); up.topMargin = t.u(1); x.addView(us, up); } r.addView(x, lpw(0, -2, 1));
            FrameLayout ck = new FrameLayout(this); android.graphics.drawable.GradientDrawable cd = new android.graphics.drawable.GradientDrawable(); cd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            if (on) { cd.setColor(t.gold); cd.setStroke(t.u(2), t.gold); ck.addView(icon(this, IC_CHECK, 0xFF111111, 15).width(3.2f), center(this, 15)); } else { cd.setColor(0); cd.setStroke(t.u(2), t.sub); ck.setAlpha(.55f); }
            ck.setBackground(cd); r.addView(ck, lp(t.k(24), t.k(24))); r.setOnClickListener(v -> { toggle(u); renderChips(); renderList(); }); listBox.addView(r);
        }
    }
    private void doAdd() {
        if (!right("invite", me(), meta)) { toast(this, "ليس لديك صلاحية إضافة أعضاء"); return; }
        final List<String> ids = new ArrayList<>(selected); Map<String, Object> up = addSystem(chatId, "add", ids, null); up.put("participants", FieldValue.arrayUnion(ids.toArray())); long now = System.currentTimeMillis(); for (String u : ids) up.put("readAt." + u, now);
        db().collection("chats").document(chatId).update(up).addOnSuccessListener(x -> { toast(this, "تمت إضافة " + ids.size() + (ids.size() == 1 ? " عضو" : " أعضاء")); finish(); }).addOnFailureListener(e -> toast(this, "تعذرت الإضافة"));
    }

    // ---------- الخطوة 2 ----------
    private void step2() {
        step = 2; root.removeAllViews(); chipsBox = null; listBox = null; fab = null; LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        col.addView(topBar("مجموعة جديدة", "الاسم والصورة", v -> step1())); ScrollView sv = new ScrollView(this); LinearLayout in = new LinearLayout(this); in.setOrientation(LinearLayout.VERTICAL); sv.addView(in); col.addView(sv, lpw(-1, 0, 1));
        LinearLayout hero = new LinearLayout(this); hero.setOrientation(LinearLayout.VERTICAL); hero.setGravity(Gravity.CENTER_HORIZONTAL); hero.setPadding(t.u(18), t.u(22), t.u(18), t.u(4));
        FrameLayout ph = new FrameLayout(this); ph.setClipChildren(false);
        if (!photo.isEmpty()) { Av a = new Av(this, 96 * t.s); Map<String, Object> m = new HashMap<>(); m.put("name", name); m.put("photo", photo); a.setGroup(m); ph.addView(a, new FrameLayout.LayoutParams(t.k(96), t.k(96))); }
        else { View bgv = new View(this); bgv.setBackground(goldGrad(0, true)); ph.addView(bgv, new FrameLayout.LayoutParams(t.k(96), t.k(96))); ph.addView(icon(this, IC_CAM, 0xFFFFFFFF, 38 * t.s), center(this, 38 * t.s)); }
        FrameLayout plus = new FrameLayout(this); android.graphics.drawable.GradientDrawable pg = oval(t.card); pg.setStroke(t.u(3), t.bg); plus.setBackground(pg); plus.addView(icon(this, IC_PLUS, t.gold, 14 * t.s).width(2.4f), center(this, 14 * t.s));
        ph.addView(plus, new FrameLayout.LayoutParams(t.k(30), t.k(30), Gravity.BOTTOM | Gravity.RIGHT)); ph.setElevation(t.u(6));
        ph.setOnClickListener(v -> { Intent i = new Intent(Intent.ACTION_GET_CONTENT); i.setType("image/*"); startActivityForResult(i, 77); }); hero.addView(ph, lp(t.k(96), t.k(96)));
        TextView hint = tv(this, t, "صورة المجموعة (اختياري)", 12.5f, t.sub, false); hint.setPadding(0, t.u(10), 0, 0); hero.addView(hint); in.addView(hero);
        LinearLayout fld = new LinearLayout(this); fld.setGravity(Gravity.CENTER_VERTICAL); fld.setBackground(round(t.card, t.u(18))); fld.setPadding(t.u(16), 0, t.u(16), 0); fld.addView(icon(this, IC_USERS, t.gold, 22));
        final EditText ne = new EditText(this); ne.setHint("اسم المجموعة"); ne.setHintTextColor(t.sub); ne.setTextColor(t.text); ne.setTextSize(16.5f * t.s); ne.setTypeface(Typeface.DEFAULT_BOLD); ne.setBackground(null); ne.setSingleLine(true);
        ne.setFilters(new InputFilter[]{new InputFilter.LengthFilter(40)}); ne.setText(name); ne.setPadding(t.u(12), 0, t.u(12), 0); fld.addView(ne, lpw(0, -1, 1)); final TextView cnt = tv(this, t, name.length() + "/40", 12, t.sub, false); fld.addView(cnt);
        ne.addTextChangedListener(new TextWatcher() { public void beforeTextChanged(CharSequence a, int b, int c, int d) { } public void onTextChanged(CharSequence a, int b, int c, int d) { } public void afterTextChanged(Editable e) { name = e.toString(); cnt.setText(e.length() + "/40"); } });
        LinearLayout.LayoutParams flp = lp(-1, t.k(56)); flp.setMargins(t.u(14), t.u(14), t.u(14), 0); in.addView(fld, flp);
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setBackground(round(t.card, t.u(20))); card.setPadding(0, 0, 0, t.u(6));
        LinearLayout hd = new LinearLayout(this); hd.setGravity(Gravity.CENTER_VERTICAL); hd.setPadding(t.u(16), t.u(14), t.u(16), t.u(6)); FrameLayout hic = new FrameLayout(this); hic.setBackground(round(0x24C99A1C, t.u(11))); hic.addView(icon(this, IC_USERS, t.gold, 19), center(this, 19)); hd.addView(hic, lp(t.k(32), t.k(32)));
        TextView ht = tv(this, t, "الأعضاء: " + (selected.size() + 1), 14.5f, t.text, true); ht.setPadding(t.u(10), 0, 0, 0); hd.addView(ht); card.addView(hd);
        for (String u : selected) { U g = user(u); LinearLayout r = new LinearLayout(this); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(t.u(16), t.u(9), t.u(16), t.u(9)); Av av = new Av(this, 48 * t.s); av.setUser(u); r.addView(av, lp(t.k(48), t.k(48)));
            LinearLayout x = new LinearLayout(this); x.setOrientation(LinearLayout.VERTICAL); x.setPadding(t.u(13), 0, 0, 0); x.addView(tv(this, t, g.name, 15.5f, t.text, true)); if (!g.username.isEmpty()) x.addView(tv(this, t, "@" + g.username, 13, t.sub, false)); r.addView(x); card.addView(r); }
        LinearLayout.LayoutParams clp = lp(-1, -2); clp.setMargins(t.u(14), t.u(12), t.u(14), t.u(96)); in.addView(card, clp);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1)); FrameLayout f = makeFab(IC_CHECK); f.setOnClickListener(v -> create());
    }
    @Override protected void onActivityResult(int rc, int res, Intent d) { super.onActivityResult(rc, res, d);
        if (rc == 77 && res == RESULT_OK && d != null && d.getData() != null) { Bitmap b = decodeSquare(getContentResolver(), d.getData(), 320); if (b == null) { toast(this, "تعذر تجهيز الصورة"); return; } photo = toDataUrl(b); step2(); } }

    private void create() {
        if (creating) return; final String nm = name.trim(); if (nm.isEmpty()) { toast(this, "اكتب اسم المجموعة"); return; } if (selected.isEmpty()) { toast(this, "اختر عضواً واحداً على الأقل"); return; }
        creating = true; final String me = me(); final long now = System.currentTimeMillis(); final List<String> members = new ArrayList<>(); members.add(me); members.addAll(selected);
        final com.google.firebase.firestore.DocumentReference ref = db().collection("chats").document(); Map<String, Object> readAt = new HashMap<>(); for (String u : members) readAt.put(u, now);
        String text = sysPreview("create", myName(), null); Map<String, Object> lm = new HashMap<>(); lm.put("system", true); lm.put("text", text); lm.put("senderId", me); lm.put("at", now);
        Map<String, Object> doc = new HashMap<>(); doc.put("type", "group"); doc.put("name", nm); doc.put("photo", photo); doc.put("ownerId", me); doc.put("participants", members); doc.put("createdAt", now); doc.put("updatedAt", now); doc.put("readAt", readAt); doc.put("lastMsg", lm);
        Map<String, Object> sys = new HashMap<>(); sys.put("k", "create"); sys.put("by", me); Map<String, Object> msg = new HashMap<>(); msg.put("system", true); msg.put("sys", sys); msg.put("text", text); msg.put("senderId", me); msg.put("createdAt", now);
        ref.set(doc).addOnSuccessListener(x -> ref.collection("messages").add(msg).addOnCompleteListener(tk -> { creating = false; toast(this, "تم إنشاء المجموعة"); Intent i = new Intent(this, GroupRoomActivity.class); i.putExtra("chatId", ref.getId()); startActivity(i); finish(); }))
           .addOnFailureListener(e -> { creating = false; toast(this, "تعذر إنشاء المجموعة، تأكد من قواعد Firestore"); });
    }
}
