package com.babylon.chat.iq;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * حركات واجهة Babylon Chat — منقولة من ملف HTML (bb-entrance-anim, babylon-tab-slide,
 * theme-switch-animation-js, :active scale ...).
 *
 * كل المنحنيات والمدد والتأخيرات مطابقة لقيم الـ CSS الأصلية:
 *   bbRise      : 380ms  translateY 12px -> 0     delay = i*32 + 70
 *   bbPop       : 260ms  scale .85 -> 1           delay = i*20 + 70
 *   bbViewIn    : 320ms  translateY 10px, scale .985
 *   bbChatGrow  : 420ms  scale .88 + translateY 18px (pivot 50% 60%)
 *   bbBarDown   : 340ms  translateY -14px
 *   bbBarUp     : 340ms  translateY 16px
 *   bbMsgIn     : 260ms  translateY 10px, scale .94
 *   tab slide   : 140ms  cubic-bezier(.25,.9,.3,1)
 *   theme reveal: 600ms  cubic-bezier(.4,0,.2,1) دائرة تتسع من موضع اللمس
 */
public final class BabylonFx {

    private BabylonFx() { }

    // -------------------------------------------------------------- منحنيات
    private static Interpolator easeOut() { return new PathInterpolator(0.22f, 1f, 0.36f, 1f); }
    private static Interpolator tabEase() { return new PathInterpolator(0.25f, 0.9f, 0.3f, 1f); }
    private static Interpolator themeEase() { return new PathInterpolator(0.4f, 0f, 0.2f, 1f); }

    private static float dp(View v, float d) {
        return d * v.getResources().getDisplayMetrics().density;
    }

    /** يمنع التشغيل المتكرر أثناء حركة سابقة على نفس العنصر. */
    public static void reset(View v) {
        v.animate().cancel();
        v.setAlpha(1f);
        v.setTranslationX(0f);
        v.setTranslationY(0f);
        v.setScaleX(1f);
        v.setScaleY(1f);
    }

    // --------------------------------------------------------- حركات مفردة
    /** bbRise: صفوف/بطاقات/عناوين أقسام. */
    public static void rise(final View v, int index) {
        if (v == null) return;
        reset(v);
        v.setAlpha(0f);
        v.setTranslationY(dp(v, 12));
        v.animate().alpha(1f).translationY(0f)
                .setDuration(150).setStartDelay(index * 14L)
                .setInterpolator(easeOut())
                .withEndAction(() -> reset(v)).start();
    }

    /** bbPop: الأزرار. */
    public static void pop(final View v, int index) {
        if (v == null) return;
        reset(v);
        v.setAlpha(0f);
        v.setScaleX(0.85f);
        v.setScaleY(0.85f);
        v.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(130).setStartDelay(index * 10L)
                .setInterpolator(easeOut())
                .withEndAction(() -> reset(v)).start();
    }

    /** bbViewIn: دخول الشاشات الثانوية. */
    public static void viewIn(final View v) {
        if (v == null) return;
        reset(v);
        v.setAlpha(0f);
        v.setTranslationY(dp(v, 10));
        v.setScaleX(0.985f);
        v.setScaleY(0.985f);
        v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setDuration(320).setInterpolator(easeOut())
                .withEndAction(() -> reset(v)).start();
    }

    /** دخول صفحة رئيسية (محادثات/إعدادات/ملف شخصي): نفس bbViewIn لكن بتأخير يبدأ بعد انتهاء خروج الصفحة السابقة فلا تتداخل. */
    public static void pageIn(final View v, long delay) { pageIn(v, delay, 140L); }

    public static void pageIn(final View v, long delay, long durMs) {
        if (v == null) return;
        reset(v);
        v.setAlpha(0f);
        v.setTranslationY(dp(v, 10));
        v.setScaleX(0.985f);
        v.setScaleY(0.985f);
        v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setStartDelay(delay).setDuration(durMs).setInterpolator(easeOut())
                .withEndAction(() -> reset(v)).start();
    }

