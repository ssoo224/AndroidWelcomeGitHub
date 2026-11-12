package com.babylon.chat.iq;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import java.util.HashMap;
import java.util.WeakHashMap;

/**
 * خط التطبيق: خمسة خيارات (عادي، عريض، نسخ، مضغوط، آلة كاتبة).
 * عند الاختيار يُطبَّق على كل TextView/EditText في كل شاشات التطبيق (الموجودة وما يُضاف لاحقاً)،
 * مع الحفاظ على الخط الأصلي لكل عنصر ليرجع كما كان عند اختيار «عادي».
 * ملاحظة: أي عنصر وسمه tag = "keepfont" (مثل معاينة الخطوط نفسها) لا يتأثر.
 */
public final class FontManager {
    private FontManager() { }

    public static final String[] IDS    = {"normal", "bold", "serif", "condensed", "mono"};
    public static final String[] LABELS = {"عادي", "عريض", "نسخ", "مضغوط", "آلة كاتبة"};

    private static final String PREF = "babylon_ui", KEY = "appFont";
    private static String mode = null;
    private static int version = 0;
    private static final HashMap<String, Typeface> cache = new HashMap<>();
    /** لكل TextView: {الخط الأصلي، الخط الذي طبّقناه، رقم النسخة وقت التطبيق} */
    private static final WeakHashMap<TextView, Object[]> state = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> installed = new WeakHashMap<>();

    public static String current(Context c) {
        if (mode == null) {
            try { mode = c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "normal"); }
            catch (Throwable t) { mode = "normal"; }
            if (indexOf(mode) < 0) mode = "normal";
        }
        return mode;
    }

    public static int indexOf(String id) {
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return i;
        return -1;
    }

    /** يحفظ الاختيار ويطبّقه فوراً على شاشة Activity الحالية (والباقي يُطبَّق عند فتحه). */
    public static void set(Activity a, String id) {
        if (indexOf(id) < 0) id = "normal";
        mode = id;
        version++;
        try { a.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, id).apply(); } catch (Throwable ignored) { }
        applyNow(a);
    }

    public static void applyNow(Activity a) {
        try { apply(a.getWindow().getDecorView()); } catch (Throwable ignored) { }
    }

    /** يربط الـ Activity: تطبيق أولي + إعادة التطبيق كلما تغيّر التخطيط (رسائل جديدة، صفحات تُبنى، إلخ). */
    public static void install(final Activity a) {
        try {
            current(a);
            final View decor = a.getWindow().getDecorView();
            if (installed.containsKey(decor)) { apply(decor); return; }
            installed.put(decor, true);
            decor.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                boolean pending = false;
                @Override public void onGlobalLayout() {
                    if (pending) return;
                    pending = true;
                    decor.post(() -> { pending = false; apply(decor); });
                }
            });
            apply(decor);
        } catch (Throwable ignored) { }
    }

    /** خط المعاينة داخل بطاقة الاختيار. */
    public static Typeface preview(String id) {
        if ("normal".equals(id)) return Typeface.DEFAULT;
        return make(id, Typeface.NORMAL);
    }

    private static Typeface make(String id, int style) {
        if ("bold".equals(id)) style = (style & Typeface.ITALIC) != 0 ? Typeface.BOLD_ITALIC : Typeface.BOLD;
        String key = id + style;
        Typeface t = cache.get(key);
        if (t != null) return t;
        switch (id) {
            case "serif":     t = Typeface.create("serif", style); break;
            case "condensed": t = Typeface.create("sans-serif-condensed", style); break;
            case "mono":      t = Typeface.create(Typeface.MONOSPACE, style); break;
            default:          t = Typeface.create(Typeface.DEFAULT, style); break;   // normal / bold
        }
        cache.put(key, t);
        return t;
    }

    private static void apply(View v) {
        if (v == null || "keepfont".equals(v.getTag())) return;
        if (v instanceof TextView) fix((TextView) v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) apply(g.getChildAt(i));
        }
    }

    private static void fix(TextView tv) {
        Typeface cur = tv.getTypeface();
        Object[] st = state.get(tv);
        if (st != null && cur == st[1] && ((Integer) st[2]) == version) return;   // لم يتغيّر شيء
        Typeface orig;
        if (st == null || cur != st[1]) {        // خط جديد وضعه التطبيق: نحفظه كأصل
            orig = cur;
        } else orig = (Typeface) st[0];           // تغيّر الاختيار فقط: نبني من الأصل
        Typeface target = "normal".equals(mode) ? orig : make(mode, orig == null ? Typeface.NORMAL : orig.getStyle());
        if (target != cur) tv.setTypeface(target);
        state.put(tv, new Object[]{orig, tv.getTypeface(), version});
    }
}
