package com.babylon.chat.iq;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.TextView;

/**
 * قيم التصميم (design tokens) مستخرجة من ملف HTML (index5.html) بعد تطبيق كل طبقات الـ CSS.
 * 1px في الـ HTML = 1dp هنا. الخطوط: px -> sp بنفس الرقم (قبل معامل --bb-s).
 * الوضع (dark) يتغيّر أثناء التشغيل لذا كل الألوان دوال تأخذ dark.
 *
 * Tk.handle(...) يوزّع الوسوم (android:tag) غير المعروفة على SettingsTags و ProfileTags.
 */
public final class Tk {
    private Tk() { }

    // ----- الألوان الأساسية (HTML: --bg / --surface / ...)
    public static int pageBg(boolean d)  { return d ? 0xFF0E1621 : 0xFFF4F6FA; }
    public static int surface(boolean d) { return d ? 0xFF17212B : 0xFFFFFFFF; }
    public static int navBg(boolean d)   { return d ? 0xFF17212B : 0xFFFFFFFF; }   // شريط التنقل: ليلي #17212B / نهاري أبيض
    /** HTML: --cr-surface داخل المحادثة (خلفية الغرفة + لون الطبقة الزجاجية + لون شريط الإيماءات) */
    public static int chatSurface(boolean d) { return chatPal != null ? chatPal.chatBg : (d ? 0xFF17212B : 0xFFEEF2F8); }

    // ----- ثيم المحادثة الحالي (ChatThemes): chatPal = لوحة الثيم المطبّقة، وnull لثيم بابل (يبقى مظهره الأصلي)
    public static String chatThemeId = ChatThemes.DEFAULT_ID;
    public static ChatThemes.Pal chatPal = null;
    public static void setChatTheme(String id, boolean dark) {
        ChatThemes.Spec sp = ChatThemes.byId(id);
        chatThemeId = sp.id;
        chatPal = ChatThemes.isBabylon(sp.id) ? null : sp.pal(dark);
    }
    /** لون علامتَي القراءة: ثيم غير بابل → --tick-read للمقروءة وإلا لون نص رسالتي؛ بابل: أبيض كما كان. */
    public static int tickColor(int state) {
        if (chatPal == null) return 0xFFFFFFFF;
        return state == MsgViews.READ ? chatPal.tick : chatPal.textMine;
    }
    public static int surface2(boolean d){ return d ? 0xFF1D2A38 : 0xFFE8EDF4; }
    public static int border(boolean d)  { return d ? 0x14FFFFFF : 0xFFE3E9F1; }
    public static int text(boolean d)    { return d ? 0xFFF1F5F9 : 0xFF17212B; }
    public static int muted(boolean d)   { return d ? 0xFFA5B2C2 : 0xFF718096; }
    public static int muted2(boolean d)  { return d ? 0xFF8696A7 : 0xFF718096; }
    public static int gold(boolean d)    { return d ? 0xFFE4C45B : 0xFFC99A1C; }
    public static int goldDeep(boolean d){ return d ? 0xFFE4C45B : 0xFFB8861B; }
    public static int goldInk(boolean d) { return d ? 0xFFE4C45B : 0xFF96720D; }
    public static int red(boolean d)     { return d ? 0xFFFB7185 : 0xFFDC2626; }
    public static int chipBg(boolean d)  { return d ? 0x14FFFFFF : 0xFFEEF2F7; }   // .sx-ch
    public static int chipFg(boolean d)  { return d ? 0xFFE2E8F0 : 0xFF52657A; }
    public static int backBg(boolean d)  { return d ? 0x14FFFFFF : 0xFFEDF2F8; }   // .sx-back
    public static int backFg(boolean d)  { return d ? 0xFFF5D76E : 0xFF52657A; }

    // ----- تدرجات ذهبية ثابتة
    public static final int[] GOLD_145 = {0xFFD9AD34, 0xFFB8861B};   // chips / badges
    public static final int[] GOLD_135 = {0xFFE0BD4A, 0xFFB8891A};   // FAB
    public static final int[] GOLD_BTN = {0xFFC29D30, 0xFFF0C94D};   // .sx-seg button.on
    public static final int[] GOLD_AV  = {0xFFE8C25A, 0xFFB88A1C};   // chat avatar
    public static final int[] GOLD_HERO= {0xFFF2D268, 0xFFB8861B};   // .sx-av

