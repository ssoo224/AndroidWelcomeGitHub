package com.babylon.chat.iq;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.view.MotionEvent;
import android.graphics.RectF;
import android.graphics.Path;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.babylon.chat.iq.databinding.MassBinding;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MassActivity extends Activity {

    private MassBinding binding;
    private FirebaseAuth mAuth;
    private FirebaseUser currentUser;
    private FirebaseFirestore db;
    private String myUid = "";

    private ListenerRegistration messagesListener;
    private String currentChatId = "";
    private String otherUid = "";
    private final java.util.Set<String> renderedMsgIds = new java.util.HashSet<>();

    private java.util.function.Consumer<Uri> pendingImageCallback = null;
    private Runnable pendingMicCallback = null;
    private static final int REQ_PICK_IMAGE = 1001;
    private static final int REQ_MIC_PERM = 1002;

    @Override
    protected void onCreate(Bundle _savedInstanceState) {
        super.onCreate(_savedInstanceState);

        // 1) إعداد معالج الأخطاء العام قبل أي شيء آخر
        setupCrashHandler();

        // 2) عرض الخطأ السابق (إن وجد) قبل البدء
        showLastCrash();

        // 3) إشعار بسيط للتأكد أن onCreate يعمل
        Toast.makeText(this, "جاري فتح MassActivity...", Toast.LENGTH_SHORT).show();

        try {
            binding = MassBinding.inflate(getLayoutInflater());
            setContentView(binding.getRoot());
        } catch (Throwable e) {
            saveAndDie(e, "inflate_layout");
            return;
        }

        try {
            mAuth = FirebaseAuth.getInstance();
            currentUser = mAuth.getCurrentUser();
            db = FirebaseFirestore.getInstance();

            if (currentUser == null) {
                startActivity(new Intent(this, Main2Activity.class));
                finish();
                return;
            }
            myUid = currentUser.getUid();
        } catch (Throwable e) {
            saveAndDie(e, "firebase_init");
            return;
        }

        try {
            initializeLogic();
        } catch (Throwable e) {
            saveAndDie(e, "initializeLogic");
            return;
        }

        try {
            makeAllTextBold(getWindow().getDecorView().getRootView());
        } catch (Throwable ignored) { }
    }

    // ==================== نظام التقاط الأخطاء ====================

    private void setupCrashHandler() {
        final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            saveCrashToPrefs(e, "uncaught_" + thread.getName());
            android.util.Log.e("BABYLON_CRASH", "UNCAUGHT in " + thread.getName(), e);
            try {
                Toast.makeText(getApplicationContext(),
                        "خطأ: " + e.getClass().getSimpleName() + " — أعد فتح التطبيق لرؤية التفاصيل",
                        Toast.LENGTH_LONG).show();
                Thread.sleep(2500);
            } catch (Throwable ignored) { }
            if (defaultHandler != null) defaultHandler.uncaughtException(thread, e);
        });
    }

    private void saveCrashToPrefs(Throwable e, String where) {
        StringBuilder sb = new StringBuilder();
        sb.append("المكان: ").append(where).append("\n\n");
        sb.append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append("\n\n");
        StackTraceElement[] st = e.getStackTrace();
        int count = 0;
        for (StackTraceElement el : st) {
            if (el.getClassName().startsWith("com.babylon") || el.getClassName().startsWith("android.")) {
                sb.append(el.getFileName()).append(":").append(el.getLineNumber())
                        .append("  →  ").append(el.getMethodName()).append("()\n");
                count++;
                if (count >= 8) break;
            }
        }
        getSharedPreferences("crash_log", MODE_PRIVATE)
                .edit().putString("last", sb.toString()).commit();
    }

    private void saveAndDie(Throwable e, String where) {
        saveCrashToPrefs(e, where);
        android.util.Log.e("BABYLON_CRASH", where, e);
        Toast.makeText(this, "سُجل خطأ. أعد فتح التطبيق لرؤيته", Toast.LENGTH_LONG).show();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(10);
        }, 2000);
    }

    private void showLastCrash() {
        String last = getSharedPreferences("crash_log", MODE_PRIVATE).getString("last", "");
        if (last == null || last.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle("خطأ في التشغيل السابق")
                .setMessage(last)
                .setPositiveButton("مسح ومتابعة", (d, w) ->
                        getSharedPreferences("crash_log", MODE_PRIVATE).edit().clear().apply())
                .setCancelable(false)
                .show();
    }

    // ==================== دورة الحياة ====================

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (messagesListener != null) messagesListener.remove();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_IMAGE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            java.util.function.Consumer<Uri> cb = pendingImageCallback;
            pendingImageCallback = null;
            if (cb != null) cb.accept(data.getData());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC_PERM) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Runnable cb = pendingMicCallback;
                pendingMicCallback = null;
                if (cb != null) cb.run();
            } else {
                Toast.makeText(this, "يجب منح إذن الميكروفون", Toast.LENGTH_SHORT).show();
                pendingMicCallback = null;
            }
        }
    }

    private void makeAllTextBold(View v) {
        if (v instanceof TextView) {
            ((TextView) v).setTypeface(null, android.graphics.Typeface.BOLD);
        } else if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                makeAllTextBold(vg.getChildAt(i));
            }
        }
    }

    private void initializeLogic() {
        final Activity act = this;
        act.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        class UI {
            SharedPreferences sp = act.getSharedPreferences("babylon_ui", MODE_PRIVATE);
            boolean dark = sp.getBoolean("dark", false);
            int fontSp = sp.getInt("font", 15);
            int radius = sp.getInt("radius", 16);
            int chatBgIdx = sp.getInt("chatBg", 0);
            int coverColor = sp.getInt("cover", 0xFF1B2A4A);
            boolean unreadOnly = false, muted = false, blocked = false;
            String curPage = "homeView", prevPage = "homeView", curChat = "";
            String replyText = null, otherName = "", otherUser = "";
            TextView editing = null;
            boolean tabStories = true;

            MediaRecorder recorder = null;
            File audioFile = null;
            boolean isRecording = false;
            long recordStart = 0;
            Handler recHandler = new Handler(Looper.getMainLooper());
            Runnable recTick = null;
            MediaPlayer mediaPlayer = null;
            Uri storyDraftUri = null;
            String chatSearchFilter = "all";
            final ArrayList<View> chatSearchHits = new ArrayList<>();
            int chatSearchHitIndex = 0;

            String[] pages = {"homeView", "searchView", "profileView", "otherUserProfileView",
                    "settingsView", "privacySettingsView", "notificationSettingsView",
                    "chatSettingsView", "editProfileView"};
            String[] govs = {"بغداد", "البصرة", "نينوى", "أربيل", "النجف", "كربلاء", "السليمانية", "دهوك",
                    "كركوك", "الأنبار", "ديالى", "صلاح الدين", "بابل", "واسط", "ذي قار", "ميسان", "المثنى", "القادسية"};
            String[] themeNames = {"افتراضي", "أزرق", "أخضر", "بنفسجي", "ذهبي"};
            int[] bgLight = {0xFFE4E9F2, 0xFFD6E6FA, 0xFFDDF0DD, 0xFFE8DDF3, 0xFFF5EBCB};
            int[] bgDark = {0xFF0E1621, 0xFF0B1B30, 0xFF0C1F14, 0xFF1A1128, 0xFF241C08};
            int[] covers = {0xFF1B2A4A, 0xFF3B2A4A, 0xFF0F5132, 0xFF7C2D12, 0xFF334155, 0xFFB8891A};

            ArrayList<String[]> chatData = new ArrayList<>();
            String userName = "", userUsername = "", userBio = "", userGov = "", userEmail = "", userJoin = "";
            String userPhoto = "", shownPhoto = "";
            Bitmap photoBmp = null;
            String otherPhoto = "";
            Bitmap otherPhotoBmp = null;

            int dp(float d) { return (int) (d * act.getResources().getDisplayMetrics().density + 0.5f); }
            View v(String n) { return act.findViewById(act.getResources().getIdentifier(n, "id", act.getPackageName())); }
            TextView tv(String n) { View x = v(n); return x instanceof TextView ? (TextView) x : null; }
            EditText et(String n) { View x = v(n); return x instanceof EditText ? (EditText) x : null; }
            String txt(String n) { EditText e = et(n); return e != null ? e.getText().toString().trim() : ""; }
            void toast(String s) { Toast.makeText(act, s, Toast.LENGTH_SHORT).show(); }
            void vis(String n, boolean on) { View x = v(n); if (x != null) x.setVisibility(on ? View.VISIBLE : View.GONE); }
            String now() { return new SimpleDateFormat("h:mm", Locale.getDefault()).format(new Date()); }
            void hideKb() {
                try {
                    InputMethodManager m = (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
                    View root = v("root");
                    if (root != null) m.hideSoftInputFromWindow(root.getWindowToken(), 0);
                } catch (Exception e) { }
            }
            GradientDrawable shape(int fill, float rDp, int stroke) {
                GradientDrawable g = new GradientDrawable();
                g.setColor(fill);
                g.setCornerRadius(dp(rDp));
                if (stroke != 0) g.setStroke(dp(1.2f), stroke);
                return g;
            }

            int cBg() { return dark ? 0xFF0F172A : 0xFFF0F2F5; }
            int cSf() { return dark ? 0xFF17212B : 0xFFFFFFFF; }
            int cBorder() { return dark ? 0xFF344256 : 0xFFE1E7EF; }
            int cText() { return dark ? 0xFFF1F5F9 : 0xFF17212B; }
            int cMuted() { return dark ? 0xFFA5B2C2 : 0xFF708094; }
            int cGold() { return dark ? 0xFFE4C45B : 0xFFC29D30; }
            int cRed() { return dark ? 0xFFFB7185 : 0xFFDC2626; }
            int cMine() { return dark ? 0xFF2B5278 : 0xFFD9FDD3; }
            int cOther() { return dark ? 0xFF182533 : 0xFFFFFFFF; }
            int cChatBg() { return dark ? bgDark[chatBgIdx] : bgLight[chatBgIdx]; }

            void style(View x) {
                if (x == null) return;
                Object t = x.getTag();
                if (t instanceof String) {
                    String[] ks = ((String) t).split(" ");
                    for (String k : ks) {
                        TextView T = (x instanceof TextView) ? (TextView) x : null;
                        if (k.equals("bg")) x.setBackgroundColor(cBg());
                        else if (k.equals("sf")) x.setBackgroundColor(cSf());
                        else if (k.equals("line")) x.setBackgroundColor(cBorder());
                        else if (k.equals("chatbg")) x.setBackgroundColor(cChatBg());
                        else if (k.equals("card")) x.setBackground(shape(cSf(), 18, cBorder()));
                        else if (k.equals("row")) x.setBackground(shape(cSf(), 20, 0));
                        else if (k.equals("badge")) {
                            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFFE2B83F, 0xFFB8861B});
                            g.setCornerRadius(dp(11));
                            x.setBackground(g);
                            if (T != null) T.setTextColor(0xFFFFFFFF);
                        } else if (k.equals("avatar")) {
                            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{dark ? 0xFFF0D77A : 0xFFE8C25A, dark ? 0xFFC9A227 : 0xFFB88A1C});
                            g.setShape(GradientDrawable.OVAL);
                            x.setBackground(g);
                        }
                        else if (k.equals("white_card")) {
                            x.setBackground(shape(0xFFFFFFFF, 16, 0));
                            x.setElevation(dp(4));
                        }
                        else if (k.equals("settings_hero")) {
                            GradientDrawable hero = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                                    new int[]{dark ? 0xFF26344A : 0xFFFFF4D4, dark ? 0xFF17212B : 0xFFE9F3FC});
                            hero.setCornerRadius(dp(24));
                            hero.setStroke(dp(1), dark ? 0x334B5D73 : 0x22B8861B);
                            x.setBackground(hero);
                            x.setElevation(dp(3));
                        }
                        else if (k.equals("icon_box_bg")) {
                            x.setBackground(shape(0xFFFFF7E6, 12, 0));
                        }
                        else if (k.equals("cover_gradient")) {
                            GradientDrawable g = new GradientDrawable(
                                GradientDrawable.Orientation.TL_BR,
                                new int[]{0xFFD49A52, 0xFF5F738C, 0xFFE4E9F2}
                            );
                            g.setCornerRadius(0);
                            x.setBackground(g);
                        }
                        else if (k.equals("inp")) {
                            x.setBackground(shape(dark ? 0xFF0F172A : 0xFFF7F9FC, 22, cBorder()));
                            if (T != null) { T.setTextColor(cText()); T.setHintTextColor(cMuted()); }
                        }
                        else if (k.equals("gold")) {
                            x.setBackground(shape(cGold(), rad(x), 0));
                            boolean chip = x.getId() == R.id.fltAll || x.getId() == R.id.fltUnread;
                            if (T != null) T.setTextColor(chip ? (dark ? 0xFF1B1B1B : 0xFFFFFFFF) : 0xFF17212B);
                        }
                        else if (k.equals("goldo")) { x.setBackground(shape(0, rad(x), cGold())); if (T != null) T.setTextColor(cGold()); }
                        else if (k.equals("redo")) { x.setBackground(shape(0, 12, cRed())); if (T != null) T.setTextColor(cRed()); }
                        else if (k.equals("circ")) {
                            GradientDrawable g = new GradientDrawable();
                            g.setShape(GradientDrawable.OVAL); g.setColor(cGold()); x.setBackground(g);
                            if (photoBmp != null && x instanceof TextView && (x == v("pAvatarText") || x == v("sxAvatar"))) applyPhotoTo((TextView) x);
                        } else if (k.equals("circRed")) {
                            GradientDrawable g = new GradientDrawable();
                            g.setShape(GradientDrawable.OVAL); g.setColor(cRed()); x.setBackground(g);
                        } else if (k.equals("ring")) {
                            GradientDrawable g = new GradientDrawable();
                            g.setShape(GradientDrawable.OVAL); g.setColor(cSf()); g.setStroke(dp(2.5f), cGold());
                            x.setBackground(g);
                            if (T != null) T.setTextColor(cGold());
                        } else if (k.equals("bubMine")) {
                            x.setBackground(shape(cMine(), radius, 0)); x.setElevation(dp(1));
                            if (T != null) T.setTextColor(dark ? 0xFFFFFFFF : 0xFF17212B);
                        } else if (k.equals("bubOther") || k.equals("other")) {
                            x.setBackground(shape(cOther(), radius, dark ? 0 : 0xFFE1E7EF)); x.setElevation(dp(1));
                            if (T != null) T.setTextColor(cText());
                        } else if (k.equals("mine")) {
                            x.setBackground(shape(cMine(), radius, 0)); x.setElevation(dp(1));
                            if (T != null) T.setTextColor(dark ? 0xFFFFFFFF : 0xFF17212B);
                        } else if (k.startsWith("ic:")) x.setBackground(shape(Color.parseColor(k.substring(3)), 12, 0));
                        else if (k.startsWith("cover:")) x.setBackgroundColor(Color.parseColor(k.substring(6)));
                        else if (T != null) {
                            if (k.equals("t")) T.setTextColor(cText());
                            else if (k.equals("m")) T.setTextColor(cMuted());
                            else if (k.equals("g")) T.setTextColor(cGold());
                            else if (k.equals("red")) T.setTextColor(cRed());
                            else if (k.equals("green")) T.setTextColor(0xFF22C55E);
                            else if (k.equals("wh")) T.setTextColor(0xFFFFFFFF);
                        }
                    }
                }
                if (x instanceof TextView) tintIcons((TextView) x);
                if (x instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) x;
                    for (int i = 0; i < g.getChildCount(); i++) style(g.getChildAt(i));
                }
            }
            int goldA(int a) { return (cGold() & 0x00FFFFFF) | (a << 24); }

            // نصف قطر الزاوية: كبسولة للأزرار القصيرة (فلاتر المحادثات) وإلا 12
            float rad(View x) {
                ViewGroup.LayoutParams lp = x.getLayoutParams();
                if (lp != null && lp.height > 0) {
                    float h = lp.height / act.getResources().getDisplayMetrics().density;
                    return h <= 40 ? h / 2f : 14f;
                }
                return 12f;
            }

            // ============ تصميم الملف الشخصي (مطابق للـ HTML) ============
            android.graphics.drawable.Drawable dr(int res, int sizeDp, int color) {
                if (res == 0) return null;
                android.graphics.drawable.Drawable d = act.getResources().getDrawable(res).mutate();
                d.setBounds(0, 0, dp(sizeDp), dp(sizeDp));
                if (color != 0) d.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN);
                return d;
            }

            void setTab(View b, boolean on) {
                if (!(b instanceof TextView)) return;
                TextView t = (TextView) b;
                t.setTextColor(on ? cGold() : cMuted());
                t.setTypeface(null, android.graphics.Typeface.BOLD);
                if (t.getLayoutParams() != null) t.getLayoutParams().height = dp(46);
                if (on) {
                    android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{shape(cGold(), 2, 0)});
                    ld.setLayerInset(0, dp(26), dp(43), dp(26), 0);
                    t.setBackground(ld);
                } else t.setBackground(null);
                tintIcons(t);
                t.requestLayout();
            }

            void fixCover(View frame) {
                if (!(frame instanceof FrameLayout)) return;
                FrameLayout f = (FrameLayout) frame;
                f.getLayoutParams().height = dp(176);
                View fade = f.findViewWithTag("fade");
                if (fade == null) {
                    fade = new View(act);
                    fade.setTag("fade");
                    f.addView(fade, 0, new FrameLayout.LayoutParams(-1, dp(110), Gravity.BOTTOM));
                }
                fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{cBg() & 0x00FFFFFF, cBg()}));
                f.requestLayout();
            }

            void fixAvatar(String id) {
                TextView a = tv(id);
                if (a == null) return;
                ViewGroup.LayoutParams lp = a.getLayoutParams();
                if (lp != null) { lp.width = dp(108); lp.height = dp(108); }
                a.setTextSize(40);
                a.setTextColor(0xFFFFFFFF);
                if (photoBmp != null && id.equals("pAvatarText")) {
                    applyPhotoTo(a);
                } else {
                    GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{dark ? 0xFFF0D77A : 0xFFE8C25A, dark ? 0xFFC9A227 : 0xFFB88A1C});
                    g.setShape(GradientDrawable.OVAL);
                    g.setStroke(dp(5), cBg());
                    a.setBackground(g);
                }
                a.setElevation(dp(6));
                View par = (View) a.getParent();
                if (par != null && par.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)
                    ((ViewGroup.MarginLayoutParams) par.getLayoutParams()).topMargin = -dp(58);
                a.requestLayout();
            }

            void quick(String id, String iconName) {
                TextView t = tv(id);
                if (t == null) return;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(74), 1f);
                lp.setMargins(dp(4.5f), 0, dp(4.5f), 0);
                t.setLayoutParams(lp);
                t.setPadding(dp(4), dp(10), dp(4), dp(10));
                t.setGravity(Gravity.CENTER);
                t.setCompoundDrawablePadding(dp(6));
                t.setTextSize(12);
                t.setTypeface(null, android.graphics.Typeface.BOLD);
                t.setBackground(shape(cSf(), 16, cBorder()));
                t.setTextColor(cText());
                t.setCompoundDrawablesRelative(null, dr(icon(iconName, 16), 22, cGold()), null, null);
            }

            // kind: 0 ذهبي رئيسي، 1 بطاقة بأيقونة ذهبية، 2 خطر
            void actBtn(String id, int kind) {
                TextView t = tv(id);
                if (t == null) return;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(52), 1f);
                lp.setMargins(dp(4.5f), 0, dp(4.5f), 0);
                t.setLayoutParams(lp);
                t.setPadding(dp(10), 0, dp(10), 0);
                t.setGravity(Gravity.CENTER);
                t.setCompoundDrawablePadding(dp(8));
                t.setTextSize(13);
                t.setTypeface(null, android.graphics.Typeface.BOLD);
                int ic;
                if (kind == 0) { t.setBackground(shape(cGold(), 16, 0)); t.setTextColor(0xFF17212B); ic = 0xFF17212B; }
                else if (kind == 1) { t.setBackground(shape(cSf(), 16, cBorder())); t.setTextColor(cText()); ic = cGold(); }
                else { t.setBackground(shape(cSf(), 16, cBorder())); t.setTextColor(cRed()); ic = cRed(); }
                android.graphics.drawable.Drawable[] ds = t.getCompoundDrawablesRelative();
                if (ds[0] != null) ds[0].mutate().setColorFilter(ic, android.graphics.PorterDuff.Mode.SRC_IN);
            }

            void polishProfile() {
                try {
                    View c1 = v("pCoverTxt"), c2 = v("otherBack");
                    if (c1 != null) fixCover((View) c1.getParent());
                    if (c2 != null) fixCover((View) c2.getParent());
                    fixAvatar("pAvatarText");
                    fixAvatar("otherAvatarText");
                    for (String id : new String[]{"pHeaderName", "otherHeaderName"}) {
                        TextView t = tv(id);
                        if (t != null) { t.setTextSize(23); t.setTypeface(null, android.graphics.Typeface.BOLD); }
                    }
                    for (String id : new String[]{"otherBack", "otherMoreBtn"}) {
                        View b = v(id);
                        if (b == null) continue;
                        GradientDrawable g = new GradientDrawable();
                        g.setShape(GradientDrawable.OVAL);
                        g.setColor(0x59000000);
                        b.setBackground(g);
                        if (b.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) b.getLayoutParams();
                            lp.width = dp(40); lp.height = dp(40);
                            lp.setMargins(dp(12), dp(12), dp(12), 0);
                        }
                    }
                    quick("pEdit", "ic_edit");
                    quick("pPhoto", "ic_camera");
                    quick("pCover", "ic_palette");
                    actBtn("otherMsgBtn", 0);
                    actBtn("otherBlockBtn", 2);
                    int[] icn = {648, 650, 652, 654, 656, 658, 660, 662, 664};
                    for (int n : icn) {
                        TextView t = tv("textview" + n);
                        if (t == null) continue;
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(36));
                        lp.setMarginEnd(dp(15));
                        t.setLayoutParams(lp);
                        t.setGravity(Gravity.CENTER);
                        t.setBackground(shape(goldA(0x21), 11, 0));
                        t.setTextSize(17);
                        t.setTextColor(cGold());
                    }
                    setTab(v("pTabStoriesBtn"), true);
                } catch (Throwable ignored) { }
            }

            void tintIcons(TextView T) {
                android.graphics.drawable.Drawable[] ds = T.getCompoundDrawablesRelative();
                for (android.graphics.drawable.Drawable d : ds) {
                    if (d != null) d.mutate().setColorFilter(T.getCurrentTextColor(), android.graphics.PorterDuff.Mode.SRC_IN);
                }
            }
            int icon(String name, int size) { return act.getResources().getIdentifier(name + "_" + size, "drawable", act.getPackageName()); }
            void setLabel(String id, String label, int iconRes) {
                TextView T = tv(id);
                if (T == null) return;
                T.setText(label);
                T.setCompoundDrawablePadding(dp(6));
                if (iconRes != 0) {
                    T.setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0);
                }
                tintIcons(T);
            }
            void setOn(View b, boolean on, String offTag) {
                if (b == null) return;
                b.setBackgroundColor(Color.TRANSPARENT);
                b.setTag(on ? "gold" : offTag);
                style(b);
            }

            void applyTheme() {
                style(v("root"));
                View pvBox = v("pvBox");
                if (pvBox != null) pvBox.setBackgroundColor(cChatBg());
                TextView themeDesc = tv("themeDescription");
                if (themeDesc != null) {
                    themeDesc.setText(dark ? "الوضع الليلي مفعّل" : "الوضع النهاري مفعّل");
                }
                setLabel("settingsThemeToggle", dark ? "نهاري" : "ليلي", icon(dark ? "ic_sun" : "ic_moon", 16));
                try { buildThemeStrip(); } catch (Throwable ignored) { }
                try { setNav(curPage); } catch (Throwable ignored) { }
                polishProfile();
                try {
                    Window w = act.getWindow();
                    w.setNavigationBarColor(dark ? 0xFF0E1621 : 0xFFFFFFFF);
                    w.setStatusBarColor(dark ? 0xFF0E1621 : 0xFFF0F2F5);
                    if (Build.VERSION.SDK_INT >= 26) {
                        int flags = dark ? 0 : (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
                        w.getDecorView().setSystemUiVisibility(flags);
                    }
                } catch (Throwable ignored) { }
            }

            void show(String p) {
                if (p == null) return;
                prevPage = curPage; curPage = p;
                View target = v(p);
                for (String page : pages) {
                    View pageView = v(page);
                    if (pageView == null) continue;
                    if (page.equals(p)) {
                        pageView.setVisibility(View.VISIBLE);
                        pageView.clearAnimation();
                        pageView.startAnimation(android.view.animation.AnimationUtils.loadAnimation(act, R.anim.bb_page_enter));
                        pageView.setAlpha(1f);
                        pageView.setTranslationY(0f);
                    } else {
                        pageView.animate().cancel();
                        pageView.setVisibility(View.GONE);
                        pageView.setAlpha(1f);
                        pageView.setTranslationY(0f);
                    }
                }
                boolean home = p.equals("homeView");
                vis("mainHeader", home);
                vis("newGroupFab", home);
                vis("bottomNav", home || p.equals("settingsView") || p.equals("profileView"));
                setNav(p);
                hideKb();
            }

            void animateMessageIn(View view) {
                if (view == null) return;
                try { view.startAnimation(android.view.animation.AnimationUtils.loadAnimation(act, R.anim.bb_message_enter)); }
                catch (Throwable ignored) { }
            }

            void setupPressAnimations() {
                String[] ids = {"navChats", "navSettings", "navProfile", "chatBack", "chatMore", "btnSend", "btnMic", "btnAttach", "pEdit", "pPhoto", "pCover", "otherMsgBtn", "otherBlockBtn", "privacyBack", "settingsThemeToggle"};
                for (String id : ids) {
                    View view = v(id);
                    if (view == null) continue;
                    view.setOnTouchListener((target, event) -> {
                        if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                            target.animate().scaleX(.96f).scaleY(.96f).setDuration(85).start();
                        } else if (event.getAction() == android.view.MotionEvent.ACTION_UP || event.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                            target.animate().scaleX(1f).scaleY(1f).setDuration(135).setInterpolator(new android.view.animation.OvershootInterpolator(1.15f)).start();
                        }
                        return false;
                    });
                }
            }

            void showNativeConfirm(String titleText, String messageText, String confirmText, Runnable action) {
                final Dialog d = new Dialog(act);
                View content = act.getLayoutInflater().inflate(R.layout.native_chat_delete_dialog, null, false);
                d.setContentView(content);
                Window w = d.getWindow();
                if (w != null) { w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT)); w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); WindowManager.LayoutParams lp=w.getAttributes(); lp.dimAmount=.68f; lp.width=WindowManager.LayoutParams.MATCH_PARENT; lp.height=WindowManager.LayoutParams.WRAP_CONTENT; lp.gravity=Gravity.CENTER; w.setAttributes(lp); }
                ((TextView)content.findViewById(R.id.deleteDialogTitle)).setText(titleText);
                ((TextView)content.findViewById(R.id.deleteDialogMessage)).setText(messageText);
                CheckBox check=(CheckBox)content.findViewById(R.id.deleteDialogBoth); check.setVisibility(View.GONE);
                TextView ok=(TextView)content.findViewById(R.id.deleteDialogConfirm); ok.setText(confirmText);
                content.findViewById(R.id.deleteDialogCancel).setOnClickListener(x->d.dismiss());
                ok.setOnClickListener(x->{d.dismiss();if(action!=null)action.run();});
                d.show(); if(w!=null)w.setLayout(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.WRAP_CONTENT);
                content.startAnimation(android.view.animation.AnimationUtils.loadAnimation(act,R.anim.bb_sheet_enter));
            }

            void showChatActions(String name, String uid) {
                final Dialog d=new Dialog(act);
                View content=act.getLayoutInflater().inflate(R.layout.native_chat_actions,null,false);
                d.setContentView(content); Window w=d.getWindow();
                if(w!=null){w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);WindowManager.LayoutParams lp=w.getAttributes();lp.dimAmount=.62f;lp.width=WindowManager.LayoutParams.MATCH_PARENT;lp.height=WindowManager.LayoutParams.WRAP_CONTENT;lp.gravity=Gravity.BOTTOM;w.setAttributes(lp);}
                ((TextView)content.findViewById(R.id.chatActionTitle)).setText(name);
                content.findViewById(R.id.chatActionRead).setOnClickListener(x->{toast("تم تعليم المحادثة كمقروءة");d.dismiss();});
                content.findViewById(R.id.chatActionDelete).setOnClickListener(x->{d.dismiss();showDeleteChatConfirm(name,uid);});
                content.findViewById(R.id.chatActionCancel).setOnClickListener(x->d.dismiss());
                d.show();if(w!=null)w.setLayout(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.WRAP_CONTENT);
                content.startAnimation(android.view.animation.AnimationUtils.loadAnimation(act,R.anim.bb_sheet_enter));
            }

            void showDeleteChatConfirm(String name, String uid) {
                final String cid = getChatId(myUid, uid);
                final Dialog d = new Dialog(act);
                View content = act.getLayoutInflater().inflate(R.layout.native_chat_delete_dialog, null, false);
                d.setContentView(content);
                Window w = d.getWindow();
                if (w != null) {
                    w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                    w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                    WindowManager.LayoutParams lp = w.getAttributes(); lp.dimAmount = .68f; lp.width = WindowManager.LayoutParams.MATCH_PARENT; lp.height = WindowManager.LayoutParams.WRAP_CONTENT; lp.gravity = Gravity.CENTER; w.setAttributes(lp);
                }
                TextView title = (TextView) content.findViewById(R.id.deleteDialogTitle);
                TextView message = (TextView) content.findViewById(R.id.deleteDialogMessage);
                CheckBox both = (CheckBox) content.findViewById(R.id.deleteDialogBoth);
                TextView cancel = (TextView) content.findViewById(R.id.deleteDialogCancel);
                TextView confirm = (TextView) content.findViewById(R.id.deleteDialogConfirm);
                title.setText("حذف المحادثة");
                message.setText("هل تريد حذف المحادثة مع " + name + "؟ لا يمكن التراجع عن هذا الإجراء.");
                cancel.setOnClickListener(x -> d.dismiss());
                confirm.setOnClickListener(x -> {
                    boolean removeForBoth = both.isChecked();
                    d.dismiss();
                    if (removeForBoth) {
                        deleteChatMessages(cid, () -> db.collection("chats").document(cid).delete()
                            .addOnSuccessListener(ok -> { chatData.removeIf(row -> row.length > 4 && uid.equals(row[4])); buildChats(); toast("تم حذف المحادثة لدى الطرفين"); })
                            .addOnFailureListener(err -> toast("تعذر حذف المحادثة: " + err.getMessage())));
                    } else {
                        java.util.Set<String> hidden = new java.util.HashSet<>(sp.getStringSet("hiddenChats", Collections.emptySet()));
                        hidden.add(cid); sp.edit().putStringSet("hiddenChats", hidden).apply();
                        chatData.removeIf(row -> row.length > 4 && uid.equals(row[4])); buildChats(); toast("تم إخفاء المحادثة من جهازك");
                    }
                });
                d.show();
                if (w != null) w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
                content.startAnimation(android.view.animation.AnimationUtils.loadAnimation(act, R.anim.bb_sheet_enter));
            }

            void showClearConversationConfirm() {
                if (currentChatId == null || currentChatId.isEmpty()) return;
                final Dialog d = new Dialog(act);
                View content = act.getLayoutInflater().inflate(R.layout.native_chat_delete_dialog, null, false);
                d.setContentView(content);
                Window w = d.getWindow();
                if (w != null) { w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT)); w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); WindowManager.LayoutParams lp=w.getAttributes(); lp.dimAmount=.68f; lp.width=WindowManager.LayoutParams.MATCH_PARENT; lp.height=WindowManager.LayoutParams.WRAP_CONTENT; lp.gravity=Gravity.CENTER; w.setAttributes(lp); }
                ((TextView)content.findViewById(R.id.deleteDialogTitle)).setText("مسح المحادثة");
                ((TextView)content.findViewById(R.id.deleteDialogMessage)).setText("سيتم حذف الرسائل من هذه المحادثة. هل تريد المتابعة؟");
                CheckBox both=(CheckBox)content.findViewById(R.id.deleteDialogBoth); both.setVisibility(View.GONE);
                content.findViewById(R.id.deleteDialogCancel).setOnClickListener(x->d.dismiss());
                content.findViewById(R.id.deleteDialogConfirm).setOnClickListener(x->{String cid=currentChatId;d.dismiss();deleteChatMessages(cid,()->{LinearLayout ma=(LinearLayout)v("messagesArea");if(ma!=null)ma.removeAllViews();renderedMsgIds.clear();toast("تم مسح المحادثة");});});
                d.show(); if(w!=null)w.setLayout(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.WRAP_CONTENT); content.startAnimation(android.view.animation.AnimationUtils.loadAnimation(act,R.anim.bb_sheet_enter));
            }

            void setupProfileMotion() {
                setupAvatarPull("pAvatarText", "pHeaderName", false);
                setupAvatarPull("otherAvatarText", "otherHeaderName", true);
            }

            void setupAvatarPull(String avatarId, String nameId, boolean otherProfile) {
                final View avatar = v(avatarId);
                if (avatar == null) return;
                final int screenW = act.getResources().getDisplayMetrics().widthPixels;
                final int startSize = dp(108);
                final int[] start = new int[2];
                final float[] down = new float[2];
                final float[] progress = {0f};
                final boolean[] dragging = {false};
                final Dialog[] dialog = {null};
                final FrameLayout[] overlay = {null};
                final ImageView[] image = {null};
                final TextView[] initial = {null};
                final TextView[] name = {null};
                final int sbId = act.getResources().getIdentifier("status_bar_height", "dimen", "android");
                final int statusBar = sbId > 0 ? act.getResources().getDimensionPixelSize(sbId) : 0;

                java.util.function.Supplier<Bitmap> currentBitmap = () -> otherProfile ? otherPhotoBmp : photoBmp;
                java.util.function.Supplier<String> currentName = () -> otherProfile ? otherName : userName;

                Runnable close = () -> {
                    if (dialog[0] == null) return;
                    Dialog old = dialog[0];
                    FrameLayout oldRoot = overlay[0];
                    if (oldRoot != null) {
                        oldRoot.animate().alpha(0f).setDuration(150).withEndAction(() -> {
                            try { old.dismiss(); } catch (Exception ignored) { }
                        }).start();
                    } else old.dismiss();
                    dialog[0] = null; overlay[0] = null; image[0] = null;
                    initial[0] = null; name[0] = null; progress[0] = 0f;
                };

                class Updater {
                    void update(float raw) {
                        float p = Math.max(0f, Math.min(1f, raw));
                        progress[0] = p;
                        if (dialog[0] == null || image[0] == null) return;
                        int size = (int) (startSize + (screenW - startSize) * p);
                        int left = (screenW - size) / 2;
                        int top = (int) ((start[1] - statusBar) * (1f - p));
                        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) image[0].getLayoutParams();
                        lp.width = size; lp.height = size; lp.leftMargin = left; lp.topMargin = top;
                        lp.gravity = Gravity.TOP | Gravity.LEFT;
                        image[0].setLayoutParams(lp);
                        float radius = dp(54) * (1f - p);
                        android.view.ViewOutlineProvider outline = new android.view.ViewOutlineProvider() {
                            @Override public void getOutline(View view, android.graphics.Outline out) {
                                out.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                            }
                        };
                        image[0].setOutlineProvider(outline);
                        image[0].setClipToOutline(true);
                        if (initial[0] != null) {
                            FrameLayout.LayoutParams ilp = (FrameLayout.LayoutParams) initial[0].getLayoutParams();
                            ilp.width = size; ilp.height = size; ilp.leftMargin = left; ilp.topMargin = top;
                            ilp.gravity = Gravity.TOP | Gravity.LEFT;
                            initial[0].setLayoutParams(ilp);
                            initial[0].setTextSize(size / act.getResources().getDisplayMetrics().density * .34f);
                            initial[0].setOutlineProvider(outline);
                            initial[0].setClipToOutline(true);
                        }
                        if (name[0] != null) {
                            name[0].setAlpha(Math.max(0f, Math.min(1f, (p - .52f) / .40f)));
                            name[0].setTranslationY(dp(16) * (1f - p));
                        }
                        if (overlay[0] != null) {
                            overlay[0].setBackgroundColor(dark ? 0xE90A101B : 0xE9F8FAFC);
                        }
                    }
                }
                final Updater updater = new Updater();

                Runnable ensure = () -> {
                    if (dialog[0] != null) return;
                    Dialog d = new Dialog(act);
                    FrameLayout frame = new FrameLayout(act);
                    frame.setBackgroundColor(dark ? 0xE90A101B : 0xE9F8FAFC);
                    Bitmap bmp = currentBitmap.get();
                    ImageView img = new ImageView(act);
                    img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    if (bmp != null && !bmp.isRecycled()) img.setImageBitmap(bmp);
                    else img.setImageDrawable(avatar.getBackground());
                    FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(startSize, startSize, Gravity.TOP | Gravity.LEFT);
                    frame.addView(img, ip);

                    TextView letter = new TextView(act);
                    letter.setGravity(Gravity.CENTER);
                    letter.setText(avatar instanceof TextView ? ((TextView) avatar).getText() : "؟");
                    letter.setTextColor(avatar instanceof TextView ? ((TextView) avatar).getCurrentTextColor() : Color.WHITE);
                    letter.setTypeface(null, android.graphics.Typeface.BOLD);
                    letter.setTextSize(39);
                    letter.setVisibility(bmp == null ? View.VISIBLE : View.GONE);
                    letter.setBackground(avatar.getBackground());
                    frame.addView(letter, new FrameLayout.LayoutParams(startSize, startSize, Gravity.TOP | Gravity.LEFT));

                    TextView title = new TextView(act);
                    title.setText(currentName.get());
                    title.setTextColor(dark ? Color.WHITE : 0xFF17212B);
                    title.setTextSize(24);
                    title.setTypeface(null, android.graphics.Typeface.BOLD);
                    title.setGravity(Gravity.CENTER);
                    title.setPadding(dp(18), 0, dp(18), 0);
                    title.setAlpha(0f);
                    FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
                    np.bottomMargin = dp(30);
                    frame.addView(title, np);

                    d.setContentView(frame);
                    d.setCancelable(false);
                    Window win = d.getWindow();
                    if (win != null) {
                        win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                        win.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
                        WindowManager.LayoutParams wp = win.getAttributes();
                        wp.dimAmount = 0f; wp.width = WindowManager.LayoutParams.MATCH_PARENT;
                        wp.height = WindowManager.LayoutParams.MATCH_PARENT; wp.gravity = Gravity.TOP | Gravity.LEFT;
                        win.setAttributes(wp);
                    }
                    d.show();
                    if (win != null) {
                        win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                        win.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
                    }
                    avatar.getLocationOnScreen(start);
                    dialog[0] = d; overlay[0] = frame; image[0] = img; initial[0] = letter; name[0] = title;
                    frame.setOnClickListener(vw -> { if (progress[0] > .72f) close.run(); });
                    frame.setOnTouchListener(new View.OnTouchListener() {
                        float y0;
                        @Override public boolean onTouch(View target, MotionEvent ev) {
                            if (ev.getAction() == MotionEvent.ACTION_DOWN) { y0 = ev.getRawY(); return true; }
                            if (ev.getAction() == MotionEvent.ACTION_MOVE) {
                                float dy = ev.getRawY() - y0;
                                if (dy < 0) updater.update(Math.max(0f, 1f + dy / Math.max(dp(240), screenW * .62f)));
                                return true;
                            }
                            if (ev.getAction() == MotionEvent.ACTION_UP || ev.getAction() == MotionEvent.ACTION_CANCEL) {
                                float total = ev.getRawY() - y0;
                                if (progress[0] < .45f || total < -dp(28) || Math.abs(total) < dp(10)) close.run();
                                else {
                                    android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(progress[0], 1f);
                                    a.setDuration(190); a.setInterpolator(new android.view.animation.DecelerateInterpolator());
                                    a.addUpdateListener(v -> updater.update((Float) v.getAnimatedValue())); a.start();
                                }
                                return true;
                            }
                            return true;
                        }
                    });
                    updater.update(0f);
                };

                avatar.setOnTouchListener((target, event) -> {
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            down[0] = event.getRawX(); down[1] = event.getRawY(); dragging[0] = false;
                            target.animate().scaleX(1.025f).scaleY(1.025f).setDuration(90).start();
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            float dy = event.getRawY() - down[1];
                            float dx = event.getRawX() - down[0];
                            if (dy > dp(7) && Math.abs(dy) > Math.abs(dx) * 1.05f) {
                                if (!dragging[0]) { dragging[0] = true; ensure.run(); }
                                updater.update(Math.min(1f, dy / Math.max(dp(270), screenW * .70f)));
                            }
                            return true;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            target.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
                            if (dragging[0]) {
                                if (progress[0] >= .42f) {
                                    android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(progress[0], 1f);
                                    a.setDuration(210); a.setInterpolator(new android.view.animation.DecelerateInterpolator());
                                    a.addUpdateListener(v -> updater.update((Float) v.getAnimatedValue())); a.start();
                                } else close.run();
                            } else if (event.getAction() == MotionEvent.ACTION_UP) {
                                if (otherProfile) showOtherAvatarActions(); else showAvatarActions();
                            }
                            dragging[0] = false;
                            return true;
                    }
                    return true;
                });
            }

            void showOtherAvatarActions() {
                final Dialog d = new Dialog(act);
                View content = act.getLayoutInflater().inflate(R.layout.native_avatar_gallery, null, false);
                styleSheetDialog(d, content, true);
                ImageView image = (ImageView) content.findViewById(R.id.avatarGalleryImage);
                TextView initial = (TextView) content.findViewById(R.id.avatarGalleryInitial);
                if (otherPhotoBmp != null && !otherPhotoBmp.isRecycled()) {
                    image.setImageBitmap(otherPhotoBmp); initial.setVisibility(View.GONE);
                } else {
                    image.setVisibility(View.GONE);
                    initial.setText(otherName.length() > 0 ? otherName.substring(0, 1) : "؟");
                }
                content.findViewById(R.id.avatarGalleryDelete).setVisibility(View.GONE);
                content.findViewById(R.id.avatarGalleryChoose).setVisibility(View.GONE);
                content.findViewById(R.id.avatarGalleryClose).setOnClickListener(x -> d.dismiss());
            }

            void setupSwipeNavigation() {
                View[] surfaces = {v("homeView"), v("settingsView"), v("profileView"), v("chatView"), v("privacySettingsView"), v("notificationSettingsView"), v("chatSettingsView")};
                for (View surface : surfaces) {
                    if (surface == null) continue;
                    surface.setClickable(true);
                    surface.setOnTouchListener(new View.OnTouchListener() {
                        float sx, sy;
                        public boolean onTouch(View view, android.view.MotionEvent ev) {
                            if (ev.getAction() == android.view.MotionEvent.ACTION_DOWN) { sx=ev.getX(); sy=ev.getY(); return false; }
                            if (ev.getAction() == android.view.MotionEvent.ACTION_UP) {
                                float dx=ev.getX()-sx, dy=ev.getY()-sy;
                                if (Math.abs(dx)>dp(86) && Math.abs(dx)>Math.abs(dy)*1.35f) {
                                    String page=curPage;
                                    if (page.equals("chatView")) { if (dx>0) closeChat(); return false; }
                                    if (page.equals("privacySettingsView") || page.equals("notificationSettingsView") || page.equals("chatSettingsView")) { if (dx>0) show("settingsView"); return false; }
                                    if (dx<0) {
                                        if (page.equals("homeView")) show("settingsView");
                                        else if (page.equals("settingsView")) { loadMyProfile(); show("profileView"); }
                                    } else {
                                        if (page.equals("profileView")) show("settingsView");
                                        else if (page.equals("settingsView")) show("homeView");
                                    }
                                }
                            }
                            return false;
                        }
                    });
                }
            }

            void setupStoryGestures() {
                View handle=v("storyGestureHandle"), scroll=v("storiesScroll");
                if (handle==null || scroll==null) return;
                handle.setOnClickListener(x -> {
                    boolean visible=scroll.getVisibility()==View.VISIBLE;
                    if (visible) scroll.animate().alpha(0f).setDuration(120).withEndAction(() -> scroll.setVisibility(View.GONE)).start();
                    else { scroll.setAlpha(0f); scroll.setVisibility(View.VISIBLE); scroll.animate().alpha(1f).setDuration(180).start(); }
                });
                handle.setOnTouchListener(new View.OnTouchListener(){ float y; public boolean onTouch(View v,android.view.MotionEvent e){
                    if(e.getAction()==android.view.MotionEvent.ACTION_DOWN){y=e.getY();return true;}
                    if(e.getAction()==android.view.MotionEvent.ACTION_UP){float dy=e.getY()-y;if(Math.abs(dy)>dp(18)){boolean hide=dy<0;if(hide){scroll.animate().alpha(0f).setDuration(120).withEndAction(()->scroll.setVisibility(View.GONE)).start();}else{scroll.setVisibility(View.VISIBLE);scroll.setAlpha(0f);scroll.animate().alpha(1f).setDuration(180).start();}}return true;} return true; }});
            }

            void styleSheetDialog(Dialog dialog, View content, boolean full) {
                dialog.setContentView(content);
                android.view.Window win=dialog.getWindow();
                if(win!=null){win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));win.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);WindowManager.LayoutParams lp=win.getAttributes();lp.dimAmount=.62f;lp.width=WindowManager.LayoutParams.MATCH_PARENT;lp.height=full?WindowManager.LayoutParams.MATCH_PARENT:WindowManager.LayoutParams.WRAP_CONTENT;lp.gravity=full?Gravity.CENTER:Gravity.BOTTOM;win.setAttributes(lp);}
                dialog.show();
                if(win!=null){win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));win.setLayout(WindowManager.LayoutParams.MATCH_PARENT,full?WindowManager.LayoutParams.MATCH_PARENT:WindowManager.LayoutParams.WRAP_CONTENT);}
                content.clearAnimation();
                content.startAnimation(android.view.animation.AnimationUtils.loadAnimation(act, R.anim.bb_sheet_enter));
            }

            void showStoryComposer() {
                storyDraftUri = null;
                final Dialog d=new Dialog(act);
                View content=act.getLayoutInflater().inflate(R.layout.native_story_composer,null,false);
                styleSheetDialog(d,content,false);
                TextView close=(TextView)content.findViewById(R.id.storyCancel);
                TextView pick=(TextView)content.findViewById(R.id.storyMediaPick);
                TextView publish=(TextView)content.findViewById(R.id.storyPublish);
                EditText caption=(EditText)content.findViewById(R.id.storyCaptionInput);
                final int[] duration={24};
                TextView[] chips={(TextView)content.findViewById(R.id.storyDuration24),(TextView)content.findViewById(R.id.storyDuration12),(TextView)content.findViewById(R.id.storyDuration6)};
                chips[0].setSelected(true);
                for(int i=0;i<chips.length;i++){final int val=i==0?24:i==1?12:6;final TextView chip=chips[i];chip.setOnClickListener(x->{duration[0]=val;for(TextView c:chips){c.setSelected(c==chip);c.setTextColor(c==chip?0xFF17212B:0xFFE2E8F0);}});}
                close.setOnClickListener(x->d.dismiss());
                pick.setOnClickListener(x->{pendingImageCallback=uri->{pick.setText("تم اختيار الوسائط");storyDraftUri=uri;};Intent i=new Intent(Intent.ACTION_GET_CONTENT);i.setType("image/*");try{act.startActivityForResult(Intent.createChooser(i,"اختر صورة للقصة"),REQ_PICK_IMAGE);}catch(Exception ex){toast("تعذر فتح معرض الصور");}});
                publish.setOnClickListener(x->{String text=caption.getText().toString().trim();if(text.isEmpty()&&storyDraftUri==null){toast("أضف وصفاً أو صورة للقصة");return;}toast("واجهة القصة جاهزة؛ نشر القصص يحتاج مسار قصص في قاعدة البيانات الحالية");d.dismiss();});
            }

            void showStoryViewer(String name) {
                final Dialog d=new Dialog(act);
                View content=act.getLayoutInflater().inflate(R.layout.native_story_viewer,null,false);
                styleSheetDialog(d,content,true);
                TextView nm=(TextView)content.findViewById(R.id.storyViewerName);TextView av=(TextView)content.findViewById(R.id.storyViewerAvatar);
                nm.setText(name);av.setText(name.length()>0?name.substring(0,1):"؟");
                content.findViewById(R.id.storyViewerClose).setOnClickListener(x->d.dismiss());
                content.setOnTouchListener(new View.OnTouchListener(){float x0,y0;public boolean onTouch(View v,android.view.MotionEvent e){if(e.getAction()==0){x0=e.getX();y0=e.getY();return true;}if(e.getAction()==1){if(Math.abs(e.getX()-x0)>dp(55)||Math.abs(e.getY()-y0)>dp(55))d.dismiss();return true;}return true;}});
            }

            void showCoverPicker(boolean otherProfile) {
                final Dialog d=new Dialog(act);View content=act.getLayoutInflater().inflate(R.layout.native_cover_picker,null,false);styleSheetDialog(d,content,false);
                int[] colors={0xFF1B2A4A,0xFF3B2A4A,0xFF0F5132,0xFF7C2D12,0xFF334155,0xFFB8891A};
                for(int i=0;i<6;i++){final int ix=i;int id=act.getResources().getIdentifier("coverColor"+i,"id",act.getPackageName());View sw=content.findViewById(id);if(sw!=null)sw.setOnClickListener(x->{coverColor=colors[ix];sp.edit().putInt(otherProfile?"otherCover":"cover",coverColor).apply();View cv=v(otherProfile?"frame_layout657":"frame_layout642");if(cv!=null)cv.setBackgroundColor(coverColor);d.dismiss();});}
                content.findViewById(R.id.coverPickerCancel).setOnClickListener(x->d.dismiss());
            }

            void showAvatarActions() {
                final Dialog d=new Dialog(act);
                View content=act.getLayoutInflater().inflate(R.layout.native_avatar_gallery,null,false);
                styleSheetDialog(d,content,true);
                ImageView image=(ImageView)content.findViewById(R.id.avatarGalleryImage);
                TextView initial=(TextView)content.findViewById(R.id.avatarGalleryInitial);
                if(photoBmp!=null){image.setImageBitmap(photoBmp);initial.setVisibility(View.GONE);}else{image.setVisibility(View.GONE);initial.setText(userName.length()>0?userName.substring(0,1):"؟");}
                content.findViewById(R.id.avatarGalleryClose).setOnClickListener(x->d.dismiss());
                content.findViewById(R.id.avatarGalleryChoose).setOnClickListener(x->{d.dismiss();pickProfilePhoto();});
                content.findViewById(R.id.avatarGalleryDelete).setOnClickListener(x->{
                    if(userPhoto==null||userPhoto.isEmpty()){toast("لا توجد صورة لحذفها");return;}
                    db.collection("users").document(myUid).update("photo","").addOnSuccessListener(a->{userPhoto="";shownPhoto="";photoBmp=null;fillProfile();toast("تم حذف الصورة الشخصية");d.dismiss();}).addOnFailureListener(e->toast("تعذر حذف الصورة: "+e.getMessage()));
                });
            }

            void setNav(String p) {
                String[] ids = {"navChats", "navSettings", "navProfile"};
                String a2 = p.equals("homeView") ? "navChats" : p.equals("settingsView") ? "navSettings" : p.equals("profileView") ? "navProfile" : "";
                for (String id : ids) {
                    boolean on = id.equals(a2);
                    TextView ic = tv(id + "Ic");
                    TextView tx = tv(id + "Tx");
                    if (ic != null) ic.setTag(on ? "g" : "m");
                    if (tx != null) tx.setTag(on ? "g" : "m");
                    View vv = v(id);
                    if (vv != null) {
                        if (on) {
                            vv.setBackground(shape(goldA(dark ? 0x26 : 0x24), 17, goldA(0x33)));
                            if (vv.getLayoutParams() instanceof LinearLayout.LayoutParams) {
                                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) vv.getLayoutParams();
                                lp.setMargins(dp(8), dp(8), dp(8), dp(8));
                                vv.setLayoutParams(lp);
                            }
                        } else {
                            vv.setBackgroundColor(Color.TRANSPARENT);
                            if (vv.getLayoutParams() instanceof LinearLayout.LayoutParams) {
                                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) vv.getLayoutParams();
                                lp.setMargins(0, 0, 0, 0);
                                vv.setLayoutParams(lp);
                            }
                        }
                        style(vv);
                        vv.animate().scaleX(on ? 1.035f : 1f).scaleY(on ? 1.035f : 1f).setDuration(150).start();
                    }
                }
            }

            void loadMyProfile() {
                db.collection("users").document(myUid).get()
                    .addOnSuccessListener(doc -> {
                        if (doc.exists()) {
                            userName = safe(doc.getString("name"));
                            userUsername = safe(doc.getString("username"));
                            userBio = safe(doc.getString("bio"));
                            userGov = safe(doc.getString("gov"));
                            userEmail = safe(doc.getString("email"));
                            userPhoto = safe(doc.getString("photo"));
                            Long createdAt = doc.getLong("createdAt");

                            if (userName.isEmpty()) userName = "Mohammed";
                            if (userUsername.isEmpty()) userUsername = "mohamed";
                            if (userBio.isEmpty()) userBio = "الشارع مالتي منو كال ملك عام";
                            if (userGov.isEmpty()) userGov = "المثنى";
                            if (userEmail.isEmpty()) userEmail = "hamodeygame45@gmail.com";
                            if (createdAt == null) createdAt = System.currentTimeMillis();

                            userJoin = new SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(new Date(createdAt));
                            try { fillProfile(); } catch (Throwable ignored) { }
                        }
                    })
                    .addOnFailureListener(e -> { });
            }
            String safe(String s) { return s != null ? s : ""; }

            void fillProfile() {
                String letter = userName.length() > 0 ? userName.substring(0, 1).toUpperCase() : "؟";
                TextView pAv = tv("pAvatarText");
                if (pAv != null) pAv.setText(letter);
                TextView pSt = tv("pHeaderStatus");
                if (pSt != null) pSt.setText("●  متصل الآن");
                TextView sxAv = tv("sxAvatar");
                if (sxAv != null) sxAv.setText(letter);
                TextView pName = tv("pHeaderName");
                if (pName != null) pName.setText(userName);
                TextView sxName = tv("sxName");
                if (sxName != null) sxName.setText(userName);
                TextView pUser = tv("pHeaderUser");
                if (pUser != null) pUser.setText(userUsername.length() > 0 ? "@" + userUsername : "");
                TextView sxUser = tv("sxUser");
                if (sxUser != null) sxUser.setText(userUsername.length() > 0 ? "@" + userUsername : "");
                TextView pBio = tv("pBioDisplay");
                if (pBio != null) pBio.setText(userBio.length() > 0 ? userBio : "أضف نبذة عن نفسك");
                TextView sxBio = tv("sxBio");
                if (sxBio != null) sxBio.setText(userBio.length() > 0 ? userBio : "أضف نبذة عن نفسك");
                TextView pGov = tv("pGovDisplay");
                if (pGov != null) pGov.setText(userGov);
                TextView pEmail = tv("pEmailDisplay");
                if (pEmail != null) pEmail.setText(userEmail);
                TextView pJoin = tv("pJoinDisplay");
                if (pJoin != null) {
                    try {
                        Date date = new SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).parse(userJoin);
                        String formattedDate = new SimpleDateFormat("MMMM yyyy", new Locale("ar")).format(date);
                        pJoin.setText(formattedDate);
                    } catch (Exception ignored) {
                        pJoin.setText(userJoin);
                    }
                }
                applyProfilePhoto();
            }

            void openEdit() {
                EditText nameE = et("modalNameInput");
                if (nameE != null) nameE.setText(userName);
                EditText userE = et("modalUsernameInput");
                if (userE != null) userE.setText(userUsername);
                EditText bioE = et("modalBioInput");
                if (bioE != null) bioE.setText(userBio);
                View govV = v("modalGovInput");
                if (govV instanceof Spinner) {
                    for (int i = 0; i < govs.length; i++)
                        if (govs[i].equals(userGov)) ((Spinner) govV).setSelection(i);
                }
                vis("modalUserError", false);
                show("editProfileView");
            }

            void saveProfile() {
                String n = txt("modalNameInput");
                String u = txt("modalUsernameInput").replace("@", "");
                String bio = txt("modalBioInput");
                View govV = v("modalGovInput");
                String gov = (govV instanceof Spinner) ? govs[((Spinner) govV).getSelectedItemPosition()] : userGov;
                if (n.length() == 0) { toast("اكتب اسم العرض"); return; }
                if (u.length() < 5 || !u.matches("[A-Za-z0-9_]+")) {
                    TextView err = tv("modalUserError");
                    if (err != null) { err.setText("اسم المستخدم 5 أحرف إنجليزية فأكثر"); err.setVisibility(View.VISIBLE); }
                    return;
                }
                db.collection("users").whereEqualTo("username", u).limit(1).get()
                    .addOnCompleteListener(task -> {
                        if (task.isSuccessful() && task.getResult() != null && !task.getResult().isEmpty()) {
                            String takenBy = task.getResult().getDocuments().get(0).getId();
                            if (!takenBy.equals(myUid) && !u.equals(userUsername)) {
                                TextView err = tv("modalUserError");
                                if (err != null) { err.setText("اسم المستخدم محجوز"); err.setVisibility(View.VISIBLE); }
                                return;
                            }
                        }
                        Map<String, Object> updates = new HashMap<>();
                        updates.put("name", n); updates.put("username", u);
                        updates.put("bio", bio); updates.put("gov", gov);
                        db.collection("users").document(myUid).update(updates)
                            .addOnSuccessListener(a -> {
                                userName = n; userUsername = u; userBio = bio; userGov = gov;
                                try { fillProfile(); } catch (Throwable ignored) { }
                                toast("تم حفظ التغييرات");
                                show("profileView");
                            })
                            .addOnFailureListener(e -> toast("فشل الحفظ: " + e.getMessage()));
                    });
            }

            void openOther(String name, String username, String uid) {
                otherName = name; otherUser = username; otherUid = uid;
                otherPhoto = ""; otherPhotoBmp = null;
                blocked = false;
                TextView oAv = tv("otherAvatarText");
                if (oAv != null) { oAv.setText(name.length() > 0 ? name.substring(0, 1) : "؟"); oAv.setBackground(shape(cGold(), 54, cBg())); }
                TextView oName = tv("otherHeaderName"); if (oName != null) oName.setText(name);
                TextView oUser = tv("otherHeaderUser"); if (oUser != null) oUser.setText("@" + username);
                TextView oBio = tv("otherBioDisplay"); if (oBio != null) oBio.setText("");
                TextView oGov = tv("otherGovDisplay"); if (oGov != null) oGov.setText("");
                TextView oJoin = tv("otherJoinDisplay"); if (oJoin != null) oJoin.setText("");
                setLabel("otherBlockBtn", "حظر", icon("ic_block", 16));
                String back = curPage;
                show("otherUserProfileView"); prevPage = back;
                db.collection("users").document(uid).get().addOnSuccessListener(doc -> {
                    if (!doc.exists() || !uid.equals(otherUid)) return;
                    String n = safe(doc.getString("name")); if (!n.isEmpty()) otherName = n;
                    String un = safe(doc.getString("username")); if (!un.isEmpty()) otherUser = un;
                    otherPhoto = safe(doc.getString("photo"));
                    String bio = safe(doc.getString("bio"));
                    String gov = safe(doc.getString("gov"));
                    Long created = doc.getLong("createdAt");
                    if (oName != null) oName.setText(otherName);
                    if (oUser != null) oUser.setText("@" + otherUser);
                    if (oBio != null) oBio.setText(bio);
                    if (oGov != null) { oGov.setText(gov); vis("otherGovRow", !gov.isEmpty()); }
                    if (oJoin != null && created != null) { oJoin.setText(new SimpleDateFormat("MMMM yyyy", new Locale("ar")).format(new Date(created))); vis("otherJoinRow", true); }
                    applyOtherPhoto();
                }).addOnFailureListener(e -> { });
            }

            void applyOtherPhoto() {
                TextView avatar = tv("otherAvatarText");
                if (avatar == null) return;
                if (otherPhoto == null || otherPhoto.isEmpty()) {
                    otherPhotoBmp = null;
                    avatar.setText(otherName.length() > 0 ? otherName.substring(0, 1) : "؟");
                    avatar.setBackground(shape(cGold(), 54, cBg()));
                    return;
                }
                try {
                    byte[] bytes = android.util.Base64.decode(otherPhoto, android.util.Base64.DEFAULT);
                    otherPhotoBmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (otherPhotoBmp != null) {
                        androidx.core.graphics.drawable.RoundedBitmapDrawable d = androidx.core.graphics.drawable.RoundedBitmapDrawableFactory.create(act.getResources(), otherPhotoBmp);
                        d.setCircular(true); avatar.setBackground(d); avatar.setText("");
                    }
                } catch (Exception e) { otherPhotoBmp = null; }
            }

            void loadChats() {
                db.collection("chats")
                    .whereArrayContains("participants", myUid)
                    .addSnapshotListener((snapshots, e) -> {
                        if (e != null || snapshots == null) return;
                        List<Map<String, Object>> temp = new ArrayList<>();
                        for (QueryDocumentSnapshot doc : snapshots) {
                            Map<String, Object> m = doc.getData();
                            m.put("__id", doc.getId());
                            temp.add(m);
                        }
                        Collections.sort(temp, (a, b) -> {
                            Long ta = (Long) a.get("lastMessageAt");
                            Long tb = (Long) b.get("lastMessageAt");
                            if (ta == null) ta = 0L;
                            if (tb == null) tb = 0L;
                            return Long.compare(tb, ta);
                        });
                        chatData.clear();
                        for (Map<String, Object> m : temp) {
                            List<String> parts = (List<String>) m.get("participants");
                            if (parts == null) continue;
                            String otherId = null;
                            for (String p : parts) if (!p.equals(myUid)) { otherId = p; break; }
                            if (otherId == null) continue;
                            final String fOtherId = otherId;
                            final String lastMsg = m.get("lastMessage") != null ? m.get("lastMessage").toString() : "";
                            final Long lastAt = (Long) m.get("lastMessageAt");
                            final String lastTime = lastAt != null ? new SimpleDateFormat("h:mm", Locale.getDefault()).format(new Date(lastAt)) : "";
                            db.collection("users").document(fOtherId).get()
                                .addOnSuccessListener(udoc -> {
                                    String oName = udoc.exists() && udoc.getString("name") != null ? udoc.getString("name") : "?";
                                    boolean found = false;
                                    for (int i = 0; i < chatData.size(); i++) {
                                        if (chatData.get(i)[4].equals(fOtherId)) {
                                            chatData.set(i, new String[]{oName, lastMsg, lastTime, "0", fOtherId});
                                            found = true; break;
                                        }
                                    }
                                    if (!found) chatData.add(new String[]{oName, lastMsg, lastTime, "0", fOtherId});
                                    try { buildChats(); } catch (Throwable ignored) { }
                                    try { buildStories(); } catch (Throwable ignored) { }
                                });
                        }
                    });
            }

            void buildStories() {
                LinearLayout bar = (LinearLayout) v("storiesBar");
                if (bar == null) return;
                bar.removeAllViews();
                for (int i = 0; i <= chatData.size() && i < 8; i++) {
                    final String nm = i == 0 ? "قصتي" : chatData.get(i - 1)[0];
                    LinearLayout c = new LinearLayout(act);
                    c.setOrientation(LinearLayout.VERTICAL);
                    c.setGravity(Gravity.CENTER_HORIZONTAL);
                    c.setPadding(dp(6), 0, dp(6), 0);
                    TextView r = new TextView(act);
                    r.setText(i == 0 ? "+" : (nm.length() > 0 ? nm.substring(0, 1) : "?"));
                    r.setGravity(Gravity.CENTER);
                    r.setTextSize(20);
                    r.setTypeface(null, android.graphics.Typeface.BOLD);
                    r.setTag("ring");
                    r.setLayoutParams(new LinearLayout.LayoutParams(dp(60), dp(60)));
                    TextView n = new TextView(act);
                    n.setText(nm.length() > 8 ? nm.substring(0, 8) : nm);
                    n.setTextSize(11);
                    n.setTypeface(null, android.graphics.Typeface.BOLD);
                    n.setTag("m");
                    n.setPadding(0, dp(4), 0, 0);
                    c.addView(r); c.addView(n);
                    c.setOnClickListener(x -> { if (nm.equals("قصتي")) showStoryComposer(); else showStoryViewer(nm); });
                    bar.addView(c);
                    style(c);
                }
            }

            void buildChats() {
                LinearLayout list = (LinearLayout) v("chatList");
                if (list == null) return;
                list.removeAllViews();
                int unreadChats = 0;
                for (String[] c : chatData) if (!c[3].equals("0")) unreadChats++;
                TextView fltUnread = tv("fltUnread");
                if (fltUnread != null) {
                    fltUnread.setText("غير المقروءة" + (unreadChats > 0 ? " (" + unreadChats + ")" : ""));
                }
                java.util.Set<String> hiddenChats = sp.getStringSet("hiddenChats", Collections.emptySet());
                for (String[] c : chatData) {
                    if (hiddenChats.contains(getChatId(myUid, c[4]))) continue;
                    if (unreadOnly && c[3].equals("0")) continue;
                    LinearLayout row = new LinearLayout(act);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(13), dp(11), dp(13), dp(11));
                    row.setTag("row");
                    row.setElevation(dp(2));
                    row.setMinimumHeight(dp(78));
                    final boolean hasUnread = !c[3].equals("0");
                    LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
                    rp.setMargins(dp(12), dp(5), dp(12), dp(5));
                    row.setLayoutParams(rp);
                    TextView av = new TextView(act);
                    av.setText(c[0].length() > 0 ? c[0].substring(0, 1) : "?");
                    av.setGravity(Gravity.CENTER);
                    av.setTextSize(20);
                    av.setTypeface(null, android.graphics.Typeface.BOLD);
                    av.setTextColor(0xFFFFFFFF);
                    av.setTag("avatar");
                    av.setLayoutParams(new LinearLayout.LayoutParams(dp(56), dp(56)));
                    LinearLayout mid = new LinearLayout(act);
                    mid.setOrientation(LinearLayout.VERTICAL);
                    LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, -2, 1f);
                    mp.setMargins(dp(12), 0, dp(12), 0);
                    mid.setLayoutParams(mp);
                    TextView nm = new TextView(act);
                    nm.setText(c[0]); nm.setTextSize(16.5f); nm.setTypeface(null, android.graphics.Typeface.BOLD); nm.setTag("t");
                    TextView ls = new TextView(act);
                    ls.setText(c[1]); ls.setTextSize(14); ls.setTypeface(null, hasUnread ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                    ls.setPadding(0, dp(3), 0, 0);
                    ls.setSingleLine(true); ls.setEllipsize(TextUtils.TruncateAt.END); ls.setTag(hasUnread ? "t" : "m");
                    mid.addView(nm); mid.addView(ls);
                    LinearLayout end = new LinearLayout(act);
                    end.setOrientation(LinearLayout.VERTICAL);
                    end.setGravity(Gravity.CENTER_HORIZONTAL);
                    TextView tm = new TextView(act);
                    tm.setText(c[2]); tm.setTextSize(12); tm.setTypeface(null, android.graphics.Typeface.BOLD); tm.setTag(hasUnread ? "g" : "m");
                    end.addView(tm);
                    if (!c[3].equals("0")) {
                        TextView b = new TextView(act);
                        b.setText(c[3]); b.setTextSize(11); b.setTypeface(null, android.graphics.Typeface.BOLD);
                        b.setGravity(Gravity.CENTER); b.setTag("badge");
                        b.setMinWidth(dp(22)); b.setMinHeight(dp(22));
                        b.setPadding(dp(7), 0, dp(7), 0);
                        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
                        bl.topMargin = dp(6);
                        end.addView(b, bl);
                    }
                    row.addView(av); row.addView(mid); row.addView(end);
                    final String fName = c[0], fUid = c[4];
                    row.setOnClickListener(x -> openChat(fName, fUid));
                    row.setOnLongClickListener(x -> { showChatActions(fName, fUid); return true; });
                    list.addView(row);
                    row.setAlpha(0f);
                    row.setTranslationY(dp(7));
                    row.animate().alpha(1f).translationY(0f).setStartDelay(Math.min(180, list.getChildCount() * 18L)).setDuration(190).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                    style(row);
                }
                if (list.getChildCount() == 0) {
                    TextView e = new TextView(act);
                    e.setText("لا توجد محادثات");
                    e.setGravity(Gravity.CENTER);
                    e.setTypeface(null, android.graphics.Typeface.BOLD);
                    e.setPadding(0, dp(60), 0, 0);
                    e.setTag("m");
                    list.addView(e);
                    style(e);
                }
            }

            void deleteChatMessages(String chatId, Runnable onDone) {
                db.collection("chats").document(chatId).collection("messages")
                    .get().addOnSuccessListener(qs -> {
                        com.google.firebase.firestore.WriteBatch batch = db.batch();
                        for (QueryDocumentSnapshot d : qs) batch.delete(d.getReference());
                        batch.commit().addOnCompleteListener(t -> { if (onDone != null) onDone.run(); });
                    }).addOnFailureListener(e -> { if (onDone != null) onDone.run(); });
            }

            void setFilter(boolean un) {
                unreadOnly = un;
                setOn(v("fltAll"), !un, "goldo");
                setOn(v("fltUnread"), un, "goldo");
                buildChats();
            }

            String getChatId(String u1, String u2) {
                return u1.compareTo(u2) < 0 ? u1 + "_" + u2 : u2 + "_" + u1;
            }

            void openChat(String name, String uid) {
                otherUid = uid; otherName = name; curChat = name;
                blocked = false; muted = false;
                TextView chatTitle = tv("chatTitle");
                if (chatTitle != null) chatTitle.setText(name);
                TextView chatAv = tv("chatAvatar");
                if (chatAv != null) chatAv.setText(name.length() > 0 ? name.substring(0, 1) : "?");
                TextView chatSub = tv("chatSub");
                if (chatSub != null) chatSub.setText("متصل");
                LinearLayout msgArea = (LinearLayout) v("messagesArea");
                if (msgArea != null) msgArea.removeAllViews();
                renderedMsgIds.clear();
                cancelReply();
                vis("chatSearchPanel", false);
                vis("recordingBar", false);

                if (messagesListener != null) messagesListener.remove();

                currentChatId = getChatId(myUid, uid);
                loadMessages(currentChatId);
                vis("chatView", true);
            }

            void loadMessages(String chatId) {
                messagesListener = db.collection("chats").document(chatId).collection("messages")
                    .orderBy("timestamp", Query.Direction.ASCENDING)
                    .addSnapshotListener((snapshots, e) -> {
                        if (e != null || snapshots == null) return;
                        for (DocumentChange dc : snapshots.getDocumentChanges()) {
                            if (dc.getType() == DocumentChange.Type.ADDED) {
                                DocumentSnapshot doc = dc.getDocument();
                                if (!doc.exists()) continue;
                                String id = doc.getId();
                                if (renderedMsgIds.contains(id)) continue;
                                renderedMsgIds.add(id);

                                String type = doc.getString("type");
                                if (type == null) type = "text";
                                String text = doc.getString("text");
                                String url = doc.getString("data");
                                if (url == null) url = doc.getString("url");
                                String sender = doc.getString("sender");
                                Long time = doc.getLong("timestamp");
                                Long dur = doc.getLong("duration");
                                boolean mine = myUid.equals(sender);
                                String timeStr = new SimpleDateFormat("h:mm", Locale.getDefault())
                                        .format(new Date(time != null ? time : System.currentTimeMillis()));

                                try {
                                    if (type.equals("image") && url != null) addImageMsg(url, mine, timeStr);
                                    else if (type.equals("audio") && url != null) addAudioMsg(url, dur != null ? dur : 0, mine, timeStr);
                                    else if (text != null) addMsg(text, mine, timeStr);
                                    scrollDown();
                                } catch (Throwable ignored) { }
                            }
                        }
                    });
            }

            void closeChat() {
                hideKb();
                vis("chatView", false);
                if (messagesListener != null) { messagesListener.remove(); messagesListener = null; }
                currentChatId = ""; otherUid = "";
                renderedMsgIds.clear();
                if (mediaPlayer != null) { try { mediaPlayer.release(); } catch (Exception e) {} mediaPlayer = null; }
                try { buildChats(); } catch (Throwable ignored) { }
                try { buildStories(); } catch (Throwable ignored) { }
            }

            TextView addMsg(String text, final boolean mine, String time) {
                final TextView b = new TextView(act);
                b.setTag(mine ? "bubMine" : "bubOther");
                b.setTextSize(fontSp);
                b.setTypeface(null, android.graphics.Typeface.BOLD);
                b.setPadding(dp(12), dp(8), dp(12), dp(6));
                b.setMaxWidth((int) (act.getResources().getDisplayMetrics().widthPixels * 0.78f));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.gravity = mine ? Gravity.END : Gravity.START;
                lp.setMargins(0, dp(3), 0, dp(3));
                b.setLayoutParams(lp);
                setMsg(b, text, mine, time);
                b.setTag(mine ? "mine" : "other");
                b.setOnLongClickListener(x -> { msgMenu(b, mine); return true; });
                LinearLayout msgArea = (LinearLayout) v("messagesArea");
                if (msgArea != null) msgArea.addView(b);
                style(b);
                animateMessageIn(b);
                return b;
            }

            void setMsg(TextView b, String text, boolean mine, String time) {
                b.setContentDescription(text);
                SpannableStringBuilder sb = new SpannableStringBuilder(text);
                int s = sb.length();
                sb.append("\n" + time + (mine ? "  ✓✓" : ""));
                sb.setSpan(new RelativeSizeSpan(0.7f), s, sb.length(), 0);
                sb.setSpan(new ForegroundColorSpan(0xFF8696A7), s, sb.length(), 0);
                b.setText(sb);
            }

            void addImageMsg(String url, boolean mine, String time) {
                FrameLayout frame = new FrameLayout(act);
                int size = Math.min(dp(220), (int) (act.getResources().getDisplayMetrics().widthPixels * 0.68f));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
                lp.gravity = mine ? Gravity.END : Gravity.START;
                lp.setMargins(0, dp(3), 0, dp(3));
                frame.setLayoutParams(lp);
                frame.setClipToOutline(true);
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(dark ? 0xFF202C33 : 0xFFF0F0F0);
                bg.setCornerRadius(dp(16));
                frame.setBackground(bg);
                frame.setOutlineProvider(new ViewOutlineProvider() {
                    @Override public void getOutline(View v, Outline o) {
                        o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(16));
                    }
                });
                ImageView img = new ImageView(act);
                img.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                frame.addView(img);
                frame.setTag(mine ? "mine" : "other");
                loadImageAsync(img, url);
                TextView t = new TextView(act);
                t.setText(time); t.setTextSize(10); t.setTextColor(0xFFFFFFFF);
                t.setTypeface(null, android.graphics.Typeface.BOLD);
                t.setPadding(dp(6), dp(2), dp(6), dp(2));
                GradientDrawable tg = new GradientDrawable();
                tg.setColor(0x99000000); tg.setCornerRadius(dp(8));
                t.setBackground(tg);
                FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-2, -2);
                tlp.gravity = Gravity.BOTTOM | Gravity.END;
                tlp.setMargins(0, 0, dp(6), dp(6));
                t.setLayoutParams(tlp);
                frame.addView(t);
                frame.setOnClickListener(v -> {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    try { act.startActivity(i); } catch (Exception e) { toast("لا يمكن فتح الصورة"); }
                });
                LinearLayout msgArea = (LinearLayout) v("messagesArea");
                if (msgArea != null) msgArea.addView(frame);
                animateMessageIn(frame);
            }

            void addAudioMsg(String url, long durationMs, boolean mine, String time) {
                LinearLayout box = new LinearLayout(act);
                box.setOrientation(LinearLayout.HORIZONTAL);
                box.setGravity(Gravity.CENTER_VERTICAL);
                box.setPadding(dp(10), dp(8), dp(14), dp(8));
                box.setTag(mine ? "bubMine" : "bubOther");
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.gravity = mine ? Gravity.END : Gravity.START;
                lp.setMargins(0, dp(3), 0, dp(3));
                box.setLayoutParams(lp);
                box.setMinimumWidth(dp(180));

                TextView play = new TextView(act);
                play.setText("▶");
                play.setTextSize(18);
                play.setGravity(Gravity.CENTER);
                play.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));

                LinearLayout center = new LinearLayout(act);
                center.setOrientation(LinearLayout.VERTICAL);
                center.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
                center.setPadding(dp(10), 0, dp(10), 0);

                TextView durTv = new TextView(act);
                durTv.setText(formatDuration(durationMs));
                durTv.setTextSize(fontSp);
                durTv.setTypeface(null, android.graphics.Typeface.BOLD);
                durTv.setTextColor(mine ? (dark ? 0xFFFFFFFF : 0xFF17212B) : cText());

                TextView timeTv = new TextView(act);
                timeTv.setText(time); timeTv.setTextSize(10); timeTv.setTypeface(null, android.graphics.Typeface.BOLD);
                timeTv.setTextColor(0xFF8696A7);

                center.addView(durTv); center.addView(timeTv);

                TextView wave = new TextView(act);
                wave.setText("▁▃▂▅▇▆▃▂");
                wave.setTextSize(14);
                wave.setTextColor(mine ? (dark ? 0xFFB0C4DE : 0xFF6B8E23) : cGold());
                box.addView(play); box.addView(center); box.addView(wave);
                box.setTag(mine ? "mine" : "other");

                play.setOnClickListener(v -> playAudio(url, play));
                box.setOnClickListener(v -> playAudio(url, play));

                LinearLayout msgArea = (LinearLayout) v("messagesArea");
                if (msgArea != null) msgArea.addView(box);
                style(box);
                animateMessageIn(box);
            }

            String formatDuration(long ms) {
                long s = ms / 1000;
                return String.format(Locale.getDefault(), "%d:%02d", s / 60, s % 60);
            }

            void playAudio(String url, TextView btn) {
                try {
                    if (mediaPlayer != null) { mediaPlayer.release(); mediaPlayer = null; }
                    mediaPlayer = new MediaPlayer();
                    mediaPlayer.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC);
                    if (url.startsWith("http")) {
                        mediaPlayer.setDataSource(url);
                    } else {
                        File pf = new File(act.getCacheDir(), "play_" + url.length() + "_" + Math.abs(url.hashCode()) + ".m4a");
                        if (!pf.exists()) {
                            byte[] b = android.util.Base64.decode(url, android.util.Base64.DEFAULT);
                            java.io.FileOutputStream fo = new java.io.FileOutputStream(pf);
                            fo.write(b); fo.close();
                        }
                        mediaPlayer.setDataSource(pf.getAbsolutePath());
                    }
                    mediaPlayer.prepareAsync();
                    mediaPlayer.setOnPreparedListener(mp -> { mp.start(); btn.setText("⏸"); });
                    mediaPlayer.setOnCompletionListener(mp -> { btn.setText("▶"); });
                } catch (Exception e) { toast("فشل تشغيل الصوت"); }
            }

            void loadImageAsync(final ImageView img, final String url) {
                new Thread(() -> {
                    try {
                        Bitmap bmp;
                        BitmapFactory.Options opts = new BitmapFactory.Options();
                        opts.inSampleSize = 2;
                        if (url.startsWith("http")) {
                            java.net.URL u = new java.net.URL(url);
                            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) u.openConnection();
                            conn.setDoInput(true); conn.connect();
                            java.io.InputStream is = conn.getInputStream();
                            bmp = BitmapFactory.decodeStream(is, null, opts);
                            is.close();
                        } else {
                            byte[] b = android.util.Base64.decode(url, android.util.Base64.DEFAULT);
                            bmp = BitmapFactory.decodeByteArray(b, 0, b.length, opts);
                        }
                        final Bitmap fb = bmp;
                        if (fb != null) act.runOnUiThread(() -> img.setImageBitmap(fb));
                    } catch (Exception e) { }
                }).start();
            }

            void msgMenu(final TextView b, final boolean mine) {
                final Dialog d = new Dialog(act);
                View content = act.getLayoutInflater().inflate(R.layout.native_message_actions, null, false);
                styleSheetDialog(d, content, false);
                View editAction = content.findViewById(R.id.msgActionEdit);
                if (editAction != null && !mine) editAction.setVisibility(View.GONE);
                String raw = String.valueOf(b.getContentDescription());
                content.findViewById(R.id.msgActionReply).setOnClickListener(x -> {
                    replyText = raw.length() > 40 ? raw.substring(0, 40) + "…" : raw;
                    setLabel("replyLabel", "الرد على الرسالة", icon("ic_reply", 16));
                    TextView rp = tv("replyPreviewText"); if (rp != null) rp.setText(replyText);
                    vis("replyPreviewBar", true); EditText mi = et("messageInput"); if (mi != null) mi.requestFocus(); d.dismiss();
                });
                content.findViewById(R.id.msgActionCopy).setOnClickListener(x -> {
                    ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("msg", raw)); toast("تم نسخ الرسالة"); d.dismiss();
                });
                if (editAction != null) editAction.setOnClickListener(x -> {
                    editing = b; replyText = null; setLabel("replyLabel", "تعديل الرسالة", icon("ic_edit", 16));
                    TextView rp = tv("replyPreviewText"); if (rp != null) rp.setText(raw);
                    vis("replyPreviewBar", true); EditText mi = et("messageInput"); if (mi != null) { mi.setText(raw); mi.setSelection(raw.length()); mi.requestFocus(); } d.dismiss();
                });
                content.findViewById(R.id.msgActionDelete).setOnClickListener(x -> {
                    d.dismiss();
                    showMessageDeleteChoices(b, mine);
                });
                content.findViewById(R.id.msgActionCancel).setOnClickListener(x -> d.dismiss());
                int[] reactions = {R.id.msgReactLaugh,R.id.msgReactHeart,R.id.msgReactLike,R.id.msgReactSad,R.id.msgReactSpark};
                String[] emojis = {"😂","❤️","👍","😭","✨"};
                for (int i=0;i<reactions.length;i++){final String emoji=emojis[i];content.findViewById(reactions[i]).setOnClickListener(x->{toast("تفاعل " + emoji);d.dismiss();});}
            }

            void showMessageDeleteChoices(TextView messageView, boolean mine) {
                final Dialog dialog = new Dialog(act);
                View content = act.getLayoutInflater().inflate(R.layout.native_message_delete_dialog, null, false);
                dialog.setContentView(content);
                Window window = dialog.getWindow();
                if (window != null) {
                    window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                    window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                    WindowManager.LayoutParams lp = window.getAttributes(); lp.dimAmount = .68f;
                    lp.width = WindowManager.LayoutParams.MATCH_PARENT; lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
                    lp.gravity = Gravity.CENTER; window.setAttributes(lp);
                }
                TextView all = (TextView) content.findViewById(R.id.messageDeleteForAll);
                if (!mine) all.setVisibility(View.GONE);
                content.findViewById(R.id.messageDeleteCancel).setOnClickListener(vw -> dialog.dismiss());
                content.findViewById(R.id.messageDeleteForMe).setOnClickListener(vw -> {
                    dialog.dismiss(); LinearLayout area = (LinearLayout) v("messagesArea");
                    if (area != null) area.removeView(messageView);
                    toast("تم إخفاء الرسالة من العرض");
                });
                all.setOnClickListener(vw -> {
                    dialog.dismiss();
                    // This app's current message renderer does not retain a Firestore document id on the view.
                    // Do not claim server deletion; remove only the visible row until ids are wired end-to-end.
                    LinearLayout area = (LinearLayout) v("messagesArea");
                    if (area != null) area.removeView(messageView);
                    toast("تمت إزالة الرسالة من العرض؛ الحذف لدى الجميع يحتاج ربط معرّف الرسالة");
                });
                dialog.show();
                if (window != null) window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
                content.startAnimation(android.view.animation.AnimationUtils.loadAnimation(act, R.anim.bb_sheet_enter));
            }

            void cancelReply() { replyText = null; editing = null; vis("replyPreviewBar", false); }
            void scrollDown() {
                View ms = v("messagesScroll");
                if (ms != null) {
                    ms.post(() -> ((ScrollView) ms).fullScroll(View.FOCUS_DOWN));
                }
            }

            void send() {
                String t = txt("messageInput");
                if (t.length() == 0) return;
                if (blocked) { toast("هذا المستخدم محظور"); return; }
                if (currentChatId.isEmpty() || otherUid.isEmpty()) return;

                String fullText = replyText != null ? "↩ " + replyText + "\n" + t : t;

                if (editing != null) {
                    setMsg(editing, t, true, now() + " (معدّلة)");
                    EditText mi = et("messageInput");
                    if (mi != null) mi.setText("");
                    cancelReply();
                    return;
                }

                Map<String, Object> msg = new HashMap<>();
                msg.put("type", "text");
                msg.put("text", fullText);
                msg.put("sender", myUid);
                msg.put("timestamp", System.currentTimeMillis());
                db.collection("chats").document(currentChatId).collection("messages")
                    .add(msg);

                updateChatMeta(fullText);
                EditText mi = et("messageInput");
                if (mi != null) mi.setText("");
                cancelReply();
            }

            void updateChatMeta(String preview) {
                Map<String, Object> chatDoc = new HashMap<>();
                chatDoc.put("participants", Arrays.asList(myUid, otherUid));
                chatDoc.put("lastMessage", preview);
                chatDoc.put("lastMessageAt", System.currentTimeMillis());
                chatDoc.put("lastSender", myUid);
                db.collection("chats").document(currentChatId).set(chatDoc);
            }

            void sendMedia(String type, String base64Data, long duration) {
                if (currentChatId.isEmpty() || otherUid.isEmpty()) return;
                Map<String, Object> msg = new HashMap<>();
                msg.put("type", type);
                msg.put("data", base64Data);
                msg.put("duration", duration);
                msg.put("sender", myUid);
                msg.put("timestamp", System.currentTimeMillis());
                db.collection("chats").document(currentChatId).collection("messages")
                    .add(msg);

                String preview = type.equals("image") ? "📷 صورة"
                        : type.equals("audio") ? "🎤 رسالة صوتية" : "";
                updateChatMeta(preview);
            }

            void pickImage() {
                pendingImageCallback = uri -> uploadImage(uri);
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.setType("image/*");
                try {
                    act.startActivityForResult(Intent.createChooser(i, "اختر صورة"), REQ_PICK_IMAGE);
                } catch (Exception e) { toast("لا توجد تطبيقات لعرض الصور"); }
            }

            void uploadImage(Uri uri) {
                if (currentChatId.isEmpty()) return;
                toast("جاري معالجة الصورة...");
                new Thread(() -> {
                    try {
                        final String b64 = encodeImage(uri, 1280, 640 * 1024, false);
                        act.runOnUiThread(() -> sendMedia("image", b64, 0));
                    } catch (Exception e) {
                        act.runOnUiThread(() -> toast("فشل معالجة الصورة: " + e.getMessage()));
                    }
                }).start();
            }

            String encodeImage(Uri uri, int maxSide, int maxBytes, boolean square) throws Exception {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                java.io.InputStream is0 = act.getContentResolver().openInputStream(uri);
                BitmapFactory.decodeStream(is0, null, o);
                if (is0 != null) is0.close();
                int sample = 1;
                while (Math.max(o.outWidth, o.outHeight) / sample > maxSide * 2) sample *= 2;
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = sample;
                java.io.InputStream is1 = act.getContentResolver().openInputStream(uri);
                Bitmap bmp = BitmapFactory.decodeStream(is1, null, o2);
                if (is1 != null) is1.close();
                if (bmp == null) throw new Exception("صورة غير صالحة");

                if (Build.VERSION.SDK_INT >= 24) {
                    try {
                        java.io.InputStream is2 = act.getContentResolver().openInputStream(uri);
                        int ori = new android.media.ExifInterface(is2).getAttributeInt(
                                android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL);
                        if (is2 != null) is2.close();
                        float deg = ori == android.media.ExifInterface.ORIENTATION_ROTATE_90 ? 90
                                : ori == android.media.ExifInterface.ORIENTATION_ROTATE_180 ? 180
                                : ori == android.media.ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
                        if (deg != 0) {
                            android.graphics.Matrix m = new android.graphics.Matrix();
                            m.postRotate(deg);
                            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                        }
                    } catch (Exception ignore) { }
                }
                if (square) {
                    int side = Math.min(bmp.getWidth(), bmp.getHeight());
                    bmp = Bitmap.createBitmap(bmp, (bmp.getWidth() - side) / 2, (bmp.getHeight() - side) / 2, side, side);
                }
                float sc = Math.min(1f, (float) maxSide / Math.max(bmp.getWidth(), bmp.getHeight()));
                if (sc < 1f) bmp = Bitmap.createScaledBitmap(bmp, Math.round(bmp.getWidth() * sc), Math.round(bmp.getHeight() * sc), true);

                for (int round = 0; round < 6; round++) {
                    for (int q = 85; q >= 30; q -= 10) {
                        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                        bmp.compress(Bitmap.CompressFormat.JPEG, q, bos);
                        byte[] out = bos.toByteArray();
                        if (out.length <= maxBytes) return android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP);
                    }
                    bmp = Bitmap.createScaledBitmap(bmp, Math.max(1, Math.round(bmp.getWidth() * 0.75f)), Math.max(1, Math.round(bmp.getHeight() * 0.75f)), true);
                }
                throw new Exception("الصورة كبيرة جداً");
            }

            void pickProfilePhoto() {
                pendingImageCallback = uri -> saveProfilePhoto(uri);
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.setType("image/*");
                try {
                    act.startActivityForResult(Intent.createChooser(i, "اختر صورة"), REQ_PICK_IMAGE);
                } catch (Exception e) { toast("لا توجد تطبيقات لعرض الصور"); }
            }

            void saveProfilePhoto(Uri uri) {
                toast("جاري حفظ الصورة...");
                new Thread(() -> {
                    try {
                        final String b64 = encodeImage(uri, 320, 60 * 1024, true);
                        act.runOnUiThread(() -> db.collection("users").document(myUid).update("photo", b64)
                            .addOnSuccessListener(a -> { userPhoto = b64; try { fillProfile(); } catch (Throwable ignored) {} toast("تم تحديث الصورة"); })
                            .addOnFailureListener(er -> toast("فشل: " + er.getMessage())));
                    } catch (Exception e) {
                        act.runOnUiThread(() -> toast("فشل معالجة الصورة: " + e.getMessage()));
                    }
                }).start();
            }

            void applyPhotoTo(TextView t) {
                if (t == null || photoBmp == null) return;
                androidx.core.graphics.drawable.RoundedBitmapDrawable d =
                        androidx.core.graphics.drawable.RoundedBitmapDrawableFactory.create(act.getResources(), photoBmp);
                d.setCircular(true);
                t.setBackground(d);
                t.setText("");
            }

            void applyProfilePhoto() {
                if (userPhoto == null || userPhoto.isEmpty()) { photoBmp = null; shownPhoto = ""; return; }
                if (!userPhoto.equals(shownPhoto)) {
                    try {
                        byte[] b = android.util.Base64.decode(userPhoto, android.util.Base64.DEFAULT);
                        photoBmp = BitmapFactory.decodeByteArray(b, 0, b.length);
                        shownPhoto = userPhoto;
                    } catch (Exception e) { photoBmp = null; return; }
                }
                applyPhotoTo(tv("pAvatarText"));
                applyPhotoTo(tv("sxAvatar"));
            }

            void toggleRecording() {
                if (!isRecording) startRecord();
                else stopRecordAndSend();
            }

            void startRecord() {
                if (act.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    pendingMicCallback = this::startRecord;
                    act.requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_MIC_PERM);
                    return;
                }
                try {
                    File dir = new File(act.getFilesDir(), "voice");
                    if (!dir.exists()) dir.mkdirs();
                    audioFile = new File(dir, "rec_" + System.currentTimeMillis() + ".m4a");

                    if (Build.VERSION.SDK_INT >= 31) recorder = new MediaRecorder(act);
                    else recorder = new MediaRecorder();
                    recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                    recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                    recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                    recorder.setAudioEncodingBitRate(32000);
                    recorder.setAudioChannels(1);
                    recorder.setAudioSamplingRate(44100);
                    recorder.setOutputFile(audioFile.getAbsolutePath());
                    recorder.prepare();
                    recorder.start();
                    isRecording = true;
                    recordStart = System.currentTimeMillis();

                    vis("recordingBar", true);
                    TextView rt = tv("recTimer");
                    if (rt != null) rt.setText("0:00");
                    recTick = new Runnable() {
                        @Override public void run() {
                            if (!isRecording) return;
                            long s = (System.currentTimeMillis() - recordStart) / 1000;
                            TextView rt = tv("recTimer");
                            if (rt != null) rt.setText(String.format(Locale.getDefault(), "%d:%02d", s / 60, s % 60));
                            if (s >= 150) { stopRecordAndSend(); return; }
                            recHandler.postDelayed(this, 500);
                        }
                    };
                    recHandler.post(recTick);
                    TextView mic = tv("btnMic");
                    if (mic != null) { mic.setTextColor(cRed()); tintIcons(mic); }
                } catch (Exception e) {
                    toast("فشل بدء التسجيل: " + e.getMessage());
                    recorder = null; isRecording = false;
                }
            }

            void stopRecordAndSend() {
                if (recorder == null) return;
                long dur = System.currentTimeMillis() - recordStart;
                try { recorder.stop(); } catch (Exception e) { }
                try { recorder.release(); } catch (Exception e) { }
                recorder = null;
                isRecording = false;
                if (recTick != null) recHandler.removeCallbacks(recTick);
                vis("recordingBar", false);
                TextView mic = tv("btnMic");
                if (mic != null) { mic.setTextColor(cMuted()); tintIcons(mic); }

                if (audioFile == null || !audioFile.exists()) return;
                if (dur < 800) { audioFile.delete(); toast("التسجيل قصير جداً"); return; }
                uploadAudio(audioFile, dur);
            }

            void cancelRecord() {
                if (recorder == null) return;
                try { recorder.stop(); } catch (Exception e) { }
                try { recorder.release(); } catch (Exception e) { }
                recorder = null;
                isRecording = false;
                if (recTick != null) recHandler.removeCallbacks(recTick);
                vis("recordingBar", false);
                TextView mic = tv("btnMic");
                if (mic != null) { mic.setTextColor(cMuted()); tintIcons(mic); }
                if (audioFile != null) audioFile.delete();
                toast("تم إلغاء التسجيل");
            }

            void uploadAudio(File f, long dur) {
                if (currentChatId.isEmpty()) return;
                toast("جاري إرسال الصوت...");
                new Thread(() -> {
                    try {
                        java.io.FileInputStream fi = new java.io.FileInputStream(f);
                        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                        byte[] buf = new byte[8192]; int n;
                        while ((n = fi.read(buf)) > 0) bo.write(buf, 0, n);
                        fi.close();
                        byte[] bytes = bo.toByteArray();
                        if (bytes.length > 650 * 1024) {
                            f.delete();
                            act.runOnUiThread(() -> toast("التسجيل طويل جداً"));
                            return;
                        }
                        final String b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
                        act.runOnUiThread(() -> { sendMedia("audio", b64, dur); f.delete(); });
                    } catch (Exception e) {
                        act.runOnUiThread(() -> toast("فشل: " + e.getMessage()));
                    }
                }).start();
            }

            void chatMenu() {
                View anchor = v("chatMore");
                if (anchor == null) return;
                final Dialog d = new Dialog(act);
                LinearLayout sheet = new LinearLayout(act);
                sheet.setOrientation(LinearLayout.VERTICAL);
                sheet.setPadding(dp(18), dp(16), dp(18), dp(12));
                sheet.setBackground(shape(cSf(), 22, cBorder()));
                TextView heading = new TextView(act); heading.setText("خيارات المحادثة"); heading.setTextColor(cGold());
                heading.setTextSize(17); heading.setTypeface(null, android.graphics.Typeface.BOLD); heading.setPadding(dp(8), dp(4), dp(8), dp(12));
                sheet.addView(heading, new LinearLayout.LayoutParams(-1, -2));
                String[] labels = {muted ? "إلغاء كتم المحادثة" : "كتم المحادثة", "البحث عن رسالة", "مسح المحادثة"};
                int[] colors = {cText(), cText(), cRed()};
                for (int i = 0; i < labels.length; i++) {
                    final int action = i;
                    TextView item = new TextView(act); item.setText(labels[i]); item.setTextColor(colors[i]); item.setTextSize(15);
                    item.setGravity(Gravity.CENTER_VERTICAL); item.setPadding(dp(14), 0, dp(14), 0); item.setMinHeight(dp(50));
                    item.setBackground(shape(dark ? 0xFF202C3A : 0xFFF3F5F8, 13, 0));
                    LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, dp(50)); ip.bottomMargin = dp(8); sheet.addView(item, ip);
                    item.setOnClickListener(x -> {
                        d.dismiss();
                        if (action == 0) { muted = !muted; toast(muted ? "تم كتم المحادثة" : "تم إلغاء الكتم"); }
                        else if (action == 1) { vis("chatSearchPanel", true); EditText input = et("chatMessageSearchInput"); if (input != null) { input.requestFocus(); ((InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT); } }
                        else showClearConversationConfirm();
                    });
                }
                TextView cancel = new TextView(act); cancel.setText("إلغاء"); cancel.setTextColor(cGold()); cancel.setTextSize(14); cancel.setGravity(Gravity.CENTER); cancel.setMinHeight(dp(44));
                sheet.addView(cancel, new LinearLayout.LayoutParams(-1, dp(44))); cancel.setOnClickListener(x -> d.dismiss());
                styleSheetDialog(d, sheet, false);
            }


            void setChatSearchFilter() {
                String[] ids={"csFilterAll","csFilterMine","csFilterOther"};
                String[] values={"all","mine","other"};
                for(int i=0;i<ids.length;i++){TextView t=tv(ids[i]);if(t!=null){boolean active=chatSearchFilter.equals(values[i]);t.setSelected(active);t.setTextColor(active?0xFF17212B:0xFFE2E8F0);}}
            }

            void searchInChat(String q) {
                LinearLayout a = (LinearLayout) v("messagesArea");
                if (a == null) return;
                q = q.trim().toLowerCase();
                chatSearchHits.clear();
                for (int i = 0; i < a.getChildCount(); i++) {
                    View c = a.getChildAt(i);
                    String tag = String.valueOf(c.getTag());
                    boolean sender = chatSearchFilter.equals("all") || (chatSearchFilter.equals("mine") && tag.equals("mine")) || (chatSearchFilter.equals("other") && tag.equals("other"));
                    boolean hit = sender && (q.length() == 0 || String.valueOf(c.getContentDescription()).toLowerCase().contains(q));
                    c.setAlpha(hit ? 1f : 0.24f);
                    if (hit && q.length() > 0) chatSearchHits.add(c);
                }
                chatSearchHitIndex = 0;
                TextView csc = tv("csCount");
                if (csc != null) csc.setText(q.length() == 0 ? "" : (chatSearchHits.size() + " نتيجة"));
                vis("csNav", !chatSearchHits.isEmpty());
                if (!chatSearchHits.isEmpty()) navigateChatSearch(0);
            }

            void navigateChatSearch(int direction) {
                if (chatSearchHits.isEmpty()) return;
                chatSearchHitIndex = (chatSearchHitIndex + direction + chatSearchHits.size()) % chatSearchHits.size();
                View target = chatSearchHits.get(chatSearchHitIndex);
                View ms = v("messagesScroll");
                if (target != null && ms instanceof ScrollView) ((ScrollView) ms).smoothScrollTo(0, target.getTop());
                TextView csc = tv("csCount");
                if (csc != null) csc.setText((chatSearchHitIndex + 1) + " / " + chatSearchHits.size());
            }

            void searchUsers(String q) {
                String cleanQ = q.replace("@", "").trim().toLowerCase();
                LinearLayout res = (LinearLayout) v("searchResults");
                if (res == null) return;
                res.removeAllViews();
                if (cleanQ.length() < 3) {
                    TextView sh = tv("searchHint");
                    if (sh != null) sh.setText("اكتب 3 أحرف على الأقل");
                    return;
                }
                db.collection("users").orderBy("username").startAt(cleanQ).endAt(cleanQ + "\uf8ff")
                    .get().addOnCompleteListener(task -> {
                        res.removeAllViews();
                        int n = 0;
                        if (task.isSuccessful() && task.getResult() != null) {
                            for (QueryDocumentSnapshot d : task.getResult()) {
                                String uid = d.getId();
                                if (uid.equals(myUid)) continue;
                                String name = d.getString("name");
                                String username = d.getString("username");
                                if (name == null) name = "?";
                                if (username == null) username = "";
                                n++;
                                final String fName = name, fUser = username, fUid = uid;
                                LinearLayout row = new LinearLayout(act);
                                row.setGravity(Gravity.CENTER_VERTICAL);
                                row.setPadding(dp(14), dp(10), dp(14), dp(10));
                                TextView av = new TextView(act);
                                av.setText(name.length() > 0 ? name.substring(0, 1) : "?");
                                av.setGravity(Gravity.CENTER);
                                av.setTextSize(18);
                                av.setTypeface(null, android.graphics.Typeface.BOLD);
                                av.setTextColor(0xFF17212B);
                                av.setTag("circ");
                                av.setLayoutParams(new LinearLayout.LayoutParams(dp(46), dp(46)));
                                LinearLayout col = new LinearLayout(act);
                                col.setOrientation(LinearLayout.VERTICAL);
                                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
                                cp.setMargins(dp(12), 0, dp(12), 0);
                                col.setLayoutParams(cp);
                                TextView a = new TextView(act);
                                a.setText(name); a.setTextSize(15); a.setTypeface(null, android.graphics.Typeface.BOLD); a.setTag("t");
                                TextView b = new TextView(act);
                                b.setText("@" + username); b.setTextSize(12); b.setTypeface(null, android.graphics.Typeface.BOLD); b.setTag("m");
                                col.addView(a); col.addView(b);
                                row.addView(av); row.addView(col);
                                row.setOnClickListener(x -> openOther(fName, fUser, fUid));
                                res.addView(row);
                                style(row);
                            }
                        }
                        TextView sh = tv("searchHint");
                        if (sh != null) sh.setText(n == 0 ? "لا توجد نتائج" : n + " نتيجة");
                    });
            }

            void buildThemeStrip() {
                LinearLayout s = (LinearLayout) v("themeStrip");
                if (s == null) return;
                s.removeAllViews();
                for (int i = 0; i < themeNames.length; i++) {
                    final int idx = i;
                    TextView c = new TextView(act);
                    c.setText(themeNames[i]); c.setTextSize(13); c.setGravity(Gravity.CENTER);
                    c.setTypeface(null, android.graphics.Typeface.BOLD);
                    c.setPadding(dp(16), dp(8), dp(16), dp(8));
                    c.setTag(i == chatBgIdx ? "gold" : "goldo");
                    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
                    p.setMargins(0, 0, dp(8), 0);
                    c.setLayoutParams(p);
                    c.setOnClickListener(x -> {
                        chatBgIdx = idx;
                        sp.edit().putInt("chatBg", idx).apply();
                        applyTheme();
                    });
                    s.addView(c);
                    style(c);
                }
            }

            void applyBubbles() {
                TextView po = tv("pvOther");
                if (po != null) po.setTextSize(fontSp);
                TextView pm2 = tv("pvMine");
                if (pm2 != null) pm2.setTextSize(fontSp);
                LinearLayout a = (LinearLayout) v("messagesArea");
                if (a != null) {
                    for (int i = 0; i < a.getChildCount(); i++) {
                        View c = a.getChildAt(i);
                        if (c instanceof TextView) ((TextView) c).setTextSize(fontSp);
                    }
                }
                style(v("pvBox"));
                style(a);
            }

            void setSeg(String id, boolean hidden) {
                setOn(v(id + "_show"), !hidden, "goldo");
                setOn(v(id + "_hide"), hidden, "goldo");
            }
            void setOnOff(String id, boolean on) {
                setOn(v(id + "OnBtn"), on, "goldo");
                setOn(v(id + "OffBtn"), !on, "goldo");
            }
            void confirm(String title, String msg, final Runnable ok) {
                new AlertDialog.Builder(act).setTitle(title).setMessage(msg)
                    .setPositiveButton("تأكيد", (d, w) -> ok.run())
                    .setNegativeButton("إلغاء", null).show();
            }
            void logout() {
                mAuth.signOut();
                sp.edit().putBoolean("logged", false).apply();
                startActivity(new Intent(act, Main2Activity.class));
                act.finish();
            }
            void input(String title, String hint, final String key, final String okMsg) {
                final EditText e = new EditText(act);
                e.setHint(hint);
                new AlertDialog.Builder(act).setTitle(title).setView(e)
                    .setPositiveButton("حفظ", (d, w) -> {
                        String s = e.getText().toString().trim();
                        if (s.length() > 0) {
                            Map<String, Object> upd = new HashMap<>();
                            upd.put(key, s);
                            db.collection("users").document(myUid).update(upd)
                                .addOnSuccessListener(a -> { loadMyProfile(); toast(okMsg); })
                                .addOnFailureListener(er -> toast("فشل: " + er.getMessage()));
                        }
                    }).setNegativeButton("إلغاء", null).show();
            }

            void click(String id) {
                if (id.equals("hdrStories")) showStoryComposer();
                else if (id.equals("mainSearchBtn")) {
                    EditText si = et("searchInput");
                    if (si != null) { si.setText(""); show("searchView"); si.requestFocus(); }
                }
                else if (id.equals("searchBack") || id.equals("navChats")) show("homeView");
                else if (id.equals("navSettings")) show("settingsView");
                else if (id.equals("navProfile") || id.equals("sxHero")) { loadMyProfile(); show("profileView"); }
                else if (id.equals("fltAll")) setFilter(false);
                else if (id.equals("fltUnread")) setFilter(true);
                else if (id.equals("newGroupFab")) {
                    final EditText e = new EditText(act);
                    e.setHint("اسم المجموعة");
                    new AlertDialog.Builder(act).setTitle("مجموعة جديدة").setView(e)
                        .setPositiveButton("إنشاء", (d, w) -> toast("إنشاء المجموعات قيد التطوير"))
                        .setNegativeButton("إلغاء", null).show();
                }
                else if (id.equals("pEdit") || id.equals("pBioRow")) openEdit();
                else if (id.equals("pPhoto")) pickProfilePhoto();
                else if (id.equals("pCover")) showCoverPicker(false);
                else if (id.equals("pUserRow")) {
                    ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                    TextView pu = tv("pHeaderUser");
                    if (pu != null) cm.setPrimaryClip(ClipData.newPlainText("u", pu.getText().toString()));
                    toast("تم نسخ اسم المستخدم");
                }
                else if (id.equals("editBack") || id.equals("btnCancelEdit")) show("profileView");
                else if (id.equals("btnSaveProfile")) saveProfile();
                else if (id.equals("otherBack")) show(prevPage);
                else if (id.equals("pTabStoriesBtn")) {
                    tabStories = true;
                    vis("pTabStories", true);
                    setTab(v("pTabStoriesBtn"), true);
                }
                else if (id.equals("otherMsgBtn")) openChat(otherName, otherUid);
                else if (id.equals("otherBlockBtn")) {
                    blocked = !blocked;
                    setLabel("otherBlockBtn", blocked ? "إلغاء الحظر" : "حظر", icon(blocked ? "ic_check" : "ic_block", 16));
                    toast(blocked ? "تم حظر " + otherName : "تم إلغاء الحظر");
                }
                else if (id.equals("otherMoreBtn")) {
                    View om = v("otherMoreBtn");
                    if (om == null) return;
                    PopupMenu pm = new PopupMenu(act, om);
                    pm.getMenu().add(0, 1, 0, "نسخ اسم المستخدم");
                    pm.getMenu().add(0, 2, 1, "إبلاغ");
                    pm.setOnMenuItemClickListener(it -> {
                        if (it.getItemId() == 1) {
                            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(ClipData.newPlainText("u", "@" + otherUser));
                            toast("تم النسخ");
                        } else toast("تم إرسال البلاغ");
                        return true;
                    });
                    pm.show();
                }
                else if (id.equals("settingsThemeToggle")) {
                    dark = !dark;
                    sp.edit().putBoolean("dark", dark).apply();
                    applyTheme();
                }
                else if (id.equals("itemChatSettings")) show("chatSettingsView");
                else if (id.equals("itemNotif")) show("notificationSettingsView");
                else if (id.equals("itemPrivacy")) show("privacySettingsView");
                else if (id.equals("privacyBack") || id.equals("notifBack") || id.equals("chatSetBack")) show("settingsView");
                else if (id.equals("itemShare")) {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("text/plain");
                    i.putExtra(Intent.EXTRA_TEXT, "حمّل تطبيق Babylon Chat");
                    act.startActivity(Intent.createChooser(i, "مشاركة رابط التحميل"));
                }
                else if (id.equals("itemLogout")) confirm("تسجيل الخروج", "هل تريد تسجيل الخروج؟", this::logout);
                else if (id.equals("itemDelete")) confirm("حذف الحساب", "سيتم حذف حسابك وبياناتك نهائياً. متأكد؟", () -> {
                    if (currentUser != null) {
                        db.collection("users").document(myUid).delete();
                        currentUser.delete().addOnCompleteListener(t -> logout());
                    }
                });
                else if (id.equals("itemChangePass")) {
                    if (currentUser != null && currentUser.getEmail() != null) {
                        mAuth.sendPasswordResetEmail(currentUser.getEmail())
                            .addOnSuccessListener(a -> toast("سيتم إرسال رابط تغيير كلمة المرور لبريدك"))
                            .addOnFailureListener(e -> toast("فشل: " + e.getMessage()));
                    }
                }
                else if (id.equals("itemChangeEmail")) input("تغيير الإيميل", "name@domain.com", "email", "تم تحديث الإيميل");
                else if (id.equals("itemBlocked")) toast("لا يوجد مستخدمون محظورون");
                else if (id.equals("segLastSeen_show")) { db.collection("users").document(myUid).update("hideLastSeen", false); setSeg("segLastSeen", false); }
                else if (id.equals("segLastSeen_hide")) { db.collection("users").document(myUid).update("hideLastSeen", true); setSeg("segLastSeen", true); }
                else if (id.equals("segAvatar_show")) { db.collection("users").document(myUid).update("hideAvatar", false); setSeg("segAvatar", false); }
                else if (id.equals("segAvatar_hide")) { db.collection("users").document(myUid).update("hideAvatar", true); setSeg("segAvatar", true); }
                else if (id.equals("itemClearCache")) toast("تم مسح البيانات المؤقتة");
                else if (id.equals("itemWipe")) confirm("مسح جميع البيانات", "سيتم مسح كل بيانات التطبيق المحلية.", () -> {
                    sp.edit().clear().apply();
                    toast("تم المسح");
                    logout();
                });
                else if (id.equals("itemAbout")) new AlertDialog.Builder(act).setTitle("حول Babylon Chat")
                    .setMessage("تطبيق المحادثات العربي\nمحادثات، مكالمات، قصص.")
                    .setPositiveButton("حسناً", null).show();
                else if (id.equals("pushOnBtn")) { sp.edit().putBoolean("push", true).apply(); setOnOff("push", true); }
                else if (id.equals("pushOffBtn")) { sp.edit().putBoolean("push", false).apply(); setOnOff("push", false); }
                else if (id.equals("soundOnBtn")) { sp.edit().putBoolean("sound", true).apply(); setOnOff("sound", true); }
                else if (id.equals("soundOffBtn")) { sp.edit().putBoolean("sound", false).apply(); setOnOff("sound", false); }
                else if (id.equals("chatBack")) closeChat();
                else if (id.equals("chatUserInfo")) { hideKb(); vis("chatView", false); openOther(curChat, otherUser, otherUid); }
                else if (id.equals("chatMore")) chatMenu();
                else if (id.equals("csFilterAll")) { chatSearchFilter="all"; setChatSearchFilter(); searchInChat(txt("chatMessageSearchInput")); }
                else if (id.equals("csFilterMine")) { chatSearchFilter="mine"; setChatSearchFilter(); searchInChat(txt("chatMessageSearchInput")); }
                else if (id.equals("csFilterOther")) { chatSearchFilter="other"; setChatSearchFilter(); searchInChat(txt("chatMessageSearchInput")); }
                else if (id.equals("csPrev")) navigateChatSearch(-1);
                else if (id.equals("csNext")) navigateChatSearch(1);
                else if (id.equals("csX")) {
                    chatSearchFilter="all"; setChatSearchFilter(); vis("csNav", false);
                    EditText cs = et("chatMessageSearchInput");
                    if (cs != null) cs.setText("");
                    searchInChat("");
                }
                else if (id.equals("replyCancel")) {
                    cancelReply();
                    EditText mi = et("messageInput");
                    if (mi != null) mi.setText("");
                }
                else if (id.equals("btnSend")) send();
                else if (id.equals("btnMic")) toggleRecording();
                else if (id.equals("recSend")) stopRecordAndSend();
                else if (id.equals("recCancel")) cancelRecord();
                else if (id.equals("btnAttach")) {
                    new AlertDialog.Builder(act).setTitle("إرفاق")
                        .setItems(new String[]{"صورة من المعرض", "ملف"}, (d, w) -> {
                            if (w == 0) pickImage();
                            else toast("الملفات قريباً");
                        }).show();
                }
            }

            void init() {
                View root = v("root");
                if (root != null) root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

                FrameLayout host = (FrameLayout) v("pagesHost");
                if (host == null) throw new RuntimeException("pagesHost غير موجود في mass.xml");

                String[] partFiles = {"part_profile", "part_settings"};
                for (String pf : partFiles) {
                    int lid = act.getResources().getIdentifier(pf, "layout", act.getPackageName());
                    if (lid == 0) {
                        throw new RuntimeException("ملف التصميم " + pf + " غير موجود");
                    }
                    ViewGroup pr = (ViewGroup) act.getLayoutInflater().inflate(lid, null);
                    while (pr.getChildCount() > 0) {
                        View c = pr.getChildAt(0);
                        pr.removeViewAt(0);
                        host.addView(c);
                    }
                }

                View govV = v("modalGovInput");
                if (govV instanceof Spinner) {
                    ((Spinner) govV).setAdapter(new ArrayAdapter<String>(act, android.R.layout.simple_spinner_dropdown_item, govs) {
                        @Override
                        public View getView(int pos, View cv, ViewGroup parent) {
                            TextView t = (TextView) super.getView(pos, cv, parent);
                            t.setTextColor(cText());
                            t.setTypeface(null, android.graphics.Typeface.BOLD);
                            return t;
                        }
                    });
                }

                View.OnClickListener L = x -> {
                    try {
                        click(act.getResources().getResourceEntryName(x.getId()));
                    } catch (Throwable ignored) { }
                };
                String[] ids = {"hdrStories", "mainSearchBtn", "searchBack", "fltAll", "fltUnread",
                        "newGroupFab", "navChats", "navSettings", "navProfile", "pEdit", "pPhoto", "pCover",
                        "pBioRow", "pUserRow", "editBack", "btnCancelEdit", "btnSaveProfile", "otherBack",
                        "otherMsgBtn", "pTabStoriesBtn", "otherBlockBtn", "otherMoreBtn", "settingsThemeToggle", "sxHero",
                        "itemChatSettings", "itemNotif", "itemPrivacy", "privacyBack", "notifBack",
                        "chatSetBack", "itemShare", "itemLogout", "itemDelete", "itemChangePass",
                        "itemChangeEmail", "itemBlocked", "segLastSeen_show", "segLastSeen_hide",
                        "segAvatar_show", "segAvatar_hide", "itemClearCache", "itemWipe", "itemAbout",
                        "pushOnBtn", "pushOffBtn", "soundOnBtn", "soundOffBtn", "chatBack", "chatUserInfo",
                        "chatMore", "csX", "csFilterAll", "csFilterMine", "csFilterOther", "csPrev", "csNext", "replyCancel", "btnSend", "btnMic", "btnAttach", "recSend", "recCancel"};
                for (String id : ids) {
                    View x = v(id);
                    if (x != null) x.setOnClickListener(L);
                }

                EditText si = et("searchInput");
                if (si != null) {
                    si.addTextChangedListener(new TextWatcher() {
                        public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
                        public void onTextChanged(CharSequence s, int a, int b, int c) { try { searchUsers(s.toString()); } catch (Throwable ignored) {} }
                        public void afterTextChanged(Editable s) { }
                    });
                }
                EditText cms = et("chatMessageSearchInput");
                if (cms != null) {
                    cms.addTextChangedListener(new TextWatcher() {
                        public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
                        public void onTextChanged(CharSequence s, int a, int b, int c) { try { searchInChat(s.toString()); } catch (Throwable ignored) {} }
                        public void afterTextChanged(Editable s) { }
                    });
                }

                SeekBar fr = (SeekBar) v("fontRange");
                if (fr != null) {
                    fr.setProgress(fontSp - 12);
                    fr.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                        public void onProgressChanged(SeekBar s, int p, boolean u) {
                            fontSp = 12 + p;
                            TextView fv = tv("fontVal");
                            if (fv != null) fv.setText("" + fontSp);
                            sp.edit().putInt("font", fontSp).apply();
                            applyBubbles();
                        }
                        public void onStartTrackingTouch(SeekBar s) { }
                        public void onStopTrackingTouch(SeekBar s) { }
                    });
                }
                SeekBar rr = (SeekBar) v("radiusRange");
                if (rr != null) {
                    rr.setProgress(radius);
                    rr.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                        public void onProgressChanged(SeekBar s, int p, boolean u) {
                            radius = p;
                            TextView rv = tv("radiusVal");
                            if (rv != null) rv.setText("" + p);
                            sp.edit().putInt("radius", p).apply();
                            applyBubbles();
                        }
                        public void onStartTrackingTouch(SeekBar s) { }
                        public void onStopTrackingTouch(SeekBar s) { }
                    });
                }
                if (fr != null) { TextView fv = tv("fontVal"); if (fv != null) fv.setText("" + fontSp); }
                if (rr != null) { TextView rv = tv("radiusVal"); if (rv != null) rv.setText("" + radius); }

                setupSwipeNavigation();
                setupStoryGestures();
                setupPressAnimations();
                setupProfileMotion();
                View ownAvatar = v("pAvatarText");
                if (ownAvatar != null) ownAvatar.setOnClickListener(x -> showAvatarActions());
                setSeg("segLastSeen", sp.getBoolean("hideLast", false));
                setSeg("segAvatar", sp.getBoolean("hideAv", false));
                setOnOff("push", sp.getBoolean("push", true));
                setOnOff("sound", sp.getBoolean("sound", true));
                applyBubbles();
                applyTheme();

                loadMyProfile();
                loadChats();
                show("homeView");
            }
        }

        UI ui = new UI();
        ui.init();

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
        boolean darkNow = getSharedPreferences("babylon_ui", MODE_PRIVATE).getBoolean("dark", false);
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (Build.VERSION.SDK_INT >= 23 && !darkNow) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26 && !darkNow) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        w.getDecorView().setSystemUiVisibility(flags);

        final View rootView = findViewById(R.id.root);
        if (rootView != null) {
            rootView.setOnApplyWindowInsetsListener((v, insets) -> {
                int top = insets.getSystemWindowInsetTop();
                int bottom = insets.getSystemWindowInsetBottom();
                v.setPadding(0, top, 0, bottom);
                return insets;
            });
            rootView.requestApplyInsets();
        }
    }
}