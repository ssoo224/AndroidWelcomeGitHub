package com.babylon.chat.iq;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * معاينة الفقاعات في «إعدادات المحادثات» — نقل من <div class="pv"> في الـ HTML:
 *   .pv: خلفية --chat-bg، حشوة 14، ارتفاع أدنى 190، فجوة 8، محتوى في الوسط، فوقه .pattern-layer (30 رمزاً)
 *   فقاعة الآخر (يمين): الاسم «أبو علي» + شارة التوثيق، «صباح الخير! شلونك اليوم؟»، الوقت 3:46
 *   فقاعتي (يسار): «الحمدلله تمام، وانت شلونك ◉»، الوقت 4:01 + ✓✓ مقروءة
 *   الفقاعة: حشوة 6/10/18/10، أقصى عرض 80%، زوايا --r مع ذيل 4 (أسفل يمين لي، أسفل يسار للآخر)، حدّ 1px للآخر.
 */
public final class ChatPreview {
    private final Activity act;
    private final FrameLayout host;
    private final ChatPattern pattern;
    private final Bub other, mine;
    private final TextView nameTv, otherText, otherTime, mineText, mineTime;
    private final ImageView tick;
    private final View fadeTop, fadeBottom;
    private final float d;

    /** فقاعة بحدّ أقصى 80% من عرض الحاوية (max-width:80%) */
    private static final class Bub extends FrameLayout {
        Bub(Activity a) { super(a); }
        @Override protected void onMeasure(int w, int h) {
            int size = MeasureSpec.getSize(w);
            if (size > 0) w = MeasureSpec.makeMeasureSpec((int) (size * 0.8f), MeasureSpec.AT_MOST);
            super.onMeasure(w, h);
        }
    }

    private int dp(float v) { return (int) (v * d + 0.5f); }

