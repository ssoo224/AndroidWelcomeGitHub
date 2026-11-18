package com.babylon.chat.iq;

import android.animation.Animator;
import android.animation.AnimatorInflater;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Shader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;

import com.babylon.chat.iq.databinding.MainBinding;
import com.google.firebase.auth.FirebaseAuth;

import java.util.ArrayList;
import java.util.List;

/** شاشة البداية (Splash): تحدد هل المستخدم مسجّل دخوله أم لا ثم تنتقل للشاشة المناسبة. */
public class MainActivity extends Activity {

    private static final long LOGO_DONE_MS = 2650;   // اكتمال رسم اللوگو وتلوينه واستقراره
    private static final long HOLD_AFTER_LOGO_MS = 0;   // جاهزة عند اكتمال رسم السبلاش فتدخل مباشرة (التلاشي 300ms في MassActivity.revealFromSplash)
    private static final long SPLASH_MS = LOGO_DONE_MS + HOLD_AFTER_LOGO_MS;   // 3150ms

    // ===== توقيت حركة الشعار (ms) =====
    private static final float T_CIRCLE_A = 0f,    T_CIRCLE_B = 800f;    // رسم محيط الدائرة الذهبية أولاً
    private static final float T_LOGO_A = 650f,    T_LOGO_B = 1550f;     // ثم رسم محيط الشعار
    private static final float T_COLOR_A = 1550f,  T_COLOR_B = 1950f;    // تلوين القرص والشعار
    private static final float T_SETTLE_A = 1950f, T_SETTLE_B = 2650f;   // رجوع للحجم الطبيعي + ارتفاع + ظهور العنوان
    private static final float DISC_DP = 120f;    // قطر القرص النهائي بعد الاكتمال
    private static final float DRAW_DP = 150f;    // قطر القرص أثناء التخطيط (الرسم) ثم يعود إلى 120dp

