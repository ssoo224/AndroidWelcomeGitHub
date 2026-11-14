package com.babylon.chat.iq;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/** يرسم drawable مع قصّ الحافة العليا (مقدار topPx) كي لا يظهر إطاره/خطّه العلوي؛ بقية الحواف كما هي. */
final class TopClipDrawable extends Drawable {
    private final Drawable inner;
    private final int topPx;

    TopClipDrawable(Drawable inner, int topPx) { this.inner = inner; this.topPx = topPx; }

    @Override public void draw(Canvas c) {
        c.save();
        c.clipRect(getBounds().left, getBounds().top + topPx, getBounds().right, getBounds().bottom);
        inner.draw(c);
        c.restore();
    }
    @Override protected void onBoundsChange(android.graphics.Rect b) { inner.setBounds(b); }
    @Override public void setAlpha(int a) { inner.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter f) { inner.setColorFilter(f); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
