package com.babylon.chat.iq;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Shader;
import android.os.Build;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.ScrollView;
import android.widget.TextView;

import com.babylon.chat.iq.databinding.Main2Binding;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;

/** شاشة تسجيل الدخول / إنشاء حساب (Firebase Auth + Firestore). */
public class Main2Activity extends Activity {

    private Main2Binding binding;
    private FirebaseAuth auth;
    private FirebaseFirestore db;
    private boolean dark;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = Main2Binding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        auth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();

        dark = AuthTheme.isDark(this);
        applyTheme();
        setupSystemBars();
        setupTitleShine();
        setupPressAnim();

        setupEnterAnimation();

        binding.btnLogin.setOnClickListener(v -> doLogin());
        binding.btnRegister.setOnClickListener(v -> doRegister());
        binding.btnForgot.setOnClickListener(v -> doForgot());
        binding.btnForgot.setVisibility(View.GONE);   // تظهر «نسيت كلمة المرور؟» فقط بعد كلمة مرور خاطئة؛ ولا تحجز مكاناً فيبقى «إنشاء حساب جديد» قريباً من زر الدخول، ثم ينزلق للأسفل بحركة ناعمة عند ظهورها
        // لا شريط تبويبات: الدخول هو الواجهة الأولى، ومنها رابط «إنشاء حساب جديد» (وفي التسجيل رابط العودة)
        binding.btnNewAccount.setOnClickListener(v -> showRegister());
        binding.btnHaveAccount.setOnClickListener(v -> showLogin());

