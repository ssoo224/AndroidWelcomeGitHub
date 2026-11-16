package com.babylon.chat.iq;

import android.content.Context;
import android.content.res.Configuration;
import android.util.DisplayMetrics;

/**
 * تحجيم ديناميكي لكل شيء (الأزرار، النصوص، الأيقونات، المسافات، الخطوط) حسب أبعاد الشاشة من 220dp إلى 711dp.
 * الفكرة: نُبقي التصميم المرجعي بعرض REF_DP (360dp) ونضرب الكثافة (densityDpi) بنسبة «أقصر ضلع للشاشة / REF_DP»،
 * فيتحجّم كل dp وsp في الـJava والـXML معاً بنفس النسبة بلا تعديل أي قيمة.
 * أقصر ضلع (smallest width) يُستعمل بدل العرض الحالي كي لا يتغير الحجم عند تدوير الشاشة.
 * خارج المدى [220, 711] تُثبَّت النسبة عند الطرف (أقل/أكبر).
 */
final class ScreenScale {
    private ScreenScale() { }

    static final float MIN_DP = 220f, MAX_DP = 711f, REF_DP = 360f;

    /** يلفّ Context الـActivity بإعدادات كثافة مُحجَّمة. يُستدعى من attachBaseContext. */
    static Context wrap(Context base) {
        try {
            Configuration cur = base.getResources().getConfiguration();
            DisplayMetrics dm = base.getResources().getDisplayMetrics();
            float sw = Math.min(dm.widthPixels, dm.heightPixels) / dm.density;   // smallest width بالـdp الأصلية
            if (sw <= 0f) return base;
            float k = Math.max(MIN_DP, Math.min(MAX_DP, sw)) / REF_DP;           // معامل التحجيم (1.0 عند 360dp)
            Configuration c = new Configuration(cur);
            int dpi = Math.max(60, Math.round(cur.densityDpi * k));
            c.densityDpi = dpi;
            float newDensity = dpi / 160f;
            c.screenWidthDp = Math.round(dm.widthPixels / newDensity);
            c.screenHeightDp = Math.round(dm.heightPixels / newDensity);
            c.smallestScreenWidthDp = Math.round(Math.min(dm.widthPixels, dm.heightPixels) / newDensity);
            return base.createConfigurationContext(c);
        } catch (Throwable t) {
            return base;
        }
    }

    // ---- أدوات للكود الجديد: كلها تعتمد على resources السياق (المُحجَّم) فتتبع التحجيم تلقائياً
    /** dp -> بكسل بحسب كثافة السياق المُحجَّمة. */
    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }
    /** sp -> بكسل (يشمل fontScale المستخدم). */
    static float sp(Context c, float v) { return v * c.getResources().getDisplayMetrics().scaledDensity; }
    /** معامل التحجيم الحالي للسياق نسبةً إلى التصميم المرجعي (1.0 = 360dp). */
    static float factor(Context c) {
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        return Math.min(dm.widthPixels, dm.heightPixels) / dm.density / REF_DP;
    }
}
