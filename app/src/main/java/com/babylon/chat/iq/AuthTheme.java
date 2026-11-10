package com.babylon.chat.iq;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.view.Window;

/** الطور الليلي لشاشتي السبلاش وتسجيل الدخول (نفس إعداد التطبيق: babylon_ui / dark). */
final class AuthTheme {
    private AuthTheme() { }

    static final int BG_TOP = 0xFF17212B, BG_EDGE = 0xFF0E1621;   // الليلي: سطح → خلفية الصفحة
    static final int SURFACE = 0xFF17212B, BORDER = 0x24FFFFFF;
    static final int GOLD_LABEL = 0xFFE4C45B, TEXT = 0xFFF1F5F9, MUTED = 0xFFA5B2C2, HINT = 0xFF7D8DA0;

    static boolean isDark(Context c) {
        try { return c.getSharedPreferences("babylon_ui", Context.MODE_PRIVATE).getBoolean("dark", false); }
        catch (Throwable t) { return false; }
    }

    /** خلفية شعاعية: نهاري #fff→#edf2f8 (كما كانت) / ليلي #17212B→#0E1621. */
    static GradientDrawable screenBg(boolean dark) {
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientCenter(0.5f, 0.5f);
        g.setColors(dark ? new int[]{BG_TOP, BG_EDGE} : new int[]{0xFFFFFFFF, 0xFFEDF2F8});
        g.setGradientRadius(1400f);   // يغطي الشاشة (الأصل 120%p)
        return g;
    }

    static GradientDrawable box(boolean dark, float radiusPx, int fillLight, int strokeLight, int strokePx) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(radiusPx);
        g.setColor(dark ? SURFACE : fillLight);
        g.setStroke(strokePx, dark ? BORDER : strokeLight);
        return g;
    }

    /** أيقونات شريط الحالة/التنقل: داكنة في النهاري، فاتحة في الليلي. */
    static void systemBars(Window w, boolean dark) {
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (!dark) {
            if (Build.VERSION.SDK_INT >= 23) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        w.getDecorView().setSystemUiVisibility(flags);
    }
}
