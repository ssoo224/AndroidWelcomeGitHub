package com.babylon.chat.iq;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * عارض الصورة بكامل الشاشة (مثل معرض الصور): خلفية سوداء، شريط علوي (رجوع + الاسم + السطر الثاني + ⋮)،
 * تقريب بإصبعين + نقر مزدوج + تحريك، تمرير أفقي بين الصور إن وُجدت أكثر من صورة،
 * وشريط مصغّرات في الأسفل. نقرة واحدة تُخفي/تُظهر الأشرطة، وزر الرجوع يُغلقه.
 */
public final class PhotoViewer {

    private final Activity act;
    private final List<Bitmap> photos;
    private final float d;
    private FrameLayout root;
    private ViewPager pager;
    private LinearLayout strip;
    private HorizontalScrollView stripScroll;
    private View header;
    private boolean barsShown = true;
    private boolean closing = false;
    private int current = 0;
    private int prevNavColor, prevUiFlags;
    private boolean navSaved = false;
    private static PhotoViewer open;   // العارض المفتوح حالياً (لزر الرجوع في النشاط)

    /** يُغلق العارض إن كان مفتوحاً؛ true إذا أغلقه (تستعمله onBackPressed في النشاط). */
    public static boolean closeIfOpen() {
        PhotoViewer o = open;
        if (o == null || o.root == null || o.root.getParent() == null || o.closing) return false;
        o.close();
        return true;
    }

    private PhotoViewer(Activity act, List<Bitmap> photos) {
        this.act = act;
        this.photos = photos;
        this.d = act.getResources().getDisplayMetrics().density;
    }

    /** يفتح العارض فوق الشاشة الحالية. title = الاسم، subtitle = السطر الثاني (مثلاً الحالة). */
    public static void show(Activity act, List<Bitmap> photos, CharSequence title, CharSequence subtitle, int startIndex) {
        if (act == null || photos == null || photos.isEmpty()) return;
        new PhotoViewer(act, new ArrayList<>(photos)).build(title, subtitle, startIndex);
    }

    private int dp(float v) { return Math.round(v * d); }

    private int barH(String name) {
        int id = act.getResources().getIdentifier(name, "dimen", "android");
        return id > 0 ? act.getResources().getDimensionPixelSize(id) : 0;
    }

