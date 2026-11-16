package com.babylon.chat.iq;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * خلفية صفحة المحادثات: أرضية فاتحة ناعمة + موجات حريرية ذهبية شفافة في النصف السفلي
 * (موجة عريضة من اليمين إلى اليسار في الوسط، قوس سفلي، وخطّان ذهبيان رفيعان في الزاوية السفلية اليمنى).
 * الإحداثيات نسب من أبعاد الشاشة الكاملة كما في اللقطة المرجعية، وتُحوَّل إلى أبعاد المنطقة المرسومة.
 */
final class HomeBg extends Drawable {
    private final boolean dark;
    private final float d;
    private final Paint base = new Paint();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line2 = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path band1 = new Path(), arc = new Path(), band3 = new Path();
    private final Path l1 = new Path(), l2 = new Path(), l3 = new Path(), l4 = new Path();
    private final Path l5 = new Path(), l6 = new Path(), band5 = new Path();   // موجة خفيفة إضافية في الجهة اليسرى من منتصف الصفحة
    private final Paint fill5 = new Paint(Paint.ANTI_ALIAS_FLAG), line5 = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path e5 = new Path(), e6 = new Path();   // حافتان هلاليتان رفيعتان تتلاشيان من الطرفين (مثل حواف الموجات في الرسمة)
    private final Paint fill1 = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill3 = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int bw = -1, bh = -1;

    // نسبة المنطقة المرسومة من ارتفاع الشاشة في اللقطة (الهيدر أعلاها ≈ 0.11 والشريط السفلي ≈ 0.12)
    private static final float TOP = 0.11f, SPAN = 0.77f;

    // الوضع النهاري (والليلي بنسخة مشتقة منها): الخلفية الفعلية مأخوذة بكسلاً ببكسل من اللقطة المرجعية (res/drawable-nodpi/home_bg_light.webp)،
    // بعرض الشاشة ومحاذاة للأسفل؛ ما فوقها لون الصفحة نفسه.
    private static Bitmap art;
    private final Paint artPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Bitmap photo;

    HomeBg(Context ctx, boolean dark, float density) {
        this(dark, density, dark ? loadArtDark(ctx) : loadArt(ctx));
    }

