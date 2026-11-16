package com.babylon.chat.iq;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * سحب الشاشة للأسفل في الملف الشخصي: تكبر الصورة مع الإصبع حتى تصبح مربعاً بعرض الشاشة
 * يظهر عليه الاسم، وتصعد أزرار (تعديل / الصورة / الغلاف) فوق أسفل الصورة — مثل تليجرام.
 * منقول من سكربت pf-pull-expand-js (+ babylon-profile-glass + babylon-profile-blur-always):
 *   THRESH = 0.45       بعدها تثبت مفتوحة عند ترك الإصبع
 *   RANGE  = 320dp      للملف الشخصي الخاص ، 150dp لملف شخص آخر
 *   ext    = ارتفاع صف الأزرار + 28dp (امتداد الصورة المموّه أسفل المربع)
 *   حلقة الصورة تتحول من دائرة (50%) إلى مربع (0%) ، الاسم والحالة يظهران عند v > 0.55
 *
 * ترتيب الطبقات (من الأسفل للأعلى) داخل pagesHost — مثل z-index في الـ HTML:
 *   coverUnder  : لون الغلاف (يبقى خلف الصورة ويكبر معها: cover.height = coverH + delta*v)
 *   hero        : الصورة المكبّرة + الامتداد المموّه + التظليل + الاسم والحالة
 *   ScrollView  : المحتوى (شفاف أثناء السحب) ← الأزرار الزجاجية تظهر فوق الصورة
 *
 * الصورة تبدأ من y=0 (خلف شريط الحالة الشفاف) لأن MassActivity لا تضع padding علوي على صفحات الملف الشخصي.
 */
public final class ProfileHero implements PullScrollView.Controller {

    public interface PhotoSource { Bitmap get(); }

    /** ألوان الثيم الحالي، وإعادة تنسيق الأزرار الأصلية عند الإغلاق. */
    public interface Palette {
        int surface();     // خلفية الأزرار العادية (--pf-card)
        int text();        // لون نص الأزرار (--pf-txt)
        int gold();        // لون أيقونة الأزرار (--pf-gold)
        int red();         // زر الحظر
        int blue();        // أيقونة زر الرسالة (--pf-blue)
        int redIcon();     // أيقونة زر الحظر (--pf-red)
        void restyle();    // يعيد تنسيق الأزرار/الغلاف كما كانت قبل السحب
    }

    private static final float THRESH = 0.45f;

    /** تُستدعى عند سحب الصورة المكبّرة للأسفل مرة أخرى: تفتح الصورة بكامل الشاشة. */
    public interface FullViewListener { void open(CharSequence name, CharSequence status); }
    private FullViewListener fullListener;

    // عدّاد الصور (1/N) والنقاط الثلاث فوق الصورة المكبّرة — مثل تليجرام
    private final boolean own;
    private final FrameLayout topCtl;
    private final TextView countPill;
    private final View dotsBtn;                 // لملفي فقط (ملف الشخص الآخر عنده زر ⋮ خاص به)
    public interface DotsListener { void onDots(View anchor); }
    private DotsListener dotsListener;
    public void setDotsListener(DotsListener r) { dotsListener = r; }
    public void setPhotoCount(int n) { total = Math.max(1, n); updatePill(); }

    // ---- قائمة الصور (الأحدث/الحالية أولاً): تمرير أفقي ونقر على الحافتين وهي مكبّرة
    public interface PhotoLoader { Bitmap load(String id); }
    private java.util.List<String> pIds = new ArrayList<>();
    private PhotoLoader loader;
    private int pIdx = 0, total = 1, gen = 0, dragDir = 0;
    private boolean swapping = false;
    private final java.util.HashMap<Integer, Bitmap> pCache = new java.util.HashMap<>();

    /** يضبط قائمة الصور؛ startIdx = -1 يُبقي الفهرس الحالي (مع ضبطه ضمن الحدود). */
    public void setPhotoIds(java.util.List<String> ids, PhotoLoader l, int startIdx) {
        if (ids != null && startIdx < 0 && ids.equals(pIds)) { loader = l; return; }   // لا تغيير: لا نعيد الضبط ولا نقطع العرض
        gen++;
        synchronized (pCache) { pCache.clear(); }
        pIds = ids != null ? new ArrayList<>(ids) : new ArrayList<String>();
        loader = l;
        total = Math.max(1, pIds.size());
        pIdx = startIdx >= 0 ? startIdx : pIdx;
        pIdx = Math.max(0, Math.min(pIdx, total - 1));
        updatePill();
        if (hero.getVisibility() == View.VISIBLE) { showCurrent(); prefetch(); }
    }
    public void setPhotoIds(java.util.List<String> ids, PhotoLoader l) { setPhotoIds(ids, l, -1); }
    public int getIndex() { return pIdx; }
    public int getCount() { return Math.max(1, pIds.size()); }
    public String getCurrentId() { return pIdx >= 0 && pIdx < pIds.size() ? pIds.get(pIdx) : null; }
    public Bitmap getCurrentBitmap() { return bitmapAt(pIdx); }

    /** فهرس دائري: بعد الأخيرة تأتي الأولى وقبل الأولى تأتي الأخيرة. */
    private int wrap(int i) { int n = Math.max(1, pIds.size()); return ((i % n) + n) % n; }

    private void updatePill() {
        countPill.setText(String.format(java.util.Locale.US, "%d/%d", pIdx + 1, Math.max(1, total)));
        // لا صور ← لا عدّاد وزر الرجوع ظاهر؛ توجد صور ← العدّاد ظاهر وزر الرجوع محذوف (ملف الشخص الآخر)
        boolean has = pIds != null && !pIds.isEmpty();
        countPill.setVisibility(has ? View.VISIBLE : View.GONE);
        if (!own && backBtn != null) backBtn.setVisibility(has ? View.INVISIBLE : View.VISIBLE);
    }

    /** صورة الفهرس i: الأول = صورة الملف الحالية، والباقي من الذاكرة المؤقتة أو بفك سريع. */
    private Bitmap bitmapAt(int i) {
        Bitmap b = null;
        if (i == 0 && photo != null) b = photo.get();
        if (b != null && !b.isRecycled()) return b;
        if (i < 0 || i >= pIds.size() || loader == null) return null;
        synchronized (pCache) { b = pCache.get(i); }
        if (b != null && !b.isRecycled()) return b;
        try { b = loader.load(pIds.get(i)); } catch (Throwable t) { b = null; }
        if (b != null) synchronized (pCache) { pCache.put(i, b); }
        return b;
    }

