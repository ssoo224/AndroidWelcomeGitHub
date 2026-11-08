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
        void restyle();    // يعيد تنسيق الأزرار/الغلاف كما كانت قبل السحب
    }

    private static final float THRESH = 0.45f;

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
    private final ImageView heroExt;
    private final FadeImageView heroImg;
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
        hero.setBackgroundColor(0xFF16213A);
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

        heroImg = new FadeImageView(act);
        heroImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        hero.addView(heroImg, new FrameLayout.LayoutParams(-1, 0));

        heroLetter = new TextView(act);
        heroLetter.setGravity(Gravity.CENTER);
        heroLetter.setTextColor(0xFFFFFFFF);
        heroLetter.setTypeface(null, android.graphics.Typeface.BOLD);
        hero.addView(heroLetter, new FrameLayout.LayoutParams(-1, 0));

        // .pf-hero-shade : rgba(0,0,0,0) -> rgba(0,0,0,.55)
        heroShade = new View(act);
        GradientDrawable sh = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x8C000000});
        heroShade.setBackground(sh);
        hero.addView(heroShade, new FrameLayout.LayoutParams(-1, 0));

        LinearLayoutLtr info = new LinearLayoutLtr(act);
        heroName = new TextView(act);
        heroName.setTextColor(0xFFFFFFFF);
        heroName.setTextSize(24);
        heroName.setTypeface(null, android.graphics.Typeface.BOLD);
        heroName.setShadowLayer(6 * d, 0, d, 0x8C000000);
        heroName.setTextDirection(View.TEXT_DIRECTION_LTR);
        heroStatus = new TextView(act);
        heroStatus.setTextColor(0xFFFFFFFF);
        heroStatus.setTextSize(13.5f);
        heroStatus.setTypeface(null, android.graphics.Typeface.BOLD);
        heroStatus.setShadowLayer(4 * d, 0, d, 0x80000000);
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
        private final Paint mask = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float fade = 0f;

        FadeImageView(Context c) {
            super(c);
            mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        }

        void setFade(float px) {
            if (Math.abs(px - fade) > 0.5f) { fade = px; invalidate(); }
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (fade <= 0.5f || h <= 0) { super.onDraw(c); return; }
            int sc = c.saveLayer(0, 0, w, h, null);
            super.onDraw(c);
            float f = Math.min(fade, h);
            mask.setShader(new LinearGradient(0, h - f, 0, h, 0xFF000000, 0x00000000, Shader.TileMode.CLAMP));
            c.drawRect(0, h - f, w, h, mask);
            c.restoreToCount(sc);
        }
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
        Bitmap bm = photo != null ? photo.get() : null;
        if (bm != null) {
            bm.setHasMipMap(true);
            heroImg.setImageBitmap(bm);
            heroImg.setVisibility(View.VISIBLE);
            heroLetter.setVisibility(View.GONE);
            hero.setBackgroundColor(0xFF16213A);
            try {
                int h = Math.max(1, 24 * bm.getHeight() / Math.max(1, bm.getWidth()));
                heroExt.setImageBitmap(Bitmap.createScaledBitmap(bm, 24, h, true));
            } catch (Throwable t) { heroExt.setImageBitmap(null); }
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
            int txt = mix(primary ? 0xFF17212B : (red ? pal.red() : pal.text()), white, pv);
            int ico = mix(primary ? 0xFF17212B : (red ? pal.red() : pal.gold()), white, pv);

            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(16 * d);
            g.setColor(fill);
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
    private void enterHero() {
        hero.setVisibility(View.VISIBLE);
        avatar.setAlpha(0f);
        enterCover();
        btnElev = new float[btns.size()];
        for (int i = 0; i < btns.size(); i++) btnElev[i] = btns.get(i).getElevation();
        lastPv = -1f;
    }

    private void apply(float v) {
        p = clamp(v);
        if (p <= 0.001f) { hideHero(); return; }
        if (hero.getVisibility() != View.VISIBLE) enterHero();

        float size = size0 + (W - size0) * p;
        float ext = (quickH + 28 * d) * clamp(p * 2f);
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
        FrameLayout.LayoutParams lt = (FrameLayout.LayoutParams) heroLetter.getLayoutParams();
        lt.height = (int) size; heroLetter.setLayoutParams(lt);
        heroLetter.setTextSize(Math.max(20f, (size / d) * 0.34f));
        FrameLayout.LayoutParams sp = (FrameLayout.LayoutParams) heroShade.getLayoutParams();
        sp.topMargin = (int) (size * 0.35f);
        sp.height = (int) (size * 0.65f + ext);
        heroShade.setLayoutParams(sp);
        heroExt.setVisibility(ext > 0 ? View.VISIBLE : View.INVISIBLE);

        radiusPx = (size / 2f) * (1f - p);
        hW = size; hH = heroH;
        hero.invalidateOutline();

        float info = clamp((p - 0.55f) / 0.4f);
        infoHolder.setAlpha(info);
        FrameLayout.LayoutParams ilp = (FrameLayout.LayoutParams) infoHolder.getLayoutParams();
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
        float finalStart = W + (quickH + 28 * d) + 10 * d;
        float shift = (finalStart - infoBottom) * p;
        boolean after = false;
        for (int i = 0; i < content.getChildCount(); i++) {
            View c = content.getChildAt(i);
            if (after) c.setTranslationY(shift);
            if (c == anchor) after = true;
        }
    }

    private void hideHero() {
        boolean was = covered || hero.getVisibility() == View.VISIBLE;
        hero.setVisibility(View.GONE);
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
        if (anim != null) anim.cancel();
        measure();
        prepareContent();
        p0 = p;
        sv.scrollTo(0, 0);
    }

    @Override public void onPull(float totalDy) {
        apply(p0 + totalDy / rangePx);
    }

    @Override public void onRelease() {
        animateTo(p >= THRESH ? 1f : 0f);
    }

    /** النقر على الصورة المكبّرة (لا على زر) يغلقها. */
    @Override public void onTap(float x, float y) {
        if (!expanded) return;
        if (y <= hero.getTranslationY() + hero.getHeight()) animateTo(0f);
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
                if (target == 0f) hideHero(); else expanded = true;
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
