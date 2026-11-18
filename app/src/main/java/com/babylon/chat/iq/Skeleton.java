package com.babylon.chat.iq;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

/**
 * Skeleton loading: كتل رمادية بلمعة متحركة (shimmer) تظهر مكان ما لم يُحمَّل بعد (محادثات، رسائل، صور)،
 * وتزول فور وصول المحتوى الحقيقي. اللمعة تُرسم من ساعة واحدة مشتركة فتتزامن كل الكتل، وتتوقف تلقائياً حين تُفصل عن الشاشة.
 */
final class Skeleton {
    private Skeleton() { }

    private static final long PERIOD = 1300L;

    static int base(boolean dark) { return dark ? 0xFF2A3942 : 0xFFE3E7EA; }
    static int light(boolean dark) { return dark ? 0xFF3B4D59 : 0xFFF6F8F9; }

    /** كتلة بلمعة متحركة؛ corner بالبكسل (0 = بلا تدوير، تُقصّ عادةً بإطار أبيها). */
    static Drawable shimmer(boolean dark, float cornerPx) { return new Shimmer(dark, cornerPx); }

    /** كتلة بلمعة بألوان مخصّصة (لون الثيم). */
    static Drawable shimmer(int base, int hi, float cornerPx) { return new Shimmer(base, hi, cornerPx); }

    static boolean isShimmer(Drawable d) { return d instanceof Shimmer; }

    private static final class Shimmer extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Matrix m = new Matrix();
        private final int base, hi;
        private final float corner;
        private LinearGradient shader;
        private float bw;
        private final Runnable tick = this::invalidateSelf;

        Shimmer(boolean dark, float corner) { this.base = Skeleton.base(dark); this.hi = Skeleton.light(dark); this.corner = corner; }
        Shimmer(int base, int hi, float corner) { this.base = base; this.hi = hi; this.corner = corner; }

        @Override protected void onBoundsChange(android.graphics.Rect b) {
            bw = Math.max(1f, b.width() * 0.7f);
            shader = new LinearGradient(0, 0, bw, 0, new int[]{base, hi, base}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        }

        @Override public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            if (b.isEmpty() || shader == null) return;
            float ph = (SystemClock.uptimeMillis() % PERIOD) / (float) PERIOD;
            float w = b.width();
            m.setTranslate(b.left + (ph * 1.7f - 0.7f) * w, 0f);
            shader.setLocalMatrix(m);
            p.setColor(base);
            p.setShader(null);
            r.set(b);
            c.drawRoundRect(r, corner, corner, p);
            p.setShader(shader);
            c.drawRoundRect(r, corner, corner, p);
            p.setShader(null);
            scheduleSelf(tick, SystemClock.uptimeMillis() + 16);   // يتوقف وحده حين تُزال الكتلة من الشاشة (لا callback)
        }

        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** لمعة اللون: أفتح قليلاً من الأساس (أكثر في النهاري). */
    static int hiOf(int base, boolean dark) { return mix(base, 0xFFFFFFFF, dark ? 0.14f : 0.45f); }

    private static View block(Context c, boolean dark, int wDp, int hDp, int cornerDp) { return block(c, dark, wDp, hDp, cornerDp, 0, 0); }

    /** base/hi = 0 → ألوان الهيكل الافتراضية؛ غير ذلك لون الثيم. */
    private static View block(Context c, boolean dark, int wDp, int hDp, int cornerDp, int base, int hi) {
        float d = c.getResources().getDisplayMetrics().density;
        View v = new View(c);
        v.setBackground(base != 0 ? shimmer(base, hi, cornerDp * d) : shimmer(dark, cornerDp * d));
        v.setLayoutParams(new LinearLayout.LayoutParams(wDp < 0 ? -1 : Math.round(wDp * d), Math.round(hDp * d)));
        return v;
    }

    /** أنواع الرسائل المدعومة في صفوف الهيكل (العنصر الرابع من كل صف). */
    static final int K_TEXT = 0, K_EMOJI = 1, K_IMAGE = 2, K_AUDIO = 3, K_FILE = 4;

    /** هيكل رسائل: فقاعات متبادلة الجهتين تملأ المحادثة من الأسفل. */
    static LinearLayout messages(Context c, boolean dark) { return messages(c, dark, 0, 0); }

    /** mineBase/otherBase: لونا فقاعتي وفقاعة الطرف الآخر في ثيم المحادثة (0 = الافتراضي). */
    static LinearLayout messages(Context c, boolean dark, int mineBase, int otherBase) { return messages(c, dark, mineBase, otherBase, null); }

