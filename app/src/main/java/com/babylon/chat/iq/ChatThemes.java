package com.babylon.chat.iq;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * ثيمات المحادثة — نقل حرفي من THEMES في الـ HTML (مولَّد من الكود نفسه):
 *   بابل، آشور، سومر، سامراء، بغداد العباسية، الأهوار — بنفس الترتيب والقيم.
 * كل ثيم (عدا بابل) له لوحة ليلية ولوحة نهارية (themeFor(id) في الـ HTML تدمج base مع modes[dark|light]).
 * مفتاح الحفظ: prefs.theme ('babylon' افتراضي) وهو نفسه حقل users/{uid}.chatPrefs.theme في Firestore.
 */
public final class ChatThemes {
    private ChatThemes() { }

    public static final String DEFAULT_ID = "babylon";
    public static final int FONT_MIN = 12, FONT_MAX = 22, FONT_DEF = 15;     // prefs.font
    public static final int RADIUS_MIN = 0, RADIUS_MAX = 20, RADIUS_DEF = 12; // prefs.radius

    /** لوحة ألوان محلولة لوضع معيّن (نهاري/ليلي). */
    public static final class Pal {
        public final int chatBg, other, textMine, textOther, accent, tick, metaOther, line, pattern;
        /** زاوية تدرّج CSS لفقاعتي (-1 = لون صلب) */
        public final float mineAngle;
        public final int mineFrom, mineTo;
        public final float patternOp;

        Pal(int chatBg, float mineAngle, int mineFrom, int mineTo, int other, int textMine, int textOther,
            int accent, int tick, int metaOther, int line, int pattern, float patternOp) {
            this.chatBg = chatBg; this.mineAngle = mineAngle; this.mineFrom = mineFrom; this.mineTo = mineTo;
            this.other = other; this.textMine = textMine; this.textOther = textOther; this.accent = accent;
            this.tick = tick; this.metaOther = metaOther; this.line = line; this.pattern = pattern; this.patternOp = patternOp;
        }
    }

    public static final class Spec {
        public final String id, name, hero;
        public final String[] symbols;
        final Pal base, dark, light;

        Spec(String id, String name, String hero, String[] symbols, Pal base, Pal dark, Pal light) {
            this.id = id; this.name = name; this.hero = hero; this.symbols = symbols;
            this.base = base; this.dark = dark; this.light = light;
        }

        /** themeFor(id) في الـ HTML: base مدموجة مع modes[dark|light] إن وُجدت */
        public Pal pal(boolean dark) {
            Pal v = dark ? this.dark : this.light;
            return v != null ? v : base;
        }
    }

