package com.babylon.chat.iq;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.PorterDuff;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.DigitsKeyListener;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.firebase.auth.FirebaseAuth;

import java.util.Locale;

/**
 * شاشة التحقق من البريد (رمز من 6 أرقام) — تظهر بعد إنشاء الحساب، وعند تسجيل الدخول بحساب لم يُتحقَّق منه بعد.
 * «إعادة إرسال الرمز» مخفية أثناء العدّ التنازلي (60ث) وتظهر فقط عند انتهائه.
 */
public class VerifyActivity extends Activity {

    static final String EXTRA_EMAIL = "email", EXTRA_SEND = "send";
    static final String PREFS = "babylon_boot", KEY_PENDING = "pending_verify";
    private static final long COOLDOWN_MS = 60_000L;
    private static final int CODE_LEN = 6;

    private boolean dark;
    private String email = "";
    private ScrollView sv;
    private LinearLayout content;
    private EditText input;
    private final TextView[] boxes = new TextView[CODE_LEN];
    private TextView timerText, btnVerify;
    private View resendRow;
    private CountDownTimer timer;
    private long remainingMs = 0;
    private boolean busyVerify = false, busyResend = false, keyboardShown = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (FirebaseAuth.getInstance().getCurrentUser() == null) { goLogin(null); return; }

        dark = AuthTheme.isDark(this);
        String e = getIntent().getStringExtra(EXTRA_EMAIL);
        if (e == null || e.isEmpty()) {
            try { e = FirebaseAuth.getInstance().getCurrentUser().getEmail(); } catch (Throwable ignored) { }
        }
        email = e == null ? "" : e;
        AppSettings.prefs(this, PREFS).edit().putString(KEY_PENDING, email).apply();   // يمنع تجاوز الشاشة بإغلاق التطبيق

