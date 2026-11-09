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

        setupSystemBars();
        startAnimations();

        handler.postDelayed(this::goNext, SPLASH_MS);
    }

    @Override
    public void onBackPressed() { /* شاشة البداية: لا خروج من التطبيق */ }

    private void goNext() {
        if (navigated || isFinishing()) return;
        navigated = true;
        boolean loggedIn = FirebaseAuth.getInstance().getCurrentUser() != null;
        startActivity(new Intent(this, loggedIn ? MassActivity.class : Main2Activity.class));
        finish();
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
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (Build.VERSION.SDK_INT >= 23) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        w.getDecorView().setSystemUiVisibility(flags);
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
