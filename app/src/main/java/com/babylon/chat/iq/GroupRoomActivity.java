package com.babylon.chat.iq;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;

import com.google.firebase.firestore.*;

import java.util.*;

import static com.babylon.chat.iq.GroupsKit.*;

/** غرفة المجموعة: رسائل بأسماء المرسلين والألقاب، رسائل النظام، شريط التثبيت، المنشن، قيود الكتابة. */
public class GroupRoomActivity extends Activity {
    private String chatId; private Map<String, Object> meta = new HashMap<>();
    private final List<DocumentSnapshot> msgs = new ArrayList<>();
    private ListenerRegistration chatReg, msgReg; private int limit = 50; private int pinIdx = -1; private boolean metaReady;
    private ListView list; private BaseAdapter adapter; private Av hdAv; private TextView hdName, hdSub;
    private LinearLayout pinBar, composer, lockBar, mentionPop; private EditText input; private View sendBtn;
    private long lastReadWrite; private final android.os.Handler h = new android.os.Handler(); private boolean typingOn; private Runnable typingOff;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b); style(this); getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        chatId = getIntent().getStringExtra("chatId"); if (chatId == null) { finish(); return; }
        build();
        chatReg = db().collection("chats").document(chatId).addSnapshotListener((doc, e) -> {
            if (doc == null) return;
            if (!doc.exists() || !parts(doc.getData()).contains(me())) { if (metaReady || doc.exists()) { toast(this, "لم تعد عضواً في هذه المجموعة"); finish(); } return; }
            meta = doc.getData(); final List<String> ps = parts(meta);
            loadUsers(ps, () -> { metaReady = true; refreshHeader(); adapter.notifyDataSetChanged(); });
        });
        listenMessages();
    }
    @Override protected void onDestroy() { super.onDestroy(); if (chatReg != null) chatReg.remove(); if (msgReg != null) msgReg.remove(); setTyping(false); }

    private void listenMessages() {
        if (msgReg != null) msgReg.remove();
        msgReg = db().collection("chats").document(chatId).collection("messages").orderBy("createdAt", Query.Direction.DESCENDING).limit(limit).addSnapshotListener((snap, e) -> {
            if (snap == null) return;
            msgs.clear(); List<DocumentSnapshot> d = new ArrayList<>(snap.getDocuments()); Collections.reverse(d); msgs.addAll(d);
            adapter.notifyDataSetChanged();
            if (!loadingOlder) list.setSelection(adapter.getCount() - 1); loadingOlder = false;
            if (!d.isEmpty()) markRead(l(d.get(d.size() - 1).get("createdAt")));
        });
    }
    private boolean loadingOlder;
    private void markRead(long newest) {
        long mine = l(map(meta, "readAt").get(me()));
        if (newest <= mine || System.currentTimeMillis() - lastReadWrite < 1200) return;
        lastReadWrite = System.currentTimeMillis();
        db().collection("chats").document(chatId).update("readAt." + me(), Math.max(System.currentTimeMillis(), newest));
    }

    // ---------- بناء الواجهة ----------
    private void build() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG); rtl(root); root.setFitsSystemWindows(true);
        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL); top.setBackgroundColor(CARD); top.setPadding(dp(this, 6), dp(this, 8), dp(this, 10), dp(this, 8));
        FrameLayout bk = new FrameLayout(this); bk.addView(icon(this, IC_BACK, TEXT, 22), new FrameLayout.LayoutParams(dp(this, 22), dp(this, 22), Gravity.CENTER)); bk.setOnClickListener(v -> finish());
        top.addView(bk, new LinearLayout.LayoutParams(dp(this, 42), dp(this, 42)));
        LinearLayout hit = new LinearLayout(this); hit.setGravity(Gravity.CENTER_VERTICAL); hit.setOnClickListener(v -> openInfo());
        hdAv = new Av(this, 40); hit.addView(hdAv);
        LinearLayout tt = new LinearLayout(this); tt.setOrientation(LinearLayout.VERTICAL); tt.setPadding(dp(this, 11), 0, 0, 0);
        hdName = tv(this, "مجموعة", 16.5f, TEXT, true); hdName.setSingleLine(true); hdName.setEllipsize(TextUtils.TruncateAt.END);
        hdSub = tv(this, "", 12.5f, SUB, false); tt.addView(hdName); tt.addView(hdSub); hit.addView(tt, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(hit, new LinearLayout.LayoutParams(0, -2, 1));
        FrameLayout inf = new FrameLayout(this); inf.addView(icon(this, IC_USERS, GOLD, 22), new FrameLayout.LayoutParams(dp(this, 22), dp(this, 22), Gravity.CENTER)); inf.setOnClickListener(v -> openInfo());
        top.addView(inf, new LinearLayout.LayoutParams(dp(this, 42), dp(this, 42)));
        root.addView(top);
        pinBar = new LinearLayout(this); pinBar.setGravity(Gravity.CENTER_VERTICAL); pinBar.setBackgroundColor(CARD); pinBar.setPadding(dp(this, 8), dp(this, 7), dp(this, 14), dp(this, 7)); pinBar.setVisibility(View.GONE);
        root.addView(pinBar);
        list = new ListView(this); list.setDivider(null); list.setBackgroundColor(BG); list.setClipToPadding(false); list.setPadding(dp(this, 8), dp(this, 6), dp(this, 8), dp(this, 6));
        list.setTranscriptMode(ListView.TRANSCRIPT_MODE_NORMAL); list.setSelector(android.R.color.transparent);
        adapter = new MsgAdapter(); list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        // أسفل: شريط المنشن + الكتابة + القفل
        FrameLayout bottom = new FrameLayout(this);
        composer = new LinearLayout(this); composer.setGravity(Gravity.CENTER_VERTICAL); composer.setBackgroundColor(CARD); composer.setPadding(dp(this, 10), dp(this, 8), dp(this, 10), dp(this, 8));
        input = new EditText(this); input.setHint("رسالة"); input.setHintTextColor(SUB); input.setTextColor(TEXT); input.setTextSize(15.5f); input.setMaxLines(5);
        input.setBackground(round(CHIP, 22, this)); input.setPadding(dp(this, 16), dp(this, 10), dp(this, 16), dp(this, 10));
        composer.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        FrameLayout sb = new FrameLayout(this); sb.setBackground(round(GOLD, 22, this)); ((android.graphics.drawable.GradientDrawable) sb.getBackground()).setShape(android.graphics.drawable.GradientDrawable.OVAL);
        sb.addView(icon(this, IC_SEND, 0xFF1A1A1A, 22), new FrameLayout.LayoutParams(dp(this, 22), dp(this, 22), Gravity.CENTER)); sendBtn = sb; sb.setOnClickListener(v -> send());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(this, 44), dp(this, 44)); sp.setMargins(dp(this, 8), 0, 0, 0); composer.addView(sb, sp);
        bottom.addView(composer);
        lockBar = new LinearLayout(this); lockBar.setGravity(Gravity.CENTER); lockBar.setBackgroundColor(CARD); lockBar.setPadding(0, dp(this, 14), 0, dp(this, 14)); lockBar.setVisibility(View.GONE);
        lockBar.addView(icon(this, IC_LOCK, SUB, 18)); TextView lt = tv(this, "", 13.5f, SUB, false); lt.setPadding(dp(this, 8), 0, 0, 0); lockBar.addView(lt); lockBar.setTag(lt);
        bottom.addView(lockBar);
        root.addView(bottom);
        mentionPop = new LinearLayout(this); mentionPop.setOrientation(LinearLayout.VERTICAL); mentionPop.setBackground(round(CARD, 16, this)); mentionPop.setVisibility(View.GONE); mentionPop.setElevation(dp(this, 10));
        FrameLayout wrap = new FrameLayout(this); wrap.addView(root, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM); mp.setMargins(dp(this, 10), 0, dp(this, 10), dp(this, 68)); wrap.addView(mentionPop, mp);
        setContentView(wrap);
        input.addTextChangedListener(new TextWatcher() { public void beforeTextChanged(CharSequence a, int b, int c, int d) { } public void onTextChanged(CharSequence a, int b, int c, int d) { }
            public void afterTextChanged(Editable e) { checkMention(); if (e.length() > 0) { setTyping(true); if (typingOff != null) h.removeCallbacks(typingOff); typingOff = () -> setTyping(false); h.postDelayed(typingOff, 3000); } } });
        list.setOnScrollListener(new AbsListView.OnScrollListener() { public void onScrollStateChanged(AbsListView v, int s) { }
            public void onScroll(AbsListView v, int f, int c, int t) { if (f == 0 && msgs.size() >= limit && !loadingOlder) { loadingOlder = true; limit += 50; listenMessages(); } } });
    }
    private void openInfo() { Intent i = new Intent(this, GroupInfoActivity.class); i.putExtra("chatId", chatId); startActivity(i); }
    private void setTyping(boolean on) { if (typingOn == on || chatId == null) return; typingOn = on; try { db().collection("chats").document(chatId).update("typing_" + me(), on); } catch (Throwable ignored) { } }

    // ---------- الرأس والصلاحيات ----------
    private void refreshHeader() {
        hdName.setText(s(meta, "name").isEmpty() ? "مجموعة" : s(meta, "name")); hdAv.setGroup(meta);
        List<String> names = new ArrayList<>(); for (String u : parts(meta)) if (!u.equals(me()) && isTrue(meta.get("typing_" + u))) names.add(userName(u));
        if (!names.isEmpty()) { hdSub.setText(names.size() == 1 ? names.get(0) + " يكتب..." : join(names.subList(0, Math.min(2, names.size())), " و") + (names.size() > 2 ? " وآخرون" : "") + " يكتبون..."); hdSub.setTextColor(0xFF4ADE80); }
        else { hdSub.setText(memberWord(parts(meta).size())); hdSub.setTextColor(SUB); }
        boolean canText = canSend("text", me(), meta);
        composer.setVisibility(canText ? View.VISIBLE : View.GONE); lockBar.setVisibility(canText ? View.GONE : View.VISIBLE);
        if (!canText) ((TextView) lockBar.getTag()).setText(role(me(), meta).equals("member") && isTrue(map(meta, "muted").get(me())) ? "تم تقييدك من الكتابة في هذه المجموعة" : "الكتابة مقيّدة في هذه المجموعة");
        renderPinned();
    }
    @SuppressWarnings("unchecked") private List<Map<String, Object>> pins() { Object o = meta.get("pins"); return o instanceof List ? (List<Map<String, Object>>) o : new ArrayList<Map<String, Object>>(); }
    private void renderPinned() {
        List<Map<String, Object>> pins = pins();
        if (pins.isEmpty()) { pinBar.setVisibility(View.GONE); pinIdx = -1; return; }
        if (pinIdx < 0 || pinIdx >= pins.size()) pinIdx = pins.size() - 1;
        final Map<String, Object> p = pins.get(pinIdx); pinBar.setVisibility(View.VISIBLE); pinBar.removeAllViews();
        LinearLayout line = new LinearLayout(this); line.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < pins.size(); i++) { View seg = new View(this); seg.setBackgroundColor(GOLD); seg.setAlpha(i == pinIdx ? 1f : .3f); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(this, 3), 0, 1); lp.setMargins(0, dp(this, 1), 0, dp(this, 1)); line.addView(seg, lp); }
        pinBar.addView(line, new LinearLayout.LayoutParams(dp(this, 3), dp(this, 34)));
        LinearLayout t = new LinearLayout(this); t.setOrientation(LinearLayout.VERTICAL); t.setPadding(dp(this, 11), 0, dp(this, 8), 0);
        t.addView(tv(this, "رسالة مثبتة" + (pins.size() > 1 ? " #" + (pinIdx + 1) : ""), 13, GOLD2, true));
        TextView pt = tv(this, s(p, "text"), 13.5f, TEXT, false); pt.setSingleLine(true); pt.setEllipsize(TextUtils.TruncateAt.END); t.addView(pt);
        pinBar.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        pinBar.setOnClickListener(v -> { jumpTo(s(p, "id")); if (pins().size() > 1) { pinIdx = (pinIdx - 1 + pins().size()) % pins().size(); renderPinned(); } });
        if (right("pin", me(), meta)) {
            FrameLayout x = new FrameLayout(this); x.addView(icon(this, IC_PIN, SUB, 19), new FrameLayout.LayoutParams(dp(this, 19), dp(this, 19), Gravity.CENTER));
            x.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("إلغاء التثبيت").setMessage("هل تريد إلغاء تثبيت هذه الرسالة؟")
                .setPositiveButton("إلغاء التثبيت", (d, w) -> { List<Map<String, Object>> np = new ArrayList<>(pins()); if (pinIdx >= 0 && pinIdx < np.size()) np.remove(pinIdx); pinIdx = -1; db().collection("chats").document(chatId).update("pins", np); })
                .setNegativeButton("تراجع", null).show());
            pinBar.addView(x, new LinearLayout.LayoutParams(dp(this, 36), dp(this, 36)));
        }
    }
    private void jumpTo(String id) {
        for (int i = 0; i < msgs.size(); i++) if (msgs.get(i).getId().equals(id)) { list.smoothScrollToPosition(i); return; }
        toast(this, "الرسالة بعيدة، مرّر للأعلى لتحميل رسائل أقدم");
    }

    // ---------- المنشن ----------
    private void checkMention() {
        String t = input.getText().toString(); java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:^|\\s)@([A-Za-z0-9_.]*)$").matcher(t);
        mentionPop.removeAllViews();
        if (!m.find()) { mentionPop.setVisibility(View.GONE); return; }
        String q = m.group(1).toLowerCase(); int n = 0;
        for (final String u : parts(meta)) {
            if (u.equals(me())) continue; final U g = user(u); if (g.username.isEmpty()) continue;
            if (!q.isEmpty() && !g.username.toLowerCase().startsWith(q) && !g.name.toLowerCase().contains(q)) continue;
            if (n++ >= 6) break;
            LinearLayout r = new LinearLayout(this); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(dp(this, 14), dp(this, 8), dp(this, 14), dp(this, 8));
            Av av = new Av(this, 36); av.setUser(u); r.addView(av);
            LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(dp(this, 10), 0, 0, 0);
            c.addView(tv(this, g.name, 14.5f, TEXT, true)); c.addView(tv(this, "@" + g.username, 12.5f, SUB, false)); r.addView(c);
            r.setOnClickListener(v -> { String cur = input.getText().toString(); input.setText(cur.replaceAll("(^|\\s)@[A-Za-z0-9_.]*$", "$1@" + g.username + " ")); input.setSelection(input.length()); mentionPop.setVisibility(View.GONE); });
            mentionPop.addView(r);
        }
        mentionPop.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
    }
    private List<String> extractMentions(String text) {
        Map<String, String> names = new HashMap<>(); for (String u : parts(meta)) { String un = user(u).username; if (!un.isEmpty()) names.put(un.toLowerCase(), u); }
        List<String> out = new ArrayList<>(); java.util.regex.Matcher m = java.util.regex.Pattern.compile("(^|[^A-Za-z0-9_.@])@([A-Za-z][A-Za-z0-9_.]{2,})").matcher(text);
        while (m.find()) { String u = names.get(m.group(2).replaceAll("\\.+$", "").toLowerCase()); if (u != null && !u.equals(me()) && !out.contains(u)) out.add(u); }
        return out;
    }

    // ---------- الإرسال ----------
    private void send() {
        String text = input.getText().toString().trim(); if (text.isEmpty() || !canSend("text", me(), meta)) return;
        long now = System.currentTimeMillis();
        Map<String, Object> m = new HashMap<>(); m.put("senderId", me()); m.put("text", text); m.put("createdAt", now);
        List<String> mn = extractMentions(text); if (!mn.isEmpty()) m.put("mentions", mn);
        input.setText(""); setTyping(false);
        db().collection("chats").document(chatId).collection("messages").add(m);
        Map<String, Object> lm = new HashMap<>(); lm.put("text", text.length() > 120 ? text.substring(0, 120) : text); lm.put("senderId", me()); lm.put("senderName", myName()); lm.put("at", now);
        Map<String, Object> up = new HashMap<>(); up.put("lastMsg", lm); up.put("updatedAt", now); up.put("readAt." + me(), now);
        db().collection("chats").document(chatId).update(up);
    }

    // ---------- قائمة الرسائل ----------
    private boolean readByAll(long created) {
        Map<String, Object> ra = map(meta, "readAt"); int others = 0;
        for (String u : parts(meta)) if (!u.equals(me())) { others++; if (l(ra.get(u)) < created) return false; }
        return others > 0;
    }
    private String keyOf(DocumentSnapshot d) { if (isTrue(d.get("system"))) return null; return String.valueOf(d.get("senderId")); }

    private final class MsgAdapter extends BaseAdapter {
        public int getCount() { return msgs.size(); } public Object getItem(int i) { return msgs.get(i); } public long getItemId(int i) { return i; }
        public View getView(int pos, View cv, ViewGroup parent) {
            final DocumentSnapshot d = msgs.get(pos); final Map<String, Object> m = d.getData();
            LinearLayout row = new LinearLayout(GroupRoomActivity.this); row.setOrientation(LinearLayout.HORIZONTAL); row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            if (isTrue(m.get("system"))) {
                TextView t = tv(GroupRoomActivity.this, sysText(m), 12.5f, 0xFFFFFFFF, false); t.setGravity(Gravity.CENTER); t.setBackground(round(0x4D000000, 14, GroupRoomActivity.this)); t.setPadding(dp(GroupRoomActivity.this, 13), dp(GroupRoomActivity.this, 5), dp(GroupRoomActivity.this, 13), dp(GroupRoomActivity.this, 5));
                row.setGravity(Gravity.CENTER); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.setMargins(0, dp(GroupRoomActivity.this, 7), 0, dp(GroupRoomActivity.this, 7)); row.addView(t, lp); return row;
            }
            final String sid = s(m, "senderId"); final boolean mine = sid.equals(me());
            String prevK = pos > 0 ? keyOf(msgs.get(pos - 1)) : null, nextK = pos + 1 < msgs.size() ? keyOf(msgs.get(pos + 1)) : null;
            boolean first = !sid.equals(prevK), last = !sid.equals(nextK);
            LinearLayout bub = new LinearLayout(GroupRoomActivity.this); bub.setOrientation(LinearLayout.VERTICAL);
            bub.setBackground(round(mine ? 0xFF2B5278 : CARD, 16, GroupRoomActivity.this)); bub.setPadding(dp(GroupRoomActivity.this, 12), dp(GroupRoomActivity.this, 7), dp(GroupRoomActivity.this, 12), dp(GroupRoomActivity.this, 6));
            if (!mine && first) {
                LinearLayout hd = new LinearLayout(GroupRoomActivity.this); hd.setGravity(Gravity.CENTER_VERTICAL);
                TextView sn = tv(GroupRoomActivity.this, userName(sid), 13.5f, colorOf(sid), true); sn.setSingleLine(true); sn.setEllipsize(TextUtils.TruncateAt.END); sn.setMaxWidth(dp(GroupRoomActivity.this, 180)); hd.addView(sn);
                String tt = title(sid, meta);
                if (!tt.isEmpty()) { TextView rl = tv(GroupRoomActivity.this, tt, 11.5f, role(sid, meta).equals("member") ? SUB : GOLD, true); rl.setPadding(dp(GroupRoomActivity.this, 10), 0, 0, 0); hd.addView(rl); }
                bub.addView(hd);
            }
            SpannableString sp = new SpannableString(s(m, "text"));
            java.util.regex.Matcher mm = java.util.regex.Pattern.compile("@[A-Za-z][A-Za-z0-9_.]{2,}").matcher(sp);
            while (mm.find()) sp.setSpan(new android.text.style.ForegroundColorSpan(GOLD), mm.start(), mm.end(), 0);
            TextView body = tv(GroupRoomActivity.this, "", 15.5f, TEXT, false); body.setText(sp); body.setMaxWidth(dp(GroupRoomActivity.this, 250)); bub.addView(body);
            long ct = l(m.get("createdAt")); LinearLayout meta2 = new LinearLayout(GroupRoomActivity.this); meta2.setGravity(Gravity.END);
            TextView tm = tv(GroupRoomActivity.this, fmtTime(ct) + (mine ? (readByAll(ct) ? "  ✓✓" : "  ✓") : ""), 11, mine && readByAll(ct) ? GOLD : SUB, false); meta2.addView(tm); bub.addView(meta2);
            bub.setOnLongClickListener(v -> { menu(d); return true; });
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
            int topGap = first ? 6 : 1; LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2); rl.setMargins(0, dp(GroupRoomActivity.this, topGap), 0, 0); row.setLayoutParams(rl);
            row.setLayoutParams(new AbsListView.LayoutParams(-1, -2)); row.setPadding(0, dp(GroupRoomActivity.this, topGap), 0, 0);
            if (mine) { row.setGravity(Gravity.START); row.addView(bub, bl); }
            else {
                row.setGravity(Gravity.END);
                Av av = new Av(GroupRoomActivity.this, 32); av.setUser(sid); av.setVisibility(last ? View.VISIBLE : View.INVISIBLE);
                LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(dp(GroupRoomActivity.this, 32), dp(GroupRoomActivity.this, 32)); al.setMargins(dp(GroupRoomActivity.this, 7), 0, 0, 0); al.gravity = Gravity.BOTTOM;
                row.addView(bub, bl); row.addView(av, al);
            }
            return row;
        }
    }

    private void menu(final DocumentSnapshot d) {
        final Map<String, Object> m = d.getData(); final String sid = s(m, "senderId"); final String text = s(m, "text");
        final boolean canPin = right("pin", me(), meta), pinned = false; boolean isPinned = false;
        for (Map<String, Object> p : pins()) if (d.getId().equals(s(p, "id"))) isPinned = true;
        final boolean fp = isPinned; final boolean canDel = sid.equals(me()) || right("delete", me(), meta);
        List<String> items = new ArrayList<>(); items.add("نسخ"); if (canPin) items.add(fp ? "إلغاء تثبيت الرسالة" : "تثبيت الرسالة"); if (canDel) items.add("حذف");
        final String[] arr = items.toArray(new String[0]);
        new AlertDialog.Builder(this).setItems(arr, (dg, w) -> {
            String it = arr[w];
            if (it.equals("نسخ")) { ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("m", text)); }
            else if (it.equals("حذف")) new AlertDialog.Builder(this).setTitle("حذف الرسالة").setMessage("حذف هذه الرسالة لدى الجميع؟").setPositiveButton("حذف", (a, b) -> d.getReference().delete()).setNegativeButton("إلغاء", null).show();
            else togglePin(d.getId(), text, fp);
        }).show();
    }
    private void togglePin(String id, String text, boolean isPinned) {
        List<Map<String, Object>> pins = new ArrayList<>(pins());
        if (isPinned) { for (Iterator<Map<String, Object>> it = pins.iterator(); it.hasNext(); ) if (id.equals(s(it.next(), "id"))) it.remove(); pinIdx = -1; db().collection("chats").document(chatId).update("pins", pins); toast(this, "تم إلغاء تثبيت الرسالة"); return; }
        if (pins.size() >= 10) { toast(this, "الحد الأقصى 10 رسائل مثبتة"); return; }
        Map<String, Object> p = new HashMap<>(); p.put("id", id); p.put("text", text.length() > 90 ? text.substring(0, 90) : text); p.put("by", me()); p.put("at", System.currentTimeMillis()); pins.add(p); pinIdx = pins.size() - 1;
        Map<String, Object> up = addSystem(chatId, "pin", null, Collections.<String, Object>singletonMap("ref", id)); up.put("pins", pins);
        db().collection("chats").document(chatId).update(up); toast(this, "تم تثبيت الرسالة");
    }
}
