package com.babylon.chat.iq;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.Gravity;
import android.widget.*;

import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.util.*;

import static com.babylon.chat.iq.GroupsKit.*;

/** قائمة مجموعاتي: صورة، اسم بأيقونة المجموعة، آخر رسالة، الوقت، وزر مجموعة جديدة. */
public class GroupListActivity extends Activity {
    private ListenerRegistration reg; private LinearLayout box;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b); style(this);
        FrameLayout fr = new FrameLayout(this); fr.setBackgroundColor(BG); rtl(fr); fr.setFitsSystemWindows(true);
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL); top.setPadding(dp(this, 8), dp(this, 10), dp(this, 8), dp(this, 8));
        FrameLayout bk = new FrameLayout(this); bk.addView(icon(this, IC_BACK, TEXT, 22), new FrameLayout.LayoutParams(dp(this, 22), dp(this, 22), Gravity.CENTER)); bk.setOnClickListener(v -> finish());
        top.addView(bk, new LinearLayout.LayoutParams(dp(this, 42), dp(this, 42))); top.addView(tv(this, "المجموعات", 18, TEXT, true)); col.addView(top);
        ScrollView sv = new ScrollView(this); box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(0, 0, 0, dp(this, 100)); sv.addView(box); col.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        fr.addView(col, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout fab = new FrameLayout(this); android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(); g.setShape(android.graphics.drawable.GradientDrawable.OVAL); g.setColor(GOLD); fab.setBackground(g); fab.setElevation(dp(this, 8));
        fab.addView(icon(this, IC_USER_PLUS, 0xFF1A1A1A, 26), new FrameLayout.LayoutParams(dp(this, 26), dp(this, 26), Gravity.CENTER));
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(dp(this, 58), dp(this, 58), Gravity.BOTTOM | Gravity.LEFT); fp.leftMargin = dp(this, 18); fp.bottomMargin = dp(this, 22); fr.addView(fab, fp);
        fab.setOnClickListener(v -> { Intent i = new Intent(this, GroupPickerActivity.class); i.putExtra("mode", "create"); startActivity(i); });
        setContentView(fr);
        reg = db().collection("chats").whereArrayContains("participants", me()).addSnapshotListener((snap, e) -> {
            if (snap == null) return; final List<Map<String, Object>> gs = new ArrayList<>(); final Set<String> uids = new HashSet<>();
            for (QueryDocumentSnapshot doc : snap) { Map<String, Object> m = doc.getData(); if (!"group".equals(m.get("type"))) continue; m.put("__id", doc.getId()); gs.add(m); Object lm = m.get("lastMsg"); }
            Collections.sort(gs, (a, c) -> Long.compare(l(c.get("updatedAt")), l(a.get("updatedAt"))));
            loadUsers(uids, () -> render(gs));
        });
    }
    @Override protected void onDestroy() { super.onDestroy(); if (reg != null) reg.remove(); }

    private void render(List<Map<String, Object>> gs) {
        box.removeAllViews();
        if (gs.isEmpty()) { TextView e = tv(this, "لا توجد مجموعات بعد.\nاضغط الزر لإنشاء مجموعة جديدة.", 14, SUB, false); e.setGravity(Gravity.CENTER); e.setPadding(dp(this, 24), dp(this, 50), dp(this, 24), 0); e.setLineSpacing(0, 1.4f); box.addView(e); return; }
        for (final Map<String, Object> m : gs) {
            LinearLayout r = new LinearLayout(this); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(dp(this, 16), dp(this, 10), dp(this, 16), dp(this, 10));
            Av av = new Av(this, 52); av.setGroup(m); r.addView(av);
            LinearLayout t = new LinearLayout(this); t.setOrientation(LinearLayout.VERTICAL); t.setPadding(dp(this, 13), 0, dp(this, 8), 0);
            LinearLayout nr = new LinearLayout(this); nr.setGravity(Gravity.CENTER_VERTICAL); View ic = icon(this, IC_USERS, GOLD, 15); LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(this, 15), dp(this, 15)); ip.setMargins(0, 0, dp(this, 5), 0); nr.addView(ic, ip);
            TextView nm = tv(this, s(m, "name").isEmpty() ? "مجموعة" : s(m, "name"), 16, TEXT, true); nm.setSingleLine(true); nm.setEllipsize(TextUtils.TruncateAt.END); nr.addView(nm); t.addView(nr);
            Map<String, Object> lm = map(m, "lastMsg"); String prev = "لا توجد رسائل بعد", time = "";
            if (!lm.isEmpty()) { boolean sys = isTrue(lm.get("system")); String who = s(lm, "senderId").equals(me()) ? "أنت: " : (s(lm, "senderName").isEmpty() ? "" : s(lm, "senderName") + ": "); prev = sys ? s(lm, "text") : who + s(lm, "text"); time = fmtTime(l(lm.get("at")) > 0 ? l(lm.get("at")) : l(m.get("updatedAt"))); }
            TextView pv = tv(this, prev, 13.5f, SUB, false); pv.setSingleLine(true); pv.setEllipsize(TextUtils.TruncateAt.END); t.addView(pv);
            r.addView(t, new LinearLayout.LayoutParams(0, -2, 1)); r.addView(tv(this, time, 12, SUB, false));
            final String id = s(m, "__id"); r.setOnClickListener(v -> { Intent i = new Intent(this, GroupRoomActivity.class); i.putExtra("chatId", id); startActivity(i); });
            box.addView(r);
        }
    }
}