    /** يجهّز الصورتين المجاورتين في خيط خلفي ويحذف البعيد منها لتوفير الذاكرة. */
    private void prefetch() {
        if (loader == null) return;
        final int g = gen, c = pIdx;
        final java.util.List<String> ids = pIds;
        synchronized (pCache) {
            java.util.Iterator<java.util.Map.Entry<Integer, Bitmap>> it = pCache.entrySet().iterator();
            final int n0 = Math.max(1, ids.size());
            while (it.hasNext()) { int dd = Math.abs(it.next().getKey() - c); if (Math.min(dd, n0 - dd) > 1) it.remove(); }
        }
        new Thread(() -> {
            for (int k0 : new int[]{c + 1, c - 1}) {
                int k = ((k0 % Math.max(1, ids.size())) + Math.max(1, ids.size())) % Math.max(1, ids.size());
                if (k <= 0 || k >= ids.size()) continue;
                synchronized (pCache) { if (pCache.containsKey(k)) continue; }
                Bitmap b = null;
                try { b = loader.load(ids.get(k)); } catch (Throwable ignored) { }
                if (b != null && g == gen) synchronized (pCache) { pCache.put(k, b); }
            }
        }).start();
    }

    /** امتداد مموّه (تصغير شديد) يلتصق بأسفل الصورة. */
    /**
     * الامتداد المموّه أسفل الصورة: شريط من أسفل المربع الذي تعرضه الصورة فعلاً (نفس قصّ CENTER_CROP) مصغّراً جداً ثم ممدوداً.
     * (كان يُبنى من الصورة كاملة فيختلف توزيع الألوان عن المربع المعروض ويظهر لون برتقالي/جلدي في منتصف الصورة حيث تتلاشى.)
     */
    private void setExt(ImageView ext, Bitmap bm) {
        try {
            int bw = bm.getWidth(), bh = bm.getHeight(), s = Math.min(bw, bh);
            int x0 = (bw - s) / 2, y0 = (bh - s) / 2;
            int sh = Math.max(1, (int) (s * 0.30f));
            Bitmap cur = Bitmap.createBitmap(bm, x0, y0 + s - sh, s, sh);
            // تصغير على مراحل (نصف في كل مرة) ثم بلور حقيقي: صورة 24×8 الممدودة كانت تُظهر خطوطاً أفقية/عمودية عند حدود الصفوف
            while (cur.getWidth() / 2 >= 96 && cur.getHeight() / 2 >= 32) {
                cur = Bitmap.createScaledBitmap(cur, cur.getWidth() / 2, cur.getHeight() / 2, true);
            }
            Bitmap sm = Bitmap.createScaledBitmap(cur, 96, 32, true);
            int[] px = new int[96 * 32];
            sm.getPixels(px, 0, 96, 0, 0, 96, 32);
            blurPixels(px, 96, 32, 4, 3);
            Bitmap out = Bitmap.createBitmap(96, 32, Bitmap.Config.ARGB_8888);
            out.setPixels(px, 0, 96, 0, 0, 96, 32);
            ext.setScaleType(ImageView.ScaleType.FIT_XY);
            ext.setImageBitmap(out);
        } catch (Throwable t) { ext.setImageBitmap(null); }
    }

    /** بلور صندوقي متكرر (≈ غاوسي) على قنوات RGB. */
    private static void blurPixels(int[] px, int w, int h, int r, int passes) {
        float[][] ch = new float[3][w * h];
        for (int i = 0; i < px.length; i++) {
            ch[0][i] = (px[i] >> 16) & 0xFF; ch[1][i] = (px[i] >> 8) & 0xFF; ch[2][i] = px[i] & 0xFF;
        }
        float[] tmp = new float[w * h];
        for (int c = 0; c < 3; c++) {
            for (int k = 0; k < passes; k++) {
                box(ch[c], tmp, w, h, r, true);
                box(tmp, ch[c], w, h, r, false);
            }
        }
        for (int i = 0; i < px.length; i++) {
            px[i] = 0xFF000000 | (Math.round(ch[0][i]) << 16) | (Math.round(ch[1][i]) << 8) | Math.round(ch[2][i]);
        }
    }

    private static void box(float[] src, float[] dst, int w, int h, int r, boolean horiz) {
        int outer = horiz ? h : w, len = horiz ? w : h, step = horiz ? 1 : w;
        float inv = 1f / (2 * r + 1);
        for (int o = 0; o < outer; o++) {
            int base = horiz ? o * w : o;
            float sum = 0;
            for (int i = -r; i <= r; i++) sum += src[base + Math.max(0, Math.min(len - 1, i)) * step];
            for (int i = 0; i < len; i++) {
                dst[base + i * step] = sum * inv;
                sum += src[base + Math.min(len - 1, i + r + 1) * step] - src[base + Math.max(0, i - r) * step];
            }
        }
    }

    /** تدرّج ناعم (smoothstep) من ألفا a0 إلى a1 بلون rgb: لا انكسار في المشتقة عند البداية والنهاية فلا تظهر خطوط ماخ. */
    private static int[] smoothAlphas(int rgb, float a0, float a1, float[] pos) {
        int n = pos.length;
        int[] cols = new int[n];
        for (int i = 0; i < n; i++) {
            float t = i / (float) (n - 1);
            pos[i] = t;
            float e = t * t * (3f - 2f * t);
            cols[i] = (Math.round((a0 + (a1 - a0) * e) * 255f) << 24) | (rgb & 0xFFFFFF);
        }
        return cols;
    }

    private void applyBitmapTo(FadeImageView iv, Bitmap bm) {
        if (bm == null) return;
        bm.setHasMipMap(true);
        iv.setImageBitmap(bm);
    }

    /** يعرض صورة الفهرس الحالي في الطبقة المكبّرة (والامتداد المموّه تحتها). */
    private void showCurrent() {
        Bitmap bm = bitmapAt(pIdx);
        if (bm == null) bm = photo != null ? photo.get() : null;
        if (bm == null) return;
        applyBitmapTo(heroImg, bm);
        heroImg.setTranslationX(0f);
        heroExt.setTranslationX(0f);
        setExt(heroExt, bm);
    }
    private float lastPullDy = 0f;
    public void setFullViewListener(FullViewListener l) { fullListener = l; }

    private final Activity act;
    private final PullScrollView sv;
    private final ViewGroup content;       // الابن المباشر لـ ScrollView
    private final View cover;              // أول ابن في المحتوى (الغلاف)
    private final View anchor;             // الابن المباشر للمحتوى الذي يحوي صف الأزرار؛ ما بعده ينزل
    private final TextView avatar;
    private final View infoBox;            // الصورة والاسم والحالة
    private final TextView nameV, statusV;
    private final View quick;              // صف/صفوف الأزرار
    private final float rangePx;
    private final float d;
    private final PhotoSource photo;
    private final View backBtn, moreBtn;   // لملف شخص آخر فقط (داخل الغلاف، تبقى فوق الصورة)
    private final Palette pal;
    private final ArrayList<TextView> btns = new ArrayList<>();
    private float[] btnElev = new float[0];

