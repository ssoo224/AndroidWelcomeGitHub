package com.babylon.chat.iq;

import android.animation.*;
import android.app.*;
import android.app.Activity;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.text.*;
import android.util.*;
import android.view.*;
import android.view.View.*;
import android.view.animation.*;
import android.widget.*;
import com.babylon.chat.iq.databinding.*;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import java.util.*;

public class Main2Activity extends Activity {

    private Main2Binding binding;
    private FirebaseAuth mAuth;
    private FirebaseFirestore db;

    @Override
    protected void onCreate(Bundle _savedInstanceState) {
        super.onCreate(_savedInstanceState);
        binding = Main2Binding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        mAuth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();
        initialize(_savedInstanceState);
        initializeLogic();
    }

    @Override
    protected void onStart() {
        super.onStart();
        FirebaseUser current = mAuth.getCurrentUser();
        if (current != null) {
            startActivity(new Intent(this, MassActivity.class));
            finish();
        }
    }

    private void initialize(Bundle _savedInstanceState) {
        binding.btnLogin.setOnClickListener(v -> doLogin());
        binding.btnRegister.setOnClickListener(v -> doRegister());
        binding.btnForgot.setOnClickListener(v -> doResetPassword());
    }

    private void doLogin() {
        String emailOrUser = binding.editUser.getText().toString().trim();
        String password = binding.editPass.getText().toString().trim();

        if (emailOrUser.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "أدخل البريد/اسم المستخدم وكلمة المرور", Toast.LENGTH_SHORT).show();
            return;
        }

