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
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;

import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.DocumentSnapshot;

import java.util.*;

import static com.babylon.chat.iq.GroupsKit.*;

/** اختيار الأعضاء وإنشاء المجموعة (وضع create) أو إضافة أعضاء (وضع add). */
public class GroupPickerActivity extends Activity {
    private String mode = "create", chatId;
    private Map<String, Object> meta = new HashMap<>();
    private final List<String> selected = new ArrayList<>(), extra = new ArrayList<>();
    private final Set<String> existing = new HashSet<>();
    private List<String> contacts = new ArrayList<>();
    private String q = "", name = "", photo = "";
    private int step = 1; private boolean creating;
    private FrameLayout root; private LinearLayout listBox, chipsBox; private HorizontalScrollView chipsScroll; private TextView countTv; private FrameLayout fab;
    private final android.os.Handler h = new android.os.Handler();
    private Runnable remote;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b); style(this);
        mode = String.valueOf(getIntent().getStringExtra("mode")).equals("add") ? "add" : "create";
        chatId = getIntent().getStringExtra("chatId");
        root = new FrameLayout(this); root.setBackgroundColor(BG); rtl(root); root.setFitsSystemWindows(true);
        setContentView(root);
        TextView ld = tv(this, "جاري التحميل…", 14, SUB, false); root.addView(ld, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        existing.add(me());
        Runnable go = () -> db().collection("chats").whereArrayContains("participants", me()).get().addOnCompleteListener(t -> {
            List<Object[]> arr = new ArrayList<>();
            if (t.isSuccessful()) for (QueryDocumentSnapshot d : t.getResult()) {
                Map<String, Object> m = d.getData(); if ("group".equals(m.get("type"))) continue;
                for (String u : parts(m)) if (!existing.contains(u)) { boolean dup = false; for (Object[] a : arr) if (a[0].equals(u)) dup = true; if (!dup) arr.add(new Object[]{u, Math.max(l(m.get("updatedAt")), l(m.get("lastTime")))}); }
            }
            Collections.sort(arr, (x, y) -> Long.compare((Long) y[1], (Long) x[1]));
            contacts = new ArrayList<>(); for (Object[] a : arr) contacts.add((String) a[0]);
            List<String> need = new ArrayList<>(contacts); need.add(me()); loadUsers(need, () -> { root.removeAllViews(); step1(); });
        });
        if (mode.equals("add") && chatId != null) {
            db().collection("chats").document(chatId).get().addOnSuccessListener(d -> {
                if (d.exists() && d.getData() != null) meta = d.getData();
                existing.addAll(parts(meta)); Object bn = meta.get("banned"); if (bn instanceof List) for (Object x : (List<?>) bn) existing.add(String.valueOf(x));
                go.run();
            });
        } else go.run();
    }

    private int left() { return GROUP_MAX - existing.size() - selected.size(); }
    private void toggle(String uid) {
        int i = selected.indexOf(uid);
        if (i >= 0) selected.remove(i);
        else { if (left() <= 0) { toast(this, "الحد الأقصى " + GROUP_MAX + " عضواً"); return; } selected.add(uid); }
    }

    private View topBar(String title, String sub, Runnable back) {
        LinearLayout t = new LinearLayout(this); t.setOrientation(LinearLayout.HORIZONTAL); t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(this, 8), dp(this, 10), dp(this, 8), dp(this, 8));
        FrameLayout bk = new FrameLayout(this); View ic = icon(this, IC_BACK, TEXT, 22); bk.addView(ic, new FrameLayout.LayoutParams(dp(this, 22), dp(this, 22), Gravity.CENTER));
        bk.setOnClickListener(v -> back.run()); t.addView(bk, new LinearLayout.LayoutParams(dp(this, 42), dp(this, 42)));
        LinearLayout tt = new LinearLayout(this); tt.setOrientation(LinearLayout.VERTICAL);
        tt.addView(tv(this, title, 18, TEXT, true));
        countTv = tv(this, sub, 12.5f, SUB, false); tt.addView(countTv);
        t.addView(tt, new LinearLayout.LayoutParams(0, -2, 1));
        return t;
    }
    private FrameLayout makeFab(String ic) {
        FrameLayout f = new FrameLayout(this); f.setBackground(round(GOLD, 29, this)); ((android.graphics.drawable.GradientDrawable) f.getBackground()).setShape(android.graphics.drawable.GradientDrawable.OVAL);
        f.addView(icon(this, ic, 0xFF1A1A1A, 26), new FrameLayout.LayoutParams(dp(this, 26), dp(this, 26), Gravity.CENTER));
        f.setElevation(dp(this, 8)); return f;
    }
    private void placeFab(FrameLayout f) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(this, 58), dp(this, 58), Gravity.BOTTOM | Gravity.LEFT);
        p.leftMargin = dp(this, 18); p.bottomMargin = dp(this, 22); root.addView(f, p);
    }

    // ---------- الخطوة 1: اختيار الأعضاء ----------
    private void step1() {
        step = 1; root.removeAllViews();
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        col.addView(topBar(mode.equals("add") ? "إضافة أعضاء" : "مجموعة جديدة", "اختر الأعضاء", this::finish));
        chipsScroll = new HorizontalScrollView(this); chipsScroll.setHorizontalScrollBarEnabled(false);
        chipsBox = new LinearLayout(this); chipsBox.setOrientation(LinearLayout.HORIZONTAL); chipsBox.setPadding(dp(this, 14), dp(this, 4), dp(this, 14), dp(this, 8));
        chipsScroll.addView(chipsBox); col.addView(chipsScroll);
        LinearLayout sr = new LinearLayout(this); sr.setGravity(Gravity.CENTER_VERTICAL); sr.setPadding(dp(this, 13), 0, dp(this, 13), 0); sr.setBackground(round(CHIP, 15, this));
        sr.addView(icon(this, IC_SEARCH, SUB, 18));
        EditText se = new EditText(this); se.setHint("بحث أو اكتب اسم المستخدم"); se.setHintTextColor(SUB); se.setTextColor(TEXT); se.setTextSize(15); se.setBackground(null); se.setSingleLine(true); se.setText(q);
        se.setPadding(dp(this, 9), dp(this, 12), 0, dp(this, 12)); sr.addView(se, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2); slp.setMargins(dp(this, 14), dp(this, 2), dp(this, 14), dp(this, 8)); col.addView(sr, slp);
        ScrollView sv = new ScrollView(this); listBox = new LinearLayout(this); listBox.setOrientation(LinearLayout.VERTICAL); listBox.setPadding(0, 0, 0, dp(this, 110)); sv.addView(listBox);
        col.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));
        fab = makeFab(mode.equals("add") ? IC_CHECK : IC_BACK); placeFab(fab);
        fab.setOnClickListener(v -> {
            if (selected.isEmpty()) { toast(this, "اختر عضواً واحداً على الأقل"); return; }
            if (mode.equals("add")) doAdd(); else step2();
        });
        se.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence a, int b, int c, int d) { }
            public void onTextChanged(CharSequence a, int b, int c, int d) { }
            public void afterTextChanged(Editable e) { q = e.toString(); renderList(); searchRemote(); }
        });
        renderChips(); renderList();
    }
    private void searchRemote() {
        if (remote != null) h.removeCallbacks(remote);
        final String qq = q.trim().replaceFirst("^@", "").toLowerCase();
        if (qq.length() < 3) return;
        remote = () -> db().collection("users").whereEqualTo("username", qq).limit(1).get().addOnSuccessListener(qs -> {
            for (DocumentSnapshot d : qs.getDocuments()) {
                final String uid = d.getId();
                if (existing.contains(uid) || extra.contains(uid) || contacts.contains(uid)) return;
                U u = new U(); u.id = uid; fillUser(u, d); GU.put(uid, u); extra.add(uid);
                if (step == 1) renderList();
            }
        });
        h.postDelayed(remote, 380);
    }
    private void renderChips() {
        if (chipsBox == null) return;
        chipsBox.removeAllViews(); chipsScroll.setVisibility(selected.isEmpty() ? View.GONE : View.VISIBLE);
        for (final String u : selected) {
            LinearLayout c = new LinearLayout(this); c.setGravity(Gravity.CENTER_VERTICAL); c.setBackground(round(CHIP, 22, this)); c.setPadding(dp(this, 5), dp(this, 4), dp(this, 11), dp(this, 4));
            Av av = new Av(this, 26); av.setUser(u); c.addView(av);
            TextView n = tv(this, user(u).name, 13.5f, TEXT, false); n.setPadding(dp(this, 7), 0, dp(this, 4), 0); c.addView(n);
            TextView x = tv(this, "×", 17, SUB, false); c.addView(x);
            c.setOnClickListener(v -> { toggle(u); renderChips(); renderList(); });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.setMargins(0, 0, dp(this, 8), 0); chipsBox.addView(c, p);
        }
        if (countTv != null) countTv.setText(selected.isEmpty() ? "اختر الأعضاء" : selected.size() + " من " + (GROUP_MAX - existing.size()));
        if (fab != null) { fab.setAlpha(selected.isEmpty() ? .45f : 1f); fab.setScaleX(selected.isEmpty() ? .92f : 1f); fab.setScaleY(selected.isEmpty() ? .92f : 1f); }
    }
    private void renderList() {
        if (listBox == null) return;
        listBox.removeAllViews();
        String qq = q.trim().replaceFirst("^@", "").toLowerCase();
        List<String> ids = new ArrayList<>(contacts);
        for (String u : extra) if (!ids.contains(u)) ids.add(u);
        if (!qq.isEmpty()) { List<String> f = new ArrayList<>(); for (String u : ids) { U g = user(u); if (g.name.toLowerCase().contains(qq) || g.username.toLowerCase().contains(qq)) f.add(u); } ids = f; }
        if (ids.isEmpty()) {
            TextView e = tv(this, qq.isEmpty() ? "ما عندك جهات دردشة بعد. ابدأ محادثة مع شخص أولاً." : "لا توجد نتائج. اكتب اليوزر كاملاً للبحث عن مستخدم جديد.", 14, SUB, false);
            e.setGravity(Gravity.CENTER); e.setPadding(dp(this, 24), dp(this, 40), dp(this, 24), 0); e.setLineSpacing(0, 1.4f); listBox.addView(e); return;
        }
        if (qq.isEmpty()) { TextView sec = tv(this, "جهات الدردشة", 13, GOLD, true); sec.setPadding(dp(this, 18), dp(this, 12), dp(this, 18), dp(this, 6)); listBox.addView(sec); }
        for (final String u : ids) {
            final U g = user(u); final boolean on = selected.contains(u);
            LinearLayout r = new LinearLayout(this); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(dp(this, 16), dp(this, 9), dp(this, 16), dp(this, 9));
            Av av = new Av(this, 48); av.setUser(u); r.addView(av);
            LinearLayout t = new LinearLayout(this); t.setOrientation(LinearLayout.VERTICAL); t.setPadding(dp(this, 13), 0, dp(this, 13), 0);
            t.addView(tv(this, g.name, 15.5f, TEXT, true)); if (!g.username.isEmpty()) t.addView(tv(this, "@" + g.username, 13, SUB, false));
            r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
            FrameLayout ck = new FrameLayout(this); int sz = dp(this, 24);
            android.graphics.drawable.GradientDrawable cd = new android.graphics.drawable.GradientDrawable(); cd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            if (on) { cd.setColor(GOLD); cd.setStroke(dp(this, 2), GOLD); ck.addView(icon(this, IC_CHECK, 0xFF111111, 15), new FrameLayout.LayoutParams(dp(this, 15), dp(this, 15), Gravity.CENTER)); }
            else { cd.setColor(0); cd.setStroke(dp(this, 2), 0x8C8798AA); }
            ck.setBackground(cd); r.addView(ck, new LinearLayout.LayoutParams(sz, sz));
            r.setOnClickListener(v -> { toggle(u); renderChips(); renderList(); });
            listBox.addView(r);
        }
    }

    // ---------- إضافة أعضاء ----------
    private void doAdd() {
        if (!right("invite", me(), meta)) { toast(this, "ليس لديك صلاحية إضافة أعضاء"); return; }
        final List<String> ids = new ArrayList<>(selected);
        Map<String, Object> up = addSystem(chatId, "add", ids, null);
        up.put("participants", FieldValue.arrayUnion(ids.toArray()));
        long now = System.currentTimeMillis(); for (String u : ids) up.put("readAt." + u, now);
        db().collection("chats").document(chatId).update(up).addOnSuccessListener(x -> { toast(this, "تمت إضافة " + ids.size() + (ids.size() == 1 ? " عضو" : " أعضاء")); finish(); })
            .addOnFailureListener(e -> toast(this, "تعذرت الإضافة"));
    }

    // ---------- الخطوة 2: الاسم والصورة ----------
    private void step2() {
        step = 2; root.removeAllViews(); chipsBox = null; listBox = null; fab = null;
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        col.addView(topBar("مجموعة جديدة", "الاسم والصورة", () -> { saveName(); step1(); }));
        ScrollView sv = new ScrollView(this); LinearLayout in = new LinearLayout(this); in.setOrientation(LinearLayout.VERTICAL); sv.addView(in);
        col.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout hero = new LinearLayout(this); hero.setOrientation(LinearLayout.VERTICAL); hero.setGravity(Gravity.CENTER_HORIZONTAL); hero.setPadding(dp(this, 18), dp(this, 22), dp(this, 18), dp(this, 4));
        FrameLayout ph = new FrameLayout(this); ph.setClipChildren(false);
        if (!photo.isEmpty()) { Av a = new Av(this, 96); Map<String, Object> m = new HashMap<>(); m.put("name", name); m.put("photo", photo); a.setGroup(m); ph.addView(a); }
        else { View bgv = new View(this); android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, new int[]{0xFFE6C566, 0xFFD3A93B, 0xFFBC8E24}); g.setShape(android.graphics.drawable.GradientDrawable.OVAL); bgv.setBackground(g); ph.addView(bgv, new FrameLayout.LayoutParams(dp(this, 96), dp(this, 96)));
            ph.addView(icon(this, IC_CAM, 0xFFFFFFFF, 38), new FrameLayout.LayoutParams(dp(this, 38), dp(this, 38), Gravity.CENTER)); }
        ph.setOnClickListener(v -> { saveName(); Intent i = new Intent(Intent.ACTION_GET_CONTENT); i.setType("image/*"); startActivityForResult(i, 77); });
        hero.addView(ph, new LinearLayout.LayoutParams(dp(this, 96), dp(this, 96)));
        TextView hint = tv(this, "صورة المجموعة (اختياري)", 12.5f, SUB, false); hint.setPadding(0, dp(this, 10), 0, 0); hero.addView(hint);
        in.addView(hero);
        LinearLayout fld = new LinearLayout(this); fld.setGravity(Gravity.CENTER_VERTICAL); fld.setBackground(round(CARD, 18, this)); fld.setPadding(dp(this, 16), 0, dp(this, 16), 0);
        fld.addView(icon(this, IC_USERS, GOLD2, 22));
        final EditText ne = new EditText(this); ne.setHint("اسم المجموعة"); ne.setHintTextColor(SUB); ne.setTextColor(TEXT); ne.setTextSize(16.5f); ne.setTypeface(Typeface.DEFAULT_BOLD); ne.setBackground(null); ne.setSingleLine(true);
        ne.setFilters(new InputFilter[]{new InputFilter.LengthFilter(40)}); ne.setText(name); ne.setPadding(dp(this, 12), 0, dp(this, 12), 0);
        fld.addView(ne, new LinearLayout.LayoutParams(0, -1, 1));
        final TextView cnt = tv(this, name.length() + "/40", 12, SUB, false); fld.addView(cnt);
        ne.addTextChangedListener(new TextWatcher() { public void beforeTextChanged(CharSequence a, int b, int c, int d) { } public void onTextChanged(CharSequence a, int b, int c, int d) { }
            public void afterTextChanged(Editable e) { name = e.toString(); cnt.setText(e.length() + "/40"); } });
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, dp(this, 56)); flp.setMargins(dp(this, 14), dp(this, 14), dp(this, 14), 0); in.addView(fld, flp);
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setBackground(round(CARD, 20, this)); card.setPadding(0, 0, 0, dp(this, 6));
        LinearLayout hd = new LinearLayout(this); hd.setGravity(Gravity.CENTER_VERTICAL); hd.setPadding(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 6));
        FrameLayout hic = new FrameLayout(this); hic.setBackground(round(0x24C99A1C, 11, this)); hic.addView(icon(this, IC_USERS, GOLD2, 19), new FrameLayout.LayoutParams(dp(this, 19), dp(this, 19), Gravity.CENTER)); hd.addView(hic, new LinearLayout.LayoutParams(dp(this, 32), dp(this, 32)));
        TextView ht = tv(this, "الأعضاء: " + (selected.size() + 1), 14.5f, TEXT, true); ht.setPadding(dp(this, 10), 0, 0, 0); hd.addView(ht); card.addView(hd);
        for (String u : selected) {
            U g = user(u); LinearLayout r = new LinearLayout(this); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(dp(this, 16), dp(this, 9), dp(this, 16), dp(this, 9));
            Av av = new Av(this, 48); av.setUser(u); r.addView(av);
            LinearLayout t = new LinearLayout(this); t.setOrientation(LinearLayout.VERTICAL); t.setPadding(dp(this, 13), 0, 0, 0);
            t.addView(tv(this, g.name, 15.5f, TEXT, true)); if (!g.username.isEmpty()) t.addView(tv(this, "@" + g.username, 13, SUB, false)); r.addView(t); card.addView(r);
        }
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2); clp.setMargins(dp(this, 14), dp(this, 12), dp(this, 14), dp(this, 96)); in.addView(card, clp);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout f = makeFab(IC_CHECK); placeFab(f); f.setOnClickListener(v -> create());
    }
    private void saveName() { /* الاسم يُحفظ عبر TextWatcher */ }
    @Override protected void onActivityResult(int rc, int res, Intent d) {
        super.onActivityResult(rc, res, d);
        if (rc == 77 && res == RESULT_OK && d != null && d.getData() != null) {
            Bitmap b = decodeSquare(getContentResolver(), d.getData(), 320);
            if (b == null) { toast(this, "تعذر تجهيز الصورة"); return; }
            photo = toDataUrl(b); step2();
        }
    }

    // ---------- الإنشاء ----------
    private void create() {
        if (creating) return;
        final String nm = name.trim();
        if (nm.isEmpty()) { toast(this, "اكتب اسم المجموعة"); return; }
        if (selected.isEmpty()) { toast(this, "اختر عضواً واحداً على الأقل"); return; }
        creating = true;
        final String me = me(); final long now = System.currentTimeMillis();
        final List<String> members = new ArrayList<>(); members.add(me); members.addAll(selected);
        final com.google.firebase.firestore.DocumentReference ref = db().collection("chats").document();
        Map<String, Object> readAt = new HashMap<>(); for (String u : members) readAt.put(u, now);
        String text = sysPreview("create", myName(), null);
        Map<String, Object> lm = new HashMap<>(); lm.put("system", true); lm.put("text", text); lm.put("senderId", me); lm.put("at", now);
        Map<String, Object> doc = new HashMap<>(); doc.put("type", "group"); doc.put("name", nm); doc.put("photo", photo); doc.put("ownerId", me);
        doc.put("participants", members); doc.put("createdAt", now); doc.put("updatedAt", now); doc.put("readAt", readAt); doc.put("lastMsg", lm);
        Map<String, Object> sys = new HashMap<>(); sys.put("k", "create"); sys.put("by", me);
        Map<String, Object> msg = new HashMap<>(); msg.put("system", true); msg.put("sys", sys); msg.put("text", text); msg.put("senderId", me); msg.put("createdAt", now);
        ref.set(doc).addOnSuccessListener(x -> ref.collection("messages").add(msg).addOnCompleteListener(t -> {
            creating = false; toast(this, "تم إنشاء المجموعة");
            Intent i = new Intent(this, GroupRoomActivity.class); i.putExtra("chatId", ref.getId()); startActivity(i); finish();
        })).addOnFailureListener(e -> { creating = false; toast(this, "تعذر إنشاء المجموعة، تأكد من قواعد Firestore"); });
    }
}