    // ----- أدوات
    public static float dp(View v, float d) { return d * v.getResources().getDisplayMetrics().density; }
    public static int dpi(View v, float d) { return (int) (dp(v, d) + 0.5f); }

    public static GradientDrawable shape(int fill, float rPx, int stroke, float strokePx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(rPx);
        if (stroke != 0 && strokePx > 0) g.setStroke((int) Math.max(1, strokePx), stroke);
        return g;
    }

    public static GradientDrawable grad(GradientDrawable.Orientation o, int[] colors, float rPx) {
        GradientDrawable g = new GradientDrawable(o, colors);
        g.setCornerRadius(rPx);
        return g;
    }

    /** .sx-av : أفاتار بتدرج ذهبي + حلقة سطح 4dp + حلقة ذهبية 2dp (box-shadow 0 0 0 4px surface, 0 0 0 6px gold). */
    public static android.graphics.drawable.Drawable ringAvatar(View v, boolean dark) {
        GradientDrawable outer = new GradientDrawable();
        outer.setShape(GradientDrawable.OVAL);
        outer.setColor(dark ? 0xB3D4AF37 : 0xCCD4AF37);
        GradientDrawable mid = new GradientDrawable();
        mid.setShape(GradientDrawable.OVAL);
        mid.setColor(surface(dark));
        GradientDrawable inner = new GradientDrawable(GradientDrawable.Orientation.TL_BR, GOLD_HERO);
        inner.setShape(GradientDrawable.OVAL);
        android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{outer, mid, inner});
        ld.setLayerInset(1, dpi(v, 2), dpi(v, 2), dpi(v, 2), dpi(v, 2));
        ld.setLayerInset(2, dpi(v, 6), dpi(v, 6), dpi(v, 6), dpi(v, 6));
        return ld;
    }

    /** أفاتار رأس المحادثة: دائرة ذهبية بحجم الصورة كاملاً (بدون حلقات ذهبية/سطح حولها). */
    public static android.graphics.drawable.Drawable chatAvatar(View v, boolean dark) {
        GradientDrawable in = new GradientDrawable(); in.setShape(GradientDrawable.OVAL); in.setColor(dark ? 0xFFE4C45B : 0xFF9B7410);
        return in;
    }

    public static int parse(String hex) { return Color.parseColor(hex); }

    /** يُستدعى من MassActivity.style() لكل وسم غير معروف. يعيد true إذا عولج. */
    /** نمط الزخارف البابلية (البلاط 180x180 من SVG الـ HTML) يُرسم برمجياً ويتكرر. */
    private static void motifs(android.graphics.Canvas c, android.graphics.Paint p) {
        android.graphics.Path z = new android.graphics.Path();
        z.moveTo(12, 52); z.lineTo(48, 52);
        z.moveTo(16, 52); z.lineTo(16, 45); z.lineTo(44, 45); z.lineTo(44, 52);
        z.moveTo(20, 45); z.lineTo(20, 38); z.lineTo(40, 38); z.lineTo(40, 45);
        z.moveTo(24, 38); z.lineTo(24, 31); z.lineTo(36, 31); z.lineTo(36, 38);
        z.moveTo(30, 31); z.lineTo(30, 26);
        c.drawPath(z, p);
        c.save(); c.translate(125, 38);
        c.drawCircle(0, 0, 13, p); c.drawCircle(0, 0, 3, p);
        c.drawLine(0, -18, 0, 18, p); c.drawLine(-18, 0, 18, 0, p);
        c.drawLine(-12.7f, -12.7f, 12.7f, 12.7f, p); c.drawLine(12.7f, -12.7f, -12.7f, 12.7f, p);
        c.restore();
        float[][] tri = {{30, 108}, {48, 118}, {24, 128}};
        for (float[] t0 : tri) {
            android.graphics.Path a = new android.graphics.Path();
            a.moveTo(t0[0], t0[1]); a.rLineTo(11, 3); a.rLineTo(-7, 11); a.close();
            c.drawPath(a, p);
        }
        android.graphics.Path cr = new android.graphics.Path();
        cr.addCircle(145, 135, 15, android.graphics.Path.Direction.CW);
        android.graphics.Path cut = new android.graphics.Path();
        cut.addCircle(152, 131, 12, android.graphics.Path.Direction.CW);
        cr.op(cut, android.graphics.Path.Op.DIFFERENCE);
        c.drawPath(cr, p);
        c.drawCircle(152, 124, 2, p);
        android.graphics.Path dm = new android.graphics.Path();
        dm.moveTo(90, 82); dm.rLineTo(6, 6); dm.rLineTo(-6, 6); dm.rLineTo(-6, -6); dm.close();
        c.drawPath(dm, p);
        android.graphics.Path pm = new android.graphics.Path();
        pm.moveTo(88, 150); pm.lineTo(88, 172);
        pm.moveTo(88, 158); pm.quadTo(78, 154, 74, 156);
        pm.moveTo(88, 158); pm.quadTo(98, 154, 102, 156);
        pm.moveTo(88, 164); pm.quadTo(80, 161, 77, 163);
        pm.moveTo(88, 164); pm.quadTo(96, 161, 99, 163);
        c.drawPath(pm, p);
    }

    /** خلفية المحادثة: نفس الرموز لكن أصغر وأكثر عدداً ومبعثرة (دوران/إزاحة لكل خلية) وأخف وضوحاً. */
    public static android.graphics.drawable.Drawable chatPattern(View v, boolean dark, int baseColor) {
        final float d = v.getResources().getDisplayMetrics().density;
        final int cell = 90, n = 3, tile = cell * n;
        int size = (int) (tile * d);
        android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(bm);
        c.scale(d, d);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setStyle(android.graphics.Paint.Style.STROKE);
        p.setStrokeWidth(2.3f);
        p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        p.setStrokeJoin(android.graphics.Paint.Join.ROUND);
        p.setColor(dark ? 0x66E4C45B : 0x66B8861B);   // أوضح (كانت شبه شفافة)
        final float[] rot = {-18, 24, 9, -31, 14, -7, 33, -22, 5};
        final float[] jx = {6, -10, 14, -4, 9, -12, 3, 12, -8};
        final float[] jy = {-8, 5, -3, 12, -10, 7, -12, 2, 9};
        final float sc = 0.45f;   // الرموز أكبر قليلاً (~1px)
        for (int i = 0; i < n * n; i++) {
            int cx = (i % n) * cell, cy = (i / n) * cell;
            c.save();
            c.translate(cx + cell / 2f + jx[i], cy + cell / 2f + jy[i]);
            c.rotate(rot[i]);
            c.scale(sc, sc);
            c.translate(-90, -90);
            motifs(c, p);
            c.restore();
        }
        android.graphics.drawable.BitmapDrawable bd = new android.graphics.drawable.BitmapDrawable(v.getResources(), bm);
        bd.setTileModeXY(android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT);
        return new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{new android.graphics.drawable.ColorDrawable(baseColor), bd});
    }

    /** خلفية غرفة المحادثة بحسب الثيم: نقوش الثيم (#chatPattern) بشفافية مخفَّفة 50% عن الـ HTML: .03 ليلي / .035 نهاري / .04 لغير بابل. */
    public static android.graphics.drawable.Drawable chatRoomBg(View v, boolean dark) {
        ChatThemes.Spec sp = ChatThemes.byId(chatThemeId);
        if (chatPal != null) return new ChatPattern.Bg(v.getContext(), chatPal.chatBg, sp.symbols, chatPal.pattern, 0.04f);
        ChatThemes.Pal b = sp.pal(dark);
        return new ChatPattern.Bg(v.getContext(), chatSurface(dark), sp.symbols, b.pattern, dark ? 0.03f : 0.035f);
    }

    public static android.graphics.drawable.Drawable pattern(View v, boolean dark, int baseColor) {
        final float d = v.getResources().getDisplayMetrics().density;
        int size = (int) (180 * d);
        android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(bm);
        c.scale(d, d);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setStyle(android.graphics.Paint.Style.STROKE);
        p.setStrokeWidth(1.3f);
        p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        p.setStrokeJoin(android.graphics.Paint.Join.ROUND);
        p.setColor(dark ? 0x1FE4C45B : 0x26B8861B);
        motifs(c, p);
        android.graphics.drawable.BitmapDrawable bd = new android.graphics.drawable.BitmapDrawable(v.getResources(), bm);
        bd.setTileModeXY(android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT);
        android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{new android.graphics.drawable.ColorDrawable(baseColor), bd});
        return ld;
    }

    public static boolean handle(String tag, View x, TextView t, boolean dark) {
        // ---- فقاعات الرسائل (MsgViews)
        if (tag.equals("mtM")) { if (t != null) t.setTextColor(chatPal != null ? chatPal.textMine : 0xFFFFFFFF); return true; }
        if (tag.equals("mtO")) { if (t != null) t.setTextColor(chatPal != null ? chatPal.textOther : text(dark)); return true; }
        if (tag.equals("mmM")) { if (t != null) t.setTextColor(chatPal != null ? chatPal.textMine : 0xF2FFFFFF); return true; }
        if (tag.equals("mmO")) { if (t != null) t.setTextColor(chatPal != null ? chatPal.metaOther : (dark ? 0xFF94A3B8 : 0xFF7C8B9C)); return true; }
        if (tag.equals("tick")) {
            if (x instanceof android.widget.ImageView) {
                android.graphics.drawable.Drawable td = ((android.widget.ImageView) x).getDrawable();
                if (td instanceof MsgViews.TickDrawable) {
                    int st = ((MsgViews.TickDrawable) td).state();
                    ((android.widget.ImageView) x).setImageDrawable(new MsgViews.TickDrawable(x.getContext(), st, 0, tickColor(st)));
                }
            }
            return true;
        }
        if (tag.equals("sct")) { if (t != null) t.setTextColor(dark ? gold(true) : 0xFF17212B); return true; }   // .set-card-title
        if (tag.equals("rqM") || tag.equals("rqO")) {
            final float r = dp(x, 10);
            x.setBackground(shape(tag.equals("rqM") ? 0x1A000000 : 0x247F8CA0, r, 0, 0));
            x.setClipToOutline(true);
            x.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); }
            });
            return true;
        }
        if (tag.equals("rqnM") || tag.equals("rqnO")) { if (t != null) t.setTextColor(dark ? 0xFFE4C45B : 0xFF9B7410); return true; }   // .reply-quote-name: var(--babylon-gold)
        if (tag.equals("rqtM")) { if (t != null) t.setTextColor(chatPal != null ? chatPal.textMine : 0xFFFFFFFF); return true; }
        if (tag.equals("rqtO")) { if (t != null) t.setTextColor(chatPal != null ? chatPal.accent : 0xFFC29D30); return true; }   // HTML: لون .reply-quote في رسائل الطرف الآخر = ذهبي accent-chat
        // ---- شريط الرد أعلى حقل الكتابة (.reply-preview-bar في الـ HTML)
        if (tag.equals("rpBar")) {      // بطاقة عائمة: radius 22، حدّ 1px، خلفية --cr-card، ظل خفيف (reply-bar-rounder)
            x.setBackground(shape(dark ? 0xFF1E2A38 : 0xFFFFFFFF, dp(x, 22), dark ? 0x14FFFFFF : 0xFFDCE4EE, dp(x, 1)));
            x.setElevation(0f);
            return true;
        }
        if (tag.equals("rpContent")) {  // بلا حقل داخلي: يبقى الخط الذهبي فقط
            x.setBackground(null);
            return true;
        }
        if (tag.equals("rpGold")) { x.setBackgroundColor(dark ? 0xFFC9A45C : 0xFFB58B2A); return true; }
        if (tag.equals("rpLabel")) {
            if (t != null) {
                int c = dark ? 0xFFE4C45B : 0xFF8A6818;
                t.setTextColor(c);
                for (android.graphics.drawable.Drawable d : t.getCompoundDrawablesRelative())
                    if (d != null) d.mutate().setColorFilter(c, android.graphics.PorterDuff.Mode.SRC_IN);
            }
            return true;
        }
        if (tag.equals("rpMsg")) { if (t != null) t.setTextColor(dark ? 0xFFF1F5F9 : 0xFF17212B); return true; }
        if (tag.equals("rpCancel")) {
            int c = dark ? 0xFFA5B2C2 : 0xFF64748B;
            x.setBackground(shape(0x00000000, dp(x, 15), dark ? 0x14FFFFFF : 0xFFDBE2EA, dp(x, 1)));
            if (t != null) {
                t.setTextColor(c);
                for (android.graphics.drawable.Drawable d : t.getCompoundDrawablesRelative())
                    if (d != null) d.mutate().setColorFilter(c, android.graphics.PorterDuff.Mode.SRC_IN);
            }
            return true;
        }
        if (tag.equals("vtTM")) { if (t != null) t.setTextColor(0xFFFFFFFF); return true; }   // عنوان "رسالة صوتية" في المرسَلة: أبيض
        if (tag.equals("vtTO")) { if (t != null) t.setTextColor(dark ? 0xFFFFFFFF : 0xFFB8861B); return true; }   // في المستلمة: ذهبي في النهاري فقط، أبيض في الليلي
        if (tag.equals("vtM")) { if (t != null) t.setTextColor(0xFF292216); return true; }
        if (tag.equals("vtO")) { if (t != null) t.setTextColor(text(dark)); return true; }
        if (tag.equals("vkM") || tag.equals("vkO")) { if (x instanceof MsgViews.TrackView) ((MsgViews.TrackView) x).setDark(dark); return true; }
        if (tag.equals("wvM") || tag.equals("wvO")) {
            if (x instanceof MsgViews.WaveView)
                ((MsgViews.WaveView) x).setColors(tag.equals("wvM") ? 0xFF292216 : text(dark), tag.equals("wvM") ? 0xFFFFFFFF : 0xFFC29D30,
                        (tag.equals("wvM") || dark) ? 0xFFFFFFFF : 0xFFC29D30);   // أبيض عند التشغيل (في الفاتح وفقاعة المستلم: ذهبي كي لا يختفي على الأبيض)
            return true;
        }
        if (tag.equals("vpM") || tag.equals("vpO")) {
            float r = dp(x, 11);
            if (tag.equals("vpM")) {
                x.setBackground(shape(0xFFFFFFFF, r, 0, 0));
            } else {
                x.setBackground(grad(GradientDrawable.Orientation.TL_BR, new int[]{0xFFE8C85D, 0xFFC29D30}, r));
            }
            if (x instanceof android.view.ViewGroup && ((android.view.ViewGroup) x).getChildCount() > 0) {
                View ic = ((android.view.ViewGroup) x).getChildAt(0);
                if (ic instanceof android.widget.ImageView)
                    ((android.widget.ImageView) ic).setColorFilter(tag.equals("vpM") ? 0xFFA9791A : 0xFFFFFFFF);
            }
            return true;
        }
        if (tag.equals("sysp")) {
            x.setBackground(shape(dark ? 0x4D000000 : 0x1A000000, dp(x, 14), 0, 0));
            if (t != null) t.setTextColor(dark ? 0xFFFFFFFF : 0xFF2A3A48);
            return true;
        }
        if (tag.equals("homepat")) { x.setBackground(pattern(x, dark, pageBg(dark))); return true; }
        if (tag.equals("chatpat")) { x.setBackground(chatRoomBg(x, dark)); return true; }
        if (tag.equals("glasst")) {          // رأس المحادثة: طبقة زجاجية 72% تمتد خلف شريط الإشعارات وتتلاشى عند حافتها السفلية
            x.setBackground(new Glass(chatSurface(dark), true, dp(x, 16)));
            return true;
        }
        if (tag.equals("glassb")) {          // مدخل الرسائل: طبقة زجاجية 72% تتلاشى عند حافتها العلوية
            x.setBackground(new Glass(chatSurface(dark), false, dp(x, 16)));
            return true;
        }
        if (tag.equals("rbtn")) {            // زر دائري أبيض بظل (.icon-btn في الهيدر)
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(dark ? 0xFF1E2A38 : 0xFFFFFFFF);
            x.setBackground(g);
            return false;                    // يكمل t لتلوين النص/الأيقونة
        }
        if (tag.equals("pill")) {            // كبسولة الهيدر
            x.setBackground(shape(dark ? 0xFF1E2A38 : 0xFFFFFFFF, dp(x, 24), dark ? 0x14FFFFFF : 0xFFE3E9F1, dp(x, 1)));
            return true;
        }
        if (tag.equals("composer")) {        // مدخل الرسائل العائم
            // #chatView #textInputBar : --cr-card (#1e2a38 / #fff) ، حدّ --cr-line ، انحناء 19px
            x.setBackground(shape(dark ? 0xFF1C2836 : 0xFFFFFFFF, dp(x, 19), dark ? 0x14FFFFFF : 0xFFE1E8F1, dp(x, 1)));
            return true;
        }
        if (tag.equals("recbar")) {          // .recording-bar : surface + حدّ ، انحناء 18
            x.setBackground(shape(dark ? 0xFF17212B : 0xFFFFFFFF, dp(x, 18), dark ? 0x3394A3B8 : 0xFFE5EAF1, dp(x, 1)));
            return true;
        }
        if (tag.equals("recbtn")) {          // زر الإيقاف/الإرسال: دائرة 42 بتدرّج ذهبي 145deg وظل ذهبي
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFFE8C85D, 0xFFC29D30});
            g.setShape(GradientDrawable.OVAL);
            x.setBackground(g);
            if (t != null) t.setTextColor(0xFFFFFFFF);
            return true;
        }
        if (tag.equals("recleft")) {         // .rec-left : عمود 38 بتدرّج ذهبي وانحناء 12
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFFE8C85D, 0xFFC29D30});
            g.setCornerRadius(dp(x, 12));
            x.setBackground(g);
            return true;
        }
        if (tag.equals("sendbtn")) {         // زر الإرسال: تدرّج ذهبي 145deg ، انحناء 15/15/7/15 ، سهم أبيض ، ظل ذهبي
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFFE9C957, 0xFFC59B24});
            float r = dp(x, 15), s = dp(x, 7);
            g.setCornerRadii(new float[]{r, r, r, r, s, s, r, r});   // TL, TR, BR(7), BL
            x.setBackground(g);
            if (t != null) t.setTextColor(0xFFFFFFFF);
            return true;
        }
        if (tag.equals("fab")) {
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, GOLD_135);
            g.setShape(GradientDrawable.OVAL);
            x.setBackground(g);
            if (t != null) t.setTextColor(0xFFFFFFFF);
            return true;
        }
        if (tag.equals("navbg")) {           // #bottomNav.telegram-bottom-nav : كريمي نهاري / #17212B ليلي
            x.setBackgroundColor(navBg(dark));
            x.setElevation(0f);
            return true;
        }
        if (tag.equals("navline")) {         // الحدّ العلوي 1px + خط ذهبي متدرّج (::before في الـ HTML)
            final int base = dark ? 0xFF2A3644 : 0xFFEFE5C3;   // حدّ علوي: داكن في الليلي، فاتح في النهاري
            android.graphics.drawable.ShapeDrawable sd = new android.graphics.drawable.ShapeDrawable(new android.graphics.drawable.shapes.RectShape());
            sd.setShaderFactory(new android.graphics.drawable.ShapeDrawable.ShaderFactory() {
                @Override public android.graphics.Shader resize(int w, int h) {
                    return new android.graphics.LinearGradient(0, 0, w, 0,
                            new int[]{0x00D4AF37, 0x99D4AF37, 0xD9F6DF86, 0x99D4AF37, 0x00D4AF37},
                            new float[]{0f, 0.25f, 0.5f, 0.75f, 1f}, android.graphics.Shader.TileMode.CLAMP);
                }
            });
            x.setBackground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                    new android.graphics.drawable.ColorDrawable(base), sd}));
            return true;
        }
        if (SettingsTags.apply(tag, x, t, dark)) return true;
        if (ProfileTags.apply(tag, x, t, dark)) return true;
        return false;
    }
}
