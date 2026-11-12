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

    private static final String[] BASE_IDS    = {"normal", "bold", "serif", "condensed", "mono"};
    private static final String[] BASE_LABELS = {"عادي", "عريض", "نسخ", "مضغوط", "آلة كاتبة"};
    /** القائمتان تتوسّعان تلقائياً بكل خط عربي موجود في assets/fonts باسم ar_xxx.ttf (انظر init). */
    public static String[] IDS    = BASE_IDS;
    public static String[] LABELS = BASE_LABELS;
    private static final HashMap<String, Typeface> assetFonts = new HashMap<>();
    private static boolean scanned = false;

    private static String arLabel(String key) {
        switch (key) {
            case "cairo":   return "القاهرة";
            case "tajawal": return "تجوّل";
            case "amiri":   return "أميري";
            case "kufi":    return "كوفي";
            case "naskh":   return "نسخ عربي";
            case "almarai": return "المراعي";
            default:        return key;
        }
    }

    /** يفحص assets/fonts عن ملفات ar_*.ttf/otf (والعريض اختياري: ar_xxx_bold.ttf) ويضيفها لخيارات الخط. */
    public static synchronized void init(Context c) {
        if (scanned) return;
        scanned = true;
        try {
            String[] files = c.getApplicationContext().getAssets().list("fonts");
            java.util.ArrayList<String> ids = new java.util.ArrayList<>(java.util.Arrays.asList(BASE_IDS));
            java.util.ArrayList<String> labs = new java.util.ArrayList<>(java.util.Arrays.asList(BASE_LABELS));
            if (files != null) {
                java.util.Arrays.sort(files);
                for (String f : files) {
                    String low = f.toLowerCase();
                    if (!low.startsWith("ar_") || !(low.endsWith(".ttf") || low.endsWith(".otf"))) continue;
                    String key = low.substring(3, low.length() - 4);
                    if (key.endsWith("_bold")) continue;   // يُحمَّل مع الخط العادي
                    try {
                        Typeface reg = Typeface.createFromAsset(c.getApplicationContext().getAssets(), "fonts/" + f);
                        assetFonts.put("ar_" + key + "0", reg);
                        try {
                            String ext = low.substring(low.length() - 4);
                            assetFonts.put("ar_" + key + "1", Typeface.createFromAsset(c.getApplicationContext().getAssets(), "fonts/ar_" + key + "_bold" + ext));
                        } catch (Throwable noBold) { }
                        ids.add("ar_" + key);
                        labs.add(arLabel(key));
                    } catch (Throwable ignored) { }
                }
            }
            IDS = ids.toArray(new String[0]);
            LABELS = labs.toArray(new String[0]);
        } catch (Throwable ignored) { }
    }

    private static final String PREF = "babylon_ui", KEY = "appFont";
    private static String mode = null;
    private static int version = 0;
    private static final HashMap<String, Typeface> cache = new HashMap<>();
    /** لكل TextView: {الخط الأصلي، الخط الذي طبّقناه، رقم النسخة وقت التطبيق} */
    private static final WeakHashMap<TextView, Object[]> state = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> installed = new WeakHashMap<>();

    public static String current(Context c) {
        init(c);
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
        if (id.startsWith("ar_")) {   // خط عربي مرفق: العريض من ملفه إن وُجد، وإلا عادي بلا تثخين مزيّف
            boolean wantBold = (style & Typeface.BOLD) != 0;
            Typeface b = wantBold ? assetFonts.get(id + "1") : null;
            Typeface reg = assetFonts.get(id + "0");
            t = b != null ? b : (reg != null ? reg : Typeface.DEFAULT);
            cache.put(key, t);
            return t;
        }
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

    /** عنوان التطبيق الوحيد الذي يبقى عريضاً في الخط «عادي». */
    private static boolean isBrandTitle(TextView tv) {
        CharSequence t = tv.getText();
        return t != null && "babylon chat".equalsIgnoreCase(t.toString().trim());
    }

    /** الأنماط عادي/نسخ/مضغوط/آلة كاتبة: يزيل التعريض من كل نص في التطبيق (عربي وإنجليزي) ما عدا عنوان Babylon Chat. */
    private static Typeface plain(TextView tv, Typeface orig) {
        if (orig == null || isBrandTitle(tv)) return orig;
        boolean bold = (orig.getStyle() & Typeface.BOLD) != 0;
        boolean italic = (orig.getStyle() & Typeface.ITALIC) != 0;
        if (android.os.Build.VERSION.SDK_INT >= 28 && orig.getWeight() >= 600) {
            return Typeface.create(orig, 400, italic);
        }
        if (bold) return Typeface.create(orig, italic ? Typeface.ITALIC : Typeface.NORMAL);
        return orig;
    }

    private static void fix(TextView tv) {
        Typeface cur = tv.getTypeface();
        Object[] st = state.get(tv);
        if (st != null && cur == st[1] && ((Integer) st[2]) == version) return;   // لم يتغيّر شيء
        Typeface orig;
        if (st == null || cur != st[1]) {        // خط جديد وضعه التطبيق: نحفظه كأصل
            orig = cur;
        } else orig = (Typeface) st[0];           // تغيّر الاختيار فقط: نبني من الأصل
        Typeface base = "bold".equals(mode) ? orig : plain(tv, orig);   // كل الأنماط ما عدا «عريض» بلا تعريض (عدا عنوان Babylon Chat)
        Typeface target = "normal".equals(mode) ? base : make(mode, base == null ? Typeface.NORMAL : base.getStyle());
        if (target != cur) tv.setTypeface(target);
        state.put(tv, new Object[]{orig, tv.getTypeface(), version});
    }
}
