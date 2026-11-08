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
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.TextView;
import android.widget.Toast;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = Main2Binding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        auth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();

        setupTabs();
        setupSystemBars();
        setupTitleShine();
        setupPressAnim();

        binding.btnLogin.setOnClickListener(v -> doLogin());
        binding.btnRegister.setOnClickListener(v -> doRegister());
        binding.btnForgot.setOnClickListener(v -> doForgot());
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
                .addOnSuccessListener(r -> goToMass())
                .addOnFailureListener(e -> {
                    setBusy(binding.btnLogin, false);
                    toast("البريد/اسم المستخدم أو كلمة المرور غير صحيحة");
                });
    }

    // ======================= إنشاء حساب =======================

    private void doRegister() {
        final String name = binding.editName.getText().toString().trim();
        String rawUser = binding.editUsername.getText().toString().trim();
        final String username = rawUser.startsWith("@") ? rawUser.substring(1) : rawUser;
        final String email = binding.editEmail.getText().toString().trim();
        final String pass = binding.editRegPass.getText().toString();

        if (name.isEmpty() || username.isEmpty() || email.isEmpty() || pass.isEmpty()) {
            toast("املأ جميع الحقول");
            return;
        }
        if (username.length() < 5) {
            toast("اسم المستخدم يجب أن يكون 5 أحرف فأكثر");
            return;
        }
        if (username.contains(" ") || username.contains("@")) {
            toast("اسم المستخدم لا يجب أن يحتوي مسافات أو @");
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

        setBusy(binding.btnRegister, true);

        // 1) إنشاء حساب المصادقة
        auth.createUserWithEmailAndPassword(email, pass)
                .addOnSuccessListener(r -> {
                    final FirebaseUser user = r.getUser();
                    if (user == null) {
                        setBusy(binding.btnRegister, false);
                        toast("حدث خطأ، حاول مرة أخرى");
                        return;
                    }
                    // 2) التحقق من أن اسم المستخدم غير مأخوذ (بعد تسجيل الدخول لتفادي مشاكل القواعد)
                    db.collection("users").whereEqualTo("username", username).limit(1).get()
                            .addOnSuccessListener(qs -> {
                                if (!qs.isEmpty()) {
                                    // اسم المستخدم مأخوذ: نحذف الحساب الذي أُنشئ للتو
                                    user.delete().addOnCompleteListener(t -> {
                                        auth.signOut();
                                        setBusy(binding.btnRegister, false);
                                        toast("اسم المستخدم مستخدم من قبل");
                                    });
                                } else {
                                    saveProfile(user, name, username, email);
                                }
                            })
                            .addOnFailureListener(e -> {
                                // فشل التحقق: نحفظ على أي حال
                                saveProfile(user, name, username, email);
                            });
                })
                .addOnFailureListener(e -> {
                    setBusy(binding.btnRegister, false);
                    toast(registerError(e));
                });
    }

    private void saveProfile(FirebaseUser user, String name, String username, String email) {
        Map<String, Object> data = new HashMap<>();
        data.put("name", name);
        data.put("username", username);
        data.put("email", email);
        data.put("bio", "");
        data.put("gov", "");
        data.put("createdAt", System.currentTimeMillis());

        db.collection("users").document(user.getUid()).set(data)
                .addOnSuccessListener(v -> {
                    toast("تم إنشاء الحساب بنجاح");
                    goToMass();
                })
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
        auth.sendPasswordResetEmail(input)
                .addOnSuccessListener(v -> toast("تم إرسال رابط إعادة التعيين إلى بريدك"))
                .addOnFailureListener(e -> toast("تعذّر الإرسال، تأكد من البريد"));
    }

    // ======================= مساعدات =======================

    private void goToMass() {
        startActivity(new Intent(this, MassActivity.class));
        finish();
    }

    private void setBusy(TextView btn, boolean busy) {
        btn.setEnabled(!busy);
        btn.setAlpha(busy ? 0.6f : 1f);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ======================= الواجهة =======================

    private void setupTabs() {
        final TextView tabLogin = binding.tabLogin;
        final TextView tabRegister = binding.tabRegister;
        final View loginForm = binding.loginForm;
        final View registerForm = binding.registerForm;

        tabLogin.setOnClickListener(v -> {
            loginForm.setVisibility(View.VISIBLE);
            registerForm.setVisibility(View.GONE);
            tabLogin.setBackgroundResource(R.drawable.bg_tab_active);
            tabLogin.setTextColor(0xFFFFFFFF);
            tabRegister.setBackgroundColor(0x00000000);
            tabRegister.setTextColor(0xFF475569);
        });

        tabRegister.setOnClickListener(v -> {
            loginForm.setVisibility(View.GONE);
            registerForm.setVisibility(View.VISIBLE);
            tabRegister.setBackgroundResource(R.drawable.bg_tab_active);
            tabRegister.setTextColor(0xFFFFFFFF);
            tabLogin.setBackgroundColor(0x00000000);
            tabLogin.setTextColor(0xFF475569);
        });
    }

    private void setupPressAnim() {
        View.OnTouchListener pressAnim = (v, e) -> {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(0.94f).scaleY(0.94f).alpha(0.85f).setDuration(100).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(150).start();
                    break;
            }
            return false;
        };
        binding.btnLogin.setOnTouchListener(pressAnim);
        binding.btnRegister.setOnTouchListener(pressAnim);
        binding.tabLogin.setOnTouchListener(pressAnim);
        binding.tabRegister.setOnTouchListener(pressAnim);
        binding.btnForgot.setOnTouchListener(pressAnim);
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

        final View rootView = binding.root;
        rootView.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });
        rootView.requestApplyInsets();
    }

    private void setupTitleShine() {
        final TextView title = binding.title;
        title.post(() -> {
            float w = title.getPaint().measureText(title.getText().toString());
            if (w <= 0) return;
            final float tile = w * 2f;
            int[] colors = {0xFF8F6C10, 0xFFC39A2A, 0xFFFFE58A, 0xFFC39A2A, 0xFF8F6C10, 0xFF8F6C10};
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