        if (emailOrUser.contains("@") && emailOrUser.contains(".")) {
            signIn(emailOrUser, password);
        } else {
            String username = emailOrUser.replace("@", "").trim();
            db.collection("users").whereEqualTo("username", username).limit(1).get()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful() && task.getResult() != null && !task.getResult().isEmpty()) {
                        String email = task.getResult().getDocuments().get(0).getString("email");
                        if (email != null) signIn(email, password);
                        else Toast.makeText(this, "لا يوجد بريد مرتبط", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "اسم المستخدم غير موجود", Toast.LENGTH_SHORT).show();
                    }
                });
        }
    }

    private void signIn(String email, String password) {
        mAuth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener(this, task -> {
                if (task.isSuccessful()) {
                    startActivity(new Intent(this, MassActivity.class));
                    finish();
                } else {
                    String msg = task.getException() != null ? task.getException().getMessage() : "خطأ";
                    Toast.makeText(this, "فشل الدخول: " + msg, Toast.LENGTH_LONG).show();
                }
            });
    }

    private void doRegister() {
        String name = binding.editName.getText().toString().trim();
        String username = binding.editUsername.getText().toString().trim().replace("@", "");
        String email = binding.editEmail.getText().toString().trim();
        String password = binding.editRegPass.getText().toString().trim();

        if (name.isEmpty()) { Toast.makeText(this, "أدخل اسم العرض", Toast.LENGTH_SHORT).show(); return; }
        if (username.length() < 5 || !username.matches("[A-Za-z0-9_]+")) {
            Toast.makeText(this, "اسم المستخدم 5 أحرف إنجليزية على الأقل (أرقام و _ مسموحة)", Toast.LENGTH_LONG).show();
            return;
        }
        if (email.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            Toast.makeText(this, "أدخل بريدًا صحيحًا", Toast.LENGTH_SHORT).show();
            return;
        }
        if (password.length() < 6) {
            Toast.makeText(this, "كلمة المرور 6 أحرف على الأقل", Toast.LENGTH_SHORT).show();
            return;
        }

        db.collection("users").whereEqualTo("username", username).limit(1).get()
            .addOnCompleteListener(task -> {
                if (task.isSuccessful() && task.getResult() != null && !task.getResult().isEmpty()) {
                    Toast.makeText(this, "اسم المستخدم مستخدم بالفعل", Toast.LENGTH_SHORT).show();
                    return;
                }
                mAuth.createUserWithEmailAndPassword(email, password)
                    .addOnCompleteListener(this, t2 -> {
                        if (t2.isSuccessful()) {
                            FirebaseUser user = mAuth.getCurrentUser();
                            if (user != null) {
                                Map<String, Object> userData = new HashMap<>();
                                userData.put("uid", user.getUid());
                                userData.put("name", name);
                                userData.put("username", username);
                                userData.put("email", email);
                                userData.put("bio", "");
                                userData.put("gov", "");
                                userData.put("createdAt", System.currentTimeMillis());
                                db.collection("users").document(user.getUid())
                                    .set(userData)
                                    .addOnSuccessListener(aVoid -> {
                                        Toast.makeText(this, "تم إنشاء الحساب بنجاح", Toast.LENGTH_SHORT).show();
                                        startActivity(new Intent(this, MassActivity.class));
                                        finish();
                                    })
                                    .addOnFailureListener(e -> Toast.makeText(this, "فشل حفظ البيانات: " + e.getMessage(), Toast.LENGTH_LONG).show());
                            }
                        } else {
                            String msg = t2.getException() != null ? t2.getException().getMessage() : "خطأ";
                            Toast.makeText(this, "فشل التسجيل: " + msg, Toast.LENGTH_LONG).show();
                        }
                    });
            });
    }

    private void doResetPassword() {
        String email = binding.editUser.getText().toString().trim();
        if (email.isEmpty() || !email.contains("@") || !email.contains(".")) {
            Toast.makeText(this, "أدخل بريدك الإلكتروني في حقل الدخول أولاً", Toast.LENGTH_LONG).show();
            return;
        }
        mAuth.sendPasswordResetEmail(email)
            .addOnCompleteListener(task -> {
                if (task.isSuccessful()) Toast.makeText(this, "تم إرسال رابط إعادة التعيين", Toast.LENGTH_LONG).show();
                else Toast.makeText(this, "فشل: " + (task.getException() != null ? task.getException().getMessage() : ""), Toast.LENGTH_LONG).show();
            });
    }

    private void initializeLogic() {
        final View loginForm = findViewById(R.id.login_form);
        final View registerForm = findViewById(R.id.register_form);
        final TextView tabLogin = (TextView) findViewById(R.id.tab_login);
        final TextView tabRegister = (TextView) findViewById(R.id.tab_register);

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

        findViewById(R.id.btn_login).setClickable(true);
        findViewById(R.id.btn_register).setClickable(true);

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

        findViewById(R.id.btn_login).setOnTouchListener(pressAnim);
        findViewById(R.id.btn_register).setOnTouchListener(pressAnim);
        findViewById(R.id.tab_login).setOnTouchListener(pressAnim);
        findViewById(R.id.tab_register).setOnTouchListener(pressAnim);
        findViewById(R.id.btn_forgot).setOnTouchListener(pressAnim);

        Window w = getWindow();
        w.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.setStatusBarColor(0x00000000);
        w.setNavigationBarColor(0x00000000);

        if (Build.VERSION.SDK_INT >= 29) {
            w.setNavigationBarContrastEnforced(false);
            w.setStatusBarContrastEnforced(false);
        }

        int topBg = 0xFFF1F4F9, botBg = 0xFFEEF2F9;
        boolean topLight = (0.299 * ((topBg >> 16) & 255) + 0.587 * ((topBg >> 8) & 255) + 0.114 * (topBg & 255)) > 150;
        boolean botLight = (0.299 * ((botBg >> 16) & 255) + 0.587 * ((botBg >> 8) & 255) + 0.114 * (botBg & 255)) > 150;

        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (Build.VERSION.SDK_INT >= 23 && topLight) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26 && botLight) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        w.getDecorView().setSystemUiVisibility(flags);

        final View rootView = findViewById(R.id.root);
        rootView.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });
        rootView.requestApplyInsets();

        final TextView bbLoginTitle = (TextView) findViewById(R.id.title);
        bbLoginTitle.post(() -> {
            float bbLoginW = bbLoginTitle.getPaint().measureText(bbLoginTitle.getText().toString());
            if (bbLoginW <= 0) return;
            final float bbLoginTile = bbLoginW * 2f;
            int[] bbLoginColors = {0xFF8F6C10, 0xFFC39A2A, 0xFFFFE58A, 0xFFC39A2A, 0xFF8F6C10, 0xFF8F6C10};
            float[] bbLoginPos = {0f, 0.2f, 0.4f, 0.6f, 0.8f, 1f};
            final LinearGradient bbLoginGrad = new LinearGradient(0, 0, bbLoginTile, 0, bbLoginColors, bbLoginPos, Shader.TileMode.REPEAT);
            bbLoginTitle.getPaint().setShader(bbLoginGrad);
            final Matrix bbLoginMatrix = new Matrix();
            ValueAnimator bbLoginShine = ValueAnimator.ofFloat(0f, bbLoginTile);
            bbLoginShine.setDuration(15000);
            bbLoginShine.setInterpolator(new LinearInterpolator());
            bbLoginShine.setRepeatCount(ValueAnimator.INFINITE);
            bbLoginShine.addUpdateListener(animation -> {
                bbLoginMatrix.setTranslate((float) animation.getAnimatedValue(), 0f);
                bbLoginGrad.setLocalMatrix(bbLoginMatrix);
                bbLoginTitle.invalidate();
            });
            bbLoginShine.start();
        });
    }
}