package com.babylon.chat.iq;

import android.animation.Animator;
import android.animation.Keyframe;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.view.View;
import android.view.animation.OvershootInterpolator;

/**
 * حركة خاصة بالإيموجي نفسه (لا بالكبسولة) عند التفاعل على رسالة.
 * لكل نوع إيموجي حركة تناسب معناه: ضحك = قفزات اهتزازية، بكاء = ميلان وارتجاف ونزول،
 * قلب = نبض، نار = تذبذب، غضب = اهتزاز أفقي، إلخ.
 */
public final class EmojiFx {
    private EmojiFx() { }

    private enum Kind { LAUGH, CRY, HEART, FIRE, ANGRY, LIKE, SPARK, PARTY, SHOCK, SLEEP, SICK, LOVE_EYES, HIDE, DEFAULT }

    private static Kind kindOf(String e) {
        if (e == null) return Kind.DEFAULT;
        if (e.startsWith("😂") || e.startsWith("🤣") || e.startsWith("😆") || e.startsWith("😄") || e.startsWith("😁") || e.startsWith("😹") || e.startsWith("🤡")) return Kind.LAUGH;
        if (e.startsWith("😭") || e.startsWith("😢") || e.startsWith("🥺") || e.startsWith("😞") || e.startsWith("💔") || e.startsWith("🫠") || e.startsWith("😔") || e.startsWith("😿")) return Kind.CRY;
        if (e.startsWith("😡") || e.startsWith("🤬") || e.startsWith("😠") || e.startsWith("💢") || e.startsWith("🖕") || e.startsWith("☠") || e.startsWith("💩")) return Kind.ANGRY;
        if (e.startsWith("🔥") || e.startsWith("❤️‍🔥") || e.startsWith("🥵") || e.startsWith("⚡") || e.startsWith("💥")) return Kind.FIRE;
        if (e.startsWith("❤") || e.startsWith("🤍") || e.startsWith("💙") || e.startsWith("💛") || e.startsWith("💚") || e.startsWith("💜") || e.startsWith("🫶") || e.startsWith("🫦") || e.startsWith("😘") || e.startsWith("❤️‍🩹")) return Kind.HEART;
        if (e.startsWith("😍") || e.startsWith("🥰") || e.startsWith("😌") || e.startsWith("😉") || e.startsWith("😎") || e.startsWith("🌚")) return Kind.LOVE_EYES;
        if (e.startsWith("👍") || e.startsWith("👏") || e.startsWith("🙏") || e.startsWith("💅") || e.startsWith("✍") || e.startsWith("💯")) return Kind.LIKE;
        if (e.startsWith("✨") || e.startsWith("⭐") || e.startsWith("🌟")) return Kind.SPARK;
        if (e.startsWith("🎉") || e.startsWith("🥳") || e.startsWith("🎊")) return Kind.PARTY;
        if (e.startsWith("😱") || e.startsWith("😮") || e.startsWith("😲") || e.startsWith("🤯") || e.startsWith("👀")) return Kind.SHOCK;
        if (e.startsWith("💤") || e.startsWith("😴")) return Kind.SLEEP;
        if (e.startsWith("🤢") || e.startsWith("🤧") || e.startsWith("🤮")) return Kind.SICK;
        if (e.startsWith("🙈") || e.startsWith("🙉") || e.startsWith("🙊")) return Kind.HIDE;
        return Kind.DEFAULT;
    }

    private static PropertyValuesHolder kf(android.util.Property<View, Float> p, float... v) {
        Keyframe[] k = new Keyframe[v.length];
        for (int i = 0; i < v.length; i++) k[i] = Keyframe.ofFloat(v.length == 1 ? 1f : (float) i / (v.length - 1), v[i]);
        return PropertyValuesHolder.ofKeyframe(p, k);
    }

    private static ObjectAnimator anim(View t, long dur, PropertyValuesHolder... h) {
        ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(t, h);
        a.setDuration(dur);
        return a;
    }

