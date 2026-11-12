package com.babylon.chat.iq;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

/**
 * شريط الحالة + شريط الإيماءات (gesture bar).
 * منقول من سكربتي status-bar-color-sync و bb-gesture-fill-js:
 *   - شريط الحالة يأخذ لون رأس القسم الظاهر ، وأيقوناته تُضبط حسب سطوع اللون (lum > 0.55 => أيقونات داكنة).
 *   - منطقة شريط الإيماءات تُملأ بلون القسم السفلي الظاهر (الشريط السفلي / مدخل الرسائل / خلفية الصفحة).
 */
public final class BabylonBars {

    private final Activity act;
    private int curTop = 0, curBottom = 0;
    private boolean first = true;
    /** true أثناء وضع الملف الشخصي: شريط الحالة شفاف تماماً (الغلاف/الصورة ممتدة خلفه). */
    private boolean transparentTop = false;
    private ValueAnimator topAnim, botAnim;
    /** true أثناء كشف الثيم الدائري: الشريطان شفافان والدائرة ترسم خلفهما، فلا نغيّر ألوان النافذة. */
    private boolean hold = false;
    /** true داخل المحادثة: شريط الإيماءات شفاف والزجاج يمتد خلفه. */
    private boolean transparentBottom = false;

    /** لون خلف شريط الحالة الحالي (0 = شفاف: الملف الشخصي). */
    public int topColor() { return curTop; }
    /** لون خلف شريط الإيماءات الحالي. */
    public int bottomColor() { return curBottom; }

    /** بدء كشف الثيم: نوقف الحركات ونجعل الشريطين شفافين ليظهر ما تحتهما (الدائرة تمتد إليهما). */
    public void beginReveal() {
        hold = true;
        if (topAnim != null) topAnim.cancel();
        if (botAnim != null) botAnim.cancel();
        Window w = act.getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
    }

    /** نهاية الكشف: نثبّت لون الشريطين الجديد فوراً (مطابق لما رسمته الدائرة). */
    public void endReveal() {
        hold = false;
        Window w = act.getWindow();
        w.setStatusBarColor(transparentTop ? Color.TRANSPARENT : curTop);
        w.setNavigationBarColor(transparentBottom ? Color.TRANSPARENT : curBottom);
    }

    public BabylonBars(Activity act) {
        this.act = act;
    }

