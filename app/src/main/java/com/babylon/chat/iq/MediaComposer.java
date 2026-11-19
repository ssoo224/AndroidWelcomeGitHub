package com.babylon.chat.iq;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * نافذة تجهيز الصورة / الفيديو قبل الإرسال — منقولة حرفياً من سكربت #babylon-media-composer في الـ HTML
 * (openMediaComposer / applyView / setupTrim / renderTrim / refreshResChips / updateNote / enterCrop / exitCrop /
 *  placeBox / onAspClick / onBoxDown / doSend):
 *  - الصورة: قص مكاني (حر / 1:1 / 4:3 / 3:4 / 16:9 / 9:16) + وصف حتى 500 حرف. GIF بلا قص.
 *  - الفيديو: قص زمني بمقبضين + دقة 480/720/1080 + قص مكاني + حجم تقريبي + وصف.
 *  - وضع التعديل (من قائمة الرسالة): الوسائط المرسلة كما هي، يتغير الوصف فقط، العنوان «تعديل الصورة/الفيديو».
 *  - الألوان مطابقة لصفحة تسجيل الدخول (ليلي/نهاري) والأبعاد بمقياس --bb-s.
 */
final class MediaComposer {

    interface Host {
        void onSend(Req r);
        void onEditCaption(String msgId, String caption);
    }

    static final class Req {
        File file;
        String mime = "";
        long size;
        boolean video, gif;
        String caption = "";
        double dur, start, end;
        int natW, natH;
        int[] crop;      // x,y,w,h بالبكسل الأصلي، أو null
        int res;
        File cropped;    // صورة مقصوصة جاهزة (JPEG بجودة .92) أو null
    }

    private static final int[] RES = {480, 720, 1080};
    private static final float[] ASP_V = {0f, 1f, 4f / 3f, 3f / 4f, 16f / 9f, 9f / 16f};
    private static final String[] ASP_L = {"حر", "1:1", "4:3", "3:4", "16:9", "9:16"};
    private static final int G1 = 0xFFE6C566, G2 = 0xFFD3A93B, G3 = 0xFFBC8E24, GOLD = 0xFFC99F2C;

    private static MediaComposer current;

    static boolean isOpen() { return current != null; }

    /** زر الرجوع: في وضع القص يخرج منه، وإلا يغلق النافذة. */
    static boolean handleBack() {
        MediaComposer c = current;
        if (c == null) return false;
        c.onBack();
        return true;
    }

    static void open(Activity act, boolean dark, File file, String mime, Host host) {
        if (current != null) current.close();
        MediaComposer c = new MediaComposer(act, dark, host);
        c.file = file; c.mime = mime == null ? "" : mime;
        c.isVideo = c.mime.startsWith("video/");
        c.isGif = "image/gif".equals(c.mime);
        c.fileSize = file == null ? 0L : file.length();
        c.show();
    }

    static void openEdit(Activity act, boolean dark, String id, boolean video, String url, String poster, String caption, Host host) {
        if (current != null) current.close();
        MediaComposer c = new MediaComposer(act, dark, host);
        c.editId = id; c.editUrl = url; c.editPoster = poster == null ? "" : poster; c.editCaption = caption == null ? "" : caption;
        c.isVideo = video;
        c.show();
    }

    // ------------------------------------------------------------------ الحالة (S في الـ HTML)
    private final Activity act;
    private final boolean dark;
    private final Host host;
    private final float dens, bs;
    private final Handler uiH = new Handler(Looper.getMainLooper());

    private File file;
    private String mime = "";
    private long fileSize;
    private boolean isVideo, isGif;
    private String editId, editUrl, editPoster = "", editCaption = "";
    private int natW, natH;
    private double dur, start, end;
    private int[] crop;
    private int res;
    private float aspect;
    private boolean cropMode;
    private float[] box;      // x,y,w,h (بكسل أصلي) أثناء القص
    private float k = 1f;
    private float[] view;     // المستطيل الظاهر من الوسائط (c في applyView)
    private Bitmap bmp;
    private int insTop, insBottom;

    // ألوان (متغيرات CSS في .bbmc)
    private final int cBg, cFg, cField, cLine, cMuted, cSub, cLabel, cTitle, cChipBg, cChipLine, cIb, cRail, cBtn;

    // الواجهة
    private Dialog dlg;
    private LinearLayout root, top, tools, cropBar, foot;
    private FrameLayout stage, wrap, closeBtn, cropBtn, sendBtn, playBtn;
    private View cropDot;
    private TextView titleTv, durTv, noteTv;
    private ImageView imgView, posterIv;
    private TextureView tex;
    private CropBoxView boxView;
    private TrimTrack trimTrack;
    private LinearLayout trimBox, resRow;
    private FlowRow resChips, aspChips;
    private EditText cap;
    private final java.util.ArrayList<TextView> resViews = new java.util.ArrayList<>();
    private final java.util.ArrayList<TextView> aspViews = new java.util.ArrayList<>();

