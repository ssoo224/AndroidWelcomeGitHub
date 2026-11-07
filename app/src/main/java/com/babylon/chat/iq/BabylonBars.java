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
    private ValueAnimator topAnim, botAnim;

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

        if (first) {
            first = false;
            curTop = top; curBottom = bottom;
            w.setStatusBarColor(top);
            w.setNavigationBarColor(bottom);
            return;
        }
        if (top != curTop) {
            if (topAnim != null) topAnim.cancel();
            topAnim = ValueAnimator.ofObject(new ArgbEvaluator(), curTop, top);
            topAnim.setDuration(180);
            topAnim.addUpdateListener(a -> act.getWindow().setStatusBarColor((Integer) a.getAnimatedValue()));
            topAnim.start();
            curTop = top;
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
