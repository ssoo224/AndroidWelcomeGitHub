package com.babylon.chat.iq;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * خلفية صفحة المحادثات: الرسمة البابلية (اللاماسو + الزقورة + أطراف النقش الإغريقي + الخطوط الذهبية)
 * مأخوذة بكسلاً ببكسل من اللقطتين المرجعيتين (res/drawable-nodpi/home_bg_light.webp و home_bg_dark.webp)
 * بعد إزالة عناصر الواجهة منها (الهيدر، نص «لا توجد محادثات»، الزر العائم، شريط التنقل).
 *
 * الرسمة محاذاة لنافذة التطبيق كاملة (لا لحدود الـ View)، فنفس الـ Drawable يوضع على الجذر (خلف شريط الحالة)
 * وعلى الهيدر وعلى صفحة المحادثات وتبدو كلها قطعة واحدة متصلة كما في اللقطة.
 * على الشاشات التي نسبتها تختلف عن اللقطة، يُمَطّ/يُضغط شريط أفقي فارغ في منتصف الرسمة فقط، فيبقى النقش العلوي
 * ملتصقاً بأعلى الشاشة والاماسو والزقورة بأسفلها دون تشويه.
 */
final class HomeBg extends Drawable {
    /** ألوان احتياطية (وسط اللقطة) إن تعذّر فكّ الصورة. */
    static final int BASE_DARK = 0xFF141519, BASE_LIGHT = 0xFFFAF7F2;

    // الشريط الأفقي الفارغ من الرسمة (نسبة من ارتفاعها) الذي يتمدد/ينكمش حسب ارتفاع الشاشة
    private static final float STRETCH_FROM = 0.272f, STRETCH_TO = 0.302f;

    private static Bitmap artLight, artDark;

    private final boolean dark;
    private final Bitmap art;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint basePaint = new Paint();
    private final int[] loc = new int[2];
    private final Rect src = new Rect();
    private final RectF dst = new RectF();

    // أكبر ارتفاع نافذة رُسمت به الخلفية بهذا العرض: عند ظهور الكيبورد/تغيّر الحشوات لا تتحرك الرسمة ولا تنضغط، بل تُقصّ فقط.
    private static int sRefW = -1, sRefH = -1;
    private static synchronized int refHeight(int w, int h) {
        if (w != sRefW) { sRefW = w; sRefH = h; }
        else if (h > sRefH) sRefH = h;
        return sRefH;
    }

    HomeBg(Context ctx, boolean dark, float density) {
        this.dark = dark;
        this.art = load(ctx, dark);
        basePaint.setColor(dark ? BASE_DARK : BASE_LIGHT);
    }

    boolean isDark() { return dark; }

    private static synchronized Bitmap load(Context ctx, boolean dark) {
        Bitmap cur = dark ? artDark : artLight;
        if (cur != null) return cur;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inScaled = false;
            cur = BitmapFactory.decodeResource(ctx.getResources(), dark ? R.drawable.home_bg_dark : R.drawable.home_bg_light, o);
        } catch (Throwable ignored) { }
        if (dark) artDark = cur; else artLight = cur;
        return cur;
    }

    @Override public void draw(Canvas c) {
        Rect b = getBounds();
        if (b.width() <= 0 || b.height() <= 0) return;
        c.save();
        c.clipRect(b);
        c.drawRect(b, basePaint);
        if (art == null) { c.restore(); return; }

        // أبعاد النافذة وموضع الـ View فيها (لمحاذاة الرسمة للشاشة كاملة)
        int winW = b.width(), winH = b.height();
        float ox = b.left, oy = b.top;
        if (getCallback() instanceof View) {
            View v = (View) getCallback();
            View r = v.getRootView();
            if (r != null && r.getWidth() > 0 && r.getHeight() > 0) {
                v.getLocationInWindow(loc);
                winW = r.getWidth(); winH = r.getHeight();
                ox = -loc[0]; oy = -loc[1];
            }
        }
        int refH = refHeight(winW, winH);
        float top = oy + (winH - refH), bottom = oy + winH;   // الرسمة ملتصقة بأسفل النافذة

        int bw = art.getWidth(), bh = art.getHeight();
        int a = Math.round(bh * STRETCH_FROM), z = Math.round(bh * STRETCH_TO);
        float s = winW / (float) bw;
        float sy = Math.min(s, refH / (float) (a + (bh - z)));    // لا نكبّر رأسياً أكثر من الأفقي
        float topH = a * sy, botH = (bh - z) * sy;

        // الشريط الأوسط أولاً (بتداخل بكسل) ثم القطعتان العلوية والسفلية فوقه فلا تظهر خطوط التقاء
        src.set(0, a, bw, z);
        dst.set(ox, top + topH - 1f, ox + winW, bottom - botH + 1f);
        if (dst.height() > 0) c.drawBitmap(art, src, dst, paint);
        src.set(0, 0, bw, a);
        dst.set(ox, top, ox + winW, top + topH);
        c.drawBitmap(art, src, dst, paint);
        src.set(0, z, bw, bh);
        dst.set(ox, bottom - botH, ox + winW, bottom);
        c.drawBitmap(art, src, dst, paint);
        c.restore();
    }

    @Override public void setAlpha(int a) { }
    @Override public void setColorFilter(ColorFilter cf) { }
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
}
