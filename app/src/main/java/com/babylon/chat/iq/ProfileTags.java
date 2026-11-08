package com.babylon.chat.iq;

import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * وسوم (android:tag) خاصة بشاشات الملف الشخصي. مسؤول عنها مَن يبني part_profile.xml.
 *
 * صفحة «تعديل البيانات الشخصية» (editProfileView) تطابق #editProfileView في index.html:
 *   epbg / ephdr / epback / epcard / eplbl / epinp / epsel / eparrow / eperr / epsave / epcancel
 * القيم مأخوذة من الـ CSS النهائي (بعد كل الطبقات): 1px = 1dp و 1px خط = 1sp.
 */
final class ProfileTags {
    private ProfileTags() { }

    // ---- ألوان صفحة تعديل البيانات (HTML: light / dark)
    private static int epPage(boolean d)    { return d ? 0xFF0E1621 : 0xFFF4F7FB; }
    private static int epHeader(boolean d)  { return d ? 0xFF0E1621 : 0xFFFFFFFF; }
    private static int epHdrLine(boolean d) { return d ? 0x14FFFFFF : 0xFFE1E7EF; }
    private static int epBackBg(boolean d)  { return d ? 0xFF232E3C : 0xFFEDF2F8; }
    private static int epBackFg(boolean d)  { return d ? 0xFF8696A7 : 0xFF536579; }
    private static int epCardBg(boolean d)  { return d ? 0xFF17212B : 0xFFFFFFFF; }
    private static int epCardLine(boolean d){ return d ? 0x14FFFFFF : 0xFFE1E7EF; }
    private static int epLabel(boolean d)   { return d ? 0xFF8696A7 : 0xFF64748B; }
    private static int epInpBg(boolean d)   { return d ? 0xFF1D2A38 : 0xFFF5F7FB; }
    private static int epInpLine(boolean d) { return d ? 0x1AFFFFFF : 0xFFDCE4EE; }
    private static int epInpText(boolean d) { return d ? 0xFFF1F5F9 : 0xFF17212B; }
    private static int epInpHint(boolean d) { return d ? 0xFF8696A7 : 0xFF708094; }
    private static final int EP_GOLD = 0xFFC29D30;   // --babylon-gold (حدّ الحقل عند التركيز)
    private static final int EP_ERR  = 0xFFF87171;   // --error-color

    private static GradientDrawable box(int fill, float rPx, int stroke, float strokePx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(rPx);
        if (stroke != 0 && strokePx > 0) g.setStroke(Math.max(1, Math.round(strokePx)), stroke);
        return g;
    }

    private static void clipRound(View x, final float rPx) {
        x.setClipToOutline(true);
        x.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), rPx); }
        });
    }

    /** حقل مثل .babylon-input داخل .edit-profile-card: radius 12، حدّ 1px، padding 12/13، وعند التركيز حدّ ذهبي. */
    private static void inputBox(View x, boolean dark) {
        float r = Tk.dp(x, 12), one = Tk.dp(x, 1);
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, box(epInpBg(dark), r, EP_GOLD, one));
        sl.addState(new int[]{}, box(epInpBg(dark), r, epInpLine(dark), one));
        x.setBackground(sl);
        x.setPadding(Tk.dpi(x, 13), Tk.dpi(x, 12), Tk.dpi(x, 13), Tk.dpi(x, 12));
    }

    static boolean apply(String tag, View x, TextView t, boolean dark) {
        // ==== PROFILE TAGS (أضف الفروع هنا) ====
        if (tag.equals("epbg")) { x.setBackgroundColor(epPage(dark)); return true; }

        if (tag.equals("ephdr")) {                       // .edit-profile-header
            android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(
                    new android.graphics.drawable.Drawable[]{
                            new android.graphics.drawable.ColorDrawable(epHdrLine(dark)),
                            new android.graphics.drawable.ColorDrawable(epHeader(dark))});
            ld.setLayerInset(1, 0, 0, 0, Math.max(1, Math.round(Tk.dp(x, 1))));   // خط سفلي 1px
            x.setBackground(ld);
            x.setElevation(0f);
            return true;
        }

        if (tag.equals("epback")) {                      // .edit-profile-back : 42px، radius 14
            x.setBackground(box(epBackBg(dark), Tk.dp(x, 14), 0, 0));
            if (t != null) t.setTextColor(epBackFg(dark));   // يلوّن السهم عبر tintIcons
            return false;                                     // يكمل tintIcons في MassActivity
        }

        if (tag.equals("epcard")) {                      // .edit-profile-card : radius 18 (شاشة ≤520)، حدّ 1px
            x.setBackground(box(epCardBg(dark), Tk.dp(x, 18), epCardLine(dark), Tk.dp(x, 1)));
            x.setElevation(Tk.dp(x, dark ? 0 : 2));
            return true;
        }

        if (tag.equals("eplbl")) {                       // label: 13px bold muted
            if (t != null) t.setTextColor(epLabel(dark));
            return true;
        }

        if (tag.equals("epinp")) {
            inputBox(x, dark);
            if (t != null) { t.setTextColor(epInpText(dark)); t.setHintTextColor(epInpHint(dark)); }
            return true;
        }

        if (tag.equals("epsel")) {                       // <select>: نفس الحقل + مساحة لسهم القائمة (على اليسار كما في RTL)
            inputBox(x, dark);
            x.setPadding(Tk.dpi(x, 38), Tk.dpi(x, 4), Tk.dpi(x, 13), Tk.dpi(x, 4));
            return true;
        }

        if (tag.equals("eparrow")) {
            if (x instanceof ImageView) ((ImageView) x).setColorFilter(epLabel(dark), android.graphics.PorterDuff.Mode.SRC_IN);
            return true;
        }

        if (tag.equals("eperr")) {                       // .field-error
            if (t != null) t.setTextColor(EP_ERR);
            return true;
        }

        if (tag.equals("epsave")) {                      // زر حفظ: تدرج ذهبي 135° + نص أبيض 800 + ظل ذهبي
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{0xFFE6C566, 0xFFD3A93B, 0xFFBC8E24});
            g.setCornerRadius(Tk.dp(x, 13));
            x.setBackground(g);
            x.setElevation(Tk.dp(x, 6));
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                x.setOutlineAmbientShadowColor(0x61BE9628);
                x.setOutlineSpotShadowColor(0x61BE9628);
            }
            if (t != null) {
                t.setTextColor(Color.WHITE);
                t.setShadowLayer(Tk.dp(x, 2), 0, Tk.dp(x, 1), 0x806E4800);
                t.setGravity(Gravity.CENTER);
            }
            return true;
        }

        if (tag.equals("epcancel")) {                    // .edit-profile-cancel
            if (dark) x.setBackground(box(0, Tk.dp(x, 13), 0xFF2E3B4E, Tk.dp(x, 1)));
            else x.setBackground(box(0xFFF5F7FB, Tk.dp(x, 13), 0xFFDCE4EE, Tk.dp(x, 1)));
            x.setElevation(0f);
            if (t != null) t.setTextColor(dark ? 0xFFF8FAFC : 0xFF334155);
            return true;
        }
        return false;
    }
}
