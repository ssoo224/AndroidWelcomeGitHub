package com.babylon.chat.iq;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * نقوش الغلاف — نقل من renderPattern() في الـ HTML:
 *   .pf-cover .pattern-layer : شبكة 6 أعمدة، ارتفاع الصف 46dp، حجم الرمز 22dp،
 *   لون أبيض بشفافية 0.2، وكل رمز مائل بزاوية ((i*37)%50)-25 درجة.
 *   الرموز (الثيم البابلي): gate, star8, bull, tablet, crescent, jar
 *   اختيار الرمز: symbols[(i*5 + floor(i/5)*2) % 6]
 * الرموز مرسومة بنفس مسارات svgIcon (viewBox 24×24، stroke 1.8، أطراف مستديرة).
 */
public final class CoverPattern extends View {

    private static final int COUNT = 24, COLS = 6;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path[] icons = new Path[6];
    private final Path tmp = new Path();
    private final android.graphics.Matrix mx = new android.graphics.Matrix();
    private final float d;

    public CoverPattern(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(0x33FFFFFF);            // #fff × opacity .2
        setWillNotDraw(false);
        buildIcons();
    }

    private static void arc(Path p, float cx, float cy, float r, float a0, float sweep) {
        p.arcTo(new RectF(cx - r, cy - r, cx + r, cy + r), a0, sweep);
    }

    private void buildIcons() {
        // 0 gate
        Path g = new Path();
        g.moveTo(4, 21); g.lineTo(4, 8); g.lineTo(7, 8); g.lineTo(7, 5); g.lineTo(9, 5); g.lineTo(9, 8);
        g.lineTo(15, 8); g.lineTo(15, 5); g.lineTo(17, 5); g.lineTo(17, 8); g.lineTo(20, 8); g.lineTo(20, 21);
        g.moveTo(9, 21); g.lineTo(9, 15); arc(g, 12, 15, 3, 180, 180); g.lineTo(15, 21);
        g.moveTo(4, 12); g.lineTo(9, 12); g.moveTo(15, 12); g.lineTo(20, 12);
        icons[0] = g;
        // 1 star8
        Path s = new Path();
        s.moveTo(12, 3); s.lineTo(12, 21); s.moveTo(3, 12); s.lineTo(21, 12);
        s.moveTo(5.6f, 5.6f); s.lineTo(18.4f, 18.4f); s.moveTo(18.4f, 5.6f); s.lineTo(5.6f, 18.4f);
        s.addCircle(12, 12, 3, Path.Direction.CW);
        icons[1] = s;
        // 2 bull
        Path b = new Path();
        b.moveTo(3, 5); b.cubicTo(3, 10, 6, 12, 8, 12);
        b.moveTo(21, 5); b.cubicTo(21, 10, 18, 12, 16, 12);
        b.moveTo(8, 12); b.cubicTo(7, 15, 8, 19, 12, 21); b.cubicTo(16, 19, 17, 15, 16, 12);
        b.moveTo(10, 15.5f); b.lineTo(10.01f, 15.5f); b.moveTo(14, 15.5f); b.lineTo(14.01f, 15.5f);
        icons[2] = b;
        // 3 tablet
        Path t = new Path();
        t.addRoundRect(new RectF(5, 3, 19, 21), 2, 2, Path.Direction.CW);
        float[][] ch = {{9, 8}, {13, 8}, {9, 14}, {13, 14}};
        for (float[] c : ch) { t.moveTo(c[0], c[1]); t.lineTo(c[0] + 2, c[1] + 1); t.lineTo(c[0], c[1] + 2); }
        icons[3] = t;
        // 4 crescent
        Path m = new Path();
        m.moveTo(20, 14.5f);
        m.cubicTo(17.8f, 18.9f, 12.5f, 20.7f, 8.1f, 18.5f);
        m.cubicTo(3.7f, 16.3f, 1.9f, 11f, 4.1f, 6.6f);
        m.cubicTo(5.4f, 4.4f, 7.3f, 3.9f, 9.5f, 4);
        m.cubicTo(8.3f, 8.5f, 12.2f, 13.5f, 20, 14.5f);
        m.close();
        icons[4] = m;
        // 5 jar
        Path j = new Path();
        j.moveTo(10, 3); j.lineTo(14, 3);
        j.moveTo(10.5f, 3); j.lineTo(10.5f, 6);
        j.cubicTo(7, 7, 5.5f, 10, 5.5f, 14); j.cubicTo(5.5f, 18, 8, 21, 12, 21);
        j.cubicTo(16, 21, 18.5f, 18, 18.5f, 14); j.cubicTo(18.5f, 10, 17, 7, 13.5f, 6); j.lineTo(13.5f, 3);
        j.moveTo(7, 12); j.lineTo(17, 12);
        icons[5] = j;
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float cellW = w / (float) COLS, rowH = 46f * d, size = 22f * d, k = size / 24f;
        paint.setStrokeWidth(1.8f * k);   // 1.8 وحدة SVG
        int rows = (int) Math.ceil(h / rowH);
        int n = Math.max(COUNT, rows * COLS);
        for (int i = 0; i < n; i++) {
            int col = i % COLS, row = i / COLS;
            float cx = cellW * (col + 0.5f), cy = rowH * (row + 0.5f);
            if (cy - rowH / 2f > h) break;
            int idx = (i * 5 + (i / 5) * 2) % 6;
            mx.reset();
            mx.postScale(k, k);
            mx.postTranslate(-size / 2f, -size / 2f);
            mx.postRotate(((i * 37) % 50) - 25);
            mx.postTranslate(cx, cy);
            tmp.reset();
            icons[idx].transform(mx, tmp);
            c.drawPath(tmp, paint);
        }
    }
}