    // طبقة الصورة المكبّرة
    private final FrameLayout hero;
    private final FrameLayout coverUnder;      // لون الغلاف + النقوش + التدرّج (كلها خلف الصورة)
    private final CoverPattern coverPat;
    private final View coverFade;
    private final ImageView heroExt, heroExt2;     // heroExt2: امتداد الصورة القادمة أثناء التمرير (يتحرك ملاصقاً لها)
    private final FadeImageView heroImg, heroImg2;
    private final TextView heroLetter, heroName, heroStatus;
    private final View heroShade;
    private final LinearLayoutLtr infoHolder;

    private float W, size0, avatarTop, ringBottom, coverH, quickH, infoBottom, quickBottom;
    private float inset = 0f;               // حشو الحلقة الذهبية حول الأفاتار (الصورة الفعلية داخلها)
    private float p = 0f, p0 = 0f, lastPv = -1f;
    private boolean expanded = false, covered = false;
    private ValueAnimator anim;
    private float radiusPx = 0f, hW = 0f, hH = 0f;
    private Drawable svBg, coverBg;

    public ProfileHero(Activity act, PullScrollView sv, TextView avatar, View infoBox,
                       TextView nameV, TextView statusV, View quick, boolean own,
                       PhotoSource photo, View backBtn, View moreBtn, Palette pal) {
        this.act = act;
        this.sv = sv;
        this.content = (ViewGroup) sv.getChildAt(0);
        this.cover = content.getChildCount() > 0 ? content.getChildAt(0) : null;
        this.avatar = avatar;
        this.infoBox = infoBox;
        this.nameV = nameV;
        this.statusV = statusV;
        this.quick = quick;
        this.photo = photo;
        this.backBtn = backBtn;
        this.moreBtn = moreBtn;
        this.pal = pal;
        this.d = act.getResources().getDisplayMetrics().density;
        this.rangePx = (own ? 320f : 150f) * d;
        this.own = own;

        // الابن المباشر للمحتوى الذي يحوي الأزرار (يتغير مكانه حسب الـ XML)
        View a = quick != null ? quick : infoBox;
        while (a.getParent() != content && a.getParent() instanceof View) a = (View) a.getParent();
        this.anchor = a;

        // الأزرار تخرج من حدود أبيها أثناء الصعود فوق الصورة: نمنع القص على كل الطريق حتى الـ ScrollView
        View w = quick != null ? quick : infoBox;
        while (w != null && w != sv) {
            ViewParent pp = w.getParent();
            if (pp instanceof ViewGroup) {
                ((ViewGroup) pp).setClipChildren(false);
                ((ViewGroup) pp).setClipToPadding(false);
            }
            w = pp instanceof View ? (View) pp : null;
        }
        collectButtons(quick);

        // --- بناء الطبقات تحت ScrollView داخل نفس الأب (الأزرار والمحتوى يبقيان فوق الصورة) ---
        hero = new FrameLayout(act);
        hero.setVisibility(View.GONE);
        hero.setBackgroundColor(Color.TRANSPARENT);   // كان كحلياً فيظهر أسفل الصورة كلون أسود/أزرق أثناء السحب
        hero.setClipToOutline(true);
        hero.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, (int) hW, (int) hH, radiusPx);
            }
        });

        // الامتداد المموّه أسفل المربع: نسخة مصغّرة جداً من الصورة تُمدّد (blur) + brightness(.9)
        heroExt = new ImageView(act);
        heroExt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        heroExt.setColorFilter(0xFFE6E6E6, PorterDuff.Mode.MULTIPLY);
        hero.addView(heroExt, new FrameLayout.LayoutParams(-1, -1));
        heroExt2 = new ImageView(act);
        heroExt2.setScaleType(ImageView.ScaleType.CENTER_CROP);
        heroExt2.setColorFilter(0xFFE6E6E6, PorterDuff.Mode.MULTIPLY);
        heroExt2.setVisibility(View.GONE);
        hero.addView(heroExt2, new FrameLayout.LayoutParams(-1, -1));

        heroImg = new FadeImageView(act);
        heroImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        hero.addView(heroImg, new FrameLayout.LayoutParams(-1, 0));

        // الصورة القادمة أثناء السحب الأفقي (تنزلق بجانب الحالية)
        heroImg2 = new FadeImageView(act);
        heroImg2.setScaleType(ImageView.ScaleType.CENTER_CROP);
        heroImg2.setVisibility(View.GONE);
        hero.addView(heroImg2, new FrameLayout.LayoutParams(-1, 0));

        heroLetter = new TextView(act);
        heroLetter.setGravity(Gravity.CENTER);
        heroLetter.setTextColor(0xFFFFFFFF);
        heroLetter.setTypeface(null, android.graphics.Typeface.BOLD);
        hero.addView(heroLetter, new FrameLayout.LayoutParams(-1, 0));

        // .pf-hero-shade : rgba(0,0,0,0) -> rgba(0,0,0,.55)
        heroShade = new View(act);
        final float[] shPos = new float[12];
        final int[] shCols = smoothAlphas(0x000000, 0f, 0x8C / 255f, shPos);
        android.graphics.drawable.ShapeDrawable sh = new android.graphics.drawable.ShapeDrawable(new android.graphics.drawable.shapes.RectShape());
        sh.setShaderFactory(new android.graphics.drawable.ShapeDrawable.ShaderFactory() {
            @Override public Shader resize(int w, int h) {
                return new LinearGradient(0, 0, 0, h, shCols, shPos, Shader.TileMode.CLAMP);
            }
        });
        heroShade.setBackground(sh);
        hero.addView(heroShade, new FrameLayout.LayoutParams(-1, 0));

        LinearLayoutLtr info = new LinearLayoutLtr(act);
        heroName = new TextView(act);
        heroName.setTextColor(0xFFFFFFFF);
        heroName.setTextSize(24);
        heroName.setTypeface(null, android.graphics.Typeface.BOLD);
        heroName.setShadowLayer(0, 0, 0, 0);
        heroName.setTextDirection(View.TEXT_DIRECTION_LTR);
        heroStatus = new TextView(act);
        heroStatus.setTextColor(0xFFFFFFFF);
        heroStatus.setTextSize(13.5f);
        heroStatus.setTypeface(null, android.graphics.Typeface.BOLD);
        heroStatus.setShadowLayer(0, 0, 0, 0);
        heroStatus.setPadding(0, (int) (3 * d), 0, 0);
        heroStatus.setTextDirection(View.TEXT_DIRECTION_LTR);
        heroStatus.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        info.setGravity(Gravity.LEFT);
        info.addView(heroName, new android.widget.LinearLayout.LayoutParams(-2, -2));
        info.addView(heroStatus, new android.widget.LinearLayout.LayoutParams(-2, -2));
        info.setAlpha(0f);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.LEFT);
        ilp.leftMargin = (int) (18 * d);
        ilp.rightMargin = (int) (18 * d);
        hero.addView(info, ilp);
        this.infoHolder = info;

        // في الـ HTML النقوش والتدرّج جزء من .pf-cover نفسه والصورة (z-index:1) فوقه؛ هنا الغلاف الأصلي
        // داخل الـ ScrollView (فوق الصورة) فنبني نسخة منه تحت الصورة ونُخفي الأصل أثناء السحب.
        coverUnder = new FrameLayout(act);
        coverUnder.setVisibility(View.GONE);
        coverPat = new CoverPattern(act);
        coverUnder.addView(coverPat, new FrameLayout.LayoutParams(-1, -1));
        coverFade = new View(act);
        coverUnder.addView(coverFade, new FrameLayout.LayoutParams(-1, 0, Gravity.BOTTOM));

        ViewGroup host = (ViewGroup) sv.getParent();
        int at = host.indexOfChild(sv);
        host.addView(coverUnder, at, new FrameLayout.LayoutParams(-1, 0));
        host.addView(hero, at + 1, new FrameLayout.LayoutParams(0, 0));

        // ---- طبقة علوية: pill «1/N» + ⋮ (فوق كل شيء، تظهر مع الاسم عند اكتمال التكبير)
        topCtl = new FrameLayout(act);
        topCtl.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        topCtl.setVisibility(View.GONE);
        topCtl.setAlpha(0f);
        countPill = new TextView(act);
        countPill.setText("1/1");
        countPill.setTextColor(0xFFFFFFFF);
        countPill.setTextSize(15);
        countPill.setGravity(Gravity.CENTER);
        countPill.setPadding((int) (12 * d), 0, (int) (12 * d), 0);
        countPill.setTextDirection(View.TEXT_DIRECTION_LTR);
        countPill.setVisibility(View.GONE);   // يظهر فقط عند وجود صور (updatePill)
        GradientDrawable pb = new GradientDrawable();
        pb.setColor(0x66707070);
        pb.setCornerRadius(40 * d);
        countPill.setBackground(pb);
        topCtl.addView(countPill, new FrameLayout.LayoutParams(-2, (int) (25 * d), Gravity.TOP | Gravity.LEFT));   // العدّاد يسار الشاشة وأنحف بـ 2dp (27 ← 25)
        if (own) {
            dotsBtn = new View(act) {
                private final Paint dp = new Paint(Paint.ANTI_ALIAS_FLAG);
                @Override protected void onDraw(Canvas c) {
                    dp.setColor(0xFFFFFFFF);
                    float cx = getWidth() / 2f, cy = getHeight() / 2f, r = 2.3f * d, g = 7f * d;
                    c.drawCircle(cx, cy - g, r, dp); c.drawCircle(cx, cy, r, dp); c.drawCircle(cx, cy + g, r, dp);
                }
            };
            dotsBtn.setClickable(true);
            dotsBtn.setOnClickListener(v -> { if (dotsListener != null) dotsListener.onDots(v); });
            topCtl.addView(dotsBtn, new FrameLayout.LayoutParams((int) (48 * d), (int) (48 * d), Gravity.TOP | Gravity.RIGHT));
        } else {
            dotsBtn = null;
        }
        host.addView(topCtl, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        sv.setController(this);
    }

    /** LinearLayout رأسي بإتجاه LTR (الاسم على اليسار فوق الصورة كما في الـ HTML). */
    private static final class LinearLayoutLtr extends android.widget.LinearLayout {
        LinearLayoutLtr(Activity a) {
            super(a);
            setOrientation(VERTICAL);
            setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        }
    }

    /**
     * صورة المستخدم المربعة: أسفلها يتلاشى إلى الامتداد المموّه خلفها
     * (mask-image: linear-gradient(to bottom, #000 calc(100% - ext*1.15px), transparent)).
     */
    private static final class FadeImageView extends ImageView {
        private static int[] smoothAlphas(int rgb, float a0, float a1, float[] pos) { return ProfileHero.smoothAlphas(rgb, a0, a1, pos); }
        private final Paint mask = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float fade = 0f;
        private int edgeSide = 0;          // -1: الحافة اليسرى تتلاشى، +1: اليمنى، 0: بلا تلاشٍ
        private final float edgePx;        // عرض التلاشي عند الحدّ بين صورتين أثناء التمرير

        FadeImageView(Context c) {
            super(c);
            mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
            edgePx = 26f * c.getResources().getDisplayMetrics().density;
        }

        void setFade(float px) {
            if (Math.abs(px - fade) > 0.5f) { fade = px; invalidate(); }
        }

        /** تلاشٍ خفيف لحافة الصورة المواجهة للصورة المجاورة أثناء التمرير. */
        void setEdge(int side) {
            if (side != edgeSide) { edgeSide = side; invalidate(); }
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            boolean bot = fade > 0.5f, edge = edgeSide != 0 && w > 0;
            if ((!bot && !edge) || h <= 0) { super.onDraw(c); return; }
            int sc = c.saveLayer(0, 0, w, h, null);
            super.onDraw(c);
            if (bot) {
                float f = Math.min(fade, h);
                float[] pos = new float[12];
                int[] cols = smoothAlphas(0x000000, 1f, 0f, pos);
                mask.setShader(new LinearGradient(0, h - f, 0, h, cols, pos, Shader.TileMode.CLAMP));
                c.drawRect(0, h - f, w, h, mask);
            }
            if (edge) {
                float e = Math.min(edgePx, w / 2f);
                if (edgeSide > 0) {
                    mask.setShader(new LinearGradient(w - e, 0, w, 0, 0xFF000000, 0x00000000, Shader.TileMode.CLAMP));
                    c.drawRect(w - e, 0, w, h, mask);
                } else {
                    mask.setShader(new LinearGradient(0, 0, e, 0, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
                    c.drawRect(0, 0, e, h, mask);
                }
            }
            mask.setShader(null);
            c.restoreToCount(sc);
        }
    }

    /** dir>0: القادمة من اليمين (الحالية تتلاشى يمينها والقادمة يسارها)، dir<0 العكس، 0: إزالة التلاشي. */
    private void seam(int dir) {
        // لا تلاشي لحواف الصور: كان يكشف الغلاف (الأخضر/النقوش) بين الصورتين، ولا يشمل الامتداد السفلي (heroExt) فيظهر خط عمودي حادّ.
        // الحدّ الآن مستقيم ومتّسق في الصورة والامتداد السفلي معاً.
        heroImg.setEdge(0);
        heroImg2.setEdge(0);
    }

    private void collectButtons(View v) {
        if (v == null) return;
        if (v instanceof TextView) { btns.add((TextView) v); return; }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectButtons(g.getChildAt(i));
        }
    }

    // ------------------------------------------------------------- قياس
    private float topInContent(View v) {
        float t = 0;
        View c = v;
        while (c != null && c != content) {
            t += c.getTop();
            if (!(c.getParent() instanceof View)) break;
            c = (View) c.getParent();
        }
        return t;
    }

    private void measure() {
        W = sv.getWidth();
        size0 = avatar.getWidth() - 2 * inset;
        avatarTop = topInContent(avatar) + inset;
        ringBottom = avatarTop + size0;
        coverH = cover != null ? cover.getHeight() : 0;
        quickH = quick != null ? quick.getHeight() : 0;
        infoBottom = topInContent(anchor) + anchor.getHeight();
        quickBottom = quick != null ? topInContent(quick) + quick.getHeight() : infoBottom;
    }

    private void prepareContent() {
        // محتوى الطبقة: صورة المستخدم أو الحرف
        Bitmap bm = bitmapAt(pIdx);
        if (bm == null && photo != null) bm = photo.get();
        if (bm != null) {
            bm.setHasMipMap(true);
            heroImg.setImageBitmap(bm);
            heroImg.setTranslationX(0f);
            heroImg.setVisibility(View.VISIBLE);
            heroLetter.setVisibility(View.GONE);
            hero.setBackgroundColor(Color.TRANSPARENT);   // كان كحلياً فيظهر أسفل الصورة كلون أسود/أزرق أثناء السحب
            setExt(heroExt, bm);
        } else {
            heroImg.setVisibility(View.GONE);
            heroLetter.setVisibility(View.VISIBLE);
            heroLetter.setText(avatar.getText());
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{0xFFE8C25A, 0xFFB88A1C});
            hero.setBackground(g);
            heroExt.setImageBitmap(null);
        }
        heroName.setText(nameV != null ? nameV.getText() : "");
        heroStatus.setText(statusV != null ? statusV.getText().toString().replace("●", "").trim() : "");
        // نفس النقطة الخضراء فوق الصورة
        Drawable[] sd = statusV != null ? statusV.getCompoundDrawables() : null;
        Drawable dotD = null;
        if (sd != null && sd[2] != null && sd[2].getConstantState() != null) {
            dotD = sd[2].getConstantState().newDrawable().mutate();
            dotD.setBounds(sd[2].getBounds());
        }
        heroStatus.setCompoundDrawablePadding((int) (4 * d));
        heroStatus.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        heroStatus.setCompoundDrawables(null, null, dotD, null);
    }

    private static float clamp(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    /** color-mix(in srgb, c1 t%, c0) — الدمج بالألوان المضروبة مسبقاً في ألفا مثل CSS. */
    private static int mix(int c0, int c1, float t) {
        float a0 = Color.alpha(c0) / 255f, a1 = Color.alpha(c1) / 255f;
        float a = a0 + (a1 - a0) * t;
        if (a <= 0.0001f) return 0;
        float r = (Color.red(c0) * a0 + (Color.red(c1) * a1 - Color.red(c0) * a0) * t) / a;
        float g = (Color.green(c0) * a0 + (Color.green(c1) * a1 - Color.green(c0) * a0) * t) / a;
        float b = (Color.blue(c0) * a0 + (Color.blue(c1) * a1 - Color.blue(c0) * a0) * t) / a;
        return Color.argb(Math.round(a * 255f), Math.min(255, Math.round(r)),
                Math.min(255, Math.round(g)), Math.min(255, Math.round(b)));
    }

    // ------------------------------------------------- طبقة الغلاف + شفافية الصفحة
    /** الصفحة شفافة والغلاف ينتقل خلف الصورة (في الـ HTML: الصورة z-index:1 فوق الغلاف). */
    private void enterCover() {
        if (covered) return;
        covered = true;
        svBg = sv.getBackground();
        sv.setBackgroundColor(Color.TRANSPARENT);
        Drawable cd = null;
        if (cover != null) {
            coverBg = cover.getBackground();
            if (coverBg != null) {
                Drawable.ConstantState cs = coverBg.getConstantState();
                cd = cs != null ? cs.newDrawable().mutate() : coverBg;
            }
            cover.setBackground(null);
        }
        coverUnder.setBackground(cd != null ? cd : new ColorDrawable(0xFF1B2A4A));
        coverUnder.setPadding(0, cover != null ? cover.getPaddingTop() : 0, 0, 0);
        Drawable fd = null;
        View fv = cover != null ? cover.findViewWithTag("fade") : null;
        if (fv != null && fv.getBackground() != null) {
            Drawable.ConstantState fs = fv.getBackground().getConstantState();
            fd = fs != null ? fs.newDrawable().mutate() : null;
        }
        coverFade.setBackground(fd);
        coverUnder.setVisibility(View.VISIBLE);
    }

    /**
     * تغيير غلاف الصفحة (applyCover في الـ HTML): التدرّج + رموز النقوش.
     * أثناء السحب (الغلاف منسوخ خلف الصورة) يُحدَّث النسخة والأصل المحفوظ ليعودا بالغلاف الجديد.
     */
    public void setCoverTheme(CoverThemes.Theme t) {
        if (t == null) return;
        coverPat.setTheme(t);
        if (covered) {
            coverBg = CoverThemes.gradient(t);
            coverUnder.setBackground(CoverThemes.gradient(t));
        } else if (cover != null) {
            cover.setBackground(CoverThemes.gradient(t));
        }
    }

    private void leaveCover() {
        if (!covered) return;
        covered = false;
        sv.setBackground(svBg);
        if (cover != null) {
            cover.setBackground(coverBg);
            fadeCover(1f);
        }
        coverUnder.setVisibility(View.GONE);
        coverUnder.setBackground(null);
    }

    /** زخرفة الغلاف وتدرّج أسفله تتلاشى؛ أزرار الرجوع/المزيد تبقى ظاهرة فوق الصورة. */
    private void fadeCover(float a) {
        if (!(cover instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) cover;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c == backBtn || c == moreBtn) continue;
            Object tg = c.getTag();
            // النقوش والتدرّج لهما نسخة تحت الصورة: الأصل يختفي فوراً حتى لا يغطي الصورة
            boolean twin = "fade".equals(tg) || "pattern".equals(tg);
            c.setAlpha(twin ? (a >= 1f ? 1f : 0f) : a);
        }
    }

    // ------------------------------------------------- أزرار زجاجية فوق الصورة
    /**
     * .pf-quick .pf-q : background color-mix(rgba(255,255,255,.16) pv%, card)،
     * color/أيقونة color-mix(#fff pv%, gold)، النص color-mix(#fff pv%, txt)،
     * زر الرسالة الذهبي: color-mix(rgba(212,169,55,.55) pv%, gold)، زر الحظر: #ffb4b4.
     * pv = round(clamp(2v)*8)/8 وبحد أدنى 0.125.
     */
    private void applyGlass(float v) {
        float pv = v > 0.001f ? Math.max(0.125f, Math.round(clamp(v * 2f) * 8f) / 8f) : 0f;
        if (pv == lastPv || pal == null) return;
        lastPv = pv;
        for (int i = 0; i < btns.size(); i++) {
            TextView t = btns.get(i);
            Object tg = t.getTag();
            String s = tg instanceof String ? (String) tg : "";
            boolean primary = s.contains("gold");
            boolean red = s.contains("redo");

            int fill = primary ? mix(pal.gold(), 0x8CD4A937, pv) : mix(pal.surface(), 0x29FFFFFF, pv);
            int white = red ? 0xFFFFB4B4 : 0xFFFFFFFF;
            int txt = mix(primary ? 0xFFFFFFFF : (red ? pal.red() : pal.text()), white, pv);
            boolean ibl = s.contains("ibl"), ird = s.contains("ird");
            int ico = mix(ibl ? pal.blue() : ird ? pal.redIcon() : (primary ? 0xFFFFFFFF : (red ? pal.red() : pal.gold())),
                    ibl ? 0xFFB9D8FF : ird ? 0xFFFFB4B4 : white, pv);

            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(16 * d);
            if (primary && pv <= 0.001f) { g.setOrientation(GradientDrawable.Orientation.TL_BR); g.setColors(new int[]{0xFFF5D875, 0xFFB98B23}); }
            else g.setColor(fill);
            t.setBackground(g);
            t.setTextColor(txt);
            for (Drawable dd : t.getCompoundDrawablesRelative()) {
                if (dd != null) dd.mutate().setColorFilter(ico, PorterDuff.Mode.SRC_IN);
            }
            // الظل يختفي مع الزجاج (box-shadow:none)
            if (i < btnElev.length) t.setElevation(btnElev[i] * (1f - pv));
        }
    }

    // ------------------------------------------------------------- تطبيق v
    /** يضبط مكان الـ pill والنقاط: ملفي = تحت شريط الحالة؛ ملف آخر = بمحاذاة زر ⋮ الموجود. */
    private void alignTop() {
        ViewGroup host = (ViewGroup) topCtl.getParent();
        if (host == null) return;
        int sbId = act.getResources().getIdentifier("status_bar_height", "dimen", "android");
        float sb = sbId > 0 ? act.getResources().getDimensionPixelSize(sbId) : 24 * d;
        float cy = sb + 29 * d;
        float rightPill = 55 * d;
        if (!own && moreBtn != null && moreBtn.getWidth() > 0) {
            int[] a = new int[2], h = new int[2];
            moreBtn.getLocationInWindow(a); host.getLocationInWindow(h);
            cy = a[1] - h[1] + moreBtn.getHeight() / 2f;
            rightPill = host.getWidth() - (a[0] - h[0]) + 2 * d;
        }
        FrameLayout.LayoutParams pl = (FrameLayout.LayoutParams) countPill.getLayoutParams();
        pl.topMargin = (int) (cy - 12.5f * d);   // منتصفه بمحاذاة منتصف النقاط (ارتفاع 25dp)
        pl.leftMargin = (int) (18 * d);          // نفس هامش الاسم والأزرار من اليسار
        pl.rightMargin = 0;
        countPill.setLayoutParams(pl);
        if (dotsBtn != null) {
            FrameLayout.LayoutParams dl = (FrameLayout.LayoutParams) dotsBtn.getLayoutParams();
            dl.topMargin = (int) (cy - 24 * d);
            dl.rightMargin = (int) (7 * d);
            dotsBtn.setLayoutParams(dl);
        }
    }

    private void enterHero() {
        alignTop();
        hero.setVisibility(View.VISIBLE);
        avatar.setAlpha(0f);
        enterCover();
        btnElev = new float[btns.size()];
        for (int i = 0; i < btns.size(); i++) btnElev[i] = btns.get(i).getElevation();
        lastPv = -1f;
    }

    // ---- ظهور الاسم/العدّاد/⋮: تلاشٍ زمني خفيف
    private boolean infoOn = false;
    private float infoA = 0f;
    private ValueAnimator infoAnim;

    private void setInfoAlpha(float a) {
        infoA = a;
        infoHolder.setAlpha(a);
        topCtl.setAlpha(a);
        topCtl.setVisibility(a > 0.01f ? View.VISIBLE : View.GONE);
    }

    private void updateInfo(boolean on) {
        if (on == infoOn) return;
        infoOn = on;
        if (infoAnim != null) infoAnim.cancel();
        infoAnim = ValueAnimator.ofFloat(infoA, on ? 1f : 0f);
        infoAnim.setDuration(on ? 260 : 120);
        infoAnim.setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f));
        infoAnim.addUpdateListener(a -> setInfoAlpha((Float) a.getAnimatedValue()));
        infoAnim.start();
    }

    private void resetInfo() {
        if (infoAnim != null) { infoAnim.cancel(); infoAnim = null; }
        infoOn = false;
        setInfoAlpha(0f);
    }

    private void apply(float v) {
        p = clamp(v);
        if (p <= 0.001f) { hideHero(); return; }
        if (hero.getVisibility() != View.VISIBLE) enterHero();

        float size = size0 + (W - size0) * p;
        float ext = (quickH + 23 * d) * clamp(p * 2f);   // أسفل الصورة مرفوع 5dp (كان 28dp) والأزرار تتبعه
        float top = avatarTop * (1f - p);
        float left = (W - size) / 2f;
        float heroH = size + ext;

        // الغلاف يكبر خلف الصورة: cover.height = coverH + delta*v
        float delta = Math.max(0f, W - ringBottom);
        FrameLayout.LayoutParams cu = (FrameLayout.LayoutParams) coverUnder.getLayoutParams();
        cu.height = (int) (coverH + delta * p);
        coverUnder.setLayoutParams(cu);
        fadeCover(clamp(1f - p * 6f));
        FrameLayout.LayoutParams fl = (FrameLayout.LayoutParams) coverFade.getLayoutParams();
        fl.height = (int) (cu.height * 0.55f);          // .pf-cover::after { height: 55% }
        coverFade.setLayoutParams(fl);

        ViewGroup.LayoutParams lp = hero.getLayoutParams();
        lp.width = (int) size;
        lp.height = (int) heroH;
        hero.setLayoutParams(lp);
        hero.setTranslationX(left);
        hero.setTranslationY(top);

        FrameLayout.LayoutParams ip = (FrameLayout.LayoutParams) heroImg.getLayoutParams();
        ip.height = (int) size; heroImg.setLayoutParams(ip);
        heroImg.setFade(ext > 0 ? ext * 1.15f : 0f);
        FrameLayout.LayoutParams ip2 = (FrameLayout.LayoutParams) heroImg2.getLayoutParams();
        ip2.height = (int) size; heroImg2.setLayoutParams(ip2);
        heroImg2.setFade(ext > 0 ? ext * 1.15f : 0f);
        FrameLayout.LayoutParams lt = (FrameLayout.LayoutParams) heroLetter.getLayoutParams();
        lt.height = (int) size; heroLetter.setLayoutParams(lt);
        heroLetter.setTextSize(Math.max(20f, (size / d) * 0.34f));
        FrameLayout.LayoutParams sp = (FrameLayout.LayoutParams) heroShade.getLayoutParams();
        sp.topMargin = (int) (size * 0.35f);
        sp.height = (int) (size * 0.65f + ext);
        heroShade.setLayoutParams(sp);
        // التظليل الداكن أسفل الصورة يظهر تدريجياً مع التكبير (كان كاملاً من أول سحبة فتبدو الصورة معتمة)
        float shA = clamp((p - 0.3f) / 0.5f);
        heroShade.setAlpha(shA * shA * (3f - 2f * shA));
        heroExt.setVisibility(ext > 0 ? View.VISIBLE : View.INVISIBLE);
        {   // الامتداد يبدأ من حيث يبدأ تلاشي الصورة (لا من أعلى الهيرو) فيتّصل بها بلا خط فاصل
            float fz = Math.min(ext > 0 ? ext * 1.15f : 0f, size);
            int et = (int) (size - fz);
            for (ImageView ev : new ImageView[]{heroExt, heroExt2}) {
                FrameLayout.LayoutParams elp = (FrameLayout.LayoutParams) ev.getLayoutParams();
                if (elp.topMargin != et || elp.height != (int) (heroH - et)) {
                    elp.topMargin = et; elp.height = Math.max(1, (int) (heroH - et));
                    ev.setLayoutParams(elp);
                }
            }
        }
        // البلور (الامتداد المموّه) يظهر تدريجياً مع التكبير بدل أن يبرز دفعةً واحدة، ويختفي تدريجياً عند الإغلاق
        // الامتداد المموّه يظهر كاملاً من أول السحب: الحافة السفلية للصورة تذوب فيه (كان يبدأ شفافاً فيظهر خلفه ضباب أبيض)
        float blurA = 1f;
        heroExt.setAlpha(blurA);
        heroExt2.setAlpha(blurA);

        radiusPx = (size / 2f) * (1f - p);
        hW = size; hH = heroH;
        hero.invalidateOutline();

        updateInfo(p >= 0.8f);   // الاسم يظهر بتلاشٍ زمني ناعم بدل أن يتبع نسبة السحب إطاراً بإطار
        FrameLayout.LayoutParams ilp = (FrameLayout.LayoutParams) infoHolder.getLayoutParams();
        int iw = (int) Math.max(0f, W - 36 * d);   // عرض ثابت = العرض النهائي: لا يُعاد تقسيم الاسم على سطرين أثناء التكبير (الهيرو يقصّ الزائد)
        if (ilp.width != iw) ilp.width = iw;
        ilp.bottomMargin = (int) (16 * d + ext);
        infoHolder.setLayoutParams(ilp);

        // الاسم/الحالة الأصليان يتلاشيان، والأزرار تصعد فوق أسفل الصورة
        float fade = clamp(1f - p * 6f);               // الاسم الأصلي (داكن) يختفي بسرعة قبل أن تصل الصورة إليه
        if (nameV != null) nameV.setAlpha(fade);
        if (statusV != null) statusV.setAlpha(fade);
        float heroBottom = top + heroH;
        if (quick != null) quick.setTranslationY((heroBottom - 14 * d - quickBottom) * p);
        applyGlass(p);

        // باقي المحتوى ينزل ليبدأ بعد الصورة الممتدة
        float finalStart = W + (quickH + 23 * d) + 10 * d;   // المحتوى تحت الصورة يصعد 5dp معها
        float shift = (finalStart - infoBottom) * p;
        boolean after = false;
        for (int i = 0; i < content.getChildCount(); i++) {
            View c = content.getChildAt(i);
            if (after) c.setTranslationY(shift);
            if (c == anchor) after = true;
        }
    }

    private void hideHero() {
        resetInfo();
        boolean was = covered || hero.getVisibility() == View.VISIBLE;
        hero.setVisibility(View.GONE);
        topCtl.setVisibility(View.GONE);
        if (was) {
            avatar.setAlpha(1f);
            if (nameV != null) nameV.setAlpha(1f);
            if (statusV != null) statusV.setAlpha(1f);
            if (quick != null) quick.setTranslationY(0f);
            for (int i = 0; i < content.getChildCount(); i++) content.getChildAt(i).setTranslationY(0f);
            leaveCover();
            for (int i = 0; i < btns.size() && i < btnElev.length; i++) btns.get(i).setElevation(btnElev[i]);
            lastPv = -1f;
            if (pal != null) pal.restyle();     // الأزرار تعود لشكل البطاقة الأصلي بالثيم الحالي
        }
        p = 0f;
        expanded = false;
        resetSwipe();
        if (pIdx != 0) { pIdx = 0; updatePill(); }
    }

    private void resetSwipe() {
        heroImg.animate().cancel();
        heroImg2.animate().cancel();
        heroExt.animate().cancel();
        heroExt2.animate().cancel();
        heroImg.setTranslationX(0f);
        heroImg2.setTranslationX(0f);
        heroExt.setTranslationX(0f);
        heroExt2.setTranslationX(0f);
        heroImg2.setVisibility(View.GONE);
        heroExt2.setVisibility(View.GONE);
        seam(0);
        dragDir = 0;
        swapping = false;
    }

    /** يُستدعى عند مغادرة الشاشة أو تغيير الثيم. */
    public void reset() {
        if (anim != null) anim.cancel();
        if (covered || hero.getVisibility() == View.VISIBLE) hideHero();
        p = 0f;
        expanded = false;
    }

    // ------------------------------------------------- PullScrollView.Controller
    @Override public boolean canStart(float dy, int scrollY) {
        if (expanded) return true;                 // أثناء الفتح: أي سحب عمودي يتحكم بالإغلاق
        return scrollY == 0 && dy > 0 && avatar.getWidth() > 0;
    }

    @Override public void onStart() {
        lastPullDy = 0f;
        if (anim != null) anim.cancel();
        measure();
        prepareContent();
        p0 = p;
        sv.scrollTo(0, 0);
    }

    @Override public void onPull(float totalDy) {
        lastPullDy = totalDy;
        apply(p0 + totalDy / rangePx);
    }

    @Override public void onRelease() {
        // الصورة مكبّرة أصلاً ثم سُحبت للأسفل مرة أخرى ← تفتح بكامل الشاشة (تقريب + تمرير بين الصور)
        if (fullListener != null && p0 >= 0.999f && lastPullDy > 48f * d) {
            animateTo(1f);
            fullListener.open(heroName.getText(), heroStatus.getText());
            return;
        }
        animateTo(p >= THRESH ? 1f : 0f);
    }

    /** النقر على الصورة المكبّرة: الحافة اليمنى ← التالية، اليسرى ← السابقة، الوسط ← إغلاق. */
    @Override public void onTap(float x, float y) {
        if (!full()) return;
        if (y <= hero.getTranslationY() + hero.getHeight()) {
            if (pIds.size() > 1 && !swapping) {
                float w = Math.max(1f, hero.getWidth());
                if (x > w * 0.72f) { animateSwitch(1); return; }
                if (x < w * 0.28f) { animateSwitch(-1); return; }
            }
            // الوسط ← يفتح الصورة (عارض الصور) لا القصة
            if (fullListener != null) fullListener.open(heroName.getText(), heroStatus.getText());
            else animateTo(0f);
        }
    }

    // ------------------------------------------------- التمرير الأفقي بين الصور
    private boolean full() { return expanded || p >= 0.99f; }

    /** الصورة مكبّرة (الغلاف الكامل): النقر في وسطها يفتح الصورة مباشرة. */
    public boolean isFull() { return full(); }

    @Override public boolean canSwipe() { return full() && pIds.size() > 1 && !swapping; }

    @Override public void onSwipeMove(float dx) {
        if (!canSwipe()) return;
        float w = Math.max(1f, hero.getWidth());
        int dir = dx < 0 ? 1 : -1;                 // سحب لليسار ← التالية
        int ni = wrap(pIdx + dir);                 // تمرير دائري: من آخر صورة للأولى ومن الأولى للأخيرة
        if (dir != dragDir) {
            Bitmap nb = bitmapAt(ni);
            if (nb != null) { applyBitmapTo(heroImg2, nb); setExt(heroExt2, nb); }
            heroImg2.setVisibility(View.VISIBLE);
            heroExt2.setVisibility(View.VISIBLE);
            dragDir = dir;
        }
        seam(dir);
        heroImg.setTranslationX(dx);
        heroExt.setTranslationX(dx);
        heroImg2.setTranslationX(dx + (dir > 0 ? w : -w));
        heroExt2.setTranslationX(dx + (dir > 0 ? w : -w));
    }

    @Override public void onSwipeEnd(float dx, float vx) {
        if (swapping) return;
        float w = Math.max(1f, hero.getWidth());
        boolean fling = Math.abs(vx) > 900f * d;
        int dir = 0;
        if (dx < -w * 0.22f || (fling && vx < 0 && dx < 0)) dir = 1;
        else if (dx > w * 0.22f || (fling && vx > 0 && dx > 0)) dir = -1;
        if (dir == 0 || dragDir != dir) { settle(0); return; }
        settle(dir);
    }

    /** نقرة على الحافة: ينزلق الإطار الحالي والقادم معاً. */
    private void animateSwitch(int dir) {
        if (swapping || pIds.size() < 2) return;
        int ni = wrap(pIdx + dir);
        float w = Math.max(1f, hero.getWidth());
        Bitmap nb = bitmapAt(ni);
        if (nb != null) { applyBitmapTo(heroImg2, nb); setExt(heroExt2, nb); }
        heroImg2.setTranslationX(dir > 0 ? w : -w);
        heroExt2.setTranslationX(dir > 0 ? w : -w);
        heroImg2.setVisibility(View.VISIBLE);
        heroExt2.setVisibility(View.VISIBLE);
        dragDir = dir;
        seam(dir);
        settle(dir);
    }

    /** dir = 0: عودة للصورة الحالية، وإلا انتقال للتالية (+1) أو السابقة (-1). */
    private void settle(final int dir) {
        swapping = true;
        final float w = Math.max(1f, hero.getWidth());
        float endMain = dir == 0 ? 0f : (dir > 0 ? -w : w);
        long dur = 190;
        if (dragDir != 0) {
            float endNext = dir == 0 ? (dragDir > 0 ? w : -w) : 0f;
            heroImg2.animate().translationX(endNext).setDuration(dur).start();
            heroExt2.animate().translationX(endNext).setDuration(dur).start();
        }
        heroExt.animate().translationX(endMain).setDuration(dur).start();
        heroImg.animate().translationX(endMain).setDuration(dur).withEndAction(() -> {
            if (dir != 0) {
                pIdx = wrap(pIdx + dir);
                showCurrent();
                updatePill();
                prefetch();
            }
            heroImg.setTranslationX(0f);
            heroImg2.setTranslationX(0f);
            heroExt.setTranslationX(0f);
            heroExt2.setTranslationX(0f);
            heroImg2.setVisibility(View.GONE);
            heroExt2.setVisibility(View.GONE);
            seam(0);
            dragDir = 0;
            swapping = false;
        }).start();
    }

    private void animateTo(final float target) {
        if (anim != null) anim.cancel();
        if (p <= 0.001f && target == 0f) { hideHero(); return; }
        if (hero.getVisibility() != View.VISIBLE) { measure(); prepareContent(); }
        anim = ValueAnimator.ofFloat(p, target);
        anim.setDuration(260);
        anim.setInterpolator(new PathInterpolator(0.22f, 1f, 0.36f, 1f));
        anim.addUpdateListener(a -> apply((Float) a.getAnimatedValue()));
        anim.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled = false;
            @Override public void onAnimationCancel(Animator animation) { cancelled = true; }
            @Override public void onAnimationEnd(Animator animation) {
                if (cancelled) return;      // لمسة جديدة أثناء الحركة: لا نغلق ولا نثبّت
                if (target == 0f) hideHero(); else { expanded = true; prefetch(); }
            }
        });
        anim.start();
    }

    /** الأفاتار مُحاط بحلقة ذهبية بحشو px؛ الصورة المكبّرة تبدأ من حدود الصورة الداخلية لا الحلقة. */
    public void setAvatarInset(float px) { inset = px; }

    /** عند تغيير حجم الشاشة/الدوران. */
    public boolean isExpanded() { return expanded; }

    /** لون النص فوق الصورة ثابت أبيض؛ هذه الدالة تتيح تحديث الاسم بعد التحميل. */
    public void refreshTexts() {
        if (hero.getVisibility() == View.VISIBLE) prepareContent();
    }

    static int dimColor(int c, float f) {
        return Color.argb(255, (int) (Color.red(c) * f), (int) (Color.green(c) * f), (int) (Color.blue(c) * f));
    }
}