    /**
     * custom: صفوف {الجهة (0 = رسالتي يميناً، 1 = الطرف الآخر يساراً), العرض dp, الارتفاع dp, [النوع K_*], [عدد الإيموجي]}
     * بدل التصميم الافتراضي. الصف بثلاثة عناصر فقط = نص.
     */
    static LinearLayout messages(Context c, boolean dark, int mineBase, int otherBase, int[][] custom) {
        float d = c.getResources().getDisplayMetrics().density;
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.BOTTOM);
        col.setPadding(Math.round(10 * d), Math.round(10 * d), Math.round(10 * d), Math.round(10 * d));
        col.setClickable(true);   // لا تمرّر اللمسات إلى ما تحته أثناء التحميل
        int[][] spec = {{0, 190, 44}, {1, 250, 62}, {1, 140, 40}, {0, 230, 78}, {1, 210, 44}, {0, 150, 40}, {1, 260, 90}, {0, 200, 44}};
        if (custom != null && custom.length > 0) spec = custom;
        addRows(c, col, dark, mineBase, otherBase, spec);
        return col;
    }

    /** يضيف صفوف الهيكل حسب نوع كل رسالة (نص / إيموجي فقط / صورة / صوتية / ملف). */
    private static void addRows(Context c, LinearLayout col, boolean dark, int mineBase, int otherBase, int[][] spec) {
        float d = c.getResources().getDisplayMetrics().density;
        int maxW = Math.round(c.getResources().getDisplayMetrics().widthPixels / d * 0.72f);
        for (int[] s : spec) {
            int kind = s.length > 3 ? s[3] : K_TEXT;
            int bb = mineBase != 0 || otherBase != 0 ? (s[0] == 0 ? mineBase : otherBase) : 0;
            int hi = bb != 0 ? hiOf(bb, dark) : 0;
            View b;
            LinearLayout.LayoutParams lp;
            if (kind == K_EMOJI) {
                b = emojiCluster(c, dark, s.length > 4 ? s[4] : 1, maxW, bb, hi);
                lp = new LinearLayout.LayoutParams(-2, -2);
            } else {
                int corner = kind == K_AUDIO ? 22 : kind == K_IMAGE ? 14 : 16;
                b = block(c, dark, Math.min(s[1], maxW), s[2], corner, bb, hi);
                lp = (LinearLayout.LayoutParams) b.getLayoutParams();
            }
            lp.gravity = s[0] == 0 ? Gravity.RIGHT : Gravity.LEFT;   // رسالتي يميناً والطرف الآخر يساراً (مطلق كالفقاعات الحقيقية)
            lp.bottomMargin = Math.round(8 * d);
            col.addView(b, lp);
        }
    }

    /** رسالة إيموجي فقط: بلا فقاعة، شبكة دوائر صغيرة (حتى 4 صفوف مهما كان عدد الإيموجي الحقيقي). */
    private static View emojiCluster(Context c, boolean dark, int count, int maxWdp, int base, int hi) {
        float d = c.getResources().getDisplayMetrics().density;
        final int size = 22, gap = 4;
        int perRow = Math.max(1, Math.min(10, (maxWdp + gap) / (size + gap)));
        int n = Math.max(1, Math.min(count, perRow * 4));
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        for (int r = 0; r * perRow < n; r++) {
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            int inRow = Math.min(perRow, n - r * perRow);
            for (int i = 0; i < inRow; i++) {
                View dot = block(c, dark, size, size, size / 2, base, hi);
                LinearLayout.LayoutParams dp = (LinearLayout.LayoutParams) dot.getLayoutParams();
                dp.setMargins(0, 0, Math.round(gap * d), Math.round(gap * d));
                row.addView(dot, dp);
            }
            box.addView(row, new LinearLayout.LayoutParams(-2, -2));
        }
        return box;
    }

    /** هيكل قائمة المحادثات (RTL كالصفوف الحقيقية): الصورة الدائرية يميناً ثم سطران، والوقت أقصى اليسار. */
    static LinearLayout chatRows(Context c, boolean dark) {
        float d = c.getResources().getDisplayMetrics().density;
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);   // التطبيق بلا supportsRtl فيُتجاهل RTL: الترتيب يدوي (مثل صفوف المحادثات الحقيقية)
        col.setPadding(Math.round(14 * d), Math.round(8 * d), Math.round(14 * d), 0);
        for (int i = 0; i < 8; i++) {
            LinearLayout row = new LinearLayout(c);
            row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Math.round(8 * d), 0, Math.round(8 * d));
            // الترتيب من اليسار: الوقت ← النص ← الصورة في أقصى اليمين
            LinearLayout.LayoutParams timeP = new LinearLayout.LayoutParams(Math.round(34 * d), Math.round(10 * d));
            timeP.gravity = Gravity.TOP;
            row.addView(block(c, dark, 34, 10, 5), timeP);   // وقت آخر رسالة (أقصى اليسار)
            LinearLayout txt = new LinearLayout(c);
            txt.setOrientation(LinearLayout.VERTICAL);
            txt.setGravity(Gravity.RIGHT);   // السطران ملتصقان بجهة الصورة (يمين)
            View l1 = block(c, dark, 120 + (i % 3) * 30, 14, 7);
            View l2 = block(c, dark, 190 + (i % 2) * 40, 12, 6);
            LinearLayout.LayoutParams p2 = (LinearLayout.LayoutParams) l2.getLayoutParams();
            p2.topMargin = Math.round(8 * d);
            txt.addView(l1);
            txt.addView(l2, p2);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
            tp.rightMargin = Math.round(12 * d);
            row.addView(txt, tp);
            View av = block(c, dark, 52, 52, 26);
            row.addView(av);   // آخر عنصر في LTR = أقصى اليمين
            col.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        return col;
    }

    /** شريط هيكل للرسائل الأقدم: ثلاث فقاعات في أعلى المحادثة (حيث ستظهر الرسائل المحمّلة). */
    static LinearLayout olderStrip(Context c, boolean dark) { return olderStrip(c, dark, 0, 0); }

    static LinearLayout olderStrip(Context c, boolean dark, int mineBase, int otherBase) { return olderStrip(c, dark, mineBase, otherBase, null); }

    /** custom: نفس صيغة messages(...) (أنواع الرسائل الأقدم الحقيقية إن عُرفت)؛ null = الشكل الافتراضي. */
    static LinearLayout olderStrip(Context c, boolean dark, int mineBase, int otherBase, int[][] custom) {
        float d = c.getResources().getDisplayMetrics().density;
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Math.round(10 * d), Math.round(6 * d), Math.round(10 * d), 0);
        int[][] spec = {{1, 200, 40}, {0, 150, 56}, {1, 240, 40}};
        if (custom != null && custom.length > 0) spec = custom;
        addRows(c, col, dark, mineBase, otherBase, spec);
        return col;
    }
}
