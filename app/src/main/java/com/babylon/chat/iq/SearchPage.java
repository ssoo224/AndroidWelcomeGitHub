package com.babylon.chat.iq;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ReplacementSpan;
import android.util.LruCache;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * صفحة «بحث المستخدمين» — مطابقة لنسخة الـ HTML (index.html) بعد تطبيق كل طبقات الـ CSS:
 *   <style id="babylon-search-redesign"> + قواعد الأيقونة/الحقل/الزر التي تليه.
 *
 * - الهيدر: تدرّج (#1e293b → #17212b ليلي ، #fff → #f8fafd نهاري) بحواف سفلية 30 وظل.
 * - زر الرجوع 42×42 بانحناء 16 وحدّ ، والعنوان 19 ثقيل.
 * - حقل البحث كبسولة (52 على الأقل) بأيقونة عدسة وزر مسح دائري يظهر عند وجود نص.
 * - الكروت: انحناء 24 ، أفاتار 50 بتدرّج (ذهبي/أزرق حسب المعرّف) ، شارة التوثيق الذهبية ، ودائرة ‹ باللون الذهبي.
 * - الحالات: ابحث عن أصدقاء / جارٍ البحث… / لا يوجد مستخدم / تعذر البحث (بطاقات بحدّ منقّط انحناء 28).
 * - «الأخيرة»: آخر الملفات التي بدأتَ منها محادثة ، ضغطة مطوّلة للتحديد ثم حذف/إلغاء.
 *
 * 1px في الـ HTML = 1dp و px للخط = sp (نفس قاعدة Tk).
 */
final class SearchPage {

    interface Host {
        boolean dark();
        String myUid();
        void openUser(String name, String username, String uid);
    }

    // ------------------------------------------------------------------ الألوان (--sr-*)
    private static final class P {
        final int bg, topA, topB, field, fieldBorder, card, line, chip, gold, text, mute, icon, soft, clearBg;

        P(int bg, int topA, int topB, int field, int fieldBorder, int card, int line, int chip,
          int gold, int text, int mute, int icon, int soft, int clearBg) {
            this.soft = soft; this.clearBg = clearBg;
            this.bg = bg; this.topA = topA; this.topB = topB; this.field = field; this.fieldBorder = fieldBorder;
            this.card = card; this.line = line; this.chip = chip; this.gold = gold; this.text = text;
            this.mute = mute; this.icon = icon;
        }

        // خلفية الصفحة والبطاقات والحدود والنصوص من نفس قيم صفحة الإعدادات (Tk.pageBg / surface / border / muted)
        // soft: لون × الإلغاء وكلمة «جاري البحث» (أسود رمادي نهاري / أبيض رمادي ليلي) بدل الذهبي
        static final P DARK = new P(Tk.pageBg(true), Tk.surface(true), Tk.surface(true), Tk.surface2(true), 0x00000000,
                Tk.surface(true), Tk.border(true), 0x1FE4C45B, 0xFFE4C45B, Tk.text(true), Tk.muted(true), 0xFFD4AF37,
                0xFFCBD5E1, 0x1FFFFFFF);
        static final P LIGHT = new P(Tk.pageBg(false), Tk.surface(false), Tk.surface(false), Tk.chipBg(false), 0xFFE3E9F1,
                Tk.surface(false), Tk.border(false), 0x21C29D30, 0xFFB98710, Tk.text(false), Tk.muted(false), 0xFF000000,
                0xFF4B5563, 0x14000000);

        static P of(boolean dark) { return dark ? DARK : LIGHT; }
    }

    /** خلفية الصفحة وألوان شريط الحالة (يستعملها MassActivity). */
    static int bg(boolean dark) { return P.of(dark).bg; }
    static int topColor(boolean dark) { return P.of(dark).topA; }

    private static final int RED = 0xFFF87171;                 // --error في الـ HTML (حالة الخطأ + زر الحذف)
    private static final int HINT_IDLE = 0, HINT_LOADING = 1, HINT_ERROR = 2;
    private static final int MAX_RECENT = 12;

    private enum St { IDLE, SHORT, LOADING, RESULTS, EMPTY, ERROR }

    private static final class Item {
        String id = "", name = "", username = "", photo = "";
        boolean verified, hideAvatar;
    }

    // ------------------------------------------------------------------ الحالة
    private final Activity act;
    private final Host host;
    private final float density;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> PHOTOS = new LruCache<>(48);

    private View searchView, top, backBtn, field, clearBtn;
    private ImageView backIcon, fieldIcon, clearIcon;
    private TextView title, hint;
    private EditText input;
    private LinearLayout results;
    private ScrollView scroll;

    private P p = P.LIGHT;
    private int token = 0;
    private St state = St.IDLE;
    private final List<Item> found = new ArrayList<>();
    private String hintText = "اكتب 3 أحرف على الأقل";
    private int hintKind = HINT_IDLE;

    // «الأخيرة»
    private boolean selMode = false;
    private final LinkedHashSet<String> sel = new LinkedHashSet<>();
    private final Map<String, Item> recentCache = new HashMap<>();
    private final Map<String, View> recentRows = new HashMap<>();
    private final java.util.Set<String> fetching = new java.util.HashSet<>();
    private TextView recentLabel;
    private LinearLayout recentActions;

    SearchPage(Activity act, Host host) {
        this.act = act;
        this.host = host;
        this.density = act.getResources().getDisplayMetrics().density;
    }

    // ------------------------------------------------------------------ ربط العناصر
    void bind() {
        searchView = act.findViewById(R.id.searchView);
        top = act.findViewById(R.id.searchTop);
        backBtn = act.findViewById(R.id.searchBack);
        backIcon = (ImageView) act.findViewById(R.id.searchBackIcon);
        title = (TextView) act.findViewById(R.id.textview622);
        field = act.findViewById(R.id.searchField);
        fieldIcon = (ImageView) act.findViewById(R.id.searchFieldIcon);
        input = (EditText) act.findViewById(R.id.searchInput);
        clearBtn = act.findViewById(R.id.searchClearBtn);
        clearIcon = (ImageView) act.findViewById(R.id.searchClearIcon);
        hint = (TextView) act.findViewById(R.id.searchHint);
        scroll = (ScrollView) act.findViewById(R.id.vscroll40);
        results = (LinearLayout) act.findViewById(R.id.searchResults);
        if (searchView == null || results == null || input == null) return;

        // الظل يمتد خارج الهيدر: لا نقصّ الأبناء
        if (searchView instanceof ViewGroup) ((ViewGroup) searchView).setClipChildren(false);
        top.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                // نمدّ المستطيل فوق الحافة العلوية فتنحني الزاويتان السفليتان فقط
                o.setRoundRect(0, -dp(40), v.getWidth(), v.getHeight(), dp(30));
            }
        });
        top.setClipToOutline(false);
        top.setElevation(0f);

        BabylonFx.press(backBtn, 0.92f);
        clearBtn.setOnClickListener(x -> {
            input.setText("");
            input.requestFocus();
        });
        input.setOnEditorActionListener((v, actionId, e) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(); return true; }
            return false;
        });
        applyTheme();
    }

    // ------------------------------------------------------------------ المظهر
    void applyTheme() {
        if (searchView == null || input == null) return;
        p = P.of(host.dark());

        searchView.setBackgroundColor(p.bg);
        if (scroll != null) scroll.setBackgroundColor(p.bg);

        GradientDrawable tg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{p.topA, p.topB});
        float r = dp(30);
        tg.setCornerRadii(new float[]{0, 0, 0, 0, r, r, r, r});
        top.setBackground(tg);

        backBtn.setBackground(box(p.field, 16, p.line, 1));
        backIcon.setColorFilter(p.text);
        title.setTextColor(p.text);

        field.setBackground(box(p.field, 40, p.fieldBorder, 1.5f));
        fieldIcon.setColorFilter(p.icon);
        input.setTextColor(p.text);
        input.setHintTextColor((p.mute & 0x00FFFFFF) | (0xD9 << 24));       // opacity .85

        GradientDrawable cg = new GradientDrawable();
        cg.setShape(GradientDrawable.OVAL);
        cg.setColor(p.clearBg);
        clearBtn.setBackground(cg);
        clearIcon.setColorFilter(p.soft);

        setHint(hintText, hintKind);
        renderCurrent();
    }

    // ------------------------------------------------------------------ الدخول إلى الصفحة (openSearch في الـ HTML)
    void onShown() {
        if (input == null) return;
        selMode = false;
        sel.clear();
        input.setText("");
        onQuery("");
        input.requestFocus();
        input.postDelayed(() -> {
            try {
                InputMethodManager m = (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (m != null) m.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            } catch (Throwable ignored) { }
        }, 160);
    }

    // ------------------------------------------------------------------ searchUsers() في الـ HTML
    void onQuery(String raw) {
        if (results == null) return;
        if (raw == null) raw = "";
        String q = raw.trim().toLowerCase();
        if (q.startsWith("@")) q = q.substring(1);

        clearBtn.setVisibility(raw.length() > 0 ? View.VISIBLE : View.GONE);
        final int tok = ++token;
        selMode = false;
        sel.clear();
        found.clear();
        results.removeAllViews();

        if (q.length() < 3) {
            if (q.isEmpty()) {
                state = St.IDLE;
                renderIdle();
            } else {
                state = St.SHORT;
            }
            setHint(q.length() > 0 ? "باقي " + (3 - q.length()) + " أحرف" : "اكتب 3 أحرف على الأقل", HINT_IDLE);
            return;
        }

        state = St.LOADING;
        setHint("جارٍ البحث…", HINT_LOADING);
        results.addView(loadingCard());

        final String fq = q;
        FirebaseFirestore.getInstance().collection("users")
                .orderBy("username").startAt(fq).endAt(fq + "")
                .get().addOnCompleteListener(task -> {
                    if (tok != token) return;
                    if (!task.isSuccessful() || task.getResult() == null) {
                        state = St.ERROR;
                        results.removeAllViews();
                        results.addView(stateCard(false, "تعذر البحث", "تحقق من الاتصال"));
                        setHint("تعذر البحث", HINT_ERROR);
                        return;
                    }
                    String me = host.myUid();
                    found.clear();
                    for (QueryDocumentSnapshot d : task.getResult()) {
                        if (d.getId().equals(me)) continue;
                        found.add(toItem(d));
                    }
                    state = found.isEmpty() ? St.EMPTY : St.RESULTS;
                    renderCurrent();
                    setHint(found.isEmpty() ? "لا نتائج" : found.size() + " نتيجة", HINT_IDLE);
                    if (scroll != null) scroll.scrollTo(0, 0);
                });
    }

    private Item toItem(DocumentSnapshot d) {
        Item it = new Item();
        it.id = d.getId();
        it.name = firstNonEmpty(d.getString("displayName"), d.getString("name"));
        it.username = nz(d.getString("username"));
        it.photo = firstNonEmpty(d.getString("avatarUrl"), d.getString("photo"));
        it.hideAvatar = Boolean.TRUE.equals(d.getBoolean("hideAvatar"));
        it.verified = Boolean.TRUE.equals(d.getBoolean("isVerified")) || Boolean.TRUE.equals(d.getBoolean("isDev"))
                || "ali".equalsIgnoreCase(it.username);
        return it;
    }

    // ------------------------------------------------------------------ الرسم حسب الحالة
    private void renderCurrent() {
        if (results == null) return;
        results.removeAllViews();
        switch (state) {
            case IDLE: renderIdle(); break;
            case LOADING: results.addView(loadingCard()); break;
            case ERROR: results.addView(stateCard(false, "تعذر البحث", "تحقق من الاتصال")); break;
            case EMPTY: results.addView(stateCard(true, "لا يوجد مستخدم", "تحقق من الاسم")); break;
            case RESULTS:
                for (int i = 0; i < found.size(); i++) {
                    View c = userCard(found.get(i), false);
                    results.addView(c, gap(i));
                }
                break;
            default: break;
        }
    }

    private LinearLayout.LayoutParams gap(int index) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = index == 0 ? 0 : dp(10);
        return lp;
    }

    // ------------------------------------------------------------------ بطاقات الحالة
    /** .search-state-card .search-empty-state : أيقونة + عنوان + سطر ثانٍ. */
    private View stateCard(boolean magnifier, String head, String sub) {
        LinearLayout c = new LinearLayout(act);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        c.setMinimumHeight(dp(210));
        c.setPadding(dp(16), dp(24), dp(16), dp(24));
        c.setBackground(dashed(p.card, 28, p.line));

        FrameLayout icon = new FrameLayout(act);
        icon.setBackground(box(p.chip, 20, 0, 0));
        if (magnifier) {
            ImageView iv = new ImageView(act);
            iv.setImageResource(R.drawable.ic_sr_search);
            iv.setColorFilter(p.gold);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.addView(iv, new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER));
        } else {
            TextView bang = new TextView(act);
            bang.setText("!");
            bang.setTextSize(30);
            bang.setTextColor(p.gold);
            bang.setGravity(Gravity.CENTER);
            bang.setIncludeFontPadding(false);
            icon.addView(bang, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
        c.addView(icon, new LinearLayout.LayoutParams(dp(58), dp(58)));

        TextView h = new TextView(act);
        h.setText(head);
        h.setTextSize(16);
        h.setTypeface(null, android.graphics.Typeface.BOLD);
        h.setTextColor(p.text);
        h.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = dp(12);                                  // gap 8 + margin-bottom 4 للأيقونة
        c.addView(h, hp);

        TextView s = new TextView(act);
        s.setText(sub);
        s.setTextSize(12);
        s.setTextColor(p.mute);
        s.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(8);
        c.addView(s, sp);

        c.setLayoutParams(cardMargin());
        return c;
    }

    /** .search-state-card مع .search-loading-dot (يخفق 0.9 ث). */
    private View loadingCard() {
        LinearLayout c = new LinearLayout(act);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(16), dp(24), dp(16), dp(24));
        c.setBackground(dashed(p.card, 28, p.line));

        final View dot = new View(act);
        GradientDrawable halo = new GradientDrawable();
        halo.setShape(GradientDrawable.OVAL);
        halo.setColor(0x00D4AF37);                              // box-shadow 0 0 0 5px rgba(212,175,55,.12)
        GradientDrawable core = new GradientDrawable();
        core.setShape(GradientDrawable.OVAL);
        core.setColor(0xFFD4AF37);
        LayerDrawable ld = new LayerDrawable(new Drawable[]{halo, core});
        ld.setLayerInset(1, dp(5), dp(5), dp(5), dp(5));
        dot.setBackground(ld);
        c.addView(dot, new LinearLayout.LayoutParams(dp(18), dp(18)));

        TextView t = new TextView(act);
        t.setText("جارٍ البحث…");
        t.setTextSize(13);
        t.setTextColor(p.mute);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.setMarginStart(dp(10));
        c.addView(t, tp);

        final ObjectAnimator pulse = ObjectAnimator.ofPropertyValuesHolder(dot,
                PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.4f),
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 0.8f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 0.8f));
        pulse.setDuration(900);
        pulse.setRepeatMode(ObjectAnimator.REVERSE);
        pulse.setRepeatCount(ObjectAnimator.INFINITE);
        dot.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { if (!pulse.isStarted()) pulse.start(); }
            @Override public void onViewDetachedFromWindow(View v) { pulse.cancel(); }
        });

        c.setLayoutParams(cardMargin());
        return c;
    }

    private LinearLayout.LayoutParams cardMargin() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        lp.bottomMargin = dp(6);
        return lp;
    }

    // ------------------------------------------------------------------ كرت مستخدم (.search-user-card)
    private View userCard(final Item it, boolean recent) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(11), dp(14), dp(11));
        row.setMinimumHeight(dp(74));
        row.setBackground(cardBg(false));
        row.setElevation(0f);
        row.setClickable(true);
        BabylonFx.press(row, 0.975f);

        // --- الأفاتار 50
        FrameLayout av = new FrameLayout(act);
        av.setBackground(toneBg(it.id));
        TextView init = new TextView(act);
        init.setText(initial(it.name));
        init.setTextSize(20);
        init.setTypeface(null, android.graphics.Typeface.BOLD);
        init.setTextColor(0xFFFFFFFF);
        init.setGravity(Gravity.CENTER);
        init.setIncludeFontPadding(false);
        av.addView(init, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        ImageView pic = new ImageView(act);
        pic.setScaleType(ImageView.ScaleType.CENTER_CROP);
        pic.setVisibility(View.GONE);
        pic.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setOval(0, 0, v.getWidth(), v.getHeight()); }
        });
        pic.setClipToOutline(true);
        av.addView(pic, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        row.addView(av, new LinearLayout.LayoutParams(dp(50), dp(50)));
        loadPhoto(it, pic);

        // --- الاسم + اليوزر
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cp.setMargins(dp(13), 0, dp(13), 0);
        row.addView(col, cp);

        TextView name = new TextView(act);
        name.setTextSize(15);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        name.setTextColor(p.text);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setTextDirection(View.TEXT_DIRECTION_RTL);
        name.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        name.setText(nameText(it));
        col.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView sub = new TextView(act);
        sub.setTextSize(12.5f);
        sub.setTextColor(p.mute);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        sub.setTextDirection(View.TEXT_DIRECTION_LTR);
        sub.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);   // text-align:right (الجذر RTL)
        sub.setText(it.username.isEmpty() ? "" : "@" + it.username);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(4);
        col.addView(sub, sp);

        // --- دائرة ‹
        TextView arrow = new TextView(act);
        arrow.setText("‹");
        arrow.setTextSize(22);
        arrow.setTextColor(p.gold);
        arrow.setGravity(Gravity.CENTER);
        arrow.setIncludeFontPadding(false);
        arrow.setPadding(0, 0, 0, dp(3));
        GradientDrawable ab = new GradientDrawable();
        ab.setShape(GradientDrawable.OVAL);
        ab.setColor(p.chip);
        arrow.setBackground(ab);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(32), dp(32)));

        if (recent) {
            recentRows.put(it.id, row);
            paintSelected(row, sel.contains(it.id));
        }
        wireCard(row, it, recent);
        return row;
    }

    private void wireCard(final View row, final Item it, final boolean recent) {
        row.setOnClickListener(x -> {
            if (recent && selMode) { toggleSel(it.id); return; }
            if (recent && it.username.isEmpty() && "…".equals(it.name)) return;   // لم تُحمّل بعد
            host.openUser(it.name.isEmpty() ? "مستخدم" : it.name, it.username, it.id);
        });
        if (recent) {
            row.setOnLongClickListener(x -> {
                x.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                if (!selMode) {
                    selMode = true;
                    sel.clear();
                    sel.add(it.id);
                    repaintRecents();
                } else {
                    toggleSel(it.id);
                }
                return true;
            });
        }
    }

    private CharSequence nameText(Item it) {
        String n = it.name.isEmpty() ? "مستخدم" : it.name;
        if (!it.verified) return n;
        SpannableStringBuilder sb = new SpannableStringBuilder(n);
        sb.append(' ');
        sb.setSpan(new SealSpan(dp(14), dp(5)), n.length(), n.length() + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    private GradientDrawable cardBg(boolean selected) {
        return box(selected ? p.chip : p.card, 24, selected ? p.gold : p.line, 1);
    }

    private void paintSelected(View row, boolean selected) {
        row.setBackground(cardBg(selected));
    }

    // ------------------------------------------------------------------ «الأخيرة»
    private SharedPreferences prefs() { return act.getSharedPreferences("babylon_search", Context.MODE_PRIVATE); }

    private String recentKey() { return "recent_" + host.myUid(); }

    private List<String> getRecents() {
        List<String> out = new ArrayList<>();
        String s = prefs().getString(recentKey(), "");
        if (s == null || s.isEmpty()) return out;
        for (String id : s.split(",")) if (!id.isEmpty() && !out.contains(id)) out.add(id);
        return out;
    }

    private void setRecents(List<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size() && i < MAX_RECENT; i++) {
            if (i > 0) sb.append(',');
            sb.append(ids.get(i));
        }
        prefs().edit().putString(recentKey(), sb.toString()).apply();
    }

    /** recordRecentVisit(uid): تُستدعى عند بدء محادثة من ملف شخص. */
    void recordVisit(String uid) {
        if (uid == null || uid.isEmpty() || uid.equals(host.myUid())) return;
        List<String> ids = getRecents();
        ids.remove(uid);
        ids.add(0, uid);
        setRecents(ids);
    }

    private void renderIdle() {
        recentRows.clear();
        recentLabel = null;
        recentActions = null;
        final List<String> ids = getRecents();
        if (ids.isEmpty()) {
            results.addView(stateCard(true, "ابحث عن أصدقاء", "اكتب اسم المستخدم"));
            return;
        }

        // .recent-head : padding 0 8 10 ، والمسافة بينه وبين القائمة gap 10
        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(8), 0, dp(8), dp(10));
        recentLabel = new TextView(act);
        recentLabel.setTextSize(12.5f);
        recentLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        recentLabel.setTextColor(p.mute);
        recentLabel.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        head.addView(recentLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        recentActions = new LinearLayout(act);
        recentActions.setOrientation(LinearLayout.HORIZONTAL);
        recentActions.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(recentActions, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        results.addView(head, gap(0));

        LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = gap(1);
        results.addView(list, lp);

        updateRecentHead();

        final int tok = token;
        for (int i = 0; i < ids.size(); i++) {
            final String id = ids.get(i);
            final Item it = recentCache.containsKey(id) ? recentCache.get(id) : placeholder(id);
            View card = userCard(it, true);
            list.addView(card, gap(i));
            if (!recentCache.containsKey(id) && fetching.add(id)) {
                FirebaseFirestore.getInstance().collection("users").document(id).get()
                        .addOnSuccessListener(d -> {
                            fetching.remove(id);
                            if (!d.exists()) return;
                            recentCache.put(id, toItem(d));
                            // أعد بناء القائمة لتظهر البيانات (الباقي صار في الكاش فلا إعادة جلب)
                            if (tok == token && state == St.IDLE) renderCurrent();
                        })
                        .addOnFailureListener(e -> fetching.remove(id));
            }
        }
    }

    private Item placeholder(String id) {
        Item it = new Item();
        it.id = id;
        it.name = "…";
        return it;
    }

    private void updateRecentHead() {
        if (recentLabel == null || recentActions == null) return;
        recentActions.removeAllViews();
        if (selMode) {
            recentLabel.setText(sel.size() + " محدد");
            TextView del = headBtn("حذف", true);
            del.setOnClickListener(x -> {
                List<String> ids = getRecents();
                ids.removeAll(sel);
                setRecents(ids);
                sel.clear();
                selMode = false;
                if (state == St.IDLE) renderCurrent();
            });
            TextView cancel = headBtn("إلغاء", false);
            cancel.setOnClickListener(x -> {
                sel.clear();
                selMode = false;
                repaintRecents();
            });
            recentActions.addView(del);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.setMarginStart(dp(8));
            recentActions.addView(cancel, cp);
        } else {
            recentLabel.setText("الأخيرة");
        }
    }

    private TextView headBtn(String label, boolean danger) {
        TextView b = new TextView(act);
        b.setText(label);
        b.setTextSize(12);
        b.setTypeface(null, android.graphics.Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), dp(6), dp(14), dp(6));
        b.setTextColor(danger ? RED : p.gold);
        b.setBackground(box(danger ? 0x26F87171 : p.chip, 40, 0, 0));
        b.setClickable(true);
        return b;
    }

    private void toggleSel(String id) {
        if (sel.contains(id)) sel.remove(id); else sel.add(id);
        if (sel.isEmpty()) selMode = false;
        repaintRecents();
    }

    private void repaintRecents() {
        for (Map.Entry<String, View> e : recentRows.entrySet()) paintSelected(e.getValue(), selMode && sel.contains(e.getKey()));
        updateRecentHead();
    }

    // ------------------------------------------------------------------ الأفاتار
    /** toneOf(u) في الـ HTML: عائلة لون ثابتة لكل حساب من معرّفه (FNV + خلط). */
    private static boolean toneBlue(String id) {
        if (true) return false;   // الجميع ذهبي متدرج (لا أزرق)
        if (id == null || id.isEmpty()) return false;
        int h = (int) 2166136261L;
        for (int i = 0; i < id.length(); i++) h = (h ^ id.charAt(i)) * 16777619;
        h ^= h >>> 15;
        h *= (int) 2246822507L;
        h ^= h >>> 13;
        return ((h >>> 7) & 1) == 1;
    }

    private GradientDrawable toneBg(String id) {
        boolean blue = toneBlue(id);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                blue ? new int[]{0xFF59CDF8, 0xFF1F63D8} : new int[]{0xFFF2D268, 0xFFB8861B});
        g.setShape(GradientDrawable.OVAL);
        return g;
    }

    private static String initial(String name) {
        if (name == null || name.isEmpty()) return "?";
        return name.substring(0, 1).toUpperCase();
    }

    private void loadPhoto(final Item it, final ImageView pic) {
        if (it.hideAvatar || it.photo.isEmpty()) return;
        final String ph = it.photo;
        final String key = ph.length() + ":" + ph.hashCode();
        Bitmap cached = PHOTOS.get(key);
        if (cached != null) { pic.setImageBitmap(cached); pic.setVisibility(View.VISIBLE); return; }
        new Thread(() -> {
            final Bitmap b = decode(ph);
            if (b == null) return;
            PHOTOS.put(key, b);
            ui.post(() -> { pic.setImageBitmap(b); pic.setVisibility(View.VISIBLE); });
        }).start();
    }

    private static Bitmap decode(String ph) {
        try {
            byte[] data;
            if (ph.startsWith("http")) {
                URLConnection c = new URL(ph).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                InputStream is = c.getInputStream();
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
                is.close();
                data = bo.toByteArray();
            } else {
                String b64 = ph.contains(",") ? ph.substring(ph.indexOf(',') + 1) : ph;
                data = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int s = 1;
            while (o.outWidth / (s * 2) >= 160 && o.outHeight / (s * 2) >= 160) s *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = s;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ أدوات
    private void setHint(String s, int kind) {
        hintText = s;
        hintKind = kind;
        if (hint == null) return;
        hint.setText(s);
        hint.setTextColor(kind == HINT_LOADING ? p.soft : kind == HINT_ERROR ? RED : p.mute);
    }

    private void hideKeyboard() {
        try {
            InputMethodManager m = (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (m != null) m.hideSoftInputFromWindow(input.getWindowToken(), 0);
        } catch (Throwable ignored) { }
    }

    private int dp(float v) { return Math.round(v * density); }

    private GradientDrawable box(int fill, float rDp, int stroke, float strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(rDp));
        if (stroke != 0 && strokeDp > 0) g.setStroke(Math.max(1, Math.round(strokeDp * density)), stroke);
        return g;
    }

    /** border:1px dashed */
    private GradientDrawable dashed(int fill, float rDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(rDp));
        g.setStroke(Math.max(1, dp(1)), stroke, dp(3), dp(3));
        return g;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String firstNonEmpty(String... v) {
        for (String s : v) if (s != null && !s.trim().isEmpty()) return s.trim();
        return "";
    }

    // ------------------------------------------------------------------ شارة التوثيق (.verified-badge النهائية في الـ HTML)
    /** ختم ذهبي مسنّن: تدرّج + لمعة + حلقة رفيعة + علامة ✓ بيضاء (SVG بإحداثيات 24×24). */
    static final class Seal extends Drawable {
        private static final float[] PTS = {
            23.35f, 12.00f, 23.25f, 12.59f, 22.98f, 13.15f, 22.56f, 13.67f, 22.08f, 14.14f, 21.59f, 14.57f, 21.16f,
            14.98f, 20.84f, 15.39f, 20.65f, 15.85f, 20.58f, 16.37f, 20.60f, 16.96f, 20.64f, 17.61f, 20.65f, 18.29f,
            20.58f, 18.94f, 20.37f, 19.54f, 20.03f, 20.03f, 19.54f, 20.37f, 18.94f, 20.58f, 18.29f, 20.65f, 17.61f,
            20.64f, 16.96f, 20.60f, 16.37f, 20.58f, 15.85f, 20.65f, 15.39f, 20.84f, 14.98f, 21.16f, 14.57f, 21.59f,
            14.14f, 22.08f, 13.67f, 22.56f, 13.15f, 22.98f, 12.59f, 23.25f, 12.00f, 23.35f, 11.41f, 23.25f, 10.85f,
            22.98f, 10.33f, 22.56f, 9.86f, 22.08f, 9.43f, 21.59f, 9.02f, 21.16f, 8.61f, 20.84f, 8.15f, 20.65f, 7.63f,
            20.58f, 7.04f, 20.60f, 6.39f, 20.64f, 5.71f, 20.65f, 5.06f, 20.58f, 4.46f, 20.37f, 3.97f, 20.03f, 3.63f,
            19.54f, 3.42f, 18.94f, 3.35f, 18.29f, 3.36f, 17.61f, 3.40f, 16.96f, 3.42f, 16.37f, 3.35f, 15.85f, 3.16f,
            15.39f, 2.84f, 14.98f, 2.41f, 14.57f, 1.92f, 14.14f, 1.44f, 13.67f, 1.02f, 13.15f, 0.75f, 12.59f, 0.65f,
            12.00f, 0.75f, 11.41f, 1.02f, 10.85f, 1.44f, 10.33f, 1.92f, 9.86f, 2.41f, 9.43f, 2.84f, 9.02f, 3.16f, 8.61f,
            3.35f, 8.15f, 3.42f, 7.63f, 3.40f, 7.04f, 3.36f, 6.39f, 3.35f, 5.71f, 3.42f, 5.06f, 3.63f, 4.46f, 3.97f,
            3.97f, 4.46f, 3.63f, 5.06f, 3.42f, 5.71f, 3.35f, 6.39f, 3.36f, 7.04f, 3.40f, 7.63f, 3.42f, 8.15f, 3.35f,
            8.61f, 3.16f, 9.02f, 2.84f, 9.43f, 2.41f, 9.86f, 1.92f, 10.33f, 1.44f, 10.85f, 1.02f, 11.41f, 0.75f, 12.00f,
            0.65f, 12.59f, 0.75f, 13.15f, 1.02f, 13.67f, 1.44f, 14.14f, 1.92f, 14.57f, 2.41f, 14.98f, 2.84f, 15.39f,
            3.16f, 15.85f, 3.35f, 16.37f, 3.42f, 16.96f, 3.40f, 17.61f, 3.36f, 18.29f, 3.35f, 18.94f, 3.42f, 19.54f,
            3.63f, 20.03f, 3.97f, 20.37f, 4.46f, 20.58f, 5.06f, 20.65f, 5.71f, 20.64f, 6.39f, 20.60f, 7.04f, 20.58f,
            7.63f, 20.65f, 8.15f, 20.84f, 8.61f, 21.16f, 9.02f, 21.59f, 9.43f, 22.08f, 9.86f, 22.56f, 10.33f, 22.98f,
            10.85f, 23.25f, 11.41f
        };
        private final Path body = new Path(), check = new Path();
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), hi = new Paint(Paint.ANTI_ALIAS_FLAG),
                ring = new Paint(Paint.ANTI_ALIAS_FLAG), chk = new Paint(Paint.ANTI_ALIAS_FLAG);

        Seal() {
            body.moveTo(PTS[0], PTS[1]);
            for (int i = 2; i + 1 < PTS.length; i += 2) body.lineTo(PTS[i], PTS[i + 1]);
            body.close();
            check.moveTo(7.4f, 12.4f);
            check.rLineTo(3.3f, 3.3f);
            check.rLineTo(6f, -6.8f);
            fill.setShader(new LinearGradient(0.65f, 0.65f, 23.35f, 23.35f,
                    new int[]{0xFFF4D870, 0xFFDFB034, 0xFFBB880F, 0xFF805C08},
                    new float[]{0f, 0.3f, 0.7f, 1f}, Shader.TileMode.CLAMP));
            hi.setShader(new RadialGradient(7.46f, 6.33f, 13.6f, 0xA6FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(0.5f);
            ring.setColor(0x8CF6DC86);                          // #f6dc86 بشفافية .55
            chk.setStyle(Paint.Style.STROKE);
            chk.setStrokeWidth(2.5f);
            chk.setStrokeCap(Paint.Cap.ROUND);
            chk.setStrokeJoin(Paint.Join.ROUND);
            chk.setColor(0xFFFFFFFF);
        }

        @Override public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            if (b.width() <= 0) return;
            c.save();
            c.translate(b.left, b.top);
            float s = b.width() / 24f;
            c.scale(s, s);
            c.drawPath(body, fill);
            c.drawPath(body, hi);
            c.drawPath(body, ring);
            c.drawPath(check, chk);
            c.restore();
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** يرسم الختم متمركزاً رأسياً مع النص ، ويترك فراغاً بعده (gap:5px بين الاسم والشارة). */
    private static final class SealSpan extends ReplacementSpan {
        private final Seal seal = new Seal();
        private final int size, gap;

        SealSpan(int size, int gap) { this.size = size; this.gap = gap; }

        @Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            return size + gap;
        }

        @Override public void draw(Canvas canvas, CharSequence text, int start, int end, float x,
                                   int top, int y, int bottom, Paint paint) {
            Paint.FontMetrics m = paint.getFontMetrics();
            float cy = y + (m.ascent + m.descent) / 2f;
            seal.setBounds(0, 0, size, size);
            canvas.save();
            canvas.translate(x, cy - size / 2f);
            seal.draw(canvas);
            canvas.restore();
        }
    }
}
