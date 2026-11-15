package com.babylon.chat.iq;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
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
    private final Paint fill1 = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill3 = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int bw = -1, bh = -1;

    // نسبة المنطقة المرسومة من ارتفاع الشاشة في اللقطة (الهيدر أعلاها ≈ 0.11 والشريط السفلي ≈ 0.12)
    private static final float TOP = 0.11f, SPAN = 0.77f;

    HomeBg(boolean dark, float density) {
        this.dark = dark;
        this.d = density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
    }

    private static float yy(float f, int h) { return (f - TOP) / SPAN * h; }

    @Override protected void onBoundsChange(Rect b) { build(b.width(), b.height()); }

    private void build(int w, int h) {
        if (w <= 0 || h <= 0) return;
        bw = w; bh = h;
        base.setColor(dark ? 0xFF0E1621 : 0xFFF4F6FA);   // نفس لون الهيدر وشريط التنقل (pageBg) فلا تظهر حدود

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
    }

    @Override public void draw(Canvas c) {
        Rect b = getBounds();
        if (b.width() != bw || b.height() != bh) build(b.width(), b.height());
        if (bw <= 0) return;
        c.save();
        c.translate(b.left, b.top);
        c.drawRect(0, 0, bw, bh, base);
        c.drawPath(arc, fill);
        c.drawPath(band1, fill1);
        c.drawPath(band3, fill3);
        c.drawPath(l1, line);
        c.drawPath(l2, line);
        c.drawPath(l3, line2);
        c.drawPath(l4, line2);
        c.restore();
    }

    @Override public void setAlpha(int a) { }
    @Override public void setColorFilter(ColorFilter cf) { }
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
}
