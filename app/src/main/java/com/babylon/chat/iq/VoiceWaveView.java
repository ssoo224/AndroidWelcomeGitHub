package com.babylon.chat.iq;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * موجة التسجيل الصوتي — مطابقة لـ .recording-wave في الـ HTML:
 *  - أعمدة عرضها 3dp وبينها 5dp، ارتفاعها الأساسي 10dp (كل 3 → 19dp، كل 5 → 23dp)
 *  - حركة recordingWave: scaleY .45 → 1 و opacity .6 → 1 (alternate كل .72s)
 *  - تأخيرات الحركة: 4n+1 = -.2s ، 4n+2 = -.5s ، 4n+3 = -.35s
 *  - وضع المعاينة (متوقفة): scaleY .8 و opacity .85
 *  - مغلّف الموجة (bb-wave-envelope): كل عمود يزيد sin(π·(i+.5)/n)·8dp فتكون الموجة أطول في المنتصف
 */
public class VoiceWaveView extends View {
    private static final float BAR_W = 3f, GAP = 5f, BASE_H = 10f, H3 = 19f, H5 = 23f, EXTRA = 8f;
    private static final long HALF = 720L, CYCLE = HALF * 2;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float d;
    private ValueAnimator anim;
    private boolean running = false;
    private long t0 = 0;
    private int color = 0xFFC99A1C;

    public VoiceWaveView(Context c) { this(c, null); }
    public VoiceWaveView(Context c, AttributeSet a) {
        super(c, a);
        d = c.getResources().getDisplayMetrics().density;
        paint.setStyle(Paint.Style.FILL);
    }

    public void setColor(int c) { color = c; invalidate(); }

    /** true = تتحرك الأعمدة، false = متوقفة (وضع المعاينة). */
    public void setRunning(boolean on) {
        if (on == running && (anim != null) == on) { return; }
        running = on;
        stopAnim();
        if (on) {
            t0 = System.currentTimeMillis();
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(CYCLE);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setInterpolator(new LinearInterpolator());
            anim.addUpdateListener(a -> invalidate());
            anim.start();
        } else {
            invalidate();
        }
    }

    private void stopAnim() {
        if (anim != null) { anim.cancel(); anim = null; }
    }

    @Override protected void onDetachedFromWindow() { stopAnim(); super.onDetachedFromWindow(); }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (running && anim == null) { boolean r = running; running = false; setRunning(r); }
    }

    private static long delayFor(int n) { // n يبدأ من 1
        switch (n % 4) {
            case 1: return 200;
            case 2: return 500;
            case 3: return 350;
            default: return 0;
        }
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float step = (BAR_W + GAP) * d;
        int count = (int) ((w + GAP * d) / step);
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        long now = System.currentTimeMillis() - t0;
        for (int i = 0; i < count; i++) {
            int n = i + 1;
            float base = (n % 5 == 0) ? H5 : (n % 3 == 0) ? H3 : BASE_H;
            float ex = (float) (EXTRA * Math.sin(Math.PI * (i + 0.5) / count)) * d;
            float bh = base * d + ex;
            float scale, alpha;
            if (running) {
                long p = (now + delayFor(n)) % CYCLE;
                float x = p <= HALF ? p / (float) HALF : (CYCLE - p) / (float) HALF;
                float e = (float) (1 - Math.cos(Math.PI * x)) / 2f;   // ease-in-out
                scale = 0.45f + 0.55f * e;
                alpha = 0.6f + 0.4f * e;
            } else {
                scale = 0.8f; alpha = 0.85f;
            }
            float bhh = Math.min(bh * scale, h);
            float left = rtl ? w - (i * step + BAR_W * d) : i * step;
            float top = (h - bhh) / 2f;
            rect.set(left, top, left + BAR_W * d, top + bhh);
            paint.setColor(color);
            paint.setAlpha(Math.round(Color255(alpha)));
            c.drawRoundRect(rect, 2f * d, 2f * d, paint);
        }
    }

    private static float Color255(float a) { return Math.max(0f, Math.min(1f, a)) * 255f; }
}
