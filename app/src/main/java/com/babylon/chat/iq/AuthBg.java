package com.babylon.chat.iq;

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

/**
 * خلفية شاشتي السبلاش وتسجيل الدخول: التدرّج الشعاعي المعتاد للتطبيق (نهاري/ليلي)
 * + موجتان ذهبيتان انسيابيتان في الزاوية العلوية اليسرى والسفلية اليمنى (بألوان المشروع الذهبية نفسها).
 */
final class AuthBg extends Drawable {
    private final boolean dark;
    private final float d;
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path pA = new Path(), pB = new Path(), pC = new Path();
    private int bw = -1, bh = -1;

    AuthBg(boolean dark, float density) {
        this.dark = dark;
        this.d = density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override protected void onBoundsChange(Rect b) { build(b.width(), b.height()); }

    private void build(int w, int h) {
        if (w <= 0 || h <= 0) return;
        bw = w; bh = h;
        float r = Math.max(w, h) * 0.85f;
        bg.setShader(new RadialGradient(w / 2f, h / 2f, r,
                dark ? AuthTheme.BG_TOP : 0xFFFFFFFF, dark ? AuthTheme.BG_EDGE : 0xFFEDF2F8, Shader.TileMode.CLAMP));
        // موجة عريضة خلفية
        pA.reset();
        pA.moveTo(0, 0); pA.lineTo(w * 0.78f, 0);
        pA.cubicTo(w * 0.60f, h * 0.10f, w * 0.26f, h * 0.15f, 0, h * 0.30f);
        pA.close();
        // موجة وسطى
        pB.reset();
        pB.moveTo(0, 0); pB.lineTo(w * 0.52f, 0);
        pB.cubicTo(w * 0.42f, h * 0.075f, w * 0.20f, h * 0.115f, 0, h * 0.21f);
        pB.close();
        // خط التوهّج على حافة الموجة
        pC.reset();
        pC.moveTo(w * 0.80f, 0);
        pC.cubicTo(w * 0.62f, h * 0.115f, w * 0.28f, h * 0.17f, 0, h * 0.325f);
        fill.setShader(new LinearGradient(0, 0, w * 0.6f, h * 0.2f,
                dark ? new int[]{0x66F5D875, 0x2EB98B23, 0x00B98B23} : new int[]{0x9CF5D875, 0x58D0A533, 0x10D0A533},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        line.setStrokeWidth(1.4f * d);
        line.setShader(new LinearGradient(0, 0, w * 0.8f, h * 0.2f,
                dark ? new int[]{0xCCF5D875, 0x66D0A533, 0x00D0A533} : new int[]{0xA0D0A533, 0x50D0A533, 0x00D0A533},
                new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override public void draw(Canvas c) {
        Rect b = getBounds();
        if (b.width() != bw || b.height() != bh) build(b.width(), b.height());
        if (bw <= 0) return;
        c.drawRect(b, bg);
        for (int i = 0; i < 2; i++) {
            c.save();
            if (i == 1) c.rotate(180f, bw / 2f, bh / 2f);   // الزاوية السفلية اليمنى = نفس الموجة مقلوبة
            c.drawPath(pA, fill);
            c.drawPath(pB, fill);
            c.drawPath(pC, line);
            c.restore();
        }
    }

    @Override public void setAlpha(int a) { }
    @Override public void setColorFilter(ColorFilter cf) { }
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
}
