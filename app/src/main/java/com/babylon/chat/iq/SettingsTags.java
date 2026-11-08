package com.babylon.chat.iq;

import android.view.View;
import android.widget.TextView;

/** وسوم (android:tag) خاصة بشاشات الإعدادات وفروعها. مسؤول عنها مَن يبني part_settings.xml. */
final class SettingsTags {
    private SettingsTags() { }

    static boolean apply(String tag, View x, TextView t, boolean dark) {
        // ==== SETTINGS TAGS (أضف الفروع هنا) ====
        if (tag.equals("sxhero")) {
            final float r = Tk.dp(x, 28);
            x.setBackground(Tk.shape(Tk.surface(dark), r, Tk.border(dark), Tk.dp(x, 1)));
            x.setClipToOutline(true);
            x.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(View v, android.graphics.Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
                }
            });
            return true;
        }
        if (tag.equals("sxcover")) {
            android.graphics.drawable.ShapeDrawable sd = new android.graphics.drawable.ShapeDrawable(new android.graphics.drawable.shapes.RectShape());
            sd.setShaderFactory(new android.graphics.drawable.ShapeDrawable.ShaderFactory() {
                @Override public android.graphics.Shader resize(int w, int h) {
                    return new android.graphics.LinearGradient(0, 0, w, h,
                            new int[]{0xFFC29D30, 0xFFF0C94D, 0xFF2AABEE}, new float[]{0f, 0.35f, 1f},
                            android.graphics.Shader.TileMode.CLAMP);
                }
            });
            sd.setAlpha(235);
            x.setBackground(sd);
            return true;
        }
        return false;
    }
}