    private void build(CharSequence title, CharSequence subtitle, int startIndex) {
        ViewGroup content = act.findViewById(android.R.id.content);
        if (content == null) return;

        root = new FrameLayout(act);
        root.setBackgroundColor(Color.BLACK);
        root.setClickable(true);                       // يمنع وصول اللمس للصفحة التي خلفه
        root.setFocusableInTouchMode(true);
        root.setAlpha(0f);

        // ---------------- الصور
        pager = new ViewPager(act);
        pager.setOffscreenPageLimit(1);
        pager.setPageMargin(dp(12));
        pager.setAdapter(new PagerAdapter() {
            @Override public int getCount() { return photos.size(); }
            @Override public boolean isViewFromObject(View v, Object o) { return v == o; }
            @Override public Object instantiateItem(ViewGroup c, int pos) {
                ZoomImageView iv = new ZoomImageView(act);
                iv.setImageBitmap(photos.get(pos));
                iv.onTap = (x, w, zoomed) -> {
                    // نقرة على الثلث الأيمن ← التالية، الثلث الأيسر ← السابقة، الوسط ← إظهار/إخفاء الأشرطة
                    if (!zoomed && photos.size() > 1 && x > w * 0.72f) { if (current < photos.size() - 1) pager.setCurrentItem(current + 1, true); }
                    else if (!zoomed && photos.size() > 1 && x < w * 0.28f) { if (current > 0) pager.setCurrentItem(current - 1, true); }
                    else toggleBars();
                };
                c.addView(iv, new ViewGroup.LayoutParams(-1, -1));
                return iv;
            }
            @Override public void destroyItem(ViewGroup c, int pos, Object o) { c.removeView((View) o); }
        });
        pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override public void onPageSelected(int pos) { current = pos; refreshStrip(true); }
        });
        root.addView(pager, new FrameLayout.LayoutParams(-1, -1));

        // ---------------- الشريط العلوي (LTR: السهم يسار، ⋮ يمين)
        int top = barH("status_bar_height");
        int bottom = barH("navigation_bar_height");

        LinearLayout h = new LinearLayout(act);
        h.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(dp(4), top + dp(6), dp(4), dp(10));
        h.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xB3000000, 0x00000000}));

        ImageView back = new ImageView(act);
        back.setImageResource(R.drawable.ic_pv_back);   // سهم الرجوع القياسي (vector)
        back.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        back.setPadding(dp(14), dp(14), dp(14), dp(14));
        back.setClickable(true);
        back.setOnClickListener(v -> close());
        h.addView(back, new LinearLayout.LayoutParams(dp(52), dp(52)));

        LinearLayout titles = new LinearLayout(act);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        TextView t1 = new TextView(act);
        t1.setText(title);
        t1.setTextColor(Color.WHITE);
        t1.setTextSize(20);
        t1.setTypeface(null, android.graphics.Typeface.BOLD);
        t1.setSingleLine(true);
        t1.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t1.setTextDirection(View.TEXT_DIRECTION_LTR);
        titles.addView(t1, new LinearLayout.LayoutParams(-2, -2));
        if (subtitle != null && subtitle.length() > 0) {
            TextView t2 = new TextView(act);
            t2.setText(subtitle);
            t2.setTextColor(0xFFE0E0E0);
            t2.setTextSize(13);
            t2.setSingleLine(true);
            t2.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t2.setTextDirection(View.TEXT_DIRECTION_LTR);
            titles.addView(t2, new LinearLayout.LayoutParams(-2, -2));
        }
        h.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));

        final IconView more = new IconView(act, false);
        more.setOnClickListener(v -> showMenu(more));
        h.addView(more, new LinearLayout.LayoutParams(dp(52), dp(52)));
        header = h;
        root.addView(h, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // ---------------- شريط المصغّرات
        stripScroll = new HorizontalScrollView(act);
        stripScroll.setHorizontalScrollBarEnabled(false);
        stripScroll.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        stripScroll.setPadding(dp(8), dp(8), dp(8), bottom + dp(10));
        stripScroll.setClipToPadding(false);
        strip = new LinearLayout(act);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        stripScroll.addView(strip, new FrameLayout.LayoutParams(-2, -2));
        stripScroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xB3000000, 0x00000000}));
        for (int i = 0; i < photos.size(); i++) {
            final int idx = i;
            ImageView th = new ImageView(act);
            th.setScaleType(ImageView.ScaleType.CENTER_CROP);
            th.setImageBitmap(photos.get(i));
            th.setOnClickListener(v -> pager.setCurrentItem(idx, true));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(54));
            lp.rightMargin = dp(3);
            strip.addView(th, lp);
        }
        root.addView(stripScroll, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        if (photos.size() <= 1) stripScroll.setVisibility(View.GONE);   // صورة واحدة: لا حاجة لشريط المصغّرات

        // زر الرجوع في النظام
        root.setOnKeyListener((v, code, ev) -> {
            if (code == KeyEvent.KEYCODE_BACK && ev.getAction() == KeyEvent.ACTION_UP) { close(); return true; }
            return code == KeyEvent.KEYCODE_BACK;
        });

        content.addView(root, new FrameLayout.LayoutParams(-1, -1));
        // شريط الإيماءات/التنقل بنفس لون الداخل (أسود) أثناء العرض، ويُعاد كما كان عند الإغلاق
        try {
            android.view.Window w = act.getWindow();
            prevNavColor = w.getNavigationBarColor();
            prevUiFlags = w.getDecorView().getSystemUiVisibility();
            navSaved = true;
            w.setNavigationBarColor(Color.BLACK);
            if (Build.VERSION.SDK_INT >= 26) w.getDecorView().setSystemUiVisibility(prevUiFlags & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        } catch (Throwable ignored) { }
        open = this;
        root.requestFocus();

        current = Math.max(0, Math.min(startIndex, photos.size() - 1));
        pager.setCurrentItem(current, false);
        refreshStrip(false);

        // ظهور سريع: تلاشٍ + تكبير بسيط
        pager.setScaleX(0.94f);
        pager.setScaleY(0.94f);
        root.animate().alpha(1f).setDuration(160).start();
        pager.animate().scaleX(1f).scaleY(1f).setDuration(180).start();
    }

    private void refreshStrip(boolean animate) {
        for (int i = 0; i < strip.getChildCount(); i++) {
            View th = strip.getChildAt(i);
            boolean sel = i == current;
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) th.getLayoutParams();
            lp.width = dp(sel ? 66 : 40);
            lp.leftMargin = sel ? dp(6) : 0;
            lp.rightMargin = sel ? dp(9) : dp(3);
            th.setLayoutParams(lp);
            th.setAlpha(sel ? 1f : 0.75f);
        }
        final View sel = strip.getChildAt(current);
        if (sel != null) stripScroll.post(() -> {
            int x = sel.getLeft() - (stripScroll.getWidth() - sel.getWidth()) / 2;
            if (animate) stripScroll.smoothScrollTo(Math.max(0, x), 0); else stripScroll.scrollTo(Math.max(0, x), 0);
        });
    }

    private void toggleBars() {
        barsShown = !barsShown;
        float a = barsShown ? 1f : 0f;
        header.animate().alpha(a).setDuration(150).start();
        stripScroll.animate().alpha(a).setDuration(150).start();
        header.setClickable(barsShown);
    }

    private void showMenu(View anchor) {
        TextView item = new TextView(act);
        item.setText("حفظ في المعرض");
        item.setTextColor(0xFF1B1B1B);
        item.setTextSize(15);
        item.setPadding(dp(22), dp(14), dp(22), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(12));
        item.setBackground(bg);
        item.setElevation(dp(6));
        final PopupWindow pw = new PopupWindow(item, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pw.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        pw.setElevation(dp(8));
        item.setOnClickListener(v -> { pw.dismiss(); saveCurrent(); });
        pw.showAsDropDown(anchor, -dp(120), -dp(6));
    }

    private void saveCurrent() { saveBitmap(act, photos.get(current)); }

    /** يحفظ الصورة في معرض الجهاز (Pictures/Babylon) — أندرويد 10 فأعلى. */
    public static void saveBitmap(Activity act, Bitmap b) {
        try {
            if (b == null) return;
            if (Build.VERSION.SDK_INT < 29) { Toast.makeText(act, "الحفظ غير مدعوم على هذا الإصدار", Toast.LENGTH_SHORT).show(); return; }
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, "Babylon_" + System.currentTimeMillis() + ".jpg");
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            cv.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Babylon");
            Uri u = act.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (u == null) throw new RuntimeException("no uri");
            try (OutputStream os = act.getContentResolver().openOutputStream(u)) {
                b.compress(Bitmap.CompressFormat.JPEG, 95, os);
            }
            Toast.makeText(act, "تم حفظ الصورة", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(act, "تعذّر حفظ الصورة", Toast.LENGTH_SHORT).show();
        }
    }

    private void restoreNavBar() {
        if (!navSaved) return;
        navSaved = false;
        try {
            android.view.Window w = act.getWindow();
            w.setNavigationBarColor(prevNavColor);
            if (Build.VERSION.SDK_INT >= 26) w.getDecorView().setSystemUiVisibility(prevUiFlags);
        } catch (Throwable ignored) { }
    }

    private void close() {
        if (closing || root == null) return;
        closing = true;
        pager.animate().scaleX(0.94f).scaleY(0.94f).setDuration(140).start();
        root.animate().alpha(0f).setDuration(140).withEndAction(() -> {
            try { ((ViewGroup) root.getParent()).removeView(root); } catch (Throwable ignored) { }
            if (open == this) open = null;
            restoreNavBar();
        }).start();
    }

    // =====================================================================================
    /** أيقونة السهم (←) أو النقاط الثلاث (⋮) بيضاء، تُرسم بالكانفس. */
    private static final class IconView extends View {
        private final boolean arrow;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        IconView(Context c, boolean arrow) {
            super(c);
            this.arrow = arrow;
            p.setColor(Color.WHITE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStyle(Paint.Style.STROKE);
            setClickable(true);
            setBackground(null);
        }

        @Override protected void onDraw(Canvas c) {
            float d = getResources().getDisplayMetrics().density;
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            if (arrow) {
                p.setStrokeWidth(2.2f * d);
                float L = 11f * d;
                c.drawLine(cx - L, cy, cx + L, cy, p);
                c.drawLine(cx - L, cy, cx - L + 7f * d, cy - 7f * d, p);
                c.drawLine(cx - L, cy, cx - L + 7f * d, cy + 7f * d, p);
            } else {
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy - 7f * d, 2.3f * d, p);
                c.drawCircle(cx, cy, 2.3f * d, p);
                c.drawCircle(cx, cy + 7f * d, 2.3f * d, p);
                p.setStyle(Paint.Style.STROKE);
            }
        }
    }

    // =====================================================================================
    /** ImageView بتقريب بإصبعين + تحريك + نقر مزدوج؛ لا يسرق السحب الأفقي من الـ ViewPager إلا وهو مكبّر. */
    private static final class ZoomImageView extends ImageView {
        interface TapCb { void tap(float x, int width, boolean zoomed); }
        TapCb onTap;
        private final Matrix m = new Matrix();
        private final float[] v = new float[9];
        private float base = 1f;
        private final ScaleGestureDetector sgd;
        private final GestureDetector gd;
        private android.animation.ValueAnimator zoomAnim;

        ZoomImageView(Context c) {
            super(c);
            setScaleType(ScaleType.MATRIX);
            sgd = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector det) {
                    float cur = scale();
                    float f = det.getScaleFactor();
                    float target = Math.max(base * 0.9f, Math.min(base * 6f, cur * f));
                    f = target / cur;
                    m.postScale(f, f, det.getFocusX(), det.getFocusY());
                    fix();
                    setImageMatrix(m);
                    return true;
                }
                @Override public void onScaleEnd(ScaleGestureDetector det) {
                    if (scale() < base) animateTo(base, getWidth() / 2f, getHeight() / 2f);
                }
            });
            gd = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent e) { return true; }
                @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                    if (scale() > base * 1.01f && !sgd.isInProgress()) {
                        m.postTranslate(-dx, -dy);
                        fix();
                        setImageMatrix(m);
                    }
                    return true;
                }
                @Override public boolean onDoubleTap(MotionEvent e) {
                    animateTo(scale() > base * 1.05f ? base : base * 2.6f, e.getX(), e.getY());
                    return true;
                }
                @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                    if (onTap != null) onTap.tap(e.getX(), getWidth(), scale() > base * 1.05f);
                    return true;
                }
            });
        }

        private float scale() { m.getValues(v); return v[Matrix.MSCALE_X]; }

        @Override public void setImageBitmap(Bitmap bm) {
            super.setImageBitmap(bm);
            fit();
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            fit();
        }

        private void fit() {
            if (getDrawable() == null || getWidth() == 0 || getHeight() == 0) return;
            float dw = getDrawable().getIntrinsicWidth(), dh = getDrawable().getIntrinsicHeight();
            if (dw <= 0 || dh <= 0) return;
            base = Math.min(getWidth() / dw, getHeight() / dh);
            m.reset();
            m.postScale(base, base);
            m.postTranslate((getWidth() - dw * base) / 2f, (getHeight() - dh * base) / 2f);
            setImageMatrix(m);
        }

        /** يبقي الصورة داخل الحدود (أو متمركزة إن كانت أصغر من الشاشة). */
        private void fix() {
            if (getDrawable() == null) return;
            RectF r = new RectF(0, 0, getDrawable().getIntrinsicWidth(), getDrawable().getIntrinsicHeight());
            m.mapRect(r);
            float dx = 0, dy = 0;
            if (r.width() <= getWidth()) dx = (getWidth() - r.width()) / 2f - r.left;
            else if (r.left > 0) dx = -r.left;
            else if (r.right < getWidth()) dx = getWidth() - r.right;
            if (r.height() <= getHeight()) dy = (getHeight() - r.height()) / 2f - r.top;
            else if (r.top > 0) dy = -r.top;
            else if (r.bottom < getHeight()) dy = getHeight() - r.bottom;
            m.postTranslate(dx, dy);
        }

        private void animateTo(final float target, final float fx, final float fy) {
            if (zoomAnim != null) zoomAnim.cancel();
            final float start = scale();
            zoomAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
            zoomAnim.setDuration(200);
            zoomAnim.addUpdateListener(a -> {
                float s = start + (target - start) * (Float) a.getAnimatedValue();
                float f = s / scale();
                m.postScale(f, f, fx, fy);
                fix();
                setImageMatrix(m);
            });
            zoomAnim.start();
        }

        @Override public boolean canScrollHorizontally(int dir) {
            if (scale() <= base * 1.01f || getDrawable() == null) return false;
            RectF r = new RectF(0, 0, getDrawable().getIntrinsicWidth(), getDrawable().getIntrinsicHeight());
            m.mapRect(r);
            return dir < 0 ? r.left < -1f : r.right > getWidth() + 1f;
        }

        @Override public boolean onTouchEvent(MotionEvent ev) {
            sgd.onTouchEvent(ev);
            gd.onTouchEvent(ev);
            // أثناء التقريب بإصبعين فقط نمنع الـ ViewPager؛ وهو مكبّر يتولّى canScrollHorizontally تسليم السحب للـ ViewPager عند حافة الصورة
            if (ev.getPointerCount() > 1) {
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            }
            return true;
        }
    }
}
