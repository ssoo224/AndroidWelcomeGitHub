package com.babylon.chat.iq;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * سحب الشاشة للأسفل في الملف الشخصي: تكبر الصورة مع الإصبع حتى تصبح مربعاً بعرض الشاشة
 * يظهر عليه الاسم، وتصعد أزرار (تعديل / الصورة / الغلاف) إلى أسفل الصورة — مثل تليجرام.
 * منقول من سكربت pf-pull-expand-js:
 *   THRESH = 0.45       بعدها تثبت مفتوحة عند ترك الإصبع
 *   RANGE  = 320dp      للملف الشخصي الخاص ، 150dp لملف شخص آخر
 *   ext    = ارتفاع صف الأزرار + 28dp (امتداد الصورة المموّه أسفل المربع)
 *   حلقة الصورة تتحول من دائرة (50%) إلى مربع (0%) ، الاسم والحالة يظهران عند v > 0.55
 */
public final class ProfileHero implements PullScrollView.Controller {

    public interface PhotoSource { Bitmap get(); }

    private static final float THRESH = 0.45f;

    private final Activity act;
    private final PullScrollView sv;
    private final ViewGroup content;       // الابن المباشر لـ ScrollView
    private final TextView avatar;
    private final View infoBox;            // الحاوية التي فيها الصورة والاسم والحالة والأزرار
    private final TextView nameV, statusV;
    private final View quick;
    private final float rangePx;
    private final PhotoSource photo;
    private final View backBtn, moreBtn;   // لملف شخص آخر فقط

    // طبقة الصورة المكبّرة
    private final FrameLayout hero;
    private final ImageView heroImg, heroExt;
    private final TextView heroLetter, heroName, heroStatus;
    private final View heroShade;
    private View heroBack, heroMore;

    private float W, size0, avatarTop, quickH, infoBottom, quickBottom;
    private float p = 0f, p0 = 0f;
    private boolean expanded = false;
    private ValueAnimator anim;
    private float radiusPx = 0f, hW = 0f, hH = 0f;

