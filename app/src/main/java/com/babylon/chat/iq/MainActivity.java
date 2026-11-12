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

    private static final long SPLASH_MS = 1800;

    private MainBinding binding;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Animator> animators = new ArrayList<>();
    private ValueAnimator shine;
    private boolean navigated = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = MainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        if (AuthTheme.isDark(this)) binding.getRoot().setBackground(AuthTheme.screenBg(true));   // الطور الليلي
        setupSystemBars();
        startAnimations();

        handler.postDelayed(this::goNext, SPLASH_MS);
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
        android.content.SharedPreferences sp = getSharedPreferences("babylon_boot", MODE_PRIVATE);
        if (Build.VERSION.SDK_INT >= 23 && !sp.getBoolean("perms_asked", false)) {   // أول دخول فقط
            sp.edit().putBoolean("perms_asked", true).apply();
            String[] need = missingPermissions();
            if (need.length > 0) { requestPermissions(need, REQ_FIRST_PERMS); return; }
        }
        proceed();
    }

    private void proceed() {
        if (navigated || isFinishing()) return;
        navigated = true;
        boolean loggedIn = FirebaseAuth.getInstance().getCurrentUser() != null;
        if (loggedIn) {
            startActivity(new Intent(this, MassActivity.class));
            overridePendingTransition(R.anim.chat_fade_in, R.anim.splash_hold);   // تتلاشى للمحادثة والسبلاش يواصل حركته
            finish();
            return;
        }
        // غير مسجّل: نمرّر موضع/حجم اللوكو والعنوان لشاشة الدخول لتكمل الحركة من نفس المكان
        final Intent i = new Intent(this, Main2Activity.class);
        putViewInfo(i, binding.logoCircle, "logo");
        putViewInfo(i, binding.title, "title");
        // نخفي التوهج والنقاط بسرعة ثم ننتقل (لا وجود لهما في شاشة الدخول)
        binding.logoGlow.animate().alpha(0f).setDuration(160).start();
        binding.linear22.animate().alpha(0f).setDuration(160).start();
        handler.postDelayed(() -> {
            startActivity(i);
            overridePendingTransition(0, 0);   // بلا انتقال افتراضي ليبدو اللوكو ثابتاً
            finish();
        }, 170);
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

    private void startAnimations() {
        play(binding.logoCircle, R.drawable.splash_breath);
        play(binding.logoGlow, R.drawable.glow_pulse);
        play(binding.dot1, R.drawable.dot_pulse_1);
        play(binding.dot2, R.drawable.dot_pulse_2);
        play(binding.dot3, R.drawable.dot_pulse_3);

        // لمعان متحرك على عنوان "Babylon Chat" (نفس شاشة الدخول)
        binding.title.post(() -> {
            float w = binding.title.getPaint().measureText(binding.title.getText().toString());
            if (w <= 0) return;
            final float tile = w * 2f;
            int[] colors = {0xFF8F6C10, 0xFFC39A2A, 0xFFFFE58A, 0xFFC39A2A, 0xFF8F6C10, 0xFF8F6C10};
            float[] pos = {0f, 0.2f, 0.4f, 0.6f, 0.8f, 1f};
            final LinearGradient grad = new LinearGradient(0, 0, tile, 0, colors, pos, Shader.TileMode.REPEAT);
            binding.title.getPaint().setShader(grad);
            final Matrix m = new Matrix();
            shine = ValueAnimator.ofFloat(0f, tile);
            shine.setDuration(15000);
            shine.setInterpolator(new LinearInterpolator());
            shine.setRepeatCount(ValueAnimator.INFINITE);
            shine.addUpdateListener(a -> {
                m.setTranslate((float) a.getAnimatedValue(), 0f);
                grad.setLocalMatrix(m);
                binding.title.invalidate();
            });
            shine.start();
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
        if (shine != null) shine.cancel();
    }
}
