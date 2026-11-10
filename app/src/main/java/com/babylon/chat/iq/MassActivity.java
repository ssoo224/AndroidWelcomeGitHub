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
import android.widget.HorizontalScrollView;
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

    private StoryComposer storyComposer;                              // نشر قصة (StoryComposer.java)
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
            try { LocalCache.init(this, myUid); } catch (Throwable ignored) { }
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
            try {   // التلميح فقط بخط عادي (النص المكتوب يبقى غامقاً)
                android.widget.EditText mi0 = (android.widget.EditText) findViewById(R.id.messageInput);
                if (mi0 != null && mi0.getHint() != null) {
                    android.text.SpannableString hs = new android.text.SpannableString(mi0.getHint().toString());
                    hs.setSpan(new android.text.style.TypefaceSpan("sans-serif"), 0, hs.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    hs.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.NORMAL), 0, hs.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);   // يلغي الغامق الموروث من makeAllTextBold
                    mi0.setHint(hs);
                }
            } catch (Throwable ignored) { }
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
        try { if (StoryViewer.onActivityResult(requestCode, resultCode, data)) return; } catch (Throwable ignored) { }
        try { if (storyComposer != null && storyComposer.onActivityResult(requestCode, resultCode, data)) return; } catch (Throwable ignored) { }
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
            int fontSp = Math.max(ChatThemes.FONT_MIN, Math.min(ChatThemes.FONT_MAX, sp.getInt("font", ChatThemes.FONT_DEF)));
            int radius = Math.max(ChatThemes.RADIUS_MIN, Math.min(ChatThemes.RADIUS_MAX, sp.getInt("radius", ChatThemes.RADIUS_DEF)));
            String chatTheme = ChatThemes.byId(sp.getString("chatTheme", ChatThemes.DEFAULT_ID)).id;   // prefs.theme (babylon افتراضي)
            ChatPreview chatPreview;
            boolean prefsFromCloudDone = false;
            Runnable prefsSyncRun;
            String coverId = CoverThemes.byId(sp.getString("coverId", CoverThemes.DEFAULT_ID)).id;   // غلافي (users/{uid}.cover)
            String otherCoverId = CoverThemes.DEFAULT_ID;                                            // غلاف الشخص المعروض
            boolean unreadOnly = false, muted = false, blocked = false;
            // --- الحظر وجهات الدردشة (مطابق للـ HTML) ---
            java.util.HashSet<String> myBlocks = new java.util.HashSet<>();
            boolean blockedByThem = false, profileFromChat = false, otherVerified = false;
            com.google.firebase.firestore.ListenerRegistration blocksReg, contactsReg;
            java.util.HashMap<String, String> contacts = new java.util.HashMap<>();
            String otherOrigName = "", dlgValue = "";
            LinearLayout blkBanner;
            String curPage = "homeView", prevPage = "homeView", curChat = "";
            String replyText = null, otherName = "", otherUser = "";
            String replyId = null, replyKind = "text"; boolean replyMine = false;   // حقول الرد بصيغة الـ HTML
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
            SearchPage searchPage;   // صفحة «بحث المستخدمين» (SearchPage.java)

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

            StoriesController stories;
            final java.util.HashMap<String, Long> storyUntil = new java.util.HashMap<>();
            final java.util.HashMap<String, Boolean> onlineMap = new java.util.HashMap<>();
            long myStoryUntil = 0;
            ArrayList<String[]> chatData = new ArrayList<>();
            // ---- تحديد المحادثات وحذفها (مطابق لـ initChatSelection / deleteChats في الـ HTML) ----
            boolean chatSelMode = false;
            final java.util.LinkedHashSet<String> chatSel = new java.util.LinkedHashSet<>();          // uid الطرف الآخر
            final java.util.HashMap<String, String> chatIdByUid = new java.util.HashMap<>();
            final java.util.HashMap<String, Long> chatClearedAll = new java.util.HashMap<>();         // chats/{id}.clearedAll
            final String PIN_KEY = "babylon_pinned_chats", CLR_KEY = "babylon_cleared_chats";
            final int MAX_CHAT_PINS = 5;
            java.util.HashMap<String, Long> clrMem = null;
            boolean chatStateLoaded = false;
            Runnable chatStateRun;
            FrameLayout chatSelBar;
            TextView chatSelCountTv;
            ImageView chatSelPinIv, chatSelXIv, chatSelDelIv;
            String userName = "", userUsername = "", userBio = "", userGov = "", userEmail = "", userJoin = "";
            String userPhoto = "", shownPhoto = "";
            Bitmap photoBmp = null;
            Bitmap otherPhotoBmp = null;                // صورة الشخص المعروض (بروفايل الشخص الآخر)

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
            /** زر ذهبي موحّد: نفس تدرج وألوان زر «حفظ التعديلات» */
            GradientDrawable goldShape(float rDp) {
                GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFFF5D875, 0xFFB98B23});
                g.setCornerRadius(dp(rDp));
                return g;
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
            /** دمج لون أمامي فوق لون أساس معتم وإرجاع لون معتم بالكامل. */
            int androidx_blend(int base, int fg, float a) {
                int r = Math.round(((base >> 16) & 0xFF) * (1 - a) + ((fg >> 16) & 0xFF) * a);
                int g = Math.round(((base >> 8) & 0xFF) * (1 - a) + ((fg >> 8) & 0xFF) * a);
                int b = Math.round((base & 0xFF) * (1 - a) + (fg & 0xFF) * a);
                return 0xFF000000 | (r << 16) | (g << 8) | b;
            }
            int cRed() { return dark ? 0xFFFB7185 : 0xFFDC2626; }
            int cMine() { return dark ? 0xFF2B5278 : 0xFFD9FDD3; }
            int cOther() { return dark ? 0xFF182533 : 0xFFFFFFFF; }
            int cChatBg() { return Tk.chatSurface(dark); }

            /** حقل الكتابة: #f1f5fa / rgba(127,140,160,.10) ، عند التركيز أبيض / .16 وحدّ #c9cfd8 / #8b94a1 ، انحناء 15 */
            GradientDrawable msgInpBg(boolean focus) {
                GradientDrawable g = new GradientDrawable();
                if (Tk.chatPal != null) {   // الطورين الليلي والنهاري: ألوان الثيم (داخل شريط الكتابة --cr-card)
                    g.setColor(Tk.chatPal.chatBg);
                    g.setCornerRadius(dp(15));
                    g.setStroke(dp(1), focus ? ((Tk.chatPal.accent & 0x00FFFFFF) | 0x99000000) : 0x00000000);
                    return g;
                }
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

            /** عبارة «اكتب رسالتك...» (hint) في اليسار فقط؛ وعند الكتابة يعود النص لاتجاهه الطبيعي. */
            void hintLeft(EditText mi) {
                boolean empty = mi.getText().length() == 0;
                int g = empty ? (Gravity.CENTER_VERTICAL | Gravity.LEFT) : (Gravity.CENTER_VERTICAL | Gravity.START);
                if (mi.getGravity() != g) mi.setGravity(g);
                int ta = empty ? View.TEXT_ALIGNMENT_GRAVITY : View.TEXT_ALIGNMENT_VIEW_START;
                if (mi.getTextAlignment() != ta) mi.setTextAlignment(ta);
                int td = empty ? View.TEXT_DIRECTION_LTR : View.TEXT_DIRECTION_FIRST_STRONG;
                if (mi.getTextDirection() != td) mi.setTextDirection(td);
            }

            void setupComposer() {
                final EditText mi = et("messageInput");
                if (mi == null) return;
                mi.addTextChangedListener(new android.text.TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
                    @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
                    @Override public void afterTextChanged(android.text.Editable e) { syncSend(); hintLeft(mi); }
                });
                hintLeft(mi);
                // ملصقات/GIF/صور من لوحة المفاتيح
                if (mi instanceof RichEditText) ((RichEditText) mi).setOnRichContent((uri, mime, release) -> sendKeyboardContent(uri, mime, release));
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
                            if (T != null) {
                                boolean th = Tk.chatPal != null;   // الليلي والنهاري يتبعان الثيم
                                T.setTextColor(th ? Tk.crInk(cText()) : cText());
                                T.setHintTextColor(th ? Tk.crMuted(cMuted()) : cMuted());
                            }
                        }
                        else if (k.equals("gold")) {
                            boolean chip = x.getId() == R.id.fltAll || x.getId() == R.id.fltUnread;
                            if (chip) {
                                x.setBackground(chipGold(true));
                                x.setAlpha(1f);
                            } else x.setBackground(goldShape(rad(x)));
                            if (T != null) {
                                T.setTextColor(0xFFFFFFFF);
                                for (android.graphics.drawable.Drawable dd : T.getCompoundDrawablesRelative())
                                    if (dd != null) dd.mutate().setColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
                            }
                        }
                        else if (k.equals("goldo")) {
                            boolean chipO = x.getId() == R.id.fltAll || x.getId() == R.id.fltUnread;
                            if (chipO) {                       // .cl-chip غير النشط: سطح #2a3a4d / #e3e9f1
                                GradientDrawable ig = new GradientDrawable();
                                ig.setCornerRadius(dp(20)); ig.setColor(dark ? 0xFF2A3A4D : 0xFFE3E9F1);
                                x.setBackground(ig);
                                x.setAlpha(1f);
                                if (T != null) T.setTextColor(dark ? 0xFFC5D0DC : 0xFF3A4757);
                            } else { x.setBackground(shape(0, rad(x), cGold())); if (T != null) T.setTextColor(cGold()); }
                        }
                        else if (k.equals("redo")) { x.setBackground(shape(0, 12, cRed())); if (T != null) T.setTextColor(cRed()); }
                        else if (k.equals("circ")) {
                            GradientDrawable g = new GradientDrawable();
                            g.setShape(GradientDrawable.OVAL); g.setColor(cGold()); x.setBackground(g);
                            if (x.getId() == R.id.sxAvatar) x.setBackground(Tk.ringAvatar(x, dark));
                            if (x.getId() == R.id.chatAvatar) { x.setBackground(Tk.chatAvatar(x, dark)); if (T != null) T.setTextColor(dark ? 0xFF17212B : 0xFFFFFFFF); try { applyChatPhoto(); } catch (Throwable ignored) { } }   // النهاري: الحرف أبيض
                            if (photoBmp != null && x instanceof TextView && (x == v("pAvatarText") || x == v("sxAvatar"))) applyPhotoTo((TextView) x);
                            if (otherPhotoBmp != null && x instanceof TextView && x == v("otherAvatarText")) applyOtherPhotoTo((TextView) x);
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
                            if (Tk.chatPal != null) {            // ثيم غير بابل: --bubble-mine / --text-mine
                                x.setBackground(ChatThemes.bubble(Tk.chatPal, true, new float[]{rr, rr, rr, rr, tl, tl, rr, rr}, 0f));
                                if (T != null) T.setTextColor(Tk.chatPal.textMine);
                            } else {
                            GradientDrawable mg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFFE8C85D, 0xFFCBA843});
                            mg.setCornerRadii(new float[]{rr, rr, rr, rr, tl, tl, rr, rr});   // الذيل أسفل اليمين كما في الـ HTML
                            x.setBackground(mg);
                            if (T != null) T.setTextColor(0xFFFFFFFF);
                            }
                        } else if (k.equals("bubOther")) {
                            float rr = dp(radius), tl = dp(6);
                            if (Tk.chatPal != null) {            // ثيم غير بابل: --bubble-other + حدّ --bubble-line + --text-other
                                x.setBackground(ChatThemes.bubble(Tk.chatPal, false, new float[]{rr, rr, rr, rr, rr, rr, tl, tl}, dp(1)));
                                if (T != null) T.setTextColor(Tk.chatPal.textOther);
                            } else {
                            GradientDrawable og = new GradientDrawable();
                            og.setColor(dark ? 0xFF263746 : 0xFFFFFFFF);
                            og.setCornerRadii(new float[]{rr, rr, rr, rr, rr, rr, tl, tl});   // الذيل أسفل اليسار كما في الـ HTML
                            x.setBackground(og);
                            if (T != null) T.setTextColor(cText());
                            }
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
                            if (k.equals("t")) T.setTextColor((x.getTag() instanceof String && ((String) x.getTag()).contains("rbtn")) || x.getId() == R.id.chatTitle ? Tk.crInk(cText()) : cText());
                            else if (k.equals("m")) T.setTextColor(x.getId() == R.id.chatSub || (Tk.chatPal != null && (x.getId() == R.id.btnMic || x.getId() == R.id.btnAttach)) ? Tk.crMuted(cMuted()) : cMuted());
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
                        new int[]{0xFFF5D875, 0xFFB98B23});
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
                centerIconWithText(t);
                t.requestLayout();
            }

            /** أيقونة drawableStart تلتصق بحافة الزر عند centered؛ نجمع الأيقونة والنص في مجموعة واحدة في منتصف الزر. */
            void centerIconWithText(final TextView t) {
                if (t.getTag(R.id.pTabStoriesBtn) != null) return;
                t.setTag(R.id.pTabStoriesBtn, Boolean.TRUE);
                t.addOnLayoutChangeListener((vw, l, tp, r, bt, ol, ot, or, ob) -> {
                    android.graphics.drawable.Drawable[] ds = t.getCompoundDrawablesRelative();
                    int w = r - l;
                    if (ds[0] == null || w <= 0) return;
                    int iw = ds[0].getBounds().width() > 0 ? ds[0].getBounds().width() : ds[0].getIntrinsicWidth();
                    int pad = t.getCompoundDrawablePadding();
                    int tw = (int) Math.ceil(t.getPaint().measureText(t.getText().toString()));
                    int side = Math.max(0, (w - (iw + pad + tw)) / 2);
                    if (t.getPaddingStart() != side || t.getPaddingEnd() != side) t.setPaddingRelative(side, 0, side, 0);
                });
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
                name.setShadowLayer(0, 0, 0, 0);
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

            /** حلقة صورة الملف الشخصي كما في الـ HTML: 2dp بلون --pf-bg ثم 4dp حلقة القصة، والصورة 110dp بعدها. */
            int ringTotalPx() { return dp(6); }

            boolean hasStoryFor(boolean mine) {
                long now = System.currentTimeMillis();
                if (mine) return myStoryUntil > now;
                Long u = otherUid == null ? null : storyUntil.get(otherUid);
                return u != null && u > now;
            }

            android.graphics.drawable.Drawable withRing(android.graphics.drawable.Drawable inner, final boolean mine) {
                return new ProfileRing(inner, dp(2), dp(4), dark ? 0xFF0E1621 : 0xFFEEF1F6,
                        () -> hasStoryFor(mine), () -> {
                            String su = mine ? myUid : otherUid; Long ue = mine ? Long.valueOf(myStoryUntil) : storyUntil.get(otherUid);
                            return ue != null && StoryViewer.isSeen(act, myUid, su, ue);
                        });
            }

            /** handleAvatarClick / openOtherStories: الضغط على صورة الملف الشخصي يفتح القصة بقوس دوّار (أو النشر إن لم توجد قصة). */
            void wireProfileAvatarClicks() {
                final TextView mineAv = tv("pAvatarText"), otherAv = tv("otherAvatarText");
                if (mineAv != null) mineAv.setOnClickListener(vw -> {
                    if (hasStoryFor(true)) {
                        android.graphics.drawable.Drawable bg = mineAv.getBackground();
                        CharSequence lt = mineAv.getText();
                        openStory(myUid, "قصتي", lt != null && lt.length() > 0 ? lt.toString().substring(0, 1) : "?", photoBmp, false,
                                bg instanceof ProfileRing ? (StoryViewer.Spin) bg : null);
                    } else startStoryPublish();
                });
                if (otherAv != null) otherAv.setOnClickListener(vw -> {
                    if (otherUid == null || otherUid.isEmpty() || !hasStoryFor(false)) return;
                    android.graphics.drawable.Drawable bg = otherAv.getBackground();
                    String nm = dispName(otherUid, "");
                    openStory(otherUid, nm, nm.length() > 0 ? nm.substring(0, 1).toUpperCase() : "?", otherPhotoBmp, StoryAvatarView.toneBlue(otherUid),
                            bg instanceof ProfileRing ? (StoryViewer.Spin) bg : null);
                });
            }

            void refreshProfileRings() {
                for (String id : new String[]{"pAvatarText", "otherAvatarText"}) { View a = v(id); if (a != null) a.invalidate(); }
            }

            void fixAvatar(String id) {
                TextView a = tv(id);
                if (a == null) return;
                ViewGroup.LayoutParams lp = a.getLayoutParams();
                if (lp != null) { lp.width = dp(110) + 2 * ringTotalPx(); lp.height = lp.width; }   // 110 + حلقة القصة 4 + حلقة الخلفية 2
                a.setTextSize(40);
                a.setTextColor(0xFFFFFFFF);
                if (photoBmp != null && id.equals("pAvatarText")) {
                    applyPhotoTo(a);
                } else if (otherPhotoBmp != null && id.equals("otherAvatarText")) {
                    applyOtherPhotoTo(a);   // كانت تُستبدل بالحرف الذهبي عند كل إعادة تنسيق (مثلاً بعد تصغير الصورة المسحوبة)
                } else {
                    GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{dark ? 0xFFF0D77A : 0xFFE8C25A, dark ? 0xFFC9A227 : 0xFFB88A1C});
                    g.setShape(GradientDrawable.OVAL);
                    a.setBackground(withRing(g, id.equals("pAvatarText")));
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

            void quick(String id, String iconName) { quick(id, iconName, cGold()); }

            void quick(String id, String iconName, int iconColor) {
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
                t.setCompoundDrawablesRelative(null, dr(icon(iconName, 16), 19, iconColor), null, null);
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
                if (kind == 0) { t.setBackground(goldShape(16)); t.setTextColor(0xFFFFFFFF); ic = 0xFFFFFFFF; }
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
                    wireProfileAvatarClicks();
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
                        b.setBackground(null);   // الـ HTML: بلا دائرة خلف السهم والنقاط، أيقونة بيضاء فقط
                        b.setOnTouchListener((tv0, ev0) -> {
                            int a0 = ev0.getActionMasked();
                            if (a0 == android.view.MotionEvent.ACTION_DOWN) tv0.setAlpha(0.6f);
                            else if (a0 == android.view.MotionEvent.ACTION_UP || a0 == android.view.MotionEvent.ACTION_CANCEL) tv0.setAlpha(1f);
                            return false;
                        });
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
                    refreshOtherButtons();
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

            /** ثيم المحادثة الحالي → Tk (يقرؤه رسم الفقاعات والخلفية والشريط الزجاجي). */
            void syncChatTheme() { Tk.setChatTheme(chatTheme, dark); }

            /** مؤشرات الشرائح: ذهبية بحسب الوضع (accent-color: var(--babylon-gold)) */
            void tintSeek(String id) {
                View sv = v(id);
                if (!(sv instanceof SeekBar)) return;
                SeekBar sb = (SeekBar) sv;
                if (Build.VERSION.SDK_INT >= 21) {
                    sb.setProgressTintList(android.content.res.ColorStateList.valueOf(cGold()));
                    sb.setThumbTintList(android.content.res.ColorStateList.valueOf(cGold()));
                    sb.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(dark ? 0x55FFFFFF : 0x33000000));
                }
            }

            void applyTheme() {
                syncChatTheme();
                style(v("root"));
                if (searchPage != null) searchPage.applyTheme();
                View pvBox = v("pvBox");
                if (pvBox instanceof FrameLayout) {
                    if (chatPreview == null) chatPreview = new ChatPreview(act, (FrameLayout) pvBox);
                    chatPreview.apply(chatTheme, dark, fontSp, radius);
                }
                tintSeek("fontRange"); tintSeek("radiusRange");
                TextView themeDesc = tv("themeDescription");
                if (themeDesc != null) {
                    themeDesc.setText(dark ? "الوضع الليلي مفعّل" : "الوضع النهاري مفعّل");
                }
                setLabel("settingsThemeToggle", dark ? "نهاري" : "ليلي", icon(dark ? "ic_sun" : "ic_moon", 16));
                try { buildThemeStrip(); } catch (Throwable ignored) { }
                try { setNav(curPage); } catch (Throwable ignored) { }
                try { buildStories(); buildChats(); } catch (Throwable ignored) { }
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
                // الصفحة الرئيسية تبقى ظاهرة خلف حركة دخول/خروج المحادثة: نُبقي لها إزاحة شريط النظام بنفسها
                // حتى لا تصعد تحت شريط الحالة ولا تنزل تحت أزرار التنقل أثناء الحركة.
                View mp = v("mainPage");
                if (mp != null) {
                    if (chat) mp.setPadding(0, isProfilePage(curPage) ? 0 : sbTop, 0, padBottom);
                    else mp.setPadding(0, 0, 0, 0);
                }
                if (chat) applyChatInsets();
            }

            /** صفحة تعديل الملف: يبقى الحقل المحرَّر (النبذة خصوصاً) فوق لوحة المفاتيح وشريط الإيموجي/الاقتراحات ولا يُغطّى بهما. */
            boolean editKbWired;
            void keepEditVisible() {
                View ep = v("editProfileView");
                if (ep == null || ep.getVisibility() != View.VISIBLE) return;
                View fo = act.getCurrentFocus();
                if (!(fo instanceof EditText)) return;
                final EditText e = (EditText) fo;
                e.postDelayed(() -> {
                    try { e.requestRectangleOnScreen(new android.graphics.Rect(0, 0, e.getWidth(), e.getHeight() + dp(56)), false); }
                    catch (Throwable ignored) { }
                }, 120);
            }

            void wireEditKeyboard() {
                if (editKbWired) return;
                editKbWired = true;
                for (String id : new String[]{"modalNameInput", "modalUsernameInput", "modalBioInput"}) {
                    EditText e = et(id);
                    if (e == null) continue;
                    e.setOnFocusChangeListener((vw, hasF) -> { if (hasF) { keepEditVisible(); vw.postDelayed(this::keepEditVisible, 350); } });
                    e.addTextChangedListener(new android.text.TextWatcher() {
                        @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
                        @Override public void onTextChanged(CharSequence c, int a, int b, int d) { }
                        @Override public void afterTextChanged(android.text.Editable ed) { keepEditVisible(); }
                    });
                }
            }

            /** حواف المحادثة: الرأس ينزل بقدر شريط الإشعارات (+4dp) ، والمدخل يترك مسافة سفلية كما في .chat-input-container. */
            void applyChatInsets() {
                View tb = v("chatTopBar"), bb = v("chatBottomBar");
                if (tb != null) tb.setPadding(0, sbTop + dp(4), 0, 0);
                if (bb != null) {
                    // HTML: padding-bottom = max(6px, مساحة الإيماءات) ، و4px أثناء ظهور لوحة المفاتيح.
                    // مساحة الإيماءات هنا مأخوذة أصلاً من padding الجذر، فنكمّل الفرق فقط.
                    boolean kb = padBottom - navBottom > dp(100);
                    if (blkBanner != null) {
                        // لافتة الحظر: تمتد هي نفسها إلى شريط الإيماءات (نفس اللون) بدل أن يظهر الزجاج تحتها
                        bb.setPadding(0, 0, 0, 0);
                        blkBanner.setPadding(dp(16), dp(18), dp(16), dp(16) + (kb ? 0 : navBottom));
                    } else
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
                // ظهور لوحة المفاتيح يصغّر منطقة الرسائل: إن كنتَ قرب آخر الرسائل تبقى ظاهرة فوق اللوحة، وإن كنتَ بعيداً لا يتغير موضعك
                final View msK = v("messagesScroll");
                if (msK instanceof ScrollView) {
                    msK.addOnLayoutChangeListener((vw, l2, t2, r2, b2, ol2, ot2, or2, ob2) -> {
                        int newH = b2 - t2, oldH = ob2 - ot2;
                        if (oldH <= 0 || newH >= oldH) return;       // نهتم بالتصغير فقط (فتح الكيبورد)
                        ScrollView sv = (ScrollView) msK;
                        View c = sv.getChildAt(0);
                        if (c == null) return;
                        boolean wasNear = (c.getBottom() + sv.getPaddingBottom()) - (sv.getScrollY() + oldH) < dp(160);
                        if (wasNear) scrollDown();
                    });
                }
            }

            void syncBars() {
                try {
                    boolean inChat = chatOpen();
                    applyInsets();
                    int top, bottom;
                    if (inChat) { bars.syncChat(blkBanner != null ? cSf() : Tk.chatSurface(dark)); return; }   // HTML: شريط الحالة شفاف (الزجاج خلفه) + شريط الإيماءات بلون خلفية المحادثة الصلب
                    else {
                        top = cBg();
                        boolean nav = v("bottomNav") != null && v("bottomNav").getVisibility() == View.VISIBLE;
                        bottom = nav ? Tk.navBg(dark) : cBg();
                        // صفحة البحث: خلفية --sr-bg وشريط الحالة بلون بداية تدرّج الهيدر (مثل الـ HTML)
                        boolean srch = "searchView".equals(curPage);
                        View rt = v("root");
                        if (rt != null) rt.setBackgroundColor(srch ? SearchPage.bg(dark) : cBg());
                        if (srch) { top = SearchPage.topColor(dark); bottom = SearchPage.bg(dark); }
                        // تعديل البيانات الشخصية (نهاري): شريط الإشعارات بنفس بياض ترويسة الصفحة (.edit-profile-header)
                        if (("editProfileView".equals(curPage) || "chatSettingsView".equals(curPage)) && !dark) top = 0xFFFFFFFF;
                    }
                    if (!inChat && isProfilePage(curPage)) bars.syncProfile(bottom);   // شريط حالة شفاف + أيقونات بيضاء
                    else bars.sync(top, bottom);
                } catch (Throwable ignored) { }
            }

            void show(String p) {
                final String from = curPage;
                prevPage = curPage; curPage = p;
                if (stories != null && !p.equals("homeView")) stories.collapseNow();   // الانتقال لصفحة/قسم آخر يطوي القصص
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
                if (chatSelMode && !home) exitChatSel(false);
                vis("mainHeader", home && !chatSelMode);
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
                    int inactive = dark ? 0xFFE4C45B : 0xFFC29D30;   // نهاري: ذهبي #c29d30 / ليلي #e4c45b (الخامل بشفافية .78)
                    int active = dark ? 0xFFE4C45B : 0xFFB8861B;     // النشط في النهار: ذهبي أغمق قليلاً
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

            /** يطبّق بيانات المستخدم المحفوظة محلياً فوراً (بدون انتظار السيرفر). يرجع true إن وُجد خزن. */
            boolean applyMyProfileCache() {
                try {
                    org.json.JSONObject j = LocalCache.getMyProfile();
                    if (j == null || j.optString("name").isEmpty()) return false;
                    userName = j.optString("name");
                    userUsername = j.optString("username");
                    userBio = j.optString("bio");
                    userGov = j.optString("gov");
                    userEmail = j.optString("email");
                    userPhoto = j.optString("photo");
                    String cv = j.optString("cover");
                    String cvId = CoverThemes.has(cv) ? cv : CoverThemes.DEFAULT_ID;
                    sp.edit().putString("coverId", cvId).apply();
                    applyCover(true, cvId);
                    if (userName.isEmpty()) userName = "Mohammed";
                    if (userUsername.isEmpty()) userUsername = "mohamed";
                    if (userBio.isEmpty()) userBio = "الشارع مالتي منو كال ملك عام";
                    if (userGov.isEmpty()) userGov = "المثنى";
                    if (userEmail.isEmpty()) userEmail = "hamodeygame45@gmail.com";
                    long created = j.optLong("createdAt", System.currentTimeMillis());
                    userJoin = new SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(new Date(created));
                    myStoryUntil = j.optLong("storyUntil", 0L);
                    try { refreshProfileRings(); } catch (Throwable ignored) { }
                    try { applyProfilePhoto(); } catch (Throwable ignored) { }
                    try { buildStories(); } catch (Throwable ignored) { }
                    try { fillProfile(); } catch (Throwable ignored) { }
                    return true;
                } catch (Throwable t) { return false; }
            }

            /** عند فتح التطبيق: الخزن المحلي أولاً؛ السيرفر فقط إن مضى أكثر من 10 دقائق (أو لا يوجد خزن). */
            void loadMyProfileSmart() {
                boolean had = applyMyProfileCache();
                long age = Long.MAX_VALUE;
                try { org.json.JSONObject j = LocalCache.getMyProfile(); if (j != null) age = System.currentTimeMillis() - j.optLong("_t", 0L); } catch (Throwable ignored) { }
                if (had && age < 10 * 60 * 1000L) fetchMyProfile(com.google.firebase.firestore.Source.CACHE, true);   // نسخة Firestore المحلية (تشمل تعديلاتي) بلا إنترنت
                else fetchMyProfile(com.google.firebase.firestore.Source.DEFAULT, false);
            }

            void loadMyProfile() { fetchMyProfile(com.google.firebase.firestore.Source.DEFAULT, false); }

            void fetchMyProfile(final com.google.firebase.firestore.Source src, final boolean fallbackServer) {
                db.collection("users").document(myUid).get(src)
                    .addOnSuccessListener(doc -> {
                        if (src == com.google.firebase.firestore.Source.CACHE && fallbackServer && !doc.exists()) {
                            fetchMyProfile(com.google.firebase.firestore.Source.DEFAULT, false);
                            return;
                        }
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
                            try { syncPrefsFromUser(doc); } catch (Throwable ignored) { }
                            try { syncChatStateFromUser(doc); } catch (Throwable ignored) { }
                            Long createdAt = doc.getLong("createdAt");

                            if (userName.isEmpty()) userName = "Mohammed";
                            try { buildStories(); } catch (Throwable ignored) { }   // حرف «قصتي» بعد وصول الاسم
                            if (userUsername.isEmpty()) userUsername = "mohamed";
                            if (userBio.isEmpty()) userBio = "الشارع مالتي منو كال ملك عام";
                            if (userGov.isEmpty()) userGov = "المثنى";
                            if (userEmail.isEmpty()) userEmail = "hamodeygame45@gmail.com";
                            if (createdAt == null) createdAt = System.currentTimeMillis();

                            userJoin = new SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(new Date(createdAt));
                            try { Long msu = doc.getLong("storyUntil"); myStoryUntil = msu != null ? msu : 0L; refreshProfileRings(); } catch (Throwable ignored) { }
                            try {
                                org.json.JSONObject cj = new org.json.JSONObject();
                                cj.put("name", userName); cj.put("username", userUsername); cj.put("bio", userBio);
                                cj.put("gov", userGov); cj.put("email", userEmail); cj.put("photo", userPhoto);
                                cj.put("cover", cvId); cj.put("createdAt", createdAt); cj.put("storyUntil", myStoryUntil);
                                LocalCache.putMyProfile(cj, src != com.google.firebase.firestore.Source.CACHE);
                            } catch (Throwable ignored) { }
                            try { applyProfilePhoto(); } catch (Throwable ignored) { }
                            try { buildStories(); } catch (Throwable ignored) { }
                            try { fillProfile(); } catch (Throwable ignored) { }
                            try { loadProfileStories(true); } catch (Throwable ignored) { }
                        }
                    })
                    .addOnFailureListener(e -> {
                        if (src == com.google.firebase.firestore.Source.CACHE && fallbackServer)
                            fetchMyProfile(com.google.firebase.firestore.Source.DEFAULT, false);
                    });
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
                wireEditKeyboard();
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


            // ===================== الحظر وجهات الدردشة (مطابق للـ HTML) =====================
            String dispName(String uid, String orig) {
                String c = uid != null ? contacts.get(uid) : null;
                return c != null && c.length() > 0 ? c : orig;
            }

            void startBlocksListener() {
                if (myUid == null || myUid.isEmpty()) return;
                if (blocksReg == null) {
                    blocksReg = db.collection("users").document(myUid).collection("blocks")
                        .addSnapshotListener((q, e) -> {
                            if (e != null || q == null) return;
                            myBlocks.clear();
                            for (DocumentSnapshot bd : q.getDocuments()) myBlocks.add(bd.getId());
                            refreshBlockUi();
                        });
                }
                if (contactsReg == null) {
                    contactsReg = db.collection("users").document(myUid)
                        .addSnapshotListener((d, e) -> {
                            if (e != null || d == null || !d.exists()) return;
                            Object o = d.get("chatContacts");
                            contacts.clear();
                            if (o instanceof Map) {
                                for (Object en : ((Map<?, ?>) o).entrySet()) {
                                    Map.Entry<?, ?> me = (Map.Entry<?, ?>) en;
                                    if (me.getKey() == null || me.getValue() == null) continue;
                                    String sv = String.valueOf(me.getValue()).trim();
                                    if (sv.length() > 0) contacts.put(String.valueOf(me.getKey()), sv);
                                }
                            }
                            applyContactNames();
                        });
                }
            }

            void checkBlockedByThem(final String uid) {
                blockedByThem = false;
                if (uid == null || uid.isEmpty() || uid.equals(myUid)) return;
                db.collection("users").document(uid).collection("blocks").document(myUid).get()
                    .addOnSuccessListener(d -> {
                        if (!uid.equals(otherUid)) return;
                        blockedByThem = d != null && d.exists();
                        applyChatBlockUi();
                    });
            }

            void refreshBlockUi() {
                try {
                    blocked = otherUid != null && !otherUid.isEmpty() && myBlocks.contains(otherUid);
                    refreshOtherButtons();
                    applyChatBlockUi();
                } catch (Throwable ignored) { }
            }

            /** زرا رسالة/حظر: أيقونة وكلمة بلون ذهبي (أصفر) في النهاري والليلي. */
            void refreshOtherButtons() {
                quick("otherMsgBtn", "ic_chat", cGold());
                quick("otherBlockBtn", "ic_block", cGold());
                View mb = v("otherMsgBtn"), bb0 = v("otherBlockBtn");
                if (mb != null) mb.setTag("card g");
                if (bb0 != null) bb0.setTag("card g");
                TextView ob = tv("otherBlockBtn");
                if (ob != null) {
                    ob.setText(blocked ? "إلغاء الحظر" : "حظر");
                    ob.setTextColor(cGold());
                    tintIcons(ob);
                }
            }

            void toggleBlock() {
                final String uid = otherUid;
                if (uid == null || uid.isEmpty() || uid.equals(myUid)) return;
                startBlocksListener();
                if (myBlocks.contains(uid)) { unblockUser(uid); return; }
                cardDialog("حظر المستخدم", "تحظر هذا المستخدم؟ ما راح يكدر يراسلك، وراح تظهر له رسالة إنك حظرته.",
                        null, "حظر", true, () -> blockUser(uid));
            }

            void blockFailed(String msg) {
                try {
                    new AlertDialog.Builder(act).setMessage(msg).setPositiveButton("حسناً", null).show();
                } catch (Throwable ignored) { }
            }

            void blockUser(final String uid) {
                if (uid == null || uid.isEmpty() || myUid == null || myUid.isEmpty() || uid.equals(myUid)) return;
                myBlocks.add(uid); refreshBlockUi();
                Map<String, Object> m = new HashMap<>();
                m.put("at", System.currentTimeMillis());
                db.collection("users").document(myUid).collection("blocks").document(uid).set(m)
                    .addOnFailureListener(e -> { myBlocks.remove(uid); refreshBlockUi(); blockFailed("تعذر الحظر، تحقق من الاتصال وحاول مرة أخرى"); });
            }

            void unblockUser(final String uid) {
                if (uid == null || uid.isEmpty() || myUid == null || myUid.isEmpty()) return;
                myBlocks.remove(uid); refreshBlockUi();
                db.collection("users").document(myUid).collection("blocks").document(uid).delete()
                    .addOnFailureListener(e -> { myBlocks.add(uid); refreshBlockUi(); blockFailed("تعذر إلغاء الحظر، تحقق من الاتصال وحاول مرة أخرى"); });
            }

            /** داخل المحادثة: لافتة "هذا المستخدم محظور" مع زر إلغاء الحظر بدل شريط الكتابة (#blockBanner). */
            void applyChatBlockUi() {
                try {
                    ViewGroup bar = (ViewGroup) v("chatBottomBar");
                    if (bar == null) return;
                    if (blkBanner != null) { bar.removeView(blkBanner); blkBanner = null; }
                    boolean on = blocked && otherUid != null && !otherUid.isEmpty();
                    if (on) {
                        hideKb();
                        if (isRecording || recPreview) { try { cancelRecord(); } catch (Throwable ignored) { } }
                        vis("replyPreviewBar", false);
                        vis("recordingBar", false);
                        vis("textInputBar", false);
                        blkBanner = buildBlockBanner();
                        bar.addView(blkBanner);
                    } else if (!isRecording && !recPreview) {
                        vis("textInputBar", true);
                    }
                    applyChatInsets();
                    TextView cs = tv("chatSub");
                    if (cs != null && blockedByThem && !on) { cs.setText("آخر ظهور كان منذ زمن طويل"); cs.setTextColor(Tk.crMuted(cMuted())); }
                    syncBars();
                } catch (Throwable ignored) { }
            }

            LinearLayout buildBlockBanner() {
                LinearLayout box = new LinearLayout(act);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
                box.setGravity(Gravity.CENTER_HORIZONTAL);
                box.setPadding(dp(16), dp(18), dp(16), dp(16));
                float r = dp(24);
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(cSf());
                bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
                box.setBackground(bg);
                box.setClickable(true);

                LinearLayout top = new LinearLayout(act);
                top.setOrientation(LinearLayout.HORIZONTAL);
                top.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
                top.setGravity(Gravity.CENTER);

                ImageView ic = new ImageView(act);
                ic.setImageResource(R.drawable.ic_block_16);
                ic.setScaleType(ImageView.ScaleType.FIT_CENTER);
                ic.setPadding(dp(10.5f), dp(10.5f), dp(10.5f), dp(10.5f));
                GradientDrawable ib = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFFF87171, 0xFFDC2626});
                ib.setShape(GradientDrawable.OVAL);
                ic.setBackground(ib);
                top.addView(ic, new LinearLayout.LayoutParams(dp(46), dp(46)));

                LinearLayout info = new LinearLayout(act);
                info.setOrientation(LinearLayout.VERTICAL);
                info.setGravity(Gravity.CENTER_HORIZONTAL);
                TextView t1 = new TextView(act);
                t1.setText("هذا المستخدم محظور");
                t1.setTextSize(16.5f);
                t1.setTypeface(null, android.graphics.Typeface.BOLD);
                t1.setTextColor(dark ? 0xFFFCA5A5 : 0xFFB91C1C);
                t1.setGravity(Gravity.CENTER);
                info.addView(t1, new LinearLayout.LayoutParams(-2, -2));
                TextView t2 = new TextView(act);
                t2.setText("لا يمكنكما تبادل الرسائل حالياً");
                t2.setTextSize(12.5f);
                t2.setTextColor(dark ? 0xFF94A3B8 : 0xFF64748B);
                t2.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(-2, -2);
                l2.topMargin = dp(3);
                info.addView(t2, l2);
                LinearLayout.LayoutParams li = new LinearLayout.LayoutParams(-2, -2);
                li.setMarginStart(dp(14));
                top.addView(info, li);
                box.addView(top, new LinearLayout.LayoutParams(-2, -2));

                LinearLayout btn = new LinearLayout(act);
                btn.setOrientation(LinearLayout.HORIZONTAL);
                btn.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
                btn.setGravity(Gravity.CENTER);
                int fg = dark ? 0xFFFCA5A5 : 0xFFDC2626;
                ImageView ulIv = new ImageView(act);
                ulIv.setImageDrawable(dr(R.drawable.ic_unlock_19, 19, fg));
                ulIv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                btn.addView(ulIv, new LinearLayout.LayoutParams(dp(19), dp(19)));
                TextView btnTxt = new TextView(act);
                btnTxt.setText("إلغاء الحظر");
                btnTxt.setTextSize(14.5f);
                btnTxt.setTypeface(null, android.graphics.Typeface.BOLD);
                btnTxt.setTextColor(fg);
                btnTxt.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams ltx = new LinearLayout.LayoutParams(-2, -2);
                ltx.setMarginStart(dp(8));
                btn.addView(btnTxt, ltx);
                GradientDrawable bb = new GradientDrawable();
                bb.setColor(dark ? 0x24DC2626 : 0xFFFFFFFF);
                bb.setCornerRadius(dp(14));
                bb.setStroke(dp(2), dark ? 0xFFF87171 : 0xFFDC2626);
                btn.setBackground(bb);
                btn.setOnClickListener(x -> unblockUser(otherUid));
                LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(-1, dp(46));
                lb.topMargin = dp(14);
                box.addView(btn, lb);
                return box;
            }

            /** رجوع من بروفايل الآخر: إن فُتح من المحادثة نرجع إليها (مثل goBackFromOtherProfile في الـ HTML). */
            void leaveOtherProfile() {
                String back = prevPage == null || prevPage.equals("otherUserProfileView") ? "homeView" : prevPage;
                if (profileFromChat && otherUid != null && !otherUid.isEmpty() && !currentChatId.isEmpty()) {
                    profileFromChat = false;
                    show(back);
                    vis("chatView", true);
                    syncBars();
                    TextView ct = tv("chatTitle");
                    if (ct != null) ct.setText(dispName(otherUid, otherOrigName.isEmpty() ? otherName : otherOrigName));
                    applyChatBlockUi();
                    return;
                }
                show(back);
            }

            // ---------- جهات الدردشة (الاسم المخصص) ----------
            void applyContactNames() {
                try {
                    if (otherUid != null && !otherUid.isEmpty()) {
                        String base = otherOrigName.isEmpty() ? otherName : otherOrigName;
                        TextView n = tv("otherHeaderName");
                        if (n != null && "otherUserProfileView".equals(curPage)) {
                            String sn = dispName(otherUid, base);
                            n.setText(otherVerified ? withVerified(sn) : sn);
                        }
                        TextView ct = tv("chatTitle");
                        View cvv = v("chatView");
                        if (ct != null && cvv != null && cvv.getVisibility() == View.VISIBLE)
                            ct.setText(dispName(otherUid, base));
                    }
                    buildChats();
                } catch (Throwable ignored) { }
            }

            void saveContact(String uid, String name) {
                contacts.put(uid, name);
                applyContactNames();
                Map<String, Object> inner = new HashMap<>();
                inner.put(uid, name);
                Map<String, Object> m = new HashMap<>();
                m.put("chatContacts", inner);
                db.collection("users").document(myUid).set(m, com.google.firebase.firestore.SetOptions.merge());
            }

            void deleteContact(String uid) {
                contacts.remove(uid);
                applyContactNames();
                db.collection("users").document(myUid).update("chatContacts." + uid, com.google.firebase.firestore.FieldValue.delete());
            }

            View menuItem(int iconRes, String label, int color) {
                LinearLayout row = new LinearLayout(act);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(14), dp(12), dp(14), dp(12));
                GradientDrawable pr = new GradientDrawable();
                pr.setColor(dark ? 0x1AFFFFFF : 0x120F172A);
                pr.setCornerRadius(dp(11));
                android.graphics.drawable.StateListDrawable sl = new android.graphics.drawable.StateListDrawable();
                sl.addState(new int[]{android.R.attr.state_pressed}, pr);
                row.setBackground(sl);
                row.setClickable(true);
                ImageView ic = new ImageView(act);
                ic.setImageResource(iconRes);
                ic.setColorFilter(color);
                row.addView(ic, new LinearLayout.LayoutParams(dp(20), dp(20)));
                TextView t = new TextView(act);
                t.setText(label);
                t.setTextSize(14.5f);
                t.setTypeface(null, android.graphics.Typeface.BOLD);
                t.setTextColor(color);
                t.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
                tl.setMarginStart(dp(11));
                row.addView(t, tl);
                return row;
            }

            /** قائمة النقاط الثلاث لبروفايل الآخر (#otherTopMenu): إضافة/تعديل/حذف جهة الدردشة. */
            void otherMenu() {
                final View more = v("otherMoreBtn");
                final View host = v("root");
                final String uid = otherUid;
                if (more == null || host == null || uid == null || uid.isEmpty() || uid.equals(myUid)) return;
                final boolean has = contacts.containsKey(uid);
                final String orig = otherOrigName.isEmpty() ? otherName : otherOrigName;
                final int txt = dark ? 0xFFF1F5F9 : 0xFF17212B;
                final android.widget.PopupWindow[] pwRef = new android.widget.PopupWindow[1];

                final int cardBg = dark ? 0xFF1B2230 : 0xFFFFFFFF;
                final int cardStroke = dark ? 0xFF2E3848 : 0xFFE1E6EE;
                LinearLayout card = new LinearLayout(act);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
                card.setMinimumWidth(dp(176));
                card.setPadding(dp(6), dp(6), dp(6), dp(6));
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(cardBg);
                bg.setCornerRadius(dp(16));
                bg.setStroke(dp(1), cardStroke);
                card.setBackground(bg);
                card.setElevation(0f);

                if (!has) {
                    View add = menuItem(R.drawable.ic_user_add_20, "إضافة لجهات الدردشة", txt);
                    add.setOnClickListener(x -> {
                        pwRef[0].dismiss();
                        cardDialog("إضافة لجهات الدردشة", "الاسم الأصلي: <b>" + android.text.TextUtils.htmlEncode(orig) + "</b>",
                                orig, "إضافة", false, () -> saveContact(uid, dlgValue));
                    });
                    card.addView(add);
                } else {
                    final String cur = contacts.get(uid);
                    View ed = menuItem(R.drawable.ic_pen_20, "تعديل جهة الدردشة", txt);
                    ed.setOnClickListener(x -> {
                        pwRef[0].dismiss();
                        cardDialog("تعديل جهة الدردشة", "الاسم الأصلي: <b>" + android.text.TextUtils.htmlEncode(orig) + "</b>",
                                cur, "حفظ", false, () -> saveContact(uid, dlgValue));
                    });
                    card.addView(ed);
                    View del = menuItem(R.drawable.ic_trash_menu_20, "حذف جهة الدردشة", 0xFFEF4444);
                    del.setOnClickListener(x -> {
                        pwRef[0].dismiss();
                        cardDialog("حذف جهة الدردشة", "سيعود اسم <b>" + android.text.TextUtils.htmlEncode(cur)
                                + "</b> إلى اسمه الأصلي <b>" + android.text.TextUtils.htmlEncode(orig) + "</b>.",
                                null, "حذف", true, () -> deleteContact(uid));
                    });
                    card.addView(del);
                }

                // ---- القائمة متصلة بالنقاط الثلاث: سهم صغير يشير إلى النقاط، وتنبثق القائمة من مركزها ----
                final int pad = dp(10), caretW = dp(16), caretH = dp(8), edge = dp(10);
                card.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                final int cardW = Math.max(card.getMeasuredWidth(), dp(176));
                int[] loc = new int[2];
                more.getLocationOnScreen(loc);
                final int sw = act.getResources().getDisplayMetrics().widthPixels;
                final int cx = loc[0] + more.getWidth() / 2;       // مركز النقاط على الشاشة
                int cardX = cx < sw / 2 ? Math.max(edge, cx - dp(26)) : Math.min(sw - edge - cardW, cx + dp(26) - cardW);
                int caretX = cx - cardX - caretW / 2;
                caretX = Math.max(dp(14), Math.min(cardW - dp(14) - caretW, caretX));

                FrameLayout holder = new FrameLayout(act);
                holder.setClipChildren(false);
                holder.setClipToPadding(false);
                holder.setPadding(pad, 0, pad, dp(16));
                FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(cardW, ViewGroup.LayoutParams.WRAP_CONTENT);
                cardLp.topMargin = dp(4);
                holder.addView(card, cardLp);
                final android.graphics.Paint fillP = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                fillP.setColor(cardBg);
                final android.graphics.Paint strokeP = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                strokeP.setColor(cardStroke);
                strokeP.setStyle(android.graphics.Paint.Style.STROKE);
                strokeP.setStrokeWidth(dp(1));
                strokeP.setStrokeJoin(android.graphics.Paint.Join.ROUND);
                strokeP.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                View caret = new View(act) {
                    @Override protected void onDraw(android.graphics.Canvas c) {
                        float w = getWidth(), h = getHeight(), s = dp(1);
                        android.graphics.Path fill = new android.graphics.Path();
                        fill.moveTo(0, h + s); fill.lineTo(0, h); fill.lineTo(w / 2f, s); fill.lineTo(w, h); fill.lineTo(w, h + s); fill.close();
                        c.drawPath(fill, fillP);
                        android.graphics.Path line = new android.graphics.Path();
                        line.moveTo(0, h); line.lineTo(w / 2f, s); line.lineTo(w, h);
                        c.drawPath(line, strokeP);
                    }
                };
                FrameLayout.LayoutParams caretLp = new FrameLayout.LayoutParams(caretW, caretH + dp(1), Gravity.TOP | Gravity.LEFT);
                caretLp.leftMargin = pad + caretX;
                // السهم (المثلث) محذوف بطلب المستخدم

                final android.widget.PopupWindow pw = new android.widget.PopupWindow(holder,
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
                pwRef[0] = pw;
                pw.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
                pw.setOutsideTouchable(true);
                pw.setClippingEnabled(false);
                pw.setOnDismissListener(() -> more.animate().rotation(0f).setDuration(180).start());

                pw.showAtLocation(host, Gravity.TOP | Gravity.LEFT, cardX - pad, loc[1] + more.getHeight() - dp(6));
                // تنبثق من مركز النقاط (قمة السهم)
                holder.setPivotX(pad + caretX + caretW / 2f); holder.setPivotY(0f);
                holder.setAlpha(0f); holder.setScaleX(0.4f); holder.setScaleY(0.4f);
                holder.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(190)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(1.1f)).start();
                more.animate().rotation(90f).setDuration(180).start();
            }

            TextView dlgBtn(String label, int bg, int fg, int stroke) {
                TextView b = new TextView(act);
                b.setText(label);
                b.setTextSize(14.5f);
                b.setTypeface(null, android.graphics.Typeface.BOLD);
                b.setTextColor(fg);
                b.setGravity(Gravity.CENTER);
                b.setBackground(shape(bg, 14, stroke));
                return b;
            }

            /** نافذة تأكيد/إدخال بنفس شكل بطاقات الـ HTML (bbConfirm / bbc). */
            void cardDialog(String title, String subHtml, String inputVal, String okText, boolean danger, final Runnable onOk) {
                final android.app.Dialog dlg = new android.app.Dialog(act);
                dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
                LinearLayout card = new LinearLayout(act);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
                card.setPadding(dp(20), dp(20), dp(20), dp(16));
                card.setBackground(shape(cSf(), 22, cBorder()));
                TextView t = new TextView(act);
                t.setText(title);
                t.setTextSize(17);
                t.setTypeface(null, android.graphics.Typeface.BOLD);
                t.setTextColor(cText());
                t.setGravity(Gravity.START);
                card.addView(t, new LinearLayout.LayoutParams(-1, -2));
                if (subHtml != null) {
                    TextView sb = new TextView(act);
                    sb.setText(android.text.Html.fromHtml(subHtml));
                    sb.setTextSize(13.5f);
                    sb.setTextColor(cMuted());
                    sb.setGravity(Gravity.START);
                    sb.setLineSpacing(0, 1.25f);
                    LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
                    sl.topMargin = dp(8);
                    card.addView(sb, sl);
                }
                final EditText inp;
                if (inputVal != null) {
                    inp = new EditText(act);
                    inp.setText(inputVal);
                    inp.setSingleLine(true);
                    inp.setTextSize(15);
                    inp.setTextColor(cText());
                    inp.setBackground(shape(dark ? 0xFF0F1923 : 0xFFF4F7FB, 14, cBorder()));
                    inp.setPadding(dp(14), dp(10), dp(14), dp(10));
                    inp.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                    inp.setSelectAllOnFocus(true);
                    LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(-1, dp(46));
                    il.topMargin = dp(14);
                    card.addView(inp, il);
                } else inp = null;
                LinearLayout row = new LinearLayout(act);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
                TextView ok = danger ? dlgBtn(okText, cRed(), 0xFFFFFFFF, 0) : dlgBtn(okText, cGold(), 0xFFFFFFFF, 0);
                if (danger) {   // زر التأكيد الأحمر: نفس تدرّج زر «حذف القصة» في StoryViewer
                    GradientDrawable rg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFFFF6A70, 0xFFC52D3A});
                    rg.setCornerRadius(dp(12));
                    ok.setBackground(rg);
                }
                if (!danger) ok.setBackground(goldShape(14));   // زر «إضافة» وأشباهه: نفس التدرّج الذهبي المعتاد + نص أبيض
                TextView no = dlgBtn("إلغاء", cSf(), cText(), cBorder());
                LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, dp(46), 1f);
                bl.setMarginEnd(dp(5));
                row.addView(ok, bl);
                LinearLayout.LayoutParams bl2 = new LinearLayout.LayoutParams(0, dp(46), 1f);
                bl2.setMarginStart(dp(5));
                row.addView(no, bl2);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
                rl.topMargin = dp(18);
                card.addView(row, rl);
                ok.setOnClickListener(x -> {
                    if (inp != null) {
                        String sv = inp.getText().toString().trim();
                        if (sv.isEmpty()) return;
                        dlgValue = sv;
                    }
                    dlg.dismiss();
                    onOk.run();
                });
                no.setOnClickListener(x -> dlg.dismiss());
                dlg.setContentView(card);
                Window w = dlg.getWindow();
                if (w != null) {
                    w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
                    int sw = act.getResources().getDisplayMetrics().widthPixels;
                    w.setLayout(Math.min(sw - dp(48), dp(340)), ViewGroup.LayoutParams.WRAP_CONTENT);
                    w.setDimAmount(0.55f);
                    if (inp != null) w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
                }
                dlg.show();
            }

            void openOther(String name, String username, String uid) {
                otherName = name; otherUser = username; otherUid = uid;
                startBlocksListener();
                blocked = uid != null && myBlocks.contains(uid);
                blockedByThem = false; otherOrigName = name == null ? "" : name; otherVerified = false;
                otherPhotoBmp = null;
                TextView oAv = tv("otherAvatarText");
                if (oAv != null) { oAv.setText(name.length() > 0 ? name.substring(0, 1) : "?"); style(oAv); }
                TextView oName = tv("otherHeaderName");
                if (oName != null) oName.setText(dispName(uid, name));
                TextView oUser = tv("otherHeaderUser");
                if (oUser != null) oUser.setText("@" + username);
                otherRow("otherBioRow", "view662", "otherBioDisplay", "");
                otherRow("otherGovRow", "view664", "otherGovDisplay", "");
                otherRow("otherJoinRow", "view666", "otherJoinDisplay", "");
                TextView oSt = tv("otherHeaderStatus");
                if (oSt != null) setPfStatus(oSt, false, "غير متصل");
                refreshOtherButtons(); checkBlockedByThem(uid);
                String back = curPage;
                show("otherUserProfileView");
                prevPage = back;
                applyCover(false, CoverThemes.DEFAULT_ID);   // لا يظهر غلاف الشخص السابق أثناء التحميل
                final String reqUid = uid;
                if (uid != null && !uid.isEmpty()) {
                    db.collection("users").document(uid).get().addOnSuccessListener(d -> {
                        if (d.exists() && reqUid.equals(otherUid)) {
                            applyCover(false, d.getString("cover"));
                            try { fillOther(d); } catch (Throwable ignored) { }
                        }
                    });
                }
            }

            // صف في بطاقة البروفايل: يختفي (مع الخط الفاصل) إذا كانت القيمة فارغة، مثل setProfileRow في الـ HTML
            void otherRow(String rowId, String lineId, String valId, String text) {
                boolean on = text != null && !text.isEmpty();
                vis(rowId, on);
                if (lineId != null) vis(lineId, on);
                TextView t = tv(valId);
                if (t != null) t.setText(on ? text : "");
            }

            String firstNonEmpty(String... a) {
                for (String x : a) if (x != null && !x.trim().isEmpty()) return x;
                return "";
            }

            // arCount من الـ HTML
            String arCount(long n, String one, String two, String few, String many) {
                if (n == 1) return one;
                if (n == 2) return two;
                if (n >= 3 && n <= 10) return n + " " + few;
                return n + " " + many;
            }

            // formatLastSeen من الـ HTML
            String formatLastSeen(long ts) {
                if (ts <= 0) return "غير متصل";
                long sec = (System.currentTimeMillis() - ts) / 1000;
                if (sec < 60) return "آخر ظهور قبل لحظات";
                long m = sec / 60;
                if (m < 60) return "آخر ظهور قبل " + arCount(m, "دقيقة", "دقيقتين", "دقائق", "دقيقة");
                long h = m / 60;
                if (h < 24) return "آخر ظهور قبل " + arCount(h, "ساعة", "ساعتين", "ساعات", "ساعة");
                long dd = h / 24;
                if (dd < 7) return "آخر ظهور قبل " + arCount(dd, "يوم", "يومين", "أيام", "يوم");
                return "آخر ظهور " + new SimpleDateFormat("d MMMM", new Locale("ar", "IQ")).format(new Date(ts));
            }

            // شارة التوثيق: دائرة ذهبية متدرجة (#d4af37 → #b38f27) وعلامة ✓ داكنة، مثل .verified-badge
            android.text.SpannableStringBuilder withVerified(String name) {
                android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(name + " ");
                int sz = dp(20);
                Bitmap bm = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
                android.graphics.Canvas c = new android.graphics.Canvas(bm);
                android.graphics.Paint pt = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                pt.setShader(new android.graphics.LinearGradient(0, 0, sz, sz, 0xFFD4AF37, 0xFFB38F27, android.graphics.Shader.TileMode.CLAMP));
                c.drawCircle(sz / 2f, sz / 2f, sz / 2f, pt);
                android.graphics.Paint tp = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                tp.setColor(0xFF0F172A); tp.setTextSize(dp(12)); tp.setFakeBoldText(true); tp.setTextAlign(android.graphics.Paint.Align.CENTER);
                android.graphics.Paint.FontMetrics fm = tp.getFontMetrics();
                c.drawText("\u2713", sz / 2f, sz / 2f - (fm.ascent + fm.descent) / 2f, tp);
                android.graphics.drawable.BitmapDrawable bd = new android.graphics.drawable.BitmapDrawable(act.getResources(), bm);
                bd.setBounds(0, 0, sz, sz);
                int start = sb.length();
                sb.append("\u2713");
                sb.setSpan(new android.text.style.ImageSpan(bd, android.text.style.ImageSpan.ALIGN_BASELINE), start, start + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                return sb;
            }

            // ملء بيانات الشخص الآخر كما في openUserProfileDirect بالـ HTML
            void fillOther(com.google.firebase.firestore.DocumentSnapshot d) {
                String nm = firstNonEmpty(d.getString("name"), d.getString("displayName"), otherName);
                String un = firstNonEmpty(d.getString("username"), otherUser);
                otherName = nm; otherUser = un; otherOrigName = nm;
                boolean verified = Boolean.TRUE.equals(d.getBoolean("isVerified")) || Boolean.TRUE.equals(d.getBoolean("isDev"))
                        || "ali".equalsIgnoreCase(un);
                otherVerified = verified;
                TextView oName = tv("otherHeaderName");
                if (oName != null) oName.setText(verified ? withVerified(dispName(otherUid, nm)) : dispName(otherUid, nm));
                TextView oUser = tv("otherHeaderUser");
                if (oUser != null) oUser.setText("@" + un);
                TextView oAv = tv("otherAvatarText");
                if (oAv != null && otherPhotoBmp == null) oAv.setText(nm.length() > 0 ? nm.substring(0, 1).toUpperCase() : "?");

                otherRow("otherBioRow", "view662", "otherBioDisplay", d.getString("bio"));
                otherRow("otherGovRow", "view664", "otherGovDisplay", firstNonEmpty(d.getString("gov"), d.getString("governorate")));
                Long created = d.getLong("createdAt");
                otherRow("otherJoinRow", "view666", "otherJoinDisplay",
                        created != null && created > 0 ? new SimpleDateFormat("MMMM yyyy", new Locale("ar", "IQ")).format(new Date(created)) : "");

                // الحالة: متصل الآن / آخر ظهور (مع احترام إخفاء آخر ظهور)
                try { Long osu = d.getLong("storyUntil"); if (osu != null && otherUid != null) storyUntil.put(otherUid, osu); refreshProfileRings(); } catch (Throwable ignored) { }
                try { loadProfileStories(false); } catch (Throwable ignored) { }
                boolean hideSeen = Boolean.TRUE.equals(d.getBoolean("hideLastSeen"));
                Long ls = d.getLong("lastSeen");
                long lastSeen = ls != null ? ls : 0;
                boolean live = !hideSeen && Boolean.TRUE.equals(d.getBoolean("online")) && (System.currentTimeMillis() - lastSeen < 45000);
                TextView oSt = tv("otherHeaderStatus");
                if (oSt != null) setPfStatus(oSt, live, hideSeen ? "آخر ظهور منذ فترة" : (live ? "متصل الآن" : formatLastSeen(lastSeen)));

                // الصورة (تُخفى إذا أخفاها صاحبها)
                if (!Boolean.TRUE.equals(d.getBoolean("hideAvatar"))) {
                    final String ph = firstNonEmpty(d.getString("photo"), d.getString("avatarUrl"));
                    final String reqUid = otherUid;
                    if (!ph.isEmpty()) {
                        new Thread(() -> {
                            Bitmap bmp = null;
                            try {
                                if (ph.startsWith("http")) {
                                    java.io.InputStream is = new java.net.URL(ph).openStream();
                                    bmp = BitmapFactory.decodeStream(is); is.close();
                                } else {
                                    String b64 = ph.contains(",") ? ph.substring(ph.indexOf(',') + 1) : ph;
                                    byte[] b = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                                    bmp = BitmapFactory.decodeByteArray(b, 0, b.length);
                                }
                            } catch (Throwable ignored) { }
                            final Bitmap fb = bmp;
                            act.runOnUiThread(() -> {
                                if (fb == null || !reqUid.equals(otherUid)) return;
                                otherPhotoBmp = fb;
                                applyOtherPhotoTo(tv("otherAvatarText"));
                            });
                        }).start();
                    }
                }
            }

            /** صورة الطرف الآخر في رأس المحادثة (دائرية)، وإلا الحرف الأول على دائرة الثيم. */
            void applyChatPhoto() {
                TextView t = tv("chatAvatar");
                if (t == null || !chatOpen()) return;
                Bitmap b = (otherUid == null || otherUid.isEmpty()) ? null : peerPhotos.get(otherUid);
                if (b != null && !b.isRecycled()) {
                    androidx.core.graphics.drawable.RoundedBitmapDrawable rd =
                            androidx.core.graphics.drawable.RoundedBitmapDrawableFactory.create(act.getResources(), b);
                    rd.setCircular(true);
                    rd.setAntiAlias(true);
                    rd.setMipMap(true);
                    b.setHasMipMap(true);
                    t.setBackground(rd);
                    t.setText("");
                } else {
                    t.setBackground(Tk.chatAvatar(t, dark));
                    t.setTextColor(dark ? 0xFF17212B : 0xFFFFFFFF);
                    String n = curChat == null ? "" : curChat;
                    t.setText(n.length() > 0 ? n.substring(0, 1) : "?");
                }
            }

            void applyOtherPhotoTo(TextView t) {
                if (t == null || otherPhotoBmp == null) return;
                androidx.core.graphics.drawable.RoundedBitmapDrawable rd =
                        androidx.core.graphics.drawable.RoundedBitmapDrawableFactory.create(act.getResources(), otherPhotoBmp);
                rd.setCircular(true);
                rd.setAntiAlias(true);
                rd.setMipMap(true);
                otherPhotoBmp.setHasMipMap(true);
                t.setBackground(withRing(rd, false));
                t.setText("");
            }

            final java.util.HashMap<String, Long> chatTsMap = new java.util.HashMap<>();
            /** وقت آخر نشاط: يقبل Long/Double/Timestamp ويرجع لـ updatedAt (الـ HTML يكتب updatedAt). */
            long chatTs(Map<String, Object> m) {
                long best = 0;
                for (String k : new String[]{"lastMessageAt", "updatedAt"}) {
                    Object o = m.get(k);
                    long t = 0;
                    if (o instanceof Number) t = ((Number) o).longValue();
                    else if (o instanceof com.google.firebase.Timestamp) t = ((com.google.firebase.Timestamp) o).toDate().getTime();
                    if (t > best) best = t;
                }
                return best;
            }
            // ---------- خزن محلي لقائمة المحادثات ----------
            final java.util.HashMap<String, String> nameCache = new java.util.HashMap<>();     // uid -> اسم الطرف الآخر
            final java.util.HashMap<String, String> unreadByUid = new java.util.HashMap<>();  // uid -> عدد غير المقروءة
            final java.util.HashMap<String, Long> userFetchAt = new java.util.HashMap<>();     // آخر جلب لوثيقة المستخدم من السيرفر
            final Runnable persistChatsRun = () -> {
                try {
                    org.json.JSONArray arr = new org.json.JSONArray();
                    for (String[] r : chatData) {
                        if (r == null || r.length < 5 || r[4] == null) continue;
                        org.json.JSONObject o = new org.json.JSONObject();
                        o.put("name", r[0]); o.put("last", r[1]); o.put("unread", r[3]); o.put("uid", r[4]);
                        Long t = chatTsMap.get(r[4]);
                        o.put("ts", t == null ? 0L : t);
                        String cid = chatIdByUid.get(r[4]);
                        o.put("cid", cid == null ? "" : cid);
                        arr.put(o);
                    }
                    LocalCache.putChats(arr);
                } catch (Throwable ignored) { }
            };
            /** يحفظ قائمة المحادثات بعد هدوء 800ms (تجميع الكتابات المتتالية). */
            void persistChats() { uiH.removeCallbacks(persistChatsRun); uiH.postDelayed(persistChatsRun, 800); }

            /** يعرض آخر قائمة محادثات محفوظة فوراً قبل وصول السيرفر. */
            void restoreChatsFromCache() {
                try {
                    org.json.JSONArray arr = LocalCache.getChats();
                    if (arr == null || arr.length() == 0) return;
                    chatData.clear();
                    for (int i = 0; i < arr.length(); i++) {
                        org.json.JSONObject o = arr.getJSONObject(i);
                        String uid = o.optString("uid");
                        if (uid.isEmpty()) continue;
                        String nm = o.optString("name", "?");
                        long ts = o.optLong("ts", 0L);
                        String un = o.optString("unread", "0");
                        String cid = o.optString("cid");
                        if (!"?".equals(nm)) nameCache.put(uid, nm);
                        unreadByUid.put(uid, un);
                        chatTsMap.put(uid, ts);
                        if (!cid.isEmpty()) chatIdByUid.put(uid, cid);
                        chatData.add(new String[]{nm, o.optString("last"), ts > 0 ? listTime(ts) : "", un, uid});
                    }
                    sortChats();
                    buildChats();
                } catch (Throwable ignored) { }
            }

            /** وثيقة مستخدم آخر: إن جُلبت من السيرفر قبل أقل من دقيقتين نقرأ من نسخة Firestore المحلية بدل الشبكة. */
            void fetchUserDoc(final String uid, final com.google.android.gms.tasks.OnSuccessListener<DocumentSnapshot> ok) {
                Long at = userFetchAt.get(uid);
                if (at != null && System.currentTimeMillis() - at < 120000L) {
                    db.collection("users").document(uid).get(com.google.firebase.firestore.Source.CACHE)
                        .addOnSuccessListener(ok)
                        .addOnFailureListener(e -> serverUserDoc(uid, ok));
                } else serverUserDoc(uid, ok);
            }
            void serverUserDoc(final String uid, final com.google.android.gms.tasks.OnSuccessListener<DocumentSnapshot> ok) {
                db.collection("users").document(uid).get().addOnSuccessListener(d -> {
                    userFetchAt.put(uid, System.currentTimeMillis());
                    ok.onSuccess(d);
                });
            }

            void sortChats() {
                Collections.sort(chatData, (a, b) -> {
                    Long ta = chatTsMap.get(a[4]), tb = chatTsMap.get(b[4]);
                    return Long.compare(tb == null ? 0 : tb, ta == null ? 0 : ta);
                });
            }

            void loadChats() {
                db.collection("chats")
                    .whereArrayContains("participants", myUid)
                    .addSnapshotListener((snapshots, e) -> {
                        if (e != null || snapshots == null) {
                            if (e != null) android.util.Log.e("Babylon", "loadChats failed", e);
                            return;
                        }
                        List<Map<String, Object>> temp = new ArrayList<>();
                        for (QueryDocumentSnapshot doc : snapshots) {
                            Map<String, Object> m = doc.getData();
                            m.put("__id", doc.getId());
                            temp.add(m);
                        }
                        Collections.sort(temp, (a, b) -> {
                            return Long.compare(chatTs(b), chatTs(a));
                        });
                        chatData.clear();
                        for (Map<String, Object> m : temp) {
                            Object po = m.get("participants");
                            if (!(po instanceof List)) continue;
                            List<Object> parts = (List<Object>) po;
                            String otherId = null;
                            for (Object po2 : parts) { String p = String.valueOf(po2); if (!p.equals(myUid)) { otherId = p; break; } }
                            if (otherId == null) continue;
                            final String fOtherId = otherId;
                            final String lastMsg = m.get("lastMessage") != null ? m.get("lastMessage").toString() : "";
                            final long lastAt = chatTs(m);
                            final String lastTime = lastAt > 0 ? listTime(lastAt) : "";
                            final String fChatId = String.valueOf(m.get("__id"));
                            chatTsMap.put(fOtherId, lastAt);
                            chatIdByUid.put(fOtherId, fChatId);
                            { Object ca = m.get("clearedAll"); chatClearedAll.put(fChatId, ca instanceof Number ? ((Number) ca).longValue() : 0L); }
                            if (chatIsCleared(fChatId, lastAt)) continue;   // محذوفة عندي ولم يصلها جديد بعد الحذف
                            // يظهر الصف فوراً (حتى قبل جلب بيانات الطرف الآخر أو إن فشل الجلب) ثم يُحدَّث الاسم
                            boolean exists = false;
                            for (String[] r0 : chatData) if (r0[4].equals(fOtherId)) { exists = true; break; }
                            if (!exists) { String cn = nameCache.get(fOtherId); String cu = unreadByUid.get(fOtherId); chatData.add(new String[]{cn != null ? cn : "?", lastMsg, lastTime, cu != null ? cu : "0", fOtherId}); sortChats(); try { buildChats(); } catch (Throwable ignored) { } }
                            fetchUserDoc(fOtherId, udoc -> {
                                    if (chatIsCleared(fChatId, lastAt)) return;
                                    String oName = udoc.exists() && udoc.getString("name") != null ? udoc.getString("name") : (nameCache.get(fOtherId) != null ? nameCache.get(fOtherId) : "?");
                                    if (!"?".equals(oName)) nameCache.put(fOtherId, oName);
                                    try {
                                        Long su = udoc.getLong("storyUntil");
                                        storyUntil.put(fOtherId, su != null ? su : 0L);
                                        Long lsn = udoc.getLong("lastSeen");
                                        boolean liveNow = !Boolean.TRUE.equals(udoc.getBoolean("hideLastSeen")) && Boolean.TRUE.equals(udoc.getBoolean("online"))
                                                && lsn != null && System.currentTimeMillis() - lsn < 45000;
                                        onlineMap.put(fOtherId, liveNow);
                                    } catch (Throwable ignored) { }
                                    boolean found = false;
                                    for (int i = 0; i < chatData.size(); i++) {
                                        if (chatData.get(i)[4].equals(fOtherId)) {
                                            String keepUn = chatData.get(i)[3];
                                            chatData.set(i, new String[]{oName, lastMsg, lastTime, keepUn != null ? keepUn : "0", fOtherId});
                                            found = true; break;
                                        }
                                    }
                                    if (!found) { String cu2 = unreadByUid.get(fOtherId); chatData.add(new String[]{oName, lastMsg, lastTime, cu2 != null ? cu2 : "0", fOtherId}); }
                                    sortChats();
                                    try { buildChats(); } catch (Throwable ignored) { }
                                    try { buildStories(); } catch (Throwable ignored) { }
                                    // عدد الرسائل غير المقروءة (من الطرف الآخر) لهذه المحادثة
                                    db.collection("chats").document(fChatId).collection("messages")
                                        .whereEqualTo("read", false).get()
                                        .addOnSuccessListener(qs -> {
                                            int cnt = 0;
                                            final long clrU = chatClearedAt(fChatId);
                                            for (DocumentSnapshot md : qs.getDocuments())
                                                if (!myUid.equals(md.getString("sender")) && !myUid.equals(md.getString("senderId")) && !msgCleared(md, clrU)) cnt++;
                                            for (int i = 0; i < chatData.size(); i++) {
                                                if (chatData.get(i)[4].equals(fOtherId)) {
                                                    String[] row = chatData.get(i);
                                                    String nv = String.valueOf(cnt);
                                                    unreadByUid.put(fOtherId, nv);
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

            /** فتح عارض القصص: قوس ذهبي دوّار على الحلقة ثم نافذة منبثقة (launchStoryWithGlow + startViewer في الـ HTML). */
            void openStory(final String uid, final String name, final String letter, final Bitmap photo, final boolean blue, final StoryViewer.Spin av) {
                openStory(uid, name, letter, photo, blue, av, null, null);
            }

            void openStory(final String uid, final String name, final String letter, final Bitmap photo, final boolean blue, final StoryViewer.Spin av,
                           final String startId, final java.util.Set<String> playIds) {
                if (myUid == null || myUid.isEmpty() || db == null) return;
                StoryViewer.launch(act, db, myUid, av, uid, name, letter, photo, blue, startId, playIds, new StoryViewer.Listener() {
                    @Override public void onClosed() { try { buildStories(); } catch (Throwable ignored) { } try { refreshProfileRings(); } catch (Throwable ignored) { } try { refreshProfileStories(); } catch (Throwable ignored) { } }
                    @Override public void openChat(String u) { try { UI.this.openChat(name, u); } catch (Throwable ignored) { } }
                    @Override public void onStoriesChanged(long until) {
                        if (uid.equals(myUid)) myStoryUntil = until; else storyUntil.put(uid, until);
                        try { refreshProfileRings(); } catch (Throwable ignored) { }
                        try { buildStories(); } catch (Throwable ignored) { }
                        try { refreshProfileStories(); } catch (Throwable ignored) { }
                    }
                });
            }

            // ============================================================ قصص الملف الشخصي (قصصي / القصص المثبتة كما في الـ HTML)
            void refreshProfileStories() {
                loadProfileStories(true);
                if (otherUid != null && !otherUid.isEmpty()) loadProfileStories(false);
            }

            /** مطابق لـ checkUserStories / loadOtherStories في الـ HTML: قصصي = كل قصصي (النشطة + الأرشيف)، ملف الآخر = قصصه المثبتة فقط. */
            void loadProfileStories(final boolean mine) {
                final String uid = mine ? myUid : otherUid;
                if (uid == null || uid.isEmpty() || db == null) return;
                if (!mine) {   // مسح فوري لما كان معروضاً لشخص آخر
                    LinearLayout g0 = (LinearLayout) v("otherStoriesGrid");
                    if (g0 != null) g0.removeAllViews();
                    vis("textview666", true); vis("otherStoriesEmpty", false);
                }
                db.collection("stories").whereEqualTo("userId", uid).get().addOnSuccessListener(snap -> {
                    if (!mine && !uid.equals(otherUid)) return;
                    long now = System.currentTimeMillis();
                    final ArrayList<Object[]> items = new ArrayList<>();   // id, imageUrl, video, pinned, createdAt, active, expiresAt
                    for (com.google.firebase.firestore.DocumentSnapshot d : snap.getDocuments()) {
                        Long ex = d.getLong("expiresAt"), cr = d.getLong("createdAt");
                        Boolean pn = d.getBoolean("pinned");
                        boolean pinned = pn != null && pn;
                        if (!mine && !pinned) continue;
                        long exv = ex == null ? 0L : ex;
                        String vu = d.getString("videoUrl");
                        items.add(new Object[]{d.getId(), d.getString("imageUrl") == null ? "" : d.getString("imageUrl"),
                                vu != null && !vu.isEmpty(), pinned, cr == null ? 0L : cr, exv > now, exv});
                    }
                    java.util.Collections.sort(items, (a, b) -> Long.compare((Long) b[4], (Long) a[4]));   // الأحدث أولاً
                    renderStoryGrid(mine, uid, items);
                });
            }

            /** مطابق لـ renderTiles: شبكة 3 أعمدة، مربعات، فجوة 2، شارة الوقت/الأرشيف يسار، دبوس يمين أعلى، شارة فيديو يمين أسفل. */
            void renderStoryGrid(final boolean mine, final String uid, final ArrayList<Object[]> items) {
                final LinearLayout grid = (LinearLayout) v(mine ? "myStoriesGrid" : "otherStoriesGrid");
                if (grid == null) return;
                grid.removeAllViews();
                if (mine) { vis("linear655", true); vis("pTabStories", true); vis("myStoriesEmpty", items.isEmpty()); }
                else { vis("textview666", true); vis("otherStoriesEmpty", items.isEmpty()); }
                if (items.isEmpty()) return;
                final java.util.HashSet<String> ids = new java.util.HashSet<>();
                for (Object[] it : items) ids.add((String) it[0]);
                final int cols = 3, gap = dp(2);
                int cw = (act.getResources().getDisplayMetrics().widthPixels - dp(4) - gap * (cols - 1)) / cols;   // 2dp حشوة على الجانبين
                final long now = System.currentTimeMillis();
                for (int i = 0; i < items.size(); i += cols) {
                    LinearLayout row = new LinearLayout(act);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);   // نفس ترتيب شبكة الـ HTML في صفحة RTL
                    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, cw);
                    if (i > 0) rlp.topMargin = gap;
                    grid.addView(row, rlp);
                    for (int c = 0; c < cols; c++) {
                        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, cw, 1f);
                        if (c > 0) clp.setMarginStart(gap);
                        if (i + c >= items.size()) { row.addView(new View(act), clp); continue; }
                        final Object[] it = items.get(i + c);
                        final boolean video = (Boolean) it[2], pinned = (Boolean) it[3], active = (Boolean) it[5];
                        final long expires = (Long) it[6];
                        FrameLayout card = new FrameLayout(act);
                        card.setBackgroundColor(0xFF17212B);
                        final ImageView iv = new ImageView(act);
                        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        if (!active && !pinned) iv.setAlpha(0.5f);   // .st-old img{opacity:.5}
                        card.addView(iv, new FrameLayout.LayoutParams(-1, -1));
                        final String src = (String) it[1];
                        if (!src.isEmpty()) {
                            final int md = cw;
                            new Thread(() -> {
                                final Bitmap b = StoryViewer.fetchBitmap(src, md * 2);
                                if (b != null) act.runOnUiThread(() -> iv.setImageBitmap(b));
                            }).start();
                        }
                        final int badgeFg = 0xFFFFFFFF;
                        if (pinned) {   // .story-pin-mark : top:6 right:6، دائرة 22 بخلفية rgba(0,0,0,.62) ودبوس ذهبي (أبيض في النهاري)
                            FrameLayout pm = new FrameLayout(act);
                            GradientDrawable pg = new GradientDrawable();
                            pg.setShape(GradientDrawable.OVAL); pg.setColor(0x9E000000);
                            pm.setBackground(pg);
                            ImageView pi = new ImageView(act);
                            pi.setImageResource(R.drawable.ic_sv_pin);
                            pi.setColorFilter(dark ? 0xFFF5C542 : badgeFg, android.graphics.PorterDuff.Mode.SRC_IN);
                            pm.addView(pi, new FrameLayout.LayoutParams(dp(13), dp(13), Gravity.CENTER));
                            FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP | Gravity.RIGHT);
                            plp.setMargins(0, dp(6), dp(6), 0);
                            card.addView(pm, plp);
                        }
                        if (video) {   // .vid-badge : bottom:4 right:4
                            FrameLayout vb = new FrameLayout(act);
                            vb.setBackground(shape(0xA6000000, 10, 0));
                            ImageView vi = new ImageView(act);
                            vi.setImageResource(R.drawable.ic_voice_play);
                            vi.setColorFilter(badgeFg, android.graphics.PorterDuff.Mode.SRC_IN);
                            vb.addView(vi, new FrameLayout.LayoutParams(dp(15), dp(15), Gravity.CENTER));
                            FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(dp(27), dp(19), Gravity.BOTTOM | Gravity.RIGHT);
                            vlp.setMargins(0, 0, dp(4), dp(4));
                            card.addView(vb, vlp);
                        }
                        if (mine && !pinned) {   // .st-badge : top:4 left:4 — ساعة + المتبقي، أو «أرشيف»
                            LinearLayout sb = new LinearLayout(act);
                            sb.setOrientation(LinearLayout.HORIZONTAL);
                            sb.setGravity(Gravity.CENTER_VERTICAL);
                            sb.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
                            sb.setBackground(shape(0xA6000000, 10, 0));
                            sb.setPadding(dp(7), dp(2), dp(7), dp(2));
                            if (active) {
                                ImageView ci = new ImageView(act);
                                ci.setImageResource(R.drawable.ic_sp_clock);
                                ci.setColorFilter(badgeFg, android.graphics.PorterDuff.Mode.SRC_IN);
                                LinearLayout.LayoutParams cil = new LinearLayout.LayoutParams(dp(15), dp(15));
                                cil.setMarginStart(dp(4));
                                sb.addView(ci, cil);
                            }
                            TextView st = new TextView(act);
                            long m = Math.max(1, (expires - now) / 60000);
                            st.setText(active ? (m < 60 ? m + " د" : (m / 60) + " س") : "أرشيف");
                            st.setTextSize(11); st.setTextColor(badgeFg); st.setIncludeFontPadding(false);
                            sb.addView(st);
                            FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
                            slp.setMargins(dp(4), dp(4), 0, 0);
                            card.addView(sb, slp);
                        }
                        final String sid = (String) it[0];
                        card.setOnClickListener(vw -> {   // startViewer(uid, list.reverse(), index): يشغّل من الأقدم للأحدث ويبدأ من المضغوطة
                            if (mine) {
                                openStory(myUid, "قصتي", userName != null && userName.length() > 0 ? userName.substring(0, 1).toUpperCase() : "?", photoBmp, false, null, sid, ids);
                            } else {
                                String nm = dispName(uid, "");
                                openStory(uid, nm, nm.length() > 0 ? nm.substring(0, 1).toUpperCase() : "?", otherPhotoBmp, StoryAvatarView.toneBlue(uid), null, sid, ids);
                            }
                        });
                        row.addView(card, clp);
                    }
                }
            }

            /** نشر قصة جديدة (مثل startStoryPublish في الـ HTML). */
            void startStoryPublish() {
                if (myUid == null || myUid.isEmpty()) return;
                storyComposer = new StoryComposer(act, db, myUid, dark, until -> {
                    if (until > 0) myStoryUntil = until;
                    try { refreshProfileRings(); } catch (Throwable ignored) { }
                    try { buildStories(); } catch (Throwable ignored) { }
                    try { refreshProfileStories(); } catch (Throwable ignored) { }
                });
                storyComposer.start();
            }

            void buildStories() {
                if (stories == null) return;
                long nowMs = System.currentTimeMillis();
                ArrayList<StoriesController.Item> list = new ArrayList<>();
                StoriesController.Item me = new StoriesController.Item();
                me.uid = myUid; me.name = "قصتي"; me.me = true;
                me.letter = userName != null && userName.length() > 0 ? userName.substring(0, 1).toUpperCase() : "?";
                me.photo = photoBmp; me.blue = StoryAvatarView.toneBlue(myUid);
                me.ring = myStoryUntil > nowMs;
                me.seen = me.ring && StoryViewer.isSeen(act, myUid, myUid, myStoryUntil);
                list.add(me);
                for (String[] c : chatData) {
                    if (c.length < 5) continue;
                    Long u = storyUntil.get(c[4]);
                    if (u == null || u <= nowMs || myBlocks.contains(c[4])) continue;
                    StoriesController.Item it = new StoriesController.Item();
                    it.uid = c[4];
                    it.name = dispName(c[4], c[0]);
                    it.letter = c[0].length() > 0 ? c[0].substring(0, 1).toUpperCase() : "?";
                    it.ring = true; it.blue = StoryAvatarView.toneBlue(c[4]);
                    it.photo = peerPhotos.get(c[4]); ensurePeerPhoto(c[4]);
                    it.seen = StoryViewer.isSeen(act, myUid, c[4], u);
                    list.add(it);
                }
                stories.setItems(list, dark);
            }

            // ---- صور الأشخاص في قائمة المحادثات وشريط القصص (كانت تُمرَّر null فيظهر الحرف دائماً) ----
            final java.util.HashMap<String, Bitmap> peerPhotos = new java.util.HashMap<>();
            final java.util.HashMap<String, String> peerPhotoSig = new java.util.HashMap<>();
            final java.util.HashMap<String, Long> peerPhotoAt = new java.util.HashMap<>();
            final java.util.HashSet<String> peerPhotoReq = new java.util.HashSet<>();
            Runnable peerPhotoRefresh;

            Bitmap decodePeerPhoto(String ph) {
                try {
                    byte[] b;
                    if (ph.startsWith("http")) {
                        java.io.InputStream is = new java.net.URL(ph).openStream();
                        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                        byte[] buf = new byte[8192]; int n;
                        while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
                        is.close(); b = bo.toByteArray();
                    } else {
                        b = android.util.Base64.decode(ph.contains(",") ? ph.substring(ph.indexOf(',') + 1) : ph, android.util.Base64.DEFAULT);
                    }
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(b, 0, b.length, o);
                    int ss = 1;
                    while (Math.min(o.outWidth, o.outHeight) / (ss * 2) >= 256) ss *= 2;   // الأفاتار صغير: لا حاجة لـ 1080px لكل صف
                    o = new BitmapFactory.Options();
                    o.inSampleSize = ss;
                    return BitmapFactory.decodeByteArray(b, 0, b.length, o);
                } catch (Throwable t) { return null; }
            }

            void ensurePeerPhoto(final String uid) {
                if (uid == null || uid.isEmpty() || myBlocks.contains(uid)) return;
                Long at = peerPhotoAt.get(uid);
                if (at != null && System.currentTimeMillis() - at < 120000) return;
                if (!peerPhotoReq.add(uid)) return;
                db.collection("users").document(uid).get().addOnSuccessListener(d -> {
                    final boolean hidden = d == null || !d.exists() || Boolean.TRUE.equals(d.getBoolean("hideAvatar"));
                    final String ph = hidden ? "" : firstNonEmpty(d.getString("photo"), d.getString("avatarUrl"));
                    if (ph.equals(peerPhotoSig.get(uid)) && (ph.isEmpty() || peerPhotos.containsKey(uid))) {
                        peerPhotoAt.put(uid, System.currentTimeMillis()); peerPhotoReq.remove(uid); return;
                    }
                    new Thread(() -> {
                        final Bitmap bmp = ph.isEmpty() ? null : decodePeerPhoto(ph);
                        act.runOnUiThread(() -> {
                            peerPhotoReq.remove(uid);
                            peerPhotoAt.put(uid, System.currentTimeMillis());
                            if (!ph.isEmpty() && bmp == null) return;
                            peerPhotoSig.put(uid, ph);
                            if (bmp != null) peerPhotos.put(uid, bmp); else peerPhotos.remove(uid);
                            if (peerPhotoRefresh == null) peerPhotoRefresh = () -> {
                                try { buildChats(); } catch (Throwable ignored) { }
                                try { buildStories(); } catch (Throwable ignored) { }
                                try { applyChatPhoto(); } catch (Throwable ignored) { }
                            };
                            uiH.removeCallbacks(peerPhotoRefresh);
                            uiH.postDelayed(peerPhotoRefresh, 250);
                        });
                    }).start();
                }).addOnFailureListener(e -> peerPhotoReq.remove(uid));
            }

            void buildChats() {
                startBlocksListener();
                LinearLayout list = (LinearLayout) v("chatList");
                if (list == null) return;
                persistChats();
                syncChatSelWithData();
                list.removeAllViews();
                int unreadChats = 0;
                for (String[] c : chatData) if (!c[3].equals("0")) unreadChats++;
                TextView fltUnread = tv("fltUnread");
                if (fltUnread != null) {
                    fltUnread.setText("غير المقروءة");
                    TextView cnt = tv("fltUnreadCnt");
                    if (cnt != null) {
                        cnt.setVisibility(unreadChats > 0 ? View.VISIBLE : View.GONE);
                        cnt.setText(String.valueOf(unreadChats));
                        GradientDrawable cg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFFE2B83F, 0xFFB8861B});
                        cg.setCornerRadius(dp(11.5f)); cg.setStroke(dp(2), cSf());
                        cnt.setBackground(cg);
                    }
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
                final java.util.List<String> pinsNow = getPins();
                for (String[] c : orderedChats()) {
                    if (unreadOnly && c[3].equals("0")) continue;
                    final boolean hasUnread = !c[3].equals("0");
                    final String rowUid = c.length > 4 ? c[4] : "";
                    final boolean rowSel = chatSelMode && chatSel.contains(rowUid);
                    final boolean rowPinned = c.length > 4 && pinsNow.contains(chatIdOf(rowUid));
                    final String dn = c.length > 4 ? dispName(c[4], c[0]) : c[0];
                    LinearLayout row = new LinearLayout(act);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(12), dp(6.9f), dp(12), dp(6.9f));   // أنحف بـ 5dp (70.8 → 65.8)
                    row.setTag("row");
                    row.setClipChildren(false);
                    row.setMinimumHeight(dp(65.8f));
                    row.setElevation(0f);
                    LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
                    rp.bottomMargin = dp(3);                              // المسافة بين المحادثات 3dp
                    if (rowSel) { rp.topMargin = 0; rp.bottomMargin = dp(2); }   // المسافة بين محادثتين محدَّدتين = 2dp
                    row.setLayoutParams(rp);
                    StoryAvatarView av = new StoryAvatarView(act);
                    boolean ringOn = c.length > 4 && storyUntil.get(c[4]) != null && storyUntil.get(c[4]) > System.currentTimeMillis();
                    final Bitmap rowPhoto = c.length > 4 ? peerPhotos.get(c[4]) : null;
                    if (c.length > 4) ensurePeerPhoto(c[4]);
                    av.set(dn.length() > 0 ? dn.substring(0, 1).toUpperCase() : "?", rowPhoto, c.length > 4 && StoryAvatarView.toneBlue(c[4]))
                      .ring(ringOn, false).online(c.length > 4 && Boolean.TRUE.equals(onlineMap.get(c[4])))
                      .theme(dark, cSf()).textSp(17f).pad(ringOn ? 5f : 0f);   // الصورة تصغر فقط عند وجود قصة (الحلقة داخل حدود الـ View فلا تُقصّ)
                    if (ringOn) {   // chatAvatarStory: الضغط على أفاتار فيه حلقة يفتح القصة
                        final StoryAvatarView fav = av; final String fUid = rowUid, fName = dn;
                        final boolean fBlue = StoryAvatarView.toneBlue(rowUid);
                        av.ring(true, StoryViewer.isSeen(act, myUid, rowUid, storyUntil.get(rowUid)));
                        av.setOnClickListener(vv -> openStory(fUid, fName, fName.length() > 0 ? fName.substring(0, 1).toUpperCase() : "?", rowPhoto, fBlue, fav));
                        av.setOnLongClickListener(vv -> row.performLongClick());
                    }
                    // الأفاتار بحجم منطقة محتوى الصف نفسها (65.8 - 2*6.9 ≈ 52dp) بلا هوامش سالبة؛
                    // قطر الصورة = 52 - 2*5 = 42dp والحلقة (نصف قطر +5dp) تنتهي داخل الـ View تماماً فلا تُقصّ أبداً.
                    LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(52f), dp(52f));
                    LinearLayout mid = new LinearLayout(act);
                    mid.setOrientation(LinearLayout.VERTICAL);
                    mid.setClipChildren(false);
                    LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, -2, 1f);
                    mp.setMargins(dp(12), 0, 0, 0);
                    mid.setLayoutParams(mp);
                    // صف الاسم + الوقت
                    LinearLayout r1 = new LinearLayout(act);
                    r1.setOrientation(LinearLayout.HORIZONTAL);
                    r1.setGravity(Gravity.CENTER_VERTICAL);
                    TextView nm = new TextView(act);
                    nm.setText(dn); nm.setTextSize(16.66f); nm.setTypeface(null, android.graphics.Typeface.BOLD);
                    nm.setSingleLine(true); nm.setEllipsize(TextUtils.TruncateAt.END); nm.setTextColor(cText());
                    nm.setIncludeFontPadding(false); nm.setMinHeight(dp(20)); nm.setGravity(Gravity.CENTER_VERTICAL);
                    TextView tm = new TextView(act);
                    tm.setText(c[2]); tm.setTextSize(12.5f); tm.setTypeface(null, android.graphics.Typeface.BOLD);
                    tm.setTextColor(cText()); tm.setIncludeFontPadding(false);
                    r1.addView(nm, new LinearLayout.LayoutParams(0, -2, 1f));
                    r1.addView(tm, new LinearLayout.LayoutParams(-2, -2));
                    // صف آخر رسالة + شارة غير المقروء
                    LinearLayout r2 = new LinearLayout(act);
                    r2.setOrientation(LinearLayout.HORIZONTAL);
                    r2.setGravity(Gravity.CENTER_VERTICAL);
                    r2.setMinimumHeight(dp(22.9f));
                    LinearLayout.LayoutParams r2p = new LinearLayout.LayoutParams(-1, -2);
                    r2p.topMargin = dp(5);
                    TextView ls = new TextView(act);
                    final int mediaKind = MsgViews.mediaKindOf(c[1]);
                    ls.setText(mediaKind > 0 ? MsgViews.mediaLabelOf(mediaKind) : c[1]); ls.setTextSize(14.05f);
                    ls.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
                    ls.setSingleLine(true); ls.setEllipsize(TextUtils.TruncateAt.END); ls.setIncludeFontPadding(false);
                    ls.setTextColor(hasUnread ? cText() : (dark ? 0xFF8798AA : 0xFF6B7A8C));
                    if (mediaKind > 0) {
                        // وسائط (صوتية/صورة/مقطع): النص ثم أيقونة SVG على يساره
                        LinearLayout mw = new LinearLayout(act);
                        mw.setOrientation(LinearLayout.HORIZONTAL);
                        mw.setGravity(Gravity.CENTER_VERTICAL);
                        mw.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);   // أول عنصر (النص) يمين، والأيقونة بعده أي يساره
                        mw.addView(ls, new LinearLayout.LayoutParams(-2, -2));
                        ImageView mi = new ImageView(act);
                        mi.setImageResource(MsgViews.mediaIconOf(mediaKind));
                        mi.setColorFilter(hasUnread ? cText() : (dark ? 0xFF8798AA : 0xFF6B7A8C));
                        LinearLayout.LayoutParams mil = new LinearLayout.LayoutParams(dp(16), dp(16));
                        mil.setMarginStart(dp(5));
                        mw.addView(mi, mil);
                        r2.addView(mw, new LinearLayout.LayoutParams(0, -2, 1f));
                    } else r2.addView(ls, new LinearLayout.LayoutParams(0, -2, 1f));
                    if (hasUnread) {
                        TextView b = new TextView(act);
                        b.setText(c[3]); b.setTextSize(12.5f); b.setTypeface(null, android.graphics.Typeface.BOLD);
                        b.setGravity(Gravity.CENTER); b.setTextColor(0xFFFFFFFF); b.setIncludeFontPadding(false);
                        b.setMinWidth(dp(22.9f)); b.setMinHeight(dp(22.9f));
                        b.setPadding(dp(7), 0, dp(7), 0);
                        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFFE2B83F, 0xFFB8861B});
                        bg.setCornerRadius(dp(11.5f));
                        b.setBackground(bg);
                        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
                        bl.rightMargin = dp(8);
                        r2.addView(b, bl);
                    }
                    if (rowPinned && !hasUnread) {
                        ImageView pi = new ImageView(act);
                        pi.setImageResource(R.drawable.ic_mm_pin);
                        pi.setColorFilter(cGold());
                        pi.setAlpha(.95f);
                        LinearLayout.LayoutParams pil = new LinearLayout.LayoutParams(dp(17), dp(17));
                        pil.setMarginStart(dp(6));
                        r2.addView(pi, pil);
                    }
                    mid.addView(r1);
                    mid.addView(r2, r2p);
                    // RTL: الأفاتار أول عنصر (يمين)
                    if (false) {
                        FrameLayout avf = new FrameLayout(act);
                        avf.setClipChildren(false);
                        avf.addView(av, new FrameLayout.LayoutParams(-1, -1));
                        FrameLayout chk = new FrameLayout(act);
                        GradientDrawable cg = new GradientDrawable();
                        cg.setShape(GradientDrawable.OVAL); cg.setColor(cGold()); cg.setStroke(dp(2), cSf());
                        chk.setBackground(cg);
                        ImageView ci = new ImageView(act);
                        ci.setImageResource(R.drawable.ic_sel_check);
                        chk.addView(ci, new FrameLayout.LayoutParams(dp(13), dp(13), Gravity.CENTER));
                        FrameLayout.LayoutParams ckp = new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.RIGHT | Gravity.BOTTOM);
                        ckp.rightMargin = dp(2.3f); ckp.bottomMargin = dp(2.3f);
                        avf.addView(chk, ckp);
                        row.addView(avf, ap);
                    } else row.addView(av, ap);
                    row.addView(mid);
                    final String fName = c[0], fUid = c[4];
                    row.setOnClickListener(x -> { if (chatSelMode) toggleChatSel(fUid); else openChat(fName, fUid); });
                    row.setOnLongClickListener(x -> {
                        try { x.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); } catch (Throwable ignored) { }
                        if (!chatSelMode) enterChatSel(fUid); else toggleChatSel(fUid);
                        return true;
                    });
                    list.addView(row);
                    style(row);
                    if (rowSel) {   // التحديد: ذهبي معتم يغطي الصف بالكامل (لا تظهر النقوش خلفه) + إطار ذهبي
                        GradientDrawable sg = new GradientDrawable();
                        int selBase = cSf() | 0xFF000000;   // طبقة أساس معتمة
                        sg.setColor(androidx_blend(selBase, cGold(), 0.15f));   // الذهبي أخفّ بنسبة 50%
                        sg.setCornerRadius(dp(18)); sg.setStroke(dp(1.5f), androidx_blend(selBase, cGold(), 0.5f));
                        row.setBackground(sg);
                    }
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


            // =====================================================================
            //  تحديد المحادثات + التثبيت + الحذف (نسخة الـ HTML: التحديد/الحذف مثل تليجرام)
            //  التخزين: pins = [chatId] (حد أقصى 5)، cleared = {chatId: وقت الحذف} في prefs ومزامنة users/{uid}.chatState
            // =====================================================================
            String chatIdOf(String uid) {
                String id = chatIdByUid.get(uid);
                return id != null ? id : getChatId(myUid, uid);
            }

            java.util.List<String> getPins() {
                java.util.ArrayList<String> out = new java.util.ArrayList<>();
                try {
                    org.json.JSONArray a = new org.json.JSONArray(sp.getString(PIN_KEY, "[]"));
                    for (int i = 0; i < a.length() && out.size() < MAX_CHAT_PINS; i++) out.add(a.getString(i));   // حدّ أقصى 5 حتى للبيانات القديمة المحفوظة
                } catch (Throwable ignored) { }
                return out;
            }

            void setPins(java.util.List<String> l) {
                try { sp.edit().putString(PIN_KEY, new org.json.JSONArray(l).toString()).apply(); } catch (Throwable ignored) { }
                syncChatStateToCloud();
            }

            java.util.HashMap<String, Long> getCleared() {
                if (clrMem == null) {
                    clrMem = new java.util.HashMap<>();
                    try {
                        org.json.JSONObject o = new org.json.JSONObject(sp.getString(CLR_KEY, "{}"));
                        java.util.Iterator<String> it = o.keys();
                        while (it.hasNext()) { String k = it.next(); clrMem.put(k, o.optLong(k, 0L)); }
                    } catch (Throwable ignored) { }
                }
                return clrMem;
            }

            void setClearedLocal(java.util.Map<String, Long> m) {
                clrMem = new java.util.HashMap<>(m);
                try {
                    org.json.JSONObject o = new org.json.JSONObject();
                    for (java.util.Map.Entry<String, Long> e : m.entrySet()) o.put(e.getKey(), e.getValue().longValue());
                    sp.edit().putString(CLR_KEY, o.toString()).apply();
                } catch (Throwable ignored) { }
            }

            /** chatClearedAt: الأكبر بين وقت حذفي المحلي وclearedAll الذي كتبه الطرف الآخر. */
            long chatClearedAt(String chatId) {
                Long l = getCleared().get(chatId), a = chatClearedAll.get(chatId);
                return Math.max(l == null ? 0L : l, a == null ? 0L : a);
            }

            boolean chatIsCleared(String chatId, long lastAt) {
                long c = chatClearedAt(chatId);
                return c > 0 && lastAt <= c;
            }

            long msgTs(DocumentSnapshot d) {
                try { Long t = d.getLong("timestamp"); if (t != null) return t; } catch (Throwable ignored) { }
                try { Long t = d.getLong("createdAt"); if (t != null) return t; } catch (Throwable ignored) { }
                return 0L;
            }

            boolean msgCleared(DocumentSnapshot d, long clr) {
                if (clr <= 0) return false;
                long t = msgTs(d);
                return t > 0 && t <= clr;
            }

            void syncChatStateToCloud() {
                if (chatStateRun != null) uiH.removeCallbacks(chatStateRun);
                chatStateRun = () -> {
                    try {
                        java.util.Map<String, Object> cs = new java.util.HashMap<>();
                        cs.put("pins", new ArrayList<>(getPins()));
                        cs.put("cleared", new java.util.HashMap<>(getCleared()));
                        java.util.Map<String, Object> up = new java.util.HashMap<>();
                        up.put("chatState", cs);
                        db.collection("users").document(myUid).set(up, com.google.firebase.firestore.SetOptions.merge());
                    } catch (Throwable ignored) { }
                };
                uiH.postDelayed(chatStateRun, 900);
            }

            /** syncChatStateFromUser: مرة واحدة عند تحميل ملفي — التثبيت إن كان المحلي فارغاً، والحذف بالأكبر. */
            void syncChatStateFromUser(DocumentSnapshot doc) {
                if (chatStateLoaded || doc == null) return;
                chatStateLoaded = true;
                try {
                    Object cso = doc.get("chatState");
                    if (!(cso instanceof Map)) return;
                    Map<?, ?> cs = (Map<?, ?>) cso;
                    Object po = cs.get("pins");
                    if (po instanceof List && getPins().isEmpty()) {
                        java.util.ArrayList<String> np = new java.util.ArrayList<>();
                        for (Object o : (List<?>) po) { if (np.size() >= MAX_CHAT_PINS) break; np.add(String.valueOf(o)); }
                        sp.edit().putString(PIN_KEY, new org.json.JSONArray(np).toString()).apply();
                    }
                    Object co = cs.get("cleared");
                    if (co instanceof Map) {
                        java.util.HashMap<String, Long> c = new java.util.HashMap<>(getCleared());
                        for (Map.Entry<?, ?> e : ((Map<?, ?>) co).entrySet()) {
                            String k = String.valueOf(e.getKey());
                            long v = e.getValue() instanceof Number ? ((Number) e.getValue()).longValue() : 0L;
                            Long cur = c.get(k);
                            c.put(k, Math.max(cur == null ? 0L : cur, v));
                        }
                        setClearedLocal(c);
                    }
                    pruneClearedChats();
                    buildChats();
                    buildStories();
                } catch (Throwable ignored) { }
            }

            /** يزيل من chatData كل محادثة لم يصلها شيء بعد وقت حذفها. */
            void pruneClearedChats() {
                for (java.util.Iterator<String[]> it = chatData.iterator(); it.hasNext(); ) {
                    String[] c = it.next();
                    if (c.length < 5) continue;
                    Long ts = chatTsMap.get(c[4]);
                    if (chatIsCleared(chatIdOf(c[4]), ts == null ? 0L : ts)) it.remove();
                }
            }

            /** reorderChatListItems: المثبّتة أولاً بترتيب التثبيت ثم الباقي بترتيب آخر نشاط. */
            java.util.List<String[]> orderedChats() {
                final java.util.List<String> pins = getPins();
                java.util.ArrayList<String[]> l = new java.util.ArrayList<>(chatData);
                Collections.sort(l, (a, b) -> {
                    int pa = a.length > 4 ? pins.indexOf(chatIdOf(a[4])) : -1;
                    int pb = b.length > 4 ? pins.indexOf(chatIdOf(b[4])) : -1;
                    if ((pa >= 0) != (pb >= 0)) return pa >= 0 ? -1 : 1;
                    if (pa >= 0) return Integer.compare(pa, pb);
                    return 0;
                });
                return l;
            }

            // ---- التحديد ----
            void enterChatSel(String uid) {
                chatSelMode = true;
                chatSel.clear(); chatSel.add(uid);
                if (stories != null) stories.freeze(true);   // شريط التحديد يغطي القصص ولا يُدخَل إليها أثناء التحديد
                ensureChatSelBar();
                updateChatSelBar();
                View mh = v("mainHeader");
                if (mh != null) mh.setVisibility(View.GONE);
                if (chatSelBar != null) { chatSelBar.setVisibility(View.VISIBLE); BabylonFx.barDown(chatSelBar, 0); }
                buildChats();
            }

            void toggleChatSel(String uid) {
                if (!chatSel.remove(uid)) chatSel.add(uid);
                if (chatSel.isEmpty()) exitChatSel(true);
                else { updateChatSelBar(); buildChats(); }
            }

            void exitChatSel(boolean rebuild) {
                boolean was = chatSelMode;
                chatSelMode = false;
                chatSel.clear();
                if (stories != null) stories.freeze(false);
                if (chatSelBar != null) chatSelBar.setVisibility(View.GONE);
                View mh = v("mainHeader");
                if (was && mh != null && "homeView".equals(curPage) && !chatOpen()) {
                    mh.setVisibility(View.VISIBLE);
                    BabylonFx.barDown(mh, 0);
                }
                if (rebuild && was) buildChats();
            }

            /** إن اختفت محادثة محددة من القائمة (حُذفت من الطرف الآخر مثلاً) تُزال من التحديد. */
            void syncChatSelWithData() {
                if (!chatSelMode) return;
                java.util.HashSet<String> have = new java.util.HashSet<>();
                for (String[] c : chatData) if (c.length > 4) have.add(c[4]);
                chatSel.retainAll(have);
                if (chatSel.isEmpty()) exitChatSel(false);
                else updateChatSelBar();
            }

            void updateChatSelBar() {
                if (chatSelCountTv != null) { chatSelCountTv.setText(chatSel.size() + " من المحادثات"); chatSelCountTv.setTextColor(cGold()); }
                // إطار ذهبي مربع بحواف 2dp حول ✕ والتثبيت والحذف (يُحدَّث لون الإطار مع الثيم)
                if (chatSelXIv != null) chatSelXIv.setBackground(goldFrame());
                if (chatSelDelIv != null) chatSelDelIv.setBackground(goldFrame());
                if (chatSelPinIv != null) {
                    chatSelPinIv.setBackground(goldFrame());
                    chatSelPinIv.setVisibility(chatSel.size() >= 2 ? View.GONE : View.VISIBLE);   // التثبيت لمحادثة واحدة فقط
                }
                // كل عناصر الشريط ذهبية في النهاري والليلي (تُحدَّث عند كل تحديث تحسباً لتبديل الثيم)
                if (chatSelXIv != null) chatSelXIv.setColorFilter(cGold());
                if (chatSelDelIv != null) chatSelDelIv.setColorFilter(cGold());
                if (chatSelPinIv != null) chatSelPinIv.setColorFilter(cGold());
                if (chatSelBar != null) chatSelBar.setBackgroundColor(cBg());
                if (chatSelPinIv != null) {
                    java.util.List<String> pins = getPins();
                    boolean all = !chatSel.isEmpty();
                    for (String u : chatSel) if (!pins.contains(chatIdOf(u))) { all = false; break; }
                    chatSelPinIv.setImageResource(all ? R.drawable.ic_sel_unpin : R.drawable.ic_sel_pin);
                    chatSelPinIv.setContentDescription(all ? "إلغاء التثبيت" : "تثبيت");
                }
            }

            /** #selBar في الـ HTML: ✕ + العدد عند البداية، وعند النهاية تثبيت | حذف (أحمر). يحلّ محل mainHeader. */
            void ensureChatSelBar() {
                if (chatSelBar != null) return;
                View mh = v("mainHeader");
                if (mh == null || !(mh.getParent() instanceof LinearLayout)) return;
                LinearLayout par = (LinearLayout) mh.getParent();
                LinearLayout row = new LinearLayout(act);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(12), 0, dp(12), 0);

                // الغلاف: يحمل لون الخلفية ويُظهر العدد في منتصف الشريط تماماً (بغض النظر عن أزرار الطرفين)
                FrameLayout wrap = new FrameLayout(act);
                wrap.setTag("bg");
                wrap.setBackgroundColor(cBg());
                wrap.setVisibility(View.GONE);

                chatSelXIv = noBg(selIcon(R.drawable.ic_mm_x, cGold(), cSf(), "إلغاء التحديد", () -> exitChatSel(true)));
                row.addView(chatSelXIv, new LinearLayout.LayoutParams(dp(42), dp(42)));

                chatSelCountTv = new TextView(act);
                chatSelCountTv.setTextSize(19); chatSelCountTv.setTextColor(cGold());
                chatSelCountTv.setTypeface(null, android.graphics.Typeface.BOLD);
                chatSelCountTv.setIncludeFontPadding(false);
                chatSelCountTv.setGravity(Gravity.CENTER);
                row.addView(new View(act), new LinearLayout.LayoutParams(0, 1, 1f));   // فاصل يدفع أزرار النهاية

                chatSelPinIv = noBg(selIcon(R.drawable.ic_sel_pin, cGold(), goldA(0x1F), "تثبيت", this::selPinChats));
                LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(dp(42), dp(42));
                pl.setMarginEnd(dp(8));
                row.addView(chatSelPinIv, pl);

                chatSelDelIv = noBg(selIcon(R.drawable.ic_sel_trash, cGold(), goldA(0x1F), "حذف", this::selDeleteChats));
                row.addView(chatSelDelIv, new LinearLayout.LayoutParams(dp(42), dp(42)));

                // أيقونات الشريط (إلغاء/تثبيت/حذف) أصغر بـ 3dp: الرمز 22dp ← 19dp داخل نفس الإطار 42dp
                for (ImageView si : new ImageView[]{chatSelXIv, chatSelPinIv, chatSelDelIv}) si.setPadding(dp(11.5f), dp(11.5f), dp(11.5f), dp(11.5f));
                wrap.addView(row, new FrameLayout.LayoutParams(-1, -1));
                wrap.addView(chatSelCountTv, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
                par.addView(wrap, par.indexOfChild(mh), new LinearLayout.LayoutParams(-1, dp(62)));
                chatSelBar = wrap;
            }

            // ---- التثبيت (selPin) ----
            void selPinChats() {
                java.util.ArrayList<String> ids = new java.util.ArrayList<>();
                for (String u : chatSel) ids.add(chatIdOf(u));
                if (ids.isEmpty()) return;
                java.util.List<String> pins = getPins();
                if (pins.containsAll(ids)) {
                    pins.removeAll(ids);
                    setPins(pins);
                } else {
                    java.util.ArrayList<String> np = new java.util.ArrayList<>(pins);
                    for (String id : ids) if (!np.contains(id)) np.add(0, id);
                    if (np.size() > MAX_CHAT_PINS) {
                        // الاستثناء الوحيد لإشعار Toast: رفض التثبيت لا يجوز أن يمرّ بصمت (مثل رسالة الـ HTML)
                        android.widget.Toast.makeText(act, "يمكنك تثبيت " + MAX_CHAT_PINS + " محادثات كحد أقصى", android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    setPins(np);
                }
                exitChatSel(true);
            }

            // ---- الحذف (selDelete / openDeleteChatsDialog / deleteChats) ----
            String chatNameOfUid(String uid) {
                for (String[] c : chatData) if (c.length > 4 && c[4].equals(uid)) return dispName(uid, c[0]);
                return "المستخدم";
            }

            void selDeleteChats() {
                if (chatSel.isEmpty()) return;
                final java.util.ArrayList<String> uids = new java.util.ArrayList<>(chatSel);
                final boolean one = uids.size() == 1;
                final String name = one ? chatNameOfUid(uids.get(0)) : "";
                MsgMenu.showChatDelete(act, dark,
                        one ? "حذف المحادثة" : "حذف " + uids.size() + " محادثات",
                        one ? "هل تريد حذف المحادثة مع " : "هل تريد حذف المحادثات المحددة؟",
                        name,
                        (one ? "؟" : "") + " لا يمكن التراجع عن هذا الإجراء.",
                        one ? "حذف أيضاً عند " + name : "حذف أيضاً عند الطرف الآخر",
                        both -> deleteChats(uids, both));
            }

            void deleteChats(java.util.List<String> uids, boolean both) {
                long now = System.currentTimeMillis();
                java.util.HashMap<String, Long> cl = new java.util.HashMap<>(getCleared());
                final java.util.HashMap<String, Long> stamps = new java.util.HashMap<>();
                java.util.List<String> pins = getPins();
                for (String u : uids) {
                    String cid = chatIdOf(u);
                    Long ts = chatTsMap.get(u);
                    long stamp = Math.max(now, ts == null ? 0L : ts);
                    cl.put(cid, stamp); stamps.put(cid, stamp);
                    pins.remove(cid);
                }
                setClearedLocal(cl);
                setPins(pins);                                   // يزامن chatState (التثبيت + الحذف) مع السحابة
                for (java.util.Iterator<String[]> it = chatData.iterator(); it.hasNext(); ) {
                    String[] c = it.next();
                    if (c.length > 4 && uids.contains(c[4])) it.remove();
                }
                exitChatSel(false);
                buildChats();
                try { buildStories(); } catch (Throwable ignored) { }
                if (!both) return;
                for (final java.util.Map.Entry<String, Long> e : stamps.entrySet()) {
                    final String cid = e.getKey();
                    java.util.Map<String, Object> d = new java.util.HashMap<>();
                    d.put("clearedAll", e.getValue());
                    db.collection("chats").document(cid).set(d, com.google.firebase.firestore.SetOptions.merge())
                        .addOnSuccessListener(x -> purgeChatMessages(cid, 0, 0));
                }
            }

            /** purgeChatMessages: يحذف رسائل المحادثة على دفعات 400؛ إن رفضت القواعد حذف الكل يحذف رسائلي فقط. */
            void purgeChatMessages(final String cid, final int mode, final int round) {
                if (round >= 30) return;
                Query q = db.collection("chats").document(cid).collection("messages").limit(400);
                if (mode == 1) q = q.whereEqualTo("sender", myUid);
                else if (mode == 2) q = q.whereEqualTo("senderId", myUid);
                q.get().addOnSuccessListener(qs -> {
                    if (qs.isEmpty()) { if (mode == 1) purgeChatMessages(cid, 2, 0); return; }
                    com.google.firebase.firestore.WriteBatch b = db.batch();
                    for (DocumentSnapshot d : qs.getDocuments()) b.delete(d.getReference());
                    b.commit().addOnSuccessListener(x -> purgeChatMessages(cid, mode, round + 1))
                        .addOnFailureListener(er -> { if (mode == 0) purgeChatMessages(cid, 1, 0); });
                }).addOnFailureListener(er -> { if (mode == 0) purgeChatMessages(cid, 1, 0); });
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
                if (stories != null) stories.collapseNow();   // دخول محادثة يطوي القصص
                if (chatSelMode) exitChatSel(false);
                BabylonFx.chatFoldBegin();   // الرسائل تنتظر انتهاء حركة طيّ الصفحة ثم تظهر بتلاشٍ ناعم
                otherUid = uid; otherName = name; curChat = name;
                startBlocksListener(); blocked = uid != null && myBlocks.contains(uid); blockedByThem = false; otherOrigName = name == null ? "" : name; muted = isMutedUid(uid);
                TextView chatTitle = tv("chatTitle");
                if (chatTitle != null) chatTitle.setText(dispName(uid, name));
                TextView chatAv = tv("chatAvatar");
                if (chatAv != null) chatAv.setText(name.length() > 0 ? name.substring(0, 1) : "?");
                ensurePeerPhoto(uid);   // صورة المستخدم في رأس المحادثة
                TextView chatSub = tv("chatSub");
                if (chatSub != null) chatSub.setText("\u200B");
                listenChatPresence(uid);
                clearSel();
                LinearLayout msgArea = (LinearLayout) v("messagesArea");
                if (msgArea != null) msgArea.removeAllViews();
                renderedMsgIds.clear(); lastDayKey = ""; msgViewsById.clear(); msgMeta.clear();
                pins = new ArrayList<>(); pinIdx = -1; renderPinnedBar();
                cancelReply();
                vis("chatSearchPanel", false);
                parkRecording();   // لا حذف: يُحفظ تسجيل المحادثة السابقة بدل إلغائه
                vis("recordingBar", false);

                if (messagesListener != null) messagesListener.remove();

                currentChatId = getChatId(myUid, uid);
                loadMessages(currentChatId);
                restoreRecording(currentChatId);   // تعود الصوتية المسجّلة إن وُجدت
                listenPins(currentChatId);
                vis("chatView", true);
                applyChatPhoto();
                final View cv = v("chatView");
                syncBars();
                if (cv != null) BabylonFx.chatGrowNow(cv, v("chatTopBar"), v("chatBottomBar"));
                applyChatBlockUi();
                checkBlockedByThem(uid);
            }

            String mediaQuote = null;   // اقتباس رد لرسالة صورة/صوتية قيد الرسم
            long chatStick = 0L;
            boolean chatUserTouched = false;   // لمس المستخدم المحادثة: توقّف عن التثبيت أسفلها
            boolean stickInstalled = false;

            /** يُظهر المحادثة عند آخر رسالة مباشرة (بدون وميض البداية): المحتوى مخفي حتى يكتمل أول رسم ثم يُنزَّل للأسفل. */
            void revealChatBottom() {
                final View ms = v("messagesScroll"), area = v("messagesArea");
                if (!(ms instanceof ScrollView) || area == null) return;
                chatStick = 0L;   // أُزيلت مهلة التثبيت (8 ثوانٍ): النزول لآخر رسالة مرة واحدة عند الدخول فقط
                chatUserTouched = false;
                final android.view.ViewTreeObserver vto = ms.getViewTreeObserver();
                vto.addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                    @Override public boolean onPreDraw() {
                        android.view.ViewTreeObserver o = ms.getViewTreeObserver();
                        if (o.isAlive()) o.removeOnPreDrawListener(this);
                        View c0 = ((ScrollView) ms).getChildAt(0);
                        if (c0 != null) ((ScrollView) ms).scrollTo(0, Math.max(0, c0.getHeight() + ms.getPaddingTop() + ms.getPaddingBottom() - ms.getHeight()));
                        area.animate().cancel();
                        area.setAlpha(0f);
                        area.animate().alpha(1f).setDuration(180).start();   // ظهور ناعم بعد انتهاء حركة الدخول
                        return true;
                    }
                });
                ms.invalidate();
            }

            void loadMessages(String chatId) {
                final boolean[] firstSnap = {true};
                {   // أخفِ الرسائل حتى يكتمل التحميل الأول (لا وميض ولا قفزة من أول المحادثة)
                    final View ms0 = v("messagesScroll"), area0 = v("messagesArea");
                    if (area0 != null) area0.setAlpha(0f);
                    if (!stickInstalled && area0 != null && ms0 instanceof ScrollView) {
                        stickInstalled = true;
                        ((ScrollView) ms0).setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);   // لا قفز تلقائي إلى رسالة بعينها
                        ms0.setOnTouchListener((vw, ev) -> { if (ev.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) chatUserTouched = true; return false; });
                        area0.addOnLayoutChangeListener((vv, l, t, r, b, ol, ot, orr, ob) -> {
                            if (System.currentTimeMillis() < chatStick && !chatUserTouched) {
                                View c0 = ((ScrollView) ms0).getChildAt(0);
                                if (c0 != null) ((ScrollView) ms0).scrollTo(0, Math.max(0, c0.getHeight() + ms0.getPaddingTop() + ms0.getPaddingBottom() - ms0.getHeight()));
                            }
                        });
                    }
                    uiH.postDelayed(() -> { View a1 = v("messagesArea"); if (a1 != null && a1.getAlpha() < 1f) a1.animate().alpha(1f).setDuration(120).start(); }, 900);
                }
                MsgViews.replyJump = (bv, rid) -> {
                    if (selecting) { toggleSel(bv); return; }
                    jumpToMsg(rid);
                };
                final com.google.firebase.firestore.EventListener<com.google.firebase.firestore.QuerySnapshot> onSnap = (snapshots, e) -> {
                        if (e != null || snapshots == null) return;
                        final boolean wasFirst = firstSnap[0];
                        firstSnap[0] = false;
                        final boolean rebuilt = !cacheRenderedIds.isEmpty() && reconcileCachedMsgs(snapshots);
                        final boolean liveBatch = !wasFirst && !rebuilt;
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
                                if (renderedMsgIds.contains(id)) { if (cacheRenderedIds.contains(id)) syncRenderedMsg(id, doc); continue; }
                                renderMsgData(chatId, id, doc.getData(), doc.getMetadata().hasPendingWrites(), liveBatch, doc.getReference());
                            }

                        }
                        saveMsgCache(chatId, snapshots);   // آخر 50 رسالة نصية للمحادثة في الخزن المحلي
                        if (!liveBatch) revealChatBottom();
                    };
                // لا تُعالَج الرسائل أثناء حركة الدخول (تفادياً للتقطيع والتداخل)؛ تُؤجَّل بالترتيب إلى نهايتها
                renderCachedMessages(chatId);   // آخر رسائل نصية محفوظة تظهر فوراً قبل وصول السيرفر
                messagesListener = db.collection("chats").document(chatId).collection("messages")
                    .orderBy("timestamp", Query.Direction.ASCENDING)
                    .addSnapshotListener(com.google.firebase.firestore.MetadataChanges.INCLUDE,
                        (sn, er) -> deferSnap(onSnap, chatId, sn, er));
            }

            // ================= خزن محلي لرسائل المحادثة (آخر 50 رسالة نصية فقط) =================
            final java.util.HashSet<String> cacheRenderedIds = new java.util.HashSet<>();   // رسائل رُسمت من الخزن وتنتظر تأكيد السيرفر
            long cacheMaxTs = 0L;

            String gStr(Map<String, Object> d, String k) { Object o = d == null ? null : d.get(k); return o instanceof String ? (String) o : null; }
            Long gLong(Map<String, Object> d, String k) { Object o = d == null ? null : d.get(k); return o instanceof Number ? Long.valueOf(((Number) o).longValue()) : null; }
            Boolean gBool(Map<String, Object> d, String k) { Object o = d == null ? null : d.get(k); return o instanceof Boolean ? (Boolean) o : null; }

            boolean msgClearedMap(Map<String, Object> d, long clr) {
                if (clr <= 0) return false;
                Long t = gLong(d, "timestamp");
                if (t == null) t = gLong(d, "createdAt");
                return t != null && t > 0 && t <= clr;
            }

            /** يرسم رسائل المحادثة المحفوظة محلياً (نصية فقط) فوراً. */
            void renderCachedMessages(String chatId) {
                cacheRenderedIds.clear(); cacheMaxTs = 0L;
                try {
                    org.json.JSONArray arr = LocalCache.getMessages(chatId);
                    if (arr == null || arr.length() == 0) return;
                    java.util.Set<String> hid = hiddenSet();
                    long clr = chatClearedAt(chatId);
                    for (int i = 0; i < arr.length(); i++) {
                        org.json.JSONObject o = arr.getJSONObject(i);
                        String id = o.optString("id");
                        if (id.isEmpty() || renderedMsgIds.contains(id) || hid.contains(id)) continue;
                        HashMap<String, Object> d = new HashMap<>();
                        d.put("type", "text");
                        d.put("text", o.optString("text"));
                        d.put("sender", o.optString("sender"));
                        long ts = o.optLong("timestamp", 0L);
                        if (ts > 0) d.put("timestamp", ts);
                        if (o.has("read")) d.put("read", o.optBoolean("read"));
                        if (o.has("forwarded")) d.put("forwarded", o.optBoolean("forwarded"));
                        for (String k : new String[]{"replyTo", "replyToSenderId", "replyToId", "replyToType"})
                            if (o.has(k)) d.put(k, o.optString(k));
                        if (msgClearedMap(d, clr)) continue;
                        renderMsgData(chatId, id, d, false, false, null);
                        if (msgViewsById.containsKey(id)) { cacheRenderedIds.add(id); if (ts > cacheMaxTs) cacheMaxTs = ts; }
                    }
                } catch (Throwable ignored) { }
            }

            /** يحفظ آخر 50 رسالة نصية من لقطة السيرفر (بدون الصور والفيديوهات والصوتيات والمخفية/المحذوفة). */
            void saveMsgCache(String chatId, com.google.firebase.firestore.QuerySnapshot sn) {
                try {
                    long clr = chatClearedAt(chatId);
                    java.util.Set<String> hid = hiddenSet();
                    org.json.JSONArray out = new org.json.JSONArray();
                    for (DocumentSnapshot dd : sn.getDocuments()) {
                        String ty = dd.getString("type");
                        if (ty != null && !"text".equals(ty)) continue;
                        Map<String, Object> m = dd.getData();
                        String text = gStr(m, "text");
                        if (text == null || hid.contains(dd.getId()) || msgClearedMap(m, clr)) continue;
                        org.json.JSONObject o = new org.json.JSONObject();
                        o.put("id", dd.getId());
                        o.put("text", text);
                        String sender = gStr(m, "sender");
                        o.put("sender", sender == null ? "" : sender);
                        Long ts = gLong(m, "timestamp");
                        if (ts != null) o.put("timestamp", ts.longValue());
                        Boolean rd = gBool(m, "read");
                        if (rd != null) o.put("read", rd.booleanValue());
                        Boolean fw = gBool(m, "forwarded");
                        if (fw != null) o.put("forwarded", fw.booleanValue());
                        for (String k : new String[]{"replyTo", "replyToSenderId", "replyToId", "replyToType"}) {
                            String v = gStr(m, k);
                            if (v != null) o.put(k, v);
                        }
                        out.put(o);
                    }
                    LocalCache.putMessages(chatId, out);
                } catch (Throwable ignored) { }
            }

            /** يوفّق الرسائل المرسومة من الخزن مع لقطة السيرفر. يرجع true إذا أُعيد بناء المحادثة بالكامل. */
            boolean reconcileCachedMsgs(com.google.firebase.firestore.QuerySnapshot sn) {
                boolean needRebuild = false;
                java.util.HashSet<String> srv = new java.util.HashSet<>();
                for (DocumentSnapshot dd : sn.getDocuments()) {
                    srv.add(dd.getId());
                    if (!renderedMsgIds.contains(dd.getId())) {
                        Long t = dd.getLong("timestamp");
                        // رسالة أقدم من آخر رسالة محفوظة (مثل صورة) ليست في الخزن: إضافتها بالنهاية تُفسد الترتيب
                        if (t != null && t < cacheMaxTs) { needRebuild = true; break; }
                    }
                }
                if (needRebuild) {
                    LinearLayout ma = (LinearLayout) v("messagesArea");
                    if (ma != null) ma.removeAllViews();
                    renderedMsgIds.clear(); lastDayKey = ""; msgViewsById.clear(); msgMeta.clear();
                    cacheRenderedIds.clear();
                    return true;
                }
                if (!sn.getMetadata().isFromCache()) {
                    // السيرفر أكّد: ما حُذف من الطرف الآخر يُزال من العرض
                    for (String id : new ArrayList<>(cacheRenderedIds)) if (!srv.contains(id)) removeMsgView(id, null);
                    cacheRenderedIds.clear();
                }
                return false;
            }

            /** رسالة رُسمت من الخزن ثم وصلت من السيرفر: حدّث علامات القراءة والتفاعلات وعلّمها مقروءة إن لزم. */
            void syncRenderedMsg(String id, DocumentSnapshot md) {
                View mv = msgViewsById.get(id);
                if (mv == null) return;
                try { applyReactions(id, mv, md.get("reactions")); } catch (Throwable ignored) { }
                boolean rd = Boolean.TRUE.equals(md.getBoolean("read"));
                if (myUid.equals(md.getString("sender"))) {
                    try { MsgViews.setState(mv, rd ? MsgViews.READ : (md.getMetadata().hasPendingWrites() ? MsgViews.WAIT : MsgViews.SENT)); } catch (Throwable ignored) { }
                } else if (!rd) {
                    try { md.getReference().update("read", true); } catch (Throwable ignored) { }
                }
            }

            /** رسم رسالة واحدة (من السيرفر أو من الخزن المحلي). ref = null للرسائل المحفوظة. */
            void renderMsgData(final String chatId, final String id, final Map<String, Object> d, final boolean pending,
                               final boolean liveBatch, final com.google.firebase.firestore.DocumentReference ref) {
                if (d == null) return;
                renderedMsgIds.add(id);
                                if (msgClearedMap(d, chatClearedAt(chatId))) return;   // قبل حذف المحادثة
                                if (hiddenSet().contains(id)) return;   // محذوفة "لدي فقط"

                                String type = gStr(d, "type");
                                if (type == null) type = "text";
                                String text = gStr(d, "text");
                                String rTo = gStr(d, "replyTo");
                                if (text != null && rTo != null && rTo.length() > 0 && !text.startsWith("\u21A9 ")) {
                                    String rSid = gStr(d, "replyToSenderId");
                                    String rNm = rSid == null || rSid.isEmpty() ? "" : replyOwner(rSid.equals(myUid));
                                    String rId = gStr(d, "replyToId"), rTy = gStr(d, "replyToType");
                                    text = "\u21A9 " + (rNm.isEmpty() ? "" : rNm + "\u0001") + rTo.replace('\n', ' ').replace('\u0002', ' ')
                                            + "\u0002" + (rId == null ? "" : rId) + "\u0002" + (rTy == null ? "" : rTy) + "\n" + text;
                                }
                                mediaQuote = null;
                                if (rTo != null && rTo.length() > 0 && ("image".equals(type) || "audio".equals(type))) {
                                    String qSid = gStr(d, "replyToSenderId");
                                    String qNm = qSid == null || qSid.isEmpty() ? "" : replyOwner(qSid.equals(myUid));
                                    String qId = gStr(d, "replyToId"), qTy = gStr(d, "replyToType");
                                    mediaQuote = (qNm.isEmpty() ? "" : qNm + "\u0001") + rTo.replace('\n', ' ').replace('\u0002', ' ')
                                            + "\u0002" + (qId == null ? "" : qId) + "\u0002" + (qTy == null ? "" : qTy);
                                }
                                String url = gStr(d, "data");
                                if (url == null) url = gStr(d, "url");
                                String sender = gStr(d, "sender");
                                Long time = gLong(d, "timestamp");
                                Long dur = gLong(d, "duration");
                                boolean mine = myUid.equals(sender);
                                final long ts = time != null ? time : System.currentTimeMillis();
                                String timeStr = timeLabel(ts);
                                Boolean readFlag = gBool(d, "read");
                                boolean isRead = Boolean.TRUE.equals(readFlag);
                                if (!mine && !isRead) { try { if (ref != null) ref.update("read", true); } catch (Throwable ignored) { } }
                                int state = mine ? (isRead ? MsgViews.READ : (pending ? MsgViews.WAIT : MsgViews.SENT)) : MsgViews.SENT;

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
                                        if (Boolean.TRUE.equals(gBool(d, "forwarded")) && last instanceof LinearLayout && !(last instanceof MsgViews.VoiceBubble)) {
                                            TextView fl = new TextView(act);
                                            fl.setText("↪ معاد توجيهها"); fl.setTextSize(11); fl.setTypeface(null, android.graphics.Typeface.BOLD_ITALIC);
                                            fl.setTag(mine ? "mmM" : "mmO");
                                            fl.setPadding(dp(2), 0, dp(2), dp(3));
                                            ((LinearLayout) last).addView(fl, 0);
                                            style(fl);
                                        }
                                        if (mine) MsgViews.setState(last, state);
                                        applyReactions(id, last, d.get("reactions"));
                                    }
                                    scrollDown();
                                    if (liveBatch) {
                                        LinearLayout ma = (LinearLayout) v("messagesArea");
                                        if (ma != null && ma.getChildCount() > 0) BabylonFx.msgIn(ma.getChildAt(ma.getChildCount() - 1));
                                    }
                                } catch (Throwable ignored) { }

            }

            void deferSnap(final com.google.firebase.firestore.EventListener<com.google.firebase.firestore.QuerySnapshot> l,
                           final String cid, final com.google.firebase.firestore.QuerySnapshot sn,
                           final com.google.firebase.firestore.FirebaseFirestoreException er) {
                long wait = BabylonFx.chatFoldRemaining();
                if (wait <= 0) { l.onEvent(sn, er); return; }
                uiH.postDelayed(() -> { if (cid.equals(currentChatId)) deferSnap(l, cid, sn, er); }, wait);
            }

            com.google.firebase.firestore.ListenerRegistration chatPresenceReg;

            /** حالة الطرف الآخر في رأس المحادثة (مطابق لـ userUnsubscribe في الـ HTML):
             *  مخفي → «آخر ظهور منذ فترة»، متصل خلال 45ث → «متصل الآن» (أخضر)، غير ذلك → «آخر ظهور كان قريباً»،
             *  ومن حظرني → «آخر ظهور كان منذ زمن طويل». */
            void listenChatPresence(final String uid) {
                if (chatPresenceReg != null) { chatPresenceReg.remove(); chatPresenceReg = null; }
                if (uid == null) return;
                chatPresenceReg = db.collection("users").document(uid).addSnapshotListener((d, e) -> {
                    if (e != null || d == null || !d.exists() || !uid.equals(otherUid)) return;
                    TextView cs = tv("chatSub");
                    if (cs == null) return;
                    boolean hid = Boolean.TRUE.equals(d.getBoolean("hideLastSeen"));
                    Long ls = d.getLong("lastSeen");
                    boolean live = !hid && Boolean.TRUE.equals(d.getBoolean("online"))
                            && ls != null && System.currentTimeMillis() - ls < 45000;
                    String txt; int col = Tk.crMuted(cMuted());
                    if (blockedByThem && !myBlocks.contains(uid)) txt = "آخر ظهور كان منذ زمن طويل";
                    else if (hid) txt = "آخر ظهور منذ فترة";
                    else if (live) { txt = "متصل الآن"; col = 0xFF4ADE80; }
                    else txt = "آخر ظهور كان قريباً";
                    cs.setText(txt);
                    cs.setTextColor(col);
                });
            }

            void closeChat() {
                parkRecording();   // الخروج لا يحذف الصوتية المسجّلة
                if (chatPresenceReg != null) { chatPresenceReg.remove(); chatPresenceReg = null; }
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
                    tick.setImageDrawable(new MsgViews.TickDrawable(act, MsgViews.SENT, 0, Tk.tickColor(MsgViews.SENT)));
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
                if (mediaQuote != null) {   // اقتباس الرد فوق الصورة (اسم صاحب الرسالة + النص)، والنقر ينتقل للأصلية
                    View qv = MsgViews.quoteView(act, mediaQuote, true);
                    FrameLayout.LayoutParams qlp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
                    qlp.setMargins(dp(6), dp(6), dp(6), 0);
                    frame.addView(qv, qlp);
                    style(qv);
                    GradientDrawable qg = new GradientDrawable();
                    qg.setColor(0xB3000000); qg.setCornerRadius(dp(10));
                    qv.setBackground(qg);
                }
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
                if (mediaQuote != null) MsgViews.addQuote(act, box, mediaQuote, mine);   // اقتباس الرد داخل فقاعة الصوتية
                LinearLayout msgArea = (LinearLayout) v("messagesArea");
                if (msgArea != null) msgArea.addView(box);
                style(box);
            }

            String formatDuration(long ms) {
                long s = ms / 1000;
                return String.format(Locale.getDefault(), "%d:%02d", s / 60, s % 60);
            }

            void playAudio(String url, final MsgViews.Handle h, final long fallbackMs) {
                chatUserTouched = true;   // لمس الصوتية (ابن للـ ScrollView لا يمرّ على مستمع اللمس): أوقف تثبيت أسفل المحادثة
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
                            if (b.length > 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && Build.VERSION.SDK_INT >= 28) {   // GIF متحرك
                                try {
                                    final android.graphics.drawable.Drawable dr = android.graphics.ImageDecoder.decodeDrawable(
                                            android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(b)));
                                    act.runOnUiThread(() -> {
                                        img.setImageDrawable(dr);
                                        if (dr instanceof android.graphics.drawable.AnimatedImageDrawable) {
                                            android.graphics.drawable.AnimatedImageDrawable ad = (android.graphics.drawable.AnimatedImageDrawable) dr;
                                            ad.setRepeatCount(android.graphics.drawable.AnimatedImageDrawable.REPEAT_INFINITE);
                                            ad.start();
                                        }
                                    });
                                    return;
                                } catch (Throwable ignored) { }
                            }
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
                        replyId = idOfView(b); replyMine = mine; replyKind = kind == 1 ? "image" : kind == 2 ? "audio" : "text";
                        String ownerNm = replyOwner(mine);
                        setLabel("replyLabel", ownerNm.isEmpty() ? "الرد على الرسالة" : "الرد على " + ownerNm, icon("ic_reply", 16));
                        { TextView rl_ = tv("replyLabel"); if (rl_ != null) { rl_.setCompoundDrawablePadding(dp(5)); rl_.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, icon("ic_reply", 16), 0); style(rl_); } }
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
                        { TextView rl_ = tv("replyLabel"); if (rl_ != null) { rl_.setCompoundDrawablePadding(dp(5)); rl_.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, icon("ic_edit", 16), 0); style(rl_); } }
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
                    if (StoryViewer.handleBack()) return true;
                    if (MsgMenu.isShowing()) { MsgMenu.dismiss(); return true; }
                    if (chatSelMode) { exitChatSel(true); return true; }
                    if (selecting) { clearSel(); return true; }
                    if (stories != null && stories.isExpanded()) { stories.setCollapsed(true); return true; }   // الرجوع يطوي شريط القصص أولاً

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
                        closeChat();   // الرجوع لا يحذف التسجيل (يُحفظ ويعود عند دخول المحادثة)
                        return true;
                    }

                    // صورة الملف الشخصي المكبّرة تُغلق أولاً
                    if (heroMe != null && heroMe.isExpanded()) { heroMe.reset(); return true; }
                    if (heroOther != null && heroOther.isExpanded()) { heroOther.reset(); return true; }

                    switch (curPage) {
                        case "otherUserProfileView":
                            leaveOtherProfile();
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

            /** إطار ذهبي يحيط بالرسالة المحددة من كل الجهات، خارجها بمسافة 2dp (.selected-message: outline 2px + offset 2px). */
            final java.util.HashMap<View, Object[]> selRings = new java.util.HashMap<>();

            /** انحناءات الفقاعة (TL,TR,BR,BL) + إزاحة: الزاوية المدبّبة 6dp (أسفل اليمين للمرسلة وأسفل اليسار للمستلمة). */
            float[] bubbleRadii(View mv, float add) {
                float big = (mv instanceof FrameLayout ? dp(12) : dp(radius)) + add, tail = dp(6) + add;
                float[] r = {big, big, big, big, big, big, big, big};
                Object t = mv.getTag();
                String ts = t instanceof String ? (String) t : "";
                if (ts.contains("bubMine")) { r[4] = tail; r[5] = tail; }
                else if (ts.contains("bubOther")) { r[6] = tail; r[7] = tail; }
                return r;
            }

            void markSel(final View mv, boolean on) {
                if (mv == null) return;
                Object[] old = selRings.remove(mv);
                if (old != null) {
                    try { ((ViewGroup) old[0]).getOverlay().remove((Drawable) old[1]); ((ViewGroup) old[0]).invalidate(); } catch (Throwable ignored) { }
                }
                if (Build.VERSION.SDK_INT >= 23) {
                    if (!on) { mv.setForeground(null); }
                    else {
                        GradientDrawable g = new GradientDrawable();      // تظليل خفيف داخل الرسالة بلا حدّ
                        g.setColor(goldA(0x26));
                        g.setCornerRadii(bubbleRadii(mv, 0));
                        mv.setForeground(g);
                    }
                } else mv.setAlpha(on ? 0.72f : 1f);
                if (!on || !(mv.getParent() instanceof ViewGroup)) return;
                final ViewGroup par = (ViewGroup) mv.getParent();
                par.setClipChildren(false);
                if (par.getParent() instanceof ViewGroup) { ((ViewGroup) par.getParent()).setClipChildren(false); }
                final int gap = dp(1), stroke = dp(2);      // الإطار يبعد 1dp فقط عن الفقاعة
                final android.graphics.Paint pt = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                pt.setStyle(android.graphics.Paint.Style.STROKE);
                pt.setStrokeWidth(stroke);
                pt.setColor(cGold());
                final android.graphics.Path path = new android.graphics.Path();
                Drawable ring = new Drawable() {
                    @Override public void draw(android.graphics.Canvas c) {     // يقرأ مكان الرسالة لحظياً فيتبعها
                        float in = gap + stroke / 2f;
                        // لا يلتصق إطاران متجاوران: نقصّ الامتداد العمودي بحيث تبقى بينهما مسافة
                        float vin = in;
                        ViewGroup pr = (ViewGroup) mv.getParent();
                        if (pr != null) {
                            int i = pr.indexOfChild(mv);
                            float room = Float.MAX_VALUE;
                            if (i > 0) room = Math.min(room, mv.getTop() - pr.getChildAt(i - 1).getBottom());
                            if (i >= 0 && i < pr.getChildCount() - 1) room = Math.min(room, pr.getChildAt(i + 1).getTop() - mv.getBottom());
                            if (room < Float.MAX_VALUE) vin = Math.max(stroke / 2f, Math.min(in, room / 2f - dp(0.5f) - stroke / 2f));
                        }
                        float tx = mv.getTranslationX(), ty = mv.getTranslationY();
                        android.graphics.RectF rc = new android.graphics.RectF(mv.getLeft() + tx - in, mv.getTop() + ty - vin,
                                mv.getRight() + tx + in, mv.getBottom() + ty + vin);
                        path.reset();
                        // نفس انحناءات الفقاعة (مع الزاوية المدبّبة الصغيرة) مضافاً إليها الإزاحة
                        path.addRoundRect(rc, bubbleRadii(mv, in), android.graphics.Path.Direction.CW);
                        c.drawPath(path, pt);
                    }
                    @Override public void setAlpha(int a) { pt.setAlpha(a); }
                    @Override public void setColorFilter(android.graphics.ColorFilter cf) { pt.setColorFilter(cf); }
                    @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
                };
                ring.setBounds(-dp(8), -dp(8), Math.max(par.getWidth(), mv.getRight()) + dp(8), Math.max(par.getHeight(), mv.getBottom()) + dp(8));
                par.getOverlay().add(ring);
                par.invalidate();
                selRings.put(mv, new Object[]{par, ring});
            }

            String firstSel() { return selIds.isEmpty() ? null : selIds.iterator().next(); }

            void refreshSel() {
                if (selIds.isEmpty()) { clearSel(); return; }
                for (Map.Entry<String, View> en : msgViewsById.entrySet()) markSel(en.getValue(), selIds.contains(en.getKey()));
                if (selBar == null) buildSelBar();
                if (selBar == null) return;
                selBar.setVisibility(View.VISIBLE);
                // حقل الكتابة يبقى ظاهراً أثناء التحديد
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
            /** أيقونة بلا مربّع خلفها (شريط تحديد المحادثات). */
            ImageView noBg(ImageView iv) { iv.setBackground(null); return iv; }

            /** مربع شفاف بحدّ ذهبي وبنفس انحناء مربع العدسة (14dp). */
            android.graphics.drawable.Drawable goldFrame() {
                android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
                g.setColor(0x00000000);
                g.setCornerRadius(dp(14));
                g.setStroke(Math.max(1, dp(1.5f)), cGold());
                return g;
            }

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
                row.addView(selIcon(R.drawable.ic_mm_x, cGold(), goldA(0x1F), "إلغاء التحديد", this::clearSel),
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
                uiH.postDelayed(() -> flashMsg(mv, id), 350);
            }

            private final Map<View, android.animation.ValueAnimator> flashAnims = new HashMap<>();

            /** تمييز الرسالة المردود عليها: رسالتي أبيض 20%، والمستلَمة بلون الثيم؛ ينبض مرتين ويتلاشى + نبضة تكبير خفيفة، بلا أي إطار. */
            void flashMsg(final View mv, final String id) {
                if (mv == null) return;
                android.animation.ValueAnimator prev = flashAnims.remove(mv);
                if (prev != null) prev.cancel();
                // رسالتي: أبيض بنسبة 20% — رسالة المستلَم: لون الثيم (accent) أو الذهبي في ثيم بابل
                Object[] fm = id != null ? msgMeta.get(id) : null;
                final boolean fMine = fm != null && Boolean.TRUE.equals(fm[4]);
                final float peak = fMine ? 0.20f : 0.42f;
                final GradientDrawable g = new GradientDrawable();
                g.setColor(fMine ? 0xFFFFFFFF : (Tk.chatPal != null ? (Tk.chatPal.accent | 0xFF000000) : cGold()));
                g.setCornerRadii(bubbleRadii(mv, 0));
                g.setAlpha(0);
                final boolean fg = Build.VERSION.SDK_INT >= 23;
                if (fg) mv.setForeground(g);
                final android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(0f, 1f);
                va.setDuration(1500);
                va.setInterpolator(new android.view.animation.LinearInterpolator());
                va.addUpdateListener(a -> {
                    float t = (float) a.getAnimatedValue();
                    float pulse = (float) (0.5 - 0.5 * Math.cos(4 * Math.PI * t));          // نبضتان
                    float fade = 1f - 0.55f * t;                                            // تخفت تدريجياً
                    float k = pulse * fade;
                    if (fg) g.setAlpha(Math.round(255 * peak * k));
                    else mv.setAlpha(1f - (fMine ? 0.20f : 0.3f) * k);
                    float sc = 1f + 0.025f * (float) Math.sin(Math.PI * Math.min(1f, t * 1.6f));
                    mv.setScaleX(sc); mv.setScaleY(sc);
                });
                final Runnable done = () -> {
                    flashAnims.remove(mv);
                    mv.setScaleX(1f); mv.setScaleY(1f); mv.setAlpha(1f);
                    if (fg) { if (selIds.contains(id)) markSel(mv, true); else mv.setForeground(null); }
                };
                va.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(android.animation.Animator an) { done.run(); }
                    @Override public void onAnimationCancel(android.animation.Animator an) { done.run(); }
                });
                flashAnims.put(mv, va);
                va.start();
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
                bar.setBackground(shape(Tk.crCard(cSf()), 18, Tk.crLine(cBorder())));   // .pinned-bar{background:var(--cr-card);border:1px solid var(--cr-line)}
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
                blp.setMargins(dp(10), dp(2), dp(10), dp(6));
                bar.setLayoutParams(blp);

                LinearLayout line = new LinearLayout(act);
                line.setOrientation(LinearLayout.VERTICAL);
                for (int i = 0; i < n; i++) {
                    View seg = new View(act);
                    GradientDrawable sg = new GradientDrawable();
                    sg.setCornerRadius(dp(14));
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
                tx.setTextSize(13.5f); tx.setTextColor(Tk.crInk(cText())); tx.setAlpha(0.85f);
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
                xb.setImageResource(R.drawable.ic_mm_unpin); xb.setColorFilter(Tk.crMuted(cMuted()));
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

            /** replyOwnerName في الـ HTML: "أنت" لرسالتي، وإلا اسم الطرف الآخر (مع الاسم المخصص). */
            String replyOwner(boolean mineMsg) {
                if (mineMsg) return "أنت";
                return dispName(otherUid, otherOrigName.isEmpty() ? otherName : otherOrigName);
            }

            void cancelReply() { replyText = null; replyId = null; replyKind = "text"; editing = null; vis("replyPreviewBar", false); }
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
                if (blockedByThem) return;   // حظرك الطرف الآخر: لا تُرسل
                if (currentChatId.isEmpty() || otherUid.isEmpty()) return;

                String fullText = t;   // نص الرسالة بلا بادئة؛ الرد في حقول replyTo* مثل الـ HTML

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
                if (replyText != null) {
                    msg.put("replyTo", replyText);
                    msg.put("replyToId", replyId == null ? "" : replyId);
                    msg.put("replyToSenderId", replyMine ? myUid : otherUid);
                    msg.put("replyToType", replyKind);
                }
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

            void sendMedia(String type, String base64Data, long duration) { sendMedia(type, base64Data, duration, null); }

            /** ملصق/GIF/صورة قادمة من لوحة المفاتيح: GIF وPNG وWebP الصغيرة تُرسل كما هي (تحافظ على الحركة والشفافية). */
            void sendKeyboardContent(final Uri uri, final String mime, final Runnable release) {
                if (currentChatId.isEmpty() || otherUid.isEmpty() || blocked || blockedByThem) { if (release != null) release.run(); return; }
                new Thread(() -> {
                    try {
                        boolean keep = "image/gif".equals(mime) || "image/png".equals(mime) || "image/webp".equals(mime);
                        String b64 = null, outMime = mime;
                        if (keep) {
                            byte[] raw = readCapped(uri, 600 * 1024);
                            if (raw != null) b64 = android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP);
                        }
                        if (b64 == null) {
                            if ("image/gif".equals(mime)) throw new Exception("ملف الـ GIF كبير جداً");
                            b64 = encodeImage(uri, 1280, 640 * 1024, false);
                            outMime = "image/jpeg";
                        }
                        final String fb = b64, fm = outMime;
                        act.runOnUiThread(() -> sendMedia("image", fb, 0, fm));
                    } catch (Exception e) {
                        act.runOnUiThread(() -> toast("فشل إرسال المحتوى: " + e.getMessage()));
                    } finally {
                        if (release != null) release.run();
                    }
                }).start();
            }

            /** يقرأ الملف كاملاً، أو يرجع null إن تجاوز الحدّ (حدّ مستند Firestore 1MB). */
            byte[] readCapped(Uri uri, int cap) throws Exception {
                java.io.InputStream in = act.getContentResolver().openInputStream(uri);
                if (in == null) return null;
                try {
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[16384]; int n, total = 0;
                    while ((n = in.read(buf)) > 0) { total += n; if (total > cap) return null; bos.write(buf, 0, n); }
                    return bos.toByteArray();
                } finally { in.close(); }
            }

            void sendMedia(String type, String base64Data, long duration, String mime) {
                if (currentChatId.isEmpty() || otherUid.isEmpty() || blocked || blockedByThem) return;
                Map<String, Object> msg = new HashMap<>();
                msg.put("type", type);
                msg.put("data", base64Data);
                msg.put("duration", duration);
                if (mime != null) msg.put("mime", mime);
                msg.put("sender", myUid);
                msg.put("timestamp", System.currentTimeMillis());
                if (replyText != null) {   // الرد على رسالة يشمل الصور والصوتيات أيضاً (bbReplyFields في الـ HTML)
                    msg.put("replyTo", replyText);
                    msg.put("replyToId", replyId == null ? "" : replyId);
                    msg.put("replyToSenderId", replyMine ? myUid : otherUid);
                    msg.put("replyToType", replyKind);
                }
                db.collection("chats").document(currentChatId).collection("messages")
                    .add(msg);
                cancelReply();

                String preview = type.equals("image") ? ("image/gif".equals(mime) ? "🎞 GIF" : "📷 صورة")
                        : type.equals("audio") ? "🎤 رسالة صوتية" : "";
                updateChatMeta(preview);
            }

            void pickImage() {
                if (blocked || blockedByThem) return;
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
                t.setBackground(t == tv("pAvatarText") ? withRing(d, true) : d);
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
                try { buildStories(); } catch (Throwable ignored) { }   // صورتي في «قصتي» بعد وصولها
            }

            void toggleRecording() {
                if (blocked || blockedByThem) return;
                if (!isRecording && !recPreview) startRecord();
            }

            // ---------------- التسجيل الصوتي: مطابق لـ #recordingBar في الـ HTML (تسجيل ← معاينة ← إرسال)
            boolean recPreview = false, recPlaying = false, forceAac = false;
            /** تسجيلات معاينة محفوظة لكل محادثة (chatId → {File, مدة بالمللي ثانية}) تبقى عند الخروج وتعود عند دخول نفس المحادثة. */
            final java.util.HashMap<String, Object[]> savedRec = new java.util.HashMap<>();
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
                    b.setBackground(shape(Tk.chatPal != null ? Tk.chatPal.mineFrom : cGold(), 2, 0));   // موجة التسجيل تتبع الثيم
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
                if (dot != null && (!recPreview || recPlaying)) {      // recordingPulse 1s (يستمر الخفقان أثناء تشغيل المعاينة)
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
                if (lb != null) { lb.setText(pv ? "تم التسجيل" : "جاري التسجيل"); lb.setTextColor(pv ? (Tk.chatPal != null ? Tk.chatPal.accent : (dark ? 0xFFE4C45B : 0xFFB8861B)) : 0xFFF87171); } // «تم التسجيل» ذهبي
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
                vis("textInputBar", !on && !blocked);
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
                enterPreview();
            }

            /** يدخل وضع الاستماع قبل الإرسال للملف audioFile بمدة recDurMs. */
            void enterPreview() {
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

            /** عند مغادرة المحادثة: التسجيل الجاري يُنهى ويُحفظ، والمعاينة تُحفظ (لا حذف) لتعود عند دخول نفس المحادثة. */
            void parkRecording() {
                try {
                    if (isRecording) stopRecordPreview();          // يتحول لمعاينة (أو يُحذف إن كان أقصر من 0.8ث)
                    if (recPreview && audioFile != null && audioFile.exists() && currentChatId != null && !currentChatId.isEmpty()) {
                        releaseRecPlayer();
                        savedRec.put(currentChatId, new Object[]{audioFile, recDurMs});
                        recPreview = false; audioFile = null;
                    } else if (recPreview) {
                        cancelRecord();
                    }
                    showRecordingUi(false);
                } catch (Throwable ignored) { }
            }

            /** يستعيد معاينة التسجيل المحفوظة لهذه المحادثة إن وُجدت. */
            void restoreRecording(String chatId) {
                Object[] r = savedRec.remove(chatId);
                if (r == null) return;
                File f = (File) r[0];
                if (f == null || !f.exists() || blocked) { if (f != null) f.delete(); return; }
                showRecordingUi(true);
                audioFile = f; recDurMs = (Long) r[1];
                enterPreview();
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
                final int mAcc = Tk.chatPal != null ? Tk.chatPal.accent : cGold();   // قائمة ⋮ تتبع ثيم المحادثة
                GradientDrawable pr = new GradientDrawable(); pr.setColor((mAcc & 0x00FFFFFF) | (0x26 << 24)); pr.setCornerRadius(dp(15));
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
                ic.setColorFilter(mAcc);
                ic.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                ic.setPadding(dp(8), dp(8), dp(8), dp(8));
                GradientDrawable ibg = new GradientDrawable(); ibg.setColor((mAcc & 0x00FFFFFF) | (0x24 << 24)); ibg.setCornerRadius(dp(13));
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

            /** بحث المستخدمين: كل الواجهة والمنطق في SearchPage (مطابقة لصفحة البحث في الـ HTML). */
            void searchUsers(String q) {
                if (searchPage != null) searchPage.onQuery(q);
            }

            // ====================== ثيم وخلفية المحادثة (buildThemeStrip / setTheme / applyPrefs في الـ HTML) ======================
            /** شريط الثيمات: بطاقات 96dp، مصغّر 132dp بحدّ 3dp (ذهبي للمحدد وبحجم 1.03)، فقاعتان وزخرفة الثيم والرمز الكبير والاسم. */
            void buildThemeStrip() {
                LinearLayout s = (LinearLayout) v("themeStrip");
                if (s == null) return;
                s.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
                if (s.getParent() instanceof View) ((View) s.getParent()).setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
                s.removeAllViews();
                s.setPadding(dp(4), dp(4), dp(4), dp(12));
                for (final ChatThemes.Spec spc : ChatThemes.ALL) {
                    final ChatThemes.Pal p = spc.pal(dark);
                    boolean sel = spc.id.equals(chatTheme);
                    LinearLayout card = new LinearLayout(act);
                    card.setOrientation(LinearLayout.VERTICAL);
                    LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(96), -2);
                    cp.setMarginEnd(dp(10));
                    card.setLayoutParams(cp);

                    FrameLayout thumb = new FrameLayout(act);
                    thumb.setPadding(dp(3), dp(3), dp(3), dp(3));
                    GradientDrawable tb = new GradientDrawable();
                    tb.setColor(p.chatBg);
                    tb.setCornerRadius(dp(14));
                    tb.setStroke(dp(3), sel ? cGold() : 0x00000000);
                    thumb.setBackground(tb);
                    thumb.setClipToOutline(true);
                    thumb.setOutlineProvider(new android.view.ViewOutlineProvider() {
                        @Override public void getOutline(View vw, android.graphics.Outline o) { o.setRoundRect(0, 0, vw.getWidth(), vw.getHeight(), dp(14)); }
                    });
                    card.addView(thumb, new LinearLayout.LayoutParams(-1, dp(132)));
                    if (sel) { thumb.setScaleX(1.03f); thumb.setScaleY(1.03f); }

                    ChatPattern pat = new ChatPattern(act).grid(3, 34f, 15f, 24)
                            .style(spc.symbols, p.pattern, Math.min(0.4f, p.patternOp * 2.4f));
                    thumb.addView(pat, new FrameLayout.LayoutParams(-1, -1));

                    float inner = 96f - 6f;   // عرض صندوق الحشوة داخل الحدّ
                    float[] r12 = {dp(12), dp(12), dp(12), dp(12), dp(12), dp(12), dp(12), dp(12)};
                    View thMine = new View(act);
                    thMine.setBackground(ChatThemes.bubble(p, true, r12, 0f));
                    FrameLayout.LayoutParams lm = new FrameLayout.LayoutParams(dp(inner * 0.72f), dp(22), Gravity.TOP | Gravity.LEFT);
                    lm.leftMargin = dp(inner * 0.16f); lm.topMargin = dp(16);
                    thumb.addView(thMine, lm);
                    View thOther = new View(act);
                    thOther.setBackground(ChatThemes.bubble(p, false, r12, 0f));
                    FrameLayout.LayoutParams lo = new FrameLayout.LayoutParams(dp(inner * 0.62f), dp(22), Gravity.TOP | Gravity.LEFT);
                    lo.leftMargin = dp(inner * 0.12f); lo.topMargin = dp(48);
                    thumb.addView(thOther, lo);
                    ChatPattern.Glyph hero = new ChatPattern.Glyph(act).set(spc.hero, p.pattern);
                    FrameLayout.LayoutParams lh = new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
                    lh.bottomMargin = dp(8);
                    thumb.addView(hero, lh);

                    TextView nm = new TextView(act);
                    nm.setText(spc.name);
                    nm.setTextSize(12);
                    nm.setGravity(Gravity.CENTER);
                    nm.setTextColor(sel ? cGold() : cMuted());
                    nm.setTypeface(null, sel ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                    LinearLayout.LayoutParams ln = new LinearLayout.LayoutParams(-1, -2);
                    ln.topMargin = dp(6);
                    card.addView(nm, ln);

                    final String id = spc.id;
                    card.setOnClickListener(x -> setChatTheme(id));
                    s.addView(card);
                }
            }

            void setChatTheme(String id) {
                if (!ChatThemes.has(id)) return;
                chatTheme = id;
                saveChatPrefs();
                syncChatTheme();
                if (chatPreview != null) chatPreview.apply(chatTheme, dark, fontSp, radius);
                try { buildThemeStrip(); } catch (Throwable ignored) { }
                style(v("chatView"));
                try { syncBars(); } catch (Throwable ignored) { }
            }

            void saveChatPrefs() {
                sp.edit().putString("chatTheme", chatTheme).putInt("font", fontSp).putInt("radius", radius).apply();
                syncPrefsToCloud();
            }

            /** users/{uid}.chatPrefs = {theme, font, radius} بعد 800ms من آخر تغيير (syncPrefsToCloud في الـ HTML) */
            void syncPrefsToCloud() {
                if (prefsSyncRun != null) uiH.removeCallbacks(prefsSyncRun);
                prefsSyncRun = () -> {
                    if (db == null || myUid == null || myUid.isEmpty()) return;
                    Map<String, Object> cp = new HashMap<>();
                    cp.put("theme", chatTheme); cp.put("font", fontSp); cp.put("radius", radius);
                    Map<String, Object> m = new HashMap<>();
                    m.put("chatPrefs", cp);
                    try { db.collection("users").document(myUid).set(m, com.google.firebase.firestore.SetOptions.merge()); } catch (Throwable ignored) { }
                };
                uiH.postDelayed(prefsSyncRun, 800);
            }

            /** عند أول تحميل لوثيقة المستخدم: تُعتمد chatPrefs السحابية (syncPrefsFromUser في الـ HTML) */
            void syncPrefsFromUser(com.google.firebase.firestore.DocumentSnapshot doc) {
                if (prefsFromCloudDone || doc == null) return;
                prefsFromCloudDone = true;
                Object o = doc.get("chatPrefs");
                if (!(o instanceof Map)) { syncPrefsToCloud(); return; }
                Map<?, ?> c = (Map<?, ?>) o;
                Object th = c.get("theme");
                if (th instanceof String && ChatThemes.has((String) th)) chatTheme = (String) th;
                Object f = c.get("font");
                if (f instanceof Number) { int n = ((Number) f).intValue(); if (n >= ChatThemes.FONT_MIN && n <= ChatThemes.FONT_MAX) fontSp = n; }
                Object r = c.get("radius");
                if (r instanceof Number) { int n = ((Number) r).intValue(); if (n >= ChatThemes.RADIUS_MIN && n <= ChatThemes.RADIUS_MAX) radius = n; }
                sp.edit().putString("chatTheme", chatTheme).putInt("font", fontSp).putInt("radius", radius).apply();
                SeekBar fr0 = (SeekBar) v("fontRange"), rr0 = (SeekBar) v("radiusRange");
                if (fr0 != null) fr0.setProgress(fontSp - ChatThemes.FONT_MIN);
                if (rr0 != null) rr0.setProgress(radius - ChatThemes.RADIUS_MIN);
                TextView fv0 = tv("fontVal"), rv0 = tv("radiusVal");
                if (fv0 != null) fv0.setText("" + fontSp);
                if (rv0 != null) rv0.setText("" + radius);
                applyTheme();
                applyBubbles();
            }

            void setMsgTextSize(View x) {
                Object t = x.getTag();
                if (x instanceof TextView && t instanceof String && (t.equals("mtM") || t.equals("mtO"))) ((TextView) x).setTextSize(fontSp);
                if (x instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) x;
                    for (int i = 0; i < g.getChildCount(); i++) setMsgTextSize(g.getChildAt(i));
                }
            }

            /** applyPrefs: حجم الخط وانحناء الفقاعات على المعاينة وعلى رسائل المحادثة المفتوحة */
            void applyBubbles() {
                LinearLayout a = (LinearLayout) v("messagesArea");
                if (a != null) {
                    for (int i = 0; i < a.getChildCount(); i++) {
                        View c = a.getChildAt(i);
                        if (c instanceof TextView) ((TextView) c).setTextSize(fontSp);
                    }
                    setMsgTextSize(a);
                }
                if (chatPreview != null) chatPreview.apply(chatTheme, dark, fontSp, radius);
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
                try { LocalCache.clearAll(); } catch (Throwable ignored) { }
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
                if (id.equals("mainSearchBtn")) {
                    show("searchView");
                    if (searchPage != null) searchPage.onShown();
                }
                else if (id.equals("searchBack") || id.equals("navChats")) { if (!"homeView".equals(curPage)) show("homeView"); }   // الضغط على القسم الحالي لا يعيد تحميله ولا يعيد الأنيميشن
                else if (id.equals("navSettings")) { if (!"settingsView".equals(curPage)) show("settingsView"); }
                else if (id.equals("navProfile") || id.equals("sxHero")) {
                    if ("profileView".equals(curPage)) return;
                    if (userJoin.isEmpty()) loadMyProfile();   // يُحمَّل مرة واحدة فقط؛ التعديلات تحدّث الصفحة بنفسها
                    show("profileView");
                }
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
                else if (id.equals("otherBack")) leaveOtherProfile();
                else if (id.equals("pTabStoriesBtn")) {
                    tabStories = true; vis("pTabStories", true);
                    setTab(v("pTabStoriesBtn"), true);
                }
                else if (id.equals("otherMsgBtn")) {
                    if (searchPage != null) searchPage.recordVisit(otherUid);   // recordRecentVisit في الـ HTML
                    openChat(otherName, otherUid);
                }
                else if (id.equals("otherBlockBtn")) toggleBlock();
                else if (id.equals("otherMoreBtn")) otherMenu();
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
                        try { LocalCache.clearAll(); } catch (Throwable ignored) { }
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
                else if (id.equals("chatUserInfo")) { hideKb(); vis("chatView", false); openOther(curChat, otherUser, otherUid); profileFromChat = true; }
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
                String[] ids = {"mainSearchBtn", "searchBack", "fltAll", "fltUnread",
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

                searchPage = new SearchPage(act, new SearchPage.Host() {
                    @Override public boolean dark() { return dark; }
                    @Override public String myUid() { return myUid; }
                    @Override public void openUser(String name, String username, String uid) { profileFromChat = false; openOther(name, username, uid); }
                });
                searchPage.bind();

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
                    fr.setProgress(fontSp - ChatThemes.FONT_MIN);
                    fr.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                        public void onProgressChanged(SeekBar s, int p, boolean u) {
                            fontSp = ChatThemes.FONT_MIN + p;
                            TextView fv = tv("fontVal");
                            if (fv != null) fv.setText("" + fontSp);
                            if (u) saveChatPrefs();
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
                            radius = ChatThemes.RADIUS_MIN + p;
                            TextView rv = tv("radiusVal");
                            if (rv != null) rv.setText("" + radius);
                            if (u) saveChatPrefs();
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

                try {
                    stories = new StoriesController(act, (FrameLayout) v("root"), (StoriesHost) v("homeView"), (ScrollView) v("vscroll39"),
                            (LinearLayout) v("topStories"), (HorizontalScrollView) v("storiesScroll"), (LinearLayout) v("storiesBar"),
                            (LinearLayout) v("hdrStories"), it -> {
                                if (it.me && !it.ring) startStoryPublish();                 // myStoryClick: لا قصة نشطة = نشر
                                else openStory(it.uid, it.me ? "قصتي" : it.name, it.letter, it.photo, it.blue, it.av);
                            });
                    ((StoriesHost) v("homeView")).setController(stories);
                    buildStories();
                } catch (Throwable ignored) { }
                loadMyProfileSmart();
                restoreChatsFromCache();
                loadChats();
                show("homeView");
                try {
                    ProfileHero.Palette pal = new ProfileHero.Palette() {
                        @Override public int surface() { return cSf(); }
                        @Override public int text() { return cText(); }
                        @Override public int gold() { return cGold(); }
                        @Override public int red() { return cRed(); }
                        @Override public int blue() { return dark ? 0xFF60A5FA : 0xFF2563EB; }
                        @Override public int redIcon() { return dark ? 0xFFF87171 : 0xFFDC2626; }
                        @Override public void restyle() { polishProfile(); }
                    };
                    heroMe = new ProfileHero(act, (PullScrollView) v("profileView"), tv("pAvatarText"), v("linear643"),
                            tv("pHeaderName"), tv("pHeaderStatus"), v("linear644"), true, () -> photoBmp, null, null, pal);
                    heroOther = new ProfileHero(act, (PullScrollView) v("otherUserProfileView"), tv("otherAvatarText"), v("linear658"),
                            tv("otherHeaderName"), tv("otherHeaderStatus"), v("linear659"), false, () -> otherPhotoBmp,
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
                    for (String pid : new String[]{"navChats", "navSettings", "navProfile", "mainSearchBtn", "fltAll", "fltUnread"})
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
                ui.applyInsets();
                ui.keepEditVisible();                   // ارتفاع لوحة المفاتيح/شريط الإيموجي تغيّر: أبقِ حقل النبذة ظاهراً
                // صفحات الملف الشخصي بلا padding علوي (الغلاف خلف الشريط)
                if (topChanged) ui.polishProfile();     // ارتفاع الغلاف = 176dp + ارتفاع شريط الحالة
                return insets;
            });
            rootView.requestApplyInsets();
            noOverScroll(rootView);
        }
    }

    /** يعطّل ارتداد/تمدد السحب في كل ScrollView حتى لا يظهر الشريط الأبيض أعلى الشاشة. */
    private static void noOverScroll(View v) {
        if (v == null) return;
        v.setOverScrollMode(View.OVER_SCROLL_NEVER);
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) noOverScroll(g.getChildAt(i));
        }
    }
}