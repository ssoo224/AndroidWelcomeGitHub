package com.babylon.chat.iq;

import android.app.Activity;
import android.content.Intent;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.util.*;

import static com.babylon.chat.iq.GroupsKit.*;

/** صفوف المجموعات داخل قائمة المحادثات الرئيسية (تُدمج حسب وقت آخر نشاط) مع عدّاد غير المقروء وشارة @. */
final class GroupRows {
    static final class G { String id; Map<String, Object> d; long ts; int unread; boolean mention; String sig = ""; }
    private static final Map<String, G> GS = new LinkedHashMap<>();
    private static ListenerRegistration reg; private static String regUid = "";

    /** يبدأ مراقبة مجموعاتي؛ onChange تعيد بناء القائمة. */
    static void start(final Activity act, final Runnable onChange) {
        String me = me(); if (me.isEmpty() || (reg != null && me.equals(regUid))) return; stop(); regUid = me;
        reg = db().collection("chats").whereArrayContains("participants", me).addSnapshotListener((snap, e) -> {
            if (snap == null) return; final Set<String> alive = new HashSet<>(); final List<String> uids = new ArrayList<>();
            for (QueryDocumentSnapshot doc : snap) { Map<String, Object> m = doc.getData(); if (!"group".equals(m.get("type"))) continue; alive.add(doc.getId()); G g = GS.get(doc.getId()); if (g == null) { g = new G(); g.id = doc.getId(); GS.put(g.id, g); } g.d = m; g.ts = Math.max(l(m.get("updatedAt")), l(map(m, "lastMsg").get("at")));
                String sig = l(m.get("updatedAt")) + "|" + l(map(m, "readAt").get(me)); if (!sig.equals(g.sig)) { g.sig = sig; countUnread(g, onChange); } }
            GS.keySet().retainAll(alive); onChange.run();
        });
    }
    static void stop() { if (reg != null) reg.remove(); reg = null; regUid = ""; GS.clear(); }

    private static void countUnread(final G g, final Runnable onChange) {
        final long mineRead = l(map(g.d, "readAt").get(me())); final String me = me();
        db().collection("chats").document(g.id).collection("messages").orderBy("createdAt", Query.Direction.DESCENDING).limit(60).get().addOnSuccessListener(qs -> {
            int n = 0; boolean mention = false;
            for (DocumentSnapshot m : qs.getDocuments()) { Map<String, Object> x = m.getData(); if (l(x.get("createdAt")) <= mineRead) break; if (me.equals(s(x, "senderId")) || isTrue(x.get("system"))) continue; n++; if (strList(x, "mentions").contains(me)) mention = true; }
            if (n != g.unread || mention != g.mention) { g.unread = n; g.mention = mention; onChange.run(); }
        });
    }
    static int unreadChats() { int n = 0; for (G g : GS.values()) if (g.unread > 0) n++; return n; }
    static long tsOf(G g) { return g.ts; }
    static List<G> all() { List<G> l = new ArrayList<>(GS.values()); Collections.sort(l, (a, b) -> Long.compare(b.ts, a.ts)); return l; }

