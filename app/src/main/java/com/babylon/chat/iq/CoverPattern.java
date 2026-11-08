package com.babylon.chat.iq;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import androidx.core.graphics.PathParser;

import java.util.HashMap;
import java.util.Map;

/**
 * نقوش الغلاف — نقل من renderPattern(el, theme, count) في الـ HTML:
 *   .pf-cover .pattern-layer : شبكة 6 أعمدة، ارتفاع الصف 46dp، حجم الرمز 22dp، أبيض بشفافية 0.2   (24 رمزاً)
 *   .cover-opt .pattern-layer: شبكة 4 أعمدة، ارتفاع الصف 26dp، حجم الرمز 13dp، أبيض بشفافية 0.24 (12 رمزاً)
 *   اختيار الرمز: symbols[(i*5 + floor(i/5)*2) % symbols.length] — ورموز كل غلاف من CoverThemes.
 * مسارات الرموز هي نفسها مسارات ICONS في الـ HTML (viewBox 24×24، stroke 1.8، أطراف مستديرة)
 * وتُقرأ بـ PathParser فلا تحتاج رسماً يدوياً لكل رمز.
 * الرمز منتصب (بدون ميلان) ومتمركز حسب حدوده الفعلية داخل خليّته.
 */
public final class CoverPattern extends View {

    // ---------------------------------------------------------------- مسارات الرموز (ICONS في الـ HTML)
    private static final class Icon {
        final Path path = new Path();
        float cx, cy;
    }

    private static final Map<String, Icon> CACHE = new HashMap<>();

    private static synchronized Icon icon(String name) {
        Icon ic = CACHE.get(name);
        if (ic != null) return ic;
        ic = new Icon();
        Path p = ic.path;
        switch (name) {
            case "gate":
                add(p, "M4 21V8h3V5h2v3h6V5h2v3h3v13M9 21v-6a3 3 0 0 1 6 0v6M4 12h5M15 12h5"); break;
            case "star8":
                add(p, "M12 3v18M3 12h18M5.6 5.6l12.8 12.8M18.4 5.6 5.6 18.4");
                p.addCircle(12, 12, 3, Path.Direction.CW); break;
            case "bull":
                add(p, "M3 5c0 5 3 7 5 7M21 5c0 5-3 7-5 7M8 12c-1 3 0 7 4 9 4-2 5-6 4-9M10 15.500h.01M14 15.500h.01"); break;
            case "tablet":
                p.addRoundRect(new RectF(5, 3, 19, 21), 2, 2, Path.Direction.CW);
                add(p, "m9 8 2 1-2 1M13 8l2 1-2 1M9 14l2 1-2 1M13 14l2 1-2 1"); break;
            case "crescent":
                add(p, "M20 14.5A8.5 8.5 0 1 1 9.5 4a7 7 0 0 0 10.5 10.5z"); break;
            case "jar":
                add(p, "M10 3h4M10.500 3v3C7 7 5.500 10 5.500 14c0 4 2.500 7 6.500 7s6.500-3 6.500-7c0-4-1.500-7-5-8V3M7 12h10"); break;
            case "arch":
                add(p, "M5 22V11a7 7 0 0 1 14 0v11M9 22V12a3 3 0 0 1 6 0v10M3 22h18"); break;
            case "sun":
                p.addCircle(12, 12, 4, Path.Direction.CW);
                add(p, "M12 2v3M12 19v3M2 12h3M19 12h3M5 5l2 2M17 17l2 2M19 5l-2 2M7 17l-2 2"); break;
            case "ziggurat":
                add(p, "M2 21h20M4 21v-4h16v4M7 17v-4h10v4M10 13V9h4v4M12 9V6"); break;
            case "lyre":
                add(p, "M6 3c-2 6 0 12 6 14 6-2 8-8 6-14M6 3h12M10 6v13M14 6v13M8 21h8"); break;
            case "reeds":
                add(p, "M8 22V8M8 8c-1-2 0-4 0-6 1 2 1 4 0 6M13 22V5M13 5c-1-2 0-3 0-4 1 1 1 3 0 4M18 22V10M18 10c-1-2 0-3 0-5 1 2 1 3 0 5"); break;
            case "minaret":
                add(p, "M12 2v3M12 5c-2.5 0-4 .8-4 2s1.500 2 4 2 4 .8 4 2-1.500 2-4 2-5 .8-5 2 2 2 5 2 5 .8 5 2M5 22h14"); break;
            case "palm":
                add(p, "M12 22V10M12 10c-1-3-4-4-7-3M12 10c1-3 4-4 7-3M12 10c-3 0-5 2-6 5M12 10c3 0 5 2 6 5M12 10V4"); break;
            case "wave":
                add(p, "M2 9c3-3 5 3 8 0s5 3 8 0 3 0 4-1M2 16c3-3 5 3 8 0s5 3 8 0 3 0 4-1"); break;
            case "boat":
                add(p, "M2 15c4 4 16 4 20 0M5 15 3 9M19 15l2-6M8 11c1-2 3-3 4-3s3 1 4 3"); break;
            case "fish":
                add(p, "M3 12c3-5 9-6 13-2l5-3v10l-5-3c-4 4-10 3-13-2zM8 11h.01"); break;
            default:
                break;
        }
        RectF r = new RectF();
        p.computeBounds(r, true);
        ic.cx = r.centerX();
        ic.cy = r.centerY();
        CACHE.put(name, ic);
        return ic;
    }

