package com.babylon.chat.iq;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RectShape;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * شريط القصص + صورة الهيدر: منقول حرفياً من سكربتات الـ HTML
 *  babylon-stories-collapse-js / babylon-avatar-fab-js / bb-stories-smooth-js / babylon-header-story-stack-fix-js
 *
 *  - الشريط مطوي افتراضياً ويُفتح بالنقر على صورة الهيدر (أو بسحب الشاشة للأسفل وهي في الأعلى).
 *  - السحب للأعلى (أو زر السهم أسفل الشريط) يطويه. أثناء السحب يتبع الإصبع 1:1 (150px = 100%).
 *  - الإفلات: يكمل بحركة easeOut (1-(1-t)^3) مدتها 110–210ms حسب السرعة، وعتبة السرعة 0.35 px/ms.
 *  - صورة الهيدر تنتقل فعلياً إلى الشريط وتكبر (والعكس عند الطي) والمصغّرات تظهر بتلاشي 220ms.
 *  - المحتوى يصغر بنفس نسبة الشريط (scale حول أعلى المنتصف) والخط الذهبي يتبع التقدم.
 */
public final class StoriesController {

    public static final class Item {
        public String uid = "", name = "", letter = "?";
        public Bitmap photo;
        public boolean me, ring, seen, blue;
        public StoryAvatarView av;      // أفاتار الخلية الحالي (لتشغيل القوس الدوّار عند الضغط)
    }

    public interface OnItem { void click(Item it); }

    // ----- القياسات (px الـ HTML عند --bb-s=1.041 ⇒ dp)
    static final float AV = 45f, ITEM_W = AV + 10f, SIDE = 14f, GAP_ITEMS = 5f, GAP_TXT = 7f, TXT_H = 15f;
    static final float PT = 12f, PB = 10f;
    /** هامش رسم الحلقة داخل حدود الأفاتار (≥ 5dp سماكة+إزاحة الحلقة) كي لا تُقصّ من أي حاوية. */
    static final float RP = 5.3f;
    /** الفراغ (dp) بين حلقة «قصتي» والقصة الملاصقة لها خلفها في الهيدر. */
    static final float GAP_STACK = 2f;
    static final float PTP = PT - RP;
    static final float HH = 22.9f, HMT = -6f, HMB = 2f, TOP_PB = 2f;
    static final float HDR_AV = 32.3f, THUMB = 37.3f, COUNT = 33.8f;   // AV=42 مثل صورة أفاتار المحادثات، HDR_AV=42 مثل زر العدسة

    private final Activity act;
    private final float dens;
    private final FrameLayout root;
    private final StoriesHost home;
    private final ScrollView chatScroll;
    private final LinearLayout top;
    private final HorizontalScrollView scroll;
    private final LinearLayout bar;
    private final LinearLayout hdrBtn;
    private final ChevronView handle;
    private final View line;
    private final OnItem onItem;

    private StoryAvatarView hdrAv;
    /** هامش رسم صورة الهيدر (dp): أصغر عند عدم وجود قصة لتصبح بحجم مصغّرة القصة المجاورة (THUMB). */
    private float hdrPad = RP;
    private LinearLayout hdrStack;

    private List<Item> items = new ArrayList<>();
    private boolean dark;
    private boolean collapsed = true, hidden = true;
    private boolean animating = false;
    private String hmode = "";
    private int H = 0;
    private float curP = 0f;

    private ValueAnimator anim;
    private float[] pend;           // x, y, scrollY عند لمسة البداية
    private Drag drag;
    private boolean locked = false;
    private int lockTop = 0;
    private Fly fl;

    private static final class Drag { char mode; float y0, p0, p, v, ly; long lt; }
    private static final class Fly { boolean thumb; StoryAvatarView v; float[] hr, br; boolean plus; StoryAvatarView srcAv; }
    /** طيران القصص الأخرى (حتى 3 مصغّرات في الهيدر) من موضع مصغّرتها إلى خليتها في الشريط، مثل «قصتي». */
    private final List<Fly> others = new ArrayList<>();

    public StoriesController(Activity act, FrameLayout root, StoriesHost home, ScrollView chatScroll,
                             LinearLayout top, HorizontalScrollView scroll, LinearLayout bar,
                             LinearLayout hdrBtn, OnItem onItem) {
        this.act = act; this.root = root; this.home = home; this.chatScroll = chatScroll;
        this.top = top; this.scroll = scroll; this.bar = bar; this.hdrBtn = hdrBtn; this.onItem = onItem;
        this.dens = act.getResources().getDisplayMetrics().density;

        top.setClipChildren(false); top.setClipToPadding(false);
        scroll.setClipChildren(false); bar.setClipChildren(false);
        scroll.setClipToPadding(false); bar.setClipToPadding(false);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        if (chatScroll.getParent() instanceof ViewGroup) ((ViewGroup) chatScroll.getParent()).setClipChildren(false);
        chatScroll.setClipChildren(false);

        handle = new ChevronView(act);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(HH));
        hl.topMargin = px(HMT); hl.bottomMargin = px(HMB);
        handle.setLayoutParams(hl);
        handle.setClickable(true);
        handle.setOnClickListener(v -> setCollapsed(true));
        // سهم إرجاع/طيّ شريط القصص محذوف: لا يُضاف للتخطيط (الطيّ يبقى بالسحب)، والكائن يبقى فقط كي لا تتعطل بقية المنطق.

        line = new View(act);
        line.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, px(1))));
        ShapeDrawable sd = new ShapeDrawable(new RectShape());
        sd.setShaderFactory(new ShapeDrawable.ShaderFactory() {
            @Override public Shader resize(int w, int h) {
                return new LinearGradient(0, 0, w, 0,
                        new int[]{0x00000000, 0xA6CDD3DB, 0xE6DDE2E9, 0xA6CDD3DB, 0x00000000},   // الفاصل: رمادي فاتح بدل الذهبي
                        new float[]{0f, .2f, .5f, .8f, 1f}, Shader.TileMode.CLAMP);
            }
        });
        line.setBackground(sd);
        top.addView(line);
        top.setPadding(0, 0, 0, px(TOP_PB));
        top.setVisibility(View.GONE);

        // زر الهيدر: [الأفاتار][المصغّرات] بترتيب LTR (كما في الصفحة)
        hdrBtn.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        hdrBtn.setOrientation(LinearLayout.HORIZONTAL);
        hdrBtn.setGravity(Gravity.CENTER_VERTICAL);
        hdrBtn.setClipChildren(false);
        hdrBtn.setClipToPadding(false);
        hdrBtn.setPadding(0, 0, 0, 0);
        if (hdrBtn.getParent() instanceof ViewGroup) ((ViewGroup) hdrBtn.getParent()).setClipChildren(false);
        hdrAv = new StoryAvatarView(act);
        hdrAv.shadow(true).textSp(13f).ringSmall(true);
        hdrAv.setTranslationZ(px(2));   // حلقة قصتي فوق قصة الشخص الآخر
        hdrAv.pad(RP);
        hdrBtn.addView(hdrAv, new LinearLayout.LayoutParams(px(HDR_AV + 2 * RP), px(HDR_AV + 2 * RP)));
        hdrStack = new LinearLayout(act);
        hdrStack.setOrientation(LinearLayout.HORIZONTAL);
        hdrStack.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        hdrStack.setGravity(Gravity.CENTER_VERTICAL);
        hdrStack.setClipChildren(false);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.leftMargin = Math.round(-px(RP - 3) - 0.6f * px(THUMB));   // قصة الآخر 60% خلف حلقة قصتي (قطر الحلقة = قطر القصة المجاورة)
        hdrBtn.addView(hdrStack, sl);
        hdrBtn.setOnClickListener(v -> toggle());
    }

    // ============================================================ ألوان
    private int cText() { return dark ? 0xFFF1F5F9 : 0xFF17212B; }
    private int cSurface() { return dark ? 0xFF17212B : 0xFFFFFFFF; }
    private int cGold() { return dark ? 0xFFE4C45B : 0xFF9B7410; }
    private int px(float dp) { return Math.round(dp * dens); }
    private static float clamp(float p) { return p < 0f ? 0f : p > 1f ? 1f : p; }

    // ============================================================ البيانات
    public void setItems(List<Item> list, boolean dark) {
        this.items = list != null ? list : new ArrayList<Item>();
        this.dark = dark;
        this.hidden = this.items.isEmpty();
        handle.setColor(cText());
        rebuildBar();
        rebuildHeader();
        if (animating || drag != null) apply(curP); else syncStatic();
    }

    public boolean isExpanded() { return !collapsed && !hidden; }

    private void rebuildBar() {
        bar.removeAllViews();
        bar.setPadding(px(SIDE), px(PTP), px(SIDE), px(PB));
        for (int i = 0; i < items.size(); i++) {
            final Item it = items.get(i);
            LinearLayout cell = new LinearLayout(act);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setClipChildren(false);
            cell.setPivotX(px(ITEM_W) / 2f);
            cell.setPivotY(0f);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(px(ITEM_W), ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) cp.leftMargin = px(GAP_ITEMS);
            cell.setLayoutParams(cp);
            StoryAvatarView av = new StoryAvatarView(act);
            av.set(it.letter, it.photo, it.blue).ring(it.ring, it.seen).plus(it.me && !it.ring)
              .theme(dark, cSurface()).textSp(18f).shadow(true).pad(RP);
            it.av = av;
            cell.addView(av, new LinearLayout.LayoutParams(px(AV + 2 * RP), px(AV + 2 * RP)));
            TextView nm = new TextView(act);
            nm.setText(it.name);
            nm.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            nm.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            nm.setTextColor(cText());
            nm.setSingleLine(true);
            nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
            nm.setGravity(Gravity.CENTER);
            nm.setIncludeFontPadding(false);
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(TXT_H));
            np.topMargin = px(GAP_TXT - RP);
            cell.addView(nm, np);
            cell.setOnClickListener(v -> { if (onItem != null) onItem.click(it); });
            bar.addView(cell);
        }
    }

    private void rebuildHeader() {
        Item me = items.isEmpty() ? null : items.get(0);
        if (me != null) hdrAv.set(me.letter, me.photo, me.blue).ring(me.ring, me.seen);
        // بلا قصة: قطر الصورة = قطر مصغّرة القصة المجاورة؛ مع قصة: يبقى الجسم أصغر لتتّسع الحلقة
        hdrPad = (me != null && me.ring) ? RP : (HDR_AV + 2 * RP - THUMB) / 2f;
        hdrAv.pad(hdrPad);
        hdrAv.plus(false).theme(dark, cSurface());
        // القصة المجاورة أصغر من قصتي بـ 1dp: القطر الظاهر لقصتي = الحلقة (جسم + سماكتين) أو الجسم نفسه بلا قصة
        final float myOuter = (me != null && me.ring) ? HDR_AV + 2f * (me.seen ? 2f : 3f) : THUMB;
        final float thumbDp = myOuter - 1f;
        try {
            LinearLayout.LayoutParams slp = (LinearLayout.LayoutParams) hdrStack.getLayoutParams();
            if (slp != null) {
                slp.leftMargin = Math.round(-px(RP - 3) - 0.6f * px(thumbDp));
                hdrStack.setLayoutParams(slp);
            }
        } catch (Throwable ignored) { }
        hdrStack.removeAllViews();
        int shown = 0, active = Math.max(0, items.size() - 1);
        hdrAv.halo(0f, 1f);   // الفاصل صار فتحة شفافة 1dp تُقصّ من المصغّرة الخلفية (لا إطار يحيط بقصتي)
        ThumbView prevThumb = null;
        for (int i = 1; i < items.size() && shown < 3; i++, shown++) {
            Item it = items.get(i);
            ThumbView tv = new ThumbView(act);
            tv.bind(it.letter, it.photo, it.seen, cSurface(), cGold());
            tv.cut(shown == 0 ? (View) hdrAv : (View) prevThumb, hdrBtn);   // فاصل 1dp شفاف بينها وبين التي أمامها فقط
            prevThumb = tv;
            tv.setTranslationZ(px(3 - shown));   // الأولى فوق الثانية فوق الثالثة (قصتي فوق الجميع)
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(px(thumbDp), px(thumbDp));
            if (shown > 0) p.leftMargin = px(-8);
            hdrStack.addView(tv, p);
        }
        hdrStack.setVisibility(active > 0 ? View.VISIBLE : View.GONE);
    }

    // ============================================================ الحالة الثابتة
    private void syncStatic() {
        boolean expanded = isExpanded();
        top.setVisibility(expanded ? View.VISIBLE : View.GONE);
        handle.setVisibility(expanded ? View.VISIBLE : View.GONE);
        hdrBtn.animate().cancel();
        hdrBtn.setAlpha(1f);
        hdrBtn.setVisibility(expanded ? View.INVISIBLE : View.VISIBLE);
        hdrStack.animate().cancel();
        hdrStack.setAlpha(1f);
    }

    // ============================================================ طيّ/فتح برمجي (النقر)
    /** أثناء تحديد المحادثات: شريط التحديد يغطي القصص فلا تُفتح ولا تُسحب ولا تُنقر. */
    private boolean frozen = false;
    public void freeze(boolean on) {
        frozen = on;
        if (on) { pend = null; collapseNow(); }
    }

    public void toggle() {
        if (hidden || frozen) return;
        setCollapsed(!collapsed);
    }

    public void setCollapsed(boolean c) {
        if (drag != null) return;
        if (frozen && !c) return;
        cancelAnim(); clear();
        boolean isC = collapsed;
        if (isC == c || hidden) { collapsed = c; syncStatic(); return; }
        if (c) {
            measureH();
            if (H < px(4)) { collapsed = true; syncStatic(); return; }
            float[] br0 = barRect();
            moved(false); hmode = "c";
            holdHeader();
            lock(chatScroll.getScrollY());
            animating = true;
            flyStart(hdrRect(), br0);
            run(1f, 0f, 1.6f, new Runnable() { @Override public void run() { finishCollapse(); } });
        } else {
            float[] hr1 = hdrRect();
            collapsed = false;
            top.setVisibility(View.VISIBLE); handle.setVisibility(View.VISIBLE);
            moved(true); hmode = "e";
            animating = true;
            measureH();
            flyStart(hr1, barRect());
            apply(0f);
            run(0f, 1f, 1.6f, new Runnable() { @Override public void run() { clear(); unlock(); } });
        }
    }

    /** طيّ فوري بلا حركة (عند دخول محادثة أو الانتقال لصفحة/قسم آخر). */
    public void collapseNow() {
        if (hidden) return;
        if (collapsed && !animating && drag == null) return;
        drag = null; pend = null;
        cancelAnim();
        collapsed = true; hmode = "";
        clear();
        handle.setVisibility(View.GONE);
        syncStatic();
        unlock();
    }

    private void finishCollapse() {
        handle.setVisibility(View.GONE);
        collapsed = true;
        clear();
        syncStatic();
        unlock();
    }

    // ============================================================ قياسات
    private void measureH() {
        bar.setPadding(px(SIDE), px(PTP), px(SIDE), px(PB));
        bar.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        H = bar.getMeasuredHeight();
    }

    private float[] rectIn(View v) {
        int[] a = new int[2], r = new int[2];
        v.getLocationInWindow(a); root.getLocationInWindow(r);
        return new float[]{a[0] - r[0], a[1] - r[1], v.getWidth(), v.getHeight()};
    }

    /** مستطيل جسم الأفاتار فقط (بدون هامش الحلقة). */
    private float[] body(float[] r) {
        float k = px(RP);
        return new float[]{r[0] + k, r[1] + k, r[2] - 2 * k, r[3] - 2 * k};
    }

    private float[] hdrRect() {
        float[] r = rectIn(hdrAv);
        float k = px(hdrPad);
        return new float[]{r[0] + k, r[1] + k, r[2] - 2 * k, r[3] - 2 * k};
    }

    /** مستطيل أفاتار «قصتي» داخل الشريط وهو مفتوح بالكامل (يُحسب هندسياً إن لم يكن مرسوماً بعد). */
    private float[] barRect() { return barRect(0); }

    private float[] barRect(int idx) {
        View first = bar.getChildCount() > idx ? ((ViewGroup) bar.getChildAt(idx)).getChildAt(0) : null;
        if (first != null && top.getVisibility() == View.VISIBLE && first.getWidth() > 0 && scroll.getHeight() >= H - 2 && curP >= 0.999f) {
            return body(rectIn(first));
        }
        int[] a = new int[2], r = new int[2];
        home.getLocationInWindow(a); root.getLocationInWindow(r);
        float x = a[0] - r[0] + px(SIDE) + idx * (px(ITEM_W) + px(GAP_ITEMS)) + (px(ITEM_W) - px(AV)) / 2f - scroll.getScrollX();
        float y = a[1] - r[1] - chatScroll.getScrollY() + px(PT);
        return new float[]{x, y, px(AV), px(AV)};
    }

    // ============================================================ الصورة الطائرة
    private void flyStart(float[] hr, float[] br) {
        if (hr == null || br == null || hr[2] <= 0 || bar.getChildCount() == 0 || items.isEmpty()) return;
        View cell = bar.getChildAt(0);
        View src = ((ViewGroup) cell).getChildAt(0);
        if (!(src instanceof StoryAvatarView)) return;
        Fly f = new Fly();
        f.hr = hr; f.br = br;
        f.srcAv = (StoryAvatarView) src;
        f.v = new StoryAvatarView(act);
        f.v.copyFrom(f.srcAv);
        f.plus = items.get(0).me && !items.get(0).ring;
        int w = Math.round(br[2] + 2 * px(RP)), h = Math.round(br[3] + 2 * px(RP));
        f.v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        f.v.layout(0, 0, w, h);
        // القصص الأخرى: تطير من مصغّراتها في الهيدر (تحت «قصتي» في الترتيب) إلى خلاياها في الشريط
        others.clear();
        int n = Math.min(3, items.size() - 1);
        for (int i = 1; i <= n; i++) {
            if (i >= bar.getChildCount() || i - 1 >= hdrStack.getChildCount()) break;
            View tvw = hdrStack.getChildAt(i - 1);
            View cav = ((ViewGroup) bar.getChildAt(i)).getChildAt(0);
            if (!(tvw instanceof ThumbView) || !(cav instanceof StoryAvatarView) || tvw.getWidth() <= 0) continue;
            float[] tr = rectIn(tvw);
            float[] b2 = barRect(i);
            if (b2 == null || b2[2] <= 0) continue;
            Fly g = new Fly();
            g.hr = tr; g.br = b2; g.thumb = true;
            g.srcAv = (StoryAvatarView) cav;
            g.v = new StoryAvatarView(act);
            g.v.copyFrom(g.srcAv);
            int gw = Math.round(b2[2] + 2 * px(RP)), gh = Math.round(b2[3] + 2 * px(RP));
            g.v.measure(View.MeasureSpec.makeMeasureSpec(gw, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(gh, View.MeasureSpec.EXACTLY));
            g.v.layout(0, 0, gw, gh);
            g.srcAv.setAlpha(0f);
            others.add(g);
        }
        // الأولى فوق الثانية فوق الثالثة: تُضاف بترتيب عكسي (الأحدث إضافةً هو الأعلى)
        for (int j = others.size() - 1; j >= 0; j--) root.getOverlay().add(others.get(j).v);
        root.getOverlay().add(f.v);
        f.srcAv.setAlpha(0f);
        fl = f;
    }

    private void place(Fly f, float p) {
        float[] h = f.hr, b = f.br;
        float hx = h[0] + h[2] / 2f, hy = h[1] + h[3] / 2f, bx = b[0] + b[2] / 2f, by = b[1] + b[3] / 2f;
        float cx = hx + (bx - hx) * p, cy = hy + (by - hy) * p;
        float sc = (h[2] + (b[2] - h[2]) * p) / b[2];
        f.v.setTranslationX(cx - (b[2] + 2 * px(RP)) / 2f);
        f.v.setTranslationY(cy - (b[3] + 2 * px(RP)) / 2f);
        f.v.setScaleX(sc); f.v.setScaleY(sc);
        // القصص الأخرى تهبط على ThumbView (حدّ 2dp)، فيتحوّل شكلها تدريجياً كي لا يحدث قفز في الحجم عند الاستبدال
        if (f.thumb) f.v.setMorph(1f - p, b[2] / h[2]);
        // إطار الفصل يظهر تدريجياً مع الوصول إلى الهيدر (بقدر ما في الهيدر حين وجود قصص أخرى)
        if (!f.thumb) f.v.setFlyRing(sc, p);   // سماكة/بُعد الحلقة ثابتان على الشاشة أثناء الطيران فلا «تسمن» عند الوصول
        if (f.thumb || (f == fl && hdrStack.getVisibility() == View.VISIBLE)) f.v.halo(px(GAP_STACK) * b[2] / h[2], 1f - p);
    }

    private void flyUpdate(float p) {
        if (fl == null) return;
        for (Fly g : others) place(g, p);
        place(fl, p);
        if (fl.plus) fl.v.setPlusAlpha(p);
    }

    private void endFly() {
        boolean hadOthers = !others.isEmpty();
        for (Fly g : others) {
            try { root.getOverlay().remove(g.v); } catch (Throwable ignored) { }
            if (g.srcAv != null) g.srcAv.setAlpha(1f);
        }
        others.clear();
        if (fl != null) {
            try { root.getOverlay().remove(fl.v); } catch (Throwable ignored) { }
            if (fl.srcAv != null) fl.srcAv.setAlpha(1f);
            fl = null;
        }
        releaseHeader(hadOthers);
    }

    // ============================================================ صورة الهيدر
    /** story-avatar-moved: تظهر/تختفي مع بداية الحركة. */
    private void moved(boolean on) {
        hdrBtn.animate().cancel();
        if (on) {
            hdrBtn.animate().alpha(0f).setDuration(100).withEndAction(new Runnable() {
                @Override public void run() { hdrBtn.setVisibility(View.INVISIBLE); }
            }).start();
        } else {
            if (fl != null) holdHeader();
            else { hdrBtn.setAlpha(1f); }
        }
    }

    /** bb-hold: الزر مخفي (بدون إزالة حيّزه) والمصغّرات شفافة حتى نهاية الطيران. */
    private void holdHeader() {
        hdrBtn.animate().cancel();
        hdrBtn.setAlpha(1f);
        hdrBtn.setVisibility(View.INVISIBLE);
        hdrStack.animate().cancel();
        hdrStack.setAlpha(0f);
    }

    private void releaseHeader(boolean flown) {
        if (collapsed || hidden) {
            hdrBtn.setVisibility(View.VISIBLE);
            hdrBtn.setAlpha(1f);
            hdrStack.animate().cancel();
            // القصص الأخرى هبطت فعلاً على مصغّراتها، فتظهر المصغّرات فوراً دون تلاشٍ حتى لا يحدث وميض
            if (flown) hdrStack.setAlpha(1f);
            else if (hdrStack.getAlpha() < 1f) hdrStack.animate().alpha(1f).setDuration(220).start();
        } else {
            hdrStack.animate().cancel();
            hdrStack.setAlpha(1f);
        }
    }

    // ============================================================ التطبيق الفعلي لنسبة التقدم p (0..1)
    private void apply(float p) {
        p = clamp(p);
        curP = p;
        if (locked && chatScroll.getScrollY() != lockTop) chatScroll.scrollTo(0, lockTop);
        int hpx = Math.max(0, Math.round(H * p));
        ViewGroup.LayoutParams lp = scroll.getLayoutParams();
        if (lp.height != hpx) { lp.height = hpx; scroll.setLayoutParams(lp); }
        bar.setPadding(px(SIDE), Math.round(px(PTP) * p), px(SIDE), Math.round(px(PB) * p));
        for (int i = 0; i < bar.getChildCount(); i++) {
            View c = bar.getChildAt(i);
            c.setPivotX(px(ITEM_W) / 2f); c.setPivotY(0f);
            c.setScaleX(p); c.setScaleY(p);
        }
        scroll.setAlpha(Math.min(1f, p * 1.5f));
        top.setPadding(0, 0, 0, Math.round(px(TOP_PB) * p));
        line.setAlpha(p);
        flyUpdate(p);
        LinearLayout.LayoutParams hl = (LinearLayout.LayoutParams) handle.getLayoutParams();
        int hh = Math.round(px(HH) * p), mt = Math.round(px(HMT) * p), mb = Math.round(px(HMB) * p);
        if (hl.height != hh || hl.topMargin != mt || hl.bottomMargin != mb) {
            hl.height = hh; hl.topMargin = mt; hl.bottomMargin = mb;
            handle.setLayoutParams(hl);
        }
        handle.setAlpha("c".equals(hmode) ? 0f : Math.min(1f, p * 1.5f));
    }

    /** إزالة كل القيم المضمّنة والعودة للتخطيط الطبيعي (clear() في الـ HTML). */
    private void clear() {
        ViewGroup.LayoutParams lp = scroll.getLayoutParams();
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        scroll.setLayoutParams(lp);
        bar.setPadding(px(SIDE), px(PTP), px(SIDE), px(PB));
        for (int i = 0; i < bar.getChildCount(); i++) {
            View c = bar.getChildAt(i);
            c.setScaleX(1f); c.setScaleY(1f);
        }
        scroll.setAlpha(1f);
        top.setPadding(0, 0, 0, px(TOP_PB));
        line.setAlpha(1f);
        LinearLayout.LayoutParams hl = (LinearLayout.LayoutParams) handle.getLayoutParams();
        hl.height = px(HH); hl.topMargin = px(HMT); hl.bottomMargin = px(HMB);
        handle.setLayoutParams(hl);
        handle.setAlpha(1f);
        endFly();
        animating = false;
        curP = collapsed ? 0f : 1f;
    }

    private void lock(int topY) { locked = true; lockTop = topY; chatScroll.scrollTo(0, topY); }
    private void unlock() { locked = false; }

    private void cancelAnim() {
        if (anim != null) { anim.removeAllListeners(); anim.cancel(); anim = null; }
    }

    /** نفس run() في الـ HTML: المدة = clamp(110, 210, المسافة/السرعة*1.1 + 70) والمنحنى 1-(1-t)^3. */
    private void run(final float from, final float to, float vel, final Runnable done) {
        cancelAnim();
        float distDp = Math.abs(to - from) * H / dens;
        float dur = Math.max(110f, Math.min(210f, distDp / Math.max(0.6f, Math.abs(vel)) * 1.1f + 70f));
        apply(from);
        final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        anim = a;
        a.setDuration((long) dur);
        a.setInterpolator(new LinearInterpolator());
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator va) {
                float t = (float) va.getAnimatedValue();
                float e = 1f - (float) Math.pow(1f - t, 3);
                apply(from + (to - from) * e);
            }
        });
        a.addListener(new AnimatorListenerAdapter() {
            boolean cancelled = false;
            @Override public void onAnimationCancel(Animator an) { cancelled = true; }
            @Override public void onAnimationEnd(Animator an) {
                if (cancelled) return;
                anim = null;
                apply(to);
                if (done != null) done.run();
            }
        });
        a.start();
    }

    // ============================================================ اللمس
    private boolean open() { return !collapsed && !hidden; }

    /** يُستدعى من onInterceptTouchEvent. */
    boolean intercept(MotionEvent e) {
        if (frozen) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pend = new float[]{e.getX(), e.getY(), chatScroll.getScrollY()};
                return false;
            case MotionEvent.ACTION_POINTER_DOWN:
                pend = null;
                return false;
            case MotionEvent.ACTION_MOVE:
                if (drag != null) return true;
                return maybeStart(e);
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                pend = null;
                return false;
            default:
                return false;
        }
    }

    /** يُستدعى من onTouchEvent (عند اعتراض السحب أو عندما لا يستهلك أي ابن اللمسة). */
    boolean touch(MotionEvent e) {
        if (frozen) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pend = new float[]{e.getX(), e.getY(), chatScroll.getScrollY()};
                return !hidden;
            case MotionEvent.ACTION_POINTER_DOWN:
                pend = null;
                return drag != null;
            case MotionEvent.ACTION_MOVE:
                if (drag == null && !maybeStart(e)) return false;
                if (drag == null) return false;
                float y = e.getY();
                float p = drag.p0 + (y - drag.y0) / (150f * dens);
                long now = SystemClock.uptimeMillis(), dt = now - drag.lt;
                if (dt > 0) { drag.v = 0.7f * drag.v + 0.3f * ((y - drag.ly) / dt / dens); drag.lt = now; drag.ly = y; }
                drag.p = clamp(p);
                apply(drag.p);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                boolean had = drag != null;
                release();
                return had;
            default:
                return drag != null;
        }
    }

    private boolean maybeStart(MotionEvent e) {
        if (pend == null || hidden || drag != null) return false;
        float dy = e.getY() - pend[1], dx = Math.abs(e.getX() - pend[0]);
        if (Math.abs(dy) < px(4) || Math.abs(dy) < dx * 1.2f) return false;
        if (open() && dy < 0) return startDrag('c', e.getY());
        if (!open() && dy > 0 && chatScroll.getScrollY() <= 0) return startDrag('e', e.getY());
        return false;
    }

    private boolean startDrag(char mode, float y) {
        Drag d = new Drag();
        d.mode = mode; d.y0 = y;
        if (mode == 'c') {
            cancelAnim(); clear(); measureH();
            if (H < px(4)) return false;
            float[] br2 = barRect();
            animating = true;
            moved(false); hmode = "c";
            holdHeader();
            flyStart(hdrRect(), br2);
            apply(1f);
            lock((int) (pend != null ? pend[2] : 0));
            d.p0 = 1f;
        } else {
            cancelAnim(); clear();
            float[] hr2 = hdrRect();
            collapsed = false;
            top.setVisibility(View.VISIBLE); handle.setVisibility(View.VISIBLE);
            moved(true); hmode = "e";
            animating = true;
            measureH();
            if (H < px(4)) { collapsed = true; animating = false; syncStatic(); return false; }
            flyStart(hr2, barRect());
            apply(0f);
            lock(0);
            d.p0 = 0f;
        }
        d.v = 0f; d.lt = SystemClock.uptimeMillis(); d.ly = y; d.p = d.p0;
        drag = d;
        return true;
    }

    private void release() {
        pend = null;
        if (drag == null) return;
        Drag d = drag; drag = null;
        boolean closing = d.p < 0.5f;
        if (d.v < -0.35f) closing = true; else if (d.v > 0.35f) closing = false;
        float v = Math.abs(d.v);
        if (closing) {
            moved(false); hmode = "c";
            apply(d.p);
            run(d.p, 0f, v, new Runnable() { @Override public void run() { finishCollapse(); } });
        } else {
            moved(true); hmode = "e";
            run(d.p, 1f, v, new Runnable() { @Override public void run() { clear(); unlock(); } });
        }
    }

    // ============================================================ عناصر مساعدة
    /** سهم «إخفاء القصص»: m6 15 6-6 6 6 بسماكة 2.4 داخل 22×22. */
    static final class ChevronView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        ChevronView(Activity a) {
            super(a);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }
        void setColor(int c) { p.setColor(c); invalidate(); }
        @Override protected void onDraw(Canvas c) {
            float d = getResources().getDisplayMetrics().density;
            float k = 22f * d / 24f;
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            p.setStrokeWidth(2.4f * k);
            path.reset();
            path.moveTo(cx + (6 - 12) * k, cy + (15 - 12) * k);
            path.lineTo(cx + (12 - 12) * k, cy + (9 - 12) * k);
            path.lineTo(cx + (18 - 12) * k, cy + (15 - 12) * k);
            c.drawPath(path, p);
        }
    }

    /** فتحة شفافة (CLEAR) بنصف قطر الدائرة التي أمام هذا الـ View + 1dp؛ تفصل بينهما فقط ولا تحيط بالمجموعة. */
    static void punchGap(Canvas c, View self, View cutter, View anc, Paint clr) {
        if (cutter == null || anc == null || cutter.getWidth() <= 0) return;
        float d = self.getResources().getDisplayMetrics().density;
        float rr;
        if (cutter instanceof StoryAvatarView) rr = ((StoryAvatarView) cutter).outerRadiusPx() + GAP_STACK * d;
        else rr = cutter.getWidth() / 2f + GAP_STACK * d;
        float[] a = centerIn(cutter, anc), b = centerIn(self, anc);
        c.drawCircle(self.getWidth() / 2f + (a[0] - b[0]), self.getHeight() / 2f + (a[1] - b[1]), rr, clr);
    }

    private static float[] centerIn(View v, View anc) {
        float x = v.getWidth() / 2f, y = v.getHeight() / 2f;
        View cur = v;
        while (cur != null && cur != anc) {
            x += cur.getLeft(); y += cur.getTop();
            android.view.ViewParent pp = cur.getParent();
            cur = pp instanceof View ? (View) pp : null;
        }
        return new float[]{x, y};
    }

    private static Paint clearPaint() {
        Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
        q.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR));
        return q;
    }

    /** .hdr-story-thumb : دائرة بحدّ 2px (#d4af37 لم تُشاهَد / #66717d شوهدت) وسطح داخلي وحرف ذهبي أو صورة. */
    static final class ThumbView extends View {
        private String letter = "•";
        private Bitmap photo;
        private boolean seen;
        private int surface, gold;
        private View cutter, anc;
        private final Paint clr = clearPaint();
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        ThumbView(Activity a) { super(a); p.setTextAlign(Paint.Align.CENTER); }
        void cut(View cutter, View anc) { this.cutter = cutter; this.anc = anc; invalidate(); }
        void bind(String l, Bitmap ph, boolean seen, int surface, int gold) {
            this.letter = (l == null || l.isEmpty()) ? "•" : l; this.photo = ph; this.seen = seen;
            this.surface = surface; this.gold = gold; invalidate();
        }
        @Override protected void onDraw(Canvas c) {
            float d = getResources().getDisplayMetrics().density;
            float r = getWidth() / 2f, cx = r, cy = getHeight() / 2f, b = 2f * d;
            int sc = c.saveLayer(new android.graphics.RectF(0, 0, getWidth(), getHeight()), null);
            p.setStyle(Paint.Style.FILL);
            p.setShader(null);
            p.setColor(seen ? 0xFF66717D : 0xFFD4AF37);
            c.drawCircle(cx, cy, r, p);
            boolean hasPhoto = photo != null && !photo.isRecycled();
            if (hasPhoto) {
                p.setColor(surface);
                c.drawCircle(cx, cy, r - b, p);
            } else {
                // بلا صورة: جسم ذهبي متدرج (145°) في النهاري والليلي معاً + حرف أبيض
                p.setShader(StoryAvatarView.css145(cx, cy, (r - b) * 2f, StoryAvatarView.GOLD_A, StoryAvatarView.GOLD_B));
                c.drawCircle(cx, cy, r - b, p);
                p.setShader(null);
            }
            if (hasPhoto) {
                android.graphics.BitmapShader bs = new android.graphics.BitmapShader(photo, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                float dd = (r - b) * 2f;
                float photoScale = Math.max(dd / photo.getWidth(), dd / photo.getHeight());
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.setScale(photoScale, photoScale);
                m.postTranslate(cx - photo.getWidth() * photoScale / 2f, cy - photo.getHeight() * photoScale / 2f);
                bs.setLocalMatrix(m);
                p.setShader(bs);
                c.drawCircle(cx, cy, r - b, p);
                p.setShader(null);
            } else {
                p.setColor(0xFFFFFFFF);
                p.setTypeface(Typeface.DEFAULT_BOLD);
                p.setTextSize(11f * getResources().getDisplayMetrics().scaledDensity);
                Paint.FontMetrics fm = p.getFontMetrics();
                c.drawText(letter, cx, cy - (fm.ascent + fm.descent) / 2f, p);
            }
            punchGap(c, this, cutter, anc, clr);   // فاصل 1dp شفاف عن التي أمامها
            c.restoreToCount(sc);
        }
    }
}