    /** يبني صف مجموعة بنفس هندسة صفوف التطبيق (محتوى يساراً والصورة أقصى اليمين). */
    static View row(final Activity act, final G g, boolean dark, int cText, int cGold, boolean selectedUnreadOnly) {
        final Th t = th(act); final float d = t.dens; int sub = dark ? 0xFF8798AA : 0xFF6B7A8C; boolean hasUnread = g.unread > 0;
        LinearLayout row = new LinearLayout(act); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL); row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR); row.setPadding(Math.round(12 * d), Math.round(6.9f * d), Math.round(12 * d), Math.round(6.9f * d)); row.setMinimumHeight(Math.round(65.8f * d)); row.setTag("grow");
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2); rp.bottomMargin = Math.round(3 * d); row.setLayoutParams(rp);
        Av av = new Av(act, 46); av.setGroup(g.d); LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(Math.round(52 * d), Math.round(52 * d));
        FrameLayout avw = new FrameLayout(act); avw.addView(av, new FrameLayout.LayoutParams(Math.round(46 * d), Math.round(46 * d), Gravity.CENTER));
        LinearLayout mid = new LinearLayout(act); mid.setOrientation(LinearLayout.VERTICAL); LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, -2, 1f); mp.setMarginEnd(Math.round(12 * d)); mid.setLayoutParams(mp);
        LinearLayout r1 = new LinearLayout(act); r1.setGravity(Gravity.CENTER_VERTICAL);
        TextView tm = new TextView(act); long at = g.ts; tm.setText(at > 0 ? fmtTimeList(at) : ""); tm.setTextSize(12.5f); tm.setTypeface(null, hasUnread ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL); tm.setTextColor(hasUnread ? cGold : sub); tm.setIncludeFontPadding(false); r1.addView(tm, new LinearLayout.LayoutParams(-2, -2));
        if (s(map(g.d, "lastMsg"), "senderId").equals(me()) && !isTrue(map(g.d, "lastMsg").get("system"))) {   // آخر رسالة مني: ✓ مرسلة، ✓✓ إن قرأها كل الأعضاء
            long lat = l(map(g.d, "lastMsg").get("at")); boolean seen = lat > 0; Map<String, Object> ra = map(g.d, "readAt");
            for (String u : parts(g.d)) if (!u.equals(me()) && l(ra.get(u)) < lat) { seen = false; break; }
            ImageView tk = new ImageView(act); tk.setImageResource(seen ? R.drawable.ic_check_double_16 : R.drawable.ic_check_16); tk.setColorFilter(seen ? cGold : sub);
            LinearLayout.LayoutParams tkp = new LinearLayout.LayoutParams(Math.round(17 * d), Math.round(17 * d)); tkp.setMarginStart(Math.round(3 * d)); r1.addView(tk, tkp);
        }
        LinearLayout nr = new LinearLayout(act); nr.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL); nr.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        Ic gi = icon(act, IC_USERS, t.gold, 15 * t.s); LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(Math.round(15 * t.s * d), Math.round(15 * t.s * d)); gp.setMarginEnd(Math.round(5 * d)); nr.addView(gi, gp);
        TextView nm = new TextView(act); nm.setText(s(g.d, "name").isEmpty() ? "مجموعة" : s(g.d, "name")); nm.setTextSize(16.66f); nm.setTypeface(null, android.graphics.Typeface.BOLD); nm.setTextAlignment(View.TEXT_ALIGNMENT_GRAVITY); nm.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL); nm.setSingleLine(true); nm.setEllipsize(TextUtils.TruncateAt.END); nm.setTextColor(hasUnread && !dark ? 0xFF000000 : cText); nm.setIncludeFontPadding(false); nm.setMinHeight(Math.round(20 * d)); if (hasUnread) { nm.setTag("keepbold"); nm.setTypeface((android.os.Build.VERSION.SDK_INT >= 28 ? android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 900, false) : android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD))); } nr.addView(nm, new LinearLayout.LayoutParams(-2, -2));
        r1.addView(nr, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout r2 = new LinearLayout(act); r2.setGravity(Gravity.CENTER_VERTICAL); r2.setMinimumHeight(Math.round(22.9f * d)); LinearLayout.LayoutParams r2p = new LinearLayout.LayoutParams(-1, -2); r2p.topMargin = Math.round(5 * d);
        Map<String, Object> lm = map(g.d, "lastMsg"); String prev; String ty = s(lm, "type");
        if (lm.isEmpty()) prev = "لا توجد رسائل بعد"; else { boolean sys = isTrue(lm.get("system")); String who = s(lm, "senderId").equals(me()) ? "أنت: " : (s(lm, "senderName").isEmpty() ? "" : s(lm, "senderName") + ": "); prev = sys ? s(lm, "text") : who + s(lm, "text"); }
        final int mediaKind = (lm.isEmpty() || isTrue(lm.get("system"))) ? 0 : MsgViews.mediaKindOf(s(lm, "text"));
        if (mediaKind > 0) { String who2 = s(lm, "senderId").equals(me()) ? "أنت: " : (s(lm, "senderName").isEmpty() ? "" : s(lm, "senderName") + ": "); prev = who2 + MsgViews.mediaLabelOf(mediaKind); }
        TextView ls = new TextView(act); ls.setText(prev); ls.setTextSize(14.05f);
        ls.setTypeface(!hasUnread ? android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
                : android.os.Build.VERSION.SDK_INT >= 28 ? android.graphics.Typeface.create(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL), 600, false)
                : android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));
        ls.setSingleLine(true); ls.setEllipsize(TextUtils.TruncateAt.END); ls.setIncludeFontPadding(false); ls.setTextAlignment(View.TEXT_ALIGNMENT_GRAVITY); ls.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        ls.setTextColor(hasUnread ? (dark ? cText : 0xFF000000) : sub);
        if (mediaKind == 0) ls.setText(MsgViews.iconize(act, prev, ls.getCurrentTextColor(), 15));
        if (hasUnread) { ls.setTag("keepbold"); ls.setTypeface((android.os.Build.VERSION.SDK_INT >= 28 ? android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 900, false) : android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD))); }
        if (mediaKind > 0) {
            LinearLayout mw = new LinearLayout(act); mw.setLayoutDirection(View.LAYOUT_DIRECTION_LTR); mw.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            ImageView mi = new ImageView(act); mi.setImageResource(MsgViews.mediaIconOf(mediaKind)); mi.setColorFilter(hasUnread ? cText : sub);
            LinearLayout.LayoutParams mil = new LinearLayout.LayoutParams(Math.round(16 * d), Math.round(16 * d)); mil.setMarginEnd(Math.round(5 * d)); mw.addView(mi, mil);
            mw.addView(ls, new LinearLayout.LayoutParams(-2, -2)); r2.addView(mw, new LinearLayout.LayoutParams(0, -2, 1f));
        } else r2.addView(ls, new LinearLayout.LayoutParams(0, -2, 1f));
        if (hasUnread) {
            LinearLayout bw = new LinearLayout(act); bw.setGravity(Gravity.CENTER_VERTICAL);
            if (g.mention) { TextView at2 = new TextView(act); at2.setText("@"); at2.setTextSize(12.5f); at2.setTypeface(null, android.graphics.Typeface.BOLD); at2.setGravity(Gravity.CENTER); at2.setTextColor(0xFF1A1A1A); at2.setIncludeFontPadding(false); at2.setBackground(round(t.gold, Math.round(11.5f * d))); at2.setMinWidth(Math.round(22.9f * d)); at2.setMinHeight(Math.round(22.9f * d)); LinearLayout.LayoutParams ap2 = new LinearLayout.LayoutParams(-2, -2); ap2.setMarginEnd(Math.round(5 * d)); bw.addView(at2, ap2); }
            TextView b = new TextView(act); b.setText(g.unread > 99 ? "99+" : String.valueOf(g.unread)); b.setTextSize(12.5f); b.setTypeface(null, android.graphics.Typeface.BOLD); b.setGravity(Gravity.CENTER); b.setTextColor(dark ? 0xFFE4C45B : 0xFFA9780F); b.setIncludeFontPadding(false); b.setMinWidth(Math.round(22.9f * d)); b.setMinHeight(Math.round(22.9f * d)); b.setPadding(Math.round(7 * d), 0, Math.round(7 * d), 0); b.setBackground(round(dark ? 0x40E4C45B : 0x38D4A017, Math.round(11.5f * d))); bw.addView(b);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2); bl.rightMargin = Math.round(8 * d); r2.addView(bw, bl); View lsw = r2.getChildAt(0); r2.removeViewAt(0); r2.addView(lsw);
        }
        mid.addView(r1); mid.addView(r2, r2p); row.addView(mid); row.addView(avw, ap);
        final String id = g.id; row.setOnClickListener(x -> { Intent i = new Intent(act, GroupRoomActivity.class); i.putExtra("chatId", id); act.startActivity(i); }); return row;
    }
    private static String fmtTimeList(long ts) {
        Calendar now = Calendar.getInstance(), c = Calendar.getInstance(); c.setTimeInMillis(ts); long days = (now.getTimeInMillis() - ts) / 86400000L;
        if (now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR) && now.get(Calendar.YEAR) == c.get(Calendar.YEAR)) return new java.text.SimpleDateFormat("h:mm a", new Locale("ar")).format(new Date(ts));
        if (days >= 0 && days < 7) return new java.text.SimpleDateFormat("EEEE", new Locale("ar")).format(new Date(ts)); return new java.text.SimpleDateFormat("d MMMM", new Locale("ar")).format(new Date(ts));
    }
}
