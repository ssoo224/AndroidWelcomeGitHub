package com.babylon.chat.iq;

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
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.view.View;

/**
 * .avatar في الـ HTML:
 *  - دائرة بتدرج 145° (ذهبي #f2d268→#b8861b أو أزرق #59cdf8→#1f63d8 حسب نغمة الحساب) + ظل ملوّن 0 3px 10px
 *  - .sring::after : حلقة مخروطية (conic-gradient) بسماكة 3px على بعد 5px مع توهّج ذهبي 7px (أو #4b5a6b للمشاهَدة)
 *  - .sb-plus : علامة + (نهاري: تدرج ذهبي وعلامة بيضاء، ليلي: دائرة بيضاء وعلامة #D4A017)
 *  - .cl-online : نقطة اتصال خضراء بحدّ بلون السطح وهالة 5px
 * العنصر يرسم خارج حدوده (الحلقة والتوهج والظل) لذلك يجب أن تكون الحاويات clipChildren=false.
 */
public class StoryAvatarView extends View {

    public static final int GOLD_A = 0xFFF2D268, GOLD_B = 0xFFB8861B;
    public static final int BLUE_A = 0xFF59CDF8, BLUE_B = 0xFF1F63D8;

    private String letter = "?";
    private Bitmap photo;
    private boolean blue, ring, seen, plus, online, dark;
    private float textSp = 20f;
    private int surface = 0xFF17212B;       // لون حدّ النقطة/علامة +
    private float plusAlpha = 1f;
    private boolean shadow = true;

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

    public void copyFrom(StoryAvatarView o) {
        letter = o.letter; photo = o.photo; blue = o.blue; ring = o.ring; seen = o.seen; plus = o.plus;
        online = o.online; dark = o.dark; textSp = o.textSp; surface = o.surface; shadow = o.shadow;
        invalidate();
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
        float d = Math.min(w, h);
        float R = d / 2f, cx = w / 2f, cy = h / 2f;
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

        // جسم الأفاتار
        p.reset(); p.setAntiAlias(true);
        p.setShader(css145(cx, cy, d, blue ? BLUE_A : GOLD_A, blue ? BLUE_B : GOLD_B));
        c.drawCircle(cx, cy, R, p);
        p.setShader(null);

        if (photo != null && !photo.isRecycled()) {
            BitmapShader bs = new BitmapShader(photo, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            float sc = Math.max(d / photo.getWidth(), d / photo.getHeight());
            mx.reset();
            mx.setScale(sc, sc);
            mx.postTranslate(cx - photo.getWidth() * sc / 2f, cy - photo.getHeight() * sc / 2f);
            bs.setLocalMatrix(mx);
            p.setShader(bs);
            c.drawCircle(cx, cy, R, p);
            p.setShader(null);
        } else {
            tp.setTextSize(textSp * getResources().getDisplayMetrics().scaledDensity);
            Paint.FontMetrics fm = tp.getFontMetrics();
            c.drawText(letter, cx, cy - (fm.ascent + fm.descent) / 2f, tp);
        }

        // حلقة القصة
        if (ring) {
            float outerR = R + dp(5), thick = dp(3), mid = outerR - thick / 2f;
            if (false) { // تم إلغاء توهج الحلقة
                float g = outerR + dp(7);
                p.reset(); p.setAntiAlias(true);
                p.setShader(new RadialGradient(cx, cy, g,
                        new int[]{0x00D4A017, 0x00D4A017, 0x4DD4A017, 0x00D4A017},
                        new float[]{0f, (outerR - dp(0.2f)) / g, (outerR + dp(0.2f)) / g, 1f}, Shader.TileMode.CLAMP));
                c.drawCircle(cx, cy, g, p);
            }
            p.reset(); p.setAntiAlias(true);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(thick);
            if (seen) {
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
            c.drawCircle(cx, cy, mid, p);
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
            float ox = dp(1) + r, oy = h - dp(1) - r;
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
