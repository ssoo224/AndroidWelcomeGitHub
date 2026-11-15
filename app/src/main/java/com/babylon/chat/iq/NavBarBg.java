package com.babylon.chat.iq;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * شريط التنقل السفلي العائم: كبسولة بيضاء (ليلي: #17212B) بظل ناعم، وفي منتصفها فقط انحناء خفيف يرتفع عن حافتها العلوية.
 * الانحناء ثابت في المنتصف مهما كان التبويب المحدد. يُرسم على حاوية الشريط (bottomNav) التي حشواتها
 * تترك مكان الظل والانحناء: يمين/يسار 22dp، أعلى 7dp، أسفل 10dp.
 */
final class NavBarBg extends Drawable {
    static final float PAD_L = 22f, PAD_R = 22f, PAD_T = 7f, PAD_B = 10f;   // dp — تطابق حشوات bottomNav في mass.xml
    private static final float BUMP_H = 5f, BUMP_HALF_W = 56f;               // dp — ارتفاع الانحناء ونصف عرضه

    private final boolean dark;
    private final float d;
    private Bitmap bm;

    NavBarBg(boolean dark, float density) {
        this.dark = dark;
        this.d = density;
    }

    @Override protected void onBoundsChange(Rect b) {
        if (bm != null) { bm.recycle(); bm = null; }
        int w = b.width(), h = b.height();
        if (w <= 0 || h <= 0) return;
        bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        float l = PAD_L * d, r = w - PAD_R * d, t = PAD_T * d, bt = h - PAD_B * d;
        Path p = shape(l, t, r, bt);

        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(dark ? 0xFF17212B : 0xFFFFFFFF);
        fill.setShadowLayer(9f * d, 0f, 2.5f * d, dark ? 0x66000000 : 0x2E5A6A8A);   // الظل داخل الصورة (برمجي) فلا يتأثر بالتسريع
        c.drawPath(p, fill);

        if (dark) {
            Paint st = new Paint(Paint.ANTI_ALIAS_FLAG);
            st.setStyle(Paint.Style.STROKE);
            st.setStrokeWidth(Math.max(1f, d));
            st.setColor(0x1FFFFFFF);
            c.drawPath(p, st);
        }
    }

    /** كبسولة كاملة الاستدارة + انحناء ناعم في المنتصف فقط. */
    private Path shape(float l, float t, float r, float b) {
        float R = (b - t) / 2f, cx = (l + r) / 2f, bw = BUMP_HALF_W * d, bh = BUMP_H * d;
        Path p = new Path();
        p.moveTo(l + R, t);
        p.lineTo(cx - bw, t);
        p.cubicTo(cx - bw * 0.5f, t, cx - bw * 0.5f, t - bh, cx, t - bh);   // صعود ناعم إلى قمة الانحناء
        p.cubicTo(cx + bw * 0.5f, t - bh, cx + bw * 0.5f, t, cx + bw, t);   // ونزول متناظر
        p.lineTo(r - R, t);
        p.arcTo(new RectF(r - 2 * R, t, r, b), -90f, 180f);
        p.lineTo(l + R, b);
        p.arcTo(new RectF(l, t, l + 2 * R, b), 90f, 180f);
        p.close();
        return p;
    }

    @Override public void draw(Canvas c) {
        if (bm == null) return;
        Rect b = getBounds();
        c.drawBitmap(bm, b.left, b.top, null);
    }

    @Override public void setAlpha(int a) { }
    @Override public void setColorFilter(ColorFilter cf) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }

    /** خلفية التبويب المحدد: كبسولة ذهبية كريمية ناعمة مع توهّج خفيف حولها. */
    static final class Sel extends Drawable {
        private static final float GLOW = 12f;   // dp
        private final boolean dark;
        private final float d;
        private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Bitmap glow;

        Sel(boolean dark, float density) {
            this.dark = dark;
            this.d = density;
        }

        @Override protected void onBoundsChange(Rect b) {
            if (glow != null) { glow.recycle(); glow = null; }
            int w = b.width(), h = b.height();
            if (w <= 0 || h <= 0) return;
            float g = GLOW * d, rad = h / 2f;
            body.setShader(new RadialGradient(w / 2f, h * 0.45f, Math.max(w, h) * 0.62f,
                    dark ? 0x4DE4C45B : 0xFFF8DE92, dark ? 0x26E4C45B : 0xFFFDF1C9, Shader.TileMode.CLAMP));
            glow = Bitmap.createBitmap((int) (w + 2 * g), (int) (h + 2 * g), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(glow);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(dark ? 0x33E4C45B : 0xFFFDF1C9);
            p.setShadowLayer(9f * d, 0f, 2f * d, dark ? 0x40E4C45B : 0x66F0C850);
            c.drawRoundRect(new RectF(g, g, g + w, g + h), rad, rad, p);
        }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            if (b.isEmpty()) return;
            if (glow != null) {
                float g = GLOW * d;
                c.drawBitmap(glow, b.left - g, b.top - g, null);
            }
            float rad = b.height() / 2f;
            c.save();
            c.translate(b.left, b.top);
            c.drawRoundRect(new RectF(0, 0, b.width(), b.height()), rad, rad, body);
            c.restore();
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