    // ---------------------------------------------------------------- تحليل قيم CSS
    private static int col(String s) {
        s = s.trim();
        if (s.startsWith("#")) {
            int v = (int) Long.parseLong(s.substring(1), 16);
            return 0xFF000000 | v;
        }
        // rgba(r,g,b,a)
        String in = s.substring(s.indexOf('(') + 1, s.indexOf(')'));
        String[] p = in.split(",");
        int r = Integer.parseInt(p[0].trim()), g = Integer.parseInt(p[1].trim()), b = Integer.parseInt(p[2].trim());
        int a = Math.round(Float.parseFloat(p[3].trim()) * 255f);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static Pal pal(String chatBg, String mine, String other, String textMine, String textOther,
                           String accent, String tick, String metaOther, String line, String pattern, float patternOp) {
        float ang = -1f; int from, to;
        if (mine.startsWith("linear-gradient")) {
            String in = mine.substring(mine.indexOf('(') + 1, mine.lastIndexOf(')'));
            String[] p = in.split(",");
            ang = Float.parseFloat(p[0].replace("deg", "").trim());
            from = col(p[1]); to = col(p[2]);
        } else { from = to = col(mine); }
        return new Pal(col(chatBg), ang, from, to, col(other), col(textMine), col(textOther),
                col(accent), col(tick), col(metaOther), col(line), col(pattern), patternOp);
    }

    // ---------------------------------------------------------------- THEMES (ترتيب المفاتيح = ترتيب شريط الاختيار)
    public static final Spec[] ALL = {
        new Spec("babylon", "بابل", "gate", new String[]{"gate", "star8", "bull", "tablet", "crescent", "jar"},
            pal("#0d1626", "linear-gradient(145deg,#e8c85d,#cba843)", "#263345", "#ffffff", "#f3f4f6", "#c29d30", "#ffe08a", "#94a3b8", "rgba(255,255,255,0.06)", "#c29d30", 0.11f),
            null,
            null),
        new Spec("ashur", "آشور", "bull", new String[]{"bull", "arch", "sun", "gate", "star8", "tablet"},
            pal("#1f1710", "linear-gradient(135deg,#8a5a2b,#c48b47)", "#3a2c1d", "#fff8ec", "#f1e4cc", "#e0b36a", "#ffe6b0", "#b89f7a", "rgba(255,255,255,0.05)", "#c9a064", 0.11f),
            pal("#241d33", "#6b5a9e", "#352c4a", "#ffffff", "#ece7f6", "#b7a6e0", "#d9cff2", "#a89cc4", "rgba(255,255,255,0.07)", "#b7a6e0", 0.09f),
            pal("#ebe6f3", "#6b5a9e", "#faf8fd", "#ffffff", "#2d2542", "#6b5a9e", "#e3dbf5", "#74688f", "rgba(0,0,0,0.07)", "#6b5a9e", 0.1f)),
        new Spec("sumer", "سومر", "ziggurat", new String[]{"ziggurat", "tablet", "lyre", "jar", "reeds", "sun"},
            pal("#ecd9b8", "linear-gradient(135deg,#a8552d,#cc7b49)", "#fbf1de", "#ffffff", "#3b2412", "#8a4524", "#fff3c4", "#7a5c3a", "rgba(0,0,0,0.08)", "#7a4a24", 0.16f),
            pal("#2e211b", "#a1573a", "#43322a", "#ffffff", "#f3e7de", "#d9a07f", "#f6dfcf", "#b9a091", "rgba(255,255,255,0.07)", "#d9a07f", 0.09f),
            pal("#f1e4d9", "#a1573a", "#fcf8f4", "#ffffff", "#3b2519", "#a1573a", "#f6dfcf", "#7f6455", "rgba(0,0,0,0.07)", "#a1573a", 0.1f)),
        new Spec("samarra", "سامراء", "minaret", new String[]{"minaret", "palm", "arch", "sun", "wave", "jar"},
            pal("#dcc690", "linear-gradient(135deg,#5f4a1c,#8c6d2f)", "#f6eacb", "#ffffff", "#3a2c10", "#5a4318", "#ffffff", "#7b6537", "rgba(0,0,0,0.08)", "#5f4a1c", 0.15f),
            pal("#2a261a", "#7f6d2d", "#3c3624", "#ffffff", "#f1ead3", "#cdb866", "#efe3b0", "#b2a778", "rgba(255,255,255,0.07)", "#cdb866", 0.09f),
            pal("#efe9d3", "#7f6d2d", "#fbf9ee", "#ffffff", "#37301a", "#7f6d2d", "#efe3b0", "#756c47", "rgba(0,0,0,0.07)", "#7f6d2d", 0.1f)),
        new Spec("baghdad", "بغداد العباسية", "arch", new String[]{"arch", "crescent", "star8", "minaret", "palm", "sun"},
            pal("#0b2b2c", "linear-gradient(135deg,#13787a,#25aaa3)", "#17484a", "#ffffff", "#eef6f2", "#f3ead3", "#c4f5f0", "#8fb8b4", "rgba(255,255,255,0.06)", "#f3ead3", 0.1f),
            pal("#1b2a3d", "#3f6fa3", "#2a3d56", "#ffffff", "#e6eef8", "#9cc0e6", "#d3e4f7", "#9fb3cb", "rgba(255,255,255,0.07)", "#9cc0e6", 0.09f),
            pal("#e1eaf4", "#3f6fa3", "#f8fbfe", "#ffffff", "#1f2f44", "#3f6fa3", "#d3e4f7", "#5f7490", "rgba(0,0,0,0.07)", "#3f6fa3", 0.1f)),
        new Spec("ahwar", "الأهوار", "boat", new String[]{"reeds", "boat", "fish", "wave", "palm", "sun"},
            pal("#cfe3d4", "linear-gradient(135deg,#2b7a8c,#3f9a7a)", "#f2f8ef", "#ffffff", "#1f3a2a", "#2b6a4a", "#d9f5c8", "#5f7f6a", "rgba(0,0,0,0.07)", "#3f6b3a", 0.16f),
            pal("#1d2e26", "#3f8060", "#2c4339", "#ffffff", "#e5f1ea", "#9bd0b3", "#d2ecdd", "#9fbcae", "rgba(255,255,255,0.07)", "#9bd0b3", 0.09f),
            pal("#e1ede5", "#3f8060", "#f8fcf9", "#ffffff", "#1f3328", "#3f8060", "#d2ecdd", "#5d7a6a", "rgba(0,0,0,0.07)", "#3f8060", 0.1f))
    };

    public static boolean has(String id) {
        if (id == null) return false;
        for (Spec s : ALL) if (s.id.equals(id)) return true;
        return false;
    }

    /** THEMES[id] || THEMES.babylon */
    public static Spec byId(String id) {
        if (id != null) for (Spec s : ALL) if (s.id.equals(id)) return s;
        return ALL[0];
    }

    public static Pal themeFor(String id, boolean dark) { return byId(id).pal(dark); }

    public static boolean isBabylon(String id) { return DEFAULT_ID.equals(byId(id).id); }

    /**
     * ألوان معاينة الرسائل (#chatSettingsView .pv) لثيم بابل — كما تُحسب من الـ CSS النهائي:
     *  نهاري: خلفية #eaf0f5، الأخرى #fff بحدّ #dce4ee ونص #202a35 ووقت #7c8b9c، مرسلتي تدرّج ذهبي ونص #292216، الاسم #9a6b00.
     *  ليلي: خلفية #0d1626، الأخرى #182533، مرسلتي #2b5278 (متغيّرات .app-container[dark]).
     */
    public static Pal babylonPreview(boolean dark) {
        if (dark) return new Pal(0xFF0D1626, -1f, 0xFF2B5278, 0xFF2B5278, 0xFF182533, 0xFFFFFFFF, 0xFFF3F4F6,
                0xFFC29D30, 0xFFFFE08A, 0xFF94A3B8, 0x0FFFFFFF, 0xFFC29D30, 0.11f);
        return new Pal(0xFFEAF0F5, 145f, 0xFFE8C85D, 0xFFCBA843, 0xFFFFFFFF, 0xFF292216, 0xFF202A35,
                0xFF9A6B00, 0xFFFFE08A, 0xFF7C8B9C, 0xFFDCE4EE, 0xFFC29D30, 0.11f);
    }

    /** لوحة المعاينة الفعلية: بابل لها لوحتها الخاصة، وغيرها themeFor. */
    public static Pal previewPal(String id, boolean dark) {
        return isBabylon(id) ? babylonPreview(dark) : themeFor(id, dark);
    }

    /** تطبيق .filter:grayscale(1) على لون النقش (CSS: 0.2126 / 0.7152 / 0.0722) */
    public static int gray(int c) {
        float r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        int y = Math.round(0.2126f * r + 0.7152f * g + 0.0722f * b);
        return 0xFF000000 | (y << 16) | (y << 8) | y;
    }

    // ---------------------------------------------------------------- خلفية الفقاعة
    /** صلب أو تدرّج بزاوية CSS على أي مقاس، بأنصاف أقطار الزوايا الأربع + حدّ 1px اختياري. */
    public static Drawable bubble(Pal p, boolean mine, float[] radii8, float strokePx) {
        return new BubbleBg(p, mine, radii8, strokePx);
    }

    private static final class BubbleBg extends Drawable {
        private final Pal p; private final boolean mine; private final float[] r; private final float sw;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path(), inner = new Path();
        private final RectF rf = new RectF();
        private int lw = -1, lh = -1;

        BubbleBg(Pal p, boolean mine, float[] r, float sw) {
            this.p = p; this.mine = mine; this.r = r.clone(); this.sw = sw;
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(sw);
            line.setColor(p.line);
        }

        @Override public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            if (b.isEmpty()) return;
            rf.set(b);
            if (b.width() != lw || b.height() != lh) {
                lw = b.width(); lh = b.height();
                if (mine && p.mineAngle >= 0f) {
                    double a = Math.toRadians(p.mineAngle);
                    float dx = (float) Math.sin(a), dy = (float) -Math.cos(a);
                    float half = (Math.abs(lw * dx) + Math.abs(lh * dy)) / 2f;
                    float cx = rf.centerX(), cy = rf.centerY();
                    fill.setShader(new LinearGradient(cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half,
                            p.mineFrom, p.mineTo, Shader.TileMode.CLAMP));
                } else {
                    fill.setShader(null);
                    fill.setColor(mine ? p.mineFrom : p.other);
                }
            }
            path.reset();
            path.addRoundRect(rf, r, Path.Direction.CW);
            c.drawPath(path, fill);
            if (!mine && sw > 0f && (p.line >>> 24) != 0) {
                float h = sw / 2f;
                RectF ri = new RectF(rf.left + h, rf.top + h, rf.right - h, rf.bottom - h);
                float[] r2 = new float[8];
                for (int i = 0; i < 8; i++) r2[i] = Math.max(0f, r[i] - h);
                inner.reset();
                inner.addRoundRect(ri, r2, Path.Direction.CW);
                c.drawPath(inner, line);
            }
        }

        @Override public void setAlpha(int alpha) { fill.setAlpha(alpha); line.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter cf) { fill.setColorFilter(cf); line.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