        buildUi();
        setupSystemBars();
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);

        if (b != null && b.containsKey("remaining")) {
            startTimer(b.getLong("remaining"));
        } else if (getIntent().getBooleanExtra(EXTRA_SEND, false)) {
            startTimer(COOLDOWN_MS);
            EmailOtp.send((ok, msg) -> {
                if (!ok) { toast(msg); startTimer(0); }   // فشل الإرسال: نُظهر «إعادة الإرسال» فوراً
            });
        } else {
            startTimer(0);   // عودة للشاشة (مثلاً بعد إغلاق التطبيق): الرمز قد يكون انتهى، نُظهر «إعادة الإرسال»
        }
        input.post(() -> focusInput());
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putLong("remaining", remainingMs);
    }

    @Override
    protected void onDestroy() {
        if (timer != null) timer.cancel();
        super.onDestroy();
    }

    // ======================= الواجهة =======================

    private void buildUi() {
        final float d = getResources().getDisplayMetrics().density;
        final int gold = 0xFFB8892A;
        final int textMain = dark ? AuthTheme.TEXT : 0xFF55657B;

        sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackground(AuthTheme.authBg(this, dark));   // التدرّج + الموجتان الذهبيتان نفسهما
        sv.setClipToPadding(false);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(22), dp(24), dp(22), dp(48));
        sv.addView(content, new ScrollView.LayoutParams(-1, -1));

        // --- الشعار: قرص ذهبي + مظروف/درع ---
        FrameLayout logo = new FrameLayout(this);
        logo.setBackgroundResource(R.drawable.bg_glow);
        content.addView(logo, lp(dp(160), dp(160), 0, dp(24), 0, 0));
        DiscDrawView disc = new DiscDrawView(this);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(dp(112), dp(112), Gravity.CENTER);
        logo.addView(disc, dlp);
        ImageView ic = new ImageView(this);
        ic.setImageResource(R.drawable.ic_verify_mail);
        logo.addView(ic, new FrameLayout.LayoutParams(dp(62), dp(62), Gravity.CENTER));

        // --- العنوان (ذهبي متدرّج) + الوصف ---
        final TextView title = new TextView(this);
        title.setText("تحقق من بريدك الإلكتروني");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(gold);
        title.setGravity(Gravity.CENTER);
        content.addView(title, lp(-2, -2, 0, dp(18), 0, 0));
        title.post(() -> {
            float w = title.getPaint().measureText(title.getText().toString());
            if (w > 0) title.getPaint().setShader(new LinearGradient(0, 0, w, 0,
                    new int[]{0xFFE6B93A, 0xFFD4A528, 0xFFB88A18}, null, Shader.TileMode.CLAMP));
            title.invalidate();
        });

        TextView sub = new TextView(this);
        sub.setText("لقد أرسلنا رمز التحقق إلى بريدك الإلكتروني");
        sub.setTextSize(15);
        sub.setTextColor(dark ? AuthTheme.MUTED : 0xFF5B6B80);
        sub.setGravity(Gravity.CENTER);
        content.addView(sub, lp(-2, -2, 0, dp(14), 0, 0));

        // --- كبسولة البريد ---
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER);
        pill.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        pill.setBackground(AuthTheme.box(dark, 28 * d, 0xFFF4F6FA, 0xFFDDE3EC, Math.max(1, Math.round(d))));
        ImageView mail = new ImageView(this);
        Drawable md = getResources().getDrawable(R.drawable.ic_mail_20).mutate();
        md.setColorFilter(gold, PorterDuff.Mode.SRC_IN);
        mail.setImageDrawable(md);
        pill.addView(mail, new LinearLayout.LayoutParams(dp(26), dp(26)));
        TextView em = new TextView(this);
        em.setText(email);
        em.setTextSize(17);
        em.setTypeface(Typeface.DEFAULT_BOLD);
        em.setTextColor(textMain);
        em.setSingleLine(true);
        em.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-2, -2);
        elp.leftMargin = dp(16);
        pill.addView(em, elp);
        content.addView(pill, lp(-1, dp(58), 0, dp(26), 0, 0));

        // --- خانات الرمز (6): EditText شفاف فوق الصف يلتقط الكتابة/اللصق/الحذف ---
        FrameLayout codeWrap = new FrameLayout(this);
        codeWrap.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        for (int i = 0; i < CODE_LEN; i++) {
            TextView t = new TextView(this);
            t.setGravity(Gravity.CENTER);
            t.setTextSize(26);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            boxes[i] = t;
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(64), 1f);
            p.leftMargin = p.rightMargin = dp(4);
            row.addView(t, p);
        }
        codeWrap.addView(row, new FrameLayout.LayoutParams(-1, dp(64)));
        input = new EditText(this);
        input.setBackground(null);
        input.setTextColor(Color.TRANSPARENT);
        input.setCursorVisible(false);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setKeyListener(DigitsKeyListener.getInstance("0123456789"));
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(CODE_LEN)});
        input.setLongClickable(false);
        if (Build.VERSION.SDK_INT >= 26) input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { renderBoxes(); }
        });
        input.setOnFocusChangeListener((v, has) -> renderBoxes());
        codeWrap.addView(input, new FrameLayout.LayoutParams(-1, dp(64)));
        content.addView(codeWrap, lp(-1, dp(64), 0, dp(28), 0, 0));

        // --- العدّ التنازلي / إعادة الإرسال (يتبادلان في نفس المكان) ---
        FrameLayout resendArea = new FrameLayout(this);
        timerText = new TextView(this);
        timerText.setTextSize(15);
        timerText.setTextColor(dark ? AuthTheme.MUTED : 0xFF5B6B80);
        timerText.setGravity(Gravity.CENTER);
        resendArea.addView(timerText, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        LinearLayout rr = new LinearLayout(this);
        rr.setOrientation(LinearLayout.HORIZONTAL);
        rr.setGravity(Gravity.CENTER);
        rr.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        ImageView rIc = new ImageView(this);
        Drawable rd = getResources().getDrawable(R.drawable.ic_refresh_22).mutate();
        rd.setColorFilter(0xFFD99A12, PorterDuff.Mode.SRC_IN);
        rIc.setImageDrawable(rd);
        rr.addView(rIc, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView rTx = new TextView(this);
        rTx.setText("إعادة إرسال الرمز");
        rTx.setTextSize(17);
        rTx.setTypeface(Typeface.DEFAULT_BOLD);
        rTx.setTextColor(0xFFD99A12);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
        rlp.leftMargin = dp(8);
        rr.addView(rTx, rlp);
        rr.setPadding(dp(16), dp(8), dp(16), dp(8));
        rr.setVisibility(View.GONE);
        rr.setOnClickListener(v -> resend());
        resendRow = rr;
        resendArea.addView(rr, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        content.addView(resendArea, lp(-1, dp(44), 0, dp(22), 0, 0));

        // --- زر التحقق ---
        btnVerify = new TextView(this);
        btnVerify.setText("تحقق الآن");
        btnVerify.setTextSize(20);
        btnVerify.setTypeface(Typeface.DEFAULT_BOLD);
        btnVerify.setTextColor(Color.WHITE);
        btnVerify.setGravity(Gravity.CENTER);
        btnVerify.setBackgroundResource(R.drawable.bg_button);
        btnVerify.setOnClickListener(v -> doVerify());
        content.addView(btnVerify, lp(-1, dp(58), 0, dp(26), 0, 0));

        // --- «العودة إلى تسجيل الدخول» بين خطّين ذهبيين ---
        LinearLayout back = new LinearLayout(this);
        back.setOrientation(LinearLayout.HORIZONTAL);
        back.setGravity(Gravity.CENTER);
        back.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        View l1 = new View(this), l2 = new View(this);
        l1.setBackgroundColor(0xFFE3C873);
        l2.setBackgroundColor(0xFFE3C873);
        TextView bt = new TextView(this);
        bt.setText("العودة إلى تسجيل الدخول");
        bt.setTextSize(15);
        bt.setTypeface(Typeface.DEFAULT_BOLD);
        bt.setTextColor(dark ? AuthTheme.GOLD_LABEL : 0xFFA97F1C);
        bt.setPadding(dp(14), dp(10), dp(14), dp(10));
        back.addView(l1, new LinearLayout.LayoutParams(0, dp(1), 1f));
        back.addView(bt, new LinearLayout.LayoutParams(-2, -2));
        back.addView(l2, new LinearLayout.LayoutParams(0, dp(1), 1f));
        bt.setOnClickListener(v -> goLogin(null));
        LinearLayout.LayoutParams blp = lp(-1, -2, dp(20), dp(10), dp(20), 0);
        content.addView(back, blp);

        setContentView(sv);
        renderBoxes();
    }

    /** يلوّن الخانات: فارغة (شرطة) / الخانة الحالية (إطار ذهبي) / ممتلئة (رقم ذهبي). */
    private void renderBoxes() {
        final float d = getResources().getDisplayMetrics().density;
        String s = input == null ? "" : input.getText().toString();
        boolean focused = input != null && input.hasFocus();
        for (int i = 0; i < CODE_LEN; i++) {
            boolean filled = i < s.length();
            boolean current = focused && i == Math.min(s.length(), CODE_LEN - 1);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(20 * d);
            g.setColor(dark ? AuthTheme.SURFACE : (filled ? 0xFFFFFBEF : 0xFFFFFFFF));
            int stroke = (filled || current) ? 0xFFD4A937 : (dark ? AuthTheme.BORDER : 0xFFD8DEE8);
            g.setStroke(Math.round((current ? 2f : 1.2f) * d), stroke);
            boxes[i].setBackground(g);
            if (filled) {
                boxes[i].setText(String.valueOf(s.charAt(i)));
                boxes[i].setTextColor(dark ? AuthTheme.GOLD_LABEL : 0xFF8F6C10);
            } else {
                boxes[i].setText("–");
                boxes[i].setTextColor(dark ? AuthTheme.HINT : 0xFFAEB8C6);
            }
        }
        boolean full = s.length() == CODE_LEN;
        if (btnVerify != null) { btnVerify.setAlpha(full && !busyVerify ? 1f : 0.6f); }
    }

    // ======================= العدّ التنازلي =======================

    private void startTimer(long ms) {
        if (timer != null) timer.cancel();
        remainingMs = ms;
        if (ms <= 0) { showResend(); return; }
        timerText.setVisibility(View.VISIBLE);
        resendRow.setVisibility(View.GONE);   // «إعادة الإرسال» مخفية طوال العدّ
        updateTimerText(ms);
        timer = new CountDownTimer(ms, 250) {
            @Override public void onTick(long left) { remainingMs = left; updateTimerText(left); }
            @Override public void onFinish() { remainingMs = 0; showResend(); }
        }.start();
    }

    private void updateTimerText(long ms) {
        long sec = (ms + 999) / 1000;
        timerText.setText(String.format(Locale.US, "يمكنك طلب رمز جديد خلال %02d:%02d", sec / 60, sec % 60));
    }

    /** انتهى الوقت: يختفي العدّ وتظهر «إعادة إرسال الرمز». */
    private void showResend() {
        timerText.setVisibility(View.GONE);
        resendRow.setVisibility(View.VISIBLE);
        resendRow.setAlpha(0f);
        resendRow.animate().alpha(1f).setDuration(250).start();
    }

    private void resend() {
        if (busyResend) return;
        busyResend = true;
        resendRow.setAlpha(0.5f);
        EmailOtp.send((ok, msg) -> {
            busyResend = false;
            resendRow.setAlpha(1f);
            if (ok) {
                input.setText("");
                toast("تم إرسال رمز جديد إلى بريدك");
                startTimer(COOLDOWN_MS);   // تختفي «إعادة الإرسال» ويبدأ العدّ من جديد
                focusInput();
            } else {
                toast(msg);
            }
        });
    }

    // ======================= التحقق =======================

    private void doVerify() {
        if (busyVerify) return;
        String code = input.getText().toString();
        if (code.length() != CODE_LEN) { toast("أدخل الرمز المكوّن من 6 أرقام"); return; }
        busyVerify = true;
        renderBoxes();
        toast("جارٍ التحقق");
        EmailOtp.verify(code, (ok, msg) -> {
            busyVerify = false;
            renderBoxes();
            if (ok) {
                AppSettings.prefs(this, PREFS).edit().remove(KEY_PENDING).apply();
                Intent i = new Intent(this, MassActivity.class);
                i.putExtra("bb_toast", "تم التحقق من البريد بنجاح");
                startActivity(i);
                overridePendingTransition(0, 0);
                finish();
            } else {
                toast(msg);
                input.setText("");
            }
        });
    }

    private void goLogin(String toastMsg) {
        try { FirebaseAuth.getInstance().signOut(); } catch (Throwable ignored) { }
        AppSettings.prefs(this, PREFS).edit().remove(KEY_PENDING).apply();
        Intent i = new Intent(this, Main2Activity.class);
        if (toastMsg != null) i.putExtra("bb_toast", toastMsg);
        startActivity(i);
        overridePendingTransition(0, 0);
        finish();
    }

    @Override
    public void onBackPressed() { goLogin(null); }

    // ======================= مساعدات =======================

    private void toast(String s) { AppToast.show(this, s, dark, btnVerify); }

    private void focusInput() {
        input.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private static LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        p.gravity = Gravity.CENTER_HORIZONTAL;
        return p;
    }

    private void setupSystemBars() {
        Window w = getWindow();
        w.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.setStatusBarColor(0x00000000);
        w.setNavigationBarColor(0x00000000);
        if (Build.VERSION.SDK_INT >= 29) {
            w.setNavigationBarContrastEnforced(false);
            w.setStatusBarContrastEnforced(false);
        }
        AuthTheme.systemBars(w, dark);
        sv.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom, ime;
            if (Build.VERSION.SDK_INT >= 30) {
                top = insets.getInsets(WindowInsets.Type.systemBars()).top;
                int nav = insets.getInsets(WindowInsets.Type.systemBars()).bottom;
                ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
                bottom = Math.max(nav, ime);
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
                ime = bottom > dp(120) ? bottom : 0;
            }
            v.setPadding(0, top, 0, bottom);
            boolean shown = ime > 0;
            if (shown != keyboardShown) {
                keyboardShown = shown;
                if (shown) v.postDelayed(() -> sv.smoothScrollTo(0, content.getHeight()), 120);   // يبقى زر «تحقق الآن» ظاهراً فوق الكيبورد
            }
            return insets;
        });
        sv.requestApplyInsets();
    }
}
