package com.babylon.chat.iq;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * عارض الصورة/الفيديو داخل المحادثة — منقول من openImageViewer / openVideoViewer / createMediaViewerToolbar في الـ HTML:
 * خلفية سوداء، شريط علوي (إغلاق + ⋮ فيه «تنزيل الصورة/الفيديو» و«حذف» لرسائلي)، النقر يُظهر/يُخفي الأدوات،
 * الصورة: تكبير بإصبعين حتى 5× وسحب عند التكبير، الفيديو: تشغيل تلقائي + زر مركزي + شريط تقدّم + الوقت الحالي/الكلي.
 */
final class MediaViewer {

    interface Cb { void download(); void delete(); }

    private static MediaViewer cur;
    static boolean closeIfOpen() { MediaViewer c = cur; if (c == null) return false; c.close(); return true; }

    static void showImage(Activity act, String src, boolean mine, boolean canDelete, Cb cb) {
        if (cur != null) cur.close();
        MediaViewer v = new MediaViewer(act, mine && canDelete, cb);
        v.build(false, src, null);
    }

    static void showVideo(Activity act, String url, String poster, boolean mine, boolean canDelete, Cb cb) {
        if (cur != null) cur.close();
        MediaViewer v = new MediaViewer(act, mine && canDelete, cb);
        v.build(true, url, poster);
    }

    private static final int GOLD = 0xFFD4AF37;

    private final Activity act;
    private final boolean canDelete;
    private final Cb cb;
    private final float dens, bs;
    private final Handler uiH = new Handler(Looper.getMainLooper());
    private Dialog dlg;
    private LinearLayout bar;
    private View ui;            // عناصر تحكم الفيديو
    private boolean hidden;
    private LinearLayout menu;