    /** يُستدعى مرة واحدة لإعداد النافذة (edge-to-edge) . */
    public void setup() {
        Window w = act.getWindow();
        w.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        if (Build.VERSION.SDK_INT >= 29) {
            w.setNavigationBarContrastEnforced(false);
            w.setStatusBarContrastEnforced(false);
        }
        // الغلاف/الصورة المكبّرة تمتد أيضاً خلف القصّة (notch) في الأجهزة التي فيها قصّة
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }
    }

    private static float lum(int c) {
        return (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f;
    }

    /**
     * @param top    لون رأس القسم الظاهر (خلف شريط الحالة)
     * @param bottom لون القسم السفلي الظاهر (خلف شريط الإيماءات)
     */
    public void sync(int top, int bottom) {
        top |= 0xFF000000;
        bottom |= 0xFF000000;
        Window w = act.getWindow();

        // أيقونات الشريطين حسب السطوع
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (Build.VERSION.SDK_INT >= 23 && lum(top) > 0.55f) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26 && lum(bottom) > 0.55f) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        w.getDecorView().setSystemUiVisibility(flags);

        if (hold) {
            if (topAnim != null) topAnim.cancel();
            curTop = top;
            transparentTop = false;
        } else if (first || transparentTop) {
            // أول مرة، أو الخروج من وضع الملف الشخصي (الشريط كان شفافاً): نضع اللون فوراً بلا حركة
            if (topAnim != null) topAnim.cancel();
            curTop = top;
            w.setStatusBarColor(top);
            transparentTop = false;
        } else if (top != curTop) {
            if (topAnim != null) topAnim.cancel();
            topAnim = ValueAnimator.ofObject(new ArgbEvaluator(), curTop, top);
            topAnim.setDuration(180);
            topAnim.addUpdateListener(a -> act.getWindow().setStatusBarColor((Integer) a.getAnimatedValue()));
            topAnim.start();
            curTop = top;
        }
        syncBottom(w, bottom);
    }

    /**
     * الملف الشخصي (ملفي / ملف شخص آخر): مطابق لـ edge(true) في الـ HTML —
     * شريط الحالة شفاف تماماً فتظهر الصورة/الغلاف خلفه، والأيقونات بيضاء دائماً
     * (setStyle DARK في Capacitor = أيقونات فاتحة).
     *
     * @param bottom لون القسم السفلي الظاهر (خلف شريط الإيماءات)
     */
    public void syncProfile(int bottom) {
        bottom |= 0xFF000000;
        Window w = act.getWindow();
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        // لا نضيف LIGHT_STATUS_BAR => أيقونات بيضاء فوق الغلاف/الصورة
        if (Build.VERSION.SDK_INT >= 26 && lum(bottom) > 0.55f) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        w.getDecorView().setSystemUiVisibility(flags);

        if (topAnim != null) topAnim.cancel();
        w.setStatusBarColor(Color.TRANSPARENT);
        transparentTop = true;
        curTop = 0;
        syncBottom(w, bottom);
    }

    /**
     * داخل المحادثة: مطابق للـ HTML —
     *  - شريط الحالة شفاف تماماً (الطبقة الزجاجية للرأس تمتد خلفه) وأيقونته تتبع سطوع لون المحادثة؛
     *  - شريط الإيماءات يأخذ لون خلفية المحادثة الصلب (#bbGestureFill) وأيقونته كذلك.
     *
     * @param surface لون خلفية المحادثة (--cr-surface)
     */
    public void syncChat(int surface) {
        surface |= 0xFF000000;
        Window w = act.getWindow();
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        boolean light = lum(surface) > 0.55f;
        if (Build.VERSION.SDK_INT >= 23 && light) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26 && light) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        w.getDecorView().setSystemUiVisibility(flags);

        if (topAnim != null) topAnim.cancel();
        w.setStatusBarColor(Color.TRANSPARENT);
        transparentTop = true;
        curTop = 0;
        // شريط الإيماءات شفاف مثل شريط الإشعارات (الطبقة الزجاجية للمدخل تمتد خلفه)
        if (botAnim != null) botAnim.cancel();
        if (!hold) w.setNavigationBarColor(Color.TRANSPARENT);
        transparentBottom = true;
        curBottom = surface;
        first = false;
    }

    /** داخل المحادثة أثناء البحث عن رسالة: شريط الحالة يمتد بنفس لون شريط البحث (وأيقوناته حسب سطوعه). */
    public void syncChatSearch(int color) {
        color |= 0xFF000000;
        Window w = act.getWindow();
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (Build.VERSION.SDK_INT >= 23 && lum(color) > 0.55f) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26 && lum(curBottom | 0xFF000000) > 0.55f) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        w.getDecorView().setSystemUiVisibility(flags);
        if (topAnim != null) topAnim.cancel();
        if (!hold) w.setStatusBarColor(color);
        transparentTop = true;   // عند الخروج يُعاد اللون الشفاف فوراً بلا حركة
        curTop = 0;
        if (botAnim != null) botAnim.cancel();
        if (!hold) w.setNavigationBarColor(Color.TRANSPARENT);
        transparentBottom = true;
        first = false;
    }

    private void syncBottom(Window w, int bottom) {
        if (transparentBottom && !hold) {   // الخروج من المحادثة: نعيد اللون الصلب فوراً بلا حركة
            if (botAnim != null) botAnim.cancel();
            transparentBottom = false;
            curBottom = bottom;
            w.setNavigationBarColor(bottom);
            first = false;
            return;
        }
        if (hold) {
            if (botAnim != null) botAnim.cancel();
            curBottom = bottom;
            first = false;
            return;
        }
        if (first) {
            first = false;
            curBottom = bottom;
            w.setNavigationBarColor(bottom);
            return;
        }
        if (bottom != curBottom) {
            if (botAnim != null) botAnim.cancel();
            botAnim = ValueAnimator.ofObject(new ArgbEvaluator(), curBottom, bottom);
            botAnim.setDuration(180);
            botAnim.addUpdateListener(a -> act.getWindow().setNavigationBarColor((Integer) a.getAnimatedValue()));
            botAnim.start();
            curBottom = bottom;
        }
    }

    /** شاشات سوداء كاملة (عارض قصص/صور): الشريطان أسودان وأيقونات بيضاء. */
    public void black() {
        sync(0xFF000000, 0xFF000000);
    }
}