    /** دخول بتلاشٍ فقط (بلا إزاحة ولا تصغير): للصفحات التي تمتد حتى حافة الشاشة (الملف الشخصي) كي لا تنكشف الخلفية كخط أبيض/أسود. */
    public static void pageFade(final View v, long delay, long durMs) {
        if (v == null) return;
        reset(v);
        v.setAlpha(0f);
        v.animate().alpha(1f)
                .setStartDelay(delay).setDuration(durMs).setInterpolator(easeOut())
                .withEndAction(() -> reset(v)).start();
    }

    /** bbBarDown: الهيدر. */
    public static void barDown(final View v, long delay) {
        if (v == null) return;
        reset(v);
        v.setAlpha(0f);
        v.setTranslationY(-dp(v, 14));
        v.animate().alpha(1f).translationY(0f)
                .setDuration(150).setStartDelay(delay).setInterpolator(easeOut())
                .withEndAction(() -> reset(v)).start();
    }

    /** bbBarUp: الشريط السفلي والمدخل. */
    public static void barUp(final View v, long delay) {
        if (v == null) return;
        reset(v);
        v.setAlpha(0f);
        v.setTranslationY(dp(v, 16));
        v.animate().alpha(1f).translationY(0f)
                .setDuration(150).setStartDelay(delay).setInterpolator(easeOut())
                .withEndAction(() -> reset(v)).start();
    }

    /** bbFabIn: زر مجموعة جديدة. */
    public static void fabIn(View v) {
        if (v == null) return;
        reset(v);
        v.setAlpha(0f);
        v.setScaleX(0.85f);
        v.setScaleY(0.85f);
        v.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(140).setStartDelay(60).setInterpolator(easeOut()).start();
    }

    /** bbMsgIn: رسالة جديدة داخل المحادثة. */
    public static void msgIn(final View v) {
        if (v == null) return;
        reset(v);
        v.setAlpha(0f);
        v.setTranslationY(dp(v, 10));
        v.setScaleX(0.94f);
        v.setScaleY(0.94f);
        v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setDuration(20).setInterpolator(easeOut())   // ظهور الرسالة 20ms
                .withEndAction(() -> reset(v)).start();
    }

    /** اتجاه طيّ صفحة المحادثة: المفصلة على الحافة اليمنى (كتاب عربي). غيّرها إلى false لتصير على اليسرى. */
    private static final boolean FOLD_HINGE_RIGHT = true;
    /** زاوية بداية الطي (درجة) ومدته (ms). إشارتها تحدّد اتجاه الدوران؛ اعكسها إن ظهر الدوران معكوساً. */
    private static final float FOLD_START_DEG = 78f;
    private static final long FOLD_MS = 20;   // مدة حركة دخول المحادثة (أسرع ما يمكن مع بقاء الحركة ظاهرة)
    private static final long MSG_WAIT_MS = 0;   // الرسائل لا تنتظر الحركة: تُرسم فوراً أثناءها
    private static final long LEAVE_MS = 20;   // تلاشي الخروج سريع جداً
    private static final long POP_MS = 20;     // انبثاق الدخول سريع جداً
    /** وقت (uptime) انتهاء حركة الطيّ؛ تنتظره المحادثة قبل رسم الرسائل كي لا تتداخل معها ولا تُقطّعها. */
    private static volatile long foldEndUptime = 0L;

    /** يُستدعى فور بدء فتح المحادثة (قبل أول إطار) ليعرف تحميل الرسائل أن الحركة ستبدأ. */
    public static void chatFoldBegin() { foldEndUptime = SystemClock.uptimeMillis() + MSG_WAIT_MS; }

    /** ms المتبقية حتى تنتهي حركة الطيّ (0 إن لم تكن جارية). */
    public static long chatFoldRemaining() { return Math.max(0L, foldEndUptime - SystemClock.uptimeMillis()); }