    // فيديو
    private MediaPlayer mp;
    private boolean ready, playing, userSeeking;
    private Surface surf;
    private TextureView tex;
    private ImageView centerBtn;
    private ProgressBar spin;
    private SeekBar seek;
    private TextView curTv, durTv;
    private int vw, vh;
    private FrameLayout stage;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (mp == null || !ready) return;
            try {
                if (!userSeeking) {
                    int d = Math.max(1, mp.getDuration()), p = mp.getCurrentPosition();
                    seek.setProgress((int) (1000L * p / d));
                    curTv.setText(fmt(p / 1000));
                }
            } catch (Throwable ignored) { }
            if (playing) uiH.postDelayed(this, 200);
        }
    };

    private MediaViewer(Activity act, boolean canDelete, Cb cb) {
        this.act = act; this.canDelete = canDelete; this.cb = cb;
        dens = act.getResources().getDisplayMetrics().density;
        float w = Math.min(act.getResources().getDisplayMetrics().widthPixels, act.getResources().getDisplayMetrics().heightPixels) / dens;
        bs = Math.max(0.9f, Math.min(1.25f, (float) Math.sqrt(w / 360f)));
    }

    private int dp(float v) { return Math.round(v * dens); }
    private int px(float v) { return Math.round(v * dens * bs); }
    private String fmt(int s) { return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60); }

    private void build(boolean video, String src, String poster) {
        cur = this;
        dlg = new Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dlg.setCancelable(false);
        dlg.setOnKeyListener((d, code, ev) -> {
            if (code == android.view.KeyEvent.KEYCODE_BACK) { if (ev.getAction() == android.view.KeyEvent.ACTION_UP) close(); return true; }
            return false;
        });
        FrameLayout root = new FrameLayout(act);
        root.setBackgroundColor(0xFF000000);
        stage = new FrameLayout(act);
        root.addView(stage, new FrameLayout.LayoutParams(-1, -1));

        // شريط علوي: تدرج أسود، إغلاق (يسار) و⋮ (يمين)
        bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        bar.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xC7000000, 0x00000000}));
        bar.setPadding(dp(14), dp(10), dp(14), dp(10));
        ImageView close = new ImageView(act);
        close.setImageResource(R.drawable.ic_mc_close);
        close.setColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
        close.setScaleType(ImageView.ScaleType.CENTER);
        close.setOnClickListener(v -> close());
        bar.addView(close, new LinearLayout.LayoutParams(px(42), px(42)));
        View sp = new View(act);
        bar.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));
        TextView dots = new TextView(act);
        dots.setText("\u22EE"); dots.setTextColor(0xFFFFFFFF); dots.setGravity(Gravity.CENTER);
        dots.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 26 * dens);
        dots.setOnClickListener(v -> toggleMenu());
        bar.addView(dots, new LinearLayout.LayoutParams(px(42), px(42)));
        root.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // قائمة ⋮
        menu = new LinearLayout(act);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        menu.setPadding(dp(6), dp(6), dp(6), dp(6));
        GradientDrawable mg = new GradientDrawable();
        mg.setColor(0xFF17191F); mg.setCornerRadius(dp(12)); mg.setStroke(dp(1), 0x21FFFFFF);
        menu.setBackground(mg);
        menu.setElevation(dp(12));
        menu.setVisibility(View.GONE);
        menu.addView(option("تنزيل " + (video ? "الفيديو" : "الصورة"), 0xFFFFFFFF, () -> { menu.setVisibility(View.GONE); if (cb != null) cb.download(); }));
        if (canDelete) menu.addView(option("حذف", 0xFFFF7373, () -> { menu.setVisibility(View.GONE); close(); if (cb != null) cb.delete(); }));
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(dp(170), -2, Gravity.TOP | Gravity.RIGHT);
        mlp.topMargin = dp(10) + px(42) + dp(6); mlp.rightMargin = dp(14);
        root.addView(menu, mlp);

        if (video) buildVideo(src, poster); else buildImage(src);

        dlg.setContentView(root);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0xFF000000));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
            w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            w.setStatusBarColor(0xFF000000);
            w.setNavigationBarColor(0xFF000000);   // شريطا النظام أسودان أثناء العرض (babylonMediaBars)
        }
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            bar.setPadding(dp(14), Math.max(dp(10), ins.getSystemWindowInsetTop()), dp(14), dp(10));
            return ins;
        });
        dlg.show();
    }

    private TextView option(String label, int color, final Runnable r) {
        TextView t = new TextView(act);
        t.setText(label); t.setTextColor(color); t.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 14 * dens * bs);
        t.setPadding(dp(12), dp(11), dp(12), dp(11));
        t.setOnClickListener(v -> r.run());
        return t;
    }

    private void toggleMenu() { menu.setVisibility(menu.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); }

    private void toggleUi() {
        menu.setVisibility(View.GONE);
        hidden = !hidden;
        float a = hidden ? 0f : 1f;
        bar.animate().alpha(a).translationY(hidden ? -dp(20) : 0).setDuration(220).start();
        if (ui != null) ui.animate().alpha(a).setDuration(220).start();
        bar.setEnabled(!hidden);
    }

    void close() {
        if (cur == this) cur = null;
        uiH.removeCallbacks(tick);
        try { if (mp != null) mp.release(); } catch (Throwable ignored) { }
        mp = null; ready = false; playing = false;
        try { if (surf != null) surf.release(); } catch (Throwable ignored) { }
        surf = null;
        Dialog d = dlg; dlg = null;
        try { if (d != null) d.dismiss(); } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ صورة
    private void buildImage(final String src) {
        final ZoomImage zi = new ZoomImage(act);
        stage.addView(zi, new FrameLayout.LayoutParams(-1, -1));
        new Thread(() -> {
            Bitmap b = null;
            try {
                byte[] bytes;
                if (src.startsWith("http")) {
                    HttpURLConnection c = (HttpURLConnection) new URL(src).openConnection();
                    c.setConnectTimeout(15000); c.setReadTimeout(25000);
                    InputStream is = c.getInputStream();
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384]; int n;
                    while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
                    is.close(); c.disconnect();
                    bytes = bo.toByteArray();
                } else if (src.startsWith("data:") && src.indexOf(',') > 0) bytes = Base64.decode(src.substring(src.indexOf(',') + 1), Base64.DEFAULT);
                else bytes = Base64.decode(src, Base64.DEFAULT);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
                int s = 1;
                while (Math.max(o.outWidth, o.outHeight) / s > 4096) s *= 2;
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = s;
                b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o2);
            } catch (Throwable ignored) { }
            final Bitmap fb = b;
            uiH.post(() -> { if (dlg == null) return; if (fb == null) { AppToast.show(act, "تعذر عرض الصورة"); close(); } else zi.setBmp(fb); });
        }).start();
    }

    /** صورة بتكبير حتى 5× (قرصة) وسحب عند التكبير؛ نقرة واحدة تبدّل الأدوات. */
    private final class ZoomImage extends View {
        private Bitmap b;
        private final Matrix m = new Matrix();
        private final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private float fit = 1f, scale = 1f, tx, ty;
        private final ScaleGestureDetector sg;
        private final GestureDetector gd;

        ZoomImage(android.content.Context c) {
            super(c);
            sg = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector d) {
                    scale = Math.max(1f, Math.min(5f, scale * d.getScaleFactor()));
                    if (scale == 1f) { tx = 0; ty = 0; }
                    clampPan(); invalidate();
                    return true;
                }
            });
            gd = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onSingleTapConfirmed(MotionEvent e) { toggleUi(); return true; }
                @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                    if (scale > 1f && !sg.isInProgress()) { tx -= dx; ty -= dy; clampPan(); invalidate(); }
                    return true;
                }
                @Override public boolean onDown(MotionEvent e) { return true; }
            });
        }

        void setBmp(Bitmap bb) { b = bb; recalc(); invalidate(); }

        private void recalc() {
            if (b == null || getWidth() == 0) return;
            fit = Math.min(getWidth() / (float) b.getWidth(), getHeight() / (float) b.getHeight());
        }

        private void clampPan() {
            if (b == null) return;
            float w = b.getWidth() * fit * scale, h = b.getHeight() * fit * scale;
            float mx = Math.max(0, (w - getWidth()) / 2f), my = Math.max(0, (h - getHeight()) / 2f);
            tx = Math.max(-mx, Math.min(mx, tx)); ty = Math.max(-my, Math.min(my, ty));
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) { recalc(); }

        @Override protected void onDraw(Canvas cv) {
            if (b == null) return;
            float s = fit * scale;
            m.reset();
            m.postScale(s, s);
            m.postTranslate((getWidth() - b.getWidth() * s) / 2f + tx, (getHeight() - b.getHeight() * s) / 2f + ty);
            cv.drawBitmap(b, m, p);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            sg.onTouchEvent(e);
            gd.onTouchEvent(e);
            return true;
        }
    }

    // ------------------------------------------------------------------ فيديو
    private void buildVideo(String url, String poster) {
        final FrameLayout box = new FrameLayout(act);
        stage.addView(box, new FrameLayout.LayoutParams(-1, -1));
        tex = new TextureView(act);
        box.addView(tex, new FrameLayout.LayoutParams(dp(2), dp(2), Gravity.CENTER));
        tex.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                surf = new Surface(st);
                try { if (mp != null) mp.setSurface(surf); } catch (Throwable ignored) { }
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { try { if (mp != null) mp.setSurface(null); } catch (Throwable ignored) { } return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
        });
        box.setOnClickListener(v -> toggleUi());

        FrameLayout uiF = new FrameLayout(act);
        ui = uiF;
        box.addView(uiF, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout center = new FrameLayout(act);
        GradientDrawable cg = new GradientDrawable();
        cg.setShape(GradientDrawable.OVAL); cg.setColor(0x3812161C);
        center.setBackground(cg);
        centerBtn = new ImageView(act);
        centerBtn.setImageResource(R.drawable.ic_mc_pause);
        centerBtn.setColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
        center.addView(centerBtn, new FrameLayout.LayoutParams(px(34), px(34), Gravity.CENTER));
        spin = new ProgressBar(act);
        spin.setIndeterminateTintList(ColorStateList.valueOf(GOLD));
        center.addView(spin, new FrameLayout.LayoutParams(px(30), px(30), Gravity.CENTER));
        centerBtn.setVisibility(View.GONE);
        center.setOnClickListener(v -> togglePlay());
        uiF.addView(center, new FrameLayout.LayoutParams(px(72), px(72), Gravity.CENTER));

        LinearLayout b = new LinearLayout(act);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        b.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xB8000000, 0x00000000}));
        b.setPadding(dp(16), dp(30), dp(16), dp(14));
        curTv = timeTv(); durTv = timeTv();
        seek = new SeekBar(act);
        seek.setMax(1000);
        seek.setProgressTintList(ColorStateList.valueOf(GOLD));
        seek.setThumbTintList(ColorStateList.valueOf(0xFFFFFFFF));
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(0x4DFFFFFF));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser && mp != null && ready) curTv.setText(fmt((int) (mp.getDuration() * (long) p / 1000 / 1000)));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                userSeeking = false;
                try { if (mp != null && ready) mp.seekTo((int) (mp.getDuration() * (long) s.getProgress() / 1000)); } catch (Throwable ignored) { }
            }
        });
        b.addView(curTv);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, px(28), 1f);
        sl.setMarginStart(dp(12)); sl.setMarginEnd(dp(12));
        b.addView(seek, sl);
        b.addView(durTv);
        uiF.addView(b, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        b.setOnClickListener(v -> { });

        try {
            mp = new MediaPlayer();
            mp.setDataSource(url);
            if (surf != null) mp.setSurface(surf);
            mp.setOnPreparedListener(m -> {
                ready = true; vw = m.getVideoWidth(); vh = m.getVideoHeight();
                fitVideo(box);
                durTv.setText(fmt(Math.max(0, m.getDuration() / 1000)));
                spin.setVisibility(View.GONE); centerBtn.setVisibility(View.VISIBLE);
                try { m.start(); playing = true; centerBtn.setImageResource(R.drawable.ic_mc_pause); uiH.post(tick); } catch (Throwable ignored) { }
            });
            mp.setOnCompletionListener(m -> { playing = false; centerBtn.setImageResource(R.drawable.ic_mc_play); seek.setProgress(1000); });
            mp.setOnErrorListener((m, what, extra) -> { AppToast.show(act, "تعذر تشغيل الفيديو"); spin.setVisibility(View.GONE); return true; });
            mp.prepareAsync();
        } catch (Throwable t) {
            AppToast.show(act, "تعذر تشغيل الفيديو");
        }
    }

    private TextView timeTv() {
        TextView t = new TextView(act);
        t.setText("0:00"); t.setTextColor(0xFFFFFFFF); t.setGravity(Gravity.CENTER);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 13 * dens);
        t.setMinWidth(px(42));
        t.setShadowLayer(dp(3), 0, dp(1), 0x99000000);
        return t;
    }

    private void fitVideo(FrameLayout box) {
        if (vw <= 0 || vh <= 0) return;
        int W = box.getWidth(), H = box.getHeight();
        if (W <= 0 || H <= 0) return;
        float k = Math.min(W / (float) vw, H / (float) vh);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(vw * k), Math.round(vh * k), Gravity.CENTER);
        tex.setLayoutParams(lp);
    }

    private void togglePlay() {
        if (mp == null || !ready) return;
        try {
            if (playing) { mp.pause(); playing = false; centerBtn.setImageResource(R.drawable.ic_mc_play); }
            else { mp.start(); playing = true; centerBtn.setImageResource(R.drawable.ic_mc_pause); uiH.removeCallbacks(tick); uiH.post(tick); }
        } catch (Throwable ignored) { }
    }
}