    // مشغّل الفيديو
    private MediaPlayer mp;
    private boolean mpReady, playing;
    private Surface surf;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (mp == null || !mpReady || !playing) return;
            try {
                double pos = mp.getCurrentPosition() / 1000.0;
                if (dur > 0 && pos >= end) {
                    mp.pause(); playing = false;
                    mp.seekTo((int) Math.round(start * 1000));
                    updatePlayUi();
                }
            } catch (Throwable ignored) { }
            if (trimTrack != null) trimTrack.invalidate();
            if (playing) uiH.postDelayed(this, 60);
        }
    };

    private MediaComposer(Activity act, boolean dark, Host host) {
        this.act = act; this.dark = dark; this.host = host;
        this.dens = act.getResources().getDisplayMetrics().density;
        float w = Math.min(act.getResources().getDisplayMetrics().widthPixels, act.getResources().getDisplayMetrics().heightPixels) / dens;
        this.bs = Math.max(0.9f, Math.min(1.25f, (float) Math.sqrt(w / 360f)));
        if (dark) {
            cBg = 0xFF0F172A; cFg = 0xFFF8FAFC; cField = 0xFF0F172A; cLine = 0xFF2E3B4E; cMuted = 0xFF8696A7; cSub = 0xFFCBD5E1;
            cLabel = 0xFFA8841F; cTitle = 0xFFC39A2A; cChipBg = 0xFF0F172A; cChipLine = 0xFF2E3B4E; cIb = 0xFF0F172A; cRail = 0xFF2E3B4E; cBtn = 0xFF0F172A;
        } else {
            cBg = 0xFFEDF2F8; cFg = 0xFF17212B; cField = 0xFFFFFFFF; cLine = 0xFFDCE4EE; cMuted = 0xFF8B98A8; cSub = 0xFF3F4F63;
            cLabel = 0xFF6B4F08; cTitle = 0xFF8B680E; cChipBg = 0xFFFFFFFF; cChipLine = 0xFFDCE4EE; cIb = 0xFFFFFFFF; cRail = 0xFFDCE4EE; cBtn = 0xFFEAF0F7;
        }
    }

    // ------------------------------------------------------------------ أدوات
    private int dp(float v) { return Math.round(v * dens); }
    private int px(float v) { return Math.round(v * dens * bs); }
    private void tsz(TextView t, float css) { t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, css * dens * bs); }
    private static float clamp(float n, float a, float b) { return Math.max(a, Math.min(b, n)); }
    private static double clampD(double n, double a, double b) { return Math.max(a, Math.min(b, n)); }
    private void toast(String m) { try { AppToast.show(act, m); } catch (Throwable ignored) { } }

    private String fmt(double t) {
        t = Math.max(0, t);
        int m = (int) Math.floor(t / 60), s = (int) Math.floor(t % 60);
        return m + ":" + (s < 10 ? "0" : "") + s;
    }

    private String fmtSize(double bytes) {
        return bytes >= 1048576 ? String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
                : Math.max(1, Math.round(bytes / 1024.0)) + " KB";
    }

    private GradientDrawable goldGrad(float rDp) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{G1, G2, G3});
        g.setCornerRadius(dp(rDp));
        return g;
    }

    private GradientDrawable shape(int fill, float rDp, int stroke, float strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(rDp));
        if (stroke != 0) g.setStroke(Math.max(1, dp(strokeDp)), stroke);
        return g;
    }

    private void goldShadow(View v, float elevDp) {
        try {
            v.setElevation(dp(elevDp));
            if (Build.VERSION.SDK_INT >= 28) {
                v.setOutlineSpotShadowColor(0x61BE9628);
                v.setOutlineAmbientShadowColor(0x61BE9628);
            }
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ بناء النافذة
    private void show() {
        current = this;
        dlg = new Dialog(act, android.R.style.Theme_Black_NoTitleBar);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dlg.setCancelable(false);
        dlg.setOnKeyListener((d, code, ev) -> {
            if (code == android.view.KeyEvent.KEYCODE_BACK) {
                if (ev.getAction() == android.view.KeyEvent.ACTION_UP) onBack();
                return true;
            }
            return false;
        });

        root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        // ---- .bbmc-top
        top = new LinearLayout(act);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(12), dp(10), dp(12), dp(8));
        closeBtn = iconBtn(R.drawable.ic_mc_close);
        closeBtn.setOnClickListener(v -> close());
        titleTv = new TextView(act);
        titleTv.setGravity(Gravity.CENTER);
        titleTv.setTypeface(null, Typeface.BOLD);
        titleTv.setTextColor(cTitle);
        tsz(titleTv, 15);
        titleTv.setText(titleText());
        cropBtn = iconBtn(R.drawable.ic_mc_crop);
        cropDot = new View(act);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL); dot.setColor(GOLD); dot.setStroke(dp(2), cBg);
        cropDot.setBackground(dot);
        cropDot.setVisibility(View.GONE);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(dp(9), dp(9), Gravity.TOP | Gravity.RIGHT);
        dlp.topMargin = dp(7); dlp.rightMargin = dp(7);
        cropBtn.addView(cropDot, dlp);
        cropBtn.setOnClickListener(v -> enterCrop());
        if (isGif || editId != null) cropBtn.setVisibility(View.GONE);
        top.addView(closeBtn, new LinearLayout.LayoutParams(px(42), px(42)));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
        tl.setMarginStart(dp(8)); tl.setMarginEnd(dp(8));
        top.addView(titleTv, tl);
        top.addView(cropBtn, new LinearLayout.LayoutParams(px(42), px(42)));
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));

        // ---- .bbmc-stage > .bbmc-wrap
        stage = new FrameLayout(act);
        stage.setPadding(dp(12), dp(6), dp(12), dp(6));
        wrap = new FrameLayout(act);
        wrap.setBackground(shape(0xFF000000, 10, cLine, 1));
        wrap.setClipToOutline(true);
        wrap.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10)); }
        });
        FrameLayout.LayoutParams wlp = new FrameLayout.LayoutParams(dp(2), dp(2), Gravity.CENTER);
        stage.addView(wrap, wlp);
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1f));
        stage.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) { applyView(false); placeBox(); }
        });

        // ---- .bbmc-tools (للفيديو الجديد فقط؛ فارغة لغيره لكنها تحتفظ بحشوتها كما في الـ HTML)
        tools = new LinearLayout(act);
        tools.setOrientation(LinearLayout.VERTICAL);
        tools.setPadding(dp(16), dp(6), dp(16), dp(4));
        if (isVideo && editId == null) buildVideoTools();
        root.addView(tools, new LinearLayout.LayoutParams(-1, -2));

        // ---- .bbmc-cropbar
        cropBar = new LinearLayout(act);
        cropBar.setOrientation(LinearLayout.VERTICAL);
        cropBar.setPadding(dp(16), dp(8), dp(16), dp(14));
        aspChips = new FlowRow(act, true);
        for (int i = 0; i < ASP_L.length; i++) {
            final int idx = i;
            TextView c = chip(ASP_L[i]);
            c.setOnClickListener(v -> onAsp(idx));
            aspViews.add(c);
            aspChips.addView(c);
        }
        LinearLayout.LayoutParams acl = new LinearLayout.LayoutParams(-1, -2);
        cropBar.addView(aspChips, acl);
        LinearLayout act3 = new LinearLayout(act);
        act3.setOrientation(LinearLayout.HORIZONTAL);
        TextView bCancel = cropBtnView("إلغاء", false), bReset = cropBtnView("إعادة ضبط", false), bDone = cropBtnView("تم", true);
        bCancel.setOnClickListener(v -> exitCrop(false));
        bReset.setOnClickListener(v -> {
            aspect = 0f; markAspect();
            if (natW > 0) { box = new float[]{0, 0, natW, natH}; placeBox(); }
        });
        bDone.setOnClickListener(v -> exitCrop(true));
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, -2, 1f);
        LinearLayout.LayoutParams b2 = new LinearLayout.LayoutParams(0, -2, 1f);
        LinearLayout.LayoutParams b3 = new LinearLayout.LayoutParams(0, -2, 1f);
        b2.setMarginStart(dp(8)); b3.setMarginStart(dp(8));
        act3.addView(bCancel, b1); act3.addView(bReset, b2); act3.addView(bDone, b3);
        LinearLayout.LayoutParams a3 = new LinearLayout.LayoutParams(-1, -2);
        a3.topMargin = dp(12);
        cropBar.addView(act3, a3);
        cropBar.setVisibility(View.GONE);
        root.addView(cropBar, new LinearLayout.LayoutParams(-1, -2));

        // ---- .bbmc-foot
        foot = new LinearLayout(act);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.BOTTOM);
        foot.setPadding(dp(12), dp(8), dp(12), dp(10));
        cap = new EditText(act);
        cap.setHint("أضف وصفاً " + (isVideo ? "للفيديو" : "للصورة") + "...");
        cap.setHintTextColor(cMuted);
        cap.setTextColor(cFg);
        tsz(cap, 15);
        cap.setLineSpacing(0, 1.5f);
        cap.setMinHeight(px(46));
        cap.setMaxHeight(dp(110));
        cap.setFilters(new InputFilter[]{new InputFilter.LengthFilter(500)});
        cap.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        cap.setHorizontallyScrolling(false);
        cap.setMaxLines(5);
        cap.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        cap.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        cap.setPadding(dp(16), dp(12), dp(16), dp(12));
        cap.setBackground(shape(cField, 12, cLine, 1));
        cap.setOnFocusChangeListener((v, has) -> cap.setBackground(shape(cField, 12, has ? GOLD : cLine, 1)));
        if (editId != null && editCaption.length() > 0) cap.setText(editCaption);
        sendBtn = new FrameLayout(act);
        sendBtn.setBackground(goldCircle());
        goldShadow(sendBtn, 6);
        ImageView si = new ImageView(act);
        si.setImageResource(R.drawable.ic_mc_send);
        si.setPadding(0, 0, dp(2), 0);
        FrameLayout.LayoutParams sil = new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER);
        sendBtn.addView(si, sil);
        sendBtn.setContentDescription("إرسال");
        sendBtn.setOnClickListener(v -> doSend());
        BabylonFx.press(sendBtn, 0.93f);
        foot.addView(cap, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(px(48), px(48));
        sl.setMarginStart(dp(10));
        foot.addView(sendBtn, sl);
        root.addView(foot, new LinearLayout.LayoutParams(-1, -2));

        root.setOnApplyWindowInsetsListener((v, ins) -> {
            insTop = ins.getSystemWindowInsetTop();
            insBottom = ins.getSystemWindowInsetBottom();
            top.setPadding(dp(12), Math.max(dp(10), insTop), dp(12), dp(8));
            foot.setPadding(dp(12), dp(8), dp(12), dp(10) + Math.max(0, insBottom));
            cropBar.setPadding(dp(16), dp(8), dp(16), dp(14) + Math.max(0, insBottom));
            return ins;
        });

        dlg.setContentView(root);
        Window w = dlg.getWindow();
        if (w != null) {
            android.util.DisplayMetrics dm = act.getResources().getDisplayMetrics();
            GradientDrawable bg = new GradientDrawable();
            bg.setGradientType(GradientDrawable.RADIAL_GRADIENT);
            bg.setGradientCenter(0.5f, 0.5f);
            bg.setGradientRadius((float) Math.hypot(dm.widthPixels / 2.0, dm.heightPixels / 2.0));
            bg.setColors(dark ? new int[]{0xFF1E293B, 0xFF090D16} : new int[]{0xFFFFFFFF, 0xFFEDF2F8});
            w.setBackgroundDrawable(bg);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            w.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
            w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            w.setStatusBarColor(Color.TRANSPARENT);
            w.setNavigationBarColor(dark ? 0xFF090D16 : 0xFFEDF2F8);
            int ui = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
            if (!dark && Build.VERSION.SDK_INT >= 23) ui |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (!dark && Build.VERSION.SDK_INT >= 26) ui |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            w.getDecorView().setSystemUiVisibility(ui);
        }
        dlg.show();
        startMedia();
    }

    private String titleText() {
        if (editId != null) return isVideo ? "تعديل الفيديو" : "تعديل الصورة";
        return isVideo ? "إرسال فيديو" : "إرسال صورة";
    }

    private GradientDrawable goldCircle() {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{G1, G2, G3});
        g.setShape(GradientDrawable.OVAL);
        return g;
    }

    /** .bbmc-ib: زر دائري 42 مع حد 1px ولون --ib. */
    private FrameLayout iconBtn(int res) {
        FrameLayout f = new FrameLayout(act);
        f.setBackground(shape(cIb, 999, cLine, 1));
        ImageView i = new ImageView(act);
        i.setImageResource(res);
        i.setColorFilter(cFg, android.graphics.PorterDuff.Mode.SRC_IN);
        f.addView(i, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        BabylonFx.press(f, 0.93f);
        return f;
    }

    private TextView chip(String text) {
        TextView c = new TextView(act);
        c.setText(text);
        c.setGravity(Gravity.CENTER);
        c.setTypeface(null, Typeface.BOLD);
        tsz(c, 12.5f);
        c.setMinWidth(px(52));
        c.setPadding(dp(11), dp(7), dp(11), dp(7));
        c.setTextColor(cSub);
        c.setBackground(shape(cChipBg, 10, cChipLine, 1));
        c.setIncludeFontPadding(false);
        return c;
    }

    private void chipOn(TextView c, boolean on) {
        if (on) {
            c.setBackground(goldGrad(10));
            c.setTextColor(0xFFFFFFFF);
            c.setShadowLayer(dp(2), 0, dp(1), 0x806E4800);
            goldShadow(c, 4);
        } else {
            c.setBackground(shape(cChipBg, 10, cChipLine, 1));
            c.setTextColor(cSub);
            c.setShadowLayer(0, 0, 0, 0);
            c.setElevation(0f);
        }
    }

    private TextView cropBtnView(String text, boolean pri) {
        TextView b = new TextView(act);
        b.setText(text);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(null, Typeface.BOLD);
        tsz(b, 14);
        b.setPadding(dp(12), dp(12), dp(12), dp(12));
        if (pri) {
            b.setBackground(goldGrad(10));
            b.setTextColor(0xFFFFFFFF);
            b.setShadowLayer(dp(2), 0, dp(1), 0x806E4800);
            goldShadow(b, 6);
        } else {
            b.setBackground(shape(cField, 10, cLine, 1));
            b.setTextColor(cFg);
        }
        BabylonFx.press(b, 0.96f);
        return b;
    }

    private TextView label(String t) {
        TextView l = new TextView(act);
        l.setText(t);
        l.setTypeface(null, Typeface.BOLD);
        l.setTextColor(cLabel);
        tsz(l, 13);
        return l;
    }

    private void buildVideoTools() {
        // مدة الفيديو: العنوان + المدة، ثم الشريط
        trimBox = new LinearLayout(act);
        trimBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(label("مدة الفيديو"));
        View sp = new View(act);
        row.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));
        durTv = new TextView(act);
        durTv.setTextColor(GOLD);
        durTv.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        durTv.setTextDirection(View.TEXT_DIRECTION_LTR);
        tsz(durTv, 12.5f);
        row.addView(durTv);
        trimBox.addView(row, new LinearLayout.LayoutParams(-1, -2));
        trimTrack = new TrimTrack(act);
        LinearLayout.LayoutParams ttl = new LinearLayout.LayoutParams(-1, px(44));
        ttl.setMarginStart(dp(14)); ttl.setMarginEnd(dp(14));
        trimBox.addView(trimTrack, ttl);
        tools.addView(trimBox, new LinearLayout.LayoutParams(-1, -2));

        // دقة الفيديو
        resRow = new LinearLayout(act);
        resRow.setOrientation(LinearLayout.HORIZONTAL);
        resRow.setGravity(Gravity.CENTER_VERTICAL);
        resRow.addView(label("دقة الفيديو"));
        View sp2 = new View(act);
        resRow.addView(sp2, new LinearLayout.LayoutParams(0, 1, 1f));
        resChips = new FlowRow(act, false);
        for (final int r : RES) {
            TextView c = chip(r + "p");
            c.setTag(Integer.valueOf(r));
            c.setOnClickListener(v -> {
                if (!v.isEnabled()) return;
                res = r; refreshResChips(false);
            });
            resViews.add(c);
            resChips.addView(c);
        }
        resRow.addView(resChips, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams rrl = new LinearLayout.LayoutParams(-1, -2);
        rrl.topMargin = dp(10);
        tools.addView(resRow, rrl);

        noteTv = new TextView(act);
        noteTv.setTextColor(cMuted);
        noteTv.setGravity(Gravity.RIGHT);
        noteTv.setLineSpacing(0, 1.5f);
        tsz(noteTv, 11.5f);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-1, -2);
        nl.topMargin = dp(10);
        tools.addView(noteTv, nl);
        renderTrim();
        refreshResChips(true);
    }

    // ------------------------------------------------------------------ تحميل الوسائط
    private void startMedia() {
        if (isVideo) {
            tex = new TextureView(act);
            tex.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                    surf = new Surface(st);
                    try { if (mp != null) mp.setSurface(surf); } catch (Throwable ignored) { }
                }
                @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { }
                @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                    try { if (mp != null) mp.setSurface(null); } catch (Throwable ignored) { }
                    try { if (surf != null) surf.release(); } catch (Throwable ignored) { }
                    surf = null;
                    return true;
                }
                @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
            });
            wrap.addView(tex, new FrameLayout.LayoutParams(dp(2), dp(2)));
            if (editId != null && editPoster.length() > 0) {
                posterIv = new ImageView(act);
                posterIv.setScaleType(ImageView.ScaleType.FIT_XY);
                wrap.addView(posterIv, new FrameLayout.LayoutParams(dp(2), dp(2)));
                loadRemoteAsync(editPoster, 1280, b -> { if (b != null && posterIv != null) posterIv.setImageBitmap(b); });
            }
            playBtn = new FrameLayout(act);
            playBtn.setBackground(shape(0x8C12161C, 999, 0, 0));
            ImageView pi = new ImageView(act);
            pi.setImageResource(R.drawable.ic_mc_play);
            pi.setColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
            playBtn.addView(pi, new FrameLayout.LayoutParams(px(30), px(30), Gravity.CENTER));
            playBtn.setOnClickListener(v -> togglePlay());
            wrap.addView(playBtn, new FrameLayout.LayoutParams(px(62), px(62), Gravity.CENTER));
            createPlayer();
        } else {
            imgView = new ImageView(act);
            imgView.setScaleType(ImageView.ScaleType.FIT_XY);
            wrap.addView(imgView, new FrameLayout.LayoutParams(dp(2), dp(2)));
            if (editId != null) {
                loadRemoteAsync(editUrl, 2048, b -> {
                    if (dlg == null) return;
                    if (b == null) { toast("تعذر قراءة هذه الصورة"); close(); return; }
                    setBitmap(b);
                });
            } else {
                final File f = file;
                final boolean gif = isGif;
                new Thread(() -> {
                    final Bitmap b = decodeFile(f, 4096, !gif);
                    uiH.post(() -> {
                        if (dlg == null) return;
                        if (b == null) { toast("تعذر قراءة هذه الصورة"); close(); return; }
                        setBitmap(b);
                    });
                }).start();
            }
        }
    }

    private void setBitmap(Bitmap b) {
        bmp = b;
        natW = b.getWidth(); natH = b.getHeight();
        if (imgView != null) imgView.setImageBitmap(b);
        applyView(false);
    }

    static Bitmap decodeFile(File f, int maxSide, boolean rotate) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int s = 1;
            while (Math.max(o.outWidth, o.outHeight) / s > maxSide) s *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s;
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
            if (b == null) return null;
            if (rotate) {
                Matrix m = new Matrix();
                boolean change = true;
                int ori = ExifInterface.ORIENTATION_NORMAL;
                try { ori = new ExifInterface(f.getAbsolutePath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL); } catch (Throwable ignored) { }
                switch (ori) {
                    case ExifInterface.ORIENTATION_ROTATE_90: m.postRotate(90); break;
                    case ExifInterface.ORIENTATION_ROTATE_180: m.postRotate(180); break;
                    case ExifInterface.ORIENTATION_ROTATE_270: m.postRotate(270); break;
                    case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: m.postScale(-1, 1); break;
                    case ExifInterface.ORIENTATION_FLIP_VERTICAL: m.postScale(1, -1); break;
                    case ExifInterface.ORIENTATION_TRANSPOSE: m.postRotate(90); m.postScale(-1, 1); break;
                    case ExifInterface.ORIENTATION_TRANSVERSE: m.postRotate(270); m.postScale(-1, 1); break;
                    default: change = false; break;
                }
                if (change) {
                    Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
                    if (r != b) b.recycle();
                    b = r;
                }
            }
            return b;
        } catch (Throwable t) { return null; }
    }

    private interface BmpCb { void on(Bitmap b); }

    /** صورة من رابط https أو data: (للتعديل). */
    private void loadRemoteAsync(final String url, final int maxSide, final BmpCb cb) {
        new Thread(() -> {
            Bitmap b = null;
            try {
                byte[] bytes;
                if (url == null || url.isEmpty()) bytes = null;
                else if (url.startsWith("data:") && url.indexOf(',') > 0) bytes = Base64.decode(url.substring(url.indexOf(',') + 1), Base64.DEFAULT);
                else {
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(15000); c.setReadTimeout(20000);
                    InputStream is = c.getInputStream();
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384]; int n;
                    while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
                    is.close(); c.disconnect();
                    bytes = bo.toByteArray();
                }
                if (bytes != null) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
                    int s = 1;
                    while (Math.max(o.outWidth, o.outHeight) / s > maxSide) s *= 2;
                    BitmapFactory.Options o2 = new BitmapFactory.Options();
                    o2.inSampleSize = s;
                    b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o2);
                }
            } catch (Throwable ignored) { }
            final Bitmap fb = b;
            uiH.post(() -> cb.on(fb));
        }).start();
    }

    // ------------------------------------------------------------------ المشغّل (video في الـ HTML)
    private void createPlayer() {
        try {
            mp = new MediaPlayer();
            if (editId != null) mp.setDataSource(editUrl);
            else mp.setDataSource(file.getAbsolutePath());
            mp.setVolume(0f, 0f);   // m.muted = true
            mp.setLooping(false);
            if (surf != null) mp.setSurface(surf);
            mp.setOnPreparedListener(m -> {
                mpReady = true;
                int vw = m.getVideoWidth(), vh = m.getVideoHeight();
                natW = vw; natH = vh;
                if (natW <= 0 || natH <= 0) toast("تعذر قراءة أبعاد الفيديو");
                if (editId == null) {
                    int d = m.getDuration();
                    dur = d > 0 ? d / 1000.0 : 0;
                    start = 0; end = dur;
                    try { m.seekTo(50); } catch (Throwable ignored) { }   // v.currentTime = 0.05
                    refreshResChips(true);
                    renderTrim();
                } else {
                    try { m.seekTo(50); } catch (Throwable ignored) { }
                }
                applyView(false);
            });
            mp.setOnCompletionListener(m -> { playing = false; updatePlayUi(); });
            mp.setOnErrorListener((m, what, extra) -> { toast("تعذر قراءة هذا الفيديو"); return true; });
            mp.prepareAsync();
        } catch (Throwable t) {
            toast("تعذر قراءة هذا الفيديو");
        }
    }

    private void togglePlay() {
        if (mp == null || !mpReady) return;
        try {
            if (playing) { mp.pause(); playing = false; }
            else {
                double pos = mp.getCurrentPosition() / 1000.0;
                if (dur > 0 && (pos < start - 0.05 || pos >= end - 0.05)) mp.seekTo((int) Math.round(start * 1000));
                mp.start(); playing = true;
                if (posterIv != null) posterIv.setVisibility(View.GONE);
                uiH.removeCallbacks(tick);
                uiH.post(tick);
            }
        } catch (Throwable ignored) { }
        updatePlayUi();
    }

    private void updatePlayUi() {
        if (playBtn != null) playBtn.animate().alpha(playing ? 0f : 1f).setDuration(150).start();
    }

    private void pauseMedia() {
        try { if (mp != null && mpReady && playing) { mp.pause(); } } catch (Throwable ignored) { }
        playing = false;
        updatePlayUi();
    }

    private void seekTo(double sec) {
        try { if (mp != null && mpReady) mp.seekTo((int) Math.round(Math.max(0, sec) * 1000)); } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ العرض والمعاينة (applyView)
    private void applyView(boolean full) {
        if (natW <= 0 || natH <= 0 || stage == null) return;
        float cx = 0, cy = 0, cw = natW, ch = natH;
        if (!(full || cropMode) && crop != null) { cx = crop[0]; cy = crop[1]; cw = crop[2]; ch = crop[3]; }
        int aw = stage.getWidth() - dp(24), ah = stage.getHeight() - dp(12);
        if (aw < dp(40) || ah < dp(40)) return;
        float kk = Math.min(aw / cw, ah / ch);
        FrameLayout.LayoutParams wl = new FrameLayout.LayoutParams(Math.round(cw * kk), Math.round(ch * kk), Gravity.CENTER);
        wrap.setLayoutParams(wl);
        View m = isVideo ? tex : imgView;
        if (m != null) {
            FrameLayout.LayoutParams ml = new FrameLayout.LayoutParams(Math.round(natW * kk), Math.round(natH * kk));
            ml.leftMargin = Math.round(-cx * kk); ml.topMargin = Math.round(-cy * kk);
            m.setLayoutParams(ml);
        }
        if (posterIv != null) {
            FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(Math.round(natW * kk), Math.round(natH * kk));
            pl.leftMargin = Math.round(-cx * kk); pl.topMargin = Math.round(-cy * kk);
            posterIv.setLayoutParams(pl);
        }
        k = kk;
        view = new float[]{cx, cy, cw, ch};
        if (boxView != null) boxView.invalidate();
    }

    // ------------------------------------------------------------------ مدة الفيديو (قص زمني)
    private void renderTrim() {
        if (trimBox == null || durTv == null) return;
        if (dur <= 0) { durTv.setText("غير متاحة"); }
        else durTv.setText(fmt(start) + " – " + fmt(end) + "  •  " + Math.round(end - start) + "s");
        if (trimTrack != null) trimTrack.invalidate();
        updateNote();
    }

    // ------------------------------------------------------------------ دقة الفيديو
    private float effSide() {
        float w = crop != null ? crop[2] : natW, h = crop != null ? crop[3] : natH;
        return Math.min(w, h);
    }

    private void refreshResChips(boolean init) {
        if (resViews.isEmpty()) return;
        float side = effSide();
        int best = 0;
        for (TextView c : resViews) {
            int r = ((Integer) c.getTag()).intValue();
            boolean ok = side <= 0 || r <= side * 1.08f;
            c.setEnabled(ok);
            c.setAlpha(ok ? 1f : 0.3f);
            if (ok) best = r;
        }
        if (best == 0) best = RES[0];
        if (init || res == 0 || (side > 0 && res > side * 1.08f)) res = best;
        for (TextView c : resViews) chipOn(c, ((Integer) c.getTag()).intValue() == res);
        updateNote();
    }

    private void updateNote() {
        if (noteTv == null || natW <= 0) return;
        float w = crop != null ? crop[2] : natW, h = crop != null ? crop[3] : natH;
        float side = Math.min(w, h), ow = w, oh = h;
        if (res > 0 && res < side - 1) { float kk = res / side; ow = w * kk; oh = h * kk; }
        double pix = (ow * oh) / (double) (natW * natH);
        double tr = dur > 0 ? (end - start) / dur : 1;
        double est = fileSize * Math.min(1, pix) * Math.min(1, tr);
        noteTv.setText("الحجم التقريبي: " + fmtSize(est));
    }

    // ------------------------------------------------------------------ القص المكاني (صورة أو فيديو)
    private void enterCrop() {
        if (natW <= 0) return;
        pauseMedia();
        cropMode = true;
        titleTv.setText(isVideo ? "قص الفيديو" : "قص الصورة");
        titleTv.setTextColor(0xFFFFFFFF);
        // عنوان بتدرج الأزرار (الذهبي) كما في .bbmc.crop-mode .bbmc-title
        float tw = titleTv.getPaint().measureText(titleTv.getText().toString());
        titleTv.getPaint().setShader(new LinearGradient(0, 0, Math.max(1f, tw), titleTv.getTextSize(),
                new int[]{G1, G2, G3}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        titleTv.invalidate();
        cropBtn.setVisibility(View.GONE);
        if (playBtn != null) playBtn.setVisibility(View.GONE);
        tools.setVisibility(View.GONE);
        foot.setVisibility(View.GONE);
        cropBar.setVisibility(View.VISIBLE);
        box = crop != null ? new float[]{crop[0], crop[1], crop[2], crop[3]} : new float[]{0, 0, natW, natH};
        aspect = 0f; markAspect();
        applyView(false);
        boxView = new CropBoxView(act);
        wrap.addView(boxView, new FrameLayout.LayoutParams(-1, -1));
        placeBox();
    }

    private void exitCrop(boolean apply) {
        if (apply && box != null) {
            boolean full = box[2] >= natW - 2 && box[3] >= natH - 2;
            crop = full ? null : new int[]{Math.round(box[0]), Math.round(box[1]), Math.round(box[2]), Math.round(box[3])};
        }
        cropMode = false;
        titleTv.getPaint().setShader(null);
        titleTv.setTextColor(cTitle);
        titleTv.setText(titleText());
        cropBtn.setVisibility(View.VISIBLE);
        cropDot.setVisibility(crop != null ? View.VISIBLE : View.GONE);
        if (playBtn != null) playBtn.setVisibility(View.VISIBLE);
        tools.setVisibility(View.VISIBLE);
        foot.setVisibility(View.VISIBLE);
        cropBar.setVisibility(View.GONE);
        if (boxView != null) { wrap.removeView(boxView); boxView = null; }
        applyView(false);
        if (isVideo) {
            refreshResChips(false);
            seekTo(start > 0 ? start : 0.05);
        }
    }

    private void placeBox() {
        if (boxView != null) boxView.invalidate();
    }

    private void markAspect() {
        for (int i = 0; i < aspViews.size(); i++) chipOn(aspViews.get(i), ASP_V[i] == aspect);
    }

    private void onAsp(int idx) {
        if (!cropMode || box == null) return;
        float r = ASP_V[idx];
        aspect = r; markAspect();
        if (r == 0f) return;
        float W = natW, H = natH;
        float w = Math.min(W, H * r), h = w / r;
        float cx = box[0] + box[2] / 2f, cy = box[1] + box[3] / 2f;
        w *= 0.9f; h *= 0.9f;
        box = new float[]{clamp(cx - w / 2f, 0, W - w), clamp(cy - h / 2f, 0, H - h), w, h};
        placeBox();
    }

    // ------------------------------------------------------------------ الإرسال (doSend)
    private void onBack() {
        if (cropMode) { exitCrop(false); return; }
        close();
    }

    private void doSend() {
        if (cropMode) exitCrop(true);
        String c = cap.getText().toString().trim();
        if (c.length() > 500) c = c.substring(0, 500);
        final String caption = c;
        if (editId != null) {
            final String id = editId;
            close();
            host.onEditCaption(id, caption);
            return;
        }
        final Req r = new Req();
        r.file = file; r.mime = mime; r.size = fileSize; r.video = isVideo; r.gif = isGif; r.caption = caption;
        r.natW = natW; r.natH = natH; r.crop = crop; r.res = res;
        r.dur = dur; r.start = start; r.end = end;
        if (!isVideo && crop != null && !isGif && bmp != null) {
            final int[] cr = crop; final Bitmap src = bmp;
            new Thread(() -> {
                try {
                    int x = Math.max(0, Math.min(src.getWidth() - 1, cr[0])), y = Math.max(0, Math.min(src.getHeight() - 1, cr[1]));
                    int w = Math.max(1, Math.min(src.getWidth() - x, cr[2])), h = Math.max(1, Math.min(src.getHeight() - y, cr[3]));
                    Bitmap out = Bitmap.createBitmap(src, x, y, w, h);
                    File f = new File(act.getCacheDir(), "crop_" + System.currentTimeMillis() + ".jpg");
                    FileOutputStream fo = new FileOutputStream(f);
                    out.compress(Bitmap.CompressFormat.JPEG, 92, fo);
                    fo.close();
                    r.cropped = f; r.mime = "image/jpeg"; r.size = f.length();
                    uiH.post(() -> { close(); uiH.postDelayed(() -> host.onSend(r), 60); });
                } catch (Throwable t) {
                    uiH.post(() -> toast("تعذر قص الصورة"));
                }
            }).start();
            return;
        }
        close();
        uiH.postDelayed(() -> host.onSend(r), 60);
    }

    void close() {
        if (current == this) current = null;
        uiH.removeCallbacks(tick);
        try { if (mp != null) { mp.release(); } } catch (Throwable ignored) { }
        mp = null; mpReady = false; playing = false;
        try { if (surf != null) surf.release(); } catch (Throwable ignored) { }
        surf = null;
        try { if (cap != null) { ((android.view.inputmethod.InputMethodManager) act.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(cap.getWindowToken(), 0); } } catch (Throwable ignored) { }
        Dialog d = dlg;
        dlg = null;
        try { if (d != null) d.dismiss(); } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ تخطيط الشرائح (flex-wrap)
    /** صف يلتف على أسطر بفجوة 6dp؛ center = وسط، وإلا flex-end (في RTL = اليسار). */
    private final class FlowRow extends ViewGroup {
        private final boolean center;
        FlowRow(android.content.Context c, boolean center) { super(c); this.center = center; }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            int maxW = MeasureSpec.getSize(wSpec);
            if (MeasureSpec.getMode(wSpec) == MeasureSpec.UNSPECIFIED) maxW = Integer.MAX_VALUE / 2;
            int gap = dp(6), x = 0, y = 0, lineH = 0, usedW = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                if (c.getVisibility() == GONE) continue;
                c.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                int cw = c.getMeasuredWidth(), chh = c.getMeasuredHeight();
                if (x > 0 && x + cw > maxW) { y += lineH + gap; x = 0; lineH = 0; }
                x += cw + gap; lineH = Math.max(lineH, chh); usedW = Math.max(usedW, x - gap);
            }
            int h = y + lineH;
            int w = MeasureSpec.getMode(wSpec) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(wSpec) : usedW;
            setMeasuredDimension(w, h);
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int W = r - l, gap = dp(6);
            java.util.ArrayList<java.util.ArrayList<View>> lines = new java.util.ArrayList<>();
            java.util.ArrayList<View> cur = new java.util.ArrayList<>();
            int x = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                if (c.getVisibility() == GONE) continue;
                int cw = c.getMeasuredWidth();
                if (!cur.isEmpty() && x + cw > W) { lines.add(cur); cur = new java.util.ArrayList<>(); x = 0; }
                cur.add(c); x += cw + gap;
            }
            if (!cur.isEmpty()) lines.add(cur);
            int y = 0;
            for (java.util.ArrayList<View> ln : lines) {
                int lw = 0, lh = 0;
                for (View c : ln) { lw += c.getMeasuredWidth(); lh = Math.max(lh, c.getMeasuredHeight()); }
                lw += gap * (ln.size() - 1);
                int free = Math.max(0, W - lw);
                int startOff = center ? free / 2 : free;   // RTL: flex-end = يسار الصف
                int rightEdge = W - startOff;              // الصف يبدأ من اليمين
                for (View c : ln) {
                    int cw = c.getMeasuredWidth(), chh = c.getMeasuredHeight();
                    c.layout(rightEdge - cw, y + (lh - chh) / 2, rightEdge, y + (lh - chh) / 2 + chh);
                    rightEdge -= cw + gap;
                }
                y += lh + gap;
            }
        }
    }

    // ------------------------------------------------------------------ إطار القص (.bbmc-box + .bbmc-hd)
    private final class CropBoxView extends View {
        private final Paint dimP = new Paint();
        private final Paint ringP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint gridP = new Paint();
        private final Paint cornP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private String corner = "";
        private float sx, sy;
        private float[] b0;
        private boolean active;

        CropBoxView(android.content.Context c) {
            super(c);
            dimP.setColor(0x94000000);                       // rgba(0,0,0,.58)
            gridP.setColor(0x80E6C566);                      // rgba(230,197,102,.5)
            gridP.setStrokeWidth(Math.max(1f, dens));
            ringP.setStyle(Paint.Style.STROKE);
            cornP.setStyle(Paint.Style.STROKE);
            cornP.setStrokeJoin(Paint.Join.MITER);
            cornP.setStrokeCap(Paint.Cap.BUTT);
        }

        @Override protected void onDraw(Canvas cv) {
            if (box == null) return;
            float l = box[0] * k, t = box[1] * k, r = (box[0] + box[2]) * k, b = (box[1] + box[3]) * k;
            int W = getWidth(), H = getHeight();
            cv.drawRect(0, 0, W, t, dimP);
            cv.drawRect(0, b, W, H, dimP);
            cv.drawRect(0, t, l, b, dimP);
            cv.drawRect(r, t, W, b, dimP);
            Shader sh = new LinearGradient(l, t, Math.max(l + 1, r), Math.max(t + 1, b), new int[]{G1, G2, G3}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
            float bw = dp(2);
            ringP.setShader(sh);
            ringP.setStrokeWidth(bw);
            cv.drawRect(l + bw / 2f, t + bw / 2f, r - bw / 2f, b - bw / 2f, ringP);
            float il = l + bw, it = t + bw, ir = r - bw, ib = b - bw;
            float gw = ir - il, gh = ib - it;
            cv.drawLine(il + gw * 0.3333f, it, il + gw * 0.3333f, ib, gridP);
            cv.drawLine(il + gw * 0.6666f, it, il + gw * 0.6666f, ib, gridP);
            cv.drawLine(il, it + gh * 0.3333f, ir, it + gh * 0.3333f, gridP);
            cv.drawLine(il, it + gh * 0.6666f, ir, it + gh * 0.6666f, gridP);
            float th = dp(4), arm = px(22), hf = th / 2f;
            cornP.setShader(sh);
            cornP.setStrokeWidth(th);
            path.reset();
            path.moveTo(il + hf, it + arm); path.lineTo(il + hf, it + hf); path.lineTo(il + arm, it + hf);
            path.moveTo(ir - hf, it + arm); path.lineTo(ir - hf, it + hf); path.lineTo(ir - arm, it + hf);
            path.moveTo(il + hf, ib - arm); path.lineTo(il + hf, ib - hf); path.lineTo(il + arm, ib - hf);
            path.moveTo(ir - hf, ib - arm); path.lineTo(ir - hf, ib - hf); path.lineTo(ir - arm, ib - hf);
            cv.drawPath(path, cornP);
        }

        /** المقبض تحت الإصبع (ترتيب الـ DOM: nw ne sw se n s e w، والأخير فوق). */
        private String hit(float x, float y, float l, float t, float r, float b) {
            float cx = (l + r) / 2f, cy = (t + b) / 2f;
            float c38 = px(38), e28 = px(28), e56 = px(56);
            if (aspect == 0f) {
                if (x >= l && x <= l + e28 && y >= cy - e56 / 2f && y <= cy + e56 / 2f) return "w";
                if (x >= r - e28 && x <= r && y >= cy - e56 / 2f && y <= cy + e56 / 2f) return "e";
                if (y >= b - e28 && y <= b && x >= cx - e56 / 2f && x <= cx + e56 / 2f) return "s";
                if (y >= t && y <= t + e28 && x >= cx - e56 / 2f && x <= cx + e56 / 2f) return "n";
            }
            if (x >= r - c38 && y >= b - c38) return "se";
            if (x <= l + c38 && y >= b - c38) return "sw";
            if (x >= r - c38 && y <= t + c38) return "ne";
            if (x <= l + c38 && y <= t + c38) return "nw";
            return "";
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    if (box == null) return false;
                    float l = box[0] * k, t = box[1] * k, r = (box[0] + box[2]) * k, b = (box[1] + box[3]) * k;
                    float x = e.getX(), y = e.getY();
                    if (x < l || x > r || y < t || y > b) return false;
                    corner = hit(x, y, l, t, r, b);
                    sx = x; sy = y;
                    b0 = new float[]{box[0], box[1], box[2], box[3]};
                    active = true;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (!active || b0 == null) return false;
                    float W = natW, H = natH;
                    float dx = (e.getX() - sx) / k, dy = (e.getY() - sy) / k;
                    float MIN = Math.max(24f, Math.min(W, H) * 0.06f);
                    if (corner.isEmpty()) {
                        box = new float[]{clamp(b0[0] + dx, 0, W - b0[2]), clamp(b0[1] + dy, 0, H - b0[3]), b0[2], b0[3]};
                    } else if (corner.length() == 1 || aspect == 0f) {
                        float x1 = b0[0], x2 = b0[0] + b0[2], y1 = b0[1], y2 = b0[1] + b0[3];
                        if (corner.indexOf('w') > -1) x1 = clamp(b0[0] + dx, 0, x2 - MIN);
                        if (corner.indexOf('e') > -1) x2 = clamp(b0[0] + b0[2] + dx, x1 + MIN, W);
                        if (corner.indexOf('n') > -1) y1 = clamp(b0[1] + dy, 0, y2 - MIN);
                        if (corner.indexOf('s') > -1) y2 = clamp(b0[1] + b0[3] + dy, y1 + MIN, H);
                        box = new float[]{x1, y1, x2 - x1, y2 - y1};
                    } else {
                        boolean west = corner.indexOf('w') > -1, north = corner.indexOf('n') > -1;
                        float ax = west ? b0[0] + b0[2] : b0[0], ay = north ? b0[1] + b0[3] : b0[1];
                        float pxx = clamp((west ? b0[0] : b0[0] + b0[2]) + dx, 0, W), pyy = clamp((north ? b0[1] : b0[1] + b0[3]) + dy, 0, H);
                        float w = Math.abs(pxx - ax), h = Math.abs(pyy - ay);
                        if (west ? pxx > ax - MIN : pxx < ax + MIN) w = MIN;
                        if (north ? pyy > ay - MIN : pyy < ay + MIN) h = MIN;
                        float maxW = west ? ax : W - ax, maxH = north ? ay : H - ay;
                        float ra = aspect;
                        w = Math.max(w, h * ra); h = w / ra;
                        if (w > maxW) { w = maxW; h = w / ra; }
                        if (h > maxH) { h = maxH; w = h * ra; }
                        box = new float[]{west ? ax - w : ax, north ? ay - h : ay, w, h};
                    }
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    active = false;
                    return true;
                default:
                    return true;
            }
        }
    }

    // ------------------------------------------------------------------ شريط مدة الفيديو (.bbmc-track)
    private final class TrimTrack extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rf = new RectF();
        private int active = 0;   // 1 = البداية، 2 = النهاية

        TrimTrack(android.content.Context c) {
            super(c);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);   // ظل المقبض
        }

        @Override protected void onDraw(Canvas cv) {
            int w = getWidth();
            float railTop = dp(19), railH = dp(6);
            p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(cRail);
            rf.set(0, railTop, w, railTop + railH);
            cv.drawRoundRect(rf, dp(6), dp(6), p);
            if (dur <= 0) return;
            float a = (float) (start / dur) * w, b = (float) (end / dur) * w;
            rf.set(a, railTop, Math.max(a + 1, b), railTop + railH);
            p.setShader(new LinearGradient(rf.left, rf.top, rf.right, rf.bottom, new int[]{G1, G2, G3}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            cv.drawRoundRect(rf, dp(6), dp(6), p);
            p.setShader(null);
            // رأس التشغيل
            double pos = 0;
            try { if (mp != null && mpReady) pos = mp.getCurrentPosition() / 1000.0; } catch (Throwable ignored) { }
            float fx = (float) clampD(pos / dur, 0, 1) * w;
            p.setColor(0xE6FFFFFF);
            rf.set(fx - dp(1), dp(12), fx + dp(1), dp(12) + px(20));
            cv.drawRoundRect(rf, dp(2), dp(2), p);
            // المقبضان
            for (int i = 0; i < 2; i++) {
                float x = i == 0 ? a : b;
                float left = x - dp(13), topY = dp(6), wd = px(26), ht = px(32);
                p.setStyle(Paint.Style.FILL); p.setColor(0xFFFFFFFF);
                p.setShadowLayer(dp(5), 0, dp(1), 0x80000000);
                rf.set(left, topY, left + wd, topY + ht);
                cv.drawRoundRect(rf, dp(8), dp(8), p);
                p.clearShadowLayer();
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(2)); p.setColor(GOLD);
                rf.set(left + dp(1), topY + dp(1), left + wd - dp(1), topY + ht - dp(1));
                cv.drawRoundRect(rf, dp(7), dp(7), p);
                p.setStyle(Paint.Style.FILL); p.setColor(0x59000000);
                rf.set(left + wd / 2f - dp(1), topY + dp(9), left + wd / 2f + dp(1), topY + ht - dp(9));
                cv.drawRoundRect(rf, dp(2), dp(2), p);
            }
        }

        private boolean hit(float x, float y, float hx) {
            float left = hx - dp(13);
            return x >= left && x <= left + px(26) && y >= dp(6) && y <= dp(6) + px(32);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (dur <= 0) return false;
            int w = Math.max(1, getWidth());
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    float x = e.getX(), y = e.getY();
                    boolean hs = hit(x, y, w * (float) (start / dur)), he = hit(x, y, w * (float) (end / dur));
                    if (!hs && !he) return false;
                    active = he ? 2 : 1;
                    pauseMedia();
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (active == 0) return false;
                    float f = clamp(e.getX() / w, 0f, 1f);
                    double tt = f * dur, gap = Math.min(1, dur);
                    if (active == 1) { start = clampD(tt, 0, end - gap); seekTo(start); }
                    else { end = clampD(tt, start + gap, dur); seekTo(Math.max(start, end - 0.05)); }
                    renderTrim();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    active = 0;
                    return true;
                default:
                    return true;
            }
        }
    }
}
