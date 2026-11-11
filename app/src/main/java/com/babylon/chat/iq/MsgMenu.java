package com.babylon.chat.iq;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * قائمة الرسالة العائمة — نسخة أندرويد من الـ HTML:
 *   #msgModal  (شريط التفاعلات + بطاقة الأزرار: رد / نسخ / تحميل / تعديل / حذف)
 *   #selEmojiBar (الحبة العائمة فوق الرسالة المحددة بالضغط المطوّل)
 *   #messageDeleteModal (حذف لدي فقط / حذف لدى الكل)
 * الخلفية: تعتيم + ضبابية، مع فتحة بحواف الرسالة تبقي الرسالة المحددة واضحة (msgBlurLayer في الـ HTML).
 */
public final class MsgMenu {

    public interface Cb {
        void reply();
        void copy();
        void download();
        void edit();
        void delete();
        void react(String emoji);
        default void pin() { }
    }

    public static final class Spec {
        public boolean canCopy, canEdit, canDownload, canPin, pinned;
        public String downloadLabel = "تحميل";
        public String myReaction = "";
    }

    public interface DeleteCb { void forMe(); void forAll(); }

    private static final String[] QUICK = {"😂", "❤️", "👍🏻", "😭", "✨"};
    private static final String[] ALL = {"😂","😭","😉","😌","🫠","😡","🥵","🤢","💩","🤡","😎","🤧","☠️","🌚","🙈","🙉","🙊","✨","⚡","💢","💤","🔥","💯","🎉","💥","❤️","🤍","❤️‍🔥","❤️‍🩹","💔","🫦","👀","🫶🏻","👍🏻","🖕🏻","✍🏻","🙏🏻","💅🏻"};

    private static FrameLayout overlay;
    private static ViewGroup overlayParent;
    private static View pillView;

    private MsgMenu() { }

    public static boolean isShowing() { return overlay != null; }

    public static void dismiss() {
        final FrameLayout o = overlay;
        final ViewGroup p = overlayParent;
        overlay = null; overlayParent = null; pillView = null;
        if (o == null || p == null) return;
        o.animate().alpha(0f).setDuration(120).withEndAction(() -> { try { p.removeView(o); } catch (Throwable ignored) { } }).start();
    }

