package com.babylon.chat.iq;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.provider.OpenableColumns;
import android.text.InputFilter;
import android.widget.ScrollView;
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
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
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

    /** أي حلقة تدعم القوس الدوّار (أفاتار القصص أو حلقة الملف الشخصي). */
    public interface Spin { void setLaunching(boolean on); boolean isLaunching(); }

    /** عنوان إشعار الرد على القصة (نفس /api/index في الموقع) مثل: https://موقعك/api/index — فارغ = بلا إشعار. */
    public static String NOTIFY_URL = "";
    private static final String CLD_CLOUD = "xuljmnmh", CLD_PRESET = "stories_unsigned";
    private static final int REQ_PICK = 1004;

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
    /** فتح قيد التحميل (القوس يدور ولم يُبنَ العارض بعد): الرجوع يلغيه. */
    private static boolean pendingLaunch, cancelLaunch;
    private static Spin pendingAv;

    public static boolean handleBack() {
        if (current == null) {
            if (pendingLaunch) {
                cancelLaunch = true;
                if (pendingAv != null) { try { pendingAv.setLaunching(false); } catch (Throwable ignored) { } }
                return true;
            }
            return false;
        }
        if (current.modal != null) { current.closeModal(); return true; }
        current.close();
        return true;
    }

    /** نتيجة اختيار ملف بديل لتعديل القصة (من Activity.onActivityResult). */
    public static boolean onActivityResult(int req, int res, Intent data) {
        if (req != REQ_PICK) return false;
        if (current != null && res == Activity.RESULT_OK && data != null && data.getData() != null) current.setPicked(data.getData());
        return true;
    }

    /** آخر ما شوهد من قصص مستخدم (expiresAt الأكبر). */
    public static boolean isSeen(Context c, String me, String uid, long storyUntil) {
        if (c == null || me == null || uid == null || storyUntil <= 0) return false;
        try { return c.getSharedPreferences("story_seen_" + me, Context.MODE_PRIVATE).getLong(uid, 0L) >= storyUntil; }
        catch (Throwable t) { return false; }
    }

    /** ضغطة على أفاتار قصة: قوس دوّار 220ms ثم يُفتح العارض ويبقى القوس ثانية بعد الفتح (launchStoryWithGlow). */
    public static void launch(final Activity act, final FirebaseFirestore db, final String me, final Spin av,
                              final String uid, final String name, final String letter, final Bitmap photo,
                              final boolean blue, final Listener l) {
        launch(act, db, me, av, uid, name, letter, photo, blue, null, null, l);
    }

    /** من الملف الشخصي (startViewer في الـ HTML): يشغّل القصص المحددة فقط (playIds) من الأقدم للأحدث، ويبدأ من startId. */
    public static void launch(final Activity act, final FirebaseFirestore db, final String me, final Spin av,
                              final String uid, final String name, final String letter, final Bitmap photo,
                              final boolean blue, final String startId, final java.util.Set<String> playIds, final Listener l) {
        if (act == null || db == null || me == null || uid == null || current != null) return;
        if (av != null) { if (av.isLaunching()) return; av.setLaunching(true); }
        pendingLaunch = true; cancelLaunch = false; pendingAv = av;
        final Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(() -> { pendingLaunch = false; }, 12000);   // أمان: لا يبقى الرجوع محجوزاً إن لم يكتمل التحميل
        h.postDelayed(() -> {
            try {
                if (cancelLaunch) { pendingLaunch = false; cancelLaunch = false; if (av != null) av.setLaunching(false); return; }
                final StoryViewer sv = new StoryViewer(act, db, me, uid, name, letter, photo, blue, l);
                sv.startId = startId; sv.playIds = playIds;
                sv.open(new Runnable() {          // بعد بدء الفتح: يبقى المؤشر ثانية كاملة
                    @Override public void run() {
                        pendingLaunch = false;
                        if (av != null) av.setLaunching(false);   // يتوقف الدوران فور ظهور القصة
                    }
                }, new Runnable() {               // فشل/لا قصص/إلغاء: يختفي فوراً
                    @Override public void run() { pendingLaunch = false; cancelLaunch = false; if (av != null) av.setLaunching(false); }
                });
            } catch (Throwable t) {
                pendingLaunch = false;
                if (av != null) av.setLaunching(false);
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

    private String startId; private java.util.Set<String> playIds;
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
    private FrameLayout modal;
    private boolean modalOpen;
    private Object backCb;               // OnBackInvokedCallback (API 33+) لإيماءة الرجوع
    private boolean navTinted;           // شريط الإيماءات مصبوغ بلون قائمة المشاهدين حالياً
    private int navUiBefore;             // أعلام واجهة النظام قبل الصبغ لاستعادتها
    private static boolean sMuted = false;               // كتم صوت القصص (يبقى بين القصص)
    private android.media.MediaPlayer curMp;             // مشغّل الفيديو الحالي لتطبيق الكتم فوراً
    // لوحة المفاتيح: ارتفاع الـ inset السفلي (نظام + لوحة) والثابت (النظام فقط) وآخر إزاحة مطبّقة
    private int kbBottom = 0, kbStable = 0;
    private float kbTarget = 0f;
    private boolean imeWasOpen = false;
    private Uri pickedUri;
    private TextView pickedName, saveBtnTv;
    private EditText capEt;

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
                    boolean keep = ex != null && (playIds != null ? playIds.contains(d.getId()) : ex > now);
                    if (!keep) continue;
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
                if (startId != null) {
                    for (int k = 0; k < list.size(); k++) if (startId.equals(list.get(k).id)) { start = k; break; }
                } else if (!mine) {
                    long seen = 0;
                    try { seen = act.getSharedPreferences("story_seen_" + me, Context.MODE_PRIVATE).getLong(uid, 0L); } catch (Throwable ignored) { }
                    for (int k = 0; k < list.size(); k++) if (list.get(k).expiresAt > seen) { start = k; break; }
                }
                idx = start;
                final Runnable go = () -> {
                    if (cancelLaunch) { cancelLaunch = false; if (onFail != null) onFail.run(); return; }
                    try { build(); } catch (Throwable e) { if (onFail != null) onFail.run(); return; }
                    if (onOpened != null) onOpened.run();
                };
                final String firstSrc = list.get(idx).imageUrl;
                if (firstSrc.isEmpty()) { go.run(); return; }
                // القوس الدوّار يبقى يدور أثناء التحميل المسبق؛ مهلة 1.2ث كي لا ينتظر المستخدم طويلاً على شبكة بطيئة
                final boolean[] fired = {false};
                final Runnable fire = () -> { if (fired[0]) return; fired[0] = true; go.run(); };
                handler.postDelayed(fire, 1200);
                IO.execute(() -> {
                    final Bitmap b = fetchBitmap(firstSrc);
                    handler.post(() -> {
                        if (!fired[0]) { preSrc = firstSrc; preBmp = b; }
                        handler.removeCallbacks(fire);
                        fire.run();
                    });
                });
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
        capTv.setBackground(round(0x6B0F1218, 12, 0, 0));
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

        // حدود الصورة/الفيديو: تمتد جزئياً خلف أزرار القصة (انظر fitMedia)
        final FrameLayout fMedia = media;
        final Runnable fitMedia = new Runnable() {
            boolean done;
            @Override public void run() {
                if (done || closed || stage == null || stage.getHeight() <= 0 || top.getHeight() <= 0 || foot.getHeight() <= 0) return;
                done = true;
                // الوسائط تمتد خلف الشريط العلوي (صورتي/المشاهدات) وخلف أزرار التذييل لكن بدون الوصول لحافتي الشاشة
                final int topR = Math.max(0, top.getTop());
                final int botR = Math.max(0, sab + dp(8));
                stage.post(() -> {
                    FrameLayout.LayoutParams mp = (FrameLayout.LayoutParams) fMedia.getLayoutParams();
                    mp.topMargin = topR; mp.bottomMargin = botR;
                    fMedia.setLayoutParams(mp);
                });
            }
        };
        top.addOnLayoutChangeListener((v, l, t0, r, b, ol, ot, orr, ob) -> fitMedia.run());
        foot.addOnLayoutChangeListener((v, l, t0, r, b, ol, ot, orr, ob) -> fitMedia.run());
        stage.addOnLayoutChangeListener((v, l, t0, r, b, ol, ot, orr, ob) -> fitMedia.run());

        // --- ألوان أشرطة النظام أثناء العرض (setStorySystemBars)
        try {
            android.view.Window w = act.getWindow();
            oldSb = w.getStatusBarColor(); oldNb = w.getNavigationBarColor();
        } catch (Throwable ignored) { }

        decor.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        current = this;
        // إيماءة/زر رجوع الهاتف يغلق القصة (أو النافذة المفتوحة فوقها) — بأولوية فوق أي معالج آخر
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                final android.window.OnBackInvokedCallback cb = () -> handleBack();
                act.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, cb);
                backCb = cb;
            } catch (Throwable ignored) { }
        }
        // ارتفاع حقل الرد + التفاعلات + التعليق مع لوحة المفاتيح (الشاشة تستخدم أعلام fullscreen فلا يعمل adjustResize وحده)
        overlay.setOnApplyWindowInsetsListener((v, ins) -> {
            kbBottom = ins.getSystemWindowInsetBottom();
            kbStable = ins.getStableInsetBottom();
            updateKeyboard();
            return ins;
        });
        overlay.getViewTreeObserver().addOnGlobalLayoutListener(this::updateKeyboard);
        overlay.requestApplyInsets();
        // تغيير ألوان أشرطة النظام يُرسَل بعد أول إطار من الحركة (لا في إطار البناء نفسه) لتفادي تقطّع الدخول
        overlay.post(() -> {
            try {
                if (closed) return;
                android.view.Window w = act.getWindow();
                w.setStatusBarColor(Color.BLACK); w.setNavigationBarColor(Color.BLACK);
                if (Build.VERSION.SDK_INT >= 23) {   // رموز إشعارات الهاتف (الساعة/البطارية/الشبكة) بيضاء داخل القصص
                    sbUiBefore = w.getDecorView().getSystemUiVisibility();
                    w.getDecorView().setSystemUiVisibility(sbUiBefore & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
                }
            } catch (Throwable ignored) { }
        });

        // --- حركة الفتح: .story-opening
        ValueAnimator bg = ValueAnimator.ofInt(0, 255);
        bg.setDuration(620);
        bg.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        bg.addUpdateListener(a -> { if (!swipeDragging && overlay != null) overlay.setBackgroundColor(Color.argb((Integer) a.getAnimatedValue(), 0, 0, 0)); });
        bg.start();
        stage.setScaleX(.88f); stage.setScaleY(.88f); stage.setAlpha(.35f);
        stage.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(720).withLayer()
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

        // أزرار صاحب القصة: تعديل / تثبيت / حذف (.sv-action)
        if (mine) {
            ImageView edit = action(R.drawable.ic_sv_edit, 0x59FFFFFF, 0x1FFFFFFF);
            edit.setOnClickListener(v -> showEditModal());
            pinBtn = action(R.drawable.ic_sv_pin, 0x73F5CF55, 0x3D916708);
            pinBtn.setOnClickListener(v -> togglePin());
            ImageView del = action(R.drawable.ic_sv_trash, 0x66FF6464, 0x4D781419);
            del.setOnClickListener(v -> showDeleteModal());
            LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(dp(34 * S), dp(34 * S)); ep.setMarginEnd(dp(5));
            top.addView(edit, ep);
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(34 * S), dp(34 * S)); ap.setMarginEnd(dp(5));
            top.addView(pinBtn, ap);
            LinearLayout.LayoutParams ap2 = new LinearLayout.LayoutParams(dp(34 * S), dp(34 * S)); ap2.setMarginEnd(dp(7));
            top.addView(del, ap2);
        }

        // زر الصوت (بدل ×): نقرة تكتم الصوت وتبدّل الأيقونة، ونقرة أخرى تعيده. الإغلاق بالسحب للأسفل أو زر الرجوع.
        final ImageView snd = new ImageView(act);
        snd.setImageResource(sMuted ? R.drawable.ic_sv_volume_off : R.drawable.ic_sv_volume);
        snd.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int cpad = dp((36 * S - 22 * S) / 2f);
        snd.setPadding(cpad, cpad, cpad, cpad);
        snd.setOnClickListener(v -> {
            sMuted = !sMuted;
            snd.setImageResource(sMuted ? R.drawable.ic_sv_volume_off : R.drawable.ic_sv_volume);
            try { if (curMp != null) curMp.setVolume(sMuted ? 0f : 1f, sMuted ? 0f : 1f); } catch (Throwable ignored) { }
        });
        top.addView(snd, new LinearLayout.LayoutParams(dp(36 * S), dp(36 * S)));
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
        statsTv.setOnClickListener(v -> openViewers());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = dp(18) + sab;
        stage.addView(statsTv, lp);
        setStats(0, 0);
    }

    private static final String[] PK_QUICK = {"😂", "❤️", "👍🏻", "😭", "✨"};
    private static final String[] PK_ALL = {"😂","😭","😉","😌","🫠","😡","🥵","🤢","💩","🤡","😎","🤧","☠️","🌚","🙈","🙉","🙊","✨","⚡","💢","💤","🔥","💯","🎉","💥","❤️","🤍","❤️‍🔥","❤️‍🩹","💔","🫦","👀","🫶🏻","👍🏻","🖕🏻","✍🏻","🙏🏻","💅🏻"};
    private ScrollView pickerGrid;
    private ImageView pickerPlus;

    private TextView pickCell(final String e) {
        TextView t = tv(e, 23f, Color.WHITE, false);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        GradientDrawable pr = new GradientDrawable(); pr.setShape(GradientDrawable.OVAL); pr.setColor(0x33808080);
        android.graphics.drawable.StateListDrawable sl = new android.graphics.drawable.StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, pr);
        t.setBackground(sl);
        t.setOnClickListener(v -> setReaction(react != null && react.equals(e) ? null : e));
        return t;
    }

    /** فتح/إغلاق شبكة كل الإيموجي (زر +) كما في قائمة الرسالة. */
    private void setPickerGrid(boolean open) {
        if (pickerGrid == null) return;
        if (pickerPlus != null) pickerPlus.animate().rotation(open ? 45f : 0f).setDuration(280)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.2f)).start();
        if (open) {
            pickerGrid.setVisibility(View.VISIBLE);
            pickerGrid.setAlpha(0f); pickerGrid.setTranslationY(-dp(6));
            pickerGrid.animate().alpha(1f).translationY(0).setDuration(260).start();
        } else pickerGrid.setVisibility(View.GONE);
    }

    private void buildViewerFoot() {
        foot.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        // منتقي التفاعلات (يظهر بضغطة مطوّلة على القلب): مطابق لشريط تفاعلات النقر على الرسالة (MsgMenu)
        //   صف علوي: [+] و5 إيموجي سريعة، و«+» يفتح شبكة كل الإيموجي (38، 6 في الصف) بنفس الترتيب والعدد.
        pickerBox = new LinearLayout(act);
        pickerBox.setOrientation(LinearLayout.VERTICAL);
        pickerBox.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        pickerBox.setPadding(dp(6), dp(4), dp(6), dp(4));
        pickerBox.setBackground(round(0x00000000, 14, 0, 0));   // قائمة الإيموجي شفافة بالكامل (بلا خلفية)
        pickerBox.setVisibility(View.GONE);
        pickerBox.setClickable(true);

        LinearLayout topRow = new LinearLayout(act);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        final ImageView plus = new ImageView(act);
        plus.setImageResource(R.drawable.ic_mm_plus);
        plus.setColorFilter(Color.WHITE);
        plus.setPadding(dp(6.5f), dp(6.5f), dp(6.5f), dp(6.5f));
        GradientDrawable pbg = new GradientDrawable(); pbg.setShape(GradientDrawable.OVAL); pbg.setColor(0x29808080);
        plus.setBackground(pbg);
        topRow.addView(plus, new LinearLayout.LayoutParams(dp(30), dp(30)));
        LinearLayout quick = new LinearLayout(act);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        quick.setGravity(Gravity.CENTER_VERTICAL);
        for (final String e : PK_QUICK) {
            TextView t = pickCell(e);
            quick.addView(t, new LinearLayout.LayoutParams(dp(34), dp(34)));
        }
        LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(-2, -2);
        ql.setMarginStart(dp(4));
        topRow.addView(quick, ql);
        pickerBox.addView(topRow, new LinearLayout.LayoutParams(-2, -2));

        final ScrollView gridScroll = new ScrollView(act);
        gridScroll.setVisibility(View.GONE);
        gridScroll.setVerticalScrollBarEnabled(false);
        LinearLayout grid = new LinearLayout(act);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(0, dp(6), 0, dp(2));
        LinearLayout grow = null;
        for (int i = 0; i < PK_ALL.length; i++) {
            if (i % 6 == 0) {
                grow = new LinearLayout(act);
                grow.setOrientation(LinearLayout.HORIZONTAL);
                grow.setGravity(Gravity.CENTER_HORIZONTAL);
                grid.addView(grow, new LinearLayout.LayoutParams(-1, dp(34)));
            }
            grow.addView(pickCell(PK_ALL[i]), new LinearLayout.LayoutParams(dp(34), dp(34)));
        }
        gridScroll.addView(grid, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, dp(170));
        gl.topMargin = dp(5);
        pickerBox.addView(gridScroll, gl);
        pickerGrid = gridScroll; pickerPlus = plus;
        plus.setOnClickListener(v -> setPickerGrid(gridScroll.getVisibility() != View.VISIBLE));
        // المنتقي في الـ stage (لا داخل التذييل) كي يظهر فوق التعليق/النبذة؛ موضعه يُحسب عند الظهور (showPickerAnimated)
        stage.addView(pickerBox, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));

        // صف الإيموجي السريعة (يظهر عند التركيز على حقل الرد)
        reactsRow = new LinearLayout(act);
        reactsRow.setOrientation(LinearLayout.HORIZONTAL);
        reactsRow.setGravity(Gravity.CENTER);
        reactsRow.setPadding(dp(10), dp(7), dp(10), dp(7));
        reactsRow.setBackground(round(0x6B12161C, 18, 0, 0));
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
        // في الـ stage (لا داخل التذييل) كي يقع فوق التعليق/النبذة لا فوقه؛ موضعه يُحسب في placeReacts()
        stage.addView(reactsRow, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));

        // صف الرد: [قلب] [حقل] [إرسال]
        replyRow = new LinearLayout(act);
        replyRow.setOrientation(LinearLayout.HORIZONTAL);
        replyRow.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);   // ترتيب صريح: [إرسال] [حقل] [قلب] — القلب في أقصى اليمين
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
            showReacts(has);
            hidePicker();
        });
        replyIn.setOnEditorActionListener((v, a, ev) -> { if (a == EditorInfo.IME_ACTION_SEND) { sendReply(); return true; } return false; });
        replyIn.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { replyChanged(); }
        });
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, -2, 1f);
        ip.setMarginEnd(dp(8));

        // زر الإرسال (.sv-pill)
        sendBtn = new LinearLayout(act);
        sendBtn.setGravity(Gravity.CENTER);
        sendBtn.setBackground(round(0x33FFFFFF, 20, 0, 0));
        ImageView si = new ImageView(act);
        si.setImageResource(R.drawable.ic_send_up_21);   // نفس رمز زر الإرسال داخل المحادثة (SVG)
        sendBtn.addView(si, new LinearLayout.LayoutParams(dp(21), dp(21)));
        sendBtn.setVisibility(View.GONE);
        sendBtn.setOnClickListener(v -> sendReply());
        LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(dp(46), dp(40));
        sbp.setMarginEnd(dp(8));
        // الترتيب (يسار ← يمين): إرسال، حقل الرد، قلب التفاعل
        replyRow.addView(sendBtn, sbp);
        replyRow.addView(replyIn, ip);
        replyRow.addView(heartBtn, new LinearLayout.LayoutParams(dp(42 * S), dp(42 * S)));

        foot.addView(replyRow, new LinearLayout.LayoutParams(-1, -2));
    }

    private void replyChanged() {
        boolean typed = replyIn != null && replyIn.getText().toString().trim().length() > 0;
        if (sendBtn != null) sendBtn.setVisibility(typed ? View.VISIBLE : View.GONE);
        if (heartBtn != null) heartBtn.setVisibility(typed ? View.GONE : View.VISIBLE);
        if (typed) hidePicker();
    }

    /** المنتقي وصف الإيموجي السريعة لا يظهران معاً: ظهور أحدهما يخفي الآخر. */
    private void togglePicker() {
        if (pickerBox == null) return;
        if (pickerBox.getVisibility() == View.VISIBLE) { hidePicker(); return; }
        if (reactsRow != null) reactsRow.setVisibility(View.GONE);
        if (pickerGrid != null) { pickerGrid.setVisibility(View.GONE); if (pickerPlus != null) pickerPlus.setRotation(0f); }
        showPickerAnimated();
    }

    /** يظهر المنتقي بحركة (تلاشٍ + تكبير + صعود) فوق التعليق/النبذة، أو فوق صف الرد إن لم يوجد تعليق. */
    private void showPickerAnimated() {
        int H = stage.getHeight();
        int ref = foot.getTop() + (replyRow != null ? replyRow.getTop() : dp(30));
        if (capTv != null && capTv.getVisibility() == View.VISIBLE) ref = Math.min(ref, capTv.getTop());
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) pickerBox.getLayoutParams();
        lp.bottomMargin = Math.max(dp(8), H - ref + dp(8));
        pickerBox.setLayoutParams(lp);
        pickerBox.animate().cancel();
        pickerBox.setPivotX(pickerBox.getWidth() > 0 ? pickerBox.getWidth() / 2f : dp(100));
        pickerBox.setPivotY(pickerBox.getHeight() > 0 ? pickerBox.getHeight() : dp(90));
        pickerBox.setAlpha(0f);
        pickerBox.setScaleX(.86f); pickerBox.setScaleY(.86f);
        pickerBox.setTranslationY(kbTarget + dp(14));
        pickerBox.setVisibility(View.VISIBLE);
        pickerBox.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(kbTarget).setDuration(200)
            .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
    }
    /** يرفع التذييل (الرد/التفاعلات/المنتقي) والتعليق فوق لوحة المفاتيح، ويخفي منتقي الضغط المطوّل عند ظهورها. */
    private void updateKeyboard() {
        if (closed || overlay == null || foot == null) return;
        int imeH = Math.max(0, kbBottom - kbStable);
        float shift = 0f;
        if (imeH > 0) {
            try {
                View rv = overlay.getRootView();
                int[] loc = new int[2];
                overlay.getLocationInWindow(loc);
                int ovBottom = loc[1] + overlay.getHeight();
                int kbTop = rv.getHeight() - kbBottom;
                // إن كانت الشاشة تتقلّص أصلاً بـ adjustResize فالفرق صفر ولا إزاحة مضاعفة
                shift = Math.max(0, ovBottom - kbTop) - dp(6) - sab;
            } catch (Throwable ignored) { }
            if (shift < 0f) shift = 0f;
        }
        boolean open = imeH > 0;
        if (open && !imeWasOpen) hidePicker();   // فُتحت لوحة المفاتيح للكتابة: اختفاء إيموجيات الضغط المطوّل
        if (!open && imeWasOpen) {               // الخروج من وضع الكتابة: تختفي إيموجيات الكتابة السريعة وتُسحب بؤرة الحقل
            try {
                if (replyIn != null && replyIn.hasFocus()) {
                    touch.setFocusableInTouchMode(true);
                    replyIn.clearFocus();
                    touch.requestFocus();
                }
            } catch (Throwable ignored) { }
            if (reactsRow != null) reactsRow.setVisibility(View.GONE);
        }
        imeWasOpen = open;
        float ty = -shift;
        if (Math.abs(ty - kbTarget) > 0.5f) {
            kbTarget = ty;
            foot.animate().translationY(ty).setDuration(150).start();
            if (capTv != null) capTv.animate().translationY(ty).setDuration(150).start();
            if (reactsRow != null) {
                if (reactsRow.getVisibility() == View.VISIBLE) reactsRow.animate().translationY(ty).setDuration(150).start();
                else reactsRow.setTranslationY(ty);
            }
            if (pickerBox != null) {
                if (pickerBox.getVisibility() == View.VISIBLE) pickerBox.animate().translationY(ty).setDuration(150).start();
                else pickerBox.setTranslationY(ty);
            }
        }
    }

    /** شريط الإيموجي السريعة: يظهر فوق التعليق (أو فوق صف الرد إن لم يوجد تعليق) ويتبع لوحة المفاتيح. */
    private void showReacts(boolean on) {
        if (reactsRow == null) return;
        if (!on) { reactsRow.setVisibility(View.GONE); return; }
        placeReacts();
        reactsRow.setVisibility(View.VISIBLE);
        reactsRow.post(this::placeReacts);   // إعادة الحساب بعد اكتمال القياس
    }
    private void placeReacts() {
        if (reactsRow == null || stage == null || foot == null) return;
        int H = stage.getHeight();
        int ref = foot.getTop() + (replyRow != null ? replyRow.getTop() : dp(30));
        if (capTv != null && capTv.getVisibility() == View.VISIBLE) ref = Math.min(ref, capTv.getTop());
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) reactsRow.getLayoutParams();
        int bm = Math.max(dp(8), H - ref + dp(10));
        if (lp.bottomMargin != bm) { lp.bottomMargin = bm; reactsRow.setLayoutParams(lp); }
        reactsRow.setTranslationY(kbTarget);
    }

    private boolean pickerOpen() { return pickerBox != null && pickerBox.getVisibility() == View.VISIBLE; }
    private void hidePicker() {
        boolean was = pickerOpen();
        if (pickerBox != null) { pickerBox.animate().cancel(); pickerBox.setVisibility(View.GONE); }
        // أُغلق المنتقي والحقل ما زال مركّزاً: يعود صف الإيموجي السريعة
        if (was && reactsRow != null && replyIn != null && replyIn.hasFocus()) showReacts(true);
    }

    // ------------------------------------------------------------ عرض قصة (showStory)
    private void showStory() {
        if (idx < 0 || idx >= list.size()) { close(); return; }
        final Story s = list.get(idx);
        hidePicker();   // قصة جديدة: أغلق المنتقي كي لا تبقى القصة متوقفة
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
                    curMp = mp;
                    try { mp.setVolume(sMuted ? 0f : 1f, sMuted ? 0f : 1f); } catch (Throwable ignored) { }
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
            syncPinIcon(s.pinned);
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
                if (paused || hold || modalOpen || pickerOpen()) {   // منتقي التفاعلات مفتوح: القصة متوقفة حتى يتم التفاعل
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
    /** صورة القصة الأولى تُحمَّل وتُفكّ مسبقاً (قبل بدء حركة الفتح) كي لا يحدث تقطّع ولا تظهر القصة فارغة ثم تقفز الصورة. */
    private Bitmap preBmp; private String preSrc;

    /** تنزيل + فك ترميز (يُستدعى من خيط IO فقط). */
    private static Bitmap fetchBitmap(String src) { return fetchBitmap(src, 1800); }

    /** تنزيل/فك صورة مع حدّ أقصى للبعد الأكبر (للمصغّرات في الملف الشخصي). */
    static Bitmap fetchBitmap(String src, int maxDim) {
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
            while (mx / sample > maxDim) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o2);
        } catch (Throwable ignored) { return null; }
    }

    private void loadImage(final String src, final int token) {
        if (src == null || src.isEmpty()) return;
        if (preBmp != null && src.equals(preSrc)) {          // جاهزة مسبقاً: تظهر فوراً مع أول إطار
            img.setImageBitmap(preBmp);
            preBmp = null; preSrc = null;
            return;
        }
        IO.execute(() -> {
            final Bitmap fb = fetchBitmap(src);
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

    private void sendEmoji(String e) { sendMessage(e, "تم إرسال الرد ✓", "تعذر إرسال الرد", false); }   // رد على القصة مرفق ببطاقتها، ولا يمسّ تفاعل القلب

    private void sendReply() {
        if (replyIn == null) return;
        String text = replyIn.getText().toString().trim();
        if (text.isEmpty()) return;
        sendMessage(text, "تم إرسال ردك ✓", "تعذر إرسال الرد", true);
    }

    private void sendMessage(final String text, final String okMsg, final String errMsg, final boolean clear) {
        if (mine || idx >= list.size()) return;
        final Story s = list.get(idx);
        // نفس معرّف المحادثة ونفس حقول الرسالة المستخدمة داخل المحادثة (getChatId / send في MassActivity) حتى تظهر الرسالة في محادثة الشخص
        final String chatId = me.compareTo(uid) < 0 ? me + "_" + uid : uid + "_" + me;
        final long nowMs = System.currentTimeMillis();
        Map<String, Object> chat = new HashMap<>();
        chat.put("participants", Arrays.asList(me, uid));
        chat.put("lastMessage", text);
        chat.put("lastMessageAt", nowMs);
        chat.put("lastSender", me);
        db.collection("chats").document(chatId).set(chat, SetOptions.merge()).addOnSuccessListener(v -> {
            Map<String, Object> m = new HashMap<>();
            m.put("type", "text");
            m.put("text", text);
            m.put("sender", me);
            m.put("timestamp", nowMs);
            m.put("storyReply", true);
            {
                Map<String, Object> sd = new HashMap<>();
                sd.put("imageUrl", s.imageUrl); sd.put("videoUrl", s.videoUrl); sd.put("caption", s.caption); sd.put("storyId", s.id);
                m.put("storyReplyData", sd);
            }
            m.put("read", false);
            db.collection("chats").document(chatId).collection("messages").add(m)
                .addOnSuccessListener(r -> {
                    notifyPeer(chatId, text);
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

    /** رمزان: دبوس للتثبيت (غير مثبّتة) ودبوس مشطوب لإلغاء التثبيت (مثبّتة). */
    private void syncPinIcon(boolean pinned) {
        if (pinBtn == null) return;
        pinBtn.setAlpha(1f);
        pinBtn.setImageResource(pinned ? R.drawable.ic_sv_unpin : R.drawable.ic_sv_pin);
    }

    private void togglePin() {
        if (idx >= list.size()) return;
        final Story s = list.get(idx);
        final boolean next = !s.pinned;
        s.pinned = next;
        syncPinIcon(next);
        refreshTime(s);
        db.collection("stories").document(s.id).update("pinned", next).addOnFailureListener(e -> {
            s.pinned = !next;
            syncPinIcon(s.pinned);
            refreshTime(s);
            Toast.makeText(act, "تعذر تحديث التثبيت", Toast.LENGTH_SHORT).show();
        });
    }


    // ------------------------------------------------------------ إشعار الرد (notifyPeer)
    private void notifyPeer(final String chatId, final String text) {
        if (NOTIFY_URL == null || NOTIFY_URL.isEmpty()) return;
        final FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) return;
        u.getIdToken(false).addOnSuccessListener(r -> {
            final String token = r.getToken();
            if (token == null) return;
            db.collection("users").document(me).get().addOnCompleteListener(task -> {
                String nm = "رسالة جديدة";
                try {
                    DocumentSnapshot d = task.getResult();
                    if (d != null) { String x = d.getString("displayName"); if (x == null || x.isEmpty()) x = d.getString("name"); if (x != null && !x.isEmpty()) nm = x; }
                } catch (Throwable ignored) { }
                final String sender = nm;
                IO.execute(() -> {
                    try {
                        JSONObject j = new JSONObject();
                        j.put("recipientId", uid); j.put("chatId", chatId); j.put("senderName", sender); j.put("messageText", text);
                        byte[] body = j.toString().getBytes(StandardCharsets.UTF_8);
                        HttpURLConnection c = (HttpURLConnection) new URL(NOTIFY_URL).openConnection();
                        c.setRequestMethod("POST"); c.setDoOutput(true); c.setConnectTimeout(12000); c.setReadTimeout(15000);
                        c.setRequestProperty("Content-Type", "application/json");
                        c.setRequestProperty("Authorization", "Bearer " + token);
                        OutputStream os = c.getOutputStream(); os.write(body); os.close();
                        c.getResponseCode(); c.disconnect();
                    } catch (Throwable ignored) { }
                });
            });
        });
    }

    // ------------------------------------------------------------ نوافذ الإدارة (#storyManageModal)
    private FrameLayout openScrim(int color) {
        closeModal();
        modal = new FrameLayout(act);
        modal.setBackgroundColor(color);
        modal.setClickable(true);
        modal.setOnClickListener(v -> closeModal());
        modalOpen = true;
        overlay.addView(modal, new FrameLayout.LayoutParams(-1, -1));
        return modal;
    }

    /** الطور الحالي للتطبيق (نفس مفتاح MassActivity: babylon_ui / dark). */
    private boolean isDark() {
        try { return act.getSharedPreferences("babylon_ui", Context.MODE_PRIVATE).getBoolean("dark", false); }
        catch (Throwable t) { return false; }
    }

    /** يصبغ شريط الإيماءات السفلي بلون القائمة (وأيقوناته فاتحة/داكنة حسب الطور). */
    private void tintNav(int color, boolean dark) {
        try {
            android.view.Window w = act.getWindow();
            if (!navTinted) navUiBefore = w.getDecorView().getSystemUiVisibility();
            navTinted = true;
            w.setNavigationBarColor(color);
            if (Build.VERSION.SDK_INT >= 26) {
                int f = w.getDecorView().getSystemUiVisibility();
                f = dark ? (f & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR) : (f | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
                w.getDecorView().setSystemUiVisibility(f);
            }
        } catch (Throwable ignored) { }
    }

    /** يعيد شريط الإيماءات إلى ما كان عليه أثناء عرض القصة (أسود). */
    private void restoreNav() {
        if (!navTinted) return;
        navTinted = false;
        try {
            android.view.Window w = act.getWindow();
            w.setNavigationBarColor(Color.BLACK);
            if (Build.VERSION.SDK_INT >= 26) w.getDecorView().setSystemUiVisibility(navUiBefore);
        } catch (Throwable ignored) { }
    }

    private void closeModal() {
        restoreNav();
        try {
            if (capEt != null) {
                android.view.inputmethod.InputMethodManager im =
                    (android.view.inputmethod.InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (im != null) im.hideSoftInputFromWindow(capEt.getWindowToken(), 0);
            }
        } catch (Throwable ignored) { }
        if (modal != null) { try { overlay.removeView(modal); } catch (Throwable ignored) { } modal = null; }
        modalOpen = false; pickedUri = null; pickedName = null; capEt = null; saveBtnTv = null;
    }

    private LinearLayout manageCard() {
        LinearLayout c = new LinearLayout(act);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        c.setPadding(dp(20), dp(20), dp(20), dp(20));
        final boolean d = isDark();
        GradientDrawable g = d
            ? new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFA1E222B, 0xFA0C0F15})
            : new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFFFFFFFF, 0xFFF4F6FA});
        g.setCornerRadius(dp(20));
        g.setStroke(Math.max(1, dp(1)), d ? 0x21FFFFFF : Tk.border(false));
        c.setBackground(g);
        c.setClickable(true);
        return c;
    }

    private void addTitleSub(LinearLayout card, String title, String sub) {
        TextView t = tv(title, 18f * S, isDark() ? Color.WHITE : Tk.text(false), true);
        t.setGravity(Gravity.START);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2); tp.bottomMargin = dp(7);
        card.addView(t, tp);
        TextView sTv = tv(sub, 13f * S, isDark() ? 0xA6FFFFFF : Tk.muted(false), false);
        sTv.setGravity(Gravity.START);
        sTv.setLineSpacing(0f, 1.35f);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2); sp.bottomMargin = dp(16);
        card.addView(sTv, sp);
    }

    private TextView actionBtn(String text, int textColor, Drawable bg) {
        TextView b = tv(text, 14f * S, textColor, true);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(43 * S));
        b.setBackground(bg);
        return b;
    }

    private void placeCard(FrameLayout scrim, LinearLayout card) {
        int sw = act.getResources().getDisplayMetrics().widthPixels;
        scrim.addView(card, new FrameLayout.LayoutParams(Math.min(sw - dp(44), dp(390)), -2, Gravity.CENTER));
    }

    private void showEditModal() {
        if (idx >= list.size() || modal != null) return;
        final Story s = list.get(idx);
        FrameLayout sc = openScrim(isDark() ? 0xAD000000 : 0x80000000);
        LinearLayout card = manageCard();
        addTitleSub(card, "تعديل القصة", "عدّل الوصف، أو اختر صورة أو فيديو جديدًا لاستبدال القصة الحالية.");

        capEt = new EditText(act);
        capEt.setText(s.caption);
        capEt.setHint("اكتب وصف القصة هنا…");
        final boolean dk = isDark();
        capEt.setHintTextColor(dk ? 0x80FFFFFF : 0xFF9AA7B6);
        capEt.setTextColor(dk ? Color.WHITE : Tk.text(false));
        capEt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * S);
        capEt.setFilters(new InputFilter[]{new InputFilter.LengthFilter(200)});
        capEt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        capEt.setGravity(Gravity.TOP | Gravity.START);
        capEt.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        capEt.setTextDirection(View.TEXT_DIRECTION_RTL);
        capEt.setMinHeight(dp(94 * S));
        capEt.setPadding(dp(12), dp(12), dp(12), dp(12));
        capEt.setBackground(dk ? round(0x0FFFFFFF, 13, 0x29FFFFFF, 1) : round(Tk.surface2(false), 13, Tk.border(false), 1));
        capEt.setSelection(capEt.getText().length());
        card.addView(capEt, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout media = new LinearLayout(act);
        media.setOrientation(LinearLayout.HORIZONTAL);
        media.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        media.setGravity(Gravity.CENTER_VERTICAL);
        media.setPadding(dp(11), dp(11), dp(11), dp(11));
        media.setBackground(dk ? round(0x14F5CF55, 12, 0x4DF5CF55, 1) : round(0x14C99A1C, 12, 0x66C99A1C, 1));
        ImageView mi = new ImageView(act);
        mi.setImageResource(R.drawable.ic_sv_image);
        if (!dk) mi.setColorFilter(Tk.goldInk(false));
        media.addView(mi, new LinearLayout.LayoutParams(dp(20 * S), dp(20 * S)));
        pickedName = tv("اختيار صورة أو فيديو جديد (اختياري)", 13f * S, dk ? 0xFFF5CF55 : Tk.goldInk(false), true);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, -2); np.setMarginStart(dp(9));
        media.addView(pickedName, np);
        media.setOnClickListener(v -> {
            try {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.setType("*/*");
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
                act.startActivityForResult(Intent.createChooser(i, "اختر صورة أو فيديو"), REQ_PICK);
            } catch (Throwable e) { Toast.makeText(act, "لا توجد تطبيقات لاختيار الملفات", Toast.LENGTH_SHORT).show(); }
        });
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, -2); mp.topMargin = dp(11);
        card.addView(media, mp);

        LinearLayout acts = new LinearLayout(act);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        acts.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        TextView cancel = actionBtn("إلغاء", isDark() ? Color.WHITE : Tk.chipFg(false), round(isDark() ? 0x17FFFFFF : Tk.chipBg(false), 12, 0, 0));
        cancel.setOnClickListener(v -> closeModal());
        GradientDrawable gold = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFFF5D875, 0xFFB98B23});
        gold.setCornerRadius(dp(12));
        saveBtnTv = actionBtn("حفظ التعديلات", 0xFFFFFFFF, gold);
        saveBtnTv.setOnClickListener(v -> saveEdit(s));
        LinearLayout.LayoutParams a1 = new LinearLayout.LayoutParams(0, -2, 1f);
        LinearLayout.LayoutParams a2 = new LinearLayout.LayoutParams(0, -2, 1f); a2.setMarginStart(dp(9));
        acts.addView(cancel, a1); acts.addView(saveBtnTv, a2);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, -2); ap.topMargin = dp(17);
        card.addView(acts, ap);
        placeCard(sc, card);
        capEt.requestFocus();
    }

    private void setPicked(Uri u) {
        pickedUri = u;
        if (pickedName == null) return;
        String nm = null;
        try {
            Cursor c = act.getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null) { if (c.moveToFirst()) nm = c.getString(0); c.close(); }
        } catch (Throwable ignored) { }
        pickedName.setText(nm != null && !nm.isEmpty() ? nm : "تم اختيار ملف جديد");
    }

    private void saveEdit(final Story s) {
        if (saveBtnTv == null || capEt == null || !saveBtnTv.isEnabled()) return;
        String c0 = capEt.getText().toString().trim();
        final String caption = c0.length() > 200 ? c0.substring(0, 200) : c0;
        final Uri file = pickedUri;
        saveBtnTv.setEnabled(false); saveBtnTv.setText("جارٍ الحفظ…"); saveBtnTv.setAlpha(.55f);
        IO.execute(() -> {
            try {
                final Map<String, Object> upd = new HashMap<>();
                upd.put("caption", caption);
                String upUrl = null; boolean isVideo = false; double duration = s.duration > 0 ? s.duration : 30;
                if (file != null) {
                    String mime = act.getContentResolver().getType(file);
                    if (mime == null) mime = "";
                    isVideo = mime.startsWith("video/");
                    if (!isVideo && !mime.startsWith("image/")) throw new Exception("اختر صورة أو فيديو صالحًا");
                    long size = -1;
                    try {
                        Cursor c = act.getContentResolver().query(file, new String[]{OpenableColumns.SIZE}, null, null, null);
                        if (c != null) { if (c.moveToFirst() && !c.isNull(0)) size = c.getLong(0); c.close(); }
                    } catch (Throwable ignored) { }
                    if (isVideo) {
                        if (size > 100L * 1024 * 1024) throw new Exception("حجم الفيديو كبير جدًا");
                        MediaMetadataRetriever mr = new MediaMetadataRetriever();
                        try {
                            mr.setDataSource(act, file);
                            String d = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                            double sec = d == null ? 0 : Long.parseLong(d) / 1000.0;
                            if (sec <= 0 || sec > 30.5) throw new Exception("مدة الفيديو تتجاوز الحد المسموح");
                            duration = Math.min(sec, 30);
                        } finally { try { mr.release(); } catch (Throwable ignored) { } }
                    }
                    upUrl = uploadMedia(file, isVideo, size);
                    upd.put("imageUrl", isVideo ? upUrl.replace("/video/upload/", "/video/upload/so_0/").replaceAll("\\.[^/.]+$", ".jpg") : upUrl);
                    upd.put("mediaType", isVideo ? "video" : "image");
                    upd.put("videoUrl", isVideo ? (Object) upUrl : FieldValue.delete());
                    upd.put("videoPath", "");
                    upd.put("duration", isVideo ? (Object) Double.valueOf(duration) : FieldValue.delete());
                }
                final String fUp = upUrl; final boolean fVid = isVideo; final double fDur = duration;
                db.collection("stories").document(s.id).update(upd).addOnSuccessListener(v -> {
                    s.caption = caption;
                    if (fUp != null) {
                        s.imageUrl = (String) upd.get("imageUrl");
                        if (fVid) { s.videoUrl = fUp; s.duration = fDur; } else { s.videoUrl = ""; s.duration = 0; }
                    }
                    if (closed) return;
                    closeModal();
                    Toast.makeText(act, "تم تعديل القصة بنجاح", Toast.LENGTH_SHORT).show();
                    showStory();
                    notifyChanged();
                }).addOnFailureListener(e -> editFailed("تعذر تعديل القصة"));
            } catch (final Throwable e) {
                editFailed(e.getMessage() != null && !e.getMessage().isEmpty() ? e.getMessage() : "تعذر تعديل القصة");
            }
        });
    }

    private void editFailed(final String msg) {
        handler.post(() -> {
            Toast.makeText(act, msg, Toast.LENGTH_SHORT).show();
            if (saveBtnTv != null) { saveBtnTv.setEnabled(true); saveBtnTv.setText("حفظ التعديلات"); saveBtnTv.setAlpha(1f); }
        });
    }

    private String uploadMedia(Uri uri, boolean video, long fileSize) throws Exception {
        final String boundary = "----BabylonStory" + System.currentTimeMillis();
        final String crlf = "\r\n";
        StringBuilder pre = new StringBuilder();
        pre.append("--").append(boundary).append(crlf)
           .append("Content-Disposition: form-data; name=\"upload_preset\"").append(crlf).append(crlf)
           .append(CLD_PRESET).append(crlf);
        pre.append("--").append(boundary).append(crlf)
           .append("Content-Disposition: form-data; name=\"folder\"").append(crlf).append(crlf)
           .append("stories").append(crlf);
        pre.append("--").append(boundary).append(crlf)
           .append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(video ? "story.mp4" : "story.jpg").append("\"").append(crlf)
           .append("Content-Type: application/octet-stream").append(crlf).append(crlf);
        byte[] head = pre.toString().getBytes(StandardCharsets.UTF_8);
        byte[] tail = (crlf + "--" + boundary + "--" + crlf).getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.cloudinary.com/v1_1/" + CLD_CLOUD + (video ? "/video" : "/image") + "/upload").openConnection();
        c.setRequestMethod("POST"); c.setDoOutput(true); c.setConnectTimeout(20000); c.setReadTimeout(120000);
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        if (fileSize > 0) c.setFixedLengthStreamingMode(head.length + fileSize + tail.length); else c.setChunkedStreamingMode(65536);
        InputStream in = null;
        try {
            OutputStream out = c.getOutputStream();
            out.write(head);
            in = act.getContentResolver().openInputStream(uri);
            if (in == null) throw new Exception("تعذر قراءة الملف");
            byte[] buf = new byte[32768]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.write(tail); out.flush(); out.close();
            int code = c.getResponseCode();
            InputStream rs = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            if (rs != null) { byte[] b2 = new byte[8192]; int k; while ((k = rs.read(b2)) > 0) bos.write(b2, 0, k); rs.close(); }
            JSONObject r = null;
            try { r = new JSONObject(bos.toString("UTF-8")); } catch (Throwable ignored) { }
            if (code >= 200 && code < 300 && r != null && r.optString("secure_url", "").length() > 0) return r.getString("secure_url");
            String em = r != null && r.optJSONObject("error") != null ? r.optJSONObject("error").optString("message", "" + code) : "" + code;
            throw new Exception("فشل رفع الوسائط: " + em);
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
            c.disconnect();
        }
    }

    private void showDeleteModal() {
        if (idx >= list.size() || modal != null) return;
        final Story s = list.get(idx);
        FrameLayout sc = openScrim(isDark() ? 0xAD000000 : 0x80000000);
        LinearLayout card = manageCard();
        addTitleSub(card, "حذف القصة؟", "هل تريد حذف هذه القصة نهائيًا؟ لن تتمكن من استعادتها بعد الحذف.");
        LinearLayout acts = new LinearLayout(act);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        acts.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        TextView cancel = actionBtn("إلغاء", isDark() ? Color.WHITE : Tk.chipFg(false), round(isDark() ? 0x17FFFFFF : Tk.chipBg(false), 12, 0, 0));
        cancel.setOnClickListener(v -> closeModal());
        // ظلّ خلف زر «إلغاء» ليتميّز عن خلفية البطاقة البيضاء
        cancel.setElevation(dp(4));
        acts.setClipChildren(false); acts.setClipToPadding(false);
        card.setClipChildren(false); card.setClipToPadding(false);
        GradientDrawable red = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFFFF6A70, 0xFFC52D3A});
        red.setCornerRadius(dp(12));
        final TextView ok = actionBtn("حذف القصة", Color.WHITE, red);
        ok.setOnClickListener(v -> {
            if (!ok.isEnabled()) return;
            ok.setEnabled(false); ok.setText("جارٍ الحذف…"); ok.setAlpha(.55f);
            db.collection("stories").document(s.id).collection("views").get().addOnCompleteListener(vt -> {
                try { if (vt.isSuccessful() && vt.getResult() != null) for (DocumentSnapshot d : vt.getResult().getDocuments()) d.getReference().delete(); } catch (Throwable ignored) { }
                db.collection("stories").document(s.id).delete().addOnSuccessListener(x -> {
                    if (closed) return;
                    closeModal();
                    Toast.makeText(act, "تم حذف القصة", Toast.LENGTH_SHORT).show();
                    list.remove(s);
                    syncMeta();
                    if (list.isEmpty()) { close(); return; }
                    if (idx >= list.size()) idx = list.size() - 1;
                    showStory();
                }).addOnFailureListener(e -> {
                    Toast.makeText(act, "تعذر الحذف", Toast.LENGTH_SHORT).show();
                    ok.setEnabled(true); ok.setText("حذف القصة"); ok.setAlpha(1f);
                });
            });
        });
        LinearLayout.LayoutParams a1 = new LinearLayout.LayoutParams(0, -2, 1f);
        LinearLayout.LayoutParams a2 = new LinearLayout.LayoutParams(0, -2, 1f); a2.setMarginStart(dp(9));
        acts.addView(cancel, a1); acts.addView(ok, a2);
        card.addView(acts, new LinearLayout.LayoutParams(-1, -2));
        placeCard(sc, card);
    }

    private void notifyChanged() {
        long now = System.currentTimeMillis(), until = 0;
        for (Story x : list) if (x.expiresAt > now) until = Math.max(until, x.expiresAt);
        if (listener != null) listener.onStoriesChanged(until);
    }

    // ------------------------------------------------------------ قائمة المشاهدين (#viewersModal)
    private void openViewers() {
        if (idx >= list.size() || modal != null) return;
        final Story s = list.get(idx);
        final FrameLayout sc = openScrim(0xCC000000);
        final int maxH = (int) (overlay.getHeight() * 0.70f);
        LinearLayout card = new LinearLayout(act) {
            @Override protected void onMeasure(int w, int h) {
                super.onMeasure(w, h);
                if (getMeasuredHeight() > maxH) super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.EXACTLY));
            }
        };
        card.setOrientation(LinearLayout.VERTICAL);
        card.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        card.setClickable(true);
        final boolean vd = isDark();
        final int vSurface = Tk.surface(vd);                     // نفس لون القائمة يُمدّ على شريط الإيماءات
        final int navPad = kbStable > 0 ? kbStable : Math.max(0, sab - dp(24));
        card.setPadding(dp(16), dp(16), dp(16), dp(16) + navPad);
        GradientDrawable g = new GradientDrawable();
        g.setColor(vSurface);
        g.setCornerRadii(new float[]{dp(20), dp(20), dp(20), dp(20), 0, 0, 0, 0});
        card.setBackground(g);
        final TextView title = tv("المشاهدون", 16f * S, Tk.text(vd), true);
        title.setGravity(Gravity.START);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2); tp.bottomMargin = dp(12);
        card.addView(title, tp);
        ScrollView sv = new ScrollView(act);
        final LinearLayout listBox = new LinearLayout(act);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        sv.addView(listBox, new ViewGroup.LayoutParams(-1, -2));
        card.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        sc.addView(card, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        tintNav(vSurface, vd);

        db.collection("stories").document(s.id).collection("views").get().addOnSuccessListener(q -> {
            if (modal != sc || closed) return;
            List<DocumentSnapshot> docs = new ArrayList<>(q.getDocuments());
            Collections.sort(docs, new Comparator<DocumentSnapshot>() {
                @Override public int compare(DocumentSnapshot a, DocumentSnapshot b) {
                    Long x = a.getLong("at"), y = b.getLong("at");
                    return Long.compare(y == null ? 0 : y, x == null ? 0 : x);
                }
            });
            title.setText("المشاهدون (" + docs.size() + ")");
            if (docs.isEmpty()) {
                TextView e = tv("لا توجد مشاهدات بعد", 14f * S, isDark() ? 0x99FFFFFF : Tk.muted(false), false);
                e.setGravity(Gravity.CENTER);
                e.setPadding(dp(10), dp(10), dp(10), dp(10));
                listBox.addView(e, new LinearLayout.LayoutParams(-1, -2));
                return;
            }
            for (DocumentSnapshot d : docs) addViewerRow(listBox, d);
        });
    }

    private void addViewerRow(final LinearLayout box, DocumentSnapshot vd) {
        final String vUid = vd.getId();
        Long at = vd.getLong("at");
        final long when = at == null ? 0 : at;
        final String re = vd.getString("reaction");
        final LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        final StoryAvatarView av = new StoryAvatarView(act);
        av.set("?", null, StoryAvatarView.toneBlue(vUid)).ring(false, false).shadow(false).textSp(15f * S);
        row.addView(av, new LinearLayout.LayoutParams(dp(36 * S), dp(36 * S)));
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        final boolean rd = isDark();
        final TextView nm = tv("...", 14f * S, Tk.text(rd), true);
        nm.setGravity(Gravity.START);
        final TextView sub = tv(ago(when), 11f * S, rd ? 0x99FFFFFF : Tk.muted(false), false);
        sub.setGravity(Gravity.START);
        col.addView(nm, new LinearLayout.LayoutParams(-2, -2));
        col.addView(sub, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1f); cp.setMarginStart(dp(10));
        row.addView(col, cp);
        if (re != null && !re.isEmpty()) {
            row.addView(tv(re, 20f * S, Tk.text(rd), false), new LinearLayout.LayoutParams(-2, -2));
        }
        box.addView(row, new LinearLayout.LayoutParams(-1, -2));
        View div = new View(act);
        div.setBackgroundColor(rd ? 0x0DFFFFFF : Tk.border(false));
        box.addView(div, new LinearLayout.LayoutParams(-1, 1));

        db.collection("users").document(vUid).get().addOnSuccessListener(u -> {
            if (modal == null || !u.exists()) return;
            String n = u.getString("displayName"); if (n == null || n.isEmpty()) n = u.getString("name"); if (n == null || n.isEmpty()) n = "مستخدم";
            String un = u.getString("username");
            nm.setText(n);
            sub.setText("@" + (un == null ? "" : un) + " · " + ago(when));
            final String letterV = n.substring(0, 1).toUpperCase();
            av.set(letterV, null, StoryAvatarView.toneBlue(vUid));
            String ph = u.getString("photo"); if (ph == null || ph.isEmpty()) ph = u.getString("avatarUrl"); if (ph == null || ph.isEmpty()) ph = u.getString("photoURL");
            if (ph != null && !ph.isEmpty()) {
                final String fph = ph;
                IO.execute(() -> {
                    final Bitmap b = decodeSrc(fph);
                    if (b != null) handler.post(() -> { if (modal != null) av.set(letterV, b, StoryAvatarView.toneBlue(vUid)); });
                });
            }
        });
    }

    private static Bitmap decodeSrc(String src) {
        try {
            byte[] data;
            if (src.startsWith("data:")) data = Base64.decode(src.substring(src.indexOf(',') + 1), Base64.DEFAULT);
            else if (src.startsWith("http")) {
                HttpURLConnection c = (HttpURLConnection) new URL(src).openConnection();
                c.setConnectTimeout(12000); c.setReadTimeout(20000);
                InputStream is = c.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[16384]; int n;
                while ((n = is.read(buf)) > 0) { bos.write(buf, 0, n); if (bos.size() > 8 * 1024 * 1024) break; }
                is.close(); data = bos.toByteArray();
            } else data = Base64.decode(src, Base64.DEFAULT);
            BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int sample = 1, mx = Math.max(o.outWidth, o.outHeight);
            while (mx / sample > 256) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options(); o2.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o2);
        } catch (Throwable t) { return null; }
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
    private int sbUiBefore = -1;

    private void close() {
        if (closed) return;
        closed = true;
        if (Build.VERSION.SDK_INT >= 33 && backCb != null) {
            try { act.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((android.window.OnBackInvokedCallback) backCb); } catch (Throwable ignored) { }
            backCb = null;
        }
        handler.removeCallbacksAndMessages(null);
        try { vid.stopPlayback(); } catch (Throwable ignored) { }
        try { restoreNav(); } catch (Throwable ignored) { }
        try {   // إعادة لون رموز شريط الحالة كما كان قبل فتح القصة
            if (sbUiBefore >= 0 && Build.VERSION.SDK_INT >= 23) {
                View dv = act.getWindow().getDecorView();
                int f = dv.getSystemUiVisibility();
                f = (sbUiBefore & View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) != 0 ? (f | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) : (f & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
                dv.setSystemUiVisibility(f);
            }
        } catch (Throwable ignored) { }
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
        try { if (listener != null) listener.onClosed(); } catch (Throwable ignored) { }
    }
}
