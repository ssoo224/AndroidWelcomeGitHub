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
 * .avatar-story-ring حول صورة الملف الشخصي (النسخة النهائية في الـ HTML):
 *  - حلقة خارجية 2px بلون خلفية الصفحة (--pf-bg): box-shadow:0 0 0 2px
 *  - حلقة القصة 4px: تدرّج 135° (#8f6810 · #f8e7a0 18% · #c29d30 38% · #fff1a8 52% · #c69a20 72% · #f5dc7a)
 *    أو #4b5a6b إن شوهدت، وشفافة إن لم توجد قصة؛ بلا توهج ولا حركة
 *  - الصورة بعدها مباشرة (110px داخل 118px)
 */
public final class ProfileRing extends Drawable {
    public interface Flag { boolean get(); }

    private static final int[] GRAD = {0xFF8F6810, 0xFFF8E7A0, 0xFFC29D30, 0xFFFFF1A8, 0xFFC69A20, 0xFFF5DC7A};
    private static final float[] POS = {0f, .18f, .38f, .52f, .72f, 1f};

    private final Drawable inner;
    private final float outerPx, ringPx;
    private final int bg;
    private final Flag hasStory, seen;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ProfileRing(Drawable inner, float outerPx, float ringPx, int bg, Flag hasStory, Flag seen) {
        this.inner = inner; this.outerPx = outerPx; this.ringPx = ringPx; this.bg = bg;
        this.hasStory = hasStory; this.seen = seen;
    }

    @Override protected void onBoundsChange(Rect b) {
        int t = Math.round(outerPx + ringPx);
        inner.setBounds(b.left + t, b.top + t, b.right - t, b.bottom - t);
    }

    @Override public void draw(Canvas c) {
        Rect b = getBounds();
        float cx = b.exactCenterX(), cy = b.exactCenterY();
        float R = Math.min(b.width(), b.height()) / 2f;

        // الحلقة الخارجية بلون الصفحة
        p.setShader(null); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(outerPx); p.setColor(bg);
        c.drawCircle(cx, cy, R - outerPx / 2f, p);

        // حلقة القصة
        if (hasStory.get()) {
            float rr = R - outerPx - ringPx / 2f;
            p.setStrokeWidth(ringPx);
            if (seen.get()) {
                p.setColor(0xFF4B5A6B);
            } else {
                float s = (R - outerPx) * 2f, a = (float) Math.toRadians(135);
                float dx = (float) Math.sin(a), dy = (float) -Math.cos(a);   // CSS: 135deg = نحو أسفل اليمين
                float len = (float) (Math.abs(s * Math.sin(a)) + Math.abs(s * Math.cos(a)));
                p.setShader(new LinearGradient(cx - dx * len / 2, cy - dy * len / 2, cx + dx * len / 2, cy + dy * len / 2,
                        GRAD, POS, Shader.TileMode.CLAMP));
            }
            c.drawCircle(cx, cy, rr, p);
            p.setShader(null);
        }
        inner.draw(c);
    }

    @Override public void setAlpha(int a) { inner.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter f) { inner.setColorFilter(f); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