    /** فتح غرفة المحادثة بانبثاق وتلاشٍ سريعين جداً. */
    public static void chatGrow(final View chat, View header, View composer) {
        if (chat == null) return;
        reset(chat);
        chat.setAlpha(1f);
        // أي تلاشٍ سابق للأبناء يُلغى: المحتوى يظهر كاملاً داخل الصفحة وهي تنفتح
        if (chat instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) chat;
            for (int i = 0; i < g.getChildCount(); i++) { g.getChildAt(i).animate().cancel(); g.getChildAt(i).setAlpha(1f); }
        }
        View ma0 = chat.findViewById(R.id.messagesArea);
        if (ma0 != null) ma0.animate().cancel();
        final int w = chat.getWidth(), h = chat.getHeight();
        if (w <= 0 || h <= 0) return;
        foldEndUptime = SystemClock.uptimeMillis() + MSG_WAIT_MS;
        // انبثاق سريع جداً: تلاشٍ من الصفر + تكبير بسيط من الوسط
        chat.setPivotX(w / 2f);
        chat.setPivotY(h / 2f);
        chat.setAlpha(0f);
        chat.setScaleX(0.94f);
        chat.setScaleY(0.94f);
        chat.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(POP_MS).setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                .withEndAction(() -> reset(chat)).start();
    }

    /** مدة تلاشي الخروج من المحادثة (ms): واحدة لكل أجزاء المحادثة (الرسائل، الهيدر، شريط الكتابة، الشريط المثبّت، الخلفية) فتتلاشى معاً وبنفس اللحظة. كانت 480ms على مراحل؛ الآن النصف. */
    private static final long LEAVE_ALL_MS = 240;

    /**
     * الخروج من غرفة المحادثة بتلاشٍ شفاف واحد متزامن (240ms):
     *   كل دواخل المحادثة تتلاشى كوحدة واحدة بنفس المدة والمنحنى ونفس لحظة البداية (بلا مراحل متتابعة)،
     *   فلا يحصل تشابك أو خربطة بين الأجزاء، وتنكشف الصفحة الرئيسية (page) من خلفها.
     *   عند اكتمال التلاشي تُنفَّذ swap (إخفاء المحادثة).
     */
    public static void chatLeave(final View chat, final View page, final Runnable swap) {
        if (page != null) { page.animate().cancel(); page.setAlpha(1f); }
        if (chat == null || chat.getVisibility() != View.VISIBLE || chat.getWidth() <= 0 || chat.getHeight() <= 0) {
            if (swap != null) swap.run();
            return;
        }
        reset(chat);
        if (page != null) {   // ظهور الصفحة الرئيسية خلف المحادثة بنفس مدة التلاشي وبنفس اللحظة
            page.setTranslationY(dp(page, 10));
            page.setScaleX(0.985f); page.setScaleY(0.985f);
            page.animate().translationY(0f).scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(LEAVE_ALL_MS)
                    .setInterpolator(easeOut()).withEndAction(() -> { reset(page); page.animate().setStartDelay(0); }).start();
        }
        // أي تلاشٍ فردي سابق للأبناء يُلغى ويُعاد كاملاً: التلاشي كله يأتي من الجذر وحده كي لا يتضاعف أو يتفاوت بين الأجزاء
        final java.util.ArrayList<View> kids = new java.util.ArrayList<>();
        final View msgs = chat.findViewById(R.id.messagesArea);
        if (chat instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) chat;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                c.animate().cancel(); c.animate().setStartDelay(0); c.setAlpha(1f);
                kids.add(c);
            }
        }
        if (msgs != null) { msgs.animate().cancel(); msgs.setAlpha(1f); }
        final android.view.animation.Interpolator lin = new android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f);
        chat.animate().alpha(0f).setStartDelay(0).setDuration(LEAVE_ALL_MS).setInterpolator(lin)
                .withLayer()   // الجذر بأكمله (كل أبنائه) يُرسم كطبقة واحدة فيتلاشى بتساوٍ تام
                .withEndAction(() -> {   // لا يُنفَّذ إن أُلغيت الحركة (دخول محادثة جديدة)
                    if (swap != null) swap.run();
                    reset(chat); chat.animate().setStartDelay(0);
                    for (View c : kids) { c.animate().cancel(); c.animate().setStartDelay(0); c.setAlpha(1f); }
                    if (msgs != null) { msgs.animate().cancel(); msgs.setAlpha(1f); }
                }).start();
    }

    /** ظهور خفيف مميز لصفوف المحادثات عند دخول قسم المحادثات: كل صف ينزلق من اليمين بتلاشٍ وارتداد ناعم، متتابعاً. */
    /** ظهور سريع جداً لصفوف المحادثات عند الانتقال لصفحة المحادثات: تُخفى فوراً (قبل أول إطار) ثم تظهر بلا تأخير. */
    public static void chatRowsInFast(final ViewGroup list) {
        if (list == null) return;
        final int n0 = Math.min(list.getChildCount(), 9);
        for (int i = 0; i < n0; i++) { View r = list.getChildAt(i); if (r != null) { r.animate().cancel(); r.setAlpha(0f); } }   // بلا إطار يظهر فيه الصف قبل الحركة
        list.post(() -> {
            int n = Math.min(list.getChildCount(), 9);
            for (int i = 0; i < n; i++) {
                final View r = list.getChildAt(i);
                if (r == null || r.getVisibility() != View.VISIBLE) continue;
                r.animate().cancel();
                r.setAlpha(0f);
                r.setTranslationX(dp(r, 10));
                r.animate().alpha(1f).translationX(0f).setStartDelay(i * 14L).setDuration(130)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f))
                        .withEndAction(() -> reset(r)).start();
            }
        });
    }

    public static void chatRowsIn(final ViewGroup list, final long baseDelay) { chatRowsIn(list, baseDelay, 16L); }

    /** stepMs = الفاصل بين صف وآخر؛ 0 = كل الصفوف تبدأ وتنتهي معاً (يتزامن ظهورها مع الهيدر وحقل القصص). */
    public static void chatRowsIn(final ViewGroup list, final long baseDelay, final long stepMs) { chatRowsIn(list, baseDelay, stepMs, 140L); }

    public static void chatRowsIn(final ViewGroup list, final long baseDelay, final long stepMs, final long durMs) {
        if (list == null) return;
        final int n0 = Math.min(list.getChildCount(), 9);
        for (int i = 0; i < n0; i++) { View r = list.getChildAt(i); if (r != null) { r.animate().cancel(); r.setAlpha(0f); } }   // بلا إطار تظهر فيه الصفوف قبل الحركة
        list.post(() -> {
            int n = Math.min(list.getChildCount(), 9);
            Interpolator soft = new android.view.animation.OvershootInterpolator(0.7f);
            for (int i = 0; i < n; i++) {
                final View r = list.getChildAt(i);
                if (r == null || r.getVisibility() != View.VISIBLE) continue;
                r.animate().cancel();
                r.setAlpha(0f);
                r.setTranslationX(dp(r, 14));
                r.setScaleX(0.985f); r.setScaleY(0.985f);
                r.animate().alpha(1f).translationX(0f).scaleX(1f).scaleY(1f)
                        .setStartDelay(baseDelay + i * stepMs).setDuration(durMs).setInterpolator(soft)
                        .withEndAction(() -> reset(r)).start();
            }
        });
    }

    /**
     * نفس chatGrow لكن داخل OnPreDraw: بعد القياس وقبل رسم أول إطار.
     * (استعمال post كان يرسم المحادثة كاملة إطاراً واحداً ثم يخفيها ويبدأ الحركة: وميض عند الدخول.)
     */
    public static void chatGrowNow(final View chat, final View header, final View composer) {
        if (chat == null) return;
        final android.view.ViewTreeObserver vto = chat.getViewTreeObserver();
        if (!vto.isAlive()) { chat.post(() -> chatGrow(chat, header, composer)); return; }
        vto.addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                android.view.ViewTreeObserver o = chat.getViewTreeObserver();
                if (o.isAlive()) o.removeOnPreDrawListener(this);
                chatGrow(chat, header, composer);
                return true;
            }
        });
        chat.invalidate();
    }

    // ------------------------------------------------------------ stagger
    private static final int MAX_ITEMS = 16;

    private static boolean isRise(View v) {
        Object t = v.getTag();
        if (!(t instanceof String)) return false;
        String s = (String) t;
        for (String k : s.split(" ")) {
            if (k.equals("row") || k.equals("card") || k.equals("hero")) return true;
        }
        return false;
    }

    private static boolean isPop(View v) {
        if (!v.isClickable()) return false;
        if (v instanceof ViewGroup && ((ViewGroup) v).getChildCount() > 3) return false;
        return v.getWidth() <= dp(v, 150) && v.getHeight() <= dp(v, 80);
    }

    private static void collect(View v, java.util.List<View> out) {
        if (out.size() >= MAX_ITEMS || v.getVisibility() != View.VISIBLE) return;
        if (v.getId() == R.id.topStories) return;   // القصص بلا حركة دخول منفصلة (storyItemEnter معطّلة في الـ HTML)
        if (v.getWidth() <= 0 || v.getHeight() <= 0) return;
        if (isRise(v) || isPop(v)) { out.add(v); return; }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount() && out.size() < MAX_ITEMS; i++) collect(g.getChildAt(i), out);
        }
    }

    /**
     * دخول متدرّج لعناصر شاشة.
     * يُنفَّذ داخل OnPreDraw: بعد القياس وقبل رسم أول إطار، فتُخفى العناصر قبل أن يراها المستخدم.
     * (النسخة السابقة كانت تستعمل post فيظهر إطار كامل ثم تختفي الأزرار وتعود: وميض.)
     */
    public static void stagger(final View root) {
        if (root == null) return;
        final Runnable run = () -> {
            java.util.ArrayList<View> list = new java.util.ArrayList<>();
            collect(root, list);
            int i = 0;
            for (View v : list) {
                if (isRise(v)) rise(v, Math.min(i, 12)); else pop(v, Math.min(i, 12));
                i++;
            }
        };
        final android.view.ViewTreeObserver vto = root.getViewTreeObserver();
        if (!vto.isAlive()) { root.post(run); return; }
        vto.addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                android.view.ViewTreeObserver o = root.getViewTreeObserver();
                if (o.isAlive()) o.removeOnPreDrawListener(this);
                run.run();
                return true;
            }
        });
        root.invalidate();
    }

    /** دخول جانبي لصفحة قسم رئيسي: تنزلق من الجهة المعاكسة لخروج الصفحة السابقة مع تلاشٍ خفيف. */
    public static void tabIn(final View v, float fromX, long durMs) {
        if (v == null) return;
        reset(v);
        v.setAlpha(0f);
        v.setTranslationX(fromX);
        v.animate().alpha(1f).translationX(0f)
                .setStartDelay(0).setDuration(durMs).setInterpolator(tabEase()).withLayer()
                .withEndAction(() -> reset(v)).start();
    }

    /** ظهور سريع متتابع لعناصر الصفحة من الأعلى للأسفل (كل عنصر ينزل قليلاً من فوق مكانه). */
    public static void staggerDown(final View root, final long baseDelay) { staggerDown(root, baseDelay, 150L, 16L); }

    /** نفس المتتابع بمدة وفاصل مخصّصين (تفاصيل تظهر بسرعة كبيرة بعد الانتقال). */
    public static void staggerDown(final View root, final long baseDelay, final long durMs, final long stepMs) {
        if (root == null) return;
        final Runnable run = () -> {
            java.util.ArrayList<View> list = new java.util.ArrayList<>();
            collect(root, list);
            java.util.Collections.sort(list, (a, b) -> {
                int[] la = new int[2], lb = new int[2];
                a.getLocationOnScreen(la); b.getLocationOnScreen(lb);
                return la[1] != lb[1] ? Integer.compare(la[1], lb[1]) : Integer.compare(la[0], lb[0]);
            });
            int i = 0;
            for (final View v : list) {
                reset(v);
                v.setAlpha(0f);
                v.setTranslationY(-dp(v, 10));
                v.animate().alpha(1f).translationY(0f)
                        .setStartDelay(baseDelay + Math.min(i, 12) * stepMs).setDuration(durMs)
                        .setInterpolator(easeOut())
                        .withEndAction(() -> reset(v)).start();
                i++;
            }
        };
        final android.view.ViewTreeObserver vto = root.getViewTreeObserver();
        if (!vto.isAlive()) { root.post(run); return; }
        vto.addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                android.view.ViewTreeObserver o = root.getViewTreeObserver();
                if (o.isAlive()) o.removeOnPreDrawListener(this);
                run.run();
                return true;
            }
        });
        root.invalidate();
    }

    // ------------------------------------------------------- انتقال التبويبات
    /**
     * انتقال جانبي سريع بين المحادثات / الإعدادات / الملف الشخصي (140ms).
     * @param dir  -1 : الصفحة الجديدة تدخل من اليسار، +1 : من اليمين.
     */
    private static int tabGen = 0;
    private static View sFrom, sTo;
    private static Drawable sFromTmp, sToTmp;

    private static void restoreTabBgs() {
        if (sFrom != null && sFromTmp != null && sFrom.getBackground() == sFromTmp) sFrom.setBackground(null);
        if (sTo != null && sToTmp != null && sTo.getBackground() == sToTmp) sTo.setBackground(null);
        sFrom = sTo = null; sFromTmp = sToTmp = null;
    }

    /** يُبطل أي انتقال تبويبات جارٍ (تنظيفه ورد النداء الخاص به) ويُرجع خلفيات الصفحتين المؤقتة. */
    public static void cancelTabSlide() { tabGen++; restoreTabBgs(); }

    public static int tabGenNow() { return tabGen; }

    public static void tabSlide(final View from, final View to, int dir, int bgColor, final Runnable done) {
        if (from == null || to == null || to.getWidth() <= 0) {
            if (done != null) done.run();
            return;
        }
        final float w = to.getWidth();
        cancelTabSlide();   // أي انتقال سابق لم ينتهِ يُبطَل (لا يُنفَّذ تنظيفه المتأخر فيُظهر صفحة قديمة فوق الحالية)
        final int g = tabGen;
        if (from.getBackground() == null) { sFromTmp = new ColorDrawable(bgColor); from.setBackground(sFromTmp); sFrom = from; }
        if (to.getBackground() == null) { sToTmp = new ColorDrawable(bgColor); to.setBackground(sToTmp); sTo = to; }
        from.setVisibility(View.VISIBLE);
        to.setVisibility(View.VISIBLE);
        from.setTranslationX(0f);
        to.setTranslationX(dir * w);
        final Interpolator ip = tabEase();
        final Runnable cleanup = new Runnable() {
            boolean ran = false;
            @Override public void run() {
                if (ran) return; ran = true;
                if (g != tabGen) return;   // حلّ مكانه انتقال أحدث
                from.animate().cancel(); to.animate().cancel();
                from.setTranslationX(0f); to.setTranslationX(0f);
                from.setVisibility(View.GONE);
                restoreTabBgs();
                if (done != null) done.run();
            }
        };
        from.animate().translationX(-dir * w).setDuration(140).setInterpolator(ip).start();
        to.animate().translationX(0f).setDuration(140).setInterpolator(ip)
                .withEndAction(cleanup).start();
        to.postDelayed(cleanup, 350);
    }

    // ----------------------------------------------- كشف الثيم (دائرة تتسع)
    private static final class RevealView extends View {
        private final Bitmap old;
        private final Path path = new Path();
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint barPaint = new Paint();
        private final float cx, cy;
        private final int offX, offY;
        private float radius = 0f;
        // ألوان الشريطين القديمين (0 = شفاف/لا شيء) وارتفاعاهما، لترسمهما الدائرة أيضاً
        private int oldTop = 0, oldBottom = 0, topH = 0, botH = 0;

        RevealView(Activity a, Bitmap old, float cx, float cy, int offX, int offY) {
            super(a);
            this.old = old; this.cx = cx; this.cy = cy; this.offX = offX; this.offY = offY;
            setClickable(true); // يمنع اللمس أثناء الحركة
        }

        void setBars(int oldTop, int oldBottom, int topH, int botH) {
            this.oldTop = oldTop; this.oldBottom = oldBottom; this.topH = topH; this.botH = botH;
        }

        void setRadius(float r) { radius = r; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            if (old == null || old.isRecycled()) return;
            path.reset();
            path.setFillType(Path.FillType.EVEN_ODD);
            path.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
            path.addCircle(cx, cy, radius, Path.Direction.CW);
            c.save();
            c.clipPath(path);
            c.drawBitmap(old, offX, offY, paint);
            if (Color.alpha(oldTop) != 0 && topH > 0) { barPaint.setColor(oldTop); c.drawRect(0, 0, getWidth(), topH, barPaint); }
            if (Color.alpha(oldBottom) != 0 && botH > 0) { barPaint.setColor(oldBottom); c.drawRect(0, getHeight() - botH, getWidth(), getHeight(), barPaint); }
            c.restore();
        }
    }

    /** يرسم لوني الشريطين الجديدين (الحالة والإيماءات) فوق الواجهة وتحت دائرة الكشف. */
    private static final class BarsView extends View {
        private final Paint p = new Paint();
        private int top = 0, bottom = 0, topH = 0, botH = 0;
        BarsView(Activity a, int top, int bottom, int topH, int botH) {
            super(a);
            this.top = top; this.bottom = bottom; this.topH = topH; this.botH = botH;
        }
        @Override protected void onDraw(Canvas c) {
            if (Color.alpha(top) != 0 && topH > 0) { p.setColor(top); c.drawRect(0, 0, getWidth(), topH, p); }
            if (Color.alpha(bottom) != 0 && botH > 0) { p.setColor(bottom); c.drawRect(0, getHeight() - botH, getWidth(), getHeight(), p); }
        }
    }

    /**
     * لقطة للواجهة تحترم القصّ بالحواف المدوّرة (clipToOutline) والظلال.
     * root.draw(Canvas) على Canvas برمجي يتجاهل clipToOutline فتظهر البطاقات بحواف حادّة داخل الدائرة؛
     * PixelCopy تنسخ ما رسمته الـ GPU فعلاً فتبقى الحواف مدوّرة. (API 26+ ، وإلا الطريقة القديمة)
     */
    private static void snapshot(final Activity act, final View root, final android.webkit.ValueCallback<Bitmap> done) {
        final int w = root.getWidth(), h = root.getHeight();
        final Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        final Runnable fallback = () -> {
            try { root.draw(new Canvas(bmp)); } catch (Throwable ignored) { }
            done.onReceiveValue(bmp);
        };
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            try {
                int[] loc = new int[2];
                root.getLocationInWindow(loc);
                android.graphics.Rect r = new android.graphics.Rect(loc[0], loc[1], loc[0] + w, loc[1] + h);
                android.view.PixelCopy.request(act.getWindow(), r, bmp, res -> {
                    if (res == android.view.PixelCopy.SUCCESS) done.onReceiveValue(bmp);
                    else fallback.run();
                }, new android.os.Handler(android.os.Looper.getMainLooper()));
                return;
            } catch (Throwable ignored) { }
        }
        fallback.run();
    }

    /**
     * نفس الكشف الدائري لكن الدائرة تمتد أيضاً إلى شريط الإشعارات (الحالة) وشريط الإيماءات السفلي:
     * الشريطان يصيران شفافين أثناء الحركة ويُرسم لونهما القديم داخل الصورة المتلاشية والجديد تحتها.
     */
    public static void themeReveal(final Activity act, View root, float x, float y,
                                   final BabylonBars bars, final int sbTop, final int navBottom,
                                   final Runnable applyTheme) {
        try {
            if (root == null || root.getWidth() <= 0 || root.getHeight() <= 0 || bars == null) {
                applyTheme.run(); return;
            }
            snapshot(act, root, old -> {
            try {
            final int oldTop = bars.topColor(), oldBot = bars.bottomColor();
            bars.beginReveal();
            applyTheme.run();
            final int newTop = bars.topColor(), newBot = bars.bottomColor();

            final ViewGroup content = act.findViewById(android.R.id.content);
            int[] rl = new int[2], cl = new int[2];
            root.getLocationOnScreen(rl);
            content.getLocationOnScreen(cl);
            final int offX = rl[0] - cl[0], offY = rl[1] - cl[1];
            final float cx = x - cl[0], cy = y - cl[1];

            final BarsView nb = new BarsView(act, newTop, newBot, sbTop, navBottom);
            content.addView(nb, new FrameLayout.LayoutParams(-1, -1));
            final RevealView rv = new RevealView(act, old, cx, cy, offX, offY);
            rv.setBars(oldTop, oldBot, sbTop, navBottom);
            content.addView(rv, new FrameLayout.LayoutParams(-1, -1));

            float w = content.getWidth(), h = content.getHeight();
            final float maxR = (float) Math.hypot(Math.max(cx, w - cx), Math.max(cy, h - cy));
            ValueAnimator va = ValueAnimator.ofFloat(0f, maxR);
            va.setDuration(600);
            va.setInterpolator(themeEase());
            va.addUpdateListener(a -> rv.setRadius((Float) a.getAnimatedValue()));
            va.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) {
                    bars.endReveal();
                    content.removeView(rv);
                    content.removeView(nb);
                    old.recycle();
                }
            });
            va.start();
            } catch (Throwable t) {
                try { bars.endReveal(); } catch (Throwable ignored) { }
                try { applyTheme.run(); } catch (Throwable ignored) { }
            }
            });
        } catch (Throwable t) {
            try { bars.endReveal(); } catch (Throwable ignored) { }
            try { applyTheme.run(); } catch (Throwable ignored) { }
        }
    }

    /**
     * تبديل الثيم بدائرة تتسع من موضع اللمس خلال 600ms.
     * يلتقط صورة للواجهة القديمة، يطبّق الثيم، ثم يمسح الصورة القديمة بدائرة متوسّعة.
     */
    public static void themeReveal(final Activity act, View root, float x, float y, Runnable applyTheme) {
        try {
            if (root == null || root.getWidth() <= 0 || root.getHeight() <= 0) { applyTheme.run(); return; }
            final Bitmap old = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
            root.draw(new Canvas(old));
            applyTheme.run();

            final ViewGroup content = act.findViewById(android.R.id.content);
            int[] rl = new int[2], cl = new int[2];
            root.getLocationOnScreen(rl);
            content.getLocationOnScreen(cl);
            final int offX = rl[0] - cl[0], offY = rl[1] - cl[1];
            final float cx = x - cl[0], cy = y - cl[1];
            final RevealView rv = new RevealView(act, old, cx, cy, offX, offY);
            content.addView(rv, new FrameLayout.LayoutParams(-1, -1));

            float w = content.getWidth(), h = content.getHeight();
            final float maxR = (float) Math.hypot(Math.max(cx, w - cx), Math.max(cy, h - cy));
            ValueAnimator va = ValueAnimator.ofFloat(0f, maxR);
            va.setDuration(600);
            va.setInterpolator(themeEase());
            va.addUpdateListener(a -> rv.setRadius((Float) a.getAnimatedValue()));
            va.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) {
                    content.removeView(rv);
                    old.recycle();
                }
            });
            va.start();
        } catch (Throwable t) {
            try { applyTheme.run(); } catch (Throwable ignored) { }
        }
    }

    // -------------------------------------------------------- ضغط الأزرار
    /** تأثير :active — تصغير خفيف عند اللمس (قيمة scale تُمرَّر حسب العنصر). */
    public static void press(View v, final float scale) {
        if (v == null) return;
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(scale).scaleY(scale).setDuration(110).setInterpolator(easeOut()).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(easeOut()).start();
                    break;
                default:
                    break;
            }
            return false; // لا نستهلك الحدث: النقر يعمل كالمعتاد
        });
    }

    // ---------------------------------------------------- لمعان العنوان الذهبي
    /**
     * عنوان "Babylon Chat" بتدرج ذهبي لامع يتحرك (babylonTitleShine 15s linear infinite).
     * الألوان من الـ CSS: #b8891a / #e0bd4a / #fff7cf.
     */
    public static void shine(final TextView title) {
        if (title == null) return;
        title.post(() -> {
            float w = title.getPaint().measureText(title.getText().toString());
            if (w <= 0) return;
            // تدرّج ذهبي ثابت (بلا أنيميشن) بنفس ألوان واتجاه عنوان السبلاش
            float h = title.getHeight();
            title.getPaint().setShader(new LinearGradient(0, 0, w, h,
                    new int[]{0xFFECD176, 0xFFD0A533, 0xFFB4871D}, null, Shader.TileMode.CLAMP));
            title.setTextColor(Color.WHITE); // اللون الفعلي يأتي من الـ shader
            title.invalidate();
        });
    }

    /** مؤشر الصورة لعرض صورة داخل ImageView بقص دائري (يُستعمل مع صورة الملف الشخصي). */
    public static void circleClip(final ImageView iv) {
        iv.setClipToOutline(true);
        iv.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
    }
}