        // إشعار قادم من الشاشة السابقة (مثل «تم تسجيل الخروج»)
        final String carried = getIntent() != null ? getIntent().getStringExtra("bb_toast") : null;
        if (carried != null) binding.getRoot().postDelayed(() -> toast(carried), 450);
    }

    // ======================= حركة الدخول من السبلاش =======================

    /**
     * يبدأ اللوكو والعنوان من موضع وحجم السبلاش، ثم يرتفعان/يصغران إلى مكانهما في شاشة الدخول،
     * وبعدها تظهر باقي العناصر (الوصف، التبويبات، الحقول والأزرار) تدريجياً.
     */
    private void setupEnterAnimation() {
        final Intent in = getIntent();
        if (in == null || !in.hasExtra("logo_cx")) return;   // فتح عادي (مثلاً بعد تسجيل الخروج): بلا حركة

        final float d = getResources().getDisplayMetrics().density;
        final View[] rest = {binding.subtitle, binding.loginForm};
        for (View v : rest) {
            v.setAlpha(0f);
            v.setTranslationY(28 * d);
        }
        binding.btnLogin.setEnabled(false);

        final View logo = binding.logoCircle;
        final View title = binding.title;
        logo.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                logo.getViewTreeObserver().removeOnPreDrawListener(this);
                if (logo.getWidth() == 0) return true;

                // نقطة البداية (السبلاش) بالنسبة للمكان النهائي
                startFrom(logo, in.getFloatExtra("logo_cx", 0), in.getFloatExtra("logo_cy", 0), in.getFloatExtra("logo_w", logo.getWidth()));
                startFrom(title, in.getFloatExtra("title_cx", 0), in.getFloatExtra("title_cy", 0), in.getFloatExtra("title_w", title.getWidth()));

                final long dur = 700;
                android.view.animation.Interpolator ease = new android.view.animation.PathInterpolator(0.22f, 0.9f, 0.25f, 1f);
                for (View v : new View[]{logo, title}) {
                    v.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f)
                            .setStartDelay(60).setDuration(dur).setInterpolator(ease).start();
                }

                // بعد انتهاء الارتفاع/التصغير: ظهور الوصف ثم التبويبات ثم الحقول والأزرار
                binding.root.postDelayed(() -> {
                    long delay = 0;
                    for (View v : rest) {
                        v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(420)
                                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
                        delay += 110;
                    }
                    binding.btnLogin.setEnabled(true);
                }, 60 + dur - 120);
                return true;
            }
        });
    }

    /** يضع الـView بصرياً في مركز/عرض معيّن على الشاشة (ثم نحرّكه إلى وضعه الطبيعي). */
    private void startFrom(View v, float cx, float cy, float width) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        float myCx = loc[0] + v.getWidth() / 2f, myCy = loc[1] + v.getHeight() / 2f;
        float s = v.getWidth() > 0 ? width / v.getWidth() : 1f;
        v.setTranslationX(cx - myCx);
        v.setTranslationY(cy - myCy);
        v.setScaleX(s);
        v.setScaleY(s);
    }

    // ======================= تسجيل الدخول =======================

    private void doLogin() {
        String input = binding.editUser.getText().toString().trim();
        String pass = binding.editPass.getText().toString();

        if (input.isEmpty() || pass.isEmpty()) {
            toast("أدخل البريد أو اسم المستخدم وكلمة المرور");
            return;
        }
        setBusy(binding.btnLogin, true);
        toast("جارٍ تسجيل الدخول");

        if (input.indexOf('@') > 0) {
            signIn(input, pass); // بريد إلكتروني
        } else {
            // اسم مستخدم: نجلب البريد من Firestore
            final String u = input.startsWith("@") ? input.substring(1) : input;
            db.collection("users").whereEqualTo("username", u).limit(1).get()
                    .addOnSuccessListener(qs -> {
                        if (qs.isEmpty()) {
                            setBusy(binding.btnLogin, false);
                            toast("اسم المستخدم غير موجود");
                            return;
                        }
                        String email = qs.getDocuments().get(0).getString("email");
                        if (email == null || email.isEmpty()) {
                            setBusy(binding.btnLogin, false);
                            toast("لا يوجد بريد مرتبط بهذا المستخدم");
                            return;
                        }
                        signIn(email, pass);
                    })
                    .addOnFailureListener(e -> {
                        setBusy(binding.btnLogin, false);
                        toast("تعذّر الاتصال، تحقق من الإنترنت");
                    });
        }
    }

    private void signIn(String email, String pass) {
        auth.signInWithEmailAndPassword(email, pass)
                .addOnSuccessListener(r -> afterLogin(r.getUser()))
                .addOnFailureListener(e -> {
                    setBusy(binding.btnLogin, false);
                    if (e instanceof com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
                            || e instanceof com.google.firebase.auth.FirebaseAuthInvalidUserException) {
                        boolean first = binding.btnForgot.getVisibility() != View.VISIBLE;
                        showForgot();
                        if (first) {   // ننتظر انتهاء انزلاق «إنشاء حساب جديد» كي يظهر الإشعار تحته في مكانه النهائي
                            binding.getRoot().postDelayed(() -> toast("البريد/اسم المستخدم أو كلمة المرور غير صحيحة"), 280);
                            return;
                        }
                    }
                    toast("البريد/اسم المستخدم أو كلمة المرور غير صحيحة");
                });
    }

    /** بعد الدخول: الانتقال مباشرة إلى التطبيق. */
    private void afterLogin(final FirebaseUser user) {
        goToMass("تم تسجيل الدخول بنجاح");
    }

    // ======================= إنشاء حساب =======================

    private void doRegister() {
        final String name = binding.editName.getText().toString().trim();
        final String email = binding.editEmail.getText().toString().trim();
        final String pass = binding.editRegPass.getText().toString();
        final String pass2 = binding.editRegPass2.getText().toString();

        if (name.isEmpty() || email.isEmpty() || pass.isEmpty() || pass2.isEmpty()) {
            toast("املأ جميع الحقول");
            return;
        }
        if (email.indexOf('@') <= 0 || !email.contains(".")) {
            toast("البريد الإلكتروني غير صالح");
            return;
        }
        if (pass.length() < 6) {
            toast("كلمة المرور 6 أحرف على الأقل");
            return;
        }
        if (!pass.equals(pass2)) {
            toast("كلمتا المرور غير متطابقتين");
            return;
        }

        setBusy(binding.btnRegister, true);
        toast("جارٍ إنشاء الحساب");

        // 1) إنشاء حساب المصادقة
        auth.createUserWithEmailAndPassword(email, pass)
                .addOnSuccessListener(r -> {
                    final FirebaseUser user = r.getUser();
                    if (user == null) {
                        setBusy(binding.btnRegister, false);
                        toast("حدث خطأ، حاول مرة أخرى");
                        return;
                    }
                    saveProfile(user, name, email);   // اسم المستخدم اختياري: يُضاف لاحقاً من تعديل الملف الشخصي
                })
                .addOnFailureListener(e -> {
                    setBusy(binding.btnRegister, false);
                    toast(registerError(e));
                });
    }

    private void saveProfile(FirebaseUser user, String name, String email) {
        Map<String, Object> data = new HashMap<>();
        data.put("name", name);
        data.put("email", email);
        data.put("bio", "");
        data.put("gov", "");
        data.put("createdAt", System.currentTimeMillis());

        db.collection("users").document(user.getUid()).set(data)
                .addOnSuccessListener(v -> goToMass("تم إنشاء الحساب بنجاح"))
                .addOnFailureListener(e -> {
                    setBusy(binding.btnRegister, false);
                    toast("تم إنشاء الحساب لكن تعذّر حفظ البيانات، تحقق من قواعد Firestore");
                });
    }

    private String registerError(Exception e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (m.contains("already in use")) return "هذا البريد مسجّل مسبقاً";
        if (m.contains("badly formatted")) return "البريد الإلكتروني غير صالح";
        if (m.contains("network")) return "تعذّر الاتصال، تحقق من الإنترنت";
        return "فشل إنشاء الحساب";
    }

    // ======================= نسيت كلمة المرور =======================

    private void doForgot() {
        String input = binding.editUser.getText().toString().trim();
        if (input.indexOf('@') <= 0) {
            toast("اكتب بريدك الإلكتروني في الحقل أولاً");
            return;
        }
        toast("جاري إرسال رابط الاستعادة...");
        auth.sendPasswordResetEmail(input)
                .addOnSuccessListener(v -> toast("تم إرسال رابط إعادة التعيين إلى بريدك"))
                .addOnFailureListener(e -> toast("تعذّر الإرسال، تأكد من البريد"));
    }

    // ======================= مساعدات =======================

    private void goToMass(String welcomeToast) {
        Intent i = new Intent(this, MassActivity.class);
        if (welcomeToast != null) i.putExtra("bb_toast", welcomeToast);   // يُعرض في الشاشة التالية لأن هذه تُغلق
        startActivity(i);
        overridePendingTransition(0, 0);   // بلا أنيميشن دخول لصفحة المحادثات
        finish();
    }

    /** يُظهر «نسيت كلمة المرور؟» بين زر الدخول و«إنشاء حساب جديد» فينزلق الأخير للأسفل بحركة ناعمة. */
    private void showForgot() {
        if (binding.btnForgot.getVisibility() == View.VISIBLE) return;
        android.transition.AutoTransition t = new android.transition.AutoTransition();
        t.setDuration(260);
        android.transition.TransitionManager.beginDelayedTransition(binding.loginForm, t);
        binding.btnForgot.setVisibility(View.VISIBLE);
    }

    private void setBusy(TextView btn, boolean busy) {
        btn.setEnabled(!busy);
        btn.setAlpha(busy ? 0.6f : 1f);
    }

    private void toast(String s) {
        // يظهر الإشعار تحت «إنشاء حساب جديد» (آخر عنصر في نموذج الدخول) أو تحت زر التسجيل في نموذج إنشاء الحساب
        final View anchor = binding.loginForm.getVisibility() == View.VISIBLE ? binding.btnNewAccount : binding.btnRegister;
        AppToast.show(this, s, dark, anchor);
    }

    @Override
    public void onBackPressed() {
        // من نموذج إنشاء الحساب نرجع لنموذج الدخول؛ وفي غير ذلك لا نُغلق التطبيق
        if (binding != null && binding.registerForm.getVisibility() == View.VISIBLE) showLogin();
    }

    // ======================= الواجهة =======================

    /** الواجهة الأولى هي الدخول؛ «إنشاء حساب جديد» يعرض نموذج التسجيل، ورابط «لديك حساب؟» يرجع للدخول. */
    private void showLogin() { switchForm(false); }

    private void showRegister() { switchForm(true); }

    private boolean switching = false;

    /**
     * انتقال بين الدخول والتسجيل بطابع «ختم بابل الذهبي»:
     * موجة ذهبية تتسع كحلقة من الزر الذي لُمس، واللوغو يدور دورة كاملة كعملة ذهبية،
     * وحقول النموذج الحالي تنسحب متتابعة ثم حقول النموذج الجديد تنساب متتابعة من الجهة المقابلة.
     */
    private void switchForm(final boolean toRegister) {
        final View outF = toRegister ? binding.loginForm : binding.registerForm;
        final View inF = toRegister ? binding.registerForm : binding.loginForm;
        if (switching || inF.getVisibility() == View.VISIBLE) return;
        if (outF.getHeight() == 0) { outF.setVisibility(View.GONE); inF.setVisibility(View.VISIBLE); return; }
        switching = true;
        try { android.view.inputmethod.InputMethodManager im = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE); if (im != null) im.hideSoftInputFromWindow(binding.root.getWindowToken(), 0); } catch (Throwable ignored) { }
        final float d = getResources().getDisplayMetrics().density;
        final View[] head = {binding.logoCircle, binding.title, binding.subtitle};
        final android.view.animation.Interpolator out = new android.view.animation.AccelerateInterpolator(1.4f);
        final android.view.animation.Interpolator in = new android.view.animation.DecelerateInterpolator(2.2f);
        final float dir = toRegister ? 1f : -1f;           // اتجاه خروج النموذج القديم (ودخول الجديد من الجهة المعاكسة)
        final int TOTAL = 980, SWAP = 300;

        // ---- 1) الموجة الذهبية من الزر الذي لُمس ----
        final View src = toRegister ? binding.btnNewAccount : binding.btnHaveAccount;
        final ViewGroup lay = binding.linear26;
        int[] a = new int[2], b = new int[2];
        src.getLocationOnScreen(a); lay.getLocationOnScreen(b);
        final float cx = a[0] - b[0] + src.getWidth() / 2f, cy = a[1] - b[1] + src.getHeight() / 2f;
        final float maxR = (float) Math.hypot(Math.max(cx, lay.getWidth() - cx), Math.max(cy, lay.getHeight() - cy)) * 1.05f;
        final GoldWave wave = new GoldWave(cx, cy, maxR, dark);
        wave.setBounds(0, 0, lay.getWidth(), lay.getHeight());
        lay.getOverlay().add(wave);
        android.animation.ValueAnimator wv = android.animation.ValueAnimator.ofFloat(0f, 1f);
        wv.setDuration(TOTAL); wv.setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0.6f, 0.3f, 1f));
        wv.addUpdateListener(an -> wave.setProgress((float) an.getAnimatedValue()));
        wv.addListener(new android.animation.AnimatorListenerAdapter() { @Override public void onAnimationEnd(android.animation.Animator an) { lay.getOverlay().remove(wave); } });
        wv.start();

        // ---- 2) اللوغو يدور كعملة ذهبية، والعنوان يتنفس ----
        binding.logoCircle.setCameraDistance(9000f * d);
        binding.logoCircle.animate().rotationYBy(360f * dir).setDuration(TOTAL - 120)
                .setInterpolator(new android.view.animation.PathInterpolator(0.55f, 0f, 0.2f, 1f)).withEndAction(() -> binding.logoCircle.setRotationY(0f)).start();
        for (View h : new View[]{binding.title, binding.subtitle}) {
            h.animate().alpha(0.25f).scaleX(0.96f).scaleY(0.96f).setDuration(SWAP).setInterpolator(out)
                    .withEndAction(() -> h.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(in).start()).start();
        }

        // ---- 3) انسحاب حقول النموذج الحالي بالتتابع ----
        final java.util.List<View> outKids = visibleKids((ViewGroup) outF);
        for (int i = 0; i < outKids.size(); i++) {
            outKids.get(i).animate().alpha(0f).translationX(dir * 46f * d).setStartDelay(i * 28L).setDuration(190).setInterpolator(out).start();
        }

        // ---- 4) عند اكتمال الانسحاب: تبديل النموذجين ثم انسياب الجديد ----
        binding.getRoot().postDelayed(() -> {
            final float topBefore = binding.logoCircle.getTop() + binding.logoCircle.getTranslationY();
            for (View k : outKids) { k.animate().cancel(); k.setAlpha(1f); k.setTranslationX(0f); }
            outF.setVisibility(View.GONE);
            inF.setVisibility(View.VISIBLE);
            final java.util.List<View> inKids = visibleKids((ViewGroup) inF);
            for (View k : inKids) { k.setAlpha(0f); k.setTranslationX(-dir * 46f * d); }
            lay.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    lay.getViewTreeObserver().removeOnPreDrawListener(this);
                    // الرأس يبقى بصرياً في مكانه رغم تغيّر ارتفاع النموذج ثم ينزلق لمكانه الجديد بنعومة
                    final float shift = topBefore - binding.logoCircle.getTop();
                    for (View h : head) { h.setTranslationY(shift); h.animate().translationY(0f).setDuration(520).setInterpolator(in).start(); }
                    for (int i = 0; i < inKids.size(); i++) {
                        inKids.get(i).animate().alpha(1f).translationX(0f).setStartDelay(60L + i * 42L).setDuration(430).setInterpolator(in).start();
                    }
                    long end = 60L + inKids.size() * 42L + 450L;
                    binding.getRoot().postDelayed(() -> switching = false, Math.max(end, TOTAL - SWAP));
                    return true;
                }
            });
        }, SWAP);
    }

    private java.util.List<View> visibleKids(ViewGroup g) {
        java.util.ArrayList<View> l = new java.util.ArrayList<>();
        for (int i = 0; i < g.getChildCount(); i++) if (g.getChildAt(i).getVisibility() == View.VISIBLE) l.add(g.getChildAt(i));
        return l;
    }

    /** موجة ذهبية: حلقة مضيئة تتسع من نقطة اللمس مع غسل ذهبي خفيف خلفها، ثم تتلاشى. */
    private static final class GoldWave extends android.graphics.drawable.Drawable {
        private final float cx, cy, maxR; private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final int rgb; private final float peak; private float t = 0f;
        GoldWave(float cx, float cy, float maxR, boolean dark) { this.cx = cx; this.cy = cy; this.maxR = maxR; rgb = 0xC4961A; peak = dark ? 0.50f : 0.42f; }
        void setProgress(float v) { t = v; invalidateSelf(); }
        @Override public void draw(android.graphics.Canvas c) {
            float r = Math.max(1f, maxR * t);
            float fade = t < 0.15f ? t / 0.15f : (float) Math.pow(1f - (t - 0.15f) / 0.85f, 1.3);   // يظهر بسرعة ثم يتلاشى
            int ring = ((int) (255 * peak * fade) << 24) | rgb, wash = ((int) (255 * peak * 0.22f * fade) << 24) | rgb, none = rgb;
            p.setShader(new android.graphics.RadialGradient(cx, cy, r,
                    new int[]{none, wash, ring, none}, new float[]{0f, 0.62f, 0.93f, 1f}, android.graphics.Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, p);
        }
        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /** الطور الليلي: الخلفية، الحقول، التسميات والنصوص. */
    private void applyTheme() {
        float d = getResources().getDisplayMetrics().density;
        binding.root.setBackground(AuthTheme.authBg(this, dark));   // التدرّج + الموجتان الذهبيتان (نهاري/ليلي)
        setupFieldIcons();
        if (!dark) return;
        binding.subtitle.setTextColor(AuthTheme.MUTED);
        TextView[] labels = {binding.labelUser, binding.labelPass, binding.labelName,
                binding.labelEmail, binding.labelRegPass, binding.labelRegPass2, binding.btnForgot,
                binding.btnNewAccount, binding.btnHaveAccount};
        for (TextView t : labels) t.setTextColor(AuthTheme.GOLD_LABEL);
        android.widget.EditText[] inputs = {binding.editUser, binding.editPass, binding.editName,
                binding.editEmail, binding.editRegPass, binding.editRegPass2};
        for (android.widget.EditText e : inputs) {
            e.setBackground(AuthTheme.goldBox(true, 26 * d, Math.max(1, Math.round(0.5f * d))));
            e.setTextColor(AuthTheme.TEXT);
            e.setHintTextColor(AuthTheme.HINT);
        }
    }

    /** أيقونات الحقول (يسار) + زر إظهار/إخفاء كلمة المرور (يمين) كما في التصميم الجديد؛ ألوانها من ألوان المشروع. */
    private void setupFieldIcons() {
        final int ic = dark ? AuthTheme.HINT : 0xFF6B7A8C;
        final int pad = Math.round(12 * getResources().getDisplayMetrics().density);
        final int[][] map = {
            {R.id.edit_user, R.drawable.ic_mail_20}, {R.id.edit_pass, R.drawable.ic_lock_20},
            {R.id.edit_name, R.drawable.ic_user_22},
            {R.id.edit_email, R.drawable.ic_mail_20}, {R.id.edit_reg_pass, R.drawable.ic_lock_20}, {R.id.edit_reg_pass2, R.drawable.ic_lock_20}};
        for (int[] m : map) {
            final android.widget.EditText e = findViewById(m[0]);
            if (e == null) continue;
            e.setCompoundDrawablePadding(pad);
            final android.graphics.drawable.Drawable left = getResources().getDrawable(m[1]).mutate();
            left.setColorFilter(ic, android.graphics.PorterDuff.Mode.SRC_IN);
            final boolean isPass = m[0] == R.id.edit_pass || m[0] == R.id.edit_reg_pass || m[0] == R.id.edit_reg_pass2;
            if (!isPass) { e.setCompoundDrawablesWithIntrinsicBounds(left, null, null, null); continue; }
            final boolean[] shown = {false};
            final Runnable apply = () -> {
                android.graphics.drawable.Drawable eye = getResources().getDrawable(shown[0] ? R.drawable.ic_eye_20 : R.drawable.ic_eye_off_20).mutate();
                eye.setColorFilter(ic, android.graphics.PorterDuff.Mode.SRC_IN);
                int sel = e.getSelectionEnd();
                e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | (shown[0]
                        ? android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD));
                e.setTypeface(android.graphics.Typeface.DEFAULT);
                e.setCompoundDrawablesWithIntrinsicBounds(left, null, eye, null);
                try { e.setSelection(Math.max(0, sel)); } catch (Throwable ignored) { }
            };
            apply.run();
            e.setOnTouchListener((v, ev) -> {
                if (ev.getAction() == MotionEvent.ACTION_UP) {
                    android.graphics.drawable.Drawable r = e.getCompoundDrawables()[2];
                    if (r != null && ev.getX() >= e.getWidth() - e.getPaddingRight() - r.getIntrinsicWidth() - 12 * getResources().getDisplayMetrics().density) {
                        shown[0] = !shown[0]; apply.run(); return true;
                    }
                }
                return false;
            });
        }
    }

    private void setupPressAnim() {
        View.OnTouchListener pressAnim = (v, e) -> {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:   // ضغط واضح: انكماش سريع + خفوت
                    v.animate().cancel();
                    v.animate().scaleX(0.88f).scaleY(0.88f).alpha(0.75f).setDuration(90)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:   // الرجوع بارتداد (نبضة) ليُحسّ النقر
                    v.animate().cancel();
                    v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(320)
                            .setInterpolator(new android.view.animation.OvershootInterpolator(3.2f)).start();
                    break;
            }
            return false;
        };
        binding.btnLogin.setOnTouchListener(pressAnim);
        binding.btnRegister.setOnTouchListener(pressAnim);
        binding.btnForgot.setOnTouchListener(pressAnim);
        binding.btnNewAccount.setOnTouchListener(pressAnim);
        binding.btnHaveAccount.setOnTouchListener(pressAnim);
        // ارتداد الضغط يكبّر الزر لحظياً فوق حجمه (Overshoot) فكانت حدوده تُقصّ عند حافة الحاوية: لا قصّ في أي أب
        for (View b : new View[]{binding.btnLogin, binding.btnRegister, binding.btnForgot, binding.btnNewAccount, binding.btnHaveAccount}) noClipParents(b);
    }

    private static void noClipParents(View v) {
        android.view.ViewParent p = v.getParent();
        while (p instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) p;
            g.setClipChildren(false);
            g.setClipToPadding(false);
            p = g.getParent();
        }
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

        AuthTheme.systemBars(w, dark);

        final View rootView = binding.root;
        binding.root.setClipToPadding(false);   // المحتوى يمرّ خلف شريط الإشعارات بدل أن يُقطع عند حافته
        rootView.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom, ime = 0;
            if (Build.VERSION.SDK_INT >= 30) {
                top = insets.getInsets(WindowInsets.Type.systemBars()).top;
                int nav = insets.getInsets(WindowInsets.Type.systemBars()).bottom;
                ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
                bottom = Math.max(nav, ime);
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
                // على الأجهزة القديمة: أي إزاحة كبيرة في الأسفل تعني أن الكيبورد مفتوح
                ime = bottom > dp(120) ? bottom : 0;
            }
            v.setPadding(0, top, 0, bottom);

            final boolean shown = ime > 0;
            if (shown != keyboardShown) {
                keyboardShown = shown;
                if (shown) v.postDelayed(this::scrollFormIntoView, 120);
            }
            return insets;
        });
        rootView.requestApplyInsets();

        // عند الضغط على أي حقل والكيبورد مفتوح: اجعل الحقل وزر الإجراء ظاهرين فوق الكيبورد
        View.OnFocusChangeListener focusScroll = (fv, has) -> {
            if (has && keyboardShown) fv.postDelayed(this::scrollFormIntoView, 120);
        };
        binding.editUser.setOnFocusChangeListener(focusScroll);
        binding.editPass.setOnFocusChangeListener(focusScroll);
        binding.editName.setOnFocusChangeListener(focusScroll);
        binding.editEmail.setOnFocusChangeListener(focusScroll);
        binding.editRegPass.setOnFocusChangeListener(focusScroll);
        binding.editRegPass2.setOnFocusChangeListener(focusScroll);
    }

    private boolean keyboardShown = false;

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** يمرّر الشاشة بحيث يظهر الحقل المحدد كاملاً مع زر (دخول/إنشاء) تحت الكيبورد مباشرة. */
    private void scrollFormIntoView() {
        final ScrollView sv = binding.root;
        final View content = binding.linear26;
        final boolean loginVisible = binding.loginForm.getVisibility() == View.VISIBLE;
        final View btn = loginVisible ? binding.btnLogin : binding.btnRegister;

        int btnBottom = offsetIn(content, btn) + btn.getHeight() + dp(16);
        int viewportH = sv.getHeight() - sv.getPaddingTop() - sv.getPaddingBottom();
        int target = Math.max(0, btnBottom - viewportH);

        // لا تُخفِ الحقل المحدد أعلى الشاشة
        View f = getCurrentFocus();
        if (f != null && f != btn && isDescendant(content, f)) {
            int fieldTop = offsetIn(content, f) - dp(40);
            target = Math.min(target, Math.max(0, fieldTop));
        }
        sv.smoothScrollTo(0, target);
    }

    private static int offsetIn(View ancestor, View child) {
        int y = 0;
        View v = child;
        while (v != null && v != ancestor) {
            y += v.getTop();
            if (!(v.getParent() instanceof View)) break;
            v = (View) v.getParent();
        }
        return y;
    }

    private static boolean isDescendant(View ancestor, View child) {
        View v = child;
        while (v != null) {
            if (v == ancestor) return true;
            if (!(v.getParent() instanceof View)) return false;
            v = (View) v.getParent();
        }
        return false;
    }

    private void setupTitleShine() {
        final TextView title = binding.title;
        title.post(() -> {
            float w = title.getPaint().measureText(title.getText().toString());
            if (w <= 0) return;
            final float tile = w * 2f;
            int[] colors = {0xFFB88A18, 0xFFD4A528, 0xFFF9D96A, 0xFFD4A528, 0xFFB88A18, 0xFFB88A18};
            float[] pos = {0f, 0.2f, 0.4f, 0.6f, 0.8f, 1f};
            final LinearGradient grad = new LinearGradient(0, 0, tile, 0, colors, pos, Shader.TileMode.REPEAT);
            title.getPaint().setShader(grad);

            final Matrix m = new Matrix();
            ValueAnimator shine = ValueAnimator.ofFloat(0f, tile);
            shine.setDuration(15000);
            shine.setInterpolator(new LinearInterpolator());
            shine.setRepeatCount(ValueAnimator.INFINITE);
            shine.addUpdateListener(a -> {
                m.setTranslate((float) a.getAnimatedValue(), 0f);
                grad.setLocalMatrix(m);
                title.invalidate();
            });
            shine.start();
        });
    }
}