    private static void add(Path into, String pathData) {
        Path q = PathParser.createPathFromPathData(pathData);
        if (q != null) into.addPath(q);
    }

    // ---------------------------------------------------------------- العرض
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path tmp = new Path();
    private final Matrix mx = new Matrix();
    private final float d;
    private final int cols, minCount;
    private final float rowDp, sizeDp;
    private CoverThemes.Theme theme = CoverThemes.ALL[0];
    private Icon[] cur;

    /** نقوش غلاف الملف الشخصي (.pf-cover): 6 أعمدة، صف 46dp، رمز 22dp، شفافية .2 */
    public CoverPattern(Context c) {
        this(c, 6, 46f, 22f, 24, 0.2f);
    }

    /** نقوش معاينة الغلاف في نافذة الاختيار (.cover-opt): CoverPattern(ctx, 4, 26, 13, 12, 0.24f) */
    public CoverPattern(Context c, int cols, float rowDp, float sizeDp, int count, float opacity) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        this.cols = cols; this.rowDp = rowDp; this.sizeDp = sizeDp; this.minCount = count;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor((Math.round(255f * opacity) << 24) | 0x00FFFFFF);   // #fff × opacity
        setWillNotDraw(false);
        loadIcons();
    }

    public void setTheme(CoverThemes.Theme t) {
        if (t == null || t == theme) return;
        theme = t;
        loadIcons();
        invalidate();
    }

    private void loadIcons() {
        cur = new Icon[theme.symbols.length];
        for (int i = 0; i < cur.length; i++) cur[i] = icon(theme.symbols[i]);
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0 || cur == null || cur.length == 0) return;
        float cellW = w / (float) cols, rowH = rowDp * d, size = sizeDp * d, k = size / 24f;
        paint.setStrokeWidth(1.8f * k);   // 1.8 وحدة SVG
        int rows = (int) Math.ceil(h / rowH);
        int n = Math.max(minCount, rows * cols);
        for (int i = 0; i < n; i++) {
            int col = i % cols, row = i / cols;
            float cx = cellW * (col + 0.5f), cy = rowH * (row + 0.5f);
            if (cy - rowH / 2f > h) break;
            Icon ic = cur[(i * 5 + (i / 5) * 2) % cur.length];
            mx.reset();
            mx.postTranslate(-ic.cx, -ic.cy);
            mx.postScale(k, k);
            mx.postTranslate(cx, cy);
            tmp.reset();
            ic.path.transform(mx, tmp);
            c.drawPath(tmp, paint);
        }
    }
}