    private static synchronized Bitmap loadArt(Context ctx) {
        if (art == null) {
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inScaled = false;
                art = BitmapFactory.decodeResource(ctx.getResources(), R.drawable.home_bg_light, o);
            } catch (Throwable ignored) { }
        }
        return art;
    }

    private static Bitmap artDark;

    /**
     * الوضع الليلي: نفس رسمة النهار (نفس الخطوط والنقوش والمواضع) لكن بالذهبي فوق أرضية الليل؛
     * شفافية كل بكسل مأخوذة من «ذهبيّته» (R−B) في الرسمة النهارية، وما سواها يبقى لون الصفحة.
     */
    private static synchronized Bitmap loadArtDark(Context ctx) {
        if (artDark == null) {
            Bitmap src = loadArt(ctx);
            if (src == null) return null;
            try {
                int w = src.getWidth(), h = src.getHeight();
                int[] px = new int[w * h];
                src.getPixels(px, 0, w, 0, 0, w, h);
                final int gold = 0xE4C45B;
                float[] al = new float[w * h];
                for (int i = 0; i < px.length; i++) {
                    int p = px[i];
                    int g = ((p >> 16) & 0xFF) - (p & 0xFF);          // R − B
                    float t = Math.max(0f, Math.min(1f, (g + 2f) / 52f));
                    al[i] = (float) Math.pow(t, 1.15);
                }
                // تنعيم: طمس الشفافية لإزالة التحبّب والحواف الحادة (الوضع الليلي فقط)
                float[] tmp = new float[w * h];
                for (int k = 0; k < 2; k++) {
                    boxBlur(al, tmp, w, h, 5, true);
                    boxBlur(tmp, al, w, h, 5, false);
                }
                for (int i = 0; i < px.length; i++) {
                    int a = (int) (al[i] * 0.40f * 255f);
                    px[i] = (a << 24) | gold;
                }
                Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                out.setPixels(px, 0, w, 0, 0, w, h);
                artDark = out;
            } catch (Throwable ignored) { }
        }
        return artDark;
    }

    /** طمس صندوقي بسيط (أفقي أو عمودي) بنصف قطر r باستخدام مجموع متحرك. */
    private static void boxBlur(float[] src, float[] dst, int w, int h, int r, boolean horiz) {
        int outer = horiz ? h : w, len = horiz ? w : h;
        int stepIn = horiz ? 1 : w;
        float inv = 1f / (2 * r + 1);
        for (int o = 0; o < outer; o++) {
            int base = horiz ? o * w : o;
            float sum = 0;
            for (int i = -r; i <= r; i++) sum += src[base + Math.max(0, Math.min(len - 1, i)) * stepIn];
            for (int i = 0; i < len; i++) {
                dst[base + i * stepIn] = sum * inv;
                int add = Math.min(len - 1, i + r + 1), sub = Math.max(0, i - r);
                sum += src[base + add * stepIn] - src[base + sub * stepIn];
            }
        }
    }

    private HomeBg(boolean dark, float density, Bitmap photo) {
        this.photo = photo;
        this.dark = dark;
        this.d = density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
    }

    private static float yy(float f, int h) { return (f - TOP) / SPAN * h; }
    private static float yw(float f, int h) { return (f - TOP) / (1f - TOP) * h; }   // الصفحة تمتد من الهيدر حتى أسفل الشاشة

    @Override protected void onBoundsChange(Rect b) { build(b.width(), b.height()); }

    private void build(int w, int h) {
        if (w <= 0 || h <= 0) return;
        bw = w; bh = h;
        base.setColor(dark ? 0xFF0E1621 : 0xFFF8F9FD);   // نفس لون الهيدر وشريط التنقل (pageBg) فلا تظهر حدود

        // الموجة الحريرية الوسطى: خط ذهبي رفيع من حافة اليمين إلى اليسار + شريط كريمي يضيق نحو اليمين
        l1.reset();
        l1.moveTo(w, yy(0.470f, h));
        l1.cubicTo(w * 0.72f, yy(0.552f, h), w * 0.30f, yy(0.562f, h), 0, yy(0.650f, h));
        band1.reset();
        band1.moveTo(w, yy(0.470f, h));
        band1.cubicTo(w * 0.72f, yy(0.552f, h), w * 0.30f, yy(0.562f, h), 0, yy(0.650f, h));
        band1.lineTo(0, yy(0.712f, h));
        band1.cubicTo(w * 0.36f, yy(0.620f, h), w * 0.76f, yy(0.545f, h), w, yy(0.470f, h));
        band1.close();

        // القوس السفلي: يرتفع من اليسار نحو اليمين وتحته تعبئة كريمية خفيفة جداً
        arc.reset();
        arc.moveTo(0, yy(0.790f, h));
        arc.cubicTo(w * 0.30f, yy(0.705f, h), w * 0.66f, yy(0.690f, h), w, yy(0.628f, h));
        l2.reset();
        l2.moveTo(0, yy(0.790f, h));
        l2.cubicTo(w * 0.30f, yy(0.705f, h), w * 0.66f, yy(0.690f, h), w, yy(0.628f, h));
        arc.lineTo(w, h);
        arc.lineTo(0, h);
        arc.close();

        // الزاوية السفلية اليمنى: خطّان ذهبيان رفيعان وشريط كريمي بينهما
        l3.reset();
        l3.moveTo(w, yy(0.742f, h));
        l3.cubicTo(w * 0.86f, yy(0.775f, h), w * 0.68f, yy(0.825f, h), w * 0.60f, yy(0.84f, h));
        l4.reset();
        l4.moveTo(w, yy(0.785f, h));
        l4.cubicTo(w * 0.92f, yy(0.815f, h), w * 0.84f, yy(0.845f, h), w * 0.79f, yy(0.84f, h));
        band3.reset();
        band3.moveTo(w, yy(0.742f, h));
        band3.cubicTo(w * 0.86f, yy(0.775f, h), w * 0.68f, yy(0.825f, h), w * 0.60f, yy(0.84f, h));
        band3.lineTo(w * 0.79f, yy(0.84f, h));
        band3.cubicTo(w * 0.84f, yy(0.845f, h), w * 0.92f, yy(0.815f, h), w, yy(0.785f, h));
        band3.close();

        // موجة خفيفة إضافية: تدخل من الحافة اليسرى وترتفع نحو الوسط ثم تتلاشى (شريط كريمي شفاف + خطّان ذهبيان رفيعان).
        // الصفحة تمتد الآن حتى أسفل الشاشة، فنحوّل نسب الشاشة بـ (f−TOP)/(1−TOP) لتبقى الموجة في مكانها المقصود.
        l5.reset();
        l5.moveTo(0, yw(0.520f, h));
        l5.cubicTo(w * 0.22f, yw(0.508f, h), w * 0.46f, yw(0.470f, h), w * 0.70f, yw(0.395f, h));
        l6.reset();
        l6.moveTo(0, yw(0.575f, h));
        l6.cubicTo(w * 0.20f, yw(0.562f, h), w * 0.40f, yw(0.520f, h), w * 0.56f, yw(0.470f, h));
        band5.reset();
        band5.moveTo(0, yw(0.520f, h));
        band5.cubicTo(w * 0.22f, yw(0.508f, h), w * 0.46f, yw(0.470f, h), w * 0.70f, yw(0.395f, h));
        band5.lineTo(w * 0.56f, yw(0.470f, h));
        band5.cubicTo(w * 0.40f, yw(0.520f, h), w * 0.20f, yw(0.562f, h), 0, yw(0.575f, h));
        band5.close();
        // الحافة الذهبية بدل الخط ذي السماكة الثابتة: هلال يبدأ وينتهي بسماكة صفر فيبدو كحافة حرير ناعمة
        crescent(e5, 0, yw(0.520f, h), w * 0.22f, yw(0.508f, h), w * 0.46f, yw(0.470f, h), w * 0.70f, yw(0.395f, h), 2.6f * d);
        crescent(e6, 0, yw(0.575f, h), w * 0.20f, yw(0.562f, h), w * 0.40f, yw(0.520f, h), w * 0.56f, yw(0.470f, h), 1.8f * d);

        // ألوان التعبئة والخطوط (ذهبي كريمي بشفافية منخفضة)
        final int gold = dark ? 0xE4C45B : 0xD9B25E, goldDeep = dark ? 0xB98B23 : 0xD0A533;
        fill1.setShader(new LinearGradient(0, 0, w, 0,
                new int[]{(dark ? 0x1E : 0x26) << 24 | gold, (dark ? 0x0C : 0x10) << 24 | gold, 0x00000000 | gold},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        fill.setShader(new LinearGradient(0, yy(0.63f, h), 0, h,
                new int[]{(dark ? 0x0C : 0x12) << 24 | gold, 0x00000000 | gold},   // يتلاشى نحو الأسفل فلا حدّ فوق شريط التنقل
                null, Shader.TileMode.CLAMP));
        line2.setStyle(Paint.Style.STROKE);
        line2.setStrokeCap(Paint.Cap.ROUND);
        line2.setStrokeWidth(1.1f * d);
        line2.setShader(new LinearGradient(w, yy(0.742f, h), w * 0.60f, yy(0.84f, h),
                new int[]{(dark ? 0x48 : 0x62) << 24 | goldDeep, 0x00000000 | goldDeep},
                null, Shader.TileMode.CLAMP));
        fill3.setShader(new LinearGradient(w, yy(0.742f, h), w * 0.6f, yy(0.84f, h),
                new int[]{(dark ? 0x1A : 0x28) << 24 | gold, 0x00000000 | gold},
                null, Shader.TileMode.CLAMP));
        line.setStrokeWidth(1.1f * d);
        line.setShader(new LinearGradient(w, 0, 0, 0,
                new int[]{(dark ? 0x48 : 0x62) << 24 | goldDeep, (dark ? 0x24 : 0x34) << 24 | goldDeep, 0x00000000 | goldDeep},
                new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        // الموجة الإضافية: تعبئة حريرية ناعمة تتلاشى تدريجياً + حافة هلالية رفيعة تتلاشى عند طرفيها (نفس طابع بقية الموجات)
        fill5.setShader(new LinearGradient(0, 0, w * 0.7f, 0,
                new int[]{(dark ? 0x0E : 0x16) << 24 | gold, (dark ? 0x06 : 0x08) << 24 | gold, 0x00000000 | gold},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        line5.setStyle(Paint.Style.FILL);
        line5.setShader(new LinearGradient(0, 0, w * 0.7f, 0,
                new int[]{(dark ? 0x30 : 0x4A) << 24 | goldDeep, (dark ? 0x16 : 0x24) << 24 | goldDeep, 0x00000000 | goldDeep},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
    }

    private static void crescent(Path p, float x0, float y0, float c1x, float c1y, float c2x, float c2y, float x1, float y1, float t) {
        p.reset();
        p.moveTo(x0, y0);
        p.cubicTo(c1x, c1y, c2x, c2y, x1, y1);
        p.cubicTo(c2x, c2y + t * 1.3f, c1x, c1y + t * 1.3f, x0, y0);
        p.close();
    }

    @Override public void draw(Canvas c) {
        Rect b = getBounds();
        if (b.width() != bw || b.height() != bh) build(b.width(), b.height());
        if (bw <= 0) return;
        c.save();
        c.translate(b.left, b.top);
        c.drawRect(0, 0, bw, bh, base);
        if (photo != null) {
            float sc = bw / (float) photo.getWidth(), ph = photo.getHeight() * sc;
            c.drawBitmap(photo, null, new RectF(0, bh - ph, bw, bh), artPaint);
            drawExtraWave(c);
            c.restore();
            return;
        }
        c.drawPath(arc, fill);
        c.drawPath(band1, fill1);
        c.drawPath(band3, fill3);
        c.drawPath(l1, line);
        c.drawPath(l2, line);
        c.drawPath(l3, line2);
        c.drawPath(l4, line2);
        drawExtraWave(c);
        c.restore();
    }

    private void drawExtraWave(Canvas c) {
        c.drawPath(band5, fill5);
        c.drawPath(e5, line5);
        c.drawPath(e6, line5);
    }

    @Override public void setAlpha(int a) { }
    @Override public void setColorFilter(ColorFilter cf) { }
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
}