    private TextView tv(String s, float sp, boolean bold) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextSize(sp);
        t.setTypeface(null, bold ? Typeface.BOLD : Typeface.NORMAL);
        t.setIncludeFontPadding(false);
        return t;
    }

    public ChatPreview(Activity a, FrameLayout host) {
        this.act = a; this.host = host; this.d = a.getResources().getDisplayMetrics().density;
        host.removeAllViews();
        host.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        host.setMinimumHeight(dp(260));

        pattern = new ChatPattern(a).grid(5, 64f, 26f, 30);
        host.addView(pattern, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);
        col.setPadding(dp(14), dp(46), dp(14), dp(46));   // مسافة للتلاشي أعلى وأسفل
        col.setMinimumHeight(dp(260));
        host.addView(col, new FrameLayout.LayoutParams(-1, -2));

        // ---- فقاعة الآخر
        other = new Bub(a);
        other.setPadding(dp(10), dp(6), dp(10), dp(18));
        LinearLayout oc = new LinearLayout(a);
        oc.setOrientation(LinearLayout.VERTICAL);
        LinearLayout nameRow = new LinearLayout(a);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        nameRow.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        nameTv = tv("أبو علي", 13, true);
        nameRow.addView(nameTv);
        ImageView badge = new ImageView(a);
        badge.setImageDrawable(new SearchPage.Seal());
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(dp(14), dp(14));
        bl.setMarginEnd(0); bl.setMarginStart(dp(4));
        nameRow.addView(badge, bl);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-2, -2);
        nl.bottomMargin = dp(2);
        oc.addView(nameRow, nl);
        otherText = tv("صباح الخير", 15, true);
        otherText.setGravity(Gravity.RIGHT);
        otherText.setLineSpacing(0, 1.1f);
        oc.addView(otherText, new LinearLayout.LayoutParams(-2, -2));   // WRAP: عرض MATCH_PARENT يُتجاهل في حساب عرض الأب فيضيق النص ويُقصّ سطره الثاني
        other.addView(oc, new FrameLayout.LayoutParams(-2, -2));
        otherTime = tv("3:46", 10.5f, false);
        FrameLayout.LayoutParams ot = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.LEFT);
        ot.leftMargin = dp(-2); ot.bottomMargin = dp(-15);
        other.addView(otherTime, ot);
        LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(-2, -2);
        ol.gravity = Gravity.RIGHT;
        col.addView(other, ol);

        // ---- فقاعتي
        mine = new Bub(a);
        mine.setPadding(dp(10), dp(6), dp(10), dp(18));
        mineText = tv("صباح النور حياك الله ◉", 15, true);
        mineText.setGravity(Gravity.RIGHT);
        mineText.setLineSpacing(0, 1.1f);
        mine.addView(mineText, new FrameLayout.LayoutParams(-2, -2));
        LinearLayout meta = new LinearLayout(a);
        meta.setOrientation(LinearLayout.HORIZONTAL);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        meta.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        mineTime = tv("4:01", 10.5f, false);
        meta.addView(mineTime);
        tick = new ImageView(a);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.setMarginStart(dp(3));
        meta.addView(tick, tl);
        FrameLayout.LayoutParams mt = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.LEFT);
        mt.leftMargin = dp(-2); mt.bottomMargin = dp(-15);
        mine.addView(meta, mt);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-2, -2);
        ml.gravity = Gravity.LEFT;
        ml.topMargin = dp(8);
        col.addView(mine, ml);
        col.setClipChildren(false);
        other.setClipChildren(false); mine.setClipChildren(false);
        other.setClipToPadding(false); mine.setClipToPadding(false);
        col.setClipToPadding(false);

        // ---- تلاشي أعلى/أسفل المعاينة: المحادثة «تستمر» ثم تذوب في خلفية الصفحة بلا حافة حادّة
        fadeTop = new View(a);
        fadeBottom = new View(a);
        host.addView(fadeTop, new FrameLayout.LayoutParams(-1, dp(56), Gravity.TOP));
        host.addView(fadeBottom, new FrameLayout.LayoutParams(-1, dp(56), Gravity.BOTTOM));
        host.setClipChildren(true);
    }

    private static android.graphics.drawable.GradientDrawable fade(int bg, boolean topDown) {
        int clear = bg & 0x00FFFFFF;
        int[] c = topDown ? new int[]{bg, bg, clear} : new int[]{clear, bg, bg};
        return new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, c);
    }

    /** يطبّق الثيم/الوضع/الخط/الانحناء على المعاينة (applyPrefs في الـ HTML). */
    public void apply(String themeId, boolean dark, int fontSp, int radiusDp) {
        ChatThemes.Spec sp = ChatThemes.byId(themeId);
        ChatThemes.Pal p = ChatThemes.previewPal(sp.id, dark);
        boolean babylon = ChatThemes.isBabylon(sp.id);
        host.setBackgroundColor(p.chatBg);
        int pageBg = Tk.pageBg(dark);
        fadeTop.setBackground(fade(pageBg, true));
        fadeBottom.setBackground(fade(pageBg, false));
        pattern.style(sp.symbols, p.pattern, p.patternOp);

        float r = dp(radiusDp), tail = dp(4);
        other.setBackground(ChatThemes.bubble(p, false, new float[]{r, r, r, r, r, r, tail, tail}, d));
        mine.setBackground(ChatThemes.bubble(p, true, new float[]{r, r, tail, tail, r, r, r, r}, 0f));
        // ملاحظة: مصفوفة الزوايا (TL,TR,BR,BL) → مرسلتي: BR=4 ، المستلمة: BL=4

        // نص المعاينة: نهاري صباحي / ليلي مسائي
        otherText.setText(dark ? "مساء الخير" : "صباح الخير");
        mineText.setText(dark ? "مساء النور حياك الله ◉" : "صباح النور حياك الله ◉");

        nameTv.setTextColor(p.accent);
        otherText.setTextSize(fontSp);
        otherText.setTextColor(p.textOther);
        otherTime.setTextColor(p.metaOther);

        mineText.setTextSize(fontSp);
        mineText.setTextColor(p.textMine);
        int mineMeta;
        int tickColor;
        if (babylon) {
            mineMeta = dark ? 0xB8FFFFFF : p.textMine;      // rgba(255,255,255,.72) ليلي / #292216 نهاري
            tickColor = dark ? p.tick : p.textMine;         // .check-mark.read → --tick-read ، وفي النهاري يرثها #292216
        } else {
            mineMeta = p.textMine;
            tickColor = p.tick;
        }
        mineTime.setTextColor(mineMeta);
        tick.setImageDrawable(new MsgViews.TickDrawable(act, MsgViews.READ, 0, tickColor));
    }
}