    private MainBinding binding;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Animator> animators = new ArrayList<>();
    private ValueAnimator logoAnim;
    private float startTy;   // إزاحة الشعار نحو مركز الشاشة قبل ارتفاعه (العنوان لم يظهر بعد)
    private final android.view.animation.PathInterpolator ease = new android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f);
    private boolean navigated = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = MainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.getRoot().setBackground(AuthTheme.authBg(this, AuthTheme.isDark(this)));   // التدرّج + الموجتان الذهبيتان (نهاري/ليلي)
        // حركة دخول الموجتين الذهبيتين في بداية السبلاش ثم تثبتان
        if (binding.getRoot().getBackground() instanceof AuthBg) ((AuthBg) binding.getRoot().getBackground()).playIntro(1500);
        setupSystemBars();
        startAnimations();
        warmUp();   // تجهيز الشاشة التالية في الخلفية أثناء عرض السبلاش

        // فحص تسجيل الدخول (وفحص الأذونات) يجري كله في خيط خلفي خاص (داخل warmUp) كي لا يمسّ خيط الرسم؛ نتيجته في earlyOk.
        // بعد اكتمال الرسم والتلوين تُطلق MassActivity مخفية خلف السبلاش لتُبنى أثناء الاستقرار، وعند اكتمال السبلاش تدخل مباشرة.
        handler.postDelayed(this::startMassEarly, EARLY_START_MS);

        handler.postDelayed(this::goNext, SPLASH_MS);
    }

    private static final long EARLY_START_MS = LOGO_DONE_MS;   // الجزء المتبقي من بناء الصفحة يجري في نهاية رسم اللوگو تماماً، ثم تُكشف بتلاشٍ شفاف مباشرة
    private boolean earlyStarted = false;
    private int preWaits = 0, goWaits = 0;
    private volatile Boolean earlyOk = null;   // null = الفحص الخلفي جارٍ؛ true = مسجّل ولا أذونات معلّقة

    private void startMassEarly() {
        if (isFinishing() || navigated || earlyStarted) return;
        Boolean ok = earlyOk;
        if (ok == null) { handler.postDelayed(this::startMassEarly, 25); return; }   // الفحص الخلفي لم ينتهِ بعد
        if (!ok) return;   // غير مسجّل أو أول تشغيل بالأذونات: المسار القديم عند goNext
        if (!Handoff.hasPre() && preWaits++ < 20) { handler.postDelayed(this::startMassEarly, 25); return; }   // ننتظر (حتى 0.5ث) نفخ التخطيط في الخلفية كي لا يُنفخ على الخيط الرئيسي
        earlyStarted = true;
        Handoff.begin();
        Intent i = new Intent(this, MassActivity.class);
        i.putExtra(Handoff.EXTRA, true);
        startActivity(i);
        overridePendingTransition(0, 0);
    }

    /** اكتمل اللوگو: تُكشف الصفحة المبنية مسبقاً فوراً بتلاشٍ ثم تُغلق السبلاش. */
    private void revealEarly() {
        navigated = true;
        settleThen(() -> {
            if (isFinishing()) return;
            Handoff.whenReady(() -> {
                MassActivity m = Handoff.mass();
                if (m == null) { finish(); return; }
                m.revealFromSplash(() -> { Handoff.begin(); finish(); overridePendingTransition(0, 0); });   // begin() يفرّغ المرجع الساكن لـMassActivity
            }, () -> finish());   // فشلت MassActivity وتولّت هي المسار البديل بنفسها
        });
    }

    /**
     * سبب «التوقف» قبل الدخول: بناء MassActivity (تضخيم تخطيطات ضخمة + تحميل عشرات الكلاسات + تهيئة Firebase)
     * يتم كله على الخيط الرئيسي فتتجمّد حركة السبلاش لأجزاء من الثانية.
     * هنا نُسخّن هذه الأشياء في خيط خلفي أثناء السبلاش (يُهمَل ناتجها) فيصبح بناء الشاشة الفعلي أسرع بكثير.
     */
    private void warmUp() {
        final android.content.Context app = getApplicationContext();
        android.os.HandlerThread ht = new android.os.HandlerThread("bb-warm", android.os.Process.THREAD_PRIORITY_BACKGROUND);   // له Looper لأن بعض الـViews تنشئ Handler؛ أولوية خلفية كي لا يتقطع السبلاش
        ht.start();
        new Handler(ht.getLooper()).post(() -> {
            // الفحص أولاً وبأسبقية: تسجيل الدخول + الأذونات (بعيداً عن خيط الرسم)
            boolean loggedIn = false; String uid = null;
            try {
                com.google.firebase.auth.FirebaseUser fu = FirebaseAuth.getInstance().getCurrentUser();
                loggedIn = fu != null; uid = fu != null ? fu.getUid() : null;
            } catch (Throwable ignored) { }
            boolean needPerms = false;
            try {
                AppSettings.init(app);
                needPerms = Build.VERSION.SDK_INT >= 23 && !AppSettings.prefs(app, "babylon_boot").getBoolean("perms_asked", false) && missingPermissions().length > 0;
            } catch (Throwable ignored) { }
            boolean pendingVerify = false;
            try { pendingVerify = AppSettings.prefs(app, VerifyActivity.PREFS).getString(VerifyActivity.KEY_PENDING, null) != null; } catch (Throwable ignored) { }
            earlyOk = loggedIn && !needPerms && !pendingVerify;   // حساب بانتظار التحقق من البريد: لا إطلاق مبكر لصفحة المحادثات
            if (loggedIn) {   // نفخ تخطيط صفحة المحادثات في هذا الخيط الخاص أثناء الرسم؛ تأخذه MassActivity جاهزاً
                try {
                    android.view.LayoutInflater pinf = android.view.LayoutInflater.from(new android.view.ContextThemeWrapper(app, R.style.NoActionBar));
                    Handoff.putPre(com.babylon.chat.iq.databinding.MassBinding.inflate(pinf));
                } catch (Throwable ignored) { }
            }
            try { com.google.firebase.firestore.FirebaseFirestore.getInstance(); } catch (Throwable ignored) { }
            warmData(app, uid);
            String[] cls = {"MassActivity", "Main2Activity", "MsgViews", "MsgMenu", "ChatThemes", "ChatPreview", "ChatPattern",
                    "Tk", "EmojiFx", "BabylonFx", "BabylonBars", "StoriesController", "StoryViewer", "StoryComposer",
                    "StoryAvatarView", "ProfileRing", "ProfileHero", "SearchPage", "LocalDb", "FontManager", "CoverThemes", "Glass"};
            for (String c : cls) {
                try { Class.forName("com.babylon.chat.iq." + c, true, getClassLoader()); } catch (Throwable ignored) { }
            }
            try {   // تضخيم تجريبي يُرمى: يسخّن تحليل الـXML وكاش الموارد وتحميل كلاسات الـViews
                android.view.LayoutInflater inf = android.view.LayoutInflater.from(new android.view.ContextThemeWrapper(app, R.style.NoActionBar));
                if (loggedIn) {
                    // (MassBinding نُفخ أعلاه وحُفظ في Handoff)
                    for (String pf : new String[]{"part_profile", "part_settings"}) {   // MassActivity.init يضخّمها على الخيط الرئيسي أيضاً
                        int lid = app.getResources().getIdentifier(pf, "layout", app.getPackageName());
                        if (lid != 0) inf.inflate(lid, null);
                    }
                } else com.babylon.chat.iq.databinding.Main2Binding.inflate(inf);
            } catch (Throwable ignored) { }
            ht.quitSafely();
        });
    }

    /**
     * تجهيز بيانات الشاشة التالية على الخيط الخلفي: فتح قاعدة SQLite (كانت تُفتح على الخيط الرئيسي داخل MassActivity.onCreate)،
     * وقراءة الملف الشخصي والمحادثات والجهات وأحدث رسائل أول محادثتين لتسخين الكاش، وتحميل الإعدادات والخطوط وكاش Firestore.
     * أي استدعاء لاحق من MassActivity يجد كل شيء جاهزاً فلا ينتظر.
     */
    private void warmData(android.content.Context app, String uid) {
        try { AppSettings.init(app); } catch (Throwable ignored) { }
        try { FontManager.init(app); } catch (Throwable ignored) { }
        for (String n : new String[]{"babylon_ui", "babylon_boot"}) {   // أول getAll يقرأ ملف XML؛ هنا لا على الخيط الرئيسي
            try { AppSettings.prefs(app, n).getAll(); } catch (Throwable ignored) { }
        }
        if (uid == null) return;
        try {
            LocalDb.init(app, uid);   // synchronized وتتجاهل المكرر: استدعاء MassActivity لها يصير فورياً
            LocalDb.getMyProfile();
            LocalDb.getContacts();
            org.json.JSONArray chats = LocalDb.getChats();
            if (chats != null) {
                for (int i = 0; i < Math.min(2, chats.length()); i++) {   // أعلى محادثتين: الأرجح فتحهما أولاً
                    String cid = chats.getJSONObject(i).optString("cid");
                    if (cid.isEmpty()) continue;
                    LocalDb.resumeStart(cid);
                    LocalDb.messagesBefore(cid, 0L, null, 25);
                }
            }
        } catch (Throwable ignored) { }
        try {   // يفتح مخزن Firestore المحلي الآن بدل أول استعلام للشاشة
            com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("users").document(uid)
                    .get(com.google.firebase.firestore.Source.CACHE);
        } catch (Throwable ignored) { }
    }

    @Override
    public void onBackPressed() { /* شاشة البداية: لا خروج من التطبيق */ }

    private static final int REQ_FIRST_PERMS = 2001;

    /** كل الأذونات المطلوبة للتطبيق (الميكروفون، الصور/الفيديو، الإشعارات، التخزين حسب إصدار أندرويد) التي لم تُمنح بعد. */
    private String[] missingPermissions() {
        List<String> all = new ArrayList<>();
        all.add(android.Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33) {
            all.add("android.permission.READ_MEDIA_IMAGES");
            all.add("android.permission.READ_MEDIA_VIDEO");
            all.add("android.permission.POST_NOTIFICATIONS");
        } else {
            all.add(android.Manifest.permission.READ_EXTERNAL_STORAGE);
            if (Build.VERSION.SDK_INT <= 28) all.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        List<String> need = new ArrayList<>();
        for (String p : all) if (checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED) need.add(p);
        return need.toArray(new String[0]);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_FIRST_PERMS) proceed();   // سواء مُنحت أو رُفضت نكمل الدخول
    }

    private void goNext() {
        if (navigated || isFinishing()) return;
        if (!earlyStarted && !navigated && Boolean.TRUE.equals(earlyOk) && goWaits++ < 60) {   // الإطلاق المبكر قيد الانتظار (نفخ/فحص خلفي): نمهله قليلاً بدل المسار القديم
            handler.postDelayed(this::goNext, 25); return;
        }
        if (earlyStarted) { revealEarly(); return; }
        android.content.SharedPreferences sp = AppSettings.prefs(this, "babylon_boot");
        if (Build.VERSION.SDK_INT >= 23 && !sp.getBoolean("perms_asked", false)) {   // أول دخول فقط
            sp.edit().putBoolean("perms_asked", true).apply();
            String[] need = missingPermissions();
            if (need.length > 0) { requestPermissions(need, REQ_FIRST_PERMS); return; }
        }
        proceed();
    }

    /**
     * بناء الشاشة التالية يشغل الخيط الرئيسي لأجزاء من الثانية، وأي حركة دائرية (النبض، التوهج، النقاط، اللمعان) تتجمّد وسط دورتها فيظهر «توقف».
     * الحل: نُنهي هذه الحركات بنعومة (الشعار يستقر على حجمه الطبيعي، التوهج والنقاط يخبوان) خلال ~200ms، ثم نبدأ الانتقال،
     * فيكون أي تجمّد لاحق على مشهد ساكن لا يُلاحَظ، ثم تظهر الواجهة التالية بالتلاشي.
     */
    private void settleThen(final Runnable next) {
        for (Animator a : animators) a.cancel();
        animators.clear();
        if (logoAnim != null) { logoAnim.cancel(); logoAnim = null; }
        applyLogo(T_SETTLE_B);   // الشعار والعنوان بحالتهما النهائية (حجم طبيعي) قبل الانتقال لشاشة الدخول
        android.view.animation.DecelerateInterpolator di = new android.view.animation.DecelerateInterpolator();
        binding.logoGlow.animate().cancel();
        binding.logoGlow.setAlpha(0f);   // لا يبقى توهج نصف خابٍ مجمّداً أثناء بناء الشاشة التالية
        handler.post(next);              // فوراً: كل التجهيز الثقيل تم في الخلفية أثناء السبلاش
    }

    private void proceed() {
        if (navigated || isFinishing()) return;
        navigated = true;
        final boolean loggedIn = FirebaseAuth.getInstance().getCurrentUser() != null;
        settleThen(() -> {
            if (isFinishing()) return;
            if (loggedIn && AppSettings.prefs(this, VerifyActivity.PREFS).getString(VerifyActivity.KEY_PENDING, null) != null) {
                Intent v = new Intent(this, VerifyActivity.class);   // أُغلق التطبيق قبل إكمال التحقق: نكمل من نفس الشاشة
                v.putExtra(VerifyActivity.EXTRA_SEND, false);
                startActivity(v);
                overridePendingTransition(0, 0);
                finish();
                return;
            }
            if (loggedIn) {
                startActivity(new Intent(this, MassActivity.class));
                overridePendingTransition(R.anim.chat_fade_in, R.anim.splash_hold);   // صفحة المحادثات تتلاشى شفافةً فوق السبلاش الثابت
                finish();
                return;
            }
            // غير مسجّل: نمرّر موضع/حجم اللوكو والعنوان لشاشة الدخول لتكمل الحركة من نفس المكان (الشعار الآن بحجمه الطبيعي)
            Intent i = new Intent(this, Main2Activity.class);
            putViewInfo(i, binding.logoCircle, "logo");
            putViewInfo(i, binding.title, "title");
            startActivity(i);
            overridePendingTransition(0, 0);   // بلا انتقال افتراضي ليبدو اللوكو ثابتاً
            finish();
        });
    }

    /** يحفظ مركز الـView على الشاشة (بالبكسل) وعرضه الظاهر الحالي مع احتساب نبض الـscale. */
    private void putViewInfo(Intent i, View v, String key) {
        float s = v.getScaleX();
        v.setScaleX(1f);
        v.setScaleY(1f);
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        v.setScaleX(s);
        v.setScaleY(s);
        i.putExtra(key + "_cx", loc[0] + v.getWidth() / 2f);
        i.putExtra(key + "_cy", loc[1] + v.getHeight() / 2f);
        i.putExtra(key + "_w", v.getWidth() * s);
    }

    private void setupSystemBars() {
        Window w = getWindow();
        w.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.setStatusBarColor(0x00000000);
        w.setNavigationBarColor(0x00000000);
        if (Build.VERSION.SDK_INT >= 29) {
            w.setNavigationBarContrastEnforced(false);
            w.setStatusBarContrastEnforced(false);
        }
        AuthTheme.systemBars(w, AuthTheme.isDark(this));
    }

    private static float seg(float t, float a, float b) { float v = (t - a) / (b - a); return v < 0f ? 0f : (v > 1f ? 1f : v); }

    /** يضبط كل عناصر الشعار عند الزمن t (ms) من الحركة. */
    private void applyLogo(float t) {
        final float d = getResources().getDisplayMetrics().density;
        float circle = ease.getInterpolation(seg(t, T_CIRCLE_A, T_CIRCLE_B));
        float logo = ease.getInterpolation(seg(t, T_LOGO_A, T_LOGO_B));
        float color = ease.getInterpolation(seg(t, T_COLOR_A, T_COLOR_B));
        float settle = ease.getInterpolation(seg(t, T_SETTLE_A, T_SETTLE_B));

        binding.logoDisc.setDraw(circle);
        binding.logoDisc.setFill(color);
        binding.logoImg.setDrawProgress(logo);
        binding.logoImg.setFillProgress(color);

        float big = DRAW_DP / DISC_DP;                       // 150dp أثناء الرسم ← 128dp عند الاكتمال
        float sc = 1f + (big - 1f) * (1f - settle);
        binding.logoFrame.setScaleX(sc);
        binding.logoFrame.setScaleY(sc);
        binding.logoFrame.setTranslationY(startTy * (1f - settle));   // يرتفع قليلاً ليفسح مكان العنوان

        binding.logoGlow.setAlpha(0.3f * settle);
        binding.title.setAlpha(settle);
        binding.title.setTranslationY(12f * d * (1f - settle));
    }

    private void startAnimations() {
        // الشعار يبدأ بقطر 150dp في مركز الشاشة: تُرسم الدائرة الذهبية ثم الشعار، ثم يتلوّنان ويعودان إلى 128dp ويرتفعان فيظهر العنوان
        final View root = binding.getRoot();
        root.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                root.getViewTreeObserver().removeOnPreDrawListener(this);
                final float d = getResources().getDisplayMetrics().density;
                startTy = (binding.title.getHeight() - 22.5f * d) / 2f;   // نصف (ارتفاع العنوان + هامشه السالب) لوضع الشعار في مركز الشاشة
                applyLogo(0f);   // الحالة الأولى قبل أول إطار (بلا وميض)
                logoAnim = ValueAnimator.ofFloat(0f, T_SETTLE_B);
                logoAnim.setDuration((long) T_SETTLE_B);
                logoAnim.setInterpolator(new LinearInterpolator());
                logoAnim.addUpdateListener(a -> applyLogo((float) a.getAnimatedValue()));
                logoAnim.addListener(new android.animation.AnimatorListenerAdapter() {
                    boolean cancelled;
                    @Override public void onAnimationCancel(Animator a) { cancelled = true; }
                    @Override public void onAnimationEnd(Animator a) {
                        if (cancelled || isFinishing() || navigated) return;
                        binding.logoGlow.setAlpha(0.3f);
                        play(binding.logoGlow, R.drawable.glow_pulse);   // توهج هادئ بعد الاستقرار
                    }
                });
                logoAnim.start();
                return true;
            }
        });

        // تدرّج ذهبي ثابت (غير متحرك) على عنوان "Babylon Chat" بنفس تدرّج القرص الذهبي (bg_logo: زاوية 315°)
        binding.title.post(() -> {
            float w = binding.title.getPaint().measureText(binding.title.getText().toString());
            float h = binding.title.getHeight();
            if (w <= 0) return;
            binding.title.getPaint().setShader(new LinearGradient(0, 0, w, h,
                    new int[]{0xFFEDC548, 0xFFD9AE31, 0xFFBF9220}, null, Shader.TileMode.CLAMP));
            binding.title.invalidate();
        });
    }

    private void play(View target, int animRes) {
        try {
            Animator a = AnimatorInflater.loadAnimator(this, animRes);
            a.setTarget(target);
            a.start();
            animators.add(a);
        } catch (Exception ignored) {
            // الأنيميشن تجميلي فقط؛ لا نوقف التطبيق بسببه
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        for (Animator a : animators) a.cancel();
        if (logoAnim != null) logoAnim.cancel();
        if (binding != null && binding.getRoot().getBackground() instanceof AuthBg) ((AuthBg) binding.getRoot().getBackground()).stopIntro();
    }
}
