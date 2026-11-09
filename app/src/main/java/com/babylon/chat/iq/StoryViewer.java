package com.babylon.chat.iq;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ImageSpan;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * عارض القصص (#storyViewer في الـ HTML) بنفس التصميم والحركة:
 *  - فتح كنافذة منبثقة ناعمة: الخلفية تظهر 620ms والمسرح يكبر من .88 إلى 1 خلال 720ms (cubic-bezier(.18,.78,.22,1))
 *  - قبل الفتح: قوس ذهبي دوّار فوق حلقة القصة (StoryAvatarView.launching) 220ms ثم يبقى ثانية بعد الفتح
 *  - أشرطة التقدم 3dp، شريط علوي (صورة + اسم + وقت + إغلاق)، لمس يسار=التالي / يمين=السابق، ضغطة مطوّلة = إيقاف
 *  - سحب للأسفل للإغلاق، تعليق، شريط ردّ/تفاعل للمشاهد، وإحصاءات المشاهدات وأزرار تثبيت/حذف لصاحب القصة
 */
public final class StoryViewer {

    public interface Listener {
        void onClosed();
        void openChat(String uid);
        void onStoriesChanged(long storyUntil);
    }

    private static final class Story {
        String id = "", imageUrl = "", videoUrl = "", caption = "";
        long createdAt, expiresAt;
        boolean pinned;
        double duration;
    }

    private static final float S = 1.041f;            // --bb-s في الـ HTML
    private static final int SDUR = 5000;              // مدة قصة الصورة
    private static StoryViewer current;

    // ------------------------------------------------------------ واجهة ثابتة
    public static boolean handleBack() {
        if (current == null) return false;
        current.close();
        return true;
    }

    /** آخر ما شوهد من قصص مستخدم (expiresAt الأكبر). */
    public static boolean isSeen(Context c, String me, String uid, long storyUntil) {
        if (c == null || me == null || uid == null || storyUntil <= 0) return false;
        try { return c.getSharedPreferences("story_seen_" + me, Context.MODE_PRIVATE).getLong(uid, 0L) >= storyUntil; }
        catch (Throwable t) { return false; }
    }

    /** ضغطة على أفاتار قصة: قوس دوّار 220ms ثم يُفتح العارض ويبقى القوس ثانية بعد الفتح (launchStoryWithGlow). */
    public static void launch(final Activity act, final FirebaseFirestore db, final String me, final StoryAvatarView av,
                              final String uid, final String name, final String letter, final Bitmap photo,
                              final boolean blue, final Listener l) {
        if (act == null || db == null || me == null || uid == null || current != null) return;
        if (av != null) { if (av.isLaunching()) return; av.launching(true); }
        final Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(() -> {
            try {
                final StoryViewer sv = new StoryViewer(act, db, me, uid, name, letter, photo, blue, l);
                sv.open(new Runnable() {          // بعد بدء الفتح: يبقى المؤشر ثانية كاملة
                    @Override public void run() {
                        if (av != null) h.postDelayed(() -> av.launching(false), 1000);
                    }
                }, new Runnable() {               // فشل/لا قصص: يختفي فوراً
                    @Override public void run() { if (av != null) av.launching(false); }
                });
            } catch (Throwable t) {
                if (av != null) av.launching(false);
            }
        }, 220);
    }

    // ------------------------------------------------------------ الحالة
    private final Activity act;
    private final FirebaseFirestore db;
    private final String me, uid, name, letter;
    private final Bitmap photo;
    private final boolean blue, mine;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newFixedThreadPool(2);
    private final float dens;

    private final List<Story> list = new ArrayList<>();
    private int idx = 0;
    private long t = 0, dur = SDUR;
    private boolean paused, hold, closed, videoReady, videoEnded, swipeActive, swipeDragging, heartLong;
    private String react;
    private int loadToken = 0, reactToken = 0;
    private int sat = 0, sab = 0;

    // الواجهة
    private FrameLayout overlay, stage;
    private LinearLayout bars, top, foot, replyRow, reactsRow, pickerBox;
    private View touch, footGrad;
    private ImageView img, heartImg;
    private VideoView vid;
    private TextView nameTv, timeTv, capTv, statsTv, heartTxt;
    private StoryAvatarView avatar;
    private EditText replyIn;
    private FrameLayout heartBtn;
    private LinearLayout sendBtn;
    private ImageView pinBtn;
    private int oldSb, oldNb;

    private float dX, dY, lastY;
    private long downAt, lastMoveAt;
    private int side;

    private StoryViewer(Activity act, FirebaseFirestore db, String me, String uid, String name, String letter,
                        Bitmap photo, boolean blue, Listener l) {
        this.act = act; this.db = db; this.me = me; this.uid = uid;
        this.name = name == null ? "" : name;
        this.letter = (letter == null || letter.isEmpty()) ? "?" : letter;
        this.photo = photo; this.blue = blue; this.listener = l;
        this.mine = me.equals(uid);
        this.dens = act.getResources().getDisplayMetrics().density;
    }

    private int dp(float v) { return Math.round(v * dens); }

    // ------------------------------------------------------------ تحميل القصص (loadStories + openStories)
    private void open(final Runnable onOpened, final Runnable onFail) {
        db.collection("stories").whereEqualTo("userId", uid).get()
            .addOnSuccessListener(snap -> {
                long now = System.currentTimeMillis();
                for (DocumentSnapshot d : snap.getDocuments()) {
                    Long ex = d.getLong("expiresAt"), cr = d.getLong("createdAt");
                    if (ex == null || ex <= now) continue;
                    Story s = new Story();
                    s.id = d.getId();
                    s.imageUrl = nz(d.getString("imageUrl"));
                    s.videoUrl = nz(d.getString("videoUrl"));
                    s.caption = nz(d.getString("caption"));
                    s.createdAt = cr == null ? 0 : cr; s.expiresAt = ex;
                    Boolean pn = d.getBoolean("pinned"); s.pinned = pn != null && pn;
                    Double du = d.getDouble("duration"); s.duration = du == null ? 0 : du;
                    list.add(s);
                }
                if (list.isEmpty()) {
                    Toast.makeText(act, "لا توجد قصص حالياً", Toast.LENGTH_SHORT).show();
                    if (onFail != null) onFail.run();
                    return;
                }
                Collections.sort(list, new Comparator<Story>() {      // الأقدم أولاً (reverse بعد الترتيب التنازلي)
                    @Override public int compare(Story a, Story b) { return Long.compare(a.createdAt, b.createdAt); }
                });
                int start = 0;
                if (!mine) {
                    long seen = 0;
                    try { seen = act.getSharedPreferences("story_seen_" + me, Context.MODE_PRIVATE).getLong(uid, 0L); } catch (Throwable ignored) { }
                    for (int k = 0; k < list.size(); k++) if (list.get(k).expiresAt > seen) { start = k; break; }
                }
                idx = start;
                try { build(); } catch (Throwable e) { if (onFail != null) onFail.run(); return; }
                if (onOpened != null) onOpened.run();
            })
            .addOnFailureListener(e -> {
                Toast.makeText(act, "تعذر تحميل القصة، حاول مرة أخرى", Toast.LENGTH_SHORT).show();
                if (onFail != null) onFail.run();
            });
    }

    private static String nz(String s) { return s == null ? "" : s; }

    // ------------------------------------------------------------ بناء الواجهة
    private void computeInsets(ViewGroup decor) {
        int topInset = 0, botInset = 0;
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                WindowInsets wi = decor.getRootWindowInsets();
                if (wi != null) { topInset = wi.getSystemWindowInsetTop(); botInset = wi.getStableInsetBottom(); }
            }
        } catch (Throwable ignored) { }
        if (topInset <= 0) {
            int rid = act.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (rid > 0) topInset = act.getResources().getDimensionPixelSize(rid);
        }
        int[] xy = new int[2];
        try { decor.getLocationOnScreen(xy); } catch (Throwable ignored) { }
        sat = Math.max(0, topInset - xy[1]);            // إن كان المحتوى أصلاً تحت شريط الحالة فلا إزاحة إضافية
        sab = Math.max(dp(24), botInset);               // --sv-sab = max(safe-area, nav, 24px)
    }

    private GradientDrawable round(int fill, float r, int stroke, float strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(r));
        if (stroke != 0) g.setStroke(Math.max(1, dp(strokeDp)), stroke);
        return g;
    }

    private TextView tv(String text, float sp, int color, boolean bold) {
        TextView v = new TextView(act);
        v.setText(text);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(null, Typeface.BOLD);
        v.setIncludeFontPadding(false);
        return v;
    }

    private void build() {
        final ViewGroup decor = (ViewGroup) act.findViewById(android.R.id.content);
        computeInsets(decor);

        overlay = new FrameLayout(act);
        overlay.setBackgroundColor(Color.TRANSPARENT);
        overlay.setClickable(true);
        overlay.setFocusable(true);
        overlay.setClipChildren(true);
        if (Build.VERSION.SDK_INT >= 21) overlay.setTranslationZ(dp(200));

        int sw = act.getResources().getDisplayMetrics().widthPixels;
        stage = new FrameLayout(act);
        stage.setBackgroundColor(Color.BLACK);
        stage.setClipChildren(true);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(Math.min(sw, dp(480)), ViewGroup.LayoutParams.MATCH_PARENT);
        sp.gravity = Gravity.CENTER_HORIZONTAL;
        overlay.addView(stage, sp);

        // --- الوسائط (.sv-img)
        FrameLayout media = new FrameLayout(act);
        img = new ImageView(act);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        media.addView(img, new FrameLayout.LayoutParams(-1, -1));
        vid = new VideoView(act);
        vid.setVisibility(View.INVISIBLE);
        FrameLayout.LayoutParams vp = new FrameLayout.LayoutParams(-1, -1);
        vp.gravity = Gravity.CENTER;
        media.addView(vid, vp);
        stage.addView(media, new FrameLayout.LayoutParams(-1, -1));

        // --- طبقة اللمس (.sv-tap + سحب الإغلاق)
        touch = new View(act);
        touch.setOnTouchListener((v, e) -> onStageTouch(e));
        stage.addView(touch, new FrameLayout.LayoutParams(-1, -1));

        // --- التعليق (.sv-cap)
        capTv = tv("", 13f * S, Color.WHITE, false);
        capTv.setLineSpacing(0f, 1.2f);
        capTv.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        capTv.setTextDirection(View.TEXT_DIRECTION_RTL);
        capTv.setGravity(Gravity.START);
        capTv.setPadding(dp(11), dp(8), dp(11), dp(8));
        capTv.setBackground(round(0x6B0F1218, 12, 0x38FFFFFF, 1));
        capTv.setVisibility(View.GONE);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        cp.leftMargin = dp(14); cp.rightMargin = dp(14); cp.bottomMargin = dp(72) + sab;
        stage.addView(capTv, cp);

        // --- التذييل (.sv-foot): تدرّج + ردّ/تفاعل
        foot = new LinearLayout(act);
        foot.setOrientation(LinearLayout.VERTICAL);
        foot.setPadding(dp(12), dp(30), dp(12), dp(16) + sab);
        GradientDrawable fg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0xE0000000});
        foot.setBackground(fg);
        stage.addView(foot, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        if (mine) buildOwnerFoot(); else buildViewerFoot();

        // --- أشرطة التقدم (.sv-bars)
        bars = new LinearLayout(act);
        bars.setOrientation(LinearLayout.HORIZONTAL);
        bars.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        bars.setPadding(dp(10), dp(10) + sat, dp(10), 0);
        stage.addView(bars, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // --- الشريط العلوي (.sv-top)
        top = new LinearLayout(act);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(10), dp(10), dp(10), dp(10));
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x99000000, 0x00000000}));
        buildTop();
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        tp.topMargin = dp(14) + sat;
        stage.addView(top, tp);

        // --- ألوان أشرطة النظام أثناء العرض (setStorySystemBars)
        try {
            android.view.Window w = act.getWindow();
            oldSb = w.getStatusBarColor(); oldNb = w.getNavigationBarColor();
            w.setStatusBarColor(Color.BLACK); w.setNavigationBarColor(Color.BLACK);
        } catch (Throwable ignored) { }

        decor.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        current = this;

        // --- حركة الفتح: .story-opening
        ValueAnimator bg = ValueAnimator.ofInt(0, 255);
        bg.setDuration(620);
        bg.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        bg.addUpdateListener(a -> { if (!swipeDragging && overlay != null) overlay.setBackgroundColor(Color.argb((Integer) a.getAnimatedValue(), 0, 0, 0)); });
        bg.start();
        stage.setScaleX(.88f); stage.setScaleY(.88f); stage.setAlpha(.35f);
        stage.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(720)
            .setInterpolator(new PathInterpolator(.18f, .78f, .22f, 1f)).start();

        showStory();
        handler.postDelayed(tick, 50);
    }

    private void buildTop() {
        // معلومات صاحب القصة
        LinearLayout info = new LinearLayout(act);
        info.setOrientation(LinearLayout.HORIZONTAL);
        info.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        avatar = new StoryAvatarView(act);
        avatar.set(letter, photo, blue).ring(false, false).shadow(false).textSp(15f * S);
        avatar.setOnClickListener(v -> { if (!mine) { close(); if (listener != null) listener.openChat(uid); } });
        info.addView(avatar, new LinearLayout.LayoutParams(dp(36 * S), dp(36 * S)));
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        nameTv = tv(mine ? "قصتي" : (name.isEmpty() ? "مستخدم" : name), 14f * S, Color.WHITE, true);
        nameTv.setGravity(Gravity.START);
        timeTv = tv("", 11f * S, Color.WHITE, false);
        timeTv.setAlpha(.8f);
        timeTv.setGravity(Gravity.START);
        col.addView(nameTv, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.topMargin = dp(2);
        col.addView(timeTv, tp);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
        cp.setMarginStart(dp(10));
        info.addView(col, cp);
        top.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));

        // أزرار صاحب القصة: تثبيت / حذف (.sv-action)
        if (mine) {
            pinBtn = action(R.drawable.ic_sv_pin, 0x73F5CF55, 0x3D916708);
            pinBtn.setOnClickListener(v -> togglePin());
            ImageView del = action(R.drawable.ic_sv_trash, 0x66FF6464, 0x4D781419);
            del.setOnClickListener(v -> confirmDelete());
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(34 * S), dp(34 * S));
            ap.setMarginEnd(dp(5));
            top.addView(pinBtn, ap);
            LinearLayout.LayoutParams ap2 = new LinearLayout.LayoutParams(dp(34 * S), dp(34 * S));
            ap2.setMarginEnd(dp(7));
            top.addView(del, ap2);
        }

        // زر الإغلاق
        ImageView close = new ImageView(act);
        close.setImageResource(R.drawable.ic_sv_close);
        close.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int cpad = dp((36 * S - 22 * S) / 2f);
        close.setPadding(cpad, cpad, cpad, cpad);
        close.setOnClickListener(v -> close());
        top.addView(close, new LinearLayout.LayoutParams(dp(36 * S), dp(36 * S)));
    }

    private ImageView action(int res, int stroke, int fill) {
        ImageView b = new ImageView(act);
        b.setImageResource(res);
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = dp((34 * S - 18 * S) / 2f);
        b.setPadding(pad, pad, pad, pad);
        b.setBackground(round(fill, 10, stroke, 1));
        return b;
    }

    private void buildOwnerFoot() {
        // لا شيء داخل التذييل لصاحب القصة؛ شارة الإحصاءات تُوضع فوقه (.sv-story-stats)
        foot.setMinimumHeight(dp(46) + sab);
        statsTv = tv("", 12f * S, Color.WHITE, true);
        statsTv.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        statsTv.setGravity(Gravity.CENTER);
        statsTv.setPadding(dp(13), dp(7), dp(13), dp(7));
        statsTv.setBackground(round(0xB8080C12, 999, 0x4DFFFFFF, 1));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = dp(18) + sab;
        stage.addView(statsTv, lp);
        setStats(0, 0);
    }

    private void buildViewerFoot() {
        foot.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        // منتقي التفاعلات (يظهر بضغطة مطوّلة على القلب)
        pickerBox = new LinearLayout(act);
        pickerBox.setOrientation(LinearLayout.VERTICAL);
        pickerBox.setGravity(Gravity.CENTER);
        pickerBox.setPadding(dp(10), dp(7), dp(10), dp(7));
        pickerBox.setBackground(round(0x6B12161C, 18, 0x2EFFFFFF, 1));
        pickerBox.setVisibility(View.GONE);
        final String[] pk = {"😂", "❤️", "👍🏻", "😭", "✨", "😍", "😮", "😢", "🙏", "🔥"};
        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            for (int k = 0; k < 5; k++) {
                final String e = pk[r * 5 + k];
                TextView b = tv(e, 24f * S, Color.WHITE, false);
                b.setGravity(Gravity.CENTER);
                b.setOnClickListener(v -> setReaction(react != null && react.equals(e) ? null : e));
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(36 * S), dp(36 * S));
                bp.setMargins(dp(4), dp(2), dp(4), dp(2));
                row.addView(b, bp);
            }
            pickerBox.addView(row, new LinearLayout.LayoutParams(-2, -2));
        }
        LinearLayout.LayoutParams pbp = new LinearLayout.LayoutParams(-2, -2);
        pbp.gravity = Gravity.CENTER_HORIZONTAL; pbp.bottomMargin = dp(10);
        foot.addView(pickerBox, pbp);

        // صف الإيموجي السريعة (يظهر عند التركيز على حقل الرد)
        reactsRow = new LinearLayout(act);
        reactsRow.setOrientation(LinearLayout.HORIZONTAL);
        reactsRow.setGravity(Gravity.CENTER);
        reactsRow.setPadding(dp(10), dp(7), dp(10), dp(7));
        reactsRow.setBackground(round(0x6B12161C, 18, 0x2EFFFFFF, 1));
        reactsRow.setVisibility(View.GONE);
        for (final String e : new String[]{"😂", "❤️", "👍🏻", "😭", "✨"}) {
            TextView b = tv(e, 24f * S, Color.WHITE, false);
            b.setGravity(Gravity.CENTER);
            b.setTag(e);
            b.setOnClickListener(v -> sendEmoji(e));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(32 * S), dp(32 * S));
            bp.setMargins(dp(4), 0, dp(4), 0);
            reactsRow.addView(b, bp);
        }
        LinearLayout.LayoutParams rrp = new LinearLayout.LayoutParams(-2, -2);
        rrp.gravity = Gravity.CENTER_HORIZONTAL; rrp.bottomMargin = dp(10);
        foot.addView(reactsRow, rrp);

        // صف الرد: [قلب] [حقل] [إرسال]
        replyRow = new LinearLayout(act);
        replyRow.setOrientation(LinearLayout.HORIZONTAL);
        replyRow.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        replyRow.setGravity(Gravity.CENTER_VERTICAL);

        heartBtn = new FrameLayout(act);
        heartImg = new ImageView(act);
        heartImg.setImageResource(R.drawable.ic_sv_heart);
        heartImg.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams hip = new FrameLayout.LayoutParams(dp(29 * S), dp(29 * S), Gravity.CENTER);
        heartBtn.addView(heartImg, hip);
        heartTxt = tv("", 28f * S, Color.WHITE, false);
        heartTxt.setGravity(Gravity.CENTER);
        heartTxt.setVisibility(View.GONE);
        heartBtn.addView(heartTxt, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        final Runnable longPress = () -> { heartLong = true; togglePicker(); };
        heartBtn.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: heartLong = false; handler.postDelayed(longPress, 500); return true;
                case MotionEvent.ACTION_UP:
                    handler.removeCallbacks(longPress);
                    if (!heartLong) { hidePicker(); setReaction(react != null ? null : "❤️"); }
                    return true;
                case MotionEvent.ACTION_CANCEL: handler.removeCallbacks(longPress); return true;
                default: return true;
            }
        });
        replyRow.addView(heartBtn, new LinearLayout.LayoutParams(dp(42 * S), dp(42 * S)));

        replyIn = new EditText(act);
        replyIn.setHint("رد على القصة...");
        replyIn.setHintTextColor(0x99FFFFFF);
        replyIn.setTextColor(Color.WHITE);
        replyIn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * S);
        replyIn.setSingleLine(true);
        replyIn.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        replyIn.setImeOptions(EditorInfo.IME_ACTION_SEND);
        replyIn.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        replyIn.setTextDirection(View.TEXT_DIRECTION_RTL);
        replyIn.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        replyIn.setBackground(round(0x66000000, 22, 0x66FFFFFF, 1));
        replyIn.setPadding(dp(14), dp(11), dp(14), dp(11));
        replyIn.setIncludeFontPadding(false);
        replyIn.setOnFocusChangeListener((v, has) -> {
            paused = has;
            reactsRow.setVisibility(has ? View.VISIBLE : View.GONE);
            if (!has) hidePicker();
        });
        replyIn.setOnEditorActionListener((v, a, ev) -> { if (a == EditorInfo.IME_ACTION_SEND) { sendReply(); return true; } return false; });
        replyIn.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { replyChanged(); }
        });
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, -2, 1f);
        ip.setMarginStart(dp(8));
        replyRow.addView(replyIn, ip);

        // زر الإرسال (.sv-pill)
        sendBtn = new LinearLayout(act);
        sendBtn.setGravity(Gravity.CENTER);
        sendBtn.setBackground(round(0x33FFFFFF, 20, 0, 0));
        ImageView si = new ImageView(act);
        si.setImageResource(R.drawable.ic_sv_send);
        sendBtn.addView(si, new LinearLayout.LayoutParams(dp(20), dp(20)));
        sendBtn.setVisibility(View.GONE);
        sendBtn.setOnClickListener(v -> sendReply());
        LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(dp(46), dp(40));
        sbp.setMarginStart(dp(8));
        replyRow.addView(sendBtn, sbp);

        foot.addView(replyRow, new LinearLayout.LayoutParams(-1, -2));
    }

    private void replyChanged() {
        boolean typed = replyIn != null && replyIn.getText().toString().trim().length() > 0;
        if (sendBtn != null) sendBtn.setVisibility(typed ? View.VISIBLE : View.GONE);
        if (heartBtn != null) heartBtn.setVisibility(typed ? View.GONE : View.VISIBLE);
        if (typed) hidePicker();
    }

    private void togglePicker() { if (pickerBox == null) return; pickerBox.setVisibility(pickerBox.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); }
    private void hidePicker() { if (pickerBox != null) pickerBox.setVisibility(View.GONE); }

    // ------------------------------------------------------------ عرض قصة (showStory)
    private void showStory() {
        if (idx < 0 || idx >= list.size()) { close(); return; }
        final Story s = list.get(idx);
        t = 0; videoReady = false; videoEnded = false;
        react = null; reactToken++;
        dur = SDUR;
        final int token = ++loadToken;

        // شريط التقدم
        bars.removeAllViews();
        for (int k = 0; k < list.size(); k++) {
            FrameLayout b = new FrameLayout(act);
            b.setBackground(round(0x4DFFFFFF, 2, 0, 0));
            b.setClipToOutline(true);
            b.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(2)); }
            });
            View f = new View(act);
            f.setBackgroundColor(Color.WHITE);
            f.setScaleX(0f);
            b.addView(f, new FrameLayout.LayoutParams(-1, -1));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(3), 1f);
            if (k > 0) bp.setMarginEnd(0);
            bp.setMarginStart(k == list.size() - 1 ? 0 : dp(3));
            bars.addView(b, bp);
        }

        // الوسائط
        img.setImageDrawable(null);
        if (!s.videoUrl.isEmpty()) {
            dur = (long) (Math.min(30, s.duration > 0 ? s.duration : 30) * 1000);
            vid.setVisibility(View.INVISIBLE);
            if (!s.imageUrl.isEmpty()) loadImage(s.imageUrl, token);
            try {
                vid.stopPlayback();
                vid.setOnPreparedListener(mp -> {
                    if (token != loadToken) return;
                    try { int d = mp.getDuration(); if (d > 0) dur = Math.min(30000, d); } catch (Throwable ignored) { }
                    videoReady = true;
                    vid.setVisibility(View.VISIBLE);
                    if (!paused && !hold) vid.start();
                });
                vid.setOnCompletionListener(mp -> { if (token == loadToken) videoEnded = true; });
                vid.setOnErrorListener((mp, what, extra) -> { if (token == loadToken) { vid.setVisibility(View.INVISIBLE); dur = SDUR; videoReady = false; videoEnded = false; s.videoUrl = ""; } return true; });
                vid.setVideoURI(Uri.parse(s.videoUrl));
            } catch (Throwable ignored) { }
        } else {
            try { vid.stopPlayback(); } catch (Throwable ignored) { }
            vid.setVisibility(View.INVISIBLE);
            loadImage(s.imageUrl, token);
        }

        // التعليق
        capTv.setText(s.caption);
        capTv.setVisibility(s.caption.isEmpty() ? View.GONE : View.VISIBLE);

        refreshTime(s);
        updateBars();
        markSeen(s);

        if (mine) {
            if (pinBtn != null) pinBtn.setAlpha(s.pinned ? 1f : .65f);
            loadOwnerStats(s);
        } else {
            applyReaction(null);
            if (replyIn != null) { replyIn.setText(""); replyChanged(); }
            registerView(s);
        }
    }

    private void refreshTime(Story s) {
        SpannableStringBuilder sb = new SpannableStringBuilder(ago(s.createdAt));
        if (mine) {
            if (s.pinned) {
                sb.append(" · ");
                try {
                    Drawable d = act.getResources().getDrawable(R.drawable.ic_mm_pin).mutate();
                    if (Build.VERSION.SDK_INT >= 21) d.setTint(Color.WHITE);
                    int sz = dp(12 * S);
                    d.setBounds(0, 0, sz, sz);
                    int a = sb.length();
                    sb.append(" ");
                    sb.setSpan(new ImageSpan(d, ImageSpan.ALIGN_BASELINE), a, a + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } catch (Throwable ignored) { sb.append("مثبتة"); }
            } else if (s.expiresAt > System.currentTimeMillis()) {
                sb.append(" · تنتهي بعد ").append(leftTxt(s.expiresAt));
            } else {
                sb.append(" · أرشيف");
            }
        }
        timeTv.setText(sb);
    }

    private static String ago(long ts) {
        long m = Math.max(0, (System.currentTimeMillis() - ts) / 60000);
        if (m < 1) return "الآن";
        if (m < 60) return "قبل " + m + " د";
        if (m < 1440) return "قبل " + (m / 60) + " س";
        return "قبل " + (m / 1440) + " يوم";
    }

    private static String leftTxt(long ts) {
        long m = Math.max(1, (ts - System.currentTimeMillis()) / 60000);
        return m < 60 ? m + " د" : (m / 60) + " س";
    }

    private void markSeen(Story s) {
        try {
            SharedPreferences p = act.getSharedPreferences("story_seen_" + me, Context.MODE_PRIVATE);
            if (p.getLong(uid, 0L) < s.expiresAt) p.edit().putLong(uid, s.expiresAt).apply();
        } catch (Throwable ignored) { }
    }

    private void updateBars() {
        if (bars == null) return;
        for (int k = 0; k < bars.getChildCount(); k++) {
            View b = bars.getChildAt(k);
            if (!(b instanceof FrameLayout) || ((FrameLayout) b).getChildCount() == 0) continue;
            View f = ((FrameLayout) b).getChildAt(0);
            float frac = k < idx ? 1f : (k == idx ? Math.min(1f, t / (float) (dur <= 0 ? SDUR : dur)) : 0f);
            f.setPivotX(f.getWidth());          // الصفحة RTL: الشريط يمتلئ من اليمين
            f.setPivotY(0f);
            f.setScaleX(frac);
        }
    }

    // ------------------------------------------------------------ المؤقّت (tick كل 50ms)
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (closed) return;
            try {
                Story s = (idx >= 0 && idx < list.size()) ? list.get(idx) : null;
                boolean isVid = s != null && !s.videoUrl.isEmpty();
                if (paused || hold) {
                    if (isVid && videoReady && vid.isPlaying()) vid.pause();
                } else if (isVid) {
                    if (videoReady && !videoEnded && !vid.isPlaying()) vid.start();
                    if (videoReady) t = vid.getCurrentPosition();
                    updateBars();
                    if (videoEnded || (videoReady && t >= dur - 50)) { nav(1); return; }
                } else {
                    t += 50; updateBars();
                    if (t >= SDUR) { nav(1); return; }
                }
            } catch (Throwable ignored) { }
            handler.postDelayed(this, 50);
        }
    };

    private void nav(int d) {
        int n = idx + d;
        if (n < 0) {
            t = 0;
            try { if (videoReady) vid.seekTo(0); } catch (Throwable ignored) { }
            updateBars();
            handler.removeCallbacks(tick); handler.postDelayed(tick, 50);
            return;
        }
        if (n >= list.size()) { close(); return; }
        idx = n;
        showStory();
        handler.removeCallbacks(tick); handler.postDelayed(tick, 50);
    }

    // ------------------------------------------------------------ اللمس: tap زوايا + سحب للأسفل
    private int zone(float x, float y) {
        float w = touch.getWidth(), h = touch.getHeight();
        if (y < dp(80) + sat || y > h - dp(150)) return 0;
        if (x < w * .38f) return 1;      // يسار: التالي
        if (x > w * .62f) return -1;     // يمين: السابق
        return 0;
    }

    private boolean onStageTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dX = e.getRawX(); dY = e.getRawY(); lastY = dY; downAt = System.currentTimeMillis(); lastMoveAt = downAt;
                swipeActive = true; swipeDragging = false;
                side = zone(e.getX(), e.getY());
                if (side != 0) hold = true;
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (!swipeActive) return true;
                float dy = e.getRawY() - dY, dx = e.getRawX() - dX;
                if (!swipeDragging) {
                    if (dy < dp(5) || Math.abs(dy) <= Math.abs(dx) * 1.12f) return true;
                    swipeDragging = true; hold = true;
                    stage.animate().cancel();
                }
                if (dy < 0) return true;
                float H = Math.max(1, overlay.getHeight());
                stage.setTranslationY(dy);
                stage.setAlpha(Math.max(.35f, 1f - dy / (H * 1.15f)));
                overlay.setBackgroundColor(Color.argb((int) (255 * Math.max(0f, 1f - dy / (H * .95f))), 0, 0, 0));
                lastY = e.getRawY(); lastMoveAt = System.currentTimeMillis();
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (!swipeActive) return true;
                swipeActive = false;
                if (swipeDragging) {
                    swipeDragging = false;
                    float dy = Math.max(0f, e.getRawY() - dY);
                    boolean fast = System.currentTimeMillis() - lastMoveAt < 180 && dy > dp(55);
                    hold = false;
                    if (dy > Math.min(dp(150), overlay.getHeight() * .2f) || fast) {
                        float H = overlay.getHeight();
                        stage.animate().translationY(Math.max(dy + dp(80), H * .85f)).alpha(.15f).setDuration(240)
                            .setInterpolator(new PathInterpolator(.2f, .8f, .2f, 1f))
                            .setListener(new AnimatorListenerAdapter() { @Override public void onAnimationEnd(Animator a) { close(); } }).start();
                    } else {
                        stage.animate().translationY(0f).alpha(1f).setDuration(240)
                            .setInterpolator(new PathInterpolator(.2f, .8f, .2f, 1f)).setListener(null).start();
                        overlay.setBackgroundColor(Color.BLACK);
                    }
                    return true;
                }
                hold = false;
                if (side != 0 && System.currentTimeMillis() - downAt < 250) nav(side);
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                swipeActive = false; swipeDragging = false; hold = false;
                stage.animate().translationY(0f).alpha(1f).setDuration(200).setListener(null).start();
                overlay.setBackgroundColor(Color.BLACK);
                return true;
            default: return true;
        }
    }

    // ------------------------------------------------------------ تحميل الصور
    private void loadImage(final String src, final int token) {
        if (src == null || src.isEmpty()) return;
        IO.execute(() -> {
            Bitmap bmp = null;
            try {
                byte[] data;
                if (src.startsWith("data:")) {
                    String b64 = src.substring(src.indexOf(',') + 1);
                    data = Base64.decode(b64, Base64.DEFAULT);
                } else {
                    HttpURLConnection c = (HttpURLConnection) new URL(src).openConnection();
                    c.setConnectTimeout(12000); c.setReadTimeout(20000);
                    InputStream is = c.getInputStream();
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384]; int n;
                    while ((n = is.read(buf)) > 0) { bos.write(buf, 0, n); if (bos.size() > 25 * 1024 * 1024) break; }
                    is.close();
                    data = bos.toByteArray();
                }
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, o);
                int sample = 1, mx = Math.max(o.outWidth, o.outHeight);
                while (mx / sample > 1800) sample *= 2;
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = sample;
                bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o2);
            } catch (Throwable ignored) { }
            final Bitmap fb = bmp;
            handler.post(() -> { if (!closed && token == loadToken && fb != null) img.setImageBitmap(fb); });
        });
    }

    // ------------------------------------------------------------ المشاهدات والتفاعل
    private void registerView(final Story s) {
        final int my = reactToken;
        final String cached = readCached(s.id);
        if (cached != null) { react = cached; applyReaction(cached); }
        Map<String, Object> d = new HashMap<>();
        d.put("uid", me); d.put("at", System.currentTimeMillis());
        final com.google.firebase.firestore.DocumentReference ref =
                db.collection("stories").document(s.id).collection("views").document(me);
        ref.set(d, SetOptions.merge());
        ref.get().addOnSuccessListener(doc -> {
            if (closed || my != reactToken || idx >= list.size() || list.get(idx) != s || !doc.exists()) return;
            String saved = doc.getString("reaction");
            if (saved != null && !saved.isEmpty()) { react = saved; cacheReaction(s.id, saved); applyReaction(saved); hidePicker(); }
        });
    }

    private void setReaction(String e) {
        if (mine || idx >= list.size()) return;
        final Story s = list.get(idx);
        String prev = react;
        if ((prev == null && e == null) || (prev != null && prev.equals(e))) return;
        final int my = ++reactToken;
        react = e;
        cacheReaction(s.id, e);
        applyReaction(e);
        hidePicker();
        Map<String, Object> data = new HashMap<>();
        data.put("uid", me); data.put("at", System.currentTimeMillis());
        data.put("reaction", e != null ? (Object) e : FieldValue.delete());
        final String fprev = prev;
        db.collection("stories").document(s.id).collection("views").document(me).set(data, SetOptions.merge())
            .addOnFailureListener(err -> {
                if (my != reactToken) return;
                cacheReaction(s.id, fprev);
                if (idx < list.size() && list.get(idx) == s) { react = fprev; applyReaction(fprev); }
                Toast.makeText(act, "تعذّر حفظ التفاعل. تحقق من اتصالك.", Toast.LENGTH_SHORT).show();
            });
    }

    private void applyReaction(String e) {
        if (heartImg == null) return;
        boolean isHeart = e != null && e.replace("\uFE0F", "").equals("\u2764");
        if (e == null) {
            heartImg.setVisibility(View.VISIBLE); heartImg.setImageResource(R.drawable.ic_sv_heart); heartTxt.setVisibility(View.GONE);
        } else if (isHeart) {
            heartImg.setVisibility(View.VISIBLE); heartImg.setImageResource(R.drawable.ic_sv_heart_fill); heartTxt.setVisibility(View.GONE);
        } else {
            heartImg.setVisibility(View.GONE); heartTxt.setText(e); heartTxt.setVisibility(View.VISIBLE);
        }
        if (reactsRow != null) {
            for (int k = 0; k < reactsRow.getChildCount(); k++) {
                View c = reactsRow.getChildAt(k);
                boolean on = e != null && e.equals(c.getTag());
                c.setBackground(on ? round(0x33FFFFFF, 10, 0x40FFFFFF, 1) : null);
            }
        }
    }

    private String readCached(String storyId) {
        try { return act.getSharedPreferences("story_react", Context.MODE_PRIVATE).getString(me + ":" + storyId, null); }
        catch (Throwable ignored) { return null; }
    }
    private void cacheReaction(String storyId, String e) {
        try {
            SharedPreferences.Editor ed = act.getSharedPreferences("story_react", Context.MODE_PRIVATE).edit();
            if (e != null) ed.putString(me + ":" + storyId, e); else ed.remove(me + ":" + storyId);
            ed.apply();
        } catch (Throwable ignored) { }
    }

    private void sendEmoji(String e) { sendMessage(e, "تم إرسال الإيموجي ✓", "تعذر إرسال الإيموجي", false); }

    private void sendReply() {
        if (replyIn == null) return;
        String text = replyIn.getText().toString().trim();
        if (text.isEmpty()) return;
        sendMessage(text, "تم إرسال ردك ✓", "تعذر إرسال الرد", true);
    }

    private void sendMessage(final String text, final String okMsg, final String errMsg, final boolean clear) {
        if (mine || idx >= list.size()) return;
        final Story s = list.get(idx);
        final String chatId = me.compareTo(uid) < 0 ? me + "__" + uid : uid + "__" + me;
        Map<String, Object> chat = new HashMap<>();
        chat.put("participants", Arrays.asList(me, uid));
        chat.put("updatedAt", System.currentTimeMillis());
        db.collection("chats").document(chatId).set(chat, SetOptions.merge()).addOnSuccessListener(v -> {
            Map<String, Object> m = new HashMap<>();
            m.put("senderId", me);
            m.put("text", text);
            m.put("storyReply", true);
            if (clear) {
                Map<String, Object> sd = new HashMap<>();
                sd.put("imageUrl", s.imageUrl); sd.put("videoUrl", s.videoUrl); sd.put("caption", s.caption); sd.put("storyId", s.id);
                m.put("storyReplyData", sd);
            }
            m.put("read", false);
            m.put("createdAt", System.currentTimeMillis());
            db.collection("chats").document(chatId).collection("messages").add(m)
                .addOnSuccessListener(r -> {
                    Toast.makeText(act, okMsg, Toast.LENGTH_SHORT).show();
                    if (clear && replyIn != null) { replyIn.setText(""); replyIn.clearFocus(); }
                })
                .addOnFailureListener(err -> Toast.makeText(act, errMsg, Toast.LENGTH_SHORT).show());
        }).addOnFailureListener(err -> Toast.makeText(act, errMsg, Toast.LENGTH_SHORT).show());
    }

    // ------------------------------------------------------------ صاحب القصة: إحصاءات / تثبيت / حذف
    private void setStats(int views, int reacts) {
        if (statsTv == null) return;
        SpannableStringBuilder sb = new SpannableStringBuilder("المشاهدات " + views);
        if (reacts > 0) {
            sb.append(" | ").append(String.valueOf(reacts)).append(" ");
            try {
                Drawable d = act.getResources().getDrawable(R.drawable.ic_sv_heart_mini).mutate();
                int sz = dp(14 * S);
                d.setBounds(0, 0, sz, sz);
                int a = sb.length();
                sb.append(" ");
                sb.setSpan(new ImageSpan(d, ImageSpan.ALIGN_BASELINE), a, a + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } catch (Throwable ignored) { sb.append("❤️"); }
        }
        statsTv.setText(sb);
    }

    private void loadOwnerStats(final Story s) {
        setStats(0, 0);
        db.collection("stories").document(s.id).collection("views").get().addOnSuccessListener(q -> {
            if (closed || idx >= list.size() || list.get(idx) != s) return;
            int r = 0;
            for (DocumentSnapshot d : q.getDocuments()) { String re = d.getString("reaction"); if (re != null && !re.isEmpty()) r++; }
            setStats(q.size(), r);
        });
    }

    private void togglePin() {
        if (idx >= list.size()) return;
        final Story s = list.get(idx);
        final boolean next = !s.pinned;
        s.pinned = next;
        if (pinBtn != null) pinBtn.setAlpha(next ? 1f : .65f);
        refreshTime(s);
        db.collection("stories").document(s.id).update("pinned", next).addOnFailureListener(e -> {
            s.pinned = !next;
            if (pinBtn != null) pinBtn.setAlpha(s.pinned ? 1f : .65f);
            refreshTime(s);
            Toast.makeText(act, "تعذر تحديث التثبيت", Toast.LENGTH_SHORT).show();
        });
    }

    private void confirmDelete() {
        if (idx >= list.size()) return;
        paused = true;
        final Story s = list.get(idx);
        new AlertDialog.Builder(act)
            .setTitle("حذف القصة")
            .setMessage("هل تريد حذف هذه القصة نهائياً؟")
            .setNegativeButton("إلغاء", (d, w) -> paused = false)
            .setOnCancelListener(d -> paused = false)
            .setPositiveButton("حذف", (d, w) -> {
                paused = false;
                db.collection("stories").document(s.id).delete().addOnSuccessListener(v -> {
                    list.remove(s);
                    syncMeta();
                    if (list.isEmpty()) { close(); return; }
                    if (idx >= list.size()) idx = list.size() - 1;
                    showStory();
                }).addOnFailureListener(e -> Toast.makeText(act, "تعذر حذف القصة", Toast.LENGTH_SHORT).show());
            }).show();
    }

    private void syncMeta() {
        long now = System.currentTimeMillis(), until = 0, at = 0;
        for (Story s : list) if (s.expiresAt > now) { until = Math.max(until, s.expiresAt); at = Math.max(at, s.createdAt); }
        Map<String, Object> u = new HashMap<>();
        u.put("storyUntil", until); u.put("storyAt", at);
        final long fu = until;
        db.collection("users").document(uid).set(u, SetOptions.merge());
        if (listener != null) listener.onStoriesChanged(fu);
    }

    // ------------------------------------------------------------ الإغلاق
    private void close() {
        if (closed) return;
        closed = true;
        handler.removeCallbacksAndMessages(null);
        try { vid.stopPlayback(); } catch (Throwable ignored) { }
        try { act.getWindow().setStatusBarColor(oldSb); act.getWindow().setNavigationBarColor(oldNb); } catch (Throwable ignored) { }
        try {
            if (replyIn != null) {
                android.view.inputmethod.InputMethodManager im =
                    (android.view.inputmethod.InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (im != null) im.hideSoftInputFromWindow(replyIn.getWindowToken(), 0);
            }
        } catch (Throwable ignored) { }
        try { ViewGroup p = (ViewGroup) overlay.getParent(); if (p != null) p.removeView(overlay); } catch (Throwable ignored) { }
        if (current == this) current = null;
        if (listener != null) listener.onClosed();
    }
}