    /** يشغّل حركة الإيموجي فقط (TextView الإيموجي داخل الكبسولة). آمنة: لا تلمس شجرة العناصر. */
    public static void play(final View em, String emoji) {
        if (em == null) return;
        try {
            em.animate().cancel();
            em.setPivotX(em.getWidth() / 2f);
            em.setPivotY(em.getHeight() * 0.6f);
            resetState(em);
            Animator an;
            switch (kindOf(emoji)) {
                case LAUGH: {
                    // ضحك: قفزات صغيرة سريعة مع هزّ جانبي
                    an = anim(em, 900,
                            kf(View.TRANSLATION_Y, 0, -9, 0, -7, 0, -5, 0, -3, 0),
                            kf(View.ROTATION, 0, -14, 12, -12, 10, -8, 6, -3, 0),
                            kf(View.SCALE_X, 1f, 1.25f, 1f, 1.2f, 1f, 1.12f, 1f),
                            kf(View.SCALE_Y, 1f, 1.25f, 1f, 1.2f, 1f, 1.12f, 1f));
                    break;
                }
                case CRY: {
                    // بكاء: ميلان للأسفل + ارتجاف + نزول بطيء ثم عودة
                    an = anim(em, 1300,
                            kf(View.ROTATION, 0, -10, -6, -10, -4, -9, 0),
                            kf(View.TRANSLATION_X, 0, -2, 2, -2, 2, -1.5f, 1.5f, 0),
                            kf(View.TRANSLATION_Y, 0, 2, 5, 5, 5, 3, 0),
                            kf(View.SCALE_X, 1f, .94f, .9f, .92f, .9f, .96f, 1f),
                            kf(View.SCALE_Y, 1f, .94f, .9f, .92f, .9f, .96f, 1f));
                    break;
                }
                case HEART: {
                    // قلب: نبضتان
                    an = anim(em, 950,
                            kf(View.SCALE_X, 1f, 1.4f, 1f, 1.3f, 1f),
                            kf(View.SCALE_Y, 1f, 1.4f, 1f, 1.3f, 1f));
                    break;
                }
                case FIRE: {
                    // نار: تذبذب وتمدد عمودي
                    an = anim(em, 900,
                            kf(View.SCALE_X, 1f, .88f, 1.08f, .9f, 1.05f, 1f),
                            kf(View.SCALE_Y, 1f, 1.3f, .95f, 1.25f, 1f, 1f),
                            kf(View.ROTATION, 0, -6, 6, -5, 4, 0));
                    break;
                }
                case ANGRY: {
                    // غضب: اهتزاز أفقي عنيف
                    an = anim(em, 650,
                            kf(View.TRANSLATION_X, 0, -6, 6, -6, 6, -4, 4, -2, 0),
                            kf(View.SCALE_X, 1f, 1.2f, 1.2f, 1.2f, 1.1f, 1f),
                            kf(View.SCALE_Y, 1f, 1.2f, 1.2f, 1.2f, 1.1f, 1f));
                    break;
                }
                case LIKE: {
                    // إعجاب/تصفيق/شكر: ارتفاع مع ميلان ثم ارتداد
                    an = anim(em, 800,
                            kf(View.ROTATION, 0, -28, 12, -6, 0),
                            kf(View.TRANSLATION_Y, 0, -8, 0, -2, 0),
                            kf(View.SCALE_X, 1f, 1.3f, 1f, 1.08f, 1f),
                            kf(View.SCALE_Y, 1f, 1.3f, 1f, 1.08f, 1f));
                    break;
                }
                case SPARK: {
                    // لمعان: دوران كامل مع تكبير وتصغير
                    an = anim(em, 900,
                            kf(View.ROTATION, 0, 180, 360),
                            kf(View.SCALE_X, 1f, 1.5f, .8f, 1.15f, 1f),
                            kf(View.SCALE_Y, 1f, 1.5f, .8f, 1.15f, 1f));
                    break;
                }
                case PARTY: {
                    // احتفال: قفزة مع دوران
                    an = anim(em, 900,
                            kf(View.TRANSLATION_Y, 0, -12, 0, -5, 0),
                            kf(View.ROTATION, 0, -25, 25, -15, 10, 0),
                            kf(View.SCALE_X, 1f, 1.35f, 1f, 1.1f, 1f),
                            kf(View.SCALE_Y, 1f, 1.35f, 1f, 1.1f, 1f));
                    break;
                }
                case SHOCK: {
                    // صدمة: تكبير مفاجئ ثم ارتجاف خفيف
                    an = anim(em, 800,
                            kf(View.SCALE_X, 1f, 1.55f, 1.4f, 1.45f, 1f),
                            kf(View.SCALE_Y, 1f, 1.55f, 1.4f, 1.45f, 1f),
                            kf(View.TRANSLATION_X, 0, 0, -2, 2, -2, 0));
                    break;
                }
                case SLEEP: {
                    // نوم: طفو بطيء للأعلى وتلاشٍ جزئي
                    an = anim(em, 1200,
                            kf(View.TRANSLATION_Y, 0, -6, -2, -6, 0),
                            kf(View.ALPHA, 1f, .55f, 1f, .6f, 1f),
                            kf(View.ROTATION, 0, 6, -4, 6, 0));
                    break;
                }
                case SICK: {
                    // مرض: ترنّح
                    an = anim(em, 1000,
                            kf(View.ROTATION, 0, -16, 16, -12, 12, -6, 0),
                            kf(View.TRANSLATION_Y, 0, 2, 0, 2, 0));
                    break;
                }
                case LOVE_EYES: {
                    // غمزة/إعجاب: تكبير مع ميل
                    an = anim(em, 800,
                            kf(View.SCALE_X, 1f, 1.35f, 1f, 1.15f, 1f),
                            kf(View.SCALE_Y, 1f, 1.35f, 1f, 1.15f, 1f),
                            kf(View.ROTATION, 0, -10, 8, -4, 0));
                    break;
                }
                case HIDE: {
                    // القرود: انكماش وإخفاء ثم ظهور
                    an = anim(em, 900,
                            kf(View.SCALE_X, 1f, .7f, .7f, 1.2f, 1f),
                            kf(View.SCALE_Y, 1f, .7f, .7f, 1.2f, 1f),
                            kf(View.ROTATION, 0, -10, 10, -6, 0));
                    break;
                }
                default: {
                    // أي إيموجي آخر: قفزة مرنة
                    ObjectAnimator a = anim(em, 700,
                            kf(View.SCALE_X, 1f, 1.4f, 1f),
                            kf(View.SCALE_Y, 1f, 1.4f, 1f),
                            kf(View.ROTATION, 0, -12, 8, 0));
                    a.setInterpolator(new OvershootInterpolator(1.4f));
                    an = a;
                }
            }
            an.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator a) { resetState(em); }
                @Override public void onAnimationCancel(Animator a) { resetState(em); }
            });
            an.start();
        } catch (Throwable ignored) { resetState(em); }
    }

    private static void resetState(View v) {
        v.setRotation(0f); v.setScaleX(1f); v.setScaleY(1f);
        v.setTranslationX(0f); v.setTranslationY(0f); v.setAlpha(1f);
    }
}
