package com.babylon.chat.iq;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * .avatar في الـ HTML:
 *  - دائرة بتدرج 145° (ذهبي #f2d268→#b8861b أو أزرق #59cdf8→#1f63d8 حسب نغمة الحساب) + ظل ملوّن 0 3px 10px
 *  - .sring::after : حلقة مخروطية (conic-gradient) بسماكة 3px على بعد 5px مع توهّج ذهبي 7px (أو #4b5a6b للمشاهَدة)
 *  - .sb-plus : علامة + (نهاري: تدرج ذهبي وعلامة بيضاء، ليلي: دائرة بيضاء وعلامة #D4A017)
 *  - .cl-online : نقطة اتصال خضراء بحدّ بلون السطح وهالة 5px
 * العنصر يرسم خارج حدوده (الحلقة والتوهج والظل) لذلك يجب أن تكون الحاويات clipChildren=false.
 */
public class StoryAvatarView extends View implements StoryViewer.Spin {

    public static final int GOLD_A = 0xFFF2D268, GOLD_B = 0xFFB8861B;
    public static final int BLUE_A = 0xFF59CDF8, BLUE_B = 0xFF1F63D8;

    private String letter = "?";
    private Bitmap photo;
    private boolean blue, ring, seen, plus, online, dark;
    private float textSp = 20f;
    private int surface = 0xFF17212B;       // لون حدّ النقطة/علامة +
    private float plusAlpha = 1f;
    private boolean shadow = true;
    private float padPx = 0f;

    /** توهّج الحلقة: box-shadow:0 0 7px rgba(212,160,23,.55) في .avatar.sring::after */
    public static boolean GLOW = false;   // يُقصّ التوهج عند حدود شريط القصص (مربّعات)، لذا معطّل افتراضياً
    /** مؤشر الفتح: .story-launch-loading — قوس ذهبي دوّار (conic-gradient) 2.15s linear infinite */
    private static final int[] LAUNCH_COLORS = {0x00B98710, 0x00B98710, 0xFFB98710, 0xFFD4AF37, 0xFFFFE9A0, 0xFFD4AF37, 0x00D4AF37, 0x00D4AF37};
    private static final float[] LAUNCH_POS = {0f, 205f / 360f, 225f / 360f, 255f / 360f, 285f / 360f, 320f / 360f, 340f / 360f, 1f};
    private boolean launching;
    /** مدة دورة الوميض الذهبي (كانت 2150ms؛ أسرع بنحو 1.5×). */
    private static final int LAUNCH_MS = 1400;
    /** الحلقة الرمادية أثناء الفتح + الوميض الذهبي الموحَّد (بلا تدرّج) وزاوية قوسه. */
    private static final int LAUNCH_BASE = 0xFF4B5A6B, LAUNCH_GOLD = 0xFFD4AF37;
    private static final float LAUNCH_SWEEP = 80f;
    private final RectF arcRect = new RectF();
    /** تحويل الشكل إلى مصغّرة الهيدر أثناء الطيران: 0 = شكل الشريط، 1 = شكل ThumbView (حدّ 2dp ملاصق للصورة). */
    private float morph = 0f, morphK = 1f;
    /** إطار بلون السطح حول الحلقة يفصلها عن الدوائر المتراكبة خلفها (الهيدر). */
    private float haloPx = 0f, haloA = 1f;
    private boolean ringSmall;   // الحلقة المصغّرة عند طيّ الشريط: inset:-3px; padding:2px
    private float spin;
    private ValueAnimator spinAnim;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path plusPath = new Path();
    private final Matrix mx = new Matrix();

    public StoryAvatarView(Context c) {
        super(c);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        tp.setColor(Color.WHITE);
    }

    // ------------------------------------------------------------ إعدادات
    public StoryAvatarView set(String letter, Bitmap photo, boolean blue) {
        this.letter = (letter == null || letter.isEmpty()) ? "?" : letter;
        this.photo = photo; this.blue = blue;
        invalidate();
        return this;
    }
    public StoryAvatarView ring(boolean on, boolean seen) { this.ring = on; this.seen = seen; invalidate(); return this; }
    public StoryAvatarView plus(boolean on) { this.plus = on; invalidate(); return this; }
    public StoryAvatarView online(boolean on) { this.online = on; invalidate(); return this; }
    public StoryAvatarView theme(boolean dark, int surface) { this.dark = dark; this.surface = surface; invalidate(); return this; }
    public StoryAvatarView textSp(float sp) { this.textSp = sp; invalidate(); return this; }
    public StoryAvatarView shadow(boolean on) { this.shadow = on; invalidate(); return this; }
    public void setPlusAlpha(float a) { plusAlpha = a; invalidate(); }
    /**
     * هامش داخلي (dp) تُرسم فيه الحلقة/علامة + بدل الرسم خارج حدود الـ View؛
     * قطر الجسم = min(w,h) - 2*pad. يمنع قصّ الحلقة من أي حاوية (HorizontalScrollView/overlay/clipToPadding).
     */
    public StoryAvatarView pad(float dpv) { this.padPx = dp(dpv); invalidate(); return this; }

    /** يشغّل/يوقف القوس الدوّار فوق حلقة القصة (نفس .story-launch-loading في الـ HTML). */
    public StoryAvatarView launching(boolean on) {
        if (on == launching) return this;
        launching = on;
        if (on) startSpin(); else stopSpin();
        invalidate();
        return this;
    }
    public boolean isLaunching() { return launching; }
    @Override public void setLaunching(boolean on) { launching(on); }
    /** m: 0..1، k = مقلوب مقياس الطيران عند المصغّرة (حجم الشريط ÷ حجم المصغّرة). */
    public StoryAvatarView halo(float px, float alpha) {
        if (px != haloPx || alpha != haloA) { haloPx = px; haloA = alpha; invalidate(); }
        return this;
    }
    /** نصف قطر الدائرة الخارجية المرئية (الجسم + الحلقة إن وُجدت) بالبكسل. */
    public float outerRadiusPx() {
        float R = (Math.min(getWidth(), getHeight()) - 2f * padPx) / 2f;
        return (ring || morph > 0f) ? R + dp(ringSmall ? 3 : 5) * (1f - morph) : R;
    }
    public void setMorph(float m, float k) { if (m != morph || k != morphK) { morph = m; morphK = k; invalidate(); } }
    public StoryAvatarView ringSmall(boolean on) { if (ringSmall != on) { ringSmall = on; invalidate(); } return this; }

    private void startSpin() {
        stopSpin();
        spinAnim = ValueAnimator.ofFloat(0f, 360f);
        spinAnim.setDuration(LAUNCH_MS);
        spinAnim.setInterpolator(new LinearInterpolator());
        spinAnim.setRepeatCount(ValueAnimator.INFINITE);
        spinAnim.addUpdateListener(a -> { spin = (Float) a.getAnimatedValue(); invalidate(); });
        spinAnim.start();
    }
    private void stopSpin() {
        if (spinAnim != null) { spinAnim.removeAllUpdateListeners(); spinAnim.cancel(); spinAnim = null; }
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); if (launching && spinAnim == null) startSpin(); }
    @Override protected void onDetachedFromWindow() { stopSpin(); super.onDetachedFromWindow(); }

    public void copyFrom(StoryAvatarView o) {
        letter = o.letter; photo = o.photo; blue = o.blue; ring = o.ring; seen = o.seen; plus = o.plus;
        online = o.online; dark = o.dark; textSp = o.textSp; surface = o.surface; shadow = o.shadow;
        padPx = o.padPx;
        invalidate();
    }


    // ------------------------------------------------------------ حلقة التحميل المتقطعة (مثل تيليجرام)
    /** أطوال القطع (بالدرجات) والفراغات بينها؛ المجموع = 360. */
    private static final float[] DASH = {52f, 24f, 40f, 18f, 46f, 28f, 34f};
    private static final float[] GAP = {18f, 14f, 20f, 16f, 18f, 14f, 18f};
    private static final int[] DASH_COLORS = {0xFF8F6810, 0xFFE8D176, 0xFFB98B20, 0xFFE8D991, 0xFFB8860B, 0xFFE6C45B, 0xFF8F6810};
    private static final float[] DASH_POS = {0f, .18f, .34f, .48f, .65f, .82f, 1f};

    /**
     * يرسم حلقة من قطع ذهبية متفاوتة الطول تدور حول المركز؛ طول كل قطعة "يتنفس" (يقصر ويطول) أثناء الدوران.
     * @param spin زاوية الدوران بالدرجات (0..360)
     */
    static void drawDashRing(Canvas c, float cx, float cy, float mid, float thick, float spin, Paint p, RectF rect, Matrix m) {
        p.reset(); p.setAntiAlias(true);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(thick);
        SweepGradient sg = new SweepGradient(cx, cy, DASH_COLORS, DASH_POS);
        m.reset(); m.setRotate(spin - 90f, cx, cy);
        sg.setLocalMatrix(m);
        p.setShader(sg);
        rect.set(cx - mid, cy - mid, cx + mid, cy + mid);
        float cap = (float) Math.toDegrees(thick / mid);   // الرأس المستدير يمدّ كل طرف بنصف السماكة
        float rad = (float) Math.toRadians(spin);
        float a = spin - 90f;
        for (int i = 0; i < DASH.length; i++) {
            float breathe = 0.62f + 0.38f * (0.5f + 0.5f * (float) Math.sin(2f * rad + i * 1.7f));
            float len = DASH[i] * breathe - cap;
            if (len < 0.5f) len = 0.5f;
            float start = a + (DASH[i] - len - cap) / 2f + cap / 2f;   // القطعة المتقلصة تبقى متمركزة في موضعها
            c.drawArc(rect, start, len, false, p);
            a += DASH[i] + GAP[i];
        }
    }

    // ------------------------------------------------------------ نغمة الحساب (نفس toneOf في الـ HTML)
    /** FNV-1a ثم خلط؛ البت السابع يحدّد أزرق/ذهبي. */
    public static boolean toneBlue(String id) {
        if (true) return false;   // الجميع ذهبي متدرج (لا أزرق)
        if (id == null || id.isEmpty()) return false;
        int h = (int) 2166136261L;
        for (int i = 0; i < id.length(); i++) h = (h ^ id.charAt(i)) * 16777619;
        h ^= h >>> 15;
        h *= (int) 2246822507L;
        h ^= h >>> 13;
        return ((h >>> 7) & 1) == 1;
    }

    // ------------------------------------------------------------ أدوات
    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    /** linear-gradient(145deg, a, b) على مربع بجانب s. */
    static LinearGradient css145(float cx, float cy, float s, int a, int b) {
        double th = Math.toRadians(145);
        float dx = (float) Math.sin(th), dy = (float) -Math.cos(th);
        float len = (float) (Math.abs(s * Math.sin(th)) + Math.abs(s * Math.cos(th)));
        return new LinearGradient(cx - dx * len / 2, cy - dy * len / 2, cx + dx * len / 2, cy + dy * len / 2,
                a, b, Shader.TileMode.CLAMP);
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float d = Math.min(w, h) - 2f * padPx;
        float R = d / 2f, cx = w / 2f, cy = h / 2f;
        float thumbB = morphK * dp(2);                 // سماكة حدّ المصغّرة بوحدات غير مصغّرة
        float Rc = R - morph * thumbB;                  // نصف قطر المحتوى (الصورة/الحرف)
        int toneShadow = blue ? 0x471F63D8 : 0x47B8861B;   // rgba(.., .28)

        // الظل الناعم: 0 3px 10px
        if (false) { // تم إلغاء الظل
            float cyS = cy + dp(3), outer = R + dp(9);
            p.reset(); p.setAntiAlias(true);
            p.setShader(new RadialGradient(cx, cyS, outer,
                    new int[]{toneShadow, toneShadow, 0x00000000},
                    new float[]{0f, R / outer, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cyS, outer, p);
        }

        // إطار الفصل عن الدوائر الخلفية
        if (haloPx > 0f && haloA > 0.003f) {
            float ro = (ring || morph > 0f) ? R + (R + dp(ringSmall ? 3 : 5) - R) * (1f - morph) : R;
            p.reset(); p.setAntiAlias(true);
            p.setColor((surface & 0x00FFFFFF) | (Math.round(255 * haloA) << 24));
            c.drawCircle(cx, cy, ro + haloPx, p);
        }

        // جسم الأفاتار
        p.reset(); p.setAntiAlias(true);
        p.setShader(css145(cx, cy, d, blue ? BLUE_A : GOLD_A, blue ? BLUE_B : GOLD_B));
        c.drawCircle(cx, cy, Rc, p);
        p.setShader(null);

        if (photo != null && !photo.isRecycled()) {
            BitmapShader bs = new BitmapShader(photo, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            float sc = Math.max(2f * Rc / photo.getWidth(), 2f * Rc / photo.getHeight());
            mx.reset();
            mx.setScale(sc, sc);
            mx.postTranslate(cx - photo.getWidth() * sc / 2f, cy - photo.getHeight() * sc / 2f);
            bs.setLocalMatrix(mx);
            p.setShader(bs);
            c.drawCircle(cx, cy, Rc, p);
            p.setShader(null);
        } else {
            float tsp = textSp + (11f * morphK - textSp) * morph;
            tp.setTextSize(tsp * getResources().getDisplayMetrics().scaledDensity);
            Paint.FontMetrics fm = tp.getFontMetrics();
            c.drawText(letter, cx, cy - (fm.ascent + fm.descent) / 2f, tp);
        }

        // حلقة القصة (.avatar.sring::after : inset:-5px; padding:3px)
        if (ring || morph > 0f) {
            float outerR = R + dp(ringSmall ? 3 : 5), thick = dp(ringSmall ? 2 : 3);
            outerR += (R - outerR) * morph;
            thick += (thumbB - thick) * morph;
            float mid = outerR - thick / 2f;
            if (launching) {
                // أثناء الفتح: قطع ذهبية متفاوتة الطول تدور (مثل تيليجرام)
                drawDashRing(c, cx, cy, mid, thick, spin, p, arcRect, mx);
            } else {
                if (GLOW) {   // box-shadow:0 0 7px rgba(212,160,23,.55) خارج حافة الحلقة فقط
                    float g = outerR + dp(8);
                    float e = 0.0005f;
                    p.reset(); p.setAntiAlias(true);
                    p.setShader(new RadialGradient(cx, cy, g,
                            new int[]{0x00D4A017, 0x00D4A017, 0x46D4A017, 0x2BD4A017, 0x16D4A017, 0x09D4A017, 0x03D4A017, 0x00D4A017},
                            new float[]{0f, outerR / g - e, outerR / g,
                                    (outerR + dp(1.75f)) / g, (outerR + dp(3.5f)) / g,
                                    (outerR + dp(5.25f)) / g, (outerR + dp(7f)) / g, 1f},
                            Shader.TileMode.CLAMP));
                    c.drawCircle(cx, cy, g, p);
                }
                p.reset(); p.setAntiAlias(true);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(thick);
                if (!ring) {
                    // لا حلقة قصة: لا شيء يُرسم قبل حدّ المصغّرة
                } else if (seen) {
                    p.setColor(0xFF4B5A6B);
                } else {
                    SweepGradient sg = new SweepGradient(cx, cy,
                            new int[]{0xFF8F6810, 0xFFE8D176, 0xFFB98B20, 0xFFE8D991, 0xFFB8860B, 0xFFE6C45B, 0xFF8F6810},
                            new float[]{0f, .18f, .34f, .48f, .65f, .82f, 1f});
                    mx.reset();
                    mx.setRotate(-90, cx, cy);   // conic-gradient يبدأ من الأعلى
                    sg.setLocalMatrix(mx);
                    p.setShader(sg);
                }
                if (ring) {
                    if (morph > 0f) p.setAlpha(Math.round(255 * (1f - morph)));
                    c.drawCircle(cx, cy, mid, p);
                }
                if (morph > 0f) {   // حدّ المصغّرة الموحَّد اللون يظهر تدريجياً فوق الحلقة
                    p.reset(); p.setAntiAlias(true);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(thick);
                    p.setColor(seen ? 0xFF66717D : 0xFFD4AF37);
                    p.setAlpha(Math.round(255 * morph));
                    c.drawCircle(cx, cy, mid, p);
                }
            }
        }

        // علامة + (قصتي بدون قصة)
        if (plus && plusAlpha > 0.003f) {
            float sz = dp(21.9f), pr = sz / 2f;
            float pcx = cx + R + dp(3) - pr, pcy = cy + R + dp(3) - pr;
            int a = Math.round(255 * plusAlpha);
            // ظل
            // الحدّ
            p.reset(); p.setAntiAlias(true);
            p.setColor((surface & 0x00FFFFFF) | (a << 24));
            c.drawCircle(pcx, pcy, pr, p);
            // الجسم
            float ir = pr - dp(2);
            p.reset(); p.setAntiAlias(true);
            if (dark) {
                p.setColor((0x00FFFFFF) | (a << 24));
            } else {
                LinearGradient lg = new LinearGradient(pcx - ir, pcy - ir, pcx + ir, pcy + ir, 0xFFE6BD45, 0xFFB8861B, Shader.TileMode.CLAMP);
                p.setShader(lg); p.setAlpha(a);
            }
            c.drawCircle(pcx, pcy, ir, p);
            // العلامة (SVG 24 → 12.5dp ، stroke 2.5)
            float k = dp(12.5f) / 24f;
            p.reset(); p.setAntiAlias(true);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(2.5f * k);
            int pc = dark ? 0xFFD4A017 : 0xFFFFFFFF;
            p.setColor((pc & 0x00FFFFFF) | (a << 24));
            plusPath.reset();
            plusPath.moveTo(pcx, pcy - 7 * k); plusPath.lineTo(pcx, pcy + 7 * k);
            plusPath.moveTo(pcx - 7 * k, pcy); plusPath.lineTo(pcx + 7 * k, pcy);
            c.drawPath(plusPath, p);
        }

        // نقطة الاتصال
        if (online) {
            float dd = dp(14.6f), r = dd / 2f;
            float ox = padPx + dp(1) + r, oy = h - padPx - dp(1) - r;
            p.reset(); p.setAntiAlias(true);
            p.setColor(0x2134C759);
            c.drawCircle(ox, oy, r + dp(5.2f), p);
            p.setColor(surface);
            c.drawCircle(ox, oy, r, p);
            p.setColor(0xFF34C759);
            c.drawCircle(ox, oy, r - dp(2), p);
        }
    }
}