    public ProfileHero(Activity act, PullScrollView sv, TextView avatar, View infoBox,
                       TextView nameV, TextView statusV, View quick, boolean own,
                       PhotoSource photo, View backBtn, View moreBtn) {
        this.act = act;
        this.sv = sv;
        this.content = (ViewGroup) sv.getChildAt(0);
        this.avatar = avatar;
        this.infoBox = infoBox;
        this.nameV = nameV;
        this.statusV = statusV;
        this.quick = quick;
        this.photo = photo;
        this.backBtn = backBtn;
        this.moreBtn = moreBtn;
        float d = act.getResources().getDisplayMetrics().density;
        this.rangePx = (own ? 320f : 150f) * d;

        // --- بناء طبقة hero فوق ScrollView داخل نفس الأب ---
        hero = new FrameLayout(act);
        hero.setVisibility(View.GONE);
        hero.setBackgroundColor(0xFF16213A);
        hero.setClipToOutline(true);
        hero.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, (int) hW, (int) hH, radiusPx);
            }
        });
        heroExt = new ImageView(act);
        heroExt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        heroExt.setAlpha(0.55f);
        hero.addView(heroExt, new FrameLayout.LayoutParams(-1, -1));

        heroImg = new ImageView(act);
        heroImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        hero.addView(heroImg, new FrameLayout.LayoutParams(-1, 0));

        heroLetter = new TextView(act);
        heroLetter.setGravity(Gravity.CENTER);
        heroLetter.setTextColor(0xFFFFFFFF);
        heroLetter.setTypeface(null, android.graphics.Typeface.BOLD);
        hero.addView(heroLetter, new FrameLayout.LayoutParams(-1, 0));

        heroShade = new View(act);
        GradientDrawable sh = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0xB8000000});
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
        info.addView(heroName);
        info.addView(heroStatus);
        info.setAlpha(0f);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.LEFT);
        ilp.leftMargin = (int) (18 * d);
        ilp.rightMargin = (int) (18 * d);
        hero.addView(info, ilp);
        this.infoHolder = info;

        ViewGroup host = (ViewGroup) sv.getParent();
        host.addView(hero, new FrameLayout.LayoutParams(0, 0));

        // أزرار الرجوع/المزيد لملف شخص آخر: تُنسخ فوق الصورة لأن الأصل يقع تحت الطبقة
        if (backBtn != null) heroBack = makeTopBtn(true);
        if (moreBtn != null) heroMore = makeTopBtn(false);

        hero.setOnClickListener(v -> { if (expanded) animateTo(0f); });
        sv.setController(this);
    }

    private LinearLayoutLtr infoHolder;

    /** LinearLayout رأسي بإتجاه LTR (الاسم على اليسار فوق الصورة كما في الـ HTML). */
    private static final class LinearLayoutLtr extends android.widget.LinearLayout {
        LinearLayoutLtr(Activity a) {
            super(a);
            setOrientation(VERTICAL);
            setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        }
    }

    private View makeTopBtn(final boolean back) {
        float d = act.getResources().getDisplayMetrics().density;
        TextView b = new TextView(act);
        b.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0x59000000);
        b.setBackground(g);
        View src = back ? backBtn : moreBtn;
        if (src instanceof TextView) {
            android.graphics.drawable.Drawable[] ds = ((TextView) src).getCompoundDrawablesRelative();
            b.setCompoundDrawablesRelative(ds[0], null, null, null);
            b.setCompoundDrawablePadding(0);
            b.setText(((TextView) src).getText());
            b.setTextColor(((TextView) src).getCurrentTextColor());
        }
        b.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int) (40 * d), (int) (40 * d),
                back ? (Gravity.TOP | Gravity.START) : (Gravity.TOP | Gravity.END));
        lp.setMargins((int) (12 * d), (int) (12 * d), (int) (12 * d), 0);
        hero.addView(b, lp);
        b.setOnClickListener(v -> (back ? backBtn : moreBtn).performClick());
        return b;
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
        size0 = avatar.getWidth();
        avatarTop = topInContent(avatar);
        quickH = quick != null ? quick.getHeight() : 0;
        infoBottom = topInContent(infoBox) + infoBox.getHeight();
        quickBottom = quick != null ? topInContent(quick) + quick.getHeight() : infoBottom;
    }

    private void prepareContent() {
        // محتوى الطبقة: صورة المستخدم أو الحرف
        Bitmap bm = photo != null ? photo.get() : null;
        if (bm != null) {
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
    }

    private static float clamp(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    // ------------------------------------------------------------- تطبيق v
    private void apply(float v) {
        p = clamp(v);
        float d = act.getResources().getDisplayMetrics().density;
        if (p <= 0.001f) { hideHero(); return; }
        if (hero.getVisibility() != View.VISIBLE) {
            hero.setVisibility(View.VISIBLE);
            avatar.setAlpha(0f);
            if (heroBack != null) heroBack.setVisibility(View.VISIBLE);
            if (heroMore != null) heroMore.setVisibility(View.VISIBLE);
        }
        float size = size0 + (W - size0) * p;
        float ext = (quickH + 28 * d) * clamp(p * 2f);
        float top = avatarTop * (1f - p);
        float left = (W - size) / 2f;
        float heroH = size + ext;

        ViewGroup.LayoutParams lp = hero.getLayoutParams();
        lp.width = (int) size;
        lp.height = (int) heroH;
        hero.setLayoutParams(lp);
        hero.setTranslationX(left);
        hero.setTranslationY(top);

        FrameLayout.LayoutParams ip = (FrameLayout.LayoutParams) heroImg.getLayoutParams();
        ip.height = (int) size; heroImg.setLayoutParams(ip);
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
        if (heroBack != null) heroBack.setAlpha(1f);
        if (heroMore != null) heroMore.setAlpha(1f);

        // الاسم/الحالة الأصليان يتلاشيان، والأزرار تصعد إلى أسفل الصورة
        float fade = clamp(1f - p * 2f);
        if (nameV != null) nameV.setAlpha(fade);
        if (statusV != null) statusV.setAlpha(fade);
        float heroBottom = top + heroH;
        if (quick != null) quick.setTranslationY((heroBottom - 14 * d - quickBottom) * p);

        // باقي المحتوى ينزل ليبدأ بعد الصورة الممتدة
        float finalStart = W + (quickH + 28 * d) + 10 * d;
        float shift = (finalStart - infoBottom) * p;
        boolean after = false;
        for (int i = 0; i < content.getChildCount(); i++) {
            View c = content.getChildAt(i);
            if (after) c.setTranslationY(shift);
            if (c == infoBox) after = true;
        }
    }

    private void hideHero() {
        hero.setVisibility(View.GONE);
        avatar.setAlpha(1f);
        if (heroBack != null) heroBack.setVisibility(View.GONE);
        if (heroMore != null) heroMore.setVisibility(View.GONE);
        if (nameV != null) nameV.setAlpha(1f);
        if (statusV != null) statusV.setAlpha(1f);
        if (quick != null) quick.setTranslationY(0f);
        for (int i = 0; i < content.getChildCount(); i++) content.getChildAt(i).setTranslationY(0f);
        p = 0f;
        expanded = false;
    }

    /** يُستدعى عند مغادرة الشاشة أو تغيير الثيم. */
    public void reset() {
        if (anim != null) anim.cancel();
        hideHero();
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

    private void animateTo(final float target) {
        if (anim != null) anim.cancel();
        if (p <= 0.001f && target == 0f) { hideHero(); return; }
        if (hero.getVisibility() != View.VISIBLE) { measure(); prepareContent(); }
        anim = ValueAnimator.ofFloat(p, target);
        anim.setDuration(260);
        anim.setInterpolator(new PathInterpolator(0.22f, 1f, 0.36f, 1f));
        anim.addUpdateListener(a -> apply((Float) a.getAnimatedValue()));
        anim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (target == 0f) hideHero(); else expanded = true;
            }
        });
        anim.start();
    }

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
