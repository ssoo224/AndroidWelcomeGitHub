package com.babylon.chat.iq;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.text.*;
import android.util.Base64;
import android.view.*;
import android.widget.*;

import com.google.firebase.firestore.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.*;

import static com.babylon.chat.iq.GroupsKit.*;

/** غرفة المجموعة بتصميم #chatView: هيدر عائم، فقاعات متتالية بأسماء وألقاب، صور، بصمات صوتية، تفاعلات، رد، تثبيت، منشن. */
public class GroupRoomActivity extends Activity {
    private String chatId; private Map<String, Object> meta = new HashMap<>(); private Th t;
    private final List<DocumentSnapshot> msgs = new ArrayList<>(); private ListenerRegistration chatReg, msgReg; private int limit = 50, pinIdx = -1;
    private boolean metaReady, loadingOlder; private long lastReadWrite; private String flashId = "";
    private FrameLayout rootFrame; private ListView list; private BaseAdapter adapter; private TextView hdAv; private int inkC, mutedC; private TextView hdName, hdSub; private LinearLayout pinBar, bottom, inputBar, recBar, lockBar, replyBar, mentionPop;
    private EditText input; private TextView sendB, micB, attachB; private LinearLayout topBox; private TextView recTimer;
    private String replyId = "", replyText = "", replySid = ""; private boolean typingOn; private final android.os.Handler h = new android.os.Handler(); private Runnable typingOff;
    private MediaRecorder rec; private File recFile; private long recStart; private Runnable recTick; private boolean recording;
    private static MediaPlayer player; private static String playingId = "";
    private static final String[] EMOJIS = {"❤️", "👍", "😂", "😮", "😢", "🙏"};

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b); t = th(this); chatBars(); initChatStyle(); getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        chatId = getIntent().getStringExtra("chatId"); if (chatId == null) { finish(); return; }
        build(); loadUsers(Collections.singletonList(me()), () -> { });
        chatReg = db().collection("chats").document(chatId).addSnapshotListener((doc, e) -> {
            if (doc == null) return; if (!doc.exists() || !parts(doc.getData()).contains(me())) { if (metaReady || doc.exists()) { toast(this, "لم تعد عضواً في هذه المجموعة"); finish(); } return; }
            meta = doc.getData(); loadUsers(parts(meta), () -> { metaReady = true; refreshHeader(); adapter.notifyDataSetChanged(); });
        });
        listenMessages();
    }
    @Override protected void onDestroy() { super.onDestroy(); if (chatReg != null) chatReg.remove(); if (msgReg != null) msgReg.remove(); setTyping(false); stopRec(false); stopPlayer(); }
    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); }
    @Override protected void onResume() { super.onResume(); MsgViews.replyJump = (bv, rid) -> jumpTo(rid); }
    private int fontSp = ChatThemes.FONT_DEF, radius = ChatThemes.RADIUS_DEF;
    /** نفس إعدادات الدردشة الخاصة: ثيم المحادثة وحجم الخط وانحناء الفقاعات. */
    private void initChatStyle() {
        android.content.SharedPreferences sp = AppSettings.prefs(this, "babylon_ui");
        Tk.setChatTheme(sp.getString("chatTheme", ChatThemes.DEFAULT_ID), t.dark);
        fontSp = Math.max(ChatThemes.FONT_MIN, Math.min(ChatThemes.FONT_MAX, sp.getInt("font", ChatThemes.FONT_DEF)));
        radius = Math.max(ChatThemes.RADIUS_MIN, Math.min(ChatThemes.RADIUS_MAX, sp.getInt("radius", ChatThemes.RADIUS_DEF)));
        MsgViews.replyJump = (bv, rid) -> jumpTo(rid);
    }
    /** يلوّن كل عنصر موسوم داخل الفقاعة بنفس منطق الدردشة الخاصة (Tk.handle). */
    private void tint(View v) {
        Object tg = v.getTag();
        if (tg instanceof String) for (String k : ((String) tg).split(" ")) Tk.handle(k, v, v instanceof TextView ? (TextView) v : null, t.dark);
        if (v instanceof ViewGroup) { ViewGroup g = (ViewGroup) v; for (int i = 0; i < g.getChildCount(); i++) tint(g.getChildAt(i)); }
    }
    private String time12(long ts) { String x = new java.text.SimpleDateFormat("h:mm a", Locale.getDefault()).format(new Date(ts)); return x.startsWith("0") ? x.substring(1) : x; }
    private String dayLabel(long ts) {
        Calendar n = Calendar.getInstance(), c = Calendar.getInstance(); c.setTimeInMillis(ts);
        if (n.get(Calendar.YEAR) == c.get(Calendar.YEAR) && n.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)) return "اليوم";
        n.add(Calendar.DAY_OF_YEAR, -1); if (n.get(Calendar.YEAR) == c.get(Calendar.YEAR) && n.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)) return "أمس";
        Calendar a = Calendar.getInstance(), b2 = (Calendar) c.clone(); for (Calendar x : new Calendar[]{a, b2}) { x.set(Calendar.HOUR_OF_DAY, 0); x.set(Calendar.MINUTE, 0); x.set(Calendar.SECOND, 0); x.set(Calendar.MILLISECOND, 0); }
        long days = Math.round((a.getTimeInMillis() - b2.getTimeInMillis()) / 86400000.0); Locale ar = new Locale("ar");
        if (days >= 0 && days < 7) return new java.text.SimpleDateFormat("EEEE", ar).format(new Date(ts));
        Calendar ya = Calendar.getInstance(); ya.add(Calendar.YEAR, -1); return new java.text.SimpleDateFormat(c.before(ya) ? "d MMMM yyyy" : "d MMMM", ar).format(new Date(ts));
    }
    private String dayKey(long ts) { return new java.text.SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date(ts)); }
    private View withDay(int pos, Map<String, Object> m, View row) {
        long ts = l(m.get("createdAt")); if (ts == 0) return row;
        boolean need = pos == 0 || !dayKey(ts).equals(dayKey(l(msgs.get(pos - 1).get("createdAt")))); if (!need) return row;
        LinearLayout w = new LinearLayout(this); w.setOrientation(LinearLayout.VERTICAL); w.setLayoutParams(new AbsListView.LayoutParams(-1, -2));
        TextView sp = MsgViews.sysPill(this, dayLabel(ts)); tint(sp); w.addView(sp); row.setLayoutParams(new LinearLayout.LayoutParams(-1, -2)); w.addView(row); return w;
    }

    private void listenMessages() {
        if (msgReg != null) msgReg.remove();
        msgReg = db().collection("chats").document(chatId).collection("messages").orderBy("createdAt", Query.Direction.DESCENDING).limit(limit).addSnapshotListener((snap, e) -> {
            if (snap == null) return; msgs.clear(); List<DocumentSnapshot> d = new ArrayList<>(snap.getDocuments()); Collections.reverse(d); msgs.addAll(d);
            List<String> ids = new ArrayList<>(); for (DocumentSnapshot m : d) { String sid = s(m.getData(), "senderId"); if (!sid.isEmpty()) ids.add(sid); }
            loadUsers(ids, () -> { adapter.notifyDataSetChanged(); });
            adapter.notifyDataSetChanged(); if (!loadingOlder) list.setSelection(adapter.getCount() - 1); loadingOlder = false;
            if (!d.isEmpty()) markRead(l(d.get(d.size() - 1).get("createdAt")));
        });
    }
    private void markRead(long newest) { long mine = l(map(meta, "readAt").get(me())); if (newest <= mine || System.currentTimeMillis() - lastReadWrite < 1200) return; lastReadWrite = System.currentTimeMillis(); db().collection("chats").document(chatId).update("readAt." + me(), Math.max(System.currentTimeMillis(), newest)); }

    // ---------- بناء الواجهة ----------
    private FrameLayout circleBtn(String ic) {
        FrameLayout f = new FrameLayout(this); GradientDrawable g = oval(t.crCard); g.setStroke(Math.max(1, t.u(1)), t.crLine); f.setBackground(g); f.setElevation(t.u(3)); f.addView(icon(this, ic, t.crInk, 22), center(this, 22)); return f;
    }
    private void build() {
        rootFrame = new FrameLayout(this); rootFrame.setBackgroundColor(t.crSurface); rtl(rootFrame); rootFrame.setOnApplyWindowInsetsListener((v, insets) -> {   // نفس applyInsets للمحادثة: الزجاج خلف شريط الإشعارات وشريط الإيماءات
            int top = insets.getSystemWindowInsetTop(), bot = insets.getSystemWindowInsetBottom(), nav = Math.min(bot, insets.getStableInsetBottom()); boolean kbUp = bot - nav > t.u(100);
            topBox.setPadding(0, top, 0, 0); bottom.setPadding(0, 0, 0, kbUp ? 0 : nav); rootFrame.setPadding(0, 0, 0, kbUp ? bot : 0); return insets; });
                inkC = Tk.crInk(t.dark ? 0xFFF1F5F9 : 0xFF17212B); mutedC = Tk.crMuted(Tk.muted(t.dark));   // نفس ألوان t / m في المحادثة العادية
        // الهيدر: نفس chatTopBar في mass.xml (طبقة زجاجية + chatBack / chatUserInfo / chatMore بنفس الأبعاد والوسوم)
        topBox = new LinearLayout(this); topBox.setOrientation(LinearLayout.VERTICAL); Tk.handle("glasst", topBox, null, t.dark);
        LinearLayout hd = new LinearLayout(this); hd.setGravity(Gravity.CENTER_VERTICAL); hd.setPadding(t.u(10), 0, t.u(10), t.u(6));
        TextView bk = rbtn(R.drawable.ic_arrow_left_24, 22); bk.setOnClickListener(v -> finish()); hd.addView(bk, lp(t.u(38), t.u(38)));
        LinearLayout pill = new LinearLayout(this); pill.setGravity(Gravity.CENTER_VERTICAL); pill.setPaddingRelative(t.u(8), 0, t.u(14), 0); Tk.handle("pill", pill, null, t.dark); pill.setElevation(0f); pill.setOnClickListener(v -> openInfo(null));
        hdAv = new TextView(this); hdAv.setGravity(Gravity.CENTER); hdAv.setTextSize(16); hdAv.setTypeface(null, android.graphics.Typeface.BOLD); hdAv.setText("؟"); hdAv.setTextColor(t.dark ? 0xFF17212B : 0xFFFFFFFF); hdAv.setBackground(Tk.chatAvatar(hdAv, t.dark)); pill.addView(hdAv, lp(t.u(40), t.u(40)));
        LinearLayout tt = new LinearLayout(this); tt.setOrientation(LinearLayout.VERTICAL); tt.setPaddingRelative(t.u(10), 0, 0, 0);
        hdName = new TextView(this); hdName.setText("مجموعة"); hdName.setTextSize(16); hdName.setTypeface(null, android.graphics.Typeface.BOLD); hdName.setTextColor(inkC); hdName.setSingleLine(true); hdName.setEllipsize(TextUtils.TruncateAt.END);
        hdSub = new TextView(this); hdSub.setTextSize(12); hdSub.setTextColor(mutedC); hdSub.setSingleLine(true); tt.addView(hdName); tt.addView(hdSub);
        pill.addView(tt, lpw(0, -2, 1)); LinearLayout.LayoutParams pp = lpw(0, t.u(48), 1); pp.setMargins(t.u(8), 0, t.u(8), 0); hd.addView(pill, pp);
        TextView more = rbtn(R.drawable.ic_more_22, 22); more.setOnClickListener(v -> moreMenu()); hd.addView(more, lp(t.u(38), t.u(38))); topBox.addView(hd);
        // شريط التثبيت (داخل الهيدر الزجاجي كما في المحادثة العادية)
        pinBar = new LinearLayout(this); pinBar.setGravity(Gravity.CENTER_VERTICAL); Tk.handle("rpBar", pinBar, null, t.dark); pinBar.setPadding(t.u(8), t.u(7), t.u(14), t.u(7)); pinBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams pbp = lp(-1, -2); pbp.setMargins(t.u(10), t.u(2), t.u(10), t.u(4)); topBox.addView(pinBar, pbp);
        // الرسائل
        list = new ListView(this); list.setDivider(null); list.setClipToPadding(false); list.setPadding(t.u(10), t.u(10), t.u(10), t.u(10)); list.setSelector(android.R.color.transparent); list.setOverScrollMode(View.OVER_SCROLL_NEVER); list.setTranscriptMode(ListView.TRANSCRIPT_MODE_NORMAL);
        adapter = new MsgAdapter(); list.setAdapter(adapter); Tk.handle("chatpat", list, null, t.dark); rootFrame.addView(list, new FrameLayout.LayoutParams(-1, -1));
        list.setOnScrollListener(new AbsListView.OnScrollListener() { public void onScrollStateChanged(AbsListView v, int s) { } public void onScroll(AbsListView v, int f, int c, int tt2) { if (f == 0 && msgs.size() >= limit && !loadingOlder) { loadingOlder = true; limit += 50; listenMessages(); } } });
        // الأسفل: نفس chatBottomBar في mass.xml (طبقة زجاجية، شريط رد rpBar، مدخل composer بأزرار 40dp)
        bottom = new LinearLayout(this); bottom.setOrientation(LinearLayout.VERTICAL); Tk.handle("glassb", bottom, null, t.dark);
        replyBar = new LinearLayout(this); replyBar.setGravity(Gravity.CENTER_VERTICAL); Tk.handle("rpBar", replyBar, null, t.dark); replyBar.setPadding(t.u(14), t.u(9), t.u(14), t.u(9)); replyBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams rbp = lp(-1, -2); rbp.setMargins(t.u(8), t.u(6), t.u(8), t.u(4)); bottom.addView(replyBar, rbp);
        FrameLayout inWrap = new FrameLayout(this); bottom.addView(inWrap);
        inputBar = new LinearLayout(this); inputBar.setLayoutDirection(View.LAYOUT_DIRECTION_LTR); inputBar.setGravity(Gravity.BOTTOM); Tk.handle("composer", inputBar, null, t.dark); inputBar.setMinimumHeight(t.u(48)); inputBar.setElevation(0f); inputBar.setPadding(t.u(8), t.u(5), t.u(8), t.u(5));
        sendB = tbtn(R.drawable.ic_send_up_21, 21); Tk.handle("sendbtn", sendB, sendB, t.dark); tintDr(sendB, sendB.getCurrentTextColor()); sendB.setVisibility(View.GONE); sendB.setOnClickListener(v -> send());
        LinearLayout.LayoutParams sbp = lp(t.u(40), t.u(40)); sbp.gravity = Gravity.TOP; sbp.setMarginEnd(t.u(6)); inputBar.addView(sendB, sbp);
        micB = tbtn(R.drawable.ic_mic_22, 20); tintDr(micB, mutedC); micB.setOnClickListener(v -> startRec()); LinearLayout.LayoutParams mbp = lp(t.u(40), t.u(40)); mbp.setMarginEnd(t.u(6)); inputBar.addView(micB, mbp);
        attachB = tbtn(R.drawable.ic_attach_22, 20); tintDr(attachB, mutedC); attachB.setOnClickListener(v -> pickImage()); LinearLayout.LayoutParams abp = lp(t.u(40), t.u(40)); abp.setMarginEnd(t.u(6)); inputBar.addView(attachB, abp);
        input = new EditText(this); input.setHint("اكتب رسالتك..."); input.setHintTextColor(mutedC); input.setTextColor(t.dark ? inkC : 0xFF3A404A); input.setTextSize(15); input.setMinHeight(t.u(45)); input.setMaxHeight(t.u(137));
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setGravity(Gravity.CENTER_VERTICAL | Gravity.START); input.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG); input.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START); input.setPadding(t.u(12), t.u(9), t.u(12), t.u(9));
        input.setBackground(inpBg(false)); input.setOnFocusChangeListener((v, f) -> input.setBackground(inpBg(f))); Tk.selectionTint(input, Tk.chatPal != null ? Tk.chatPal.accent : (t.dark ? 0xFFE4C45B : 0xFFC29D30));
        inputBar.addView(input, lpw(0, -2, 1));
        FrameLayout.LayoutParams ibp = new FrameLayout.LayoutParams(-1, -2); ibp.setMargins(t.u(4), t.u(4), t.u(4), 0); inWrap.addView(inputBar, ibp);
        recBar = new LinearLayout(this); recBar.setGravity(Gravity.CENTER_VERTICAL); Tk.handle("recbar", recBar, null, t.dark); recBar.setMinimumHeight(t.u(66)); recBar.setPadding(t.u(12), t.u(8), t.u(12), t.u(8)); recBar.setVisibility(View.GONE);
        TextView cx = tv(this, t, "×", 24, mutedC, false); cx.setGravity(Gravity.CENTER); cx.setOnClickListener(v -> stopRec(false)); recBar.addView(cx, lp(t.k(40), t.k(40)));
        View dot = new View(this); dot.setBackground(oval(0xFFF87171)); LinearLayout.LayoutParams dp2 = lp(t.u(10), t.u(10)); dp2.setMargins(t.u(8), 0, t.u(8), 0); recBar.addView(dot, dp2);
        TextView rl = tv(this, t, "جاري التسجيل", 14, 0xFFF87171, true); recBar.addView(rl); recTimer = tv(this, t, "00:00", 14, inkC, true); recTimer.setPadding(t.u(10), 0, 0, 0); recBar.addView(recTimer, lpw(0, -2, 1));
        FrameLayout rs = new FrameLayout(this); Tk.handle("recbtn", rs, null, t.dark); rs.addView(icon(this, IC_SEND, 0xFFFFFFFF, 22), center(this, 22)); rs.setOnClickListener(v -> stopRec(true)); recBar.addView(rs, lp(t.u(42), t.u(42))); FrameLayout.LayoutParams rbp2 = new FrameLayout.LayoutParams(-1, -2); rbp2.setMargins(t.u(10), t.u(4), t.u(10), 0); inWrap.addView(recBar, rbp2);
        lockBar = new LinearLayout(this); lockBar.setGravity(Gravity.CENTER); lockBar.setBackground(round(t.card, t.u(16))); lockBar.setPadding(t.u(16), t.u(13), t.u(16), t.u(13)); lockBar.setVisibility(View.GONE);
        lockBar.addView(icon(this, IC_LOCK, t.sub, 18)); TextView lt = tv(this, t, "", 14, t.sub, false); lt.setPadding(t.u(9), 0, 0, 0); lockBar.addView(lt); lockBar.setTag(lt);
        LinearLayout.LayoutParams lkp = lp(-1, -2); lkp.setMargins(t.u(12), t.u(8), t.u(12), t.u(10)); bottom.addView(lockBar, lkp);
        rootFrame.addView(topBox, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP)); rootFrame.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        View.OnLayoutChangeListener lc = (v, a1, a2, a3, a4, a5, a6, a7, a8) -> { int pt = topBox.getHeight() + t.u(10), pb = bottom.getHeight() + t.u(10); if (list.getPaddingTop() != pt || list.getPaddingBottom() != pb) list.setPadding(t.u(10), pt, t.u(10), pb); };
        topBox.addOnLayoutChangeListener(lc); bottom.addOnLayoutChangeListener(lc);
        mentionPop = new LinearLayout(this); mentionPop.setOrientation(LinearLayout.VERTICAL); GradientDrawable mg = round(t.card, t.u(16)); mg.setStroke(Math.max(1, t.u(1)), t.sep); mentionPop.setBackground(mg); mentionPop.setElevation(t.u(12)); mentionPop.setVisibility(View.GONE);
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM); mp.setMargins(t.u(10), 0, t.u(10), t.u(68)); rootFrame.addView(mentionPop, mp); setContentView(rootFrame);
        input.addTextChangedListener(new TextWatcher() { public void beforeTextChanged(CharSequence a, int b2, int c, int d) { } public void onTextChanged(CharSequence a, int b2, int c, int d) { }
            public void afterTextChanged(Editable e) { checkMention(); updateAction(); if (e.length() > 0) { setTyping(true); if (typingOff != null) h.removeCallbacks(typingOff); typingOff = () -> setTyping(false); h.postDelayed(typingOff, 3000); } } });
        updateAction();
    }
    /** صورة رأس المحادثة: صورة المجموعة دائرية، وإلا الحرف الأول على دائرة الثيم (Tk.chatAvatar) كما في المحادثة العادية. */
    private void setHdAvatar() {
        final String nm = s(meta, "name"), ph = s(meta, "photo"); final String ini = nm.isEmpty() ? "؟" : nm.substring(0, nm.offsetByCodePoints(0, 1)).toUpperCase();
        hdAv.setBackground(Tk.chatAvatar(hdAv, t.dark)); hdAv.setTextColor(t.dark ? 0xFF17212B : 0xFFFFFFFF); hdAv.setText(ini);
        if (!ph.isEmpty()) loadBitmap(ph, 400, bm -> hdAv.post(() -> { androidx.core.graphics.drawable.RoundedBitmapDrawable rd = androidx.core.graphics.drawable.RoundedBitmapDrawableFactory.create(getResources(), bm); rd.setCircular(true); rd.setAntiAlias(true); hdAv.setBackground(rd); hdAv.setText(""); }));
    }
    /** أشرطة النظام كما في المحادثة العادية: شفافة وبحجم الشاشة كاملاً وأيقونات حسب الثيم. */
    private void chatBars() {
        Window w = getWindow(); w.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION); w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.setStatusBarColor(0); w.setNavigationBarColor(0); if (Build.VERSION.SDK_INT >= 29) { w.setNavigationBarContrastEnforced(false); w.setStatusBarContrastEnforced(false); }
        AuthTheme.systemBars(w, t.dark);
    }
    private void updateAction() { boolean has = input.getText().toString().trim().length() > 0; sendB.setVisibility(has ? View.VISIBLE : View.GONE); micB.setVisibility(has ? View.GONE : View.VISIBLE); attachB.setVisibility(has ? View.GONE : View.VISIBLE); }
    private void tintDr(TextView x, int c) { for (android.graphics.drawable.Drawable d : x.getCompoundDrawablesRelative()) if (d != null) d.mutate().setColorFilter(c, android.graphics.PorterDuff.Mode.SRC_IN); }
    private TextView tbtn(int res, float sp) { TextView x = new TextView(this); x.setGravity(Gravity.CENTER); x.setTextSize(sp); x.setCompoundDrawablesRelativeWithIntrinsicBounds(res, 0, 0, 0); return x; }
    private TextView rbtn(int res, float sp) { TextView x = tbtn(res, sp); x.setElevation(0f); Tk.handle("rbtn", x, x, t.dark); x.setTextColor(inkC); tintDr(x, inkC); return x; }
    private int blend(int base, int fg, float a) { int r = Math.round(((base >> 16) & 0xFF) * (1 - a) + ((fg >> 16) & 0xFF) * a), g = Math.round(((base >> 8) & 0xFF) * (1 - a) + ((fg >> 8) & 0xFF) * a), b = Math.round((base & 0xFF) * (1 - a) + (fg & 0xFF) * a); return 0xFF000000 | (r << 16) | (g << 8) | b; }
    /** حقل الكتابة: نفس msgInpBg(focus, composer=true) في المحادثة العادية. */
    private GradientDrawable inpBg(boolean focus) {
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(t.u(18)); boolean dark = t.dark; int acc = (Tk.chatPal != null ? Tk.chatPal.accent : 0xFFC29D30) | 0xFF000000;
        if (focus && dark) { g.setColor(Tk.composerOuter(dark)); g.setStroke(t.u(1), Tk.chatPal != null ? Tk.chatPal.line : 0x14FFFFFF); return g; }
        if (Tk.chatPal != null) { g.setColor(focus && !dark ? blend(0xFFFFFFFF, acc, 0.045f) : Tk.composerInner(dark)); g.setStroke(t.u(1), focus ? ((Tk.chatPal.accent & 0x00FFFFFF) | 0x99000000) : 0x00000000); return g; }
        g.setColor(Tk.composerInner(dark)); g.setStroke(t.u(1), focus ? (dark ? 0xFF8B94A1 : 0xFFD9CDBB) : (dark ? 0x00000000 : Tk.BAB_INPUT_LINE)); return g;
    }
    private void onAction() { if (input.getText().toString().trim().length() > 0) send(); else startRec(); }
    private void openInfo(String popupUid) { Intent i = new Intent(this, GroupInfoActivity.class); i.putExtra("chatId", chatId); if (popupUid != null) i.putExtra("popupUid", popupUid); startActivityForResult(i, 9); }
    @Override protected void onActivityResult(int rc, int res, Intent d) {
        super.onActivityResult(rc, res, d);
        if (rc == 9 && res == RESULT_OK && d != null && d.getStringExtra("mention") != null) { String un = d.getStringExtra("mention"); String v = input.getText().toString(); if (!v.isEmpty() && !v.endsWith(" ")) v += " "; input.setText(v + "@" + un + ", "); input.setSelection(input.length()); input.requestFocus(); }
        if (rc == 11 && res == RESULT_OK && d != null && d.getData() != null) sendImage(d.getData());
    }
    private void setTyping(boolean on) { if (typingOn == on || chatId == null) return; typingOn = on; try { db().collection("chats").document(chatId).update("typing_" + me(), on); } catch (Throwable ignored) { } }

    // ---------- الرأس والصلاحيات ----------
    private void refreshHeader() {
        hdName.setText(s(meta, "name").isEmpty() ? "مجموعة" : s(meta, "name")); setHdAvatar();
        List<String> names = new ArrayList<>(); for (String u : parts(meta)) if (!u.equals(me()) && isTrue(meta.get("typing_" + u))) names.add(userName(u));
        if (!names.isEmpty()) { hdSub.setText(names.size() == 1 ? names.get(0) + " يكتب..." : join(names.subList(0, Math.min(2, names.size())), " و") + (names.size() > 2 ? " وآخرون" : "") + " يكتبون..."); hdSub.setTextColor(0xFF4ADE80); }
        else { hdSub.setText(memberWord(parts(meta).size())); hdSub.setTextColor(mutedC); }
        boolean canText = canSend("text", me(), meta); inputBar.setVisibility(canText && !recording ? View.VISIBLE : View.GONE); lockBar.setVisibility(canText ? View.GONE : View.VISIBLE);
        if (!canText) ((TextView) lockBar.getTag()).setText(role(me(), meta).equals("member") && isTrue(map(meta, "muted").get(me())) ? "تم تقييدك من الكتابة في هذه المجموعة" : "الكتابة مقيّدة في هذه المجموعة");
        renderPinned();
    }
    @SuppressWarnings("unchecked") private List<Map<String, Object>> pins() { Object o = meta.get("pins"); return o instanceof List ? (List<Map<String, Object>>) o : new ArrayList<Map<String, Object>>(); }
    private void renderPinned() {
        List<Map<String, Object>> pins = pins(); if (pins.isEmpty()) { pinBar.setVisibility(View.GONE); pinIdx = -1; return; } if (pinIdx < 0 || pinIdx >= pins.size()) pinIdx = pins.size() - 1;
        final Map<String, Object> p = pins.get(pinIdx); pinBar.setVisibility(View.VISIBLE); pinBar.removeAllViews();
        LinearLayout line = new LinearLayout(this); line.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < pins.size(); i++) { View seg = new View(this); seg.setBackground(round(t.gold, t.u(2))); seg.setAlpha(i == pinIdx ? 1f : .3f); LinearLayout.LayoutParams lp = lpw(t.u(3), 0, 1); lp.setMargins(0, i == 0 ? 0 : t.u(2), 0, 0); line.addView(seg, lp); }
        pinBar.addView(line, lp(t.u(3), t.k(34))); LinearLayout tx = new LinearLayout(this); tx.setOrientation(LinearLayout.VERTICAL); tx.setPadding(t.u(11), 0, t.u(8), 0);
        tx.addView(tv(this, t, "رسالة مثبتة" + (pins.size() > 1 ? " #" + (pinIdx + 1) : ""), 13, t.gold, true)); TextView pt = tv(this, t, s(p, "text"), 13.5f, t.text, false); pt.setAlpha(.85f); pt.setSingleLine(true); pt.setEllipsize(TextUtils.TruncateAt.END); tx.addView(pt); pinBar.addView(tx, lpw(0, -2, 1));
        pinBar.setOnClickListener(v -> { jumpTo(s(p, "id")); if (pins().size() > 1) { pinIdx = (pinIdx - 1 + pins().size()) % pins().size(); renderPinned(); } });
        if (pins.size() > 1) { FrameLayout x = new FrameLayout(this); x.addView(icon(this, IC_LIST, t.sub, 20).width(2.2f), center(this, 20)); pinBar.addView(x, lp(t.k(36), t.k(36))); x.setOnClickListener(v -> pinsSheet()); }
        else if (right("pin", me(), meta)) { FrameLayout x = new FrameLayout(this); x.addView(icon(this, IC_PIN_OFF, t.sub, 19), center(this, 19)); pinBar.addView(x, lp(t.k(36), t.k(36))); x.setOnClickListener(v -> askX(this, t, "إلغاء التثبيت", "هل تريد إلغاء تثبيت هذه الرسالة؟", null, null, 0, false, "إلغاء التثبيت", "تراجع", true, IC_WARN, (ok, y) -> { if (!ok) return; togglePin(s(p, "id"), "", true); })); }
    }
    private static final String IC_PIN_OFF = IC_PIN + "M3 3l18 18";
    /** قائمة الرسائل المثبتة (.pins-dlg): أرقام ذهبية، وإلغاء تثبيت الكل للمخوّلين. */
    private void pinsSheet() {
        final List<Map<String, Object>> pins = new ArrayList<>(pins()); if (pins.isEmpty()) return; final boolean cp = right("pin", me(), meta);
        final Dialog dg = new Dialog(this); dg.requestWindowFeature(Window.FEATURE_NO_TITLE); final int gold = t.dark ? 0xFFE4C45B : 0xFFC99A1C;
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(t.u(20), t.u(26), t.u(20), t.u(10)); rtl(card);
        FrameLayout shell = new FrameLayout(this); GradientDrawable sbg = round(t.card, t.u(26)); sbg.setStroke(Math.max(1, t.u(1)), t.dark ? 0x1FFFFFFF : 0x2416202B); shell.setBackground(sbg); shell.setClipToOutline(true); shell.addView(card, new FrameLayout.LayoutParams(-1, -2));
        View line = new View(this); line.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0, gold, gold, 0})); line.setAlpha(.85f); shell.addView(line, new FrameLayout.LayoutParams(-1, t.u(3), Gravity.TOP));
        TextView h = tv(this, t, "الرسائل المثبتة", 19, t.text, true); h.setGravity(Gravity.CENTER); card.addView(h, lp(-1, -2));
        TextView sb = tv(this, t, pins.size() + " رسائل مثبتة في هذه المحادثة", 14.5f, t.sub, false); sb.setGravity(Gravity.CENTER); LinearLayout.LayoutParams sbp = lp(-1, -2); sbp.topMargin = t.u(8); sbp.bottomMargin = t.u(14); card.addView(sb, sbp);
        ScrollView sv = new ScrollView(this); LinearLayout lst = new LinearLayout(this); lst.setOrientation(LinearLayout.VERTICAL); sv.addView(lst);
        for (int i = 0; i < pins.size(); i++) { final int idx = i; final Map<String, Object> p = pins.get(i);
            if (i > 0) { View sp = new View(this); sp.setBackgroundColor(t.sep); lst.addView(sp, lp(-1, 1)); }
            LinearLayout it = new LinearLayout(this); it.setGravity(Gravity.CENTER_VERTICAL); it.setPadding(t.u(8), t.u(11), t.u(8), t.u(11));
            TextView n = tv(this, t, String.valueOf(i + 1), 13, gold, true); n.setGravity(Gravity.CENTER); n.setBackground(oval((gold & 0x00FFFFFF) | 0x29000000)); it.addView(n, lp(t.k(26), t.k(26)));
            TextView tx = tv(this, t, s(p, "text"), 14.5f, t.text, false); tx.setSingleLine(true); tx.setEllipsize(TextUtils.TruncateAt.END); tx.setPadding(t.u(12), 0, 0, 0); it.addView(tx, lpw(0, -2, 1));
            it.setOnClickListener(v -> { dg.dismiss(); pinIdx = idx; renderPinned(); jumpTo(s(p, "id")); }); lst.addView(it); }
        card.addView(sv, lp(-1, Math.min(t.u(46 * pins.size()), Math.round(getResources().getDisplayMetrics().heightPixels * .46f))));
        LinearLayout row = new LinearLayout(this); LinearLayout.LayoutParams rp = lp(-1, -2); rp.topMargin = t.u(10); rp.bottomMargin = t.u(8); card.addView(row, rp);
        if (cp) { TextView all = tv(this, t, "إلغاء تثبيت الكل", 15.5f, 0xFFFFFFFF, true); all.setGravity(Gravity.CENTER); all.setPadding(0, t.u(13), 0, t.u(13)); GradientDrawable ab = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFFF0605D, 0xFFD94545}); ab.setCornerRadius(t.u(16)); all.setBackground(ab);
            all.setOnClickListener(v -> askX(this, t, "إلغاء تثبيت الكل", "هل تريد إلغاء تثبيت جميع الرسائل المثبتة؟", null, null, 0, false, "إلغاء الكل", "تراجع", true, IC_WARN, (ok, y) -> { if (!ok) return; dg.dismiss(); pinIdx = -1; db().collection("chats").document(chatId).update("pins", new ArrayList<Map<String, Object>>()); toast(this, "تم إلغاء تثبيت جميع الرسائل"); }));
            LinearLayout.LayoutParams ap = lpw(0, -2, 1); ap.setMarginEnd(t.u(10)); row.addView(all, ap); }
        TextView cl = tv(this, t, "إغلاق", 15.5f, t.text, true); cl.setGravity(Gravity.CENTER); cl.setPadding(0, t.u(13), 0, t.u(13)); cl.setBackground(round(t.chip, t.u(16))); cl.setOnClickListener(v -> dg.dismiss()); row.addView(cl, lpw(0, -2, 1));
        dg.setContentView(shell); Window w = dg.getWindow(); w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0)); w.setDimAmount(.52f); w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); w.setLayout(Math.min(t.u(340), getResources().getDisplayMetrics().widthPixels - t.u(44)), ViewGroup.LayoutParams.WRAP_CONTENT); dg.show();
    }
    private void jumpTo(String id) { for (int i = 0; i < msgs.size(); i++) if (msgs.get(i).getId().equals(id)) { flashId = id; list.smoothScrollToPosition(i); adapter.notifyDataSetChanged(); h.postDelayed(() -> { flashId = ""; }, 1400); return; } toast(this, "الرسالة بعيدة، مرّر للأعلى لتحميل رسائل أقدم"); }

    private void moreMenu() {
        List<String[]> it = new ArrayList<>(); it.add(new String[]{"info", "معلومات المجموعة", IC_USERS}); it.add(new String[]{"search", "بحث في المحادثة", IC_SEARCH}); it.add(new String[]{"mute", isMuted(this, chatId) ? "إلغاء كتم المحادثة" : "كتم المحادثة", isMuted(this, chatId) ? IC_BELL : IC_BELL_OFF});
        sheet(null, it, key -> { switch (key) { case "info": openInfo(null); break; case "search": searchDialog(); break; case "mute": setMuted(this, chatId, !isMuted(this, chatId)); toast(this, isMuted(this, chatId) ? "تم كتم هذه المجموعة على جهازك" : "تم إلغاء كتم المحادثة"); break; } });
    }
    private void searchDialog() {
        ask(this, t, "بحث في المحادثة", null, "", "اكتب كلمة أو حرفين", 60, false, "بحث", "إلغاء", (ok, q) -> { if (!ok) return; if (q.length() < 2) { toast(this, "اكتب حرفين على الأقل"); return; } String ql = q.toLowerCase();
            for (int i = msgs.size() - 1; i >= 0; i--) { Map<String, Object> m = msgs.get(i).getData(); if (!isTrue(m.get("system")) && s(m, "text").toLowerCase().contains(ql)) { jumpTo(msgs.get(i).getId()); return; } } toast(this, "لا توجد نتائج في الرسائل المحمّلة"); });
    }
    /** قائمة بنفس صندوق .up-box (تُستعمل للقائمة والرسائل). extraTop: صف تفاعلات اختياري. */
    private void sheet(View extraTop, List<String[]> items, final PopCb cb) {
        final Dialog dg = new Dialog(this); dg.requestWindowFeature(Window.FEATURE_NO_TITLE); LinearLayout wrap = new LinearLayout(this); wrap.setOrientation(LinearLayout.VERTICAL); rtl(wrap);
        if (extraTop != null) { LinearLayout.LayoutParams ep = lp(-1, -2); ep.bottomMargin = t.u(4); wrap.addView(extraTop, ep); }
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setBackground(round(t.card, t.u(20))); wrap.addView(box, lp(-1, -2)); boolean first = true;
        for (final String[] b : items) { box.addView(popRow(this, t, b[1], b.length > 2 && !"danger".equals(b[2]) ? b[2] : IC_PIN, b.length > 3 && "danger".equals(b[3]), !first, () -> { dg.dismiss(); cb.pick(b[0]); })); first = false; }
        dg.setContentView(wrap); Window w = dg.getWindow(); w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0)); w.setDimAmount(.52f); w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); w.setLayout(Math.min(t.u(340), getResources().getDisplayMetrics().widthPixels - t.u(44)), ViewGroup.LayoutParams.WRAP_CONTENT); dg.show();
        if (extraTop != null) extraTop.setTag(dg);
    }

    // ---------- المنشن ----------
    private void checkMention() {
        String tx = input.getText().toString(); java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:^|\\s)@([A-Za-z0-9_.]*)$").matcher(tx); mentionPop.removeAllViews(); if (!m.find()) { mentionPop.setVisibility(View.GONE); return; }
        String q = m.group(1).toLowerCase(); int n = 0;
        for (final String u : parts(meta)) { if (u.equals(me())) continue; final U g = user(u); if (g.username.isEmpty()) continue; if (!q.isEmpty() && !g.username.toLowerCase().startsWith(q) && !g.name.toLowerCase().contains(q)) continue; if (n++ >= 6) break;
            LinearLayout r = new LinearLayout(this); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(t.u(14), t.u(8), t.u(14), t.u(8)); Av av = new Av(this, 36 * t.s); av.setUser(u); r.addView(av, lp(t.k(36), t.k(36)));
            LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(t.u(10), 0, 0, 0); c.addView(tv(this, t, g.name, 14.5f, t.text, true)); TextView us = tv(this, t, "@" + g.username, 12.5f, t.sub, false); c.addView(us); r.addView(c);
            r.setOnClickListener(v -> { String cur = input.getText().toString(); input.setText(cur.replaceAll("(^|\\s)@[A-Za-z0-9_.]*$", "$1@" + g.username + " ")); input.setSelection(input.length()); mentionPop.setVisibility(View.GONE); }); mentionPop.addView(r); }
        mentionPop.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
    }
    private List<String> extractMentions(String text) {
        Map<String, String> names = new HashMap<>(); for (String u : parts(meta)) { String un = user(u).username; if (!un.isEmpty()) names.put(un.toLowerCase(), u); } List<String> out = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(^|[^A-Za-z0-9_.@])@([A-Za-z][A-Za-z0-9_.]{2,})").matcher(text); while (m.find()) { String u = names.get(m.group(2).replaceAll("\\.+$", "").toLowerCase()); if (u != null && !u.equals(me()) && !out.contains(u)) out.add(u); } return out;
    }

    // ---------- الإرسال ----------
    private Map<String, Object> baseMsg(String type) {
        Map<String, Object> m = new HashMap<>(); long now = System.currentTimeMillis(); m.put("type", type); m.put("senderId", me()); m.put("sender", me()); m.put("createdAt", now); m.put("timestamp", now);
        if (!replyId.isEmpty()) { m.put("replyTo", replyText); m.put("replyToId", replyId); m.put("replyToSenderId", replySid); } return m;
    }
    private void pushMsg(Map<String, Object> m, String preview) {
        db().collection("chats").document(chatId).collection("messages").add(m); long now = l(m.get("createdAt"));
        Map<String, Object> lm = new HashMap<>(); lm.put("text", preview); lm.put("senderId", me()); lm.put("senderName", myName()); lm.put("at", now); String ty = s(m, "type"); if (!ty.equals("text")) lm.put("type", ty);
        Map<String, Object> up = new HashMap<>(); up.put("lastMsg", lm); up.put("updatedAt", now); up.put("readAt." + me(), now); db().collection("chats").document(chatId).update(up); clearReply();
    }
    private void send() {
        String text = input.getText().toString().trim(); if (text.isEmpty() || !canSend("text", me(), meta)) return;
        Map<String, Object> m = baseMsg("text"); m.put("text", text); List<String> mn = extractMentions(text); if (!mn.isEmpty()) m.put("mentions", mn); input.setText(""); setTyping(false); pushMsg(m, text.length() > 120 ? text.substring(0, 120) : text);
    }
    private void pickImage() { if (!canSend("media", me(), meta)) { toast(this, "إرسال الصور غير مسموح لك في هذه المجموعة"); return; } Intent i = new Intent(Intent.ACTION_GET_CONTENT); i.setType("image/*"); startActivityForResult(i, 11); }
    private void sendImage(final android.net.Uri uri) {
        toast(this, "جاري إرسال الصورة…");
        new Thread(() -> {
            try {
                Bitmap b = BitmapFactory.decodeStream(getContentResolver().openInputStream(uri)); if (b == null) { runOnUiThread(() -> toast(this, "تعذر تجهيز الصورة")); return; }
                int mx = 1280; float sc = Math.min(1f, mx / (float) Math.max(b.getWidth(), b.getHeight())); if (sc < 1f) b = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * sc), Math.round(b.getHeight() * sc), true);
                File f = new File(getCacheDir(), "grp_" + System.currentTimeMillis() + ".jpg"); FileOutputStream o = new FileOutputStream(f); b.compress(Bitmap.CompressFormat.JPEG, 82, o); o.close(); String url = "";
                try { url = ChatMediaUp.upload(f, "image", "chat", new ChatMediaUp.Cancel(), null).optString("secure_url", ""); } catch (Throwable ignored) { }
                final Map<String, Object> m = baseMsg("image");
                if (!url.isEmpty()) { m.put("data", url); m.put("imageUrl", url); } else { ByteArrayOutputStream bo = new ByteArrayOutputStream(); Bitmap sm = Bitmap.createScaledBitmap(b, Math.max(1, b.getWidth() / 2), Math.max(1, b.getHeight() / 2), true); sm.compress(Bitmap.CompressFormat.JPEG, 60, bo); if (bo.size() > 700000) { runOnUiThread(() -> toast(this, "تعذر رفع الصورة")); return; } m.put("data", "data:image/jpeg;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)); }
                m.put("mime", "image/jpeg"); m.put("duration", 0L); m.put("w", b.getWidth()); m.put("h", b.getHeight()); runOnUiThread(() -> pushMsg(m, "📷 صورة"));
            } catch (Throwable e) { runOnUiThread(() -> toast(this, "تعذر إرسال الصورة")); }
        }).start();
    }

    // ---------- البصمات الصوتية ----------
    private void startRec() {
        if (!canSend("voice", me(), meta)) { toast(this, "إرسال البصمات الصوتية غير مسموح لك"); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 21); return; }
        try {
            recFile = new File(getCacheDir(), "grp_rec_" + System.currentTimeMillis() + ".m4a"); rec = android.os.Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            rec.setAudioSource(MediaRecorder.AudioSource.MIC); rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4); rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC); rec.setAudioEncodingBitRate(64000); rec.setAudioChannels(1); rec.setAudioSamplingRate(44100); rec.setOutputFile(recFile.getAbsolutePath()); rec.prepare(); rec.start();
            recording = true; recStart = System.currentTimeMillis(); inputBar.setVisibility(View.GONE); recBar.setVisibility(View.VISIBLE);
            recTick = new Runnable() { public void run() { if (!recording) return; long s = (System.currentTimeMillis() - recStart) / 1000; recTimer.setText(String.format(Locale.US, "%02d:%02d", s / 60, s % 60)); if (s >= 60) { stopRec(true); return; } h.postDelayed(this, 250); } }; h.post(recTick);
        } catch (Throwable e) { recording = false; try { if (rec != null) rec.release(); } catch (Throwable ignored) { } rec = null; toast(this, "تعذر بدء التسجيل"); }
    }
    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] g) { super.onRequestPermissionsResult(rc, p, g); if (rc == 21 && g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) startRec(); }
    private void stopRec(boolean send) {
        if (!recording || rec == null) return; recording = false; h.removeCallbacks(recTick); long dur = Math.max(1, (System.currentTimeMillis() - recStart) / 1000);
        try { rec.stop(); } catch (Throwable e) { send = false; } try { rec.release(); } catch (Throwable ignored) { } rec = null; recBar.setVisibility(View.GONE); boolean canText = canSend("text", me(), meta); inputBar.setVisibility(canText ? View.VISIBLE : View.GONE);
        if (!send || recFile == null) { if (recFile != null) recFile.delete(); return; }
        if (dur < 1) { toast(this, "التسجيل قصير جداً"); recFile.delete(); return; }
        try { byte[] raw = new byte[(int) recFile.length()]; FileInputStream in = new FileInputStream(recFile); int off = 0, n; while (off < raw.length && (n = in.read(raw, off, raw.length - off)) > 0) off += n; in.close(); recFile.delete();
            if (raw.length > 650000) { toast(this, "التسجيل طويل جداً"); return; }
            Map<String, Object> m = baseMsg("audio"); m.put("data", "data:audio/mp4;base64," + Base64.encodeToString(raw, Base64.NO_WRAP)); m.put("mime", "audio/mp4"); m.put("duration", dur); pushMsg(m, "🎤 رسالة صوتية");
        } catch (Throwable e) { toast(this, "تعذر إرسال التسجيل"); }
    }
    private static void stopPlayer() { try { if (player != null) { player.stop(); player.release(); } } catch (Throwable ignored) { } player = null; playingId = ""; }
    private MsgViews.Handle playHandle;
    private final Runnable progTick = new Runnable() { @Override public void run() {
        if (player == null || playingId.isEmpty()) return;
        try { int du = player.getDuration(), cp = player.getCurrentPosition(); if (playHandle != null && du > 0) { long sec = cp / 1000; playHandle.setProgress(cp / (float) du, String.format(Locale.US, "%d:%02d", sec / 60, sec % 60)); } } catch (Throwable ignored) { }
        h.postDelayed(this, 100); } };
    private void togglePlay(final String id, String data) {
        if (playingId.equals(id)) { stopPlayer(); adapter.notifyDataSetChanged(); return; } stopPlayer();
        try { byte[] raw = Base64.decode(data.substring(data.indexOf(',') + 1), Base64.DEFAULT); File f = new File(getCacheDir(), "grp_play.m4a"); FileOutputStream o = new FileOutputStream(f); o.write(raw); o.close();
            player = new MediaPlayer(); player.setDataSource(f.getAbsolutePath()); player.prepare(); player.start(); playingId = id; h.removeCallbacks(progTick); h.postDelayed(progTick, 150); player.setOnCompletionListener(mp -> { stopPlayer(); adapter.notifyDataSetChanged(); }); adapter.notifyDataSetChanged();
        } catch (Throwable e) { stopPlayer(); toast(this, "تعذر تشغيل التسجيل"); }
    }

    // ---------- الرد والتفاعلات والقائمة ----------
    private void clearReply() { replyId = ""; replyText = ""; replySid = ""; replyBar.setVisibility(View.GONE); }
    private void setReply(DocumentSnapshot d) {
        Map<String, Object> m = d.getData(); replyId = d.getId(); replySid = s(m, "senderId"); String ty = s(m, "type"); replyText = ty.equals("image") ? "📷 صورة" : ty.equals("audio") ? "🎤 رسالة صوتية" : s(m, "text");
        replyBar.removeAllViews();   // نفس replyPreviewBar في mass.xml: زر الإغلاق أولاً (30dp) ثم العنوان والنص، بلا خط ذهبي
        TextView cancel = new TextView(this); cancel.setGravity(Gravity.CENTER); cancel.setTextSize(16); cancel.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_close_18, 0, 0, 0); Tk.handle("rpCancel", cancel, cancel, t.dark); cancel.setOnClickListener(v -> clearReply());
        LinearLayout.LayoutParams cxp = lp(t.u(30), t.u(30)); cxp.setMarginEnd(t.u(10)); replyBar.addView(cancel, cxp);
        LinearLayout tx = new LinearLayout(this); tx.setOrientation(LinearLayout.VERTICAL);
        TextView lb = new TextView(this); lb.setText("الرد على " + userName(replySid)); lb.setTextSize(11); lb.setTypeface(null, android.graphics.Typeface.BOLD); lb.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL); lb.setCompoundDrawablePadding(t.u(5)); lb.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_reply_16, 0); Tk.handle("rpLabel", lb, lb, t.dark); LinearLayout.LayoutParams lbp = lp(-1, -2); lbp.bottomMargin = t.u(2); tx.addView(lb, lbp);
        TextView rt = new TextView(this); rt.setText(MsgViews.iconize(this, replyText, t.dark ? 0xFFF1F5F9 : 0xFF17212B, 14)); rt.setTextSize(13); rt.setTypeface(null, android.graphics.Typeface.BOLD); rt.setGravity(Gravity.RIGHT); rt.setMaxLines(1); rt.setEllipsize(TextUtils.TruncateAt.END); Tk.handle("rpMsg", rt, rt, t.dark); tx.addView(rt, lp(-1, -2));
        replyBar.addView(tx, lpw(0, -2, 1));
        replyBar.setVisibility(View.VISIBLE); input.requestFocus();
    }
    private void msgMenu(final DocumentSnapshot d, final View anchor) {
        final Map<String, Object> m = d.getData(); final String sid = s(m, "senderId"), text = s(m, "text"), type = s(m, "type"); final boolean canPin = right("pin", me(), meta); boolean ip = false; for (Map<String, Object> p : pins()) if (d.getId().equals(s(p, "id"))) ip = true; final boolean fp = ip;
        final boolean canDel = sid.equals(me()) || right("delete", me(), meta); final String mine = s(map(m, "reactions"), me());
        MsgMenu.Spec sp = new MsgMenu.Spec(); sp.canCopy = !text.isEmpty() && !type.equals("image") && !type.equals("audio"); sp.canDownload = type.equals("image"); sp.downloadLabel = "تحميل الصورة"; sp.canPin = canPin; sp.pinned = fp; sp.myReaction = mine;
        MsgMenu.show(this, anchor, t.dark, 0, 0, sp, new MsgMenu.Cb() {
            @Override public void reply() { setReply(d); }
            @Override public void copy() { ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("m", text)); toast(GroupRoomActivity.this, "تم نسخ النص!"); }
            @Override public void download() { saveImage(m); }
            @Override public void edit() { }
            @Override public void delete() { if (!canDel) { toast(GroupRoomActivity.this, "ليس لديك صلاحية حذف هذه الرسالة"); return; } confirm(GroupRoomActivity.this, t, "حذف الرسالة", "حذف هذه الرسالة لدى الجميع؟", "حذف", (ok, x) -> { if (ok) d.getReference().delete(); }); }
            @Override public void react(String emoji) { setReaction(d, emoji.equals(mine) ? null : emoji); }
            @Override public void pin() { togglePin(d.getId(), text.isEmpty() ? (type.equals("image") ? "📷 صورة" : "🎤 رسالة صوتية") : text, fp); }
        });
    }
    private void saveImage(Map<String, Object> m) {
        String src = !s(m, "imageUrl").isEmpty() ? s(m, "imageUrl") : s(m, "data");
        loadBitmap(src, 4096, bm -> { try { android.provider.MediaStore.Images.Media.insertImage(getContentResolver(), bm, "babylon_" + System.currentTimeMillis(), null); runOnUiThread(() -> toast(this, "تم حفظ الصورة")); } catch (Throwable e) { runOnUiThread(() -> toast(this, "تعذر حفظ الصورة")); } });
    }
    /** شارة التفاعل كما في المحادثة العادية (reactChip). */
    private View reactChip(String emoji, int count, boolean mineChip, boolean media, boolean onMineBubble) {
        final int acc = Tk.chatPal != null ? Tk.chatPal.accent : (t.dark ? 0xFFE4C45B : 0xFFC29D30);
        LinearLayout c = new LinearLayout(this); c.setLayoutDirection(View.LAYOUT_DIRECTION_LTR); c.setGravity(Gravity.CENTER_VERTICAL); c.setPadding(t.u(8), t.u(3), t.u(8), t.u(3));
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(t.u(20));
        if (onMineBubble) g.setColor((blend(0xFFFFFFFF, acc | 0xFF000000, 0.10f) & 0x00FFFFFF) | 0xBF000000);
        else g.setColor(mineChip ? ((acc & 0x00FFFFFF) | 0xB3000000) : (media ? 0x66000000 : ((acc & 0x00FFFFFF) | 0x33000000)));
        c.setBackground(g); int fg = onMineBubble ? acc : (mineChip || media ? 0xFFFFFFFF : acc);
        TextView e = new TextView(this); e.setText(emoji); e.setTextSize(emoji.startsWith("+") ? 11 : 17); e.setIncludeFontPadding(false); e.setTextColor(fg); c.addView(e);
        if (count > 1) { TextView n = new TextView(this); n.setText(String.valueOf(count)); n.setTextSize(12); n.setTypeface(null, android.graphics.Typeface.BOLD); n.setIncludeFontPadding(false); n.setTextColor(fg); LinearLayout.LayoutParams np = lp(-2, -2); np.setMarginStart(t.u(4)); c.addView(n, np); }
        return c;
    }
    /** الرد بالسحب: رسائلي يمين→يسار ورسائل غيري يسار→يمين، مقاومة بعد 72dp. */
    private void swipeReply(final View v, final boolean mine, final DocumentSnapshot d) {
        final float[] st = new float[3]; final int slop = android.view.ViewConfiguration.get(this).getScaledTouchSlop(); final float thr = t.u(72);
        v.setOnTouchListener((x, ev) -> {
            int a = ev.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) { st[0] = ev.getRawX(); st[1] = ev.getRawY(); st[2] = 0; return false; }
            if (a == MotionEvent.ACTION_MOVE) {
                float dx = ev.getRawX() - st[0], dy = ev.getRawY() - st[1];
                if (st[2] == 0 && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.5f && (mine ? dx < 0 : dx > 0)) { st[2] = 1; if (x.getParent() != null) x.getParent().requestDisallowInterceptTouchEvent(true); x.cancelLongPress(); }
                if (st[2] == 1) { float ab = Math.abs(dx); x.setTranslationX((mine ? -1 : 1) * (ab < thr ? ab : thr + (ab - thr) * 0.25f)); return true; }
                return false;
            }
            if ((a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) && st[2] == 1) { boolean go = a == MotionEvent.ACTION_UP && Math.abs(ev.getRawX() - st[0]) >= thr; x.animate().translationX(0f).setDuration(180).start(); st[2] = 0; if (go) setReply(d); return true; }
            return false;
        });
    }
    private void setReaction(DocumentSnapshot d, String emoji) { d.getReference().update("reactions." + me(), emoji == null ? FieldValue.delete() : emoji); }
    private void togglePin(String id, String text, boolean isPinned) {
        List<Map<String, Object>> pins = new ArrayList<>(pins());
        if (isPinned) { for (Iterator<Map<String, Object>> it = pins.iterator(); it.hasNext(); ) if (id.equals(s(it.next(), "id"))) it.remove(); pinIdx = -1; db().collection("chats").document(chatId).update("pins", pins); toast(this, "تم إلغاء تثبيت الرسالة"); return; }
        if (pins.size() >= 10) { toast(this, "الحد الأقصى 10 رسائل مثبتة"); return; } Map<String, Object> p = new HashMap<>(); p.put("id", id); p.put("text", text.length() > 90 ? text.substring(0, 90) : text); p.put("by", me()); p.put("at", System.currentTimeMillis()); pins.add(p); pinIdx = pins.size() - 1;
        Map<String, Object> up = addSystem(chatId, "pin", null, Collections.<String, Object>singletonMap("ref", id)); up.put("pins", pins); db().collection("chats").document(chatId).update(up); toast(this, "تم تثبيت الرسالة");
    }

    // ---------- قائمة الرسائل ----------
    private boolean readByAll(long created) { Map<String, Object> ra = map(meta, "readAt"); int others = 0; for (String u : parts(meta)) if (!u.equals(me())) { others++; if (l(ra.get(u)) < created) return false; } return others > 0; }
    private String keyOf(DocumentSnapshot d) { return isTrue(d.get("system")) ? null : s(d.getData(), "senderId"); }
    private android.graphics.drawable.Drawable bubbleBg(boolean mine, boolean first, boolean last) {
        float big = t.u(radius), sm = t.u(6), tl, tr, br, bl;
        if (mine) { tl = big; bl = big; tr = first ? big : sm; br = last ? (first ? sm : big) : sm; }
        else { tr = big; br = big; tl = first ? big : sm; bl = last ? (first ? sm : big) : sm; }
        float[] r8 = {tl, tl, tr, tr, br, br, bl, bl};
        if (Tk.chatPal != null) return ChatThemes.bubble(Tk.chatPal, mine, r8, mine ? 0f : t.u(1));
        GradientDrawable g;
        if (mine) g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFFE8C85D, 0xFFCBA843});
        else { g = new GradientDrawable(); g.setColor(t.dark ? 0xFF263746 : 0xFFFFFFFF); }
        g.setCornerRadii(r8); return g;
    }
    private final class MsgAdapter extends BaseAdapter {
        public int getCount() { return msgs.size(); } public Object getItem(int i) { return msgs.get(i); } public long getItemId(int i) { return i; }
        public View getView(int pos, View cv, ViewGroup parent) {
            final Activity A = GroupRoomActivity.this; final DocumentSnapshot d = msgs.get(pos); final Map<String, Object> m = d.getData();
            LinearLayout row = new LinearLayout(A); row.setOrientation(LinearLayout.HORIZONTAL); row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR); row.setLayoutParams(new AbsListView.LayoutParams(-1, -2));
            if (isTrue(m.get("system"))) {
                TextView tx = MsgViews.sysPill(A, sysText(m)); tint(tx); row.setGravity(Gravity.CENTER); row.addView(tx);   // هامش الحبة من MsgViews.sysPill نفسه كما في المحادثة العادية
                
                if ("pin".equals(s(map(m, "sys"), "k")) && !s(map(m, "sys"), "ref").isEmpty()) { final String ref = s(map(m, "sys"), "ref"); tx.setPaintFlags(tx.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG); tx.setOnClickListener(v -> jumpTo(ref)); } return withDay(pos, m, row);
            }
            final String sid = s(m, "senderId"); final boolean mine = sid.equals(me()); String prevK = pos > 0 ? keyOf(msgs.get(pos - 1)) : null, nextK = pos + 1 < msgs.size() ? keyOf(msgs.get(pos + 1)) : null; final boolean first = !sid.equals(prevK), last = !sid.equals(nextK);
            row.setPadding(0, t.u(4), 0, t.u(4)); int screen = getResources().getDisplayMetrics().widthPixels; int maxBub = Math.round(screen * .92f) - (mine ? 0 : t.k(32) + t.u(7)) - t.u(28);
            LinearLayout col = new LinearLayout(A); col.setOrientation(LinearLayout.VERTICAL); rtl(col);
            String type = s(m, "type"); final String data = s(m, "data"); final boolean isImg = type.equals("image") && !data.isEmpty(), isAud = type.equals("audio") && !data.isEmpty(), media = isImg || isAud; FrameLayout imgFrame = null; final long ct = l(m.get("createdAt"));
            final LinearLayout bub;
            if (!media) {
                String raw = s(m, "text"), rTo0 = s(m, "replyTo");
                if (!rTo0.isEmpty() && !raw.startsWith("\u21A9 ")) { String rs0 = s(m, "replyToSenderId"); raw = "\u21A9 " + (rs0.isEmpty() ? "" : userName(rs0) + "\u0001") + rTo0.replace('\n', ' ').replace('\u0002', ' ') + "\u0002" + s(m, "replyToId") + "\u0002" + "" + "\n" + raw; }
                bub = MsgViews.textBubble(A, raw, mine, time12(ct), mine ? (readByAll(ct) ? MsgViews.READ : MsgViews.SENT) : MsgViews.SENT, fontSp);
            } else if (isAud) { long du = l(m.get("duration")); bub = new MsgViews.VoiceBubble(A, mine, String.format(Locale.US, "%d:%02d", du / 60, du % 60)); }
            else { bub = new MsgViews.Bub(A); bub.setOrientation(LinearLayout.VERTICAL); }
            if ((media && !isImg) || "bubMine".equals(bub.getTag()) || "bubOther".equals(bub.getTag())) bub.setBackground(bubbleBg(mine, first, last));   // الإيموجي بلا فقاعة كما في الدردشة الخاصة
            int txtColor = mine ? t.pal.textMine : t.crInk, metaColor = mine ? (t.pal.textMine & 0x00FFFFFF | 0xE6000000) : t.crMuted;
            if (!mine && first) {
                LinearLayout hd = new LinearLayout(A); hd.setLayoutDirection(View.LAYOUT_DIRECTION_LTR); hd.setGravity(Gravity.CENTER_VERTICAL); TextView sn = tv(A, t, userName(sid), 13.5f, colorOf(sid), true); sn.setSingleLine(true); sn.setEllipsize(TextUtils.TruncateAt.END); sn.setMaxWidth(Math.min(t.u(200), Math.round(screen * .46f))); hd.addView(sn);
                View sp = new View(A); hd.addView(sp, lpw(0, 1, 1)); String tt = title(sid, meta);
                if (!tt.isEmpty()) { String r = role(sid, meta); TextView rl = tv(A, t, tt, 11.5f, r.equals("owner") ? (t.dark ? 0xFFA78BFA : 0xFF7C3AED) : r.equals("admin") ? (t.dark ? 0xFF4ADE80 : 0xFF16A34A) : t.sub, true); int bg = r.equals("owner") ? (t.dark ? 0x33A78BFA : 0x1F7C3AED) : r.equals("admin") ? (t.dark ? 0x294ADE80 : 0x1F16A34A) : 0x2E8798AA; rl.setBackground(round(bg, t.u(10))); rl.setPadding(t.u(10), t.u(2), t.u(10), t.u(2)); LinearLayout.LayoutParams rp = lp(-2, -2); rp.setMarginStart(t.u(10)); hd.addView(rl, rp); }
                LinearLayout.LayoutParams hp = lp(-1, -2); hp.bottomMargin = t.u(3); hp.leftMargin = t.u(2); hp.rightMargin = t.u(2); bub.addView(hd, 0, hp); hd.setOnClickListener(v -> openInfo(sid));
            }
            String rTo = s(m, "replyTo"); final String qEnc = rTo.isEmpty() ? "" : (s(m, "replyToSenderId").isEmpty() ? "" : userName(s(m, "replyToSenderId"))) + "\u0001" + rTo.replace('\n', ' ').replace('\u0002', ' ') + "\u0002" + s(m, "replyToId") + "\u0002";
            if (isAud && !qEnc.isEmpty()) { View qv = MsgViews.quoteView(A, qEnc, mine); bub.addView(qv, (!mine && first) ? 1 : 0); }
            if (isImg) {
                final String src = !s(m, "imageUrl").isEmpty() ? s(m, "imageUrl") : data; int size = Math.min(t.u(220), (int) (screen * 0.68f));
                final FrameLayout fr = new FrameLayout(A); imgFrame = fr; fr.setClipToOutline(true); GradientDrawable fbg = new GradientDrawable(); fbg.setColor(t.dark ? 0xFF202C33 : 0xFFF0F0F0); fbg.setCornerRadius(t.u(12)); fr.setBackground(fbg);
                fr.setOutlineProvider(new android.view.ViewOutlineProvider() { @Override public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), t.u(12)); } });
                final ImageView iv = new ImageView(A); iv.setScaleType(ImageView.ScaleType.CENTER_CROP); iv.setImageDrawable(Skeleton.shimmer(t.dark, 0f)); fr.addView(iv, new FrameLayout.LayoutParams(-1, -1));
                loadBitmap(src, 900, bm -> iv.post(() -> iv.setImageBitmap(bm))); fr.setOnClickListener(v -> viewImage(src)); fr.setOnLongClickListener(v -> { msgMenu(d, bub); return true; });
                LinearLayout tm = new LinearLayout(A); tm.setGravity(Gravity.CENTER_VERTICAL); tm.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
                TextView tt2 = new TextView(A); tt2.setText(time12(ct)); tt2.setTextSize(10); tt2.setTextColor(0xFFFFFFFF); tt2.setTypeface(null, android.graphics.Typeface.BOLD); tt2.setIncludeFontPadding(false); tm.addView(tt2);
                if (mine) { int stt = readByAll(ct) ? MsgViews.READ : MsgViews.SENT; ImageView tick = new ImageView(A); tick.setTag("tick"); tick.setImageDrawable(new MsgViews.TickDrawable(A, stt, 0, Tk.tickColor(stt))); LinearLayout.LayoutParams tkl = new LinearLayout.LayoutParams(-2, -2); tkl.setMarginStart(t.u(3)); tm.addView(tick, tkl); }
                tm.setPadding(t.u(6), t.u(2), t.u(6), t.u(2)); GradientDrawable tg = new GradientDrawable(); tg.setColor(0x99000000); tg.setCornerRadius(t.u(8)); tm.setBackground(tg);
                FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-2, -2); tlp.gravity = Gravity.BOTTOM | Gravity.END; tlp.setMargins(0, 0, t.u(6), t.u(6)); fr.addView(tm, tlp);
                if (!qEnc.isEmpty()) { View qv = MsgViews.quoteView(A, qEnc, true); FrameLayout.LayoutParams qlp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP); qlp.setMargins(t.u(6), t.u(6), t.u(6), 0); fr.addView(qv, qlp); tint(qv); GradientDrawable qg = new GradientDrawable(); qg.setColor(0xB3000000); qg.setCornerRadius(t.u(10)); qv.setBackground(qg); }
                bub.addView(fr, lp(size, size));
            } else if (isAud) {
                final MsgViews.VoiceBubble vb = (MsgViews.VoiceBubble) bub; final boolean playing = playingId.equals(d.getId());
                vb.playBtn.setOnClickListener(v -> togglePlay(d.getId(), data));
                vb.track.listener = f -> { try { if (player != null && playingId.equals(d.getId())) player.seekTo((int) (player.getDuration() * f)); } catch (Throwable ignored) { } };
                if (playing) { vb.setPlaying(true); playHandle = vb; }
            }
            if (isAud) { bub.addView(MsgViews.metaRow(A, mine, time12(ct), mine ? (readByAll(ct) ? MsgViews.READ : MsgViews.SENT) : MsgViews.SENT)); }
            tint(bub);
            bub.setOnLongClickListener(v -> { msgMenu(d, bub); return true; }); if (!isImg) bub.setOnClickListener(v -> msgMenu(d, bub)); swipeReply(bub, mine, d);
            col.addView(bub);
            Map<String, Object> rx = map(m, "reactions"); if (!rx.isEmpty()) { Map<String, Integer> cnt = new LinkedHashMap<>(); for (Object v : rx.values()) { String e = String.valueOf(v); if (e.isEmpty()) continue; cnt.put(e, cnt.containsKey(e) ? cnt.get(e) + 1 : 1); } LinearLayout rr = new LinearLayout(A); rr.setLayoutDirection(View.LAYOUT_DIRECTION_LTR); int shown = 0; final String my = mine2(m);
                for (final Map.Entry<String, Integer> en : cnt.entrySet()) { if (shown == 3) break; View c = reactChip(en.getKey(), en.getValue(), en.getKey().equals(my), isImg, mine && !isImg); c.setOnClickListener(v -> setReaction(d, en.getKey().equals(my) ? null : en.getKey())); c.setOnLongClickListener(v -> { msgMenu(d, bub); return true; }); LinearLayout.LayoutParams cp = lp(-2, -2); cp.setMarginEnd(t.u(4)); rr.addView(c, cp); shown++; }
                if (cnt.size() > shown) rr.addView(reactChip("+" + (cnt.size() - shown), 1, false, isImg, mine && !isImg));
                if (imgFrame != null) { FrameLayout.LayoutParams rfl = new FrameLayout.LayoutParams(-2, -2); rfl.gravity = Gravity.BOTTOM | Gravity.LEFT; rfl.setMargins(t.u(8), 0, 0, t.u(8)); imgFrame.addView(rr, rfl); }
                else { LinearLayout.LayoutParams rlp = lp(-2, -2); rlp.topMargin = t.u(4); col.addView(rr, rlp); } }
            if (d.getId().equals(flashId)) { bub.setAlpha(.35f); bub.animate().alpha(1f).setDuration(1200).start(); }
            if (mine) { row.setGravity(Gravity.RIGHT); row.addView(col); }   // المرسَلة يميناً والمستلمة يساراً (مطلق)
            else { row.setGravity(Gravity.LEFT); Av av = new Av(A, 32 * t.s); av.setUser(sid); av.setVisibility(last ? View.VISIBLE : View.INVISIBLE); LinearLayout.LayoutParams al = lp(t.k(32), t.k(32)); al.setMarginEnd(t.u(7)); al.gravity = Gravity.BOTTOM; al.bottomMargin = t.u(2); row.addView(av, al); row.addView(col); av.setOnClickListener(v -> openInfo(sid)); }
            return withDay(pos, m, row);
        }
    }
    private String mine2(Map<String, Object> m) { return s(map(m, "reactions"), me()); }
    private void viewImage(String src) {
        final Dialog dg = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen); final ImageView iv = new ImageView(this); iv.setScaleType(ImageView.ScaleType.FIT_CENTER); iv.setBackgroundColor(0xFF000000); iv.setOnClickListener(v -> dg.dismiss()); dg.setContentView(iv); dg.show();
        loadBitmap(src, 2048, bm -> iv.post(() -> iv.setImageBitmap(bm)));
    }
}
