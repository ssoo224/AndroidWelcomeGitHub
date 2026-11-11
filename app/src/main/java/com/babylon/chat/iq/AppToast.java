package com.babylon.chat.iq;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.RotateAnimation;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.regex.Pattern;

/**
 * إشعارات التطبيق — نسخة طبق الأصل من showToast / #toast في الـ HTML (babylon-toast-v3):
 * بطاقة عائمة أسفل الشاشة (138dp فوق الحافة)، أيقونة دائرية بلون النوع، النص، وشريط تقدّم سفلي يتقلّص.
 * الأنواع: success / error / loading / info / danger (تُستنتج من النص كما في toastKind).
 * الظهور: شفافية + صعود 26dp + تكبير من .96 (380ms)، والمدة 3000ms (4200 للخطأ، 6000 للتحميل).
 */
public final class AppToast {
    private AppToast() { }

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static View current;
    private static ValueAnimator barAnim;
    private static Runnable hideRun;

    private static final Pattern P_DANGER = Pattern.compile("حظرك");
    private static final Pattern P_LOADING = Pattern.compile("جاري|جارٍ|\\.\\.\\.|\u2026");
    private static final Pattern P_ERROR = Pattern.compile("تعذر|فشل|خطأ|غير صح|لا يمكن|حدث خطأ|الرجاء|يجب|مرفوض|ممنوع");
    private static final Pattern P_OK = Pattern.compile("تم|نجاح|\u2727|\u2713|\u2714");

    /** toastKind في الـ HTML. */
    static String kindOf(String m) {
        if (P_DANGER.matcher(m).find()) return "danger";
        if (P_LOADING.matcher(m).find()) return "loading";
        if (P_ERROR.matcher(m).find()) return "error";
        if (P_OK.matcher(m).find()) return "success";
        return "info";
    }

    public static void show(Activity act, String msg) {
        if (act == null || msg == null || msg.trim().isEmpty()) return;
        boolean dark = false;
        try { dark = act.getSharedPreferences("babylon_ui", Context.MODE_PRIVATE).getBoolean("dark", false); } catch (Throwable ignored) { }
        show(act, msg, dark);
    }

    public static void show(final Activity act, final String msg, final boolean dark) {
        if (act == null || msg == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) { H.post(() -> show(act, msg, dark)); return; }
        try { build(act, msg, dark); } catch (Throwable ignored) { }
    }

    /** يخفي الإشعار الحالي إن كان من نوع «جارٍ…» (مثل إغلاق bt-loading في الـ HTML). */
    public static void dismissLoading() {
        if (current != null && "loading".equals(current.getTag())) hide(current, true);
    }

