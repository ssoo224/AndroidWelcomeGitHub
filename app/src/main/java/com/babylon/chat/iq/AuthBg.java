package com.babylon.chat.iq;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.animation.DecelerateInterpolator;

/**
 * خلفية شاشتي السبلاش وتسجيل الدخول مرسومة برمجياً بالكامل (بلا أي صورة):
 * أرضية كريمية + موجتان ذهبيتان في الزاوية العلوية اليسرى والسفلية اليمنى،
 * كل موجة = قطعة ذهبية متدرّجة + شريط بيج فاتح + خط ذهبي رفيع.
 * الإحداثيات مأخوذة من التصميم المرجعي (842×1869) وتُرسم بنمط cover فتملأ أي شاشة.
 */
final class AuthBg extends Drawable {
    private static final float RW = 842f, RH = 1869f;

    // ===== منحنيات الزاوية العلوية اليسرى (بيزييه تكعيبي: بداية، تحكم1، تحكم2، نهاية) =====
    // حافة القطعة الذهبية
    private static final float[][] TL_GOLD = {
            {0, 329, 57, 264, 127, 212, 200, 167}, {200, 167, 275, 123, 348, 72, 404, 4}};
    // الحافة الخارجية للشريط البيج
    private static final float[][] TL_BAND = {
            {0, 436, 79, 340, 183, 268, 292, 211}, {292, 211, 399, 155, 508, 97, 588, 4}};
    // الخط الذهبي الرفيع
    private static final float[][] TL_LINE = {
            {0, 473, 81, 367, 192, 291, 308, 229}, {308, 229, 422, 171, 534, 107, 616, 7}, {616, 7, 619, 4, 622, 2, 625, -2}};
    // ===== الزاوية السفلية اليمنى (محسوبة في إحداثيات مقلوبة 180° حول مركز الصفحة) =====
    private static final float[][] BR_GOLD = {
            {0, 321, 58, 257, 126, 204, 200, 162}, {200, 162, 271, 122, 347, 87, 404, 28}, {404, 28, 416, 15, 428, 4, 440, -8}};
    private static final float[][] BR_BAND = {
            {0, 441, 84, 338, 197, 264, 316, 209}, {316, 209, 427, 156, 548, 113, 636, 23}, {636, 23, 646, 13, 657, 4, 668, -8}};
    private static final float[][] BR_LINE = {
            {0, 476, 83, 368, 194, 291, 316, 234}, {316, 234, 427, 182, 544, 138, 636, 55}, {636, 55, 651, 40, 669, 21, 690, -2}};

    private final boolean dark;
    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint goldTL = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint goldBR = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint swirlPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path swirl = new Path();
    private final Path bandTL, goldPathTL, lineTL, bandBR, goldPathBR, lineBR;

    private ValueAnimator intro;
    private float introT = 1f;   // 1 = الوضع النهائي الثابت

    AuthBg(boolean dark) {
        this.dark = dark;

        // أرضية: كريمي ثابت نهاراً، وتدرّج شعاعي ليلي
        basePaint.setShader(dark
                ? new RadialGradient(RW / 2f, RH / 2f, RH * 0.72f, 0xFF17212B, 0xFF0E1621, Shader.TileMode.CLAMP)
                : new RadialGradient(RW / 2f, RH / 2f, RH * 0.75f, 0xFFFDFCF8, 0xFFFCF9F2, Shader.TileMode.CLAMP));

        // الشريط البيج بين الذهبي والخط
        bandPaint.setShader(dark
                ? new LinearGradient(0, 300, 450, 80, 0x38E8BE45, 0x24E8BE45, Shader.TileMode.CLAMP)
                : new LinearGradient(0, 300, 450, 80, 0xFFF9E6B8, 0xFFF9E9C5, Shader.TileMode.CLAMP));

        // القطعة الذهبية: العلوية أفتح، والسفلية تغمق عند طرفها (#D9AE31)
        goldTL.setShader(new LinearGradient(0, 0, 410, 70,
                gold(new int[]{0xFFFAE085, 0xFFF6D671, 0xFFEECB63, 0xFFEDC964}),
                new float[]{0f, 0.35f, 0.75f, 1f}, Shader.TileMode.CLAMP));
        goldBR.setShader(new LinearGradient(0, 0, 430, 40,
                gold(new int[]{0xFFFCE282, 0xFFF8DB76, 0xFFF1D169, 0xFFE7C153, 0xFFD9AE31}),
                new float[]{0f, 0.3f, 0.6f, 0.82f, 1f}, Shader.TileMode.CLAMP));

        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeWidth(2.2f);
        line.setColor(dark ? 0xCCEDC548 : 0xFFEDC548);

        // توهّج دافئ خفيف في وسط الصفحة (ظلال ناعمة كما في التصميم)
        glowPaint.setShader(new RadialGradient(640f, 1150f, 560f,
                dark ? 0x18E8BE45 : 0x40FBF0D2, 0x00FBF0D2, Shader.TileMode.CLAMP));

        // موجة دافئة باهتة جداً تعبر منتصف الصفحة (الانسياب الخفيف في التصميم)
        swirlPaint.setColor(dark ? 0x0CE8BE45 : 0x16F6DFA0);
        swirl.moveTo(0, 770);
        swirl.cubicTo(180, 520, 520, 470, 842, 330);
        swirl.lineTo(842, 1040);
        swirl.cubicTo(700, 1280, 330, 1330, 0, 1500);
        swirl.close();

        bandTL = closed(TL_BAND, -8f, 592f);
        goldPathTL = closed(TL_GOLD, -8f, 410f);
        lineTL = open(TL_LINE);
        bandBR = closed(BR_BAND, -8f, 668f);
        goldPathBR = closed(BR_GOLD, -8f, 440f);
        lineBR = open(BR_LINE);
    }

