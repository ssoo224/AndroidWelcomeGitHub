package com.babylon.chat.iq;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;

/**
 * القرص الذهبي للسبلاش برسم خطي:
 * 1) يُرسم محيط الدائرة بخط ذهبي رفيع (يبدأ من الأعلى باتجاه عقارب الساعة)
 * 2) عند اكتمال الرسم يتلوّن القرص بالتدرّج الذهبي (bg_logo) ويخبو الخط
 */
public class DiscDrawView extends View {

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private Drawable disc;
    private float density;
    private float draw = 1f;   // 0..1 رسم المحيط
    private float fill = 1f;   // 0..1 تلوين القرص

    public DiscDrawView(Context c) { super(c); init(); }
    public DiscDrawView(Context c, AttributeSet a) { super(c, a); init(); }
    public DiscDrawView(Context c, AttributeSet a, int s) { super(c, a, s); init(); }

    private void init() {
        density = getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeWidth(1.8f * density);
        stroke.setColor(AuthTheme.isDark(getContext()) ? 0xFFE6B93A : 0xFFCFA020);   // ذهبي واضح على الخلفية النهارية والليلية
        disc = getResources().getDrawable(R.drawable.bg_logo, null).mutate();
    }

    public void setDraw(float p) { draw = p; invalidate(); }
    public void setFill(float f) { fill = f; invalidate(); }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (fill < 1f && draw > 0f) {
            float inset = 1f * density;   // نفس موضع حدّ bg_logo (2dp) ليتطابق المحيط مع القرص النهائي
            rect.set(inset, inset, w - inset, h - inset);
            stroke.setAlpha((int) (255 * (1f - fill)));
            if (draw >= 0.999f) c.drawOval(rect, stroke);
            else c.drawArc(rect, -90f, 360f * draw, false, stroke);
        }
        if (fill > 0f) {
            disc.setBounds(0, 0, w, h);
            disc.setAlpha((int) (255 * fill));
            disc.draw(c);
        }
    }
}
