package com.babylon.chat.iq;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
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
    private static final float BUMP_H = 6.5f, BUMP_HALF_W = 52f;               // dp — ارتفاع الانحناء ونصف عرضه

    /** ارتفاع شريط الإيماءات (px) الذي تضيفه الحاوية أسفل الكبسولة؛ تضبطه MassActivity.navGlassSync(). */
    static int EXTRA_BOTTOM = 0;
    private static final int GLASS_ALPHA = 0xB8;   // 72% — نفس Glass في المحادثة
    private static final float GLASS_FADE = 16f;   // dp — نفس حافة التلاشي في Glass

    private final boolean dark;
    private final float d;
    private final int glassColor;
    private Bitmap bm;

    NavBarBg(boolean dark, float density, int glassColor) {
        this.dark = dark;
        this.d = density;
        this.glassColor = glassColor;
    }

    /** يُعاد بناء الرسم عند تغيّر ارتفاع الإيماءات. */
    void refresh() {
        onBoundsChange(getBounds());
        invalidateSelf();
    }

    @Override protected void onBoundsChange(Rect b) {
        if (bm != null) { bm.recycle(); bm = null; }
        int w = b.width(), h = b.height();
        if (w <= 0 || h <= 0) return;
        bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        float l = PAD_L * d, r = w - PAD_R * d, t = PAD_T * d, bt = h - PAD_B * d - EXTRA_BOTTOM;
        Path p = shape(l, t, r, bt);

        // الطبقة الزجاجية: من منتصف الكبسولة حتى أسفل الشاشة (تشمل شريط الإيماءات)، تتلاشى عند حافتها العليا
        float gTop = (t + bt) / 2f, gh = h - gTop;
        if (gh > 0) {
            int solid = (glassColor & 0x00FFFFFF) | (GLASS_ALPHA << 24), clear = glassColor & 0x00FFFFFF;
            float fp = Math.min(GLASS_FADE * d, gh) / gh;
            Paint gp = new Paint();
            gp.setShader(new LinearGradient(0, gTop, 0, h, new int[]{clear, solid, solid}, new float[]{0f, fp, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, gTop, w, h, gp);
        }

        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(dark ? 0xFF17212B : 0xFFFFFFFF);
        fill.setShadowLayer(10f * d, 0f, 3f * d, dark ? 0x66000000 : 0x265A6A8A);   // الظل داخل الصورة (برمجي) فلا يتأثر بالتسريع
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
        p.cubicTo(cx - bw * 0.55f, t, cx - bw * 0.45f, t - bh, cx, t - bh);   // صعود ناعم إلى قمة الانحناء
        p.cubicTo(cx + bw * 0.45f, t - bh, cx + bw * 0.55f, t, cx + bw, t);   // ونزول متناظر
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

    /** خلفية التبويب المحدد: حبّة بيضاوية ذهبية كريمية بتدرّج عمودي (#FFF3D3 ← #FEEAC2 ← #FBE4BE) وحواف ناعمة وتوهّج خفيف. */
    static final class Sel extends Drawable {
        private static final float GLOW = 12f;                 // dp
        private static final float INSET_X = 5.5f;             // dp — تضييق الحبّة أفقياً عن عرض التبويب
        private static final float OUT_T = 5f, OUT_B = 2f;     // dp — تمتد فوق/تحت حدود التبويب لتملأ ارتفاع الشريط
        private final boolean dark;
        private final float d;
        private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private Bitmap glow;

        Sel(boolean dark, float density) {
            this.dark = dark;
            this.d = density;
        }

        private void pillRect(Rect b, RectF out) {
            out.set(b.left + INSET_X * d, b.top - OUT_T * d, b.right - INSET_X * d, b.bottom + OUT_B * d);
        }

        @Override protected void onBoundsChange(Rect b) {
            if (glow != null) { glow.recycle(); glow = null; }
            if (b.isEmpty()) return;
            pillRect(b, r);
            float w = r.width(), h = r.height();
            body.setShader(new LinearGradient(0f, r.top, 0f, r.bottom,
                    dark ? new int[]{0x4DF0D27A, 0x40E4C45B, 0x33D9B24A} : new int[]{0xFFFFF3D3, 0xFFFEEAC2, 0xFFFBE4BE},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            // لمعة ناعمة في الأعلى كما في المرجع
            sheen.setShader(new RadialGradient(r.centerX(), r.top + h * 0.22f, w * 0.55f,
                    dark ? 0x18FFFFFF : 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            float g = GLOW * d;
            glow = Bitmap.createBitmap((int) (w + 2 * g), (int) (h + 2 * g), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(glow);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(dark ? 0x33E4C45B : 0xFFFEEAC4);
            p.setShadowLayer(8f * d, 0f, 1.5f * d, dark ? 0x40E4C45B : 0x55EBCB8A);
            c.drawRoundRect(new RectF(g, g, g + w, g + h), w * 0.42f, h * 0.5f, p);
        }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            if (b.isEmpty()) return;
            pillRect(b, r);
            if (glow != null) {
                float g = GLOW * d;
                c.drawBitmap(glow, r.left - g, r.top - g, null);
            }
            float rx = r.width() * 0.42f, ry = r.height() * 0.5f;
            c.drawRoundRect(r, rx, ry, body);
            c.drawRoundRect(r, rx, ry, sheen);
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