    private int[] gold(int[] c) {
        if (!dark) return c;
        int[] o = new int[c.length];
        for (int i = 0; i < c.length; i++) {   // الليلي: نفس التدرّج بدرجة أخفّ
            int r = Math.round(((c[i] >> 16) & 0xFF) * 0.66f), g = Math.round(((c[i] >> 8) & 0xFF) * 0.66f), b = Math.round((c[i] & 0xFF) * 0.66f);
            o[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return o;
    }

    private static Path open(float[][] segs) {
        Path p = new Path();
        p.moveTo(segs[0][0], segs[0][1]);
        for (float[] s : segs) p.cubicTo(s[2], s[3], s[4], s[5], s[6], s[7]);
        return p;
    }

    /** مسار مغلق: المنحنى ثم الحافة العلوية والحافة اليسرى (تمتد قليلاً خارج الشاشة). */
    private static Path closed(float[][] segs, float out, float topEndX) {
        Path p = open(segs);
        p.lineTo(topEndX, out);
        p.lineTo(out, out);
        p.lineTo(out, segs[0][1]);
        p.close();
        return p;
    }

    /** ظهور ناعم للموجتين من الزاويتين عند بداية السبلاش ثم تثبتان. */
    void playIntro(long durationMs) {
        stopIntro();
        introT = 0f;
        intro = ValueAnimator.ofFloat(0f, 1f);
        intro.setDuration(durationMs);
        intro.setInterpolator(new DecelerateInterpolator(1.6f));
        intro.addUpdateListener(a -> { introT = (float) a.getAnimatedValue(); invalidateSelf(); });
        intro.start();
        invalidateSelf();
    }

    void stopIntro() {
        if (intro != null) { intro.cancel(); intro = null; }
        introT = 1f;
        invalidateSelf();
    }

    @Override public void draw(Canvas c) {
        Rect b = getBounds();
        if (b.width() <= 0 || b.height() <= 0) return;
        c.save();
        c.clipRect(b);
        c.translate(b.left, b.top);
        float s = Math.max(b.width() / RW, b.height() / RH);   // cover
        c.translate((b.width() - RW * s) / 2f, (b.height() - RH * s) / 2f);
        c.scale(s, s);
        // نغطي أي جزء خارج الإطار المرجعي بنفس الأرضية
        c.drawRect(-2000f, -2000f, RW + 2000f, RH + 2000f, basePaint);
        c.drawPath(swirl, swirlPaint);
        c.drawRect(0, 0, RW, RH, glowPaint);

        final float k = 1f - introT;
        final int alpha = Math.round(255f * Math.min(1f, introT * 1.6f));
        for (int i = 0; i < 2; i++) {
            c.save();
            if (i == 1) c.rotate(180f, RW / 2f, RH / 2f);   // الزاوية السفلية اليمنى
            float z = 1f + 0.18f * k;                        // انزلاق خفيف من الزاوية
            c.scale(z, z, 0f, 0f);
            bandPaint.setAlpha(alpha); (i == 0 ? goldTL : goldBR).setAlpha(alpha); line.setAlpha(Math.round(alpha * (dark ? 0.8f : 1f)));
            c.drawPath(i == 0 ? bandTL : bandBR, bandPaint);
            c.drawPath(i == 0 ? goldPathTL : goldPathBR, i == 0 ? goldTL : goldBR);
            c.drawPath(i == 0 ? lineTL : lineBR, line);
            c.restore();
        }
        c.restore();
    }

    @Override public void setAlpha(int a) { }
    @Override public void setColorFilter(ColorFilter cf) { }
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
}