    private static int dp(Context c, float v) { return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f); }

    private static void build(Activity act, String msg, boolean dark) {
        if (current != null) hide(current, false);   // الإشعار الجديد يستبدل القديم فوراً

        String kind = kindOf(msg);
        String text = msg.replaceAll("\\s*[\u2727\u2713\u2714]\\s*$", "");
        long dur = kind.equals("loading") ? 6000 : (kind.equals("error") || kind.equals("danger")) ? 4200 : 3000;

        int acc, bg, fg, bd, icRes;
        switch (kind) {
            case "success": acc = 0xFF22C55E; icRes = R.drawable.ic_toast_success; break;
            case "error": acc = 0xFFEF4444; icRes = R.drawable.ic_toast_error; break;
            case "loading": acc = 0xFFD4A937; icRes = R.drawable.ic_toast_loading; break;
            case "danger": acc = 0xFFDC2626; icRes = R.drawable.ic_toast_danger; break;
            default: acc = 0xFF3B82F6; icRes = R.drawable.ic_toast_info; break;
        }
        if (kind.equals("danger")) {
            bg = dark ? 0xF2451C1C : 0xFFFFF1F1; fg = dark ? 0xFFFECACA : 0xFFB91C1C; bd = dark ? 0x80F87171 : 0x73DC2626;
        } else if (dark) {
            bg = 0xE618202E; fg = 0xFFF1F5F9; bd = 0x17FFFFFF;
        } else {
            bg = 0xEBFFFFFF; fg = 0xFF17212B; bd = 0x140F172A;
        }

        final FrameLayout card = new FrameLayout(act);
        card.setTag(kind);
        card.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        GradientDrawable cb = new GradientDrawable();
        cb.setColor(bg); cb.setCornerRadius(dp(act, 18)); cb.setStroke(Math.max(1, dp(act, 1)), bd);
        card.setBackground(cb);
        card.setClipToOutline(true);
        if (Build.VERSION.SDK_INT >= 21) card.setElevation(dp(act, 12));
        card.setClickable(false);

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(act, 16), dp(act, 11), dp(act, 15), dp(act, 13));
        card.addView(row, new FrameLayout.LayoutParams(-1, -2));

        ImageView ic = new ImageView(act);
        ic.setImageResource(icRes);
        ic.setScaleType(ImageView.ScaleType.CENTER);
        GradientDrawable ib;
        if (kind.equals("danger")) {
            ib = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFFF87171, 0xFFDC2626});
            ic.setColorFilter(0xFFFFFFFF);
        } else {
            ib = new GradientDrawable();
            ib.setColor((acc & 0x00FFFFFF) | 0x29000000);   // 16% من لون النوع
            ic.setColorFilter(acc);
        }
        ib.setShape(GradientDrawable.OVAL);
        ic.setBackground(ib);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(act, 30), dp(act, 30));
        ilp.setMarginEnd(dp(act, 11));
        row.addView(ic, ilp);
        if (kind.equals("loading")) {
            RotateAnimation ra = new RotateAnimation(0, 360, RotateAnimation.RELATIVE_TO_SELF, 0.5f, RotateAnimation.RELATIVE_TO_SELF, 0.5f);
            ra.setDuration(800); ra.setRepeatCount(-1); ra.setInterpolator(new LinearInterpolator());
            ic.startAnimation(ra);
        }

        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTextSize(14f);
        tv.setTextColor(fg);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setLineSpacing(0, 1.18f);
        tv.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        tv.setTextDirection(View.TEXT_DIRECTION_ANY_RTL);
        row.addView(tv, new LinearLayout.LayoutParams(0, -2, 1f));

        final View bar = new View(act);
        bar.setBackgroundColor(acc);
        bar.setAlpha(kind.equals("loading") ? 0.45f : (kind.equals("danger") ? 1f : 0.85f));
        card.addView(bar, new FrameLayout.LayoutParams(-1, dp(act, 3), Gravity.BOTTOM));

        DisplayMetrics dm = act.getResources().getDisplayMetrics();
        int maxW = Math.min(dp(act, 420), dm.widthPixels - dp(act, 28));
        card.setMinimumWidth(Math.min(dp(act, 200), maxW));
        tv.setMaxWidth(maxW - dp(act, 16 + 15 + 30 + 11));

        ViewGroup decor = (ViewGroup) act.getWindow().getDecorView();
        int inset = 0;
        try {
            if (Build.VERSION.SDK_INT >= 23 && decor.getRootWindowInsets() != null) inset = decor.getRootWindowInsets().getSystemWindowInsetBottom();
        } catch (Throwable ignored) { }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = dp(act, 138) + inset;
        card.setAlpha(0f);
        card.setTranslationY(dp(act, 26));
        card.setScaleX(0.96f); card.setScaleY(0.96f);
        decor.addView(card, lp);
        current = card;

        card.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(380)
                .setInterpolator(new OvershootInterpolator(0.9f)).start();

        // شريط التقدم: يتقلّص من اليمين إلى اليسار على مدى المدة
        bar.post(() -> bar.setPivotX(bar.getWidth()));
        if (barAnim != null) barAnim.cancel();
        barAnim = ValueAnimator.ofFloat(1f, 0f);
        barAnim.setDuration(dur);
        barAnim.setInterpolator(new LinearInterpolator());
        barAnim.addUpdateListener(a -> { bar.setPivotX(bar.getWidth()); bar.setScaleX((float) a.getAnimatedValue()); });
        barAnim.start();

        if (hideRun != null) H.removeCallbacks(hideRun);
        hideRun = () -> hide(card, true);
        H.postDelayed(hideRun, dur);
    }

    private static void hide(final View card, boolean animate) {
        if (card == null) return;
        if (card == current) {
            current = null;
            if (hideRun != null) { H.removeCallbacks(hideRun); hideRun = null; }
            if (barAnim != null) { barAnim.cancel(); barAnim = null; }
        }
        card.animate().cancel();
        final Runnable rm = () -> {
            try { ViewGroup p = (ViewGroup) card.getParent(); if (p != null) p.removeView(card); } catch (Throwable ignored) { }
        };
        if (!animate) { rm.run(); return; }
        card.animate().alpha(0f).translationY(card.getResources().getDisplayMetrics().density * 26f).scaleX(0.96f).scaleY(0.96f)
                .setDuration(280).withEndAction(rm).start();
    }
}
