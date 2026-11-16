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
 * تترك مكان الظل والانحناء: يمين/يسار 22dp، أعلى 7dp، أسفل 5dp.
 */
final class NavBarBg extends Drawable {
    static final float PAD_L = 22f, PAD_R = 22f, PAD_T = 7f, PAD_B = 5f;   // dp — تطابق حشوات bottomNav في mass.xml
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
        fill.setColor(dark ? 0xFF1E2A36 : 0xFFFFFFFF);   // الليلي: أفتح بدرجة من #17212B
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

    /**
     * خلفية التبويب المحدد: مربع بحواف منحنية بتدرّج ذهبي كريمي عمودي (#FFF3D3 ← #FEEAC2 ← #FBE4BE) وحواف ناعمة وتوهّج خفيف.
     * التبويب الأوسط (middle) تتبع حافته العلوية منحنى الشريط نفسه فيشغل حتى المساحة المنحنية في الأعلى.
     */
    static final class Sel extends Drawable {
        private static final float GLOW = 12f;        // dp
        private static final float EXT_X = 0f;        // dp — الحبّة داخل حدود التبويب فلا تلمس الفواصل العمودية
        private static final float INSET_Y = 4.5f;    // dp — مساحة فاصلة بين الحبّة وحافتي الكبسولة العليا والسفلى
        private static final float RADIUS = 18f;      // dp — استدارة الزوايا
        private static final float BAR_PAD = 3f;      // dp — حشوة صف الأزرار (حافة التبويب عن حافة الكبسولة)
        private static final float INSIDE = 3.5f;     // dp — مساحة فاصلة بين حافة الحبّة العلوية (المحادثات) ومنحنى الكبسولة
        private static final int N = 48;              // عدد عيّنات منحنى الانحناء

        private final boolean dark, middle;
        private final float d;
        private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF pb = new RectF();
        private final float[] bx = new float[N + 1], bhh = new float[N + 1];   // نفس منحنى NavBarBg.shape (من الحافة إلى القمة)
        private Bitmap glow;

        Sel(boolean dark, float density, boolean middle) {
            this.dark = dark;
            this.d = density;
            this.middle = middle;
            float bw = BUMP_HALF_W * d, bh = BUMP_H * d;
            for (int i = 0; i <= N; i++) {
                float u = i / (float) N, v = 1f - u;
                bx[i] = bw * (v * v * v + 3f * v * v * u * 0.55f + 3f * v * u * u * 0.45f);
                bhh[i] = bh * (3f * v * u * u + u * u * u);
            }
        }

        /** ارتفاع الانحناء (px) فوق حافة الكبسولة على بُعد rel من المنتصف. */
        private float bumpH(float rel) {
            if (rel >= bx[0]) return 0f;
            for (int i = 0; i < N; i++) {
                if (rel <= bx[i] && rel >= bx[i + 1]) {
                    float span = bx[i] - bx[i + 1];
                    float f = span <= 0f ? 0f : (bx[i] - rel) / span;
                    return bhh[i] + (bhh[i + 1] - bhh[i]) * f;
                }
            }
            return bhh[N];
        }

        private void build(Rect b) {
            float L = b.left - EXT_X * d, R = b.right + EXT_X * d;
            float barTop = b.top - BAR_PAD * d, barBot = b.bottom + BAR_PAD * d;
            float top = barTop + INSET_Y * d, bot = barBot - INSET_Y * d;
            float rad = Math.min(RADIUS * d, (bot - top) / 2f), k = 0.5523f, cx = (L + R) / 2f;
            path.reset();
            path.moveTo(L, bot - rad);
            if (!middle) {
                path.lineTo(L, top + rad);
                path.cubicTo(L, top + rad * (1 - k), L + rad * (1 - k), top, L + rad, top);
                path.lineTo(R - rad, top);
                path.cubicTo(R - rad * (1 - k), top, R, top + rad * (1 - k), R, top + rad);
            } else {
                float xj = cx - (L + rad);                                   // مكان التقاء الزاوية بمنحنى الشريط
                float yj = barTop - bumpH(xj) + INSIDE * d;
                path.lineTo(L, yj + rad);
                path.cubicTo(L, yj + rad * (1 - k), L + rad * (1 - k), yj, L + rad, yj);
                for (int i = 0; i <= N; i++) if (bx[i] < xj) path.lineTo(cx - bx[i], barTop - bhh[i] + INSIDE * d);
                for (int i = N; i >= 0; i--) if (bx[i] < xj) path.lineTo(cx + bx[i], barTop - bhh[i] + INSIDE * d);
                path.lineTo(R - rad, yj);
                path.cubicTo(R - rad * (1 - k), yj, R, yj + rad * (1 - k), R, yj + rad);
            }
            path.lineTo(R, bot - rad);
            path.cubicTo(R, bot - rad * (1 - k), R - rad * (1 - k), bot, R - rad, bot);
            path.lineTo(L + rad, bot);
            path.cubicTo(L + rad * (1 - k), bot, L, bot - rad * (1 - k), L, bot - rad);
            path.close();
        }

        @Override protected void onBoundsChange(Rect b) {
            if (glow != null) { glow.recycle(); glow = null; }
            if (b.isEmpty()) return;
            build(b);
            path.computeBounds(pb, true);
            float w = pb.width(), h = pb.height();
            body.setShader(new LinearGradient(0f, pb.top, 0f, pb.bottom,
                    dark ? new int[]{0x4DF0D27A, 0x40E4C45B, 0x33D9B24A} : new int[]{0xFFFFF3D3, 0xFFFEEAC2, 0xFFFBE4BE},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            // لمعة ناعمة في الأعلى كما في المرجع
            sheen.setShader(new RadialGradient(pb.centerX(), pb.top + h * 0.22f, w * 0.55f,
                    dark ? 0x18FFFFFF : 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            float g = GLOW * d;
            glow = Bitmap.createBitmap((int) (w + 2 * g), (int) (h + 2 * g), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(glow);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(dark ? 0x33E4C45B : 0xFFFEEAC4);
            p.setShadowLayer(8f * d, 0f, 1.5f * d, dark ? 0x40E4C45B : 0x55EBCB8A);
            Path gp = new Path(path);
            gp.offset(g - pb.left, g - pb.top);
            c.drawPath(gp, p);
        }

        @Override public void draw(Canvas c) {
            if (getBounds().isEmpty()) return;
            if (glow != null) {
                float g = GLOW * d;
                c.drawBitmap(glow, pb.left - g, pb.top - g, null);
            }
            c.drawPath(path, body);
            c.drawPath(path, sheen);
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
