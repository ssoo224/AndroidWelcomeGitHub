package com.babylon.chat.iq;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * الطبقة الزجاجية خلف رأس المحادثة وخلف مدخل الرسائل (نفس ما يحسبه المتصفح في الـ HTML):
 *   #chatView header.telegram-chat-header::before  و  #chatView .chat-input-container::before
 *   background: color-mix(in srgb, var(--cr-surface) 72%, transparent)   (طبقة bbPerf: لا backdrop-filter)
 *   mask-image: linear-gradient(...)  حافة ناعمة 16px نحو الرسائل (تتلاشى عند الحافة الداخلية)
 *
 * الرسائل تمرّ من خلف الطبقة (الـ ScrollView يمتد حتى أعلى وأسفل الشاشة) فتظهر بشفافية 28%.
 * @param top true = رأس (التلاشي عند الحافة السفلية) ، false = مدخل (التلاشي عند الحافة العلوية)
 */
public final class Glass extends Drawable {
    /** 72% من 255 */
    private static final int ALPHA = 0xB8;

    private final Paint paint = new Paint();
    private final int solid, clear;
    private final boolean top;
    private final float fade;

    public Glass(int surface, boolean top, float fadePx) {
        this.solid = (surface & 0x00FFFFFF) | (ALPHA << 24);
        this.clear = surface & 0x00FFFFFF;
        this.top = top;
        this.fade = fadePx;
    }

    @Override protected void onBoundsChange(Rect b) {
        float h = b.height();
        if (h <= 0) { paint.setShader(null); return; }
        float f = Math.min(fade, h);
        float p = f / h;
        Shader s;
        if (top) {
            s = new LinearGradient(0, b.top, 0, b.bottom,
                    new int[]{solid, solid, clear}, new float[]{0f, 1f - p, 1f}, Shader.TileMode.CLAMP);
        } else {
            s = new LinearGradient(0, b.top, 0, b.bottom,
                    new int[]{clear, solid, solid}, new float[]{0f, p, 1f}, Shader.TileMode.CLAMP);
        }
        paint.setShader(s);
    }

    @Override public void draw(Canvas c) {
        if (paint.getShader() == null) return;
        c.drawRect(getBounds(), paint);
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
