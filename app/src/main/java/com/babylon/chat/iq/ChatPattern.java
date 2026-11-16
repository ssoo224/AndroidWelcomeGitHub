package com.babylon.chat.iq;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * نقوش المحادثة — نقل من renderPattern(el, theme, count) و .pattern-layer في الـ HTML:
 *   شبكة بعدد أعمدة --cols وارتفاع صف --cell وحجم رمز --glyph، تُملأ بـ count رمزاً بالترتيب
 *   symbols[(i*5 + floor(i/5)*2) % n] وبدوران ((i*37)%50)-25 درجة لكل رمز،
 *   لون --pattern-color بشفافية --pattern-opacity، مع filter:grayscale(1) على الرموز.
 *   الصفحة RTL فالعمود الأول عند اليمين. الدوران حول مركز مربّع الرمز (12,12).
 * الأحجام الافتراضية (غرفة المحادثة والمعاينة): 5 أعمدة، صف 64dp، رمز 26dp.
 * مصغّرات الثيمات في الشريط: 3 أعمدة، صف 34dp، رمز 15dp، 24 رمزاً.
 */
public final class ChatPattern extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path tmp = new Path();
    private final Matrix mx = new Matrix();
    private final float d;
    private int cols = 5, count = 80;
    private float cellDp = 64f, glyphDp = 26f;
    private String[] symbols = {"gate"};

    public ChatPattern(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        setWillNotDraw(false);
    }

    public ChatPattern grid(int cols, float cellDp, float glyphDp, int count) {
        this.cols = cols; this.cellDp = cellDp; this.glyphDp = glyphDp; this.count = count;
        invalidate();
        return this;
    }

    /** color = --pattern-color ، opacity = --pattern-opacity (يُطبَّق grayscale(1) هنا) */
    public ChatPattern style(String[] symbols, int color, float opacity) {
        this.symbols = symbols;
        paint.setColor(patternColor(color, opacity));
        invalidate();
        return this;
    }

    @Override protected void onDraw(Canvas c) {
        drawGrid(c, getWidth(), getHeight(), d, cols, cellDp, glyphDp, count, symbols, paint, tmp, mx);
    }

    /** رسم الشبكة (مشترك بين الـ View والـ Drawable). */
    static void drawGrid(Canvas c, int w, int h, float d, int cols, float cellDp, float glyphDp, int count,
                         String[] symbols, Paint paint, Path tmp, Matrix mx) {
        if (w <= 0 || h <= 0 || symbols.length == 0) return;
        float cellW = w / (float) cols, cellH = cellDp * d, size = glyphDp * d, k = size / 24f;
        paint.setStrokeWidth(1.8f * k);
        for (int i = 0; i < count; i++) {
            int col = i % cols, row = i / cols;
            float cx = w - cellW * (col + 0.5f), cy = cellH * (row + 0.5f);   // RTL: العمود الأول عند اليمين
            if (cy - size / 2f > h) break;
            String sym = symbols[(i * 5 + (i / 5) * 2) % symbols.length];
            Path src = CoverPattern.pathOf(sym);
            mx.reset();
            mx.postTranslate(-12f, -12f);
            mx.postScale(k, k);
            mx.postRotate(((i * 37) % 50) - 25);
            mx.postTranslate(cx, cy);
            tmp.reset();
            src.transform(mx, tmp);
            c.drawPath(tmp, paint);
        }
    }

    /** لون النقش النهائي: grayscale(1) ثم الشفافية. */
    static int patternColor(int color, float opacity) {
        int g = ChatThemes.gray(color);
        return (Math.round(255f * Math.max(0f, Math.min(1f, opacity))) << 24) | (g & 0x00FFFFFF);
    }

    /** خلفية غرفة المحادثة: لون الخلفية + موجات حريرية (لكل الثيمات بلون الثيم) + طبقة النقوش (.chat-room + #chatPattern : 5 أعمدة، صف 64، رمز 26، 80 رمزاً). */
    public static final class Bg extends android.graphics.drawable.Drawable {
        private final Paint base = new Paint(), pp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint wf = new Paint(Paint.ANTI_ALIAS_FLAG), wl = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path tmp = new Path();
        private final Path t1 = new Path(), t2 = new Path(), b1 = new Path(), b2 = new Path();
        private final Path e1 = new Path(), e2 = new Path(), e3 = new Path();
        private final Matrix mx = new Matrix();
        private final float d;
        private final String[] symbols;
        private final boolean waves;
        private int lw = -1, lh = -1;

        public Bg(Context c, int baseColor, String[] symbols, int patternColor, float opacity) {
            this(c, baseColor, symbols, patternColor, opacity, 0, false, false);
        }

        /** waveColor = لون الثيم (accent/pattern) تُشتق منه تعبئة الموجات وخطوطها؛ dark يحدد شدّتها. */
        public Bg(Context c, int baseColor, String[] symbols, int patternColor, float opacity, int waveColor, boolean dark) {
            this(c, baseColor, symbols, patternColor, opacity, waveColor, dark, true);
        }

        private Bg(Context c, int baseColor, String[] symbols, int patternColor, float opacity, int waveColor, boolean dark, boolean waves) {
            d = c.getResources().getDisplayMetrics().density;
            this.symbols = symbols;
            this.waves = waves;
            base.setColor(baseColor);
            pp.setStyle(Paint.Style.STROKE);
            pp.setStrokeCap(Paint.Cap.ROUND);
            pp.setStrokeJoin(Paint.Join.ROUND);
            pp.setColor(patternColor(patternColor, opacity));
            int rgb = waveColor & 0x00FFFFFF;
            wf.setStyle(Paint.Style.FILL);
            wf.setColor(((int) ((dark ? 0.075f : 0.085f) * 255f) << 24) | rgb);
            wl.setStyle(Paint.Style.STROKE);
            wl.setStrokeCap(Paint.Cap.ROUND);
            wl.setStrokeWidth(1.1f * d);
            wl.setColor(dark ? (((int) (0.20f * 255f) << 24) | rgb) : 0xB3FFFFFF);   // نهاري: خط أبيض ناعم كما في اللقطة، ليلي: خط بلون الثيم
        }

        private void build(int w, int h) {
            lw = w; lh = h;
            // أعلى اليمين: سطح يلتفّ من الحافة العلوية، ثم سطح ثانٍ تحت الهيدر يتلاشى نحو الوسط
            t1.reset(); t1.moveTo(w, 0); t1.lineTo(0.773f * w, 0);
            t1.cubicTo(0.745f * w, 0.02f * h, 0.730f * w, 0.034f * h, 0.716f * w, 0.052f * h);
            t1.cubicTo(0.76f * w, 0.085f * h, 0.90f * w, 0.115f * h, w, 0.124f * h);
            t1.close();
            e1.reset(); e1.moveTo(0.773f * w, 0);
            e1.cubicTo(0.745f * w, 0.02f * h, 0.730f * w, 0.034f * h, 0.716f * w, 0.052f * h);
            t2.reset(); t2.moveTo(w, 0.124f * h);
            t2.cubicTo(0.88f * w, 0.185f * h, 0.72f * w, 0.225f * h, 0.624f * w, 0.274f * h);
            t2.cubicTo(0.80f * w, 0.31f * h, 0.93f * w, 0.34f * h, w, 0.36f * h);
            t2.close();
            e2.reset(); e2.moveTo(w, 0.124f * h);
            e2.cubicTo(0.88f * w, 0.185f * h, 0.72f * w, 0.225f * h, 0.624f * w, 0.274f * h);
            // أسفل اليسار: سطحان متداخلان يرتفعان من الحافة اليسرى وينزلان خلف شريط الكتابة
            b1.reset(); b1.moveTo(0, 0.724f * h);
            b1.cubicTo(0.10f * w, 0.752f * h, 0.20f * w, 0.820f * h, 0.284f * w, 0.872f * h);
            b1.lineTo(0.34f * w, h); b1.lineTo(0, h); b1.close();
            e3.reset(); e3.moveTo(0, 0.724f * h);
            e3.cubicTo(0.10f * w, 0.752f * h, 0.20f * w, 0.820f * h, 0.284f * w, 0.872f * h);
            b2.reset(); b2.moveTo(0, 0.807f * h);
            b2.cubicTo(0.12f * w, 0.808f * h, 0.22f * w, 0.840f * h, 0.277f * w, 0.872f * h);
            b2.lineTo(0.31f * w, h); b2.lineTo(0, h); b2.close();
        }

        @Override public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            if (b.isEmpty()) return;
            c.drawRect(b, base);
            c.save();
            c.clipRect(b);
            c.translate(b.left, b.top);
            if (waves) {
                if (b.width() != lw || b.height() != lh) build(b.width(), b.height());
                c.drawPath(t1, wf); c.drawPath(t2, wf);
                c.drawPath(b1, wf); c.drawPath(b2, wf);
                c.drawPath(e1, wl); c.drawPath(e2, wl); c.drawPath(e3, wl);
            }
            drawGrid(c, b.width(), b.height(), d, 5, 64f, 26f, 80, symbols, pp, tmp, mx);
            c.restore();
        }
        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
    }

    /** رمز واحد مستقل (.th-hero): glyphHtml(t.hero, 40) بلون التيار currentColor وستروك 1.8 */
    public static final class Glyph extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path tmp = new Path();
        private final Matrix mx = new Matrix();
        private final float d;
        private String name = "gate";

        public Glyph(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            setWillNotDraw(false);
        }

        public Glyph set(String name, int color) {
            this.name = name;
            p.setColor(color);
            invalidate();
            return this;
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float s = Math.min(w, h), k = s / 24f;
            p.setStrokeWidth(1.8f * k);
            mx.reset();
            mx.postScale(k, k);
            mx.postTranslate((w - s) / 2f, (h - s) / 2f);
            tmp.reset();
            CoverPattern.pathOf(name).transform(mx, tmp);
            c.drawPath(tmp, p);
        }
    }
}
