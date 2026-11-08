package com.babylon.chat.iq;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
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
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
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
import android.view.MotionEvent;
import android.view.View;
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

import com.babylon.chat.iq.databinding.MassBinding;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.SetOptions;

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
    private ListenerRegistration pinsListener;                       // الرسائل المثبتة: chats/{id}.pins
    private java.util.function.BooleanSupplier backHook;             // زر الرجوع يلغي تحديد الرسائل أولاً

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
        if (pinsListener != null) pinsListener.remove();
    }

    @Override
    public void onBackPressed() {
        try { if (backHook != null && backHook.getAsBoolean()) return; } catch (Throwable ignored) { }
        // لا نستدعي super: زر الرجوع لا يُغلق التطبيق أبداً
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
            String coverId = CoverThemes.byId(sp.getString("coverId", CoverThemes.DEFAULT_ID)).id;   // غلافي (users/{uid}.cover)
            String otherCoverId = CoverThemes.DEFAULT_ID;                                            // غلاف الشخص المعروض
            boolean unreadOnly = false, muted = false, blocked = false;
            String curPage = "homeView", prevPage = "homeView", curChat = "";
            String replyText = null, otherName = "", otherUser = "";
            View editing = null;
            // ---- تحديد عدة رسائل + التثبيت (multiSelectBar / pinnedBar في الـ HTML)
            boolean selecting = false;
            final java.util.LinkedHashSet<String> selIds = new java.util.LinkedHashSet<>();
            final java.util.Map<String, Object[]> msgMeta = new java.util.HashMap<>();   // id -> {type, text, data, duration, mine, ts}
            FrameLayout selBar;   // شريط التحديد العلوي
            int selBarH = 0;
            TextView selCountTv;
            ImageView selPinIv;
            View selPinBtn;
            android.view.ViewTreeObserver.OnScrollChangedListener selScrollL;
            List<Map<String, Object>> pins = new ArrayList<>();
            int pinIdx = -1;
            String lastDayKey = "";
            final java.util.Map<String, View> msgViewsById = new java.util.HashMap<>();
            MsgViews.Handle playingHandle = null;
            android.os.Handler uiH = new android.os.Handler(android.os.Looper.getMainLooper());
            boolean tabStories = true;
            BabylonBars bars = new BabylonBars(act);
            boolean tabAnimating = false;
            // حواف النافذة: ارتفاع شريط الحالة/الإيماءات، وطبقات سحب صورة الملف الشخصي
            int sbTop = 0, navBottom = 0, padBottom = 0;   // navBottom = ارتفاع شريط النظام السفلي الفعلي فقط (0 إن لم يوجد)؛ padBottom يشمل لوحة المفاتيح
            ProfileHero heroMe, heroOther;

            MediaRecorder recorder = null;
            File audioFile = null;
            boolean isRecording = false;
            long recordStart = 0;
            Handler recHandler = new Handler(Looper.getMainLooper());
            Runnable recTick = null;
            MediaPlayer mediaPlayer = null;

            String[] pages = {"homeView", "searchView", "profileView", "otherUserProfileView",
                    "settingsView", "privacySettingsView", "notificationSettingsView",
                    "chatSettingsView", "editProfileView"};
            String[] govs = {"بغداد", "البصرة", "نينوى", "أربيل", "النجف", "كربلاء", "السليمانية", "دهوك",
                    "كركوك", "الأنبار", "ديالى", "صلاح الدين", "بابل", "واسط", "ذي قار", "ميسان", "المثنى", "القادسية"};
            String[] themeNames = {"افتراضي", "أزرق", "أخضر", "بنفسجي", "ذهبي"};
            int[] bgLight = {0xFFE4E9F2, 0xFFD6E6FA, 0xFFDDF0DD, 0xFFE8DDF3, 0xFFF5EBCB};
            int[] bgDark = {0xFF0E1621, 0xFF0B1B30, 0xFF0C1F14, 0xFF1A1128, 0xFF241C08};

            ArrayList<String[]> chatData = new ArrayList<>();
            String userName = "", userUsername = "", userBio = "", userGov = "", userEmail = "", userJoin = "";
            String userPhoto = "", shownPhoto = "";
            Bitmap photoBmp = null;

            int dp(float d) { return (int) (d * act.getResources().getDisplayMetrics().density + 0.5f); }
            View v(String n) { return act.findViewById(act.getResources().getIdentifier(n, "id", act.getPackageName())); }
            TextView tv(String n) { View x = v(n); return x instanceof TextView ? (TextView) x : null; }
            EditText et(String n) { View x = v(n); return x instanceof EditText ? (EditText) x : null; }
            String txt(String n) { EditText e = et(n); return e != null ? e.getText().toString().trim() : ""; }
            /** لا إشعارات Toast في التطبيق: الدالة فارغة عمداً (كل استدعاءاتها لا تعرض شيئاً). */
            void toast(String s) { }
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

            int cBg() { return Tk.pageBg(dark); }
            int cSf() { return dark ? 0xFF17212B : 0xFFFFFFFF; }
            int cBorder() { return Tk.border(dark); }
            int cText() { return dark ? 0xFFF1F5F9 : 0xFF17212B; }
            int cMuted() { return Tk.muted(dark); }
            int cGold() { return Tk.gold(dark); }
            int cRed() { return dark ? 0xFFFB7185 : 0xFFDC2626; }
            int cMine() { return dark ? 0xFF2B5278 : 0xFFD9FDD3; }
            int cOther() { return dark ? 0xFF182533 : 0xFFFFFFFF; }
            int cChatBg() { return dark ? bgDark[chatBgIdx] : bgLight[chatBgIdx]; }

            /** حقل الكتابة: #f1f5fa / rgba(127,140,160,.10) ، عند التركيز أبيض / .16 وحدّ #c9cfd8 / #8b94a1 ، انحناء 15 */
            GradientDrawable msgInpBg(boolean focus) {
                GradientDrawable g = new GradientDrawable();
                g.setColor(focus ? (dark ? 0x297F8CA0 : 0xFFFFFFFF) : (dark ? 0x1A7F8CA0 : 0xFFF1F5FA));
                g.setCornerRadius(dp(15));
                g.setStroke(dp(1), focus ? (dark ? 0xFF8B94A1 : 0xFFC9CFD8) : 0x00000000);
                return g;
            }

            /** الإرسال يظهر فقط عند وجود نص ، وعندها يختفي المايك والمرفقات (مثل .has-message في الـ HTML). */
            void syncSend() {
                EditText mi = et("messageInput");
                boolean has = mi != null && mi.getText().toString().trim().length() > 0;
                vis("btnSend", has);
                vis("btnMic", !has);
                vis("btnAttach", !has);
            }

            void setupComposer() {
                final EditText mi = et("messageInput");
                if (mi == null) return;
                mi.addTextChangedListener(new android.text.TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
                    @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
                    @Override public void afterTextChanged(android.text.Editable e) { syncSend(); }
                });
                mi.setOnFocusChangeListener((vw, f) -> mi.setBackground(msgInpBg(f)));
                syncSend();
            }

            void style(View x) {
                if (x == null) return;
                Object t = x.getTag();
                if (t instanceof String) {
                    String[] ks = ((String) t).split(" ");
                    for (String k : ks) {
                        TextView T = (x instanceof TextView) ? (TextView) x : null;
                        if (Tk.handle(k, x, T, dark)) continue;
                        if (k.equals("bg")) x.setBackgroundColor(cBg());
                        else if (k.equals("sf")) x.setBackgroundColor(cSf());
                        else if (k.equals("line")) x.setBackgroundColor(cBorder());
                        else if (k.equals("chatbg")) x.setBackgroundColor(cChatBg());
                        else if (k.equals("card")) {
                            ViewGroup.LayoutParams clp = x.getLayoutParams();
                            boolean small = clp != null && clp.height > 0 && clp.height <= dp(48);
                            x.setBackground(shape(cSf(), small ? 14 : 20, cBorder()));
                            if (isQuickBtn(x)) x.setStateListAnimator(null);
                        }
                        else if (k.equals("row")) { x.setBackground(shape(cSf(), 18, 0)); }
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
                            x.setBackground(shape(dark ? 0x1A7F8CA0 : 0xFFF1F5FA, 15, 0));
                            if (T != null) { T.setTextColor(cText()); T.setHintTextColor(cMuted()); }
                        }
                        else if (k.equals("msginp")) {
                            x.setBackground(msgInpBg(x.hasFocus()));
                            if (T != null) { T.setTextColor(cText()); T.setHintTextColor(cMuted()); }
                        }
                        else if (k.equals("gold")) {
                            boolean chip = x.getId() == R.id.fltAll || x.getId() == R.id.fltUnread;
                            if (chip) {
                                x.setBackground(chipGold(true));
                                x.setAlpha(1f);
                            } else x.setBackground(shape(cGold(), rad(x), 0));
                            if (T != null) T.setTextColor(chip ? 0xFFFFFFFF : 0xFF17212B);
                        }
                        else if (k.equals("goldo")) {
                            boolean chipO = x.getId() == R.id.fltAll || x.getId() == R.id.fltUnread;
                            if (chipO) {                       // نفس تدرّج أزرار تسجيل الدخول لكنه أخفّ عند عدم التحديد
                                x.setBackground(chipGold(false));
                                x.setAlpha(0.62f);
                                if (T != null) T.setTextColor(0xFFFFFFFF);
                            } else { x.setBackground(shape(0, rad(x), cGold())); if (T != null) T.setTextColor(cGold()); }
                        }
                        else if (k.equals("redo")) { x.setBackground(shape(0, 12, cRed())); if (T != null) T.setTextColor(cRed()); }
                        else if (k.equals("circ")) {
                            GradientDrawable g = new GradientDrawable();
                            g.setShape(GradientDrawable.OVAL); g.setColor(cGold()); x.setBackground(g);
                            if (x.getId() == R.id.sxAvatar) x.setBackground(Tk.ringAvatar(x, dark));
                            if (x.getId() == R.id.chatAvatar) x.setBackground(Tk.chatAvatar(x, dark));
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
                            float rr = dp(radius), tl = dp(6);
                            GradientDrawable mg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFFE8C85D, 0xFFCBA843});
                            mg.setCornerRadii(new float[]{rr, rr, rr, rr, tl, tl, rr, rr});   // الذيل أسفل اليمين كما في الـ HTML
                            x.setBackground(mg);
                            if (T != null) T.setTextColor(dark ? 0xFFFFFFFF : 0xFF292216);
                        } else if (k.equals("bubOther")) {
                            float rr = dp(radius), tl = dp(6);
                            GradientDrawable og = new GradientDrawable();
                            og.setColor(dark ? 0xFF263746 : 0xFFFFFFFF);
                            og.setCornerRadii(new float[]{rr, rr, rr, rr, rr, rr, tl, tl});   // الذيل أسفل اليسار كما في الـ HTML
                            x.setBackground(og);
                            if (T != null) T.setTextColor(cText());
                        } else if (k.startsWith("ic:")) {
                            int base = Color.parseColor(k.substring(3));
                            int top = Color.rgb(Color.red(base) + (255 - Color.red(base)) / 4, Color.green(base) + (255 - Color.green(base)) / 4, Color.blue(base) + (255 - Color.blue(base)) / 4);
                            GradientDrawable ig = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{top, base});
                            ig.setCornerRadius(dp(13));
                            x.setBackground(ig);
                        }
                        else if (k.startsWith("cover:")) {
                            if (x.getId() == R.id.frame_layout642) x.setBackground(CoverThemes.gradient(CoverThemes.byId(coverId)));
                            else if (x.getId() == R.id.frame_layout657) x.setBackground(CoverThemes.gradient(CoverThemes.byId(otherCoverId)));
                            else x.setBackgroundColor(Color.parseColor(k.substring(6)));
                        }
                        else if (T != null) {
                            if (k.equals("t")) T.setTextColor(cText());
                            else if (k.equals("m")) T.setTextColor(cMuted());
                            else if (k.equals("g")) T.setTextColor(cGold());
                            else if (k.equals("red")) T.setTextColor(cRed());
                            else if (k.equals("green")) T.setTextColor(0xFF4ADE80);
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
            /** تدرّج ذهبي مثل زر تسجيل الدخول: #e6c566 -> #d3a93b -> #bc8e24 (135°)، حواف كبسولة. */
            GradientDrawable chipGold(boolean on) {
                GradientDrawable cg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                        new int[]{0xFFE6C566, 0xFFD3A93B, 0xFFBC8E24});
                cg.setCornerRadius(dp(20));
                return cg;
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
                // calc(176px + var(--sat)) : الغلاف يمتد خلف شريط الحالة الشفاف، ومحتواه (أزرار الرجوع/المزيد) ينزل تحته
                f.getLayoutParams().height = dp(176) + sbTop;
                f.setPadding(0, sbTop, 0, 0);
                View fade = f.findViewWithTag("fade");
                if (fade == null) {
                    fade = new View(act);
                    fade.setTag("fade");
                    f.addView(fade, 0, new FrameLayout.LayoutParams(-1, dp(110), Gravity.BOTTOM));
                }
                fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{cBg() & 0x00FFFFFF, cBg()}));
                // نقوش الغلاف (.pattern-layer) خلف التدرّج — داخل الإطار المحشو فتبدأ تحت شريط الحالة (top:var(--sat))
                if (f.findViewWithTag("pattern") == null) {
                    CoverPattern pat = new CoverPattern(act);
                    pat.setTheme(CoverThemes.byId(f.getId() == R.id.frame_layout657 ? otherCoverId : coverId));
                    pat.setTag("pattern");
                    f.addView(pat, 0, new FrameLayout.LayoutParams(-1, -1));
                }
                f.requestLayout();
            }

            // ------------------------------------------------------------------ أغلفة الملف الشخصي
            /** applyCover(prefix, id) في الـ HTML: mine=true غلاف صفحتي، false غلاف صفحة الشخص الآخر. */
            void applyCover(boolean mine, String id) {
                CoverThemes.Theme t = CoverThemes.byId(id);
                if (mine) coverId = t.id; else otherCoverId = t.id;
                View frame = v(mine ? "frame_layout642" : "frame_layout657");
                if (frame != null) {
                    View pat = frame.findViewWithTag("pattern");
                    if (pat instanceof CoverPattern) ((CoverPattern) pat).setTheme(t);
                }
                ProfileHero hero = mine ? heroMe : heroOther;
                if (hero != null) hero.setCoverTheme(t);
                else if (frame != null) frame.setBackground(CoverThemes.gradient(t));
            }

            /** openCoverPicker() + buildCoverGrid(): شبكة 3 أعمدة، فراغ 10dp، خلية ارتفاعها 78dp وحدودها 3dp (المحدّد بالذهبي). */
            void openCoverPicker() {
                final LinearLayout card = new LinearLayout(act);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(18), dp(18), dp(18), dp(18));
                card.setBackground(shape(cSf(), 20, 0));

                TextView title = new TextView(act);
                title.setText("غلاف ملفك الشخصي");
                title.setTextColor(cGold());
                title.setTextSize(17);
                title.setTypeface(null, android.graphics.Typeface.BOLD);
                title.setGravity(Gravity.RIGHT);
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
                tlp.bottomMargin = dp(12);
                card.addView(title, tlp);

                final AlertDialog[] dlg = new AlertDialog[1];
                final int cols = 3, gap = dp(10);
                final CoverThemes.Theme[] all = CoverThemes.ALL;
                for (int r = 0; r * cols < all.length; r++) {
                    LinearLayout row = new LinearLayout(act);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);   // الترتيب الظاهري مضبوط يدوياً أدناه
                    // الصفحة عربية (RTL): أول غلاف (بابل) في أقصى اليمين مثل الـ HTML
                    for (int c = cols - 1; c >= 0; c--) {
                        int idx = r * cols + c;
                        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, dp(78), 1f);
                        if (c != 0) clp.setMarginEnd(gap);
                        if (idx < all.length) row.addView(coverCell(all[idx], dlg), clp);
                        else row.addView(new View(act), clp);
                    }
                    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
                    if (r > 0) rlp.topMargin = gap;
                    card.addView(row, rlp);
                }

                dlg[0] = new AlertDialog.Builder(act).setView(card).create();
                dlg[0].show();
                if (dlg[0].getWindow() != null) {
                    dlg[0].getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
                    int sw = act.getResources().getDisplayMetrics().widthPixels;
                    dlg[0].getWindow().setLayout(Math.min(dp(340), sw - dp(24)), ViewGroup.LayoutParams.WRAP_CONTENT);
                }
            }

            /** .cover-opt : تدرّج الغلاف + نقوش مصغّرة (4 أعمدة × 26dp، رمز 13dp، شفافية .24) + اسم الغلاف أسفلها. */
            View coverCell(final CoverThemes.Theme t, final AlertDialog[] dlg) {
                final int bw = dp(3), rad = dp(12);
                FrameLayout cell = new FrameLayout(act);
                cell.setBackground(CoverThemes.gradient(t));
                cell.setClipToOutline(true);
                cell.setOutlineProvider(new android.view.ViewOutlineProvider() {
                    @Override public void getOutline(View view, android.graphics.Outline o) {
                        o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), rad);
                    }
                });

                CoverPattern pat = new CoverPattern(act, 4, 26f, 13f, 12, 0.24f);
                pat.setTheme(t);
                FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-1, -1);
                plp.setMargins(bw, bw, bw, bw);
                cell.addView(pat, plp);

                TextView name = new TextView(act);
                name.setText(t.name);
                name.setTextColor(0xFFFFFFFF);
                name.setTextSize(11);
                name.setTypeface(null, android.graphics.Typeface.BOLD);
                name.setGravity(Gravity.CENTER);
                name.setShadowLayer(dp(3), 0, dp(1), 0x99000000);
                FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
                nlp.bottomMargin = bw + dp(4);
                cell.addView(name, nlp);

                // .cover-opt{border:3px solid transparent} / .sel{border-color:var(--babylon-gold)}
                View border = new View(act);
                GradientDrawable bd = new GradientDrawable();
                bd.setCornerRadius(rad);
                bd.setColor(Color.TRANSPARENT);
                bd.setStroke(bw, t.id.equals(coverId) ? cGold() : Color.TRANSPARENT);
                border.setBackground(bd);
                cell.addView(border, new FrameLayout.LayoutParams(-1, -1));

                cell.setOnClickListener(x -> setCover(t.id, dlg[0]));
                return cell;
            }

            /** setCover(id): تطبيق فوري ثم حفظ في users/{uid}.cover (merge) كما في الويب. */
            void setCover(String id, AlertDialog dlg) {
                if (!CoverThemes.has(id)) return;
                sp.edit().putString("coverId", id).apply();
                applyCover(true, id);
                if (dlg != null) dlg.dismiss();
                Map<String, Object> upd = new HashMap<>();
                upd.put("cover", id);
                db.collection("users").document(myUid).set(upd, SetOptions.merge())
                    .addOnSuccessListener(a -> toast("تم تغيير الغلاف ✧"))
                    .addOnFailureListener(e -> toast("تعذر حفظ الغلاف"));
            }

            int ringPx() { return dp(2.5f); }                 // الحلقة الرمادية الملاصقة للصورة
            int goldRingPx() { return 0; }                  // الشريط الذهبي محذوف
            int goldGapPx() { return 0; }                   // لا فراغ (لا شريط ذهبي)
            /** مجموع ما حول الصورة: رمادي + فراغ + ذهبي */
            int ringTotalPx() { return ringPx() + goldGapPx() + goldRingPx(); }

            /** حلقة رمادية فاتحة (2.5dp) ملاصقة للصورة بلا فراغ؛ تختفي مع الصورة نفسها عند سحبها (ProfileHero يخفي الأفاتار). */
            android.graphics.drawable.Drawable withRing(android.graphics.drawable.Drawable inner) {
                GradientDrawable gold = new GradientDrawable();
                gold.setShape(GradientDrawable.OVAL);
                gold.setColor(Color.TRANSPARENT);
                gold.setStroke(goldRingPx(), cGold());
                GradientDrawable ring = new GradientDrawable();
                ring.setShape(GradientDrawable.OVAL);
                ring.setColor(Color.TRANSPARENT);
                ring.setStroke(ringPx(), dark ? 0xFF3A3F47 : 0xFFD1D5DB);   // رمادي داكن ليلاً
                android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[]{gold, ring, inner});
                int g = goldRingPx() + goldGapPx();                 // الرمادي يبدأ بعد الذهبي + فراغ 2dp
                ld.setLayerInset(1, g, g, g, g);
                int all = g + ringPx();                             // الصورة تبدأ من حافة الحلقة الرمادية مباشرة
                ld.setLayerInset(2, all, all, all, all);
                return ld;
            }

            void fixAvatar(String id) {
                TextView a = tv(id);
                if (a == null) return;
                ViewGroup.LayoutParams lp = a.getLayoutParams();
                if (lp != null) { lp.width = dp(108) + 2 * ringTotalPx(); lp.height = lp.width; }   // 108 + الحلقتان والفراغ
                a.setTextSize(40);
                a.setTextColor(0xFFFFFFFF);
                if (photoBmp != null && id.equals("pAvatarText")) {
                    applyPhotoTo(a);
                } else {
                    GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{dark ? 0xFFF0D77A : 0xFFE8C25A, dark ? 0xFFC9A227 : 0xFFB88A1C});
                    g.setShape(GradientDrawable.OVAL);
                    a.setBackground(withRing(g));
                }
                View par = (View) a.getParent();
                if (par != null && par.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)
                    ((ViewGroup.MarginLayoutParams) par.getLayoutParams()).topMargin = -(dp(58) + ringTotalPx());
                a.requestLayout();
            }

            /** أزرار الصف السريع في الملف الشخصي (تعديل/الصورة/الغلاف وأزرار ملف الشخص الآخر): بلا ظل. */
            boolean isQuickBtn(View x) {
                int i = x.getId();
                return i == R.id.pEdit || i == R.id.pPhoto || i == R.id.pCover
                        || i == R.id.otherMsgBtn || i == R.id.otherBlockBtn;
            }

            void quick(String id, String iconName) {
                TextView t = tv(id);
                if (t == null) return;
                t.setElevation(0);
                t.setStateListAnimator(null);
                // .pf-quick .pf-q : height 56px، padding 8px 6px، gap 5px، أيقونة 19px
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(56), 1f);
                lp.setMargins(dp(4.5f), 0, dp(4.5f), 0);
                t.setLayoutParams(lp);
                t.setPadding(dp(6), dp(8), dp(6), dp(8));
                t.setGravity(Gravity.CENTER);
                t.setCompoundDrawablePadding(dp(5));
                t.setTextSize(12);
                t.setTypeface(null, android.graphics.Typeface.BOLD);
                t.setBackground(shape(cSf(), 16, cBorder()));
                t.setTextColor(cText());
                t.setCompoundDrawablesRelative(null, dr(icon(iconName, 16), 19, cGold()), null, null);
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

            /**
             * أيقونات المربعات (صفوف الملف الشخصي/الإعدادات/المحادثات): TextView فارغ النص بأيقونة drawableStart
             * مع gravity=center كانت تنزاح لجهة بسبب حساب النص+الأيقونة معاً. نثبّتها بحشو متساوٍ فتصير في المنتصف تماماً.
             */
            void centerIconBoxes(View root) {
                if (root == null) return;
                if (root instanceof TextView) {
                    TextView t = (TextView) root;
                    ViewGroup.LayoutParams lp = t.getLayoutParams();
                    android.graphics.drawable.Drawable[] ds = t.getCompoundDrawablesRelative();
                    if (lp != null && t.getText().length() == 0 && ds[0] != null && ds[1] == null && ds[2] == null && ds[3] == null
                            && lp.width > 0 && lp.height > 0 && lp.width <= dp(64)) {   // يشمل أيقونات شريط التنقل (54x26) وليس المربعات فقط
                        int iw = ds[0].getBounds().width() > 0 ? ds[0].getBounds().width() : ds[0].getIntrinsicWidth();
                        if (iw > 0 && iw < lp.width) {
                            int hp = (lp.width - iw) / 2;
                            t.setCompoundDrawablePadding(0);
                            t.setPadding(hp, 0, hp, 0);
                            t.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                        }
                    }
                }
                if (root instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) root;
                    for (int i = 0; i < g.getChildCount(); i++) centerIconBoxes(g.getChildAt(i));
                }
            }

            void polishProfile() {
                try {
                    View spark = v("pCoverTxt");               // النجمة فوق صورة الملف الشخصي
                    if (spark != null) spark.setVisibility(View.GONE);
                } catch (Throwable ignored) { }
                try { centerIconBoxes(act.findViewById(android.R.id.content)); } catch (Throwable ignored) { }
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
                    // أزرار موحّدة في سطر واحد (.pf-quick-other): نفس لون البطاقة وأيقونة ذهبية
                    quick("otherMsgBtn", "ic_chat");
                    quick("otherBlockBtn", blocked ? "ic_check" : "ic_block");
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
                    setTab(v("pTabStoriesBtn"), tabStories);
                } catch (Throwable ignored) { }
                try { centerIconBoxes(act.findViewById(android.R.id.content)); } catch (Throwable ignored) { }
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
                try { syncBars(); } catch (Throwable ignored) { }
            }

            int tabIndex(String p) {
                return p.equals("homeView") ? 0 : p.equals("settingsView") ? 1 : p.equals("profileView") ? 2 : -1;
            }

            boolean isProfilePage(String p) {
                return "profileView".equals(p) || "otherUserProfileView".equals(p);
            }

            boolean chatOpen() {
                View chat = v("chatView");
                return chat != null && chat.getVisibility() == View.VISIBLE;
            }

            /**
             * صفحتا الملف الشخصي تبدآن من أعلى الشاشة فعلاً (بلا padding علوي): الغلاف/الصورة تظهر خلف
             * شريط الحالة الشفاف مثل الـ HTML. باقي الصفحات تبقى تحت شريط الحالة كما كانت.
             */
            void applyInsets() {
                View r = v("root");
                if (r == null) return;
                boolean chat = chatOpen();
                // المحادثة مثل الـ HTML: تبدأ من أعلى الشاشة فعلاً (الطبقة الزجاجية للرأس خلف شريط الإشعارات والرسائل تمرّ من خلفها)
                boolean edge = chat || isProfilePage(curPage);
                // داخل المحادثة (بلا لوحة مفاتيح) لا نحجز مساحة الإيماءات في الجذر: الطبقة الزجاجية للمدخل تمتد خلف شريط الإيماءات الشفاف
                boolean kbUp = padBottom - navBottom > dp(100);
                r.setPadding(0, edge ? 0 : sbTop, 0, (chat && !kbUp) ? 0 : padBottom);
                if (chat) applyChatInsets();
            }

            /** حواف المحادثة: الرأس ينزل بقدر شريط الإشعارات (+4dp) ، والمدخل يترك مسافة سفلية كما في .chat-input-container. */
            void applyChatInsets() {
                View tb = v("chatTopBar"), bb = v("chatBottomBar");
                if (tb != null) tb.setPadding(0, sbTop + dp(4), 0, 0);
                if (bb != null) {
                    // HTML: padding-bottom = max(6px, مساحة الإيماءات) ، و4px أثناء ظهور لوحة المفاتيح.
                    // مساحة الإيماءات هنا مأخوذة أصلاً من padding الجذر، فنكمّل الفرق فقط.
                    boolean kb = padBottom - navBottom > dp(100);
                    bb.setPadding(0, 0, 0, kb ? dp(4) : Math.max(dp(6), navBottom));   // الزجاج يمتد خلف شريط الإيماءات والمدخل فوقه
                }
                syncChatPadding();
            }

            boolean nearBottom(ScrollView sv) {
                View c = sv.getChildAt(0);
                if (c == null) return true;
                return (c.getBottom() + sv.getPaddingBottom()) - (sv.getScrollY() + sv.getHeight()) < dp(160);
            }

            /**
             * الرسائل تمتد خلف الرأس والمدخل الزجاجيين، وتترك مسافة بقدر ارتفاعهما
             * (HTML: padding-top = ارتفاع الرأس + 8 ، padding-bottom = ارتفاع المدخل + 10؛ و messagesArea فيه 10dp أصلاً).
             */
            void syncChatPadding() {
                View ms = v("messagesScroll"), tb = v("chatTopBar"), bb = v("chatBottomBar");
                if (!(ms instanceof ScrollView) || tb == null || bb == null) return;
                if (tb.getHeight() <= 0 && bb.getHeight() <= 0) return;
                int top = Math.max(0, tb.getHeight() - dp(2));
                int bot = Math.max(0, bb.getHeight());
                if (ms.getPaddingTop() == top && ms.getPaddingBottom() == bot) return;
                boolean near = nearBottom((ScrollView) ms);
                ms.setPadding(0, top, 0, bot);
                if (near) scrollDown();
            }

            void setupChatGlass() {
                View.OnLayoutChangeListener l = (vw, a, b, c, d, oa, ob, oc, od) -> {
                    if ((d - b) != (od - ob)) vw.post(() -> syncChatPadding());
                };
                View tb = v("chatTopBar"), bb = v("chatBottomBar");
                if (tb != null) tb.addOnLayoutChangeListener(l);
                if (bb != null) bb.addOnLayoutChangeListener(l);
            }

            void syncBars() {
                try {
                    boolean inChat = chatOpen();
                    applyInsets();
                    int top, bottom;
                    if (inChat) { bars.syncChat(Tk.chatSurface(dark)); return; }   // HTML: شريط الحالة شفاف (الزجاج خلفه) + شريط الإيماءات بلون خلفية المحادثة الصلب
                    else {
                        top = cBg();
                        boolean nav = v("bottomNav") != null && v("bottomNav").getVisibility() == View.VISIBLE;
                        bottom = nav ? Tk.navBg(dark) : cBg();
                    }
                    if (!inChat && isProfilePage(curPage)) bars.syncProfile(bottom);   // شريط حالة شفاف + أيقونات بيضاء
                    else bars.sync(top, bottom);
                } catch (Throwable ignored) { }
            }

            void show(String p) {
                final String from = curPage;
                prevPage = curPage; curPage = p;
                applyInsets();
                if (heroMe != null && !p.equals("profileView")) heroMe.reset();
                if (heroOther != null && !p.equals("otherUserProfileView")) heroOther.reset();
                int fi = tabIndex(from), ti = tabIndex(p);
                final boolean slide = fi >= 0 && ti >= 0 && fi != ti && v(from) != null && v(p) != null
                        && v(from).getVisibility() == View.VISIBLE;
                for (String page : pages) {
                    if (slide && (page.equals(from) || page.equals(p))) continue;
                    vis(page, page.equals(p));
                }
                boolean home = p.equals("homeView");
                boolean hdrWas = v("mainHeader") != null && v("mainHeader").getVisibility() == View.VISIBLE;
                vis("mainHeader", home);
                vis("newGroupFab", home);
                vis("bottomNav", home || p.equals("settingsView") || p.equals("profileView"));
                setNav(p);
                hideKb();
                final View to = v(p);
                if (slide) {
                    final View fv = v(from);
                    to.setVisibility(View.INVISIBLE);
                    final String pp = p;
                    to.post(() -> {
                        BabylonFx.tabSlide(fv, to, ti > fi ? -1 : 1, cBg(), () -> {
                            fv.setVisibility(View.GONE);
                            to.setVisibility(View.VISIBLE);
                        });
                        // العناصر تدخل مع الانزلاق نفسه (كما في الـ HTML) لا بعد انتهائه، فلا تختفي بعد أن تظهر
                        BabylonFx.stagger(to);
                    });
                } else if (to != null) {
                    boolean secondary = ti < 0;
                    if (secondary) BabylonFx.viewIn(to);
                    BabylonFx.stagger(to);
                }
                if (home && !hdrWas) BabylonFx.barDown(v("mainHeader"), 0);
                syncBars();
            }

            void setNav(String p) {
                String[] ids = {"navChats", "navSettings", "navProfile"};
                String a2 = p.equals("homeView") ? "navChats" : p.equals("settingsView") ? "navSettings" : p.equals("profileView") ? "navProfile" : "";
                for (String id : ids) {
                    boolean on = id.equals(a2);
                    TextView ic = tv(id + "Ic");
                    TextView tx = tv(id + "Tx");
                    int inactive = dark ? 0xFFE4C45B : 0xFF8A8266;   // HTML: نهاري #8a8266 / ليلي #e4c45b (الخامل بشفافية .78)
                    int active = dark ? 0xFFE4C45B : 0xFF96720D;
                    if (ic != null) {
                        ic.setTag(null);
                        ic.setTextColor(on ? active : inactive);
                        ic.setBackground(on ? shape(dark ? 0x24E4C45B : 0x29D4AF37, 13, 0) : null);   // .nav-icon.active: rgba(212,175,55,.16) / rgba(228,196,91,.14)
                        tintIcons(ic);
                    }
                    if (tx != null) {
                        tx.setTag(null);
                        tx.setTextSize(11);
                        tx.setTextColor(on ? active : inactive);
                    }
                    View vv = v(id);
                    if (vv != null) {
                        vv.setBackgroundColor(Color.TRANSPARENT);
                        vv.setAlpha(on ? 1f : 0.78f);
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
                            String cv = doc.getString("cover");
                            String cvId = CoverThemes.has(cv) ? cv : CoverThemes.DEFAULT_ID;
                            sp.edit().putString("coverId", cvId).apply();
                            applyCover(true, cvId);
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

            /**
             * .pf-status : نقطة 8dp + هالة 3dp (rgba(74,222,128,.18)) + فراغ 7dp، النص 13sp.
             * متصل: #4ade80 للنقطة والنص؛ غير متصل: نقطة #5b6b7e والنص بلون الثانوي.
             */
            void setPfStatus(TextView t, boolean online, String text) {
                int dot = online ? 0xFF4ADE80 : 0xFF5B6B7E;
                GradientDrawable halo = new GradientDrawable();
                halo.setShape(GradientDrawable.OVAL);
                halo.setColor(online ? 0x2E4ADE80 : 0x00000000);
                GradientDrawable core = new GradientDrawable();
                core.setShape(GradientDrawable.OVAL);
                core.setColor(dot);
                android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[]{halo, core});
                ld.setLayerInset(1, dp(3), dp(3), dp(3), dp(3));
                ld.setBounds(0, 0, dp(14), dp(14));
                t.setText(text);
                t.setTextSize(13);
                t.setTextColor(online ? 0xFF4ADE80 : cMuted());
                t.setGravity(Gravity.CENTER_VERTICAL);
                t.setCompoundDrawablePadding(dp(4));    // 4dp + 3dp هالة = 7dp كما في الـ HTML
                t.setCompoundDrawables(null, null, ld, null);   // النقطة يمين النص والكلمة يسارها (RTL كما في الـ HTML)
            }

            void fillProfile() {
                String letter = userName.length() > 0 ? userName.substring(0, 1).toUpperCase() : "؟";
                TextView pAv = tv("pAvatarText");
                if (pAv != null) pAv.setText(letter);
                TextView pSt = tv("pHeaderStatus");
                if (pSt != null) setPfStatus(pSt, true, "متصل الآن");
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
                blocked = false;
                TextView oAv = tv("otherAvatarText");
                if (oAv != null) oAv.setText(name.length() > 0 ? name.substring(0, 1) : "?");
                TextView oName = tv("otherHeaderName");
                if (oName != null) oName.setText(name);
                TextView oUser = tv("otherHeaderUser");
                if (oUser != null) oUser.setText("@" + username);
                TextView oBio = tv("otherBioDisplay");
                if (oBio != null) oBio.setText("");
                quick("otherBlockBtn", "ic_block"); { TextView ob = tv("otherBlockBtn"); if (ob != null) ob.setText("حظر"); }
                String back = curPage;
                show("otherUserProfileView");
                prevPage = back;
                applyCover(false, CoverThemes.DEFAULT_ID);   // لا يظهر غلاف الشخص السابق أثناء التحميل
                final String reqUid = uid;
                if (uid != null && !uid.isEmpty()) {
                    db.collection("users").document(uid).get().addOnSuccessListener(d -> {
                        if (d.exists() && reqUid.equals(otherUid)) applyCover(false, d.getString("cover"));
                    });
                }
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
                            final String lastTime = lastAt != null ? listTime(lastAt) : "";
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
                                    // عدد الرسائل غير المقروءة (من الطرف الآخر) لهذه المحادثة
                                    db.collection("chats").document(getChatId(myUid, fOtherId)).collection("messages")
                                        .whereEqualTo("read", false).get()
                                        .addOnSuccessListener(qs -> {
                                            int cnt = 0;
                                            for (DocumentSnapshot md : qs.getDocuments())
                                                if (!myUid.equals(md.getString("sender"))) cnt++;
                                            for (int i = 0; i < chatData.size(); i++) {
                                                if (chatData.get(i)[4].equals(fOtherId)) {
                                                    String[] row = chatData.get(i);
                                                    String nv = String.valueOf(cnt);
                                                    if (!row[3].equals(nv)) {
                                                        row[3] = nv;
                                                        try { buildChats(); } catch (Throwable ignored) { }
                                                    }
                                                    break;
                                                }
                                            }
                                        });
                                });
                        }
                    });
            }

            String listTime(long ts) {
                java.util.Calendar a = java.util.Calendar.getInstance(), b = java.util.Calendar.getInstance();
                b.setTimeInMillis(ts);
                if (a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR))
                    return timeLabel(ts);
                a.add(java.util.Calendar.DAY_OF_YEAR, -1);
                if (a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR))
                    return "أمس";
                return new SimpleDateFormat("d/M/yyyy", new Locale("ar")).format(new Date(ts));
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
                    c.setOnClickListener(x -> toast(nm.equals("قصتي") ? "نشر قصة جديدة" : "عرض قصة " + nm));
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
                // أزرار (الكل) و(غير المقروءة) تظهر فقط عند وجود رسالة غير مقروءة
                View flt = v("chatFilters");
                if (flt != null) {
                    boolean show = unreadChats > 0;
                    if (!show && unreadOnly) {          // لم يعد هناك غير مقروء: نرجع لعرض الكل
                        unreadOnly = false;
                        setOn(v("fltAll"), true, "goldo");
                        setOn(v("fltUnread"), false, "goldo");
                    }
                    boolean was = flt.getVisibility() == View.VISIBLE;
                    flt.setVisibility(show ? View.VISIBLE : View.GONE);
                    if (show && !was) BabylonFx.pop(flt, 0);
                }
                for (String[] c : chatData) {
                    if (unreadOnly && c[3].equals("0")) continue;
                    LinearLayout row = new LinearLayout(act);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(12), dp(7), dp(12), dp(7));
                    row.setTag("row");
                    row.setMinimumHeight(dp(71));
                    final boolean hasUnread = !c[3].equals("0");
                    LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
                    rp.setMargins(dp(12), dp(4), dp(12), dp(4));
                    row.setLayoutParams(rp);
                    TextView av = new TextView(act);
                    av.setText(c[0].length() > 0 ? c[0].substring(0, 1) : "?");
                    av.setGravity(Gravity.CENTER);
                    av.setTextSize(19);
                    av.setTypeface(null, android.graphics.Typeface.BOLD);
                    av.setTextColor(0xFFFFFFFF);
                    av.setTag("avatar");
                    av.setLayoutParams(new LinearLayout.LayoutParams(dp(52), dp(52)));
                    LinearLayout mid = new LinearLayout(act);
                    mid.setOrientation(LinearLayout.VERTICAL);
                    LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, -2, 1f);
                    mp.setMargins(dp(12), 0, dp(12), 0);
                    mid.setLayoutParams(mp);
                    TextView nm = new TextView(act);
                    nm.setText(c[0]); nm.setTextSize(16f); nm.setTypeface(null, android.graphics.Typeface.BOLD); nm.setTag("t");
                    TextView ls = new TextView(act);
                    ls.setText(c[1]); ls.setTextSize(14); ls.setTypeface(null, android.graphics.Typeface.BOLD);
                    ls.setPadding(0, dp(3), 0, 0);
                    ls.setSingleLine(true); ls.setEllipsize(TextUtils.TruncateAt.END); ls.setTag(hasUnread ? "t" : "m");
                    mid.addView(nm); mid.addView(ls);
                    LinearLayout end = new LinearLayout(act);
                    end.setOrientation(LinearLayout.VERTICAL);
                    end.setGravity(Gravity.CENTER_HORIZONTAL);
                    TextView tm = new TextView(act);
                    tm.setText(c[2]); tm.setTextSize(12); tm.setTypeface(null, android.graphics.Typeface.BOLD); tm.setTag("g");
                    end.addView(tm);
                    if (!c[3].equals("0")) {
                        TextView b = new TextView(act);
                        b.setText(c[3]); b.setTextSize(12); b.setTypeface(null, android.graphics.Typeface.BOLD);
                        b.setGravity(Gravity.CENTER); b.setTag("badge");
                        b.setMinWidth(dp(23)); b.setMinHeight(dp(23));
                        b.setPadding(dp(7), 0, dp(7), 0);
                        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
                        bl.topMargin = dp(6);
                        end.addView(b, bl);
                    }
                    row.addView(av); row.addView(mid); row.addView(end);
                    final String fName = c[0], fUid = c[4];
                    row.setOnClickListener(x -> openChat(fName, fUid));
                    row.setOnLongClickListener(x -> {
                        new AlertDialog.Builder(act).setTitle(fName)
                            .setItems(new String[]{"تعليم كمقروءة", "حذف المحادثة"}, (d, w) -> {
                                if (w == 1) {
                                    String cid = getChatId(myUid, fUid);
                                    deleteChatMessages(cid, () ->
                                        db.collection("chats").document(cid).delete());
                                }
                            }).show();
                        return true;
                    });
                    list.addView(row);
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
                blocked = false; muted = isMutedUid(uid);
                TextView chatTitle = tv("chatTitle");
                if (chatTitle != null) chatTitle.setText(name);
                TextView chatAv = tv("chatAvatar");
                if (chatAv != null) chatAv.setText(name.length() > 0 ? name.substring(0, 1) : "?");
                TextView chatSub = tv("chatSub");
                if (chatSub != null) chatSub.setText("متصل");
                clearSel();
                LinearLayout msgArea = (LinearLayout) v("messagesArea");
                if (msgArea != null) msgArea.removeAllViews();
                renderedMsgIds.clear(); lastDayKey = ""; msgViewsById.clear(); msgMeta.clear();
                pins = new ArrayList<>(); pinIdx = -1; renderPinnedBar();
                cancelReply();
                vis("chatSearchPanel", false);
                if (isRecording || recPreview) cancelRecord(); else vis("recordingBar", false);

                if (messagesListener != null) messagesListener.remove();

                currentChatId = getChatId(myUid, uid);
                loadMessages(currentChatId);
                listenPins(currentChatId);
                vis("chatView", true);
                final View cv = v("chatView");
                syncBars();
                if (cv != null) BabylonFx.chatGrowNow(cv, v("chatTopBar"), v("chatBottomBar"));
            }

            void loadMessages(String chatId) {
                final boolean[] firstSnap = {true};
                messagesListener = db.collection("chats").document(chatId).collection("messages")
                    .orderBy("timestamp", Query.Direction.ASCENDING)
                    .addSnapshotListener((snapshots, e) -> {
                        if (e != null || snapshots == null) return;
                        final boolean liveBatch = !firstSnap[0];
                        firstSnap[0] = false;
                        for (DocumentChange dc : snapshots.getDocumentChanges()) {
                            if (dc.getType() == DocumentChange.Type.REMOVED) {
                                removeMsgView(dc.getDocument().getId(), null);
                                continue;
                            }
                            if (dc.getType() == DocumentChange.Type.MODIFIED) {
                                DocumentSnapshot md = dc.getDocument();
                                View mv = msgViewsById.get(md.getId());
                                if (mv != null) applyReactions(md.getId(), mv, md.get("reactions"));
                                if (mv != null && myUid.equals(md.getString("sender"))) {
                                    boolean rd = Boolean.TRUE.equals(md.getBoolean("read"));
                                    MsgViews.setState(mv, rd ? MsgViews.READ : (md.getMetadata().hasPendingWrites() ? MsgViews.WAIT : MsgViews.SENT));
                                }
                                continue;
                            }
                            if (dc.getType() == DocumentChange.Type.ADDED) {
                                DocumentSnapshot doc = dc.getDocument();
                                if (!doc.exists()) continue;
                                String id = doc.getId();
                                if (renderedMsgIds.contains(id)) continue;
                                renderedMsgIds.add(id);
                                if (hiddenSet().contains(id)) continue;   // محذوفة "لدي فقط"

                                String type = doc.getString("type");
                                if (type == null) type = "text";
                                String text = doc.getString("text");
                                String url = doc.getString("data");
                                if (url == null) url = doc.getString("url");
                                String sender = doc.getString("sender");
                                Long time = doc.getLong("timestamp");
                                Long dur = doc.getLong("duration");
                                boolean mine = myUid.equals(sender);
                                final long ts = time != null ? time : System.currentTimeMillis();
                                String timeStr = timeLabel(ts);
                                Boolean readFlag = doc.getBoolean("read");
                                boolean isRead = Boolean.TRUE.equals(readFlag);
                                if (!mine && !isRead) { try { doc.getReference().update("read", true); } catch (Throwable ignored) { } }
                                int state = mine ? (isRead ? MsgViews.READ : (doc.getMetadata().hasPendingWrites() ? MsgViews.WAIT : MsgViews.SENT)) : MsgViews.SENT;

                                try {
                                    addDaySep(ts);
                                    if (type.equals("image") && url != null) addImageMsg(url, mine, timeStr);
                                    else if (type.equals("audio") && url != null) addAudioMsg(url, dur != null ? dur : 0, mine, timeStr);
                                    else if (text != null) addMsg(text, mine, timeStr, state);
                                    LinearLayout ma0 = (LinearLayout) v("messagesArea");
                                    if (ma0 != null && ma0.getChildCount() > 0) {
                                        View last = ma0.getChildAt(ma0.getChildCount() - 1);
                                        msgViewsById.put(id, last);
                                        msgMeta.put(id, new Object[]{type, text, url, dur != null ? dur : 0L, mine, ts});
                                        if (Boolean.TRUE.equals(doc.getBoolean("forwarded")) && last instanceof LinearLayout && !(last instanceof MsgViews.VoiceBubble)) {
                                            TextView fl = new TextView(act);
                                            fl.setText("↪ معاد توجيهها"); fl.setTextSize(11); fl.setTypeface(null, android.graphics.Typeface.BOLD_ITALIC);
                                            fl.setTag(mine ? "mmM" : "mmO");
                                            fl.setPadding(dp(2), 0, dp(2), dp(3));
                                            ((LinearLayout) last).addView(fl, 0);
                                            style(fl);
                                        }
                                        if (mine) MsgViews.setState(last, state);
                                        applyReactions(id, last, doc.get("reactions"));
                                    }
                                    scrollDown();
                                    if (liveBatch) {
                                        LinearLayout ma = (LinearLayout) v("messagesArea");
                                        if (ma != null && ma.getChildCount() > 0) BabylonFx.msgIn(ma.getChildAt(ma.getChildCount() - 1));
                                    }
                                } catch (Throwable ignored) { }
                            }
                        }
                    });
            }

            void closeChat() {
                hideKb();
                clearSel();
                vis("chatView", false);
                syncBars();
                if (messagesListener != null) { messagesListener.remove(); messagesListener = null; }
                if (pinsListener != null) { pinsListener.remove(); pinsListener = null; }
                pins = new ArrayList<>(); pinIdx = -1; renderPinnedBar();
                currentChatId = ""; otherUid = "";
                renderedMsgIds.clear(); lastDayKey = ""; msgViewsById.clear(); msgMeta.clear();
                if (mediaPlayer != null) { try { mediaPlayer.release(); } catch (Exception e) {} mediaPlayer = null; }
                try { buildChats(); } catch (Throwable ignored) { }
                try { buildStories(); } catch (Throwable ignored) { }
            }

            String timeLabel(long ts) {
                String t = new SimpleDateFormat("h:mm a", Locale.getDefault()).format(new Date(ts));
                return t.startsWith("0") ? t.substring(1) : t;
            }

            String dayLabel(long ts) {
                java.util.Calendar n = java.util.Calendar.getInstance(), c = java.util.Calendar.getInstance();
                c.setTimeInMillis(ts);
                if (n.get(java.util.Calendar.YEAR) == c.get(java.util.Calendar.YEAR) && n.get(java.util.Calendar.DAY_OF_YEAR) == c.get(java.util.Calendar.DAY_OF_YEAR)) return "اليوم";
                n.add(java.util.Calendar.DAY_OF_YEAR, -1);
                if (n.get(java.util.Calendar.YEAR) == c.get(java.util.Calendar.YEAR) && n.get(java.util.Calendar.DAY_OF_YEAR) == c.get(java.util.Calendar.DAY_OF_YEAR)) return "أمس";
                String pat = c.get(java.util.Calendar.YEAR) != java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) ? "d MMMM yyyy" : "d MMMM";
                return new SimpleDateFormat(pat, new Locale("ar")).format(new Date(ts));
            }

            void addDaySep(long ts) {
                String key = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date(ts));
                if (key.equals(lastDayKey)) return;
                lastDayKey = key;
                LinearLayout msgArea = (LinearLayout) v("messagesArea");
                if (msgArea == null) return;
                TextView sp = MsgViews.sysPill(act, dayLabel(ts));
                msgArea.addView(sp);
                style(sp);
            }

            String bodyOf(String raw) {
                if (raw.startsWith("\u21A9 ")) { int nl = raw.indexOf('\n'); if (nl > 0) return raw.substring(nl + 1); }
                return raw;
            }

            LinearLayout addMsg(String text, final boolean mine, String time) { return addMsg(text, mine, time, MsgViews.SENT); }

            LinearLayout addMsg(String text, final boolean mine, String time, int state) {
                final LinearLayout b = MsgViews.textBubble(act, text, mine, time, state, fontSp);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.gravity = mine ? Gravity.RIGHT : Gravity.LEFT;   // المرسَلة يميناً والمستلمة يساراً (مطلق، لا يتأثر بالاتجاه)
                lp.setMargins(0, dp(4), 0, dp(4));
                b.setLayoutParams(lp);
                b.setOnClickListener(x -> { if (selecting) toggleSel(b); else msgMenu(b, mine, 0, null); });
                b.setOnLongClickListener(x -> { msgSelect(b, mine, 0, null); return true; });
                attachSwipeReply(b, mine);
                LinearLayout msgArea = (LinearLayout) v("messagesArea");
                if (msgArea != null) msgArea.addView(b);
                style(b);
                return b;
            }

            void setMsg(View b, String text, boolean mine, String time) {
                if (b instanceof LinearLayout) MsgViews.bind(act, (LinearLayout) b, text, mine, time, MsgViews.SENT, fontSp);
                style(b);
            }

            void addImageMsg(String url, boolean mine, String time) {
                FrameLayout frame = new FrameLayout(act);
                int size = Math.min(dp(220), (int) (act.getResources().getDisplayMetrics().widthPixels * 0.68f));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
                lp.gravity = mine ? Gravity.RIGHT : Gravity.LEFT;   // المرسَلة يميناً والمستلمة يساراً (مطلق، لا يتأثر بالاتجاه)
                lp.setMargins(0, dp(4), 0, dp(4));
                frame.setLayoutParams(lp);
                frame.setClipToOutline(true);
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(dark ? 0xFF202C33 : 0xFFF0F0F0);
                bg.setCornerRadius(dp(12));
                frame.setBackground(bg);
                frame.setOutlineProvider(new ViewOutlineProvider() {
                    @Override public void getOutline(View v, Outline o) {
                        o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(12));
                    }
                });
                ImageView img = new ImageView(act);
                img.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                frame.addView(img);
                loadImageAsync(img, url);
                LinearLayout t = new LinearLayout(act);
                t.setOrientation(LinearLayout.HORIZONTAL);
                t.setGravity(Gravity.CENTER_VERTICAL);
                t.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
                TextView tt = new TextView(act);
                tt.setText(time); tt.setTextSize(10); tt.setTextColor(0xFFFFFFFF);
                tt.setTypeface(null, android.graphics.Typeface.BOLD);
                tt.setIncludeFontPadding(false);
                t.addView(tt);
                if (mine) {   // علامة الإرسال/الرؤية SVG على الصورة أيضاً
                    ImageView tick = new ImageView(act);
                    tick.setTag("tick");
                    tick.setImageDrawable(new MsgViews.TickDrawable(act, MsgViews.SENT, 0, 0xFFFFFFFF));
                    LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
                    tl.setMarginStart(dp(3));
                    t.addView(tick, tl);
                }
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
                    if (selecting) { toggleSel(frame); return; }
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    try { act.startActivity(i); } catch (Exception e) { toast("لا يمكن فتح الصورة"); }
                });
                frame.setOnLongClickListener(x -> { msgSelect(frame, mine, 1, url); return true; });
                attachSwipeReply(frame, mine);
                LinearLayout msgArea = (LinearLayout) v("messagesArea");
                if (msgArea != null) msgArea.addView(frame);
            }

            void addAudioMsg(final String url, final long durationMs, final boolean mine, String time) {
                final MsgViews.VoiceBubble box = new MsgViews.VoiceBubble(act, mine, formatDuration(durationMs));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.gravity = mine ? Gravity.RIGHT : Gravity.LEFT;   // المرسَلة يميناً والمستلمة يساراً (مطلق، لا يتأثر بالاتجاه)
                lp.setMargins(0, dp(4), 0, dp(4));
                box.setLayoutParams(lp);
                box.addView(MsgViews.metaRow(act, mine, time, MsgViews.SENT));
                box.playBtn.setOnClickListener(v -> { if (selecting) toggleSel(box); else playAudio(url, box, durationMs); });
                box.setOnClickListener(x -> { if (selecting) toggleSel(box); else msgMenu(box, mine, 2, url); });
                box.setOnLongClickListener(x -> { msgSelect(box, mine, 2, url); return true; });
                attachSwipeReply(box, mine);
                box.track.listener = f -> { if (mediaPlayer != null && playingHandle == box) { try { mediaPlayer.seekTo((int) (mediaPlayer.getDuration() * f)); } catch (Throwable ignored) { } } };
                LinearLayout msgArea = (LinearLayout) v("messagesArea");
                if (msgArea != null) msgArea.addView(box);
                style(box);
            }

            String formatDuration(long ms) {
                long s = ms / 1000;
                return String.format(Locale.getDefault(), "%d:%02d", s / 60, s % 60);
            }

            void playAudio(String url, final MsgViews.Handle h, final long fallbackMs) {
                try {
                    if (playingHandle == h && mediaPlayer != null) {
                        if (mediaPlayer.isPlaying()) { mediaPlayer.pause(); h.setPlaying(false); }
                        else { mediaPlayer.start(); h.setPlaying(true); tickVoice(h, fallbackMs); }
                        return;
                    }
                    if (mediaPlayer != null) { mediaPlayer.release(); mediaPlayer = null; }
                    if (playingHandle != null) { playingHandle.setPlaying(false); playingHandle.setProgress(0f, null); }
                    playingHandle = h;
                    mediaPlayer = new MediaPlayer();
                    mediaPlayer.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC);
                    if (url.startsWith("http")) {
                        mediaPlayer.setDataSource(url);
                    } else {
                        boolean isOgg = url.startsWith("T2dnUw");   // "OggS" بصيغة base64
                        File pf = new File(act.getCacheDir(), "play_" + url.length() + "_" + Math.abs(url.hashCode()) + (isOgg ? ".ogg" : ".m4a"));
                        if (!pf.exists()) {
                            byte[] b = android.util.Base64.decode(url, android.util.Base64.DEFAULT);
                            java.io.FileOutputStream fo = new java.io.FileOutputStream(pf);
                            fo.write(b); fo.close();
                        }
                        mediaPlayer.setDataSource(pf.getAbsolutePath());
                    }
                    mediaPlayer.prepareAsync();
                    mediaPlayer.setOnPreparedListener(mp -> { mp.start(); h.setPlaying(true); tickVoice(h, fallbackMs); });
                    mediaPlayer.setOnCompletionListener(mp -> { h.setPlaying(false); h.setProgress(0f, formatDuration(fallbackMs)); playingHandle = null; });
                } catch (Exception e) { toast("فشل تشغيل الصوت"); }
            }

            void tickVoice(final MsgViews.Handle h, final long fallbackMs) {
                uiH.postDelayed(() -> {
                    try {
                        if (mediaPlayer == null || playingHandle != h || !mediaPlayer.isPlaying()) return;
                        int dur = Math.max(1, mediaPlayer.getDuration());
                        int pos = mediaPlayer.getCurrentPosition();
                        h.setProgress(pos / (float) dur, formatDuration(pos));
                        tickVoice(h, fallbackMs);
                    } catch (Throwable ignored) { }
                }, 80);
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

            // ===== قائمة الرسالة (نسخة طبق الأصل من #msgModal و #selEmojiBar في الـ HTML) =====
            final java.util.Map<String, String> myReact = new java.util.HashMap<>();   // msgId -> إيموجي تفاعلي أنا

            String idOfView(View b) {
                for (java.util.Map.Entry<String, View> en : msgViewsById.entrySet()) if (en.getValue() == b) return en.getKey();
                return null;
            }

            /** نقرة عادية على الرسالة: الشريط + بطاقة الأزرار. */
            void msgMenu(View b, boolean mine) { msgMenu(b, mine, 0, null); }

            void msgMenu(final View b, final boolean mine, final int kind, final String url) {
                final String id = idOfView(b);
                final String raw = kind == 0 ? bodyOf(String.valueOf(b.getContentDescription())) : "";
                MsgMenu.Spec sp0 = new MsgMenu.Spec();
                sp0.canCopy = kind == 0 && raw.trim().length() > 0;
                sp0.canEdit = kind == 0 && mine;
                sp0.canDownload = kind == 1 && url != null;
                sp0.downloadLabel = "تحميل الصورة";
                sp0.myReaction = id != null && myReact.get(id) != null ? myReact.get(id) : "";
                sp0.canPin = id != null && !currentChatId.isEmpty();
                sp0.pinned = id != null && pinIndexOf(id) >= 0;
                MsgMenu.show(act, b, dark, sbTop, Math.max(navBottom, padBottom), sp0, msgCb(b, mine, kind, url, raw));
            }

            /** الرد بالسحب كما في الـ HTML: رسائلي يمين→يسار ورسائل غيري يسار→يمين، مقاومة متزايدة، وعتبة 72dp. */
            void attachSwipeReply(final View b, final boolean mine) {
                final int dir = mine ? -1 : 1;
                final float[] st = new float[2];
                final boolean[] f = new boolean[2];   // [0] dragging, [1] armed
                b.setOnTouchListener((x, e) -> {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            st[0] = e.getRawX(); st[1] = e.getRawY(); f[0] = false; f[1] = false;
                            return false;
                        case MotionEvent.ACTION_MOVE: {
                            if (selecting) return false;
                            float dx = e.getRawX() - st[0], dy = e.getRawY() - st[1];
                            if (!f[0]) {
                                if (dx * dir > dp(14) && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                                    f[0] = true;
                                    x.cancelLongPress(); x.setPressed(false);
                                    if (x.getParent() != null) x.getParent().requestDisallowInterceptTouchEvent(true);
                                } else return false;
                            }
                            float sdx = Math.max(0, dx * dir), maxp = dp(64);
                            float pull = maxp * (1f - (float) Math.exp(-sdx / (maxp * 1.7f)));
                            x.setTranslationX(pull * dir);
                            boolean arm = sdx >= dp(72);
                            if (arm && !f[1]) x.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                            f[1] = arm;
                            return true;
                        }
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL: {
                            if (!f[0]) return false;
                            boolean fire = f[1] && e.getActionMasked() == MotionEvent.ACTION_UP;
                            f[0] = false; f[1] = false;
                            x.animate().translationX(0f).setDuration(220).start();
                            if (fire) swipeReply(b, mine);
                            return true;
                        }
                    }
                    return false;
                });
            }

            void swipeReply(View b, boolean mine) {
                String id = idOfView(b);
                Object[] m = id != null ? msgMeta.get(id) : null;
                String type = m != null ? (String) m[0] : "text";
                int kind = "image".equals(type) ? 1 : "audio".equals(type) ? 2 : 0;
                String raw = kind == 0 && m != null && m[1] != null ? bodyOf(String.valueOf(m[1])) : "";
                msgCb(b, mine, kind, m != null ? (String) m[2] : null, raw).reply();
            }

            /** ضغط مطوّل = دخول وضع التحديد (أو تبديل تحديد الرسالة إن كان الوضع مفعلاً). */
            void msgSelect(final View b, final boolean mine, final int kind, final String url) { toggleSel(b); }

            MsgMenu.Cb msgCb(final View b, final boolean mine, final int kind, final String url, final String raw) {
                return new MsgMenu.Cb() {
                    @Override public void reply() {
                        String r = kind == 0 ? raw : (kind == 1 ? "صورة" : "بصمة صوتية");
                        replyText = r.length() > 40 ? r.substring(0, 40) + "…" : r;
                        setLabel("replyLabel", "الرد على الرسالة", icon("ic_reply", 16));
                        TextView rp = tv("replyPreviewText");
                        if (rp != null) rp.setText(replyText);
                        vis("replyPreviewBar", true);
                        EditText mi = et("messageInput");
                        if (mi != null) mi.requestFocus();
                    }
                    @Override public void copy() {
                        ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText("msg", raw));
                        toast("تم نسخ النص!");
                    }
                    @Override public void download() { saveImageMsg(url); }
                    @Override public void edit() {
                        editing = b; replyText = null;
                        setLabel("replyLabel", "تعديل الرسالة", icon("ic_edit", 16));
                        TextView rp2 = tv("replyPreviewText");
                        if (rp2 != null) rp2.setText(raw);
                        vis("replyPreviewBar", true);
                        EditText mi2 = et("messageInput");
                        if (mi2 != null) {
                            mi2.setText(raw);
                            mi2.setSelection(raw.length());
                            mi2.requestFocus();
                        }
                        toast("أدخل التعديل ثم اضغط إرسال");
                    }
                    @Override public void delete() { msgDeleteDialog(b); }
                    @Override public void react(String emoji) { reactTo(b, emoji); if (selecting) clearSel(); }
                    @Override public void pin() { togglePin(idOfView(b)); }
                };
            }

            // =====================================================================
            //  تحديد عدة رسائل (multiSelectBar): إغلاق + عدد + تثبيت + إعادة توجيه + حذف
            // =====================================================================
            /**
             * زر/إيماءة الرجوع: لا يُخرج من التطبيق أبداً (يُرجع true دائماً).
             * الترتيب: قائمة الرسالة ← التحديد ← صورة الملف المكبّرة ← بحث المحادثة ← الرد/التعديل ← التسجيل
             * ← المحادثة ← الصفحات الفرعية ← الرئيسية (فلتر «غير المقروءة» ثم لا شيء).
             */
            boolean onBack() {
                try {
                    if (MsgMenu.isShowing()) { MsgMenu.dismiss(); return true; }
                    if (selecting) { clearSel(); return true; }

                    if (chatOpen()) {
                        View sp2 = v("chatSearchPanel");
                        if (sp2 != null && sp2.getVisibility() == View.VISIBLE) { click("csX"); hideKb(); return true; }
                        View rb = v("replyPreviewBar");
                        if (rb != null && rb.getVisibility() == View.VISIBLE) {
                            cancelReply();
                            EditText mi = et("messageInput");
                            if (mi != null) mi.setText("");
                            return true;
                        }
                        if (isRecording || recPreview) { cancelRecord(); return true; }
                        closeChat();
                        return true;
                    }

                    // صورة الملف الشخصي المكبّرة تُغلق أولاً
                    if (heroMe != null && heroMe.isExpanded()) { heroMe.reset(); return true; }
                    if (heroOther != null && heroOther.isExpanded()) { heroOther.reset(); return true; }

                    switch (curPage) {
                        case "otherUserProfileView":
                            show(prevPage == null || prevPage.equals("otherUserProfileView") ? "homeView" : prevPage);
                            return true;
                        case "editProfileView":
                            show("profileView"); return true;
                        case "privacySettingsView":
                        case "notificationSettingsView":
                        case "chatSettingsView":
                            show("settingsView"); return true;
                        case "searchView":
                        case "settingsView":
                        case "profileView":
                            show("homeView"); return true;
                        default:
                            if (unreadOnly) { setFilter(false); return true; }
                            return true;     // الرئيسية: لا خروج من التطبيق
                    }
                } catch (Throwable t) {
                    return true;             // حتى عند أي خطأ لا نُخرج المستخدم
                }
            }

            void toggleSel(View b) {
                String id = idOfView(b);
                if (id == null) return;
                hideKb();
                if (!selecting) {
                    selecting = true; selIds.clear();
                    b.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                    buildSelBar();
                }
                if (!selIds.add(id)) selIds.remove(id);
                refreshSel();
            }

            /** إطار ذهبي على الرسالة المحددة (.selected-message). */
            void markSel(View mv, boolean on) {
                if (mv == null) return;
                if (Build.VERSION.SDK_INT >= 23) {
                    if (!on) { mv.setForeground(null); return; }
                    GradientDrawable g = new GradientDrawable();
                    g.setColor(goldA(0x26));
                    g.setCornerRadius(mv instanceof FrameLayout ? dp(12) : dp(radius));
                    g.setStroke(dp(2), cGold());
                    mv.setForeground(g);
                } else mv.setAlpha(on ? 0.72f : 1f);
            }

            String firstSel() { return selIds.isEmpty() ? null : selIds.iterator().next(); }

            void refreshSel() {
                if (selIds.isEmpty()) { clearSel(); return; }
                for (Map.Entry<String, View> en : msgViewsById.entrySet()) markSel(en.getValue(), selIds.contains(en.getKey()));
                if (selBar == null) buildSelBar();
                if (selBar == null) return;
                selBar.setVisibility(View.VISIBLE);
                View bb = v("chatBottomBar");
                if (bb != null) bb.setVisibility(View.INVISIBLE);
                selCountTv.setText(String.valueOf(selIds.size()));
                final boolean one = selIds.size() == 1;
                final String fid = firstSel();
                selPinBtn.setVisibility(one ? View.VISIBLE : View.GONE);
                if (one) selPinIv.setImageResource(pinIndexOf(fid) >= 0 ? R.drawable.ic_mm_unpin : R.drawable.ic_mm_pin);
                final View mv = msgViewsById.get(fid);
                if (one && mv != null) {
                    Object[] m = msgMeta.get(fid);
                    final String type = m != null ? (String) m[0] : "text";
                    final int kind = "image".equals(type) ? 1 : "audio".equals(type) ? 2 : 0;
                    final String url = m != null ? (String) m[2] : null;
                    final boolean mine = m != null && Boolean.TRUE.equals(m[4]);
                    final String raw = kind == 0 && m != null && m[1] != null ? bodyOf(String.valueOf(m[1])) : "";
                    MsgMenu.showPill(act, mv, dark, cGold(), selTopInset(), Math.max(navBottom, padBottom),
                            myReact.get(fid) != null ? myReact.get(fid) : "",
                            () -> { clearSel(); msgMenu(mv, mine, kind, url); },
                            msgCb(mv, mine, kind, url, raw));
                } else MsgMenu.dismiss();
                View ms = v("messagesScroll");
                if (selScrollL == null && ms != null) {     // الحبة تتبع الرسالة أثناء التمرير
                    selScrollL = () -> {
                        if (selecting && selIds.size() == 1) MsgMenu.placePill(msgViewsById.get(firstSel()), selTopInset(), Math.max(navBottom, padBottom));
                    };
                    ms.getViewTreeObserver().addOnScrollChangedListener(selScrollL);
                }
            }

            void clearSel() {
                selecting = false;
                selIds.clear();
                for (View mv : msgViewsById.values()) markSel(mv, false);
                final FrameLayout old = selBar;
                selBar = null;
                if (old != null) {     // خروج الشريط العلوي بانزلاق للأعلى ثم إزالته
                    old.animate().alpha(0f).translationY(-Math.max(1, selBarH) * 0.6f).setDuration(160)
                            .withEndAction(() -> { if (old.getParent() instanceof ViewGroup) ((ViewGroup) old.getParent()).removeView(old); }).start();
                }
                View bb = v("chatBottomBar");
                if (bb != null) bb.setVisibility(View.VISIBLE);
                MsgMenu.dismiss();
            }

            View selPill(int iconRes, String label, int fg, Drawable bg, final Runnable r) {
                LinearLayout p = new LinearLayout(act);
                p.setOrientation(LinearLayout.HORIZONTAL);
                p.setGravity(Gravity.CENTER);
                p.setPadding(dp(14), 0, dp(14), 0);
                p.setBackground(bg);
                p.setClickable(true);
                p.setOnClickListener(x -> r.run());
                ImageView ic = new ImageView(act);
                ic.setImageResource(iconRes); ic.setColorFilter(fg);
                p.addView(ic, new LinearLayout.LayoutParams(dp(18), dp(18)));
                TextView t = new TextView(act);
                t.setText(label); t.setTextSize(13); t.setTextColor(fg);
                t.setTypeface(null, android.graphics.Typeface.BOLD);
                LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
                tl.setMarginStart(dp(6));
                p.addView(t, tl);
                BabylonFx.press(p, 0.94f);
                return p;
            }

            GradientDrawable pillBg(int fill, float rDp) {
                GradientDrawable g = new GradientDrawable();
                g.setColor(fill); g.setCornerRadius(dp(rDp));
                return g;
            }

            /** أقصى حد علوي تتجنبه الحبة العائمة: ارتفاع رأس المحادثة (مع شريط التثبيت إن وُجد). */
            int selTopInset() {
                View tb = v("chatTopBar");
                int h = tb != null ? tb.getHeight() : 0;
                return Math.max(Math.max(h, selBarH), sbTop);
            }

            /** ارتفاع رأس المحادثة نفسه (دون شريط الرسالة المثبتة) كي يغطيه شريط التحديد تماماً. */
            int chatHeaderH() {
                View tb = v("chatTopBar");
                if (tb instanceof LinearLayout && ((LinearLayout) tb).getChildCount() > 0) {
                    int bt = ((LinearLayout) tb).getChildAt(0).getBottom();
                    if (bt > 0) return bt;
                }
                return sbTop + dp(4) + dp(54);
            }

            /** زر أيقونة مربّع بحواف 12dp (مثل #selBar .icon-btn في الـ HTML). */
            ImageView selIcon(int iconRes, int tint, int fill, String desc, final Runnable r) {
                ImageView iv = new ImageView(act);
                iv.setImageResource(iconRes);
                iv.setColorFilter(tint);
                iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                iv.setPadding(dp(10), dp(10), dp(10), dp(10));
                iv.setBackground(pillBg(fill, 12));
                iv.setContentDescription(desc);
                iv.setClickable(true);
                iv.setOnClickListener(x -> r.run());
                BabylonFx.press(iv, 0.9f);
                return iv;
            }

            /**
             * شريط التحديد العلوي — يطابق #selBar في الـ HTML ويحلّ محل رأس المحادثة أثناء التحديد:
             * ✕ + «N محدد» عند البداية، وعند النهاية: تثبيت | إعادة توجيه | حذف. خط ذهبي رفيع أسفله، بلا ظل.
             */
            void buildSelBar() {
                View host = v("chatView");
                if (!(host instanceof FrameLayout)) return;
                if (selBar != null && selBar.getParent() instanceof ViewGroup) ((ViewGroup) selBar.getParent()).removeView(selBar);

                final int h = Math.max(chatHeaderH(), sbTop + dp(54));
                selBarH = h;
                FrameLayout wrap = new FrameLayout(act);
                wrap.setClickable(true);                       // لا يمرّر اللمس لما خلفه
                wrap.setBackgroundColor(Tk.chatSurface(dark));

                LinearLayout row = new LinearLayout(act);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(10), sbTop, dp(10), 0);
                wrap.addView(row, new FrameLayout.LayoutParams(-1, -1));

                // ---- البداية: إغلاق + العدد
                row.addView(selIcon(R.drawable.ic_mm_x, cText(), 0x1F7F8CA0, "إلغاء التحديد", this::clearSel),
                        new LinearLayout.LayoutParams(dp(40), dp(40)));

                LinearLayout cnt = new LinearLayout(act);
                cnt.setOrientation(LinearLayout.HORIZONTAL);
                cnt.setGravity(Gravity.CENTER_VERTICAL);
                selCountTv = new TextView(act);
                selCountTv.setTextSize(18); selCountTv.setTextColor(cGold());
                selCountTv.setTypeface(null, android.graphics.Typeface.BOLD);
                selCountTv.setIncludeFontPadding(false);
                cnt.addView(selCountTv);
                TextView lb = new TextView(act);
                lb.setText("محدد"); lb.setTextSize(12); lb.setTextColor(cMuted());
                lb.setTypeface(null, android.graphics.Typeface.BOLD);
                lb.setIncludeFontPadding(false);
                LinearLayout.LayoutParams lbl = new LinearLayout.LayoutParams(-2, -2);
                lbl.setMarginStart(dp(6));
                cnt.addView(lb, lbl);
                LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
                cl.setMarginStart(dp(10));
                row.addView(cnt, cl);

                // ---- النهاية: تثبيت | إعادة توجيه | حذف
                selPinIv = selIcon(R.drawable.ic_mm_pin, cGold(), goldA(0x1F), "تثبيت الرسالة",
                        () -> { String id = firstSel(); clearSel(); togglePin(id); });
                selPinBtn = selPinIv;
                LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(dp(40), dp(40));
                pl.setMarginEnd(dp(8));
                row.addView(selPinIv, pl);

                LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(dp(40), dp(40));
                fl.setMarginEnd(dp(8));
                row.addView(selIcon(R.drawable.ic_mm_forward, cGold(), goldA(0x1F), "إعادة توجيه", this::forwardSel), fl);

                row.addView(selIcon(R.drawable.ic_mm_delete, 0xFFF0605D, 0x1FF0605D, "حذف الرسائل المحددة", this::multiDelete),
                        new LinearLayout.LayoutParams(dp(40), dp(40)));

                // ---- خط ذهبي رفيع أسفل الشريط
                View line = new View(act);
                line.setBackgroundColor(goldA(0x59));
                wrap.addView(line, new FrameLayout.LayoutParams(-1, Math.max(1, dp(1)), Gravity.BOTTOM));

                ((FrameLayout) host).addView(wrap, new FrameLayout.LayoutParams(-1, h, Gravity.TOP));
                selBar = wrap;

                // ---- دخول: انزلاق من الأعلى مع ظهور تدريجي
                wrap.setAlpha(0f);
                wrap.setTranslationY(-h * 0.6f);
                wrap.animate().alpha(1f).translationY(0f).setDuration(220)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
            }

            // ---- حذف متعدد: لدي فقط / لدى الكل (openMultiDeleteDialog)
            void multiDelete() {
                if (selIds.isEmpty()) return;
                final List<String> ids = new ArrayList<>(selIds);
                boolean allMine = true;
                for (String id : ids) { Object[] m = msgMeta.get(id); if (m == null || !Boolean.TRUE.equals(m[4])) allMine = false; }
                final int n = ids.size();
                final String cid = currentChatId;
                MsgMenu.showDelete(act, dark, "حذف " + n + (n == 1 ? " رسالة" : " رسائل"),
                        allMine ? "اختر أين تريد حذف الرسائل المحددة." : "الحذف لدى الكل متاح فقط لرسائلك.", allMine,
                        new MsgMenu.DeleteCb() {
                            @Override public void forMe() {
                                java.util.Set<String> h = hiddenSet(); h.addAll(ids);
                                sp.edit().putStringSet("hidden_msgs_" + cid, h).apply();
                                clearSel();
                                for (String id : ids) removeMsgView(id, null);
                            }
                            @Override public void forAll() {
                                if (cid.isEmpty()) { toast("تعذر تحديد الرسائل"); return; }
                                new AlertDialog.Builder(act).setTitle("حذف الرسائل لدى الجميع")
                                    .setMessage("سيتم حذف " + n + " رسالة لدى الجميع. هل تريد المتابعة؟")
                                    .setPositiveButton("حذف الرسائل", (d, w) -> {
                                        com.google.firebase.firestore.WriteBatch wb = db.batch();
                                        for (String id : ids) wb.delete(db.collection("chats").document(cid).collection("messages").document(id));
                                        wb.commit().addOnSuccessListener(x -> { clearSel(); for (String id : ids) removeMsgView(id, null); toast("تم حذف الرسائل لدى الجميع"); })
                                            .addOnFailureListener(e -> toast(e instanceof com.google.firebase.firestore.FirebaseFirestoreException
                                                && ((com.google.firebase.firestore.FirebaseFirestoreException) e).getCode() == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED
                                                ? "تعذر الحذف: قواعد Firestore لا تسمح بذلك" : "تعذر حذف الرسائل؛ تحقق من الاتصال"));
                                    })
                                    .setNegativeButton("إلغاء", null).show();
                            }
                        });
            }

            // ---- إعادة توجيه الرسائل المحددة إلى محادثة أخرى (openForwardPicker / forwardSelected)
            void forwardSel() {
                if (selIds.isEmpty()) return;
                final List<String> names = new ArrayList<>(), uids = new ArrayList<>();
                for (String[] r : chatData) { if (r.length > 4) { names.add(r[0]); uids.add(r[4]); } }
                if (names.isEmpty()) { toast("لا توجد محادثات متاحة"); return; }
                new AlertDialog.Builder(act).setTitle("إعادة توجيه إلى")
                    .setItems(names.toArray(new String[0]), (d, i) -> doForward(uids.get(i)))
                    .setNegativeButton("إلغاء", null).show();
            }

            void doForward(final String targetUid) {
                final List<String> ids = new ArrayList<>(selIds);
                Collections.sort(ids, (a, b) -> {
                    Object[] x = msgMeta.get(a), y = msgMeta.get(b);
                    long tx = x != null ? (Long) x[5] : 0L, ty = y != null ? (Long) y[5] : 0L;
                    return Long.compare(tx, ty);
                });
                String tcid = getChatId(myUid, targetUid);
                long base = System.currentTimeMillis();
                int sent = 0; String last = "";
                for (String id : ids) {
                    Object[] m = msgMeta.get(id);
                    if (m == null) continue;
                    String type = (String) m[0];
                    Map<String, Object> msg = new HashMap<>();
                    msg.put("type", type);
                    if ("image".equals(type) || "audio".equals(type)) {
                        if (m[2] == null) continue;
                        msg.put("data", m[2]); msg.put("duration", m[3]);
                        last = "image".equals(type) ? "📷 صورة" : "🎤 رسالة صوتية";
                    } else {
                        String body = m[1] == null ? "" : bodyOf(String.valueOf(m[1]));
                        if (body.trim().isEmpty()) continue;
                        msg.put("text", body); last = body;
                    }
                    msg.put("sender", myUid);
                    msg.put("timestamp", base + sent);
                    msg.put("forwarded", true);
                    db.collection("chats").document(tcid).collection("messages").add(msg);
                    sent++;
                }
                if (sent == 0) { toast("لا توجد رسائل قابلة لإعادة التوجيه"); return; }
                Map<String, Object> cd = new HashMap<>();
                cd.put("participants", Arrays.asList(myUid, targetUid));
                cd.put("lastMessage", last);
                cd.put("lastMessageAt", System.currentTimeMillis());
                cd.put("lastSender", myUid);
                db.collection("chats").document(tcid).set(cd, com.google.firebase.firestore.SetOptions.merge());
                clearSel();
                toast("تمت إعادة توجيه " + sent + (sent == 1 ? " رسالة" : " رسائل"));
            }

            // =====================================================================
            //  تثبيت الرسائل: chats/{id}.pins = [{id, text, by, at}] (حد أقصى 10) + شريط الرسالة المثبتة
            // =====================================================================
            int pinIndexOf(String id) {
                if (id == null) return -1;
                for (int i = 0; i < pins.size(); i++) if (id.equals(pins.get(i).get("id"))) return i;
                return -1;
            }

            @SuppressWarnings("unchecked")
            void listenPins(final String cid) {
                if (pinsListener != null) pinsListener.remove();
                pinsListener = db.collection("chats").document(cid).addSnapshotListener((d, e) -> {
                    if (e != null || d == null || !cid.equals(currentChatId)) return;
                    List<Map<String, Object>> np = new ArrayList<>();
                    Object po = d.get("pins");
                    if (po instanceof List) for (Object o : (List<?>) po) if (o instanceof Map) np.add((Map<String, Object>) o);
                    pins = np;
                    renderPinnedBar();
                    if (selecting) refreshSel();
                });
            }

            void savePins(List<Map<String, Object>> np) {
                pins = np;
                renderPinnedBar();
                Map<String, Object> upd = new HashMap<>();
                upd.put("pins", np);
                db.collection("chats").document(currentChatId).set(upd, com.google.firebase.firestore.SetOptions.merge())
                    .addOnFailureListener(e -> toast("تعذر تحديث التثبيت"));
            }

            void togglePin(String id) {
                if (id == null || currentChatId.isEmpty()) return;
                int ex = pinIndexOf(id);
                List<Map<String, Object>> np = new ArrayList<>(pins);
                if (ex >= 0) {
                    np.remove(ex); pinIdx = -1;
                    toast("تم إلغاء تثبيت الرسالة");
                } else {
                    if (np.size() >= 10) { toast("الحد الأقصى 10 رسائل مثبتة"); return; }
                    Object[] m = msgMeta.get(id);
                    String type = m != null ? (String) m[0] : "text";
                    String txt = "audio".equals(type) ? "♬ بصمة صوتية" : "image".equals(type) ? "▧ صورة"
                            : (m != null && m[1] != null ? bodyOf(String.valueOf(m[1])).trim() : "");
                    if (txt.length() > 90) txt = txt.substring(0, 90);
                    Map<String, Object> pm = new HashMap<>();
                    pm.put("id", id); pm.put("text", txt); pm.put("by", myUid); pm.put("at", System.currentTimeMillis());
                    np.add(pm); pinIdx = np.size() - 1;
                    toast("تم تثبيت الرسالة");
                }
                savePins(np);
            }

            void jumpToMsg(final String id) {
                final View mv = msgViewsById.get(id);
                View ms = v("messagesScroll");
                if (mv == null || !(ms instanceof ScrollView)) { toast("الرسالة غير موجودة في المحادثة"); return; }
                int y = mv.getTop() - Math.max(0, (ms.getHeight() - mv.getHeight()) / 2);
                ((ScrollView) ms).smoothScrollTo(0, Math.max(0, y));
                uiH.postDelayed(() -> markSel(mv, true), 450);
                uiH.postDelayed(() -> { if (!selIds.contains(id)) markSel(mv, false); }, 1900);
            }

            /** شريط الرسالة المثبتة تحت رأس المحادثة: خطوط المؤشر + «رسالة مثبتة #n» + النص + زر إلغاء التثبيت. */
            void renderPinnedBar() {
                View tb = v("chatTopBar");
                if (!(tb instanceof LinearLayout)) return;
                LinearLayout top = (LinearLayout) tb;
                View old = top.findViewWithTag("pinnedBar");
                if (old != null) top.removeView(old);
                if (pins.isEmpty()) { pinIdx = -1; return; }
                if (pinIdx < 0 || pinIdx >= pins.size()) pinIdx = pins.size() - 1;
                final int idx = pinIdx;
                final Map<String, Object> p = pins.get(idx);
                final int n = pins.size();

                LinearLayout bar = new LinearLayout(act);
                bar.setTag("pinnedBar");
                bar.setOrientation(LinearLayout.HORIZONTAL);
                bar.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
                bar.setGravity(Gravity.CENTER_VERTICAL);
                bar.setPadding(dp(10), dp(6), dp(6), dp(6));
                bar.setBackground(shape(cSf(), 18, cBorder()));
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
                blp.setMargins(dp(10), dp(2), dp(10), dp(6));
                bar.setLayoutParams(blp);

                LinearLayout line = new LinearLayout(act);
                line.setOrientation(LinearLayout.VERTICAL);
                for (int i = 0; i < n; i++) {
                    View seg = new View(act);
                    GradientDrawable sg = new GradientDrawable();
                    sg.setCornerRadius(dp(2)); sg.setColor(i == idx ? cGold() : goldA(0x4D));
                    seg.setBackground(sg);
                    LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, 0, 1f);
                    if (i > 0) sl.topMargin = dp(2);
                    line.addView(seg, sl);
                }
                bar.addView(line, new LinearLayout.LayoutParams(dp(3), dp(34)));

                LinearLayout col = new LinearLayout(act);
                col.setOrientation(LinearLayout.VERTICAL);
                TextView hd = new TextView(act);
                hd.setText("رسالة مثبتة" + (n > 1 ? " #" + (idx + 1) : ""));
                hd.setTextSize(13); hd.setTextColor(cGold()); hd.setTypeface(null, android.graphics.Typeface.BOLD);
                col.addView(hd);
                TextView tx = new TextView(act);
                tx.setText(String.valueOf(p.get("text") == null ? "" : p.get("text")));
                tx.setTextSize(13.5f); tx.setTextColor(cText()); tx.setAlpha(0.85f);
                tx.setMaxLines(1); tx.setEllipsize(android.text.TextUtils.TruncateAt.END);
                tx.setTypeface(null, android.graphics.Typeface.BOLD);
                tx.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
                col.addView(tx);
                LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
                cl.setMarginStart(dp(10)); cl.setMarginEnd(dp(4));
                bar.addView(col, cl);
                col.setOnClickListener(x -> {
                    jumpToMsg(String.valueOf(p.get("id")));
                    if (n > 1) { pinIdx = (idx - 1 + n) % n; renderPinnedBar(); }
                });

                ImageView xb = new ImageView(act);
                xb.setImageResource(R.drawable.ic_mm_unpin); xb.setColorFilter(cMuted());
                xb.setPadding(dp(8), dp(8), dp(8), dp(8));
                xb.setContentDescription("إلغاء التثبيت");
                xb.setOnClickListener(x -> confirm("إلغاء التثبيت", "هل تريد إلغاء تثبيت هذه الرسالة؟", () -> {
                    List<Map<String, Object>> np = new ArrayList<>(pins);
                    int at = pinIndexOf(String.valueOf(p.get("id")));
                    if (at >= 0) np.remove(at);
                    pinIdx = -1;
                    savePins(np);
                }));
                BabylonFx.press(xb, 0.9f);
                bar.addView(xb, new LinearLayout.LayoutParams(dp(36), dp(36)));
                top.addView(bar);
            }

            // ---- حذف: لدي فقط / لدى الكل (messageDeleteModal)
            java.util.Set<String> hiddenSet() {
                return new java.util.HashSet<>(sp.getStringSet("hidden_msgs_" + currentChatId, new java.util.HashSet<String>()));
            }

            void msgDeleteDialog(final View b) {
                final String id = idOfView(b);
                final String cid = currentChatId;
                MsgMenu.showDelete(act, dark, new MsgMenu.DeleteCb() {
                    @Override public void forMe() {
                        if (id != null) { java.util.Set<String> h = hiddenSet(); h.add(id); sp.edit().putStringSet("hidden_msgs_" + cid, h).apply(); }
                        removeMsgView(id, b);
                    }
                    @Override public void forAll() {
                        if (id == null || cid.isEmpty()) { toast("تعذر تحديد الرسالة"); return; }
                        new AlertDialog.Builder(act).setTitle("حذف الرسالة لدى الجميع")
                            .setMessage("سيتم حذف الرسالة لدى الجميع إذا سمحت صلاحيات قاعدة البيانات. هل تريد المتابعة؟")
                            .setPositiveButton("حذف الرسالة", (d, w) ->
                                db.collection("chats").document(cid).collection("messages").document(id).delete()
                                    .addOnSuccessListener(x -> { removeMsgView(id, b); toast("تم حذف الرسالة لدى الجميع"); })
                                    .addOnFailureListener(e -> toast(e instanceof com.google.firebase.firestore.FirebaseFirestoreException
                                        && ((com.google.firebase.firestore.FirebaseFirestoreException) e).getCode() == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED
                                        ? "تعذر الحذف: قواعد Firestore لا تسمح بذلك" : "تعذر حذف الرسالة؛ تحقق من الاتصال والصلاحيات")))
                            .setNegativeButton("إلغاء", null).show();
                    }
                });
            }

            void removeMsgView(String id, View b) {
                View mv = id != null ? msgViewsById.remove(id) : null;
                if (mv == null) mv = b;
                if (id != null) { myReact.remove(id); selIds.remove(id); msgMeta.remove(id); }
                if (mv != null && mv.getParent() instanceof ViewGroup) ((ViewGroup) mv.getParent()).removeView(mv);
                if (selecting) refreshSel();
            }

            // ---- التفاعلات: reactions.<uid> = إيموجي (نفس صيغة الـ HTML). نفس الإيموجي مرة ثانية يزيل تفاعلك فقط.
            void reactTo(View b, String emoji) {
                String id = idOfView(b);
                if (id == null || currentChatId.isEmpty() || myUid == null || myUid.isEmpty()) return;
                String cur = myReact.get(id);
                Object val = emoji.equals(cur) ? com.google.firebase.firestore.FieldValue.delete() : emoji;
                db.collection("chats").document(currentChatId).collection("messages").document(id)
                    .update("reactions." + myUid, val)
                    .addOnFailureListener(e -> toast("تعذر إرسال التفاعل"));
            }

            /** يرسم شارات التفاعل أسفل الفقاعة (reactions-row) أو داخل زاوية الصورة (media-react). */
            @SuppressWarnings("unchecked")
            void applyReactions(String id, View bubble, Object raw) {
                if (bubble == null) return;
                java.util.LinkedHashMap<String, int[]> map = new java.util.LinkedHashMap<>();   // emoji -> {count, mine}
                String mineE = null;
                if (raw instanceof java.util.Map) {
                    for (java.util.Map.Entry<String, Object> en : ((java.util.Map<String, Object>) raw).entrySet()) {
                        if (!(en.getValue() instanceof String)) continue;
                        String e = (String) en.getValue();
                        if (e.length() == 0) continue;
                        int[] o = map.get(e); if (o == null) { o = new int[]{0, 0}; map.put(e, o); }
                        o[0]++;
                        if (en.getKey().equals(myUid)) { o[1] = 1; mineE = e; }
                    }
                }
                if (mineE != null) myReact.put(id, mineE); else myReact.remove(id);

                View old = bubble.findViewWithTag("reactRow");
                if (old != null && old.getParent() instanceof ViewGroup) ((ViewGroup) old.getParent()).removeView(old);
                if (map.isEmpty()) return;

                LinearLayout rowL = new LinearLayout(act);
                rowL.setOrientation(LinearLayout.HORIZONTAL);
                rowL.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
                rowL.setTag("reactRow");
                int shown = 0;
                for (java.util.Map.Entry<String, int[]> en : map.entrySet()) {
                    if (shown == 3) break;
                    rowL.addView(reactChip(en.getKey(), en.getValue()[0], en.getValue()[1] == 1));
                    shown++;
                }
                if (map.size() > shown) rowL.addView(reactChip("+" + (map.size() - shown), 1, false));

                if (bubble instanceof FrameLayout) {            // صورة: شارة داخل الزاوية السفلية
                    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2);
                    lp.gravity = Gravity.BOTTOM | Gravity.LEFT;
                    lp.setMargins(dp(8), 0, 0, dp(8));
                    ((FrameLayout) bubble).addView(rowL, lp);
                } else if (bubble instanceof LinearLayout) {   // نص/صوت: صف أسفل الفقاعة
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                    lp.gravity = Gravity.START;
                    lp.topMargin = dp(4);
                    ((LinearLayout) bubble).addView(rowL, lp);
                }
            }

            View reactChip(String emoji, int count, boolean mine) {
                LinearLayout c = new LinearLayout(act);
                c.setOrientation(LinearLayout.HORIZONTAL);
                c.setGravity(Gravity.CENTER_VERTICAL);
                c.setPadding(dp(8), dp(2), dp(8), dp(2));
                GradientDrawable g = new GradientDrawable();
                g.setColor(dark ? 0xFF202D3A : 0xFFFFFFFF);
                g.setCornerRadius(dp(13));
                g.setStroke(dp(1), mine ? cGold() : (dark ? 0xFF334155 : 0xFFE1E7EF));
                c.setBackground(g);
                TextView t = new TextView(act);
                t.setText(emoji); t.setTextSize(emoji.startsWith("+") ? 12 : 16); t.setIncludeFontPadding(false);
                t.setTextColor(dark ? 0xFFF1F5F9 : 0xFF17212B);
                c.addView(t);
                if (count > 1) {
                    TextView n = new TextView(act);
                    n.setText(String.valueOf(count)); n.setTextSize(11); n.setTypeface(null, android.graphics.Typeface.BOLD);
                    n.setTextColor(dark ? 0xFFF1F5F9 : 0xFF17212B); n.setIncludeFontPadding(false);
                    LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-2, -2);
                    nl.setMarginStart(dp(3));
                    c.addView(n, nl);
                }
                LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
                cl.setMarginEnd(dp(4));
                c.setLayoutParams(cl);
                return c;
            }

            // ---- تحميل الصورة إلى المعرض (Pictures/Babylon)
            void saveImageMsg(final String url) {
                if (url == null) return;
                toast("جارٍ التحميل…");
                new Thread(() -> {
                    try {
                        byte[] data;
                        if (url.startsWith("http")) {
                            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                            conn.setDoInput(true); conn.connect();
                            java.io.InputStream is = conn.getInputStream();
                            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                            byte[] buf = new byte[8192]; int n;
                            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
                            is.close();
                            data = bo.toByteArray();
                        } else data = android.util.Base64.decode(url, android.util.Base64.DEFAULT);
                        String mime = (data.length > 3 && data[0] == (byte) 0x89 && data[1] == 'P') ? "image/png"
                                : (data.length > 3 && data[0] == 'R' && data[1] == 'I') ? "image/webp" : "image/jpeg";
                        String ext = mime.equals("image/png") ? ".png" : mime.equals("image/webp") ? ".webp" : ".jpg";
                        String name = "Babylon_" + System.currentTimeMillis() + ext;
                        if (Build.VERSION.SDK_INT >= 29) {
                            android.content.ContentValues cv = new android.content.ContentValues();
                            cv.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name);
                            cv.put(android.provider.MediaStore.Images.Media.MIME_TYPE, mime);
                            cv.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Babylon");
                            Uri u = act.getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                            java.io.OutputStream os = act.getContentResolver().openOutputStream(u);
                            os.write(data); os.close();
                        } else {
                            File dir = new File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), "Babylon");
                            dir.mkdirs();
                            File f = new File(dir, name);
                            java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
                            fo.write(data); fo.close();
                            android.media.MediaScannerConnection.scanFile(act, new String[]{f.getAbsolutePath()}, new String[]{mime}, null);
                        }
                        act.runOnUiThread(() -> toast("تم حفظ الصورة في المعرض"));
                    } catch (Throwable t) { act.runOnUiThread(() -> toast("تعذر التنزيل")); }
                }).start();
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
                db.collection("chats").document(currentChatId).set(chatDoc, com.google.firebase.firestore.SetOptions.merge());   // merge: لا تُمسح pins
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
                        final String b64 = encodeImage(uri, 1080, 450 * 1024, true);   // 1080×1080 عالية الدقة (حد Firestore للمستند 1MiB)
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
                d.setAntiAlias(true);
                d.setMipMap(true);          // تصغير 1080→حجم العرض بدون تسنّن
                photoBmp.setHasMipMap(true);
                t.setBackground(t == tv("pAvatarText") ? withRing(d) : d);
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
                if (!isRecording && !recPreview) startRecord();
            }

            // ---------------- التسجيل الصوتي: مطابق لـ #recordingBar في الـ HTML (تسجيل ← معاينة ← إرسال)
            boolean recPreview = false, recPlaying = false, forceAac = false;
            long recDurMs = 0;
            MediaPlayer recPlayer = null;
            ValueAnimator recAnim = null;
            long recAnimT0 = 0;
            final java.util.ArrayList<View> waveBars = new java.util.ArrayList<>();
            Runnable recPlayTick = null;

            String fmtRec(long ms) {
                long s = Math.max(0, ms / 1000);
                return String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
            }

            void buildWave() {
                final LinearLayout w = (LinearLayout) v("recWave");
                if (w == null || w.getChildCount() > 0) return;
                int width = w.getWidth() > 0 ? w.getWidth() : act.getResources().getDisplayMetrics().widthPixels / 2;
                int n = Math.max(8, (width + dp(5)) / dp(8));        // عمود 3dp + فراغ 5dp
                waveBars.clear();
                for (int i = 1; i <= n; i++) {
                    View b = new View(act);
                    int h = dp(10);
                    if (i % 3 == 0) h = dp(19);
                    if (i % 5 == 0) h = dp(23);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(3), h);
                    lp.setMarginEnd(dp(5));
                    b.setLayoutParams(lp);
                    b.setBackground(shape(cGold(), 2, 0));
                    w.addView(b);
                    waveBars.add(b);
                }
            }

            void tickWave() {
                double t = (System.nanoTime() - recAnimT0) / 1e9;
                for (int i = 0; i < waveBars.size(); i++) {
                    int k = (i + 1) % 4;                               // 4n+1 → -.2s ، 4n+2 → -.5s ، 4n+3 → -.35s
                    double d = k == 1 ? 0.2 : k == 2 ? 0.5 : k == 3 ? 0.35 : 0;
                    double x = (t + d) / 0.72;
                    double fr = x - Math.floor(x);
                    double vv = (((long) Math.floor(x)) % 2 == 0) ? fr : 1 - fr;   // alternate
                    double e = vv * vv * (3 - 2 * vv);                              // ease-in-out
                    View b = waveBars.get(i);
                    b.setScaleY((float) (0.45 + 0.55 * e));
                    b.setAlpha((float) (0.6 + 0.4 * e));
                }
                View dot = v("recDot");
                if (dot != null && !recPreview) {                      // recordingPulse 1s
                    double ph = t % 1.0;
                    double pu = ph < 0.5 ? ph * 2 : 2 - 2 * ph;
                    dot.setAlpha((float) (1 - 0.65 * pu));
                    dot.setScaleX((float) (1 - 0.2 * pu));
                    dot.setScaleY((float) (1 - 0.2 * pu));
                }
            }

            void startRecAnim() {
                if (recAnim != null) return;
                recAnimT0 = System.nanoTime();
                recAnim = ValueAnimator.ofFloat(0f, 1f);
                recAnim.setDuration(1000);
                recAnim.setRepeatCount(ValueAnimator.INFINITE);
                recAnim.setInterpolator(new android.view.animation.LinearInterpolator());
                recAnim.addUpdateListener(a -> tickWave());
                recAnim.start();
            }

            void stopRecAnim() {
                if (recAnim != null) { recAnim.cancel(); recAnim = null; }
                for (View b : waveBars) { b.setScaleY(0.8f); b.setAlpha(0.85f); }   // .rec-preview: scaleY(.8) opacity .85
                View dot = v("recDot");
                if (dot != null) { dot.setAlpha(1f); dot.setScaleX(1f); dot.setScaleY(1f); }
            }

            void setRecUi() {
                boolean pv = recPreview;
                vis("recPlay", pv); vis("recStop", !pv); vis("recSend", pv);
                TextView lb = tv("recLabel");
                if (lb != null) { lb.setText(pv ? "تم التسجيل" : "جاري التسجيل"); lb.setTextColor(pv ? 0xFF000000 : 0xFFF87171); }
                View dot = v("recDot");
                if (dot != null) {
                    GradientDrawable g = new GradientDrawable();
                    g.setShape(GradientDrawable.OVAL);
                    g.setColor(pv ? (recPlaying ? 0xFF22C55E : 0xFF9AA4B2) : 0xFFF87171);
                    dot.setBackground(g);
                }
                TextView c = tv("recCancel");
                if (c != null) {
                    ViewGroup.LayoutParams lp = c.getLayoutParams();
                    lp.height = dp(pv ? 23 : 36);
                    c.setLayoutParams(lp);
                }
                TextView p = tv("recPlay");
                if (p != null) {
                    p.setCompoundDrawablesRelativeWithIntrinsicBounds(recPlaying ? R.drawable.ic_rec_pause : R.drawable.ic_rec_play, 0, 0, 0);
                    tintIcons(p);
                }
                if (!pv || recPlaying) startRecAnim();
                else stopRecAnim();
            }

            /** يبدّل بين حقل الكتابة وشريط التسجيل (الـ HTML يخفي textInputBar أثناء التسجيل). */
            void showRecordingUi(boolean on) {
                vis("recordingBar", on);
                vis("textInputBar", !on);
                if (on) {
                    recPreview = false; recPlaying = false;
                    TextView rt = tv("recTimer");
                    if (rt != null) rt.setText("00:00");
                    final View w = v("recWave");
                    if (w != null) w.post(() -> { buildWave(); setRecUi(); });
                    setRecUi();
                } else {
                    if (recAnim != null) { recAnim.cancel(); recAnim = null; }
                }
            }

            void releaseRecPlayer() {
                if (recPlayTick != null) recHandler.removeCallbacks(recPlayTick);
                if (recPlayer != null) {
                    try { recPlayer.stop(); } catch (Exception ignored) { }
                    try { recPlayer.release(); } catch (Exception ignored) { }
                    recPlayer = null;
                }
                recPlaying = false;
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
                    final boolean ogg = Build.VERSION.SDK_INT >= 29 && !forceAac;   // OGG/Opus يتطلب أندرويد 10+، وما دونه يبقى AAC
                    audioFile = new File(dir, "rec_" + System.currentTimeMillis() + (ogg ? ".ogg" : ".m4a"));

                    if (Build.VERSION.SDK_INT >= 31) recorder = new MediaRecorder(act);
                    else recorder = new MediaRecorder();
                    recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                    if (ogg) {
                        recorder.setOutputFormat(MediaRecorder.OutputFormat.OGG);
                        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS);
                        recorder.setAudioEncodingBitRate(64000);   // 64kbps أحادي = جودة شبه شفافة للصوت، ودقيقة كاملة ≈ 640KB base64 (حد Firestore 1MB)
                        recorder.setAudioChannels(1);
                        recorder.setAudioSamplingRate(48000);   // 48kHz هو أعلى تردد يدعمه Opus (ولا يدعم 44100)
                    } else {
                        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                        recorder.setAudioEncodingBitRate(64000);
                        recorder.setAudioChannels(1);
                        recorder.setAudioSamplingRate(44100);   // مدعوم في كل الأجهزة
                    }
                    recorder.setOutputFile(audioFile.getAbsolutePath());
                    recorder.prepare();
                    recorder.start();
                    isRecording = true;
                    recordStart = System.currentTimeMillis();

                    hideKb();
                    showRecordingUi(true);
                    recTick = new Runnable() {
                        @Override public void run() {
                            if (!isRecording) return;
                            long ms = System.currentTimeMillis() - recordStart;
                            TextView rt = tv("recTimer");
                            if (rt != null) rt.setText(fmtRec(ms));
                            if (ms >= 60000) { toast("وصلت الحد الأقصى للتسجيل (دقيقة)"); stopRecordPreview(); return; }
                            recHandler.postDelayed(this, 250);
                        }
                    };
                    recHandler.post(recTick);
                } catch (Exception e) {
                    try { if (recorder != null) recorder.release(); } catch (Exception ignored) { }
                    recorder = null; isRecording = false;
                    if (Build.VERSION.SDK_INT >= 29 && !forceAac) {   // جهاز لا يدعم OGG/Opus فعلياً: نكمل بـ AAC تلقائياً
                        forceAac = true;
                        startRecord();
                        return;
                    }
                    toast("فشل بدء التسجيل: " + e.getMessage());
                    showRecordingUi(false);
                }
            }

            /** زر الإيقاف: ينهي التسجيل وينتقل لوضع الاستماع قبل الإرسال. */
            void stopRecordPreview() {
                if (recorder == null) return;
                long dur = System.currentTimeMillis() - recordStart;
                try { recorder.stop(); } catch (Exception e) { dur = 0; }
                try { recorder.release(); } catch (Exception e) { }
                recorder = null;
                isRecording = false;
                if (recTick != null) recHandler.removeCallbacks(recTick);
                if (audioFile == null || !audioFile.exists() || dur < 800) {
                    if (audioFile != null) audioFile.delete();
                    showRecordingUi(false);
                    toast("التسجيل قصير جداً");
                    return;
                }
                recDurMs = dur;
                recPreview = true; recPlaying = false;
                try {
                    recPlayer = new MediaPlayer();
                    recPlayer.setDataSource(audioFile.getAbsolutePath());
                    recPlayer.prepare();
                    recPlayer.setOnCompletionListener(mp -> {
                        recPlaying = false;
                        if (recPlayTick != null) recHandler.removeCallbacks(recPlayTick);
                        try { mp.seekTo(0); } catch (Exception ignored) { }
                        TextView rt = tv("recTimer");
                        if (rt != null) rt.setText(fmtRec(recDurMs));
                        setRecUi();
                    });
                } catch (Exception e) { recPlayer = null; }
                TextView rt = tv("recTimer");
                if (rt != null) rt.setText(fmtRec(recDurMs));
                setRecUi();
            }

            void toggleRecPlay() {
                if (recPlayer == null) return;
                if (recPlaying) {
                    try { recPlayer.pause(); } catch (Exception ignored) { }
                    recPlaying = false;
                    if (recPlayTick != null) recHandler.removeCallbacks(recPlayTick);
                } else {
                    try { recPlayer.start(); } catch (Exception ignored) { return; }
                    recPlaying = true;
                    recPlayTick = new Runnable() {
                        @Override public void run() {
                            if (!recPlaying || recPlayer == null) return;
                            TextView rt = tv("recTimer");
                            if (rt != null) rt.setText(fmtRec(recPlayer.getCurrentPosition()));
                            recHandler.postDelayed(this, 200);
                        }
                    };
                    recHandler.post(recPlayTick);
                }
                setRecUi();
            }

            /** زر الإرسال في وضع المعاينة. */
            void sendRecording() {
                if (!recPreview || audioFile == null || !audioFile.exists()) return;
                releaseRecPlayer();
                recPreview = false;
                showRecordingUi(false);
                uploadAudio(audioFile, recDurMs);
            }

            /** زر × : إلغاء التسجيل أو المعاينة وحذف الملف. */
            void cancelRecord() {
                if (recorder != null) {
                    try { recorder.stop(); } catch (Exception e) { }
                    try { recorder.release(); } catch (Exception e) { }
                    recorder = null;
                }
                isRecording = false;
                if (recTick != null) recHandler.removeCallbacks(recTick);
                releaseRecPlayer();
                recPreview = false;
                if (audioFile != null) audioFile.delete();
                showRecordingUi(false);
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

            /** كتم الأشخاص محلياً على الجهاز (مثل babylon_muted_users في الـ HTML). */
            boolean isMutedUid(String uid) {
                return uid != null && uid.length() > 0 && sp.getStringSet("muted_users", new java.util.HashSet<String>()).contains(uid);
            }

            void toggleMuteUid(String uid) {
                java.util.Set<String> set = new java.util.HashSet<>(sp.getStringSet("muted_users", new java.util.HashSet<String>()));
                if (!set.remove(uid)) set.add(uid);
                sp.edit().putStringSet("muted_users", set).apply();
            }

            /** صف خيار القائمة (babylon-chat-more-menu-pro): [سهم ‹] [النص] [أيقونة في مربع ذهبي] — الترتيب مضبوط يدوياً (RTL غير مفعّل). */
            View chatMenuItem(int iconRes, String label) {
                LinearLayout row = new LinearLayout(act);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(dp(54));
                row.setPadding(dp(10), dp(8), dp(10), dp(8));
                GradientDrawable pr = new GradientDrawable(); pr.setColor(goldA(0x26)); pr.setCornerRadius(dp(15));
                android.graphics.drawable.StateListDrawable sl = new android.graphics.drawable.StateListDrawable();
                sl.addState(new int[]{android.R.attr.state_pressed}, pr);
                row.setBackground(sl);
                row.setClickable(true);

                ImageView chev = new ImageView(act);
                chev.setImageResource(R.drawable.ic_menu_chevron_left);
                chev.setColorFilter(cText());
                chev.setAlpha(0.32f);
                row.addView(chev, new LinearLayout.LayoutParams(dp(16), dp(16)));

                TextView t = new TextView(act);
                t.setText(label);
                t.setTextSize(15);
                t.setTypeface(null, android.graphics.Typeface.BOLD);
                t.setTextColor(cText());
                t.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
                tl.setMargins(dp(8), 0, dp(12), 0);
                row.addView(t, tl);

                ImageView ic = new ImageView(act);
                ic.setImageResource(iconRes);
                ic.setColorFilter(cGold());
                ic.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                ic.setPadding(dp(8), dp(8), dp(8), dp(8));
                GradientDrawable ibg = new GradientDrawable(); ibg.setColor(goldA(0x24)); ibg.setCornerRadius(dp(13));
                ic.setBackground(ibg);
                row.addView(ic, new LinearLayout.LayoutParams(dp(36), dp(36)));
                return row;
            }

            /**
             * قائمة ⋮ (babylon-chat-more-menu-pro): بطاقة بانحناء 22 وحد رمادي ناعم، تنبثق من زر الثلاث نقاط نفسه
             * (تلتصق بحافته من جهته وتتمدد من نقطة منتصفه)، وعناصرها تدخل متتابعة.
             */
            void chatMenu() {
                final View cm = v("chatMore");
                final View host = v("chatView");
                if (cm == null || host == null) return;
                final boolean mutedNow = isMutedUid(otherUid);
                final int screenW = act.getResources().getDisplayMetrics().widthPixels;
                final int popW = Math.min(dp(220), screenW - dp(24));

                LinearLayout card = new LinearLayout(act);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
                card.setPadding(dp(8), dp(4), dp(8), dp(4));
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(dark ? 0xFF17212B : 0xFFFFFFFF);
                bg.setCornerRadius(dp(22));
                bg.setStroke(dp(1), dark ? 0xFF2B3A4C : 0xFFDCE4EE);
                card.setBackground(bg);
                final android.widget.PopupWindow pw = new android.widget.PopupWindow(card, popW,
                        ViewGroup.LayoutParams.WRAP_CONTENT, true);
                pw.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
                pw.setOutsideTouchable(true);

                View mute = chatMenuItem(mutedNow ? R.drawable.ic_menu_bell : R.drawable.ic_menu_bell_off,
                        mutedNow ? "إلغاء كتم المحادثة" : "كتم المحادثة");
                mute.setOnClickListener(x -> {
                    pw.dismiss();
                    if (otherUid == null || otherUid.isEmpty()) return;
                    toggleMuteUid(otherUid);
                    muted = !mutedNow;
                    toast(mutedNow ? "تم إلغاء كتم المحادثة" : "تم كتم هذا الشخص على جهازك");
                });
                card.addView(mute);

                View search = chatMenuItem(R.drawable.ic_menu_search, "البحث عن رسالة");
                search.setOnClickListener(x -> {
                    pw.dismiss();
                    vis("chatSearchPanel", true);
                    EditText et1 = et("chatMessageSearchInput");
                    if (et1 != null) { et1.setText(""); et1.requestFocus(); }
                });
                LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(-1, -2);
                sLp.topMargin = dp(2);
                card.addView(search, sLp);

                // ---- الموضع: ملتصقة بحافة زر ⋮ من جهته (يسار/يمين الشاشة) وتحته مباشرة
                int[] loc = new int[2];
                cm.getLocationInWindow(loc);
                final int bw = cm.getWidth();
                final boolean leftSide = loc[0] + bw / 2f < screenW / 2f;
                final int margin = dp(12);
                int xoff, gravity;
                final float popLeft;
                if (leftSide) {
                    xoff = Math.max(0, margin - loc[0]);
                    gravity = Gravity.START;
                    popLeft = loc[0] + xoff;
                } else {
                    xoff = Math.min(0, (screenW - margin) - (loc[0] + bw));
                    gravity = Gravity.END;
                    popLeft = loc[0] + bw + xoff - popW;
                }
                final float pivotX = (loc[0] + bw / 2f) - popLeft;   // منتصف زر ⋮ داخل البطاقة
                pw.showAsDropDown(cm, xoff, dp(2), gravity);

                // ---- حركة الانبثاق من الزر: تمدد من نقطة منتصفه مع ظهور تدريجي، وعناصر تدخل متتابعة
                card.setPivotX(pivotX);
                card.setPivotY(0f);
                card.setAlpha(0f); card.setScaleX(0.35f); card.setScaleY(0.35f);
                card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(230)
                        .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0.8f, 0.3f, 1f)).start();
                for (int i = 0; i < card.getChildCount(); i++) {
                    View it = card.getChildAt(i);
                    it.setAlpha(0f); it.setTranslationY(-dp(6));
                    it.animate().alpha(1f).translationY(0f).setStartDelay(70 + i * 45L).setDuration(200).start();
                }
            }

            void searchInChat(String q) {
                LinearLayout a = (LinearLayout) v("messagesArea");
                if (a == null) return;
                q = q.trim().toLowerCase();
                int found = 0; View first = null;
                for (int i = 0; i < a.getChildCount(); i++) {
                    View c = a.getChildAt(i);
                    boolean hit = q.length() > 0 && String.valueOf(c.getContentDescription()).toLowerCase().contains(q);
                    c.setAlpha(q.length() == 0 || hit ? 1f : 0.3f);
                    if (hit) { found++; if (first == null) first = c; }
                }
                TextView csc = tv("csCount");
                if (csc != null) csc.setText(q.length() == 0 ? "" : (found + " نتيجة"));
                View ms = v("messagesScroll");
                if (first != null && ms != null) ((ScrollView) ms).smoothScrollTo(0, first.getTop());
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
                if (id.equals("hdrStories")) toast("القصص");
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
                else if (id.equals("pCover")) openCoverPicker();
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
                    tabStories = true; vis("pTabStories", true);
                    setTab(v("pTabStoriesBtn"), true);
                }
                else if (id.equals("otherMsgBtn")) openChat(otherName, otherUid);
                else if (id.equals("otherBlockBtn")) {
                    blocked = !blocked;
                    quick("otherBlockBtn", blocked ? "ic_check" : "ic_block"); { TextView ob = tv("otherBlockBtn"); if (ob != null) ob.setText(blocked ? "إلغاء الحظر" : "حظر"); }
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
                    View tg = v("settingsThemeToggle");
                    int[] loc = new int[2];
                    tg.getLocationOnScreen(loc);
                    BabylonFx.themeReveal(act, v("root"), loc[0] + tg.getWidth() / 2f, loc[1] + tg.getHeight() / 2f, bars, sbTop, navBottom, () -> {
                        dark = !dark;
                        sp.edit().putBoolean("dark", dark).apply();
                        applyTheme();
                    });
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
                else if (id.equals("csX")) {
                    vis("chatSearchPanel", false);
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
                else if (id.equals("recSend")) sendRecording();
                else if (id.equals("recStop")) stopRecordPreview();
                else if (id.equals("recPlay")) toggleRecPlay();
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
                            t.setTextSize(14);
                            t.setTypeface(null, android.graphics.Typeface.NORMAL);
                            t.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
                            t.setTextDirection(View.TEXT_DIRECTION_RTL);
                            t.setPadding(0, 0, 0, 0);
                            return t;
                        }
                        @Override
                        public View getDropDownView(int pos, View cv, ViewGroup parent) {
                            TextView t = (TextView) super.getDropDownView(pos, cv, parent);
                            t.setTextColor(cText());
                            t.setTextSize(14);
                            t.setTypeface(null, android.graphics.Typeface.NORMAL);
                            t.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
                            t.setTextDirection(View.TEXT_DIRECTION_RTL);
                            t.setBackgroundColor(dark ? 0xFF1D2A38 : 0xFFFFFFFF);
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
                        "chatMore", "csX", "replyCancel", "btnSend", "btnMic", "btnAttach", "recSend", "recCancel", "recStop", "recPlay"};
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

                setSeg("segLastSeen", sp.getBoolean("hideLast", false));
                setSeg("segAvatar", sp.getBoolean("hideAv", false));
                setOnOff("push", sp.getBoolean("push", true));
                setOnOff("sound", sp.getBoolean("sound", true));
                applyBubbles();
                applyTheme();

                loadMyProfile();
                loadChats();
                show("homeView");
                try {
                    ProfileHero.Palette pal = new ProfileHero.Palette() {
                        @Override public int surface() { return cSf(); }
                        @Override public int text() { return cText(); }
                        @Override public int gold() { return cGold(); }
                        @Override public int red() { return cRed(); }
                        @Override public void restyle() { polishProfile(); }
                    };
                    heroMe = new ProfileHero(act, (PullScrollView) v("profileView"), tv("pAvatarText"), v("linear643"),
                            tv("pHeaderName"), tv("pHeaderStatus"), v("linear644"), true, () -> photoBmp, null, null, pal);
                    heroOther = new ProfileHero(act, (PullScrollView) v("otherUserProfileView"), tv("otherAvatarText"), v("linear658"),
                            tv("otherHeaderName"), tv("otherHeaderStatus"), v("linear659"), false, () -> null,
                            v("otherBack"), v("otherMoreBtn"), pal);
                    heroMe.setCoverTheme(CoverThemes.byId(coverId));
                    heroOther.setCoverTheme(CoverThemes.byId(otherCoverId));
                    heroMe.setAvatarInset(ringTotalPx());       // الحلقة الذهبية خارج الصورة: المربع المكبّر يبدأ من حدود الصورة نفسها
                    heroOther.setAvatarInset(ringTotalPx());
                } catch (Throwable ignored) { }
                try {
                    BabylonFx.shine(tv("textview621"));
                    BabylonFx.fabIn(v("newGroupFab"));
                    BabylonFx.barUp(v("bottomNav"), 60);
                    for (String pid : new String[]{"navChats", "navSettings", "navProfile", "mainSearchBtn", "hdrStories", "fltAll", "fltUnread"})
                        BabylonFx.press(v(pid), 0.94f);
                    BabylonFx.press(v("newGroupFab"), 0.92f);
                    setupComposer();
                    setupChatGlass();
                    BabylonFx.press(v("btnSend"), 0.9f);
                    BabylonFx.press(v("btnMic"), 0.9f);
                    BabylonFx.press(v("settingsThemeToggle"), 0.95f);
                } catch (Throwable ignored) { }
            }
        }

        UI ui = new UI();
        ui.init();
        backHook = ui::onBack;

        ui.bars.setup();
        ui.syncBars();

        final View rootView = findViewById(R.id.root);
        if (rootView != null) {
            rootView.setOnApplyWindowInsetsListener((v, insets) -> {
                int top = insets.getSystemWindowInsetTop();
                int bottom = insets.getSystemWindowInsetBottom();           // شريط النظام + لوحة المفاتيح إن ظهرت
                int nav = Math.min(bottom, insets.getStableInsetBottom());   // شريط الإيماءات/الأزرار فقط: 0 في الهواتف التي لا تملك شريطاً
                boolean topChanged = top != ui.sbTop;
                ui.sbTop = top;
                ui.navBottom = nav;
                ui.padBottom = bottom;
                ui.applyInsets();                       // صفحات الملف الشخصي بلا padding علوي (الغلاف خلف الشريط)
                if (topChanged) ui.polishProfile();     // ارتفاع الغلاف = 176dp + ارتفاع شريط الحالة
                return insets;
            });
            rootView.requestApplyInsets();
        }
    }
}