    // ------------------------------------------------------------------ helpers
    private static int dp(Context c, float v) { return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f); }

    private static int cardBg(boolean d)    { return d ? 0xFF202D3A : 0xFFFFFFFF; }
    private static int cardBorder(boolean d){ return d ? 0xFF334155 : 0xFFE1E7EF; }
    private static int textMain(boolean d)  { return d ? 0xFFF1F5F9 : 0xFF17212B; }
    private static int danger(boolean d)    { return d ? 0xFFEF6464 : 0xFFDC2626; }

    private static GradientDrawable card(Context c, boolean d, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(cardBg(d));
        g.setCornerRadius(dp(c, radiusDp));
        g.setStroke(dp(c, 1), cardBorder(d));
        return g;
    }

    private static void pressScale(final View v) {
        v.setOnTouchListener((x, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) x.animate().scaleX(.88f).scaleY(.88f).setDuration(90).start();
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) x.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
            return false;
        });
    }

    /** طبقة تملأ الشاشة: لقطة مصغّرة مضبّبة + تعتيم، وفتحة (even-odd) حول الرسالة. */
    private static final class Backdrop extends View {
        private final Bitmap snap;
        private final int dim;
        private final RectF hole;
        private final float r;
        private final Paint bp = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Path path = new Path();

        Backdrop(Context c, Bitmap snap, int dim, RectF hole, float r) {
            super(c);
            this.snap = snap; this.dim = dim; this.hole = hole; this.r = r;
        }

        @Override protected void onDraw(Canvas cv) {
            path.reset();
            path.setFillType(Path.FillType.EVEN_ODD);
            path.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
            if (hole != null) path.addRoundRect(hole, r, r, Path.Direction.CW);
            cv.save();
            try { cv.clipPath(path); } catch (Throwable ignored) { }
            if (snap != null) cv.drawBitmap(snap, null, new RectF(0, 0, getWidth(), getHeight()), bp);
            cv.drawColor(dim);
            cv.restore();
        }
    }

    private static FrameLayout attach(final Activity act, boolean dark) { return attach(act, dark, true); }

    /** block=false: طبقة لا تعترض اللمس (حبة التحديد) كي تبقى الرسائل تحتها قابلة للنقر والتمرير. */
    private static FrameLayout attach(final Activity act, boolean dark, boolean block) {
        dismissNow();
        final ViewGroup content = act.findViewById(android.R.id.content);
        final FrameLayout root = new FrameLayout(act);
        if (block) {
            root.setClickable(true);
            root.setFocusableInTouchMode(true);
            root.setOnKeyListener((v, code, ev) -> {
                if (code == KeyEvent.KEYCODE_BACK && ev.getAction() == KeyEvent.ACTION_UP) { dismiss(); return true; }
                return code == KeyEvent.KEYCODE_BACK;
            });
        }
        content.addView(root, new FrameLayout.LayoutParams(-1, -1));
        if (block) root.requestFocus();
        overlay = root; overlayParent = content;
        pillView = null;
        return root;
    }

    private static void dismissNow() {
        if (overlay != null && overlayParent != null) { try { overlayParent.removeView(overlay); } catch (Throwable ignored) { } }
        overlay = null; overlayParent = null;
    }

    private static Bitmap blurSnap(ViewGroup content) {
        try {
            float s = 1f / 4f;
            Bitmap b = Bitmap.createBitmap(Math.max(1, (int) (content.getWidth() * s)), Math.max(1, (int) (content.getHeight() * s)), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.scale(s, s);
            content.draw(c);
            for (int i = 0; i < 3; i++) boxBlur(b, 5);
            return b;
        } catch (Throwable t) { return null; }
    }

    /** Box blur أفقي ثم عمودي على الصورة نفسها (O(n) لكل بكسل). */
    private static void boxBlur(Bitmap bm, int r) {
        int w = bm.getWidth(), h = bm.getHeight();
        int[] px = new int[w * h], tmp = new int[w * h];
        bm.getPixels(px, 0, w, 0, 0, w, h);
        blurPass(px, tmp, w, h, r, true);
        blurPass(tmp, px, w, h, r, false);
        bm.setPixels(px, 0, w, 0, 0, w, h);
    }

    private static void blurPass(int[] src, int[] dst, int w, int h, int r, boolean horiz) {
        int lines = horiz ? h : w, len = horiz ? w : h, div = 2 * r + 1;
        for (int l = 0; l < lines; l++) {
            int sa = 0, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = src[idx(l, Math.max(0, Math.min(len - 1, i)), w, horiz)];
                sa += p >>> 24; sr += (p >> 16) & 255; sg += (p >> 8) & 255; sb += p & 255;
            }
            for (int i = 0; i < len; i++) {
                dst[idx(l, i, w, horiz)] = ((sa / div) << 24) | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int add = src[idx(l, Math.min(len - 1, i + r + 1), w, horiz)];
                int rem = src[idx(l, Math.max(0, i - r), w, horiz)];
                sa += (add >>> 24) - (rem >>> 24);
                sr += ((add >> 16) & 255) - ((rem >> 16) & 255);
                sg += ((add >> 8) & 255) - ((rem >> 8) & 255);
                sb += (add & 255) - (rem & 255);
            }
        }
    }

    private static int idx(int line, int i, int w, boolean horiz) { return horiz ? line * w + i : i * w + line; }

    private static RectF anchorRect(View anchor, View content) {
        int[] a = new int[2], c = new int[2];
        anchor.getLocationInWindow(a);
        content.getLocationInWindow(c);
        float x = a[0] - c[0], y = a[1] - c[1];
        return new RectF(x, y, x + anchor.getWidth(), y + anchor.getHeight());
    }

    // ------------------------------------------------------------------ القائمة الكاملة
    public static void show(final Activity act, final View anchor, final boolean dark, final int insetTop, final int insetBottom,
                            final Spec spec, final Cb cb) {
        final FrameLayout root = attach(act, dark);
        final ViewGroup content = (ViewGroup) root.getParent();
        // ضبابية ناعمة (تصغير + Box Blur ثلاث مرات) بلا تكسير، والرسالة تُرسم فوقها بشكلها الحقيقي (حوافها وذيلها كاملة)
        root.setVisibility(View.INVISIBLE);
        final Bitmap snap = blurSnap(content);
        root.setVisibility(View.VISIBLE);
        final RectF hole = anchorRect(anchor, content);
        final Backdrop bd = new Backdrop(act, snap, dark ? 0x59050C16 : 0x331E293B, null, 0f);
        root.addView(bd, new FrameLayout.LayoutParams(-1, -1));
        try {
            Bitmap ab = Bitmap.createBitmap(Math.max(1, anchor.getWidth()), Math.max(1, anchor.getHeight()), Bitmap.Config.ARGB_8888);
            anchor.draw(new Canvas(ab));
            ImageView copy = new ImageView(act);
            copy.setImageBitmap(ab);
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(anchor.getWidth(), anchor.getHeight());
            clp.leftMargin = (int) hole.left; clp.topMargin = (int) hole.top;
            root.addView(copy, clp);
        } catch (Throwable ignored) { }
        bd.setAlpha(0f);
        bd.animate().alpha(1f).setDuration(200).start();
        root.setOnClickListener(v -> dismiss());

        final int W = content.getWidth(), H = content.getHeight();
        final int shellW = Math.min(dp(act, 236), W - dp(act, 24));

        final LinearLayout shell = new LinearLayout(act);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        shell.setClickable(true);                       // لا يمرّر النقرات للخلفية (stopPropagation)
        shell.setClipChildren(false);
        shell.setClipToPadding(false);

        // ---- شريط التفاعلات
        final LinearLayout bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(dark ? 0xFF2B3B4F : 0xFFFFFFFF);
        barBg.setCornerRadius(dp(act, 14));
        barBg.setStroke(dp(act, 1.5f), dark ? 0x80FFFFFF : 0xFFB9C6D6);
        bar.setBackground(barBg);
        bar.setPadding(dp(act, 6), dp(act, 4), dp(act, 6), dp(act, 4));

        LinearLayout top = new LinearLayout(act);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        final ImageView plus = new ImageView(act);
        plus.setImageResource(R.drawable.ic_mm_plus);
        plus.setColorFilter(textMain(dark));
        plus.setPadding(dp(act, 6.5f), dp(act, 6.5f), dp(act, 6.5f), dp(act, 6.5f));
        GradientDrawable pbg = new GradientDrawable(); pbg.setShape(GradientDrawable.OVAL); pbg.setColor(0x29808080);
        plus.setBackground(pbg);
        top.addView(plus, new LinearLayout.LayoutParams(dp(act, 30), dp(act, 30)));

        LinearLayout quick = new LinearLayout(act);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        quick.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        quick.setGravity(Gravity.CENTER_VERTICAL);
        for (final String e : QUICK) {
            TextView t = emojiCell(act, e, 34, 23);
            t.setOnClickListener(v -> { dismiss(); cb.react(e); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(act, 34), 1f);
            quick.addView(t, lp);
        }
        LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(0, -2, 1f);
        ql.setMarginStart(dp(act, 4));
        top.addView(quick, ql);
        bar.addView(top, new LinearLayout.LayoutParams(-1, -2));

        // ---- شبكة كل الإيموجي (مخفية حتى الضغط على +)
        final ScrollView gridScroll = new ScrollView(act);
        gridScroll.setVisibility(View.GONE);
        gridScroll.setVerticalScrollBarEnabled(false);
        final LinearLayout grid = new LinearLayout(act);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        grid.setPadding(0, dp(act, 6), 0, dp(act, 2));
        final java.util.List<View> cells = new java.util.ArrayList<>();
        final int perRow = 6;
        LinearLayout row = null;
        for (int i = 0; i < ALL.length; i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(act);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_HORIZONTAL);
                grid.addView(row, new LinearLayout.LayoutParams(-1, dp(act, 34)));
            }
            final String e = ALL[i];
            TextView t = emojiCell(act, e, 34, 23);
            t.setOnClickListener(v -> { dismiss(); cb.react(e); });
            row.addView(t, new LinearLayout.LayoutParams(dp(act, 34), dp(act, 34)));
            cells.add(t);
        }
        gridScroll.addView(grid, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, dp(act, 170));
        gl.topMargin = dp(act, 5);
        bar.addView(gridScroll, gl);
        View sep = new View(act);
        sep.setBackgroundColor(cardBorder(dark));
        sep.setVisibility(View.GONE);

        final boolean[] open = {false};
        plus.setOnClickListener(v -> {
            open[0] = !open[0];
            plus.animate().rotation(open[0] ? 45f : 0f).setDuration(280).setInterpolator(new OvershootInterpolator(1.2f)).start();
            if (open[0]) {
                gridScroll.setVisibility(View.VISIBLE);
                gridScroll.setAlpha(0f); gridScroll.setTranslationY(-dp(act, 6));
                gridScroll.animate().alpha(1f).translationY(0).setDuration(260).start();
                for (int i = 0; i < cells.size(); i++) {
                    View c = cells.get(i);
                    c.setAlpha(0f); c.setScaleX(.4f); c.setScaleY(.4f); c.setTranslationY(dp(act, 8));
                    c.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0).setStartDelay(60 + i * 16L).setDuration(340)
                            .setInterpolator(new OvershootInterpolator(1.35f)).start();
                }
            } else gridScroll.setVisibility(View.GONE);
            shell.post(() -> {      // keepShellInView
                int[] loc = new int[2]; shell.getLocationInWindow(loc);
                int[] cl = new int[2]; content.getLocationInWindow(cl);
                float bottom = loc[1] - cl[1] + shell.getHeight();
                float over = bottom - (H - dp(act, 8) - insetBottom);
                if (over > 0) shell.animate().translationYBy(-over).setDuration(180).start();
            });
        });

        // ---- بطاقة الأزرار
        final LinearLayout actions = new LinearLayout(act);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setBackground(card(act, dark, 14));
        actions.setPadding(dp(act, 4), dp(act, 4), dp(act, 4), dp(act, 4));

        actions.addView(actionRow(act, dark, R.drawable.ic_mm_reply, "رد على الرسالة", false, () -> { dismiss(); cb.reply(); }));
        if (spec.canCopy) actions.addView(actionRow(act, dark, R.drawable.ic_mm_copy, "نسخ النص", false, () -> { dismiss(); cb.copy(); }));
        if (spec.canDownload) actions.addView(actionRow(act, dark, R.drawable.ic_mm_download, spec.downloadLabel, false, () -> { dismiss(); cb.download(); }));
        if (spec.canEdit) actions.addView(actionRow(act, dark, R.drawable.ic_mm_edit, "تعديل الرسالة", false, () -> { dismiss(); cb.edit(); }));
        if (spec.canPin) actions.addView(actionRow(act, dark, spec.pinned ? R.drawable.ic_mm_unpin : R.drawable.ic_mm_pin,
                spec.pinned ? "إلغاء تثبيت الرسالة" : "تثبيت الرسالة", false, () -> { dismiss(); cb.pin(); }));
        View line = new View(act);
        line.setBackgroundColor(0x38808080);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, Math.max(1, dp(act, 1)));
        ll.topMargin = dp(act, 3);
        actions.addView(line, ll);
        actions.addView(actionRow(act, dark, R.drawable.ic_mm_delete, "حذف الرسالة", true, () -> { dismiss(); cb.delete(); }));

        shell.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, -2);
        al.topMargin = dp(act, 6);
        shell.addView(actions, al);

        // ---- الموضع: فوق الرسالة (gap 10) وإلا تحتها، مع هامش 12 داخل الشاشة
        shell.measure(View.MeasureSpec.makeMeasureSpec(shellW, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int sw = shellW, sh = shell.getMeasuredHeight();
        int gap = dp(act, 10), margin = dp(act, 12);
        float left = hole.centerX() - sw / 2f;
        left = Math.max(margin, Math.min(left, W - sw - margin));
        float minTop = margin + insetTop, maxTop = H - sh - margin - insetBottom;
        float topY = hole.top - sh - gap;
        boolean above = true;
        if (topY < minTop) { topY = Math.min(maxTop, hole.bottom + gap); above = false; }
        topY = Math.max(minTop, Math.min(topY, maxTop));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(sw, -2);
        slp.leftMargin = (int) left; slp.topMargin = (int) topY;
        root.addView(shell, slp);

        // ---- الحركة: دخول الغلاف ثم عناصره بتأخير
        shell.setPivotX(sw / 2f);
        shell.setPivotY(above ? sh : 0);
        shell.setAlpha(0f); shell.setTranslationY(dp(act, 18)); shell.setScaleX(.92f); shell.setScaleY(.92f);
        shell.animate().alpha(1f).translationY(0).scaleX(1f).scaleY(1f).setDuration(380)
                .setInterpolator(new PathInterpolator(.16f, 1f, .3f, 1f)).start();
        bar.setAlpha(0f); bar.setTranslationY(dp(act, 8));
        bar.animate().alpha(1f).translationY(0).setStartDelay(45).setDuration(340).setInterpolator(new PathInterpolator(.16f, 1f, .3f, 1f)).start();
        actions.setAlpha(0f); actions.setTranslationY(dp(act, 8));
        actions.animate().alpha(1f).translationY(0).setStartDelay(75).setDuration(360).setInterpolator(new PathInterpolator(.16f, 1f, .3f, 1f)).start();
    }

    private static TextView emojiCell(Context c, String e, float sizeDp, float sp) {
        TextView t = new TextView(c);
        t.setText(e);
        t.setTextSize(sp);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        GradientDrawable pr = new GradientDrawable(); pr.setShape(GradientDrawable.OVAL); pr.setColor(0x33808080);
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, pr);
        t.setBackground(sl);
        t.setContentDescription(e);
        pressScale(t);
        return t;
    }

    private static View actionRow(Context c, boolean dark, int iconRes, String label, boolean isDanger, final Runnable onClick) {
        int col = isDanger ? danger(dark) : textMain(dark);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(c, 38));
        row.setPadding(dp(c, 10), dp(c, 7), dp(c, 10), dp(c, 7));
        GradientDrawable pr = new GradientDrawable(); pr.setColor(0x21C19D3F); pr.setCornerRadius(dp(c, 9));
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, pr);
        row.setBackground(sl);
        row.setClickable(true);
        row.setOnClickListener(v -> onClick.run());
        ImageView ic = new ImageView(c);
        ic.setImageResource(iconRes);
        ic.setColorFilter(col);
        ic.setAlpha(0.92f);
        row.addView(ic, new LinearLayout.LayoutParams(dp(c, 17), dp(c, 17)));
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextSize(13);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextColor(col);
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
        tl.setMarginStart(dp(c, 10));
        row.addView(t, tl);
        return row;
    }

    // ------------------------------------------------------------------ الحبة العائمة عند التحديد (ضغط مطوّل)
    /** الحبة لا تعترض اللمس: الرسائل تحتها تبقى قابلة للتحديد/التمرير، والإطار الذهبي يرسمه تحديد الفقاعة نفسها. */
    public static void showPill(final Activity act, final View anchor, final boolean dark, final int gold, final int insetTop, final int insetBottom,
                                final String myReaction, final Runnable onMore, final Cb cb) {
        final FrameLayout root = attach(act, dark, false);
        final ViewGroup content = (ViewGroup) root.getParent();

        final LinearLayout pill = new LinearLayout(act);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setClickable(true);
        pill.setPadding(dp(act, 6), dp(act, 4), dp(act, 6), dp(act, 4));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(dark ? 0xFF2B3B4F : 0xFFFFFFFF);          // خلفية أوضح عن خلفية المحادثة
        bg.setCornerRadius(dp(act, 24));
        bg.setStroke(dp(act, 1.5f), dark ? 0x80FFFFFF : 0xFFB9C6D6);   // حدّ واضح بدل الخافت
        pill.setBackground(bg);
        pill.setElevation(0f);                        // ظل يفصلها عن الرسائل خلفها
        for (final String e : QUICK) {
            TextView t = new TextView(act);
            t.setText(e); t.setTextSize(23); t.setGravity(Gravity.CENTER); t.setIncludeFontPadding(false);
            t.setTextColor(dark ? 0xFFFFFFFF : 0xFF17212B);
            if (e.equals(myReaction)) { GradientDrawable on = new GradientDrawable(); on.setShape(GradientDrawable.OVAL); on.setColor(0x80D4AF37); t.setBackground(on); }
            pressScale(t);
            t.setOnClickListener(v -> { dismiss(); cb.react(e); });
            pill.addView(t, new LinearLayout.LayoutParams(dp(act, 34), dp(act, 34)));
        }
        ImageView plus = new ImageView(act);
        plus.setImageResource(R.drawable.ic_mm_plus);
        plus.setColorFilter(dark ? 0xFFFFFFFF : 0xFF17212B);
        plus.setPadding(dp(act, 7), dp(act, 7), dp(act, 7), dp(act, 7));
        GradientDrawable pb = new GradientDrawable(); pb.setShape(GradientDrawable.OVAL); pb.setColor(dark ? 0x66A5B4C8 : 0x4D7F8CA0);
        plus.setBackground(pb);
        plus.setOnClickListener(v -> { dismiss(); onMore.run(); });
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(dp(act, 30), dp(act, 30));
        pl.setMarginStart(dp(act, 2));
        pill.addView(plus, pl);

        final int W = content.getWidth();
        pill.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int w = Math.min(pill.getMeasuredWidth(), W - dp(act, 16)), h = pill.getMeasuredHeight();
        root.addView(pill, new FrameLayout.LayoutParams(w, h));
        pillView = pill;
        placePill(anchor, insetTop, insetBottom);
        pill.setAlpha(0f); pill.setTranslationY(dp(act, 6)); pill.setScaleX(.92f); pill.setScaleY(.92f);
        pill.animate().alpha(1f).translationY(0).scaleX(1f).scaleY(1f).setDuration(160).start();
    }

    /** يعيد وضع الحبة فوق الرسالة (عند التمرير) ويخفيها إن خرجت الرسالة من الشاشة — مثل positionSelEmojiBar في الـ HTML. */
    public static void placePill(View anchor, int insetTop, int insetBottom) {
        final View pill = pillView;
        if (pill == null || overlay == null || anchor == null || !anchor.isAttachedToWindow()) return;
        final ViewGroup content = overlayParent;
        final Context c = pill.getContext();
        final RectF box = anchorRect(anchor, content);
        final int W = content.getWidth(), H = content.getHeight();
        int w = pill.getLayoutParams().width, h = pill.getLayoutParams().height;
        int m = dp(c, 8);
        boolean off = box.bottom < insetTop + dp(c, 56) || box.top > H - insetBottom - dp(c, 60);
        pill.setVisibility(off ? View.INVISIBLE : View.VISIBLE);
        float left = Math.max(m, Math.min(box.centerX() - w / 2f, W - w - m));
        float minTop = m + insetTop;
        float topY = box.top - h - dp(c, 8);
        if (topY < minTop) {
            float below = box.bottom + dp(c, 8);
            topY = (below + h <= H - m - insetBottom) ? below : minTop;
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) pill.getLayoutParams();
        if (lp.leftMargin != (int) left || lp.topMargin != (int) topY) {
            lp.leftMargin = (int) left; lp.topMargin = (int) topY;
            pill.setLayoutParams(lp);
        }
    }

    // ------------------------------------------------------------------ نافذة الحذف (messageDeleteModal)
    public static void showDelete(final Activity act, final boolean dark, final DeleteCb cb) {
        showDelete(act, dark, "حذف الرسالة", "اختر أين تريد حذف هذه الرسالة.", true, cb);
    }

    /** نسخة الحذف المتعدد (openMultiDeleteDialog): العنوان بعدد الرسائل، و«لدى الكل» معطّل إن لم تكن كلها لي. */
    public static void showDelete(final Activity act, final boolean dark, String title, String desc, boolean allowAll, final DeleteCb cb) {
        final FrameLayout root = attach(act, dark);
        root.setBackgroundColor(0x80050C16);
        root.setOnClickListener(v -> dismiss());
        final LinearLayout cardV = new LinearLayout(act);
        cardV.setOrientation(LinearLayout.VERTICAL);
        cardV.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        cardV.setClickable(true);
        cardV.setPadding(dp(act, 18), dp(act, 18), dp(act, 18), dp(act, 14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(dark ? 0xFF171B24 : 0xFFFFFFFF);
        bg.setCornerRadius(dp(act, 18));
        bg.setStroke(dp(act, 1), dark ? 0x59C49B48 : 0xFFDFE5EE);
        cardV.setBackground(bg);

        TextView h = new TextView(act);
        h.setText(title); h.setTextSize(17); h.setTypeface(null, Typeface.BOLD);
        h.setTextColor(dark ? 0xFFC9A45C : 0xFF17212B);
        h.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        cardV.addView(h);
        TextView d = new TextView(act);
        d.setText(desc); d.setTextSize(13);
        d.setTextColor(dark ? 0xFF9CA3AF : 0xFF657286);
        d.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, -2);
        dl.topMargin = dp(act, 6); dl.bottomMargin = dp(act, 12);
        cardV.addView(d, dl);

        cardV.addView(dialogBtn(act, "حذف لدي فقط", dark ? 0xFF222733 : 0xFFF7F9FC, dark ? 0xFFF5F6F8 : 0xFF17212B, dark ? 0x1FFFFFFF : 0xFFE1E7EF, () -> { dismiss(); cb.forMe(); }));
        View allBtn = dialogBtn(act, "حذف لدى الكل", dark ? 0x1FDC4141 : 0xFFFFF1F2, dark ? 0xFFF87171 : 0xFFDC2626, dark ? 0x59F87171 : 0xFFFECACA, () -> { dismiss(); cb.forAll(); });
        if (!allowAll) { allBtn.setAlpha(.45f); allBtn.setOnClickListener(null); allBtn.setClickable(false); }
        cardV.addView(allBtn);
        cardV.addView(dialogBtn(act, "إلغاء", 0, dark ? 0xFF9CA3AF : 0xFF64748B, 0, MsgMenu::dismiss));

        int W = ((ViewGroup) root.getParent()).getWidth();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.min(dp(act, 340), W - dp(act, 40)), -2);
        lp.gravity = Gravity.CENTER;
        root.addView(cardV, lp);
        cardV.setAlpha(0f); cardV.setScaleX(.94f); cardV.setScaleY(.94f);
        cardV.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).start();
    }

    // ------------------------------------------------------------------ نافذة حذف الرسالة/الرسائل بألوان ثيم المحادثة
    /**
     * عنوان + سؤال + مربع «حذف من عند {الاسم} | {الوقت} أيضًا» (checkLabel == null → لا مربع، حذف لدي فقط)
     * ثم زرا «حذف» و«إلغاء» بنفس شكل نافذة حذف المحادثة. الألوان من ثيم المحادثة (Tk.chatPal) أو الذهبي لبابل.
     */
    public static void showMsgDelete(final Activity act, final boolean dark, String title, String question,
                                     String checkLabel, final ChatDelCb cb) {
        buildDelete(act, dark, true, title, question, checkLabel, cb);
    }

    private static int blend(int c, int to, float f) {
        int r = (int) (((c >> 16) & 255) * (1 - f) + ((to >> 16) & 255) * f);
        int g = (int) (((c >> 8) & 255) * (1 - f) + ((to >> 8) & 255) * f);
        int b = (int) ((c & 255) * (1 - f) + (to & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static void buildDelete(final Activity act, final boolean dark, boolean usePal, String title, CharSequence question,
                                    String checkLabel, final ChatDelCb cb) {
        final ChatThemes.Pal pal = usePal ? Tk.chatPal : null;
        final int cardC  = pal != null ? (pal.other | 0xFF000000) : (dark ? 0xFF17212B : 0xFFFFFFFF);
        final int textC  = pal != null ? (pal.textOther | 0xFF000000) : (dark ? 0xFFF1F5F9 : 0xFF16202B);
        final int subC   = pal != null ? (pal.metaOther | 0xFF000000) : (dark ? 0xFF8798AA : 0xFF6B7A8C);
        final int chipC  = pal != null ? ((pal.accent & 0x00FFFFFF) | 0x1F000000) : (dark ? 0xFF1D2A38 : 0xFFE8EDF4);
        final int accC   = pal != null ? (pal.accent | 0xFF000000) : (dark ? 0xFFE4C45B : 0xFFC99A1C);
        final int sepC   = pal != null ? ((pal.accent & 0x00FFFFFF) | 0x59000000) : (dark ? 0x14FFFFFF : 0x1416202B);

        final FrameLayout root = attach(act, dark);
        root.setBackgroundColor(0x85080C12);
        root.setOnClickListener(v -> dismiss());

        final FrameLayout card = new FrameLayout(act);
        card.setClickable(true);
        GradientDrawable cbg = new GradientDrawable();
        cbg.setColor(cardC); cbg.setCornerRadius(dp(act, 22)); cbg.setStroke(dp(act, 1), sepC);
        card.setBackground(cbg);
        card.setClipToOutline(true);
        card.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
        card.setElevation(dp(act, 18));

        final LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(act, 22), dp(act, 22), dp(act, 22), dp(act, 18));
        card.addView(col, new FrameLayout.LayoutParams(-1, -2));

        TextView h = new TextView(act);
        h.setText(title); h.setTextSize(21); h.setTypeface(null, Typeface.BOLD);
        h.setTextColor(textC); h.setGravity(Gravity.RIGHT);
        h.setTextDirection(View.TEXT_DIRECTION_RTL);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.bottomMargin = dp(act, 12);
        col.addView(h, hp);

        TextView q = new TextView(act);
        q.setText(question); q.setTextSize(16); q.setTextColor(textC);
        q.setGravity(Gravity.RIGHT);
        q.setTextDirection(View.TEXT_DIRECTION_RTL);
        q.setLineSpacing(0, 1.25f);
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(-1, -2);
        qp.bottomMargin = dp(act, 18);
        col.addView(q, qp);

        final boolean[] both = {false};
        if (checkLabel != null && checkLabel.length() > 0) {
            final LinearLayout chk = new LinearLayout(act);
            chk.setOrientation(LinearLayout.HORIZONTAL);
            chk.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);          // ترتيب صريح: [النص] [المربع] — المربع في اليمين
            chk.setGravity(Gravity.CENTER_VERTICAL);
            chk.setClickable(true);
            chk.setPadding(0, dp(act, 6), 0, dp(act, 6));
            TextView cl = new TextView(act);
            cl.setText(checkLabel); cl.setTextSize(15.5f); cl.setTextColor(textC);
            cl.setGravity(Gravity.RIGHT);
            cl.setTextDirection(View.TEXT_DIRECTION_RTL);
            LinearLayout.LayoutParams cll = new LinearLayout.LayoutParams(0, -2, 1f);
            cll.setMarginEnd(dp(act, 12));
            chk.addView(cl, cll);
            final FrameLayout box = new FrameLayout(act);
            final ImageView tick = new ImageView(act);
            tick.setImageResource(R.drawable.ic_check_svg);          // علامة ✓ بصيغة SVG (vector)
            tick.setScaleType(ImageView.ScaleType.FIT_CENTER);
            box.addView(tick, new FrameLayout.LayoutParams(dp(act, 18), dp(act, 18), Gravity.CENTER));
            final GradientDrawable boxBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{blend(accC, 0xFFFFFFFF, .28f), blend(accC, 0xFF000000, .22f)});   // ذهبي متدرج
            boxBg.setCornerRadius(dp(act, 7));
            box.setBackground(boxBg);
            final Runnable paintBox = () -> {
                if (both[0]) { boxBg.setColors(new int[]{blend(accC, 0xFFFFFFFF, .28f), blend(accC, 0xFF000000, .22f)}); boxBg.setStroke(0, 0); }
                else { boxBg.setColor(0); boxBg.setStroke(dp(act, 2), subC); }
                tick.setVisibility(both[0] ? View.VISIBLE : View.INVISIBLE);
            };
            paintBox.run();
            chk.addView(box, new LinearLayout.LayoutParams(dp(act, 26), dp(act, 26)));
            chk.setOnClickListener(v -> { both[0] = !both[0]; paintBox.run(); });
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
            cp.bottomMargin = dp(act, 18);
            col.addView(chk, cp);
        }

        // الأزرار: نفس زرّي نافذة حذف المحادثة تماماً (حذف بتدرّج أحمر | إلغاء)
        LinearLayout btns = new LinearLayout(act);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        TextView ok = new TextView(act);
        ok.setText("حذف"); ok.setTextSize(15.5f); ok.setTypeface(null, Typeface.BOLD);
        ok.setTextColor(0xFFFFFFFF); ok.setGravity(Gravity.CENTER); ok.setClickable(true);
        GradientDrawable og = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFFF0605D, 0xFFD94545});
        og.setCornerRadius(dp(act, 16));
        ok.setBackground(og);
        ok.setOnClickListener(v -> { final boolean b = both[0]; dismiss(); cb.onDelete(b); });
        pressScale(ok);
        TextView no = new TextView(act);
        no.setText("إلغاء"); no.setTextSize(15.5f); no.setTypeface(null, Typeface.BOLD);
        no.setTextColor(textC); no.setGravity(Gravity.CENTER); no.setClickable(true);
        GradientDrawable ng = new GradientDrawable();
        ng.setColor(chipC); ng.setCornerRadius(dp(act, 16));
        no.setBackground(ng);
        no.setOnClickListener(v -> dismiss());
        pressScale(no);
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, dp(act, 48), 1f);
        LinearLayout.LayoutParams b2 = new LinearLayout.LayoutParams(0, dp(act, 48), 1f);
        b2.setMarginStart(dp(act, 10));
        btns.addView(ok, b1); btns.addView(no, b2);
        col.addView(btns, new LinearLayout.LayoutParams(-1, -2));

        int W = ((ViewGroup) root.getParent()).getWidth();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.min(dp(act, 340), W - dp(act, 44)), -2);
        lp.gravity = Gravity.CENTER;
        root.addView(card, lp);
        card.setAlpha(0f); card.setScaleX(.92f); card.setScaleY(.92f);
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start();
    }

    // ------------------------------------------------------------------ نافذة حذف المحادثة (openDeleteChatsDialog / .cl-dlg.cl-del في الـ HTML)
    public interface ChatDelCb { void onDelete(boolean both); }

    /**
     * أيقونة سلة حمراء + عنوان + نص (الاسم بالذهبي) + مربع «حذف أيضاً عند الطرف الآخر» + زرا حذف / إلغاء.
     * pre + name + post يكوّنان النص؛ name قد يكون فارغاً عند حذف عدة محادثات.
     */
    public static void showChatDelete(final Activity act, final boolean dark, String title, String pre, String name,
                                      String post, String checkLabel, final ChatDelCb cb) {
        // نفس نافذة حذف الرسالة بالضبط (قالب واحد)؛ الاسم بالذهبي، وألوان عامة لا ألوان ثيم المحادثة
        final int goldC = dark ? 0xFFE4C45B : 0xFFC99A1C;
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        sb.append(pre == null ? "" : pre);
        if (name != null && name.length() > 0) {
            int a = sb.length();
            sb.append(name);
            sb.setSpan(new android.text.style.ForegroundColorSpan(goldC), a, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), a, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        sb.append(post == null ? "" : post);
        buildDelete(act, dark, false, title, sb, checkLabel, cb);
    }

    private static View dialogBtn(Context c, String label, int fill, int fg, int stroke, final Runnable r) {
        TextView t = new TextView(c);
        t.setText(label); t.setTextSize(14); t.setTypeface(null, Typeface.BOLD);
        t.setTextColor(fg); t.setGravity(Gravity.CENTER);
        t.setClickable(true);
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill); g.setCornerRadius(dp(c, 12));
        if (stroke != 0) g.setStroke(dp(c, 1), stroke);
        t.setBackground(g);
        t.setOnClickListener(v -> r.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(c, 46));
        lp.topMargin = dp(c, 8);
        t.setLayoutParams(lp);
        return t;
    }
}
