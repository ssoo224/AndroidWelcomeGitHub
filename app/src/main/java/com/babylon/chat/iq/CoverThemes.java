package com.babylon.chat.iq;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * قائمة أغلفة الملف الشخصي — نقل مطابق من الـ HTML:
 *   THEMES[id].name / .mine (تدرّج الغلاف) / .symbols (رموز النقوش) و COVER_RING[id].
 * الترتيب هو ترتيب مفاتيح THEMES نفسه (هو ترتيب شبكة الاختيار): بابل، آشور، سومر، سامراء، بغداد، الأهوار.
 * المعرّف (id) هو القيمة المحفوظة في حقل users/{uid}.cover في Firestore، فيتطابق مع نسخة الويب.
 */
public final class CoverThemes {

    public static final String DEFAULT_ID = "babylon";

    public static final class Theme {
        public final String id, name;
        /** زاوية تدرّج CSS (0 = إلى الأعلى، 90 = إلى اليمين، 135 = إلى أسفل اليمين) */
        public final float angle;
        public final int from, to;
        /** COVER_RING في الـ HTML (يُضبط كمتغيّر --ring ولا يُستهلك في CSS، محفوظ هنا للمطابقة) */
        public final int ring;
        public final String[] symbols;

        Theme(String id, String name, float angle, int from, int to, int ring, String... symbols) {
            this.id = id; this.name = name; this.angle = angle;
            this.from = from; this.to = to; this.ring = ring; this.symbols = symbols;
        }
    }

    public static final Theme[] ALL = {
        // babylon: mine = linear-gradient(145deg,#e8c85d,#cba843)
        new Theme("babylon", "بابل", 145f, 0xFFE8C85D, 0xFFCBA843, 0xFFC29D30,
                "gate", "star8", "bull", "tablet", "crescent", "jar"),
        // ashur: linear-gradient(135deg,#8a5a2b,#c48b47)
        new Theme("ashur", "آشور", 135f, 0xFF8A5A2B, 0xFFC48B47, 0xFFE0B36A,
                "bull", "arch", "sun", "gate", "star8", "tablet"),
        // sumer: linear-gradient(135deg,#a8552d,#cc7b49)
        new Theme("sumer", "سومر", 135f, 0xFFA8552D, 0xFFCC7B49, 0xFFCC7B49,
                "ziggurat", "tablet", "lyre", "jar", "reeds", "sun"),
        // samarra: linear-gradient(135deg,#5f4a1c,#8c6d2f)
        new Theme("samarra", "سامراء", 135f, 0xFF5F4A1C, 0xFF8C6D2F, 0xFFC9A24A,
                "minaret", "palm", "arch", "sun", "wave", "jar"),
        // baghdad: linear-gradient(135deg,#13787a,#25aaa3)
        new Theme("baghdad", "بغداد العباسية", 135f, 0xFF13787A, 0xFF25AAA3, 0xFF25AAA3,
                "arch", "crescent", "star8", "minaret", "palm", "sun"),
        // ahwar: linear-gradient(135deg,#2b7a8c,#3f9a7a)
        new Theme("ahwar", "الأهوار", 135f, 0xFF2B7A8C, 0xFF3F9A7A, 0xFF5FB98F,
                "reeds", "boat", "fish", "wave", "palm", "sun"),
    };

    private CoverThemes() { }

    /** الطور الحالي (يُحدَّث من Tk.setChatTheme) — الغلاف يأخذ ألوان الثيم المطابق للطور تماماً. */
    public static volatile boolean dark = false;

    /** لوحة الثيم المطابق للغلاف في الطور الحالي: نفس ألوان بطاقة الثيم في «ثيم وخلفية المحادثة». */
    static ChatThemes.Pal palFor(Theme t) {
        return (ChatThemes.isBabylon(t.id) && !dark) ? ChatThemes.babylonPreview(false) : ChatThemes.byId(t.id).pal(dark);
    }

    public static boolean has(String id) {
        if (id == null) return false;
        for (Theme t : ALL) if (t.id.equals(id)) return true;
        return false;
    }

    /** THEMES[id] ? id : 'babylon' */
    public static Theme byId(String id) {
        if (id != null) for (Theme t : ALL) if (t.id.equals(id)) return t;
        return ALL[0];
    }

    /** background:linear-gradient(<angle>deg, from, to) بنفس هندسة CSS على أي مقاس. */
    public static Drawable gradient(Theme t) {
        return new CssGradient(t);
    }

    private static final class CssGradient extends Drawable {
        private final Theme t;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private int lw = -1, lh = -1, lx = Integer.MIN_VALUE, ly = Integer.MIN_VALUE;
        private boolean ld;

        CssGradient(Theme t) { this.t = t; }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            if (b.isEmpty()) return;
            if (b.width() != lw || b.height() != lh || b.left != lx || b.top != ly || ld != dark) {
                lw = b.width(); lh = b.height(); lx = b.left; ly = b.top; ld = dark;
                ChatThemes.Pal pl = palFor(t);
                // خط التدرّج في CSS: طوله |w·sin a| + |h·cos a| ويمرّ بمركز الصندوق
                double a = Math.toRadians(pl.mineAngle);
                float dx = (float) Math.sin(a), dy = (float) -Math.cos(a);
                float half = (Math.abs(lw * dx) + Math.abs(lh * dy)) / 2f;
                float cx = b.exactCenterX(), cy = b.exactCenterY();
                paint.setShader(new LinearGradient(cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half,
                        pl.mineFrom, pl.mineTo, Shader.TileMode.CLAMP));
            }
            c.drawRect(b, paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }

        @Override public ConstantState getConstantState() {
            final Theme th = t;
            return new ConstantState() {
                @Override public Drawable newDrawable() { return new CssGradient(th); }
                @Override public int getChangingConfigurations() { return 0; }
            };
        }
    }
}
