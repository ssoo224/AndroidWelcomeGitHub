package com.babylon.chat.iq;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * مكوّنات الرسائل داخل غرفة المحادثة كما في الـ HTML:
 * فقاعة نص (مع اقتباس الرد)، علامات الإرسال/القراءة/الانتظار (SVG الأصلي يُرسم هنا)،
 * بطاقة الرسالة الصوتية (زر تشغيل 35dp، شريط تقدم، موجة 40 عموداً)، وكبسولة فاصل اليوم.
 * ألوان الوضع الليلي/النهاري تُضبط بالوسوم في Tk.handle (mtM, mtO, mmM, mmO, rqM, ...).
 */
public final class MsgViews {
    private MsgViews() { }

    public static final int SENT = 0, READ = 1, WAIT = 2;

    /** النقر على اقتباس الرد داخل الفقاعة: الرجوع للرسالة الأصلية (goToReplied في الـ HTML). */
    public interface ReplyJump { void go(View bubble, String replyId); }
    public static ReplyJump replyJump;

    private static float d(Context c) { return c.getResources().getDisplayMetrics().density; }
    private static int dp(Context c, float v) { return (int) (v * d(c) + 0.5f); }

    // ------------------------------------------------------------------ العلامات
    /** style 0 = علامة الفقاعة (TICK_SVG)، style 1 = علامة قائمة المحادثات (clTick). */
    public static final class TickDrawable extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int state, style;
        private final float dens;

        public TickDrawable(Context c, int state, int style, int color) {
            this.state = state; this.style = style;   // ساعة انتظار حتى يصل للسيرفر، ثم ✓، ثم ✓✓ عند الرؤية
            this.dens = d(c);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(color);
        }

        public int state() { return state; }

        @Override public int getIntrinsicWidth() {
            if (style == 1) return (int) (16 * dens);
            return (int) ((state == READ ? 18.5f : state == WAIT ? 12f : 15f) * dens);
        }
        @Override public int getIntrinsicHeight() {
            if (style == 1) return (int) (16 * dens);
            return (int) ((state == WAIT ? 12f : 11f) * dens);
        }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            float vw, vh, sw;
            Path a = new Path(), a2 = null;
            if (style == 1) {
                vw = 24; vh = 24; sw = 2.2f;
                if (state == READ) {
                    a.moveTo(2, 13); a.lineTo(6, 17); a.lineTo(14, 8);
                    a2 = new Path(); a2.moveTo(10, 15); a2.lineTo(12, 17); a2.lineTo(21, 7);
                } else if (state == WAIT) {
                    a.addCircle(12, 12, 8.5f, Path.Direction.CW);
                    a2 = new Path(); a2.moveTo(12, 7.5f); a2.lineTo(12, 12); a2.lineTo(15, 14);
                } else {
                    a.moveTo(5, 13); a.lineTo(9, 17); a.lineTo(19, 7);
                }
            } else if (state == READ) {
                vw = 20; vh = 12; sw = 2;
                a.moveTo(1.5f, 6.5f); a.lineTo(5.3f, 10.2f); a.lineTo(13, 1.8f);
                a2 = new Path(); a2.moveTo(9, 9.2f); a2.lineTo(10.2f, 10.2f); a2.lineTo(18.4f, 1.8f);
            } else if (state == WAIT) {
                vw = 16; vh = 16; sw = 1.7f;
                a.addCircle(8, 8, 6.3f, Path.Direction.CW);
                a2 = new Path(); a2.moveTo(8, 4.4f); a2.lineTo(8, 8); a2.lineTo(10.4f, 9.6f);
            } else {
                vw = 16; vh = 12; sw = 2;
                a.moveTo(2, 6.5f); a.lineTo(5.8f, 10.2f); a.lineTo(14, 1.8f);
            }
            c.save();
            c.translate(b.left, b.top);
            c.scale(b.width() / vw, b.height() / vh);
            p.setStrokeWidth(sw);
            c.drawPath(a, p);
            if (a2 != null) c.drawPath(a2, p);
            c.restore();
        }
        @Override public void setAlpha(int alpha) { p.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    // ------------------------------------------------------------------ حاوية الفقاعة
    /** فقاعة بحدّ أقصى 80% من عرض المحادثة (max-width:80% في الـ HTML) حتى يبقى جانبها ظاهراً يميناً/يساراً. */
    public static class Bub extends LinearLayout {
        public Bub(Context c) { super(c); }
        @Override protected void onMeasure(int w, int h) {
            int mode = MeasureSpec.getMode(w), size = MeasureSpec.getSize(w);
            if (mode != MeasureSpec.EXACTLY && size > 0) {
                int cap = Math.max((int) (size * 0.8f), getMinimumWidth());
                if (cap < size) w = MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST);
            }
            super.onMeasure(w, h);
        }
    }

    // ------------------------------------------------------------------ فقاعة نص
    /** يعيد فقاعة عمودية: [اقتباس الرد] + نص + صف الوقت/العلامة. الوسم bubMine/bubOther يلوّن الخلفية. */
    public static LinearLayout textBubble(Activity a, String text, boolean mine, String time, int state, float fontSp) {
        LinearLayout b = new Bub(a);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setTag(mine ? "bubMine" : "bubOther");
        b.setPadding(dp(a, 10), dp(a, 6), dp(a, 10), dp(a, 5));
        b.setMinimumWidth(dp(a, 64));
        bind(a, b, text, mine, time, state, fontSp);
        return b;
    }

    /** يعيد بناء محتوى الفقاعة (للتعديل أيضاً). */
    public static void bind(Activity a, LinearLayout b, String raw, boolean mine, String time, int state, float fontSp) {
        b.removeAllViews();
        b.setContentDescription(raw);
        String quote = null, body = raw;
        if (raw.startsWith("↩ ")) {
            int nl = raw.indexOf('\n');
            if (nl > 0) { quote = raw.substring(2, nl); body = raw.substring(nl + 1); }
        }
        java.util.ArrayList<String> ems = emojiClusters(body);
        if (ems != null && ems.size() == 1) { bindEmoji(a, b, body.trim(), quote, mine, time, state); return; }
        if (ems != null) { bindEmojiRow(a, b, ems, quote, mine, time, state); return; }   // عدة إيموجي: بلا فقاعة
        // رسالة عادية: يُستعاد شكل الفقاعة (قد تكون كانت إيموجي قبل التعديل)
        b.setTag(mine ? "bubMine" : "bubOther");
        b.setPadding(dp(a, 10), dp(a, 6), dp(a, 10), dp(a, 5));
        b.setMinimumWidth(dp(a, 64));
        b.setGravity(Gravity.START | Gravity.TOP);
        if (quote != null) b.addView(replyQuote(a, quote, mine, null));
        TextView t = new TextView(a);
        t.setText(body);
        t.setTextSize(fontSp);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setTag(mine ? "mtM" : "mtO");
        t.setPadding(dp(a, 2), 0, dp(a, 2), dp(a, 4));
        t.setLineSpacing(0, 1.12f);
        b.addView(t);
        b.addView(metaRow(a, mine, time, state));
    }


    // ------------------------------------------------------------------ رسالة إيموجي واحد (babylonSingleEmoji / babylonApplyBigEmoji في الـ HTML)
    private static boolean isPict(int c) {
        return c == 0xA9 || c == 0xAE || c == 0x203C || c == 0x2049 || c == 0x2122 || c == 0x2139
                || (c >= 0x2194 && c <= 0x21AA) || (c >= 0x231A && c <= 0x23FF) || c == 0x24C2
                || (c >= 0x25AA && c <= 0x25FE) || (c >= 0x2600 && c <= 0x27BF) || c == 0x2934 || c == 0x2935
                || (c >= 0x2B05 && c <= 0x2B55) || c == 0x3030 || c == 0x303D || c == 0x3297 || c == 0x3299
                || (c >= 0x1F000 && c <= 0x1FAFF && !(c >= 0x1F3FB && c <= 0x1F3FF) && !(c >= 0x1F1E6 && c <= 0x1F1FF));
    }
    private static boolean isMod(int c) { return c == 0xFE0F || (c >= 0x1F3FB && c <= 0x1F3FF) || (c >= 0xE0020 && c <= 0xE007F); }

    /** يقسّم النص إلى إيموجيات (علم، keycap، رمز مع معدِّلات/ZWJ). يعيد null إن احتوى على أي شيء غير إيموجي (المسافات مسموحة) أو زاد عن 10. */
    public static java.util.ArrayList<String> emojiClusters(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty() || t.length() > 300) return null;
        int[] cp = cps(t);
        int n = cp.length, i = 0;
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        while (i < n) {
            int c = cp[i];
            if (c == ' ' || c == 0xA0) { i++; continue; }
            int s = i;
            if (c >= 0x1F1E6 && c <= 0x1F1FF) {                                   // علم
                if (i + 1 < n && cp[i + 1] >= 0x1F1E6 && cp[i + 1] <= 0x1F1FF) i += 2; else return null;
            } else if (c == '#' || c == '*' || (c >= '0' && c <= '9')) {          // keycap
                if (i + 1 < n && cp[i + 1] == 0x20E3) i += 2;
                else if (i + 2 < n && cp[i + 1] == 0xFE0F && cp[i + 2] == 0x20E3) i += 3;
                else return null;
            } else {
                if (!isPict(c)) return null;
                boolean vs = false;
                i++;
                while (true) {
                    while (i < n && isMod(cp[i])) { if (cp[i] == 0xFE0F) vs = true; i++; }
                    if (i + 1 < n && cp[i] == 0x200D && isPict(cp[i + 1])) { i += 2; continue; }
                    break;
                }
                if (c < 0x231A && !vs) return null;                               // © ® ™ بدون FE0F ليست إيموجي
            }
            out.add(new String(cp, s, i - s));
            if (out.size() > 40) return null;
        }
        return out.isEmpty() ? null : out;
    }

    /** هل النص إيموجي واحد فقط؟ */
    public static boolean isSingleEmoji(String raw) {
        java.util.ArrayList<String> l = emojiClusters(raw);
        return l != null && l.size() == 1;
    }

    /** هل النص كله إيموجيات فقط (واحد أو أكثر، حتى 10)؟ */
    public static boolean isEmojiOnly(String raw) { return emojiClusters(raw) != null; }

    private static int[] cps(String t) {   // بدون String.codePoints() (API 24+)
        int[] out = new int[t.codePointCount(0, t.length())];
        for (int i = 0, k = 0; i < t.length(); k++) { int c = t.codePointAt(i); out[k] = c; i += Character.charCount(c); }
        return out;
    }

    private static int clampDp(float v, float lo, float hi) { return (int) Math.max(lo, Math.min(hi, v)); }

    private static final java.util.HashMap<String, byte[]> EMOJI_BYTES = new java.util.HashMap<>();
    private static final java.util.HashSet<String> EMOJI_FAILED = new java.util.HashSet<>();

    /** الإيموجي المتحرك (Noto) من gstatic كما في الـ HTML؛ إن لم يوجد يبقى الإيموجي الأصلي بحركة خفيفة. */
    private static void loadAnimatedEmoji(final Activity a, final String emoji, final ImageView iv, final TextView fb, final int sizePx) {
        loadAnimatedEmoji(a, emoji, iv, fb, sizePx, true);
    }

    /** animate=false: يُعرض الإطار الأول ثابتاً (صورة بدقة عالية) بلا أي حركة — يُستعمل مع الإيموجيات المتعددة. */
    private static void loadAnimatedEmoji(final Activity a, final String emoji, final ImageView iv, final TextView fb, final int sizePx, final boolean animate) {
        if (android.os.Build.VERSION.SDK_INT < 28) return;
        final StringBuilder sb = new StringBuilder();
        final java.util.ArrayList<String> tries = new java.util.ArrayList<>();
        StringBuilder noVs = new StringBuilder();
        for (int c : cps(emoji)) {
            if (sb.length() > 0) sb.append('_');
            sb.append(Integer.toHexString(c));
            if (c != 0xFE0F) { if (noVs.length() > 0) noVs.append('_'); noVs.append(Integer.toHexString(c)); }
        }
        tries.add(sb.toString());
        if (noVs.length() > 0 && !noVs.toString().equals(sb.toString())) tries.add(noVs.toString());
        final String key = tries.get(0);
        new Thread(() -> {
            byte[] data;
            synchronized (EMOJI_BYTES) { data = EMOJI_BYTES.get(key); if (data == null && EMOJI_FAILED.contains(key)) return; }
            if (data == null) {
                for (String code : tries) {
                    try {
                        java.net.HttpURLConnection cn = (java.net.HttpURLConnection) new java.net.URL("https://fonts.gstatic.com/s/e/notoemoji/latest/" + code + "/512.webp").openConnection();
                        cn.setConnectTimeout(6000); cn.setReadTimeout(8000);
                        if (cn.getResponseCode() != 200) continue;
                        java.io.InputStream is = cn.getInputStream();
                        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                        byte[] buf = new byte[8192]; int r;
                        while ((r = is.read(buf)) > 0) bo.write(buf, 0, r);
                        is.close();
                        data = bo.toByteArray();
                        break;
                    } catch (Throwable ignored) { }
                }
                synchronized (EMOJI_BYTES) {
                    if (data == null) { EMOJI_FAILED.add(key); return; }
                    if (EMOJI_BYTES.size() > 60) EMOJI_BYTES.clear();
                    EMOJI_BYTES.put(key, data);
                }
            }
            try {
                final android.graphics.ImageDecoder.Source src0 = android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(data));
                final Drawable d = animate
                        ? android.graphics.ImageDecoder.decodeDrawable(src0, (dec, info, src) -> dec.setTargetSize(sizePx, sizePx))
                        : new android.graphics.drawable.BitmapDrawable(a.getResources(),
                                android.graphics.ImageDecoder.decodeBitmap(src0, (dec, info, src) -> dec.setTargetSize(sizePx, sizePx)));
                a.runOnUiThread(() -> {
                    iv.setImageDrawable(d);
                    iv.setVisibility(View.VISIBLE);
                    fb.setVisibility(View.GONE);
                    if (d instanceof android.graphics.drawable.AnimatedImageDrawable) {
                        android.graphics.drawable.AnimatedImageDrawable ad = (android.graphics.drawable.AnimatedImageDrawable) d;
                        ad.setRepeatCount(android.graphics.drawable.AnimatedImageDrawable.REPEAT_INFINITE);
                        ad.start();
                    }
                });
            } catch (Throwable ignored) { }
        }).start();
    }

    /** يبني فقاعة إيموجي: بلا خلفية، إيموجي كبير (96–150dp)، كبسولة الوقت تحته، والرد بجانبه في الجهة المقابلة. */
    private static void bindEmoji(Activity a, LinearLayout b, String emoji, String quote, boolean mine, String time, int state) {
        b.setTag("bubEmoji");
        b.setBackground(null);
        b.setPadding(dp(a, 4), dp(a, 2), dp(a, 4), dp(a, 2));
        b.setMinimumWidth(0);
        b.setGravity(Gravity.CENTER_HORIZONTAL);
        float wdp = a.getResources().getDisplayMetrics().widthPixels / d(a);
        final int S = clampDp(wdp * 0.34f, 96, 150);
        final int natDp = clampDp(wdp * 0.26f, 72, 112);

        FrameLayout box = new FrameLayout(a);
        box.setTag("emojiBox");
        final TextView fb = new TextView(a);
        fb.setText(emoji);
        fb.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, natDp);
        fb.setIncludeFontPadding(false);
        fb.setGravity(Gravity.CENTER);
        box.addView(fb, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        final ImageView iv = new ImageView(a);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setVisibility(View.GONE);
        box.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        // حركة خفيفة للإيموجي الأصلي (babylonEmojiIdle): تطفو للأعلى قليلاً وتميل
        final float up = d(a) * 6f;
        final android.animation.ObjectAnimator idle = android.animation.ObjectAnimator.ofPropertyValuesHolder(fb,
                android.animation.PropertyValuesHolder.ofKeyframe(View.TRANSLATION_Y,
                        android.animation.Keyframe.ofFloat(0f, 0f), android.animation.Keyframe.ofFloat(.25f, -up),
                        android.animation.Keyframe.ofFloat(.5f, 0f), android.animation.Keyframe.ofFloat(.75f, -up / 2f), android.animation.Keyframe.ofFloat(1f, 0f)),
                android.animation.PropertyValuesHolder.ofKeyframe(View.ROTATION,
                        android.animation.Keyframe.ofFloat(0f, 0f), android.animation.Keyframe.ofFloat(.25f, -4f),
                        android.animation.Keyframe.ofFloat(.5f, 0f), android.animation.Keyframe.ofFloat(.75f, 4f), android.animation.Keyframe.ofFloat(1f, 0f)),
                android.animation.PropertyValuesHolder.ofKeyframe(View.SCALE_X,
                        android.animation.Keyframe.ofFloat(0f, 1f), android.animation.Keyframe.ofFloat(.25f, 1.05f),
                        android.animation.Keyframe.ofFloat(.5f, 1f), android.animation.Keyframe.ofFloat(.75f, 1.03f), android.animation.Keyframe.ofFloat(1f, 1f)),
                android.animation.PropertyValuesHolder.ofKeyframe(View.SCALE_Y,
                        android.animation.Keyframe.ofFloat(0f, 1f), android.animation.Keyframe.ofFloat(.25f, 1.05f),
                        android.animation.Keyframe.ofFloat(.5f, 1f), android.animation.Keyframe.ofFloat(.75f, 1.03f), android.animation.Keyframe.ofFloat(1f, 1f)));
        idle.setDuration(2400);
        idle.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        fb.post(() -> { fb.setPivotX(fb.getWidth() / 2f); fb.setPivotY(fb.getHeight() * 0.8f); });
        fb.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { if (fb.getVisibility() == View.VISIBLE && !idle.isStarted()) idle.start(); }
            @Override public void onViewDetachedFromWindow(View v) { idle.cancel(); }
        });
        loadAnimatedEmoji(a, emoji, iv, fb, (int) (S * d(a)));
        iv.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, orr, ob) -> { if (iv.getVisibility() == View.VISIBLE) idle.cancel(); });

        View content = box;
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(dp(a, S), dp(a, S));
        if (quote != null) {
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            row.setGravity(Gravity.CENTER_VERTICAL);
            View q = replyQuote(a, quote, mine, b);
            q.setTag(null);
            q.setBackground(rounded(0x598794A8, dp(a, 14)));
            q.setAlpha(0.8f);
            recolorQuote(q);
            LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(dp(a, 140), -2);
            ql.setMargins(dp(a, 8), 0, dp(a, 8), 0);
            if (mine) { row.addView(q, ql); row.addView(box, boxLp); }   // رسائلي: الإيموجي نحو الخارج (يمين) والاقتباس للداخل
            else { row.addView(box, boxLp); row.addView(q, ql); }
            content = row;
            b.addView(content, new LinearLayout.LayoutParams(-2, -2));
        } else b.addView(content, boxLp);

        LinearLayout meta = metaRow(a, mine, time, state);
        meta.setBackground(rounded(0x33000000, dp(a, 10)));
        meta.setPadding(dp(a, 8), dp(a, 1), dp(a, 8), dp(a, 1));
        for (int i = 0; i < meta.getChildCount(); i++) {
            View c = meta.getChildAt(i);
            if (c instanceof TextView) { c.setTag("mmEmoji"); ((TextView) c).setTextColor(0xFFFFFFFF); ((TextView) c).setTextSize(10.5f); }
        }
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-2, -2);
        ml.gravity = Gravity.LEFT; ml.topMargin = -dp(a, 2);   // الوقت والعلامة يسار الإيموجي
        meta.setLayoutParams(ml);
        b.addView(meta);
    }

    /** إيموجيات متعددة (مثل 😂😂🤣): بلا فقاعة ولا خلفية؛ الحجم يصغر مع زيادة العدد، وكبسولة الوقت تحتها. */
    private static void bindEmojiRow(Activity a, LinearLayout b, java.util.ArrayList<String> ems, String quote, boolean mine, String time, int state) {
        b.setTag("bubEmoji");
        b.setBackground(null);
        b.setPadding(dp(a, 4), dp(a, 2), dp(a, 4), dp(a, 2));
        b.setMinimumWidth(0);
        b.setGravity(Gravity.CENTER_HORIZONTAL);
        int n = ems.size();
        float wdp = a.getResources().getDisplayMetrics().widthPixels / d(a);
        int sz = n == 2 ? 62 : n == 3 ? 52 : n <= 5 ? 42 : 34;
        final int perRowMax = 10;                                      // حتى 10 في السطر الواحد (يصغر ليتسع)، وما زاد ينزل لسطر جديد
        int perRow = Math.min(n, perRowMax);
        sz = clampDp(Math.min(sz, (wdp * 0.8f - 16f) / perRow * 0.95f), 24, sz);
        sz = Math.max(20, sz - 8);   // أصغر حجم للإيموجيات الكثيرة 20dp (كان 16)
        // كل إيموجي صورة مستقلة بدقة 512 (حادة) بدل نص يُكبَّر من خط نقطي؛ والنص الأصلي احتياط حتى تُحمَّل الصورة
        LinearLayout box = new LinearLayout(a);
        box.setTag("emojiRowBox");   // بلا حركة ظهور/طفو
        box.setOrientation(LinearLayout.VERTICAL);
        box.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        LinearLayout rowL = null;
        for (int k = 0; k < n; k++) {
            if (k % perRow == 0) {
                rowL = new LinearLayout(a);
                rowL.setOrientation(LinearLayout.HORIZONTAL);
                rowL.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-2, -2);
                rl.gravity = Gravity.LEFT;                              // السطر الجديد يبدأ من اليسار
                box.addView(rowL, rl);
            }
            String e = ems.get(k);
            FrameLayout cell = new FrameLayout(a);
            TextView tv = new TextView(a);
            tv.setText(e);
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, sz * 0.75f);
            tv.setIncludeFontPadding(false);
            tv.setGravity(Gravity.CENTER);
            cell.addView(tv, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
            ImageView iv = new ImageView(a);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setVisibility(View.GONE);
            cell.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            rowL.addView(cell, new LinearLayout.LayoutParams(dp(a, sz), dp(a, sz)));
            loadAnimatedEmoji(a, e, iv, tv, (int) (sz * d(a)), false);   // عدة إيموجي: بلا انميشن
        }
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(-2, -2);

        if (quote != null) {
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            row.setGravity(Gravity.CENTER_VERTICAL);
            View q = replyQuote(a, quote, mine, b);
            q.setTag(null);
            q.setBackground(rounded(0x598794A8, dp(a, 14)));
            q.setAlpha(0.8f);
            recolorQuote(q);
            LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(dp(a, 140), -2);
            ql.setMargins(dp(a, 8), 0, dp(a, 8), 0);
            if (mine) { row.addView(q, ql); row.addView(box, boxLp); }
            else { row.addView(box, boxLp); row.addView(q, ql); }
            b.addView(row, new LinearLayout.LayoutParams(-2, -2));
        } else b.addView(box, boxLp);

        LinearLayout meta = metaRow(a, mine, time, state);
        meta.setBackground(rounded(0x33000000, dp(a, 10)));
        meta.setPadding(dp(a, 8), dp(a, 1), dp(a, 8), dp(a, 1));
        for (int i = 0; i < meta.getChildCount(); i++) {
            View c = meta.getChildAt(i);
            if (c instanceof TextView) { c.setTag("mmEmoji"); ((TextView) c).setTextColor(0xFFFFFFFF); ((TextView) c).setTextSize(10.5f); }
        }
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-2, -2);
        ml.gravity = Gravity.LEFT; ml.topMargin = -dp(a, 2);   // الوقت والعلامة يسار الإيموجي
        meta.setLayoutParams(ml);
        b.addView(meta);
    }

    private static android.graphics.drawable.GradientDrawable rounded(int color, int r) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(color); g.setCornerRadius(r);
        return g;
    }

    /** ألوان اقتباس الرد فوق خلفية الشات: نص أبيض بظل، اسم/خط ذهبي (لا يتبع وسوم الفقاعة). */
    private static void recolorQuote(View q) {
        if (q instanceof TextView) {
            TextView t = (TextView) q;
            t.setTag(null); t.setTextColor(0xFFFFFFFF); t.setShadowLayer(3f, 0f, 1f, 0x8C000000);
        } else if (q instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) q;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                Object tg = c.getTag();
                if (tg != null && String.valueOf(tg).startsWith("rqn") && c instanceof TextView) { c.setTag(null); ((TextView) c).setTextColor(0xFFF0D37A); ((TextView) c).setShadowLayer(3f, 0f, 1f, 0x8C000000); }
                else if (tg != null && String.valueOf(tg).startsWith("rqb")) { c.setTag(null); c.setBackgroundColor(0xFFF0D37A); }
                else recolorQuote(c);
            }
        }
    }

    /** ظهور رسالة إيموجي جديدة: تكبر مع دوران وارتداد (babylonEmojiPop). */
    public static void popEmoji(View bubble) {
        if (bubble == null) return;
        View box = bubble.findViewWithTag("emojiBox");
        if (box == null) return;
        box.setPivotX(box.getWidth() / 2f); box.setPivotY(box.getHeight() / 2f);
        android.animation.ObjectAnimator an = android.animation.ObjectAnimator.ofPropertyValuesHolder(box,
                android.animation.PropertyValuesHolder.ofKeyframe(View.SCALE_X, android.animation.Keyframe.ofFloat(0f, .15f), android.animation.Keyframe.ofFloat(.6f, 1.18f), android.animation.Keyframe.ofFloat(1f, 1f)),
                android.animation.PropertyValuesHolder.ofKeyframe(View.SCALE_Y, android.animation.Keyframe.ofFloat(0f, .15f), android.animation.Keyframe.ofFloat(.6f, 1.18f), android.animation.Keyframe.ofFloat(1f, 1f)),
                android.animation.PropertyValuesHolder.ofKeyframe(View.ROTATION, android.animation.Keyframe.ofFloat(0f, -18f), android.animation.Keyframe.ofFloat(.6f, 6f), android.animation.Keyframe.ofFloat(1f, 0f)),
                android.animation.PropertyValuesHolder.ofKeyframe(View.ALPHA, android.animation.Keyframe.ofFloat(0f, 0f), android.animation.Keyframe.ofFloat(.6f, 1f), android.animation.Keyframe.ofFloat(1f, 1f)));
        an.setDuration(600);
        an.start();
    }

    public static LinearLayout metaRow(Activity a, boolean mine, String time, int state) {
        LinearLayout m = new LinearLayout(a);
        m.setOrientation(LinearLayout.HORIZONTAL);
        m.setGravity(Gravity.CENTER_VERTICAL);
        m.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.gravity = Gravity.START;
        m.setLayoutParams(lp);
        TextView tm = new TextView(a);
        tm.setText(time);
        tm.setTextSize(11);
        tm.setTag(mine ? "mmM" : "mmO");
        tm.setIncludeFontPadding(false);
        m.addView(tm);
        if (mine) {
            ImageView tick = new ImageView(a);
            tick.setTag("tick");
            tick.setImageDrawable(new TickDrawable(a, state, 0, Tk.tickColor(state)));
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
            tl.setMarginStart(dp(a, 3));
            m.addView(tick, tl);
        }
        return m;
    }

    public static void setState(View bubble, int state) {
        View t = bubble.findViewWithTag("tick");
        if (t instanceof ImageView) ((ImageView) t).setImageDrawable(new TickDrawable(t.getContext(), state, 0, Tk.tickColor(state)));
    }

    /** اقتباس الرد داخل الرسالة مطابق لـ .reply-quote: خلفية خفيفة، حواف 10، حشوة 5×9، خط ذهبي 3dp على اليمين، سطر واحد، محاذاة يمين. */
    private static View replyQuote(Activity a, String quote, boolean mine, final View owner) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        box.setTag(mine ? "rqM" : "rqO");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(a, 4));
        box.setLayoutParams(lp);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(a, 9), dp(a, 5), dp(a, 9), dp(a, 5));
        String qName = null, qText = quote, qId = "", qKind = "";
        int sep = quote.indexOf('\u0001');
        if (sep >= 0) { qName = quote.substring(0, sep); qText = quote.substring(sep + 1); }
        int s2 = qText.indexOf('\u0002');
        if (s2 >= 0) {
            String[] pr = qText.split("\u0002", -1);
            qText = pr[0];
            if (pr.length > 1) qId = pr[1];
            if (pr.length > 2) qKind = pr[2];
        }
        if (qName != null && qName.length() > 0) {      // .reply-quote-name: سطر الاسم فوق نص الاقتباس
            TextView qn = new TextView(a);
            qn.setText(qName);
            qn.setTextSize(11f);
            qn.setSingleLine(true);
            qn.setEllipsize(android.text.TextUtils.TruncateAt.END);
            qn.setGravity(Gravity.RIGHT);
            qn.setTypeface(null, android.graphics.Typeface.BOLD);
            qn.setTag(mine ? "rqnM" : "rqnO");
            LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-1, -2);
            nl.bottomMargin = dp(a, 2);
            col.addView(qn, nl);
        }
        TextView q = new TextView(a);
        q.setText(qText);
        q.setTextSize(12f);
        q.setMaxLines(1);
        q.setSingleLine(true);
        q.setEllipsize(android.text.TextUtils.TruncateAt.END);
        q.setGravity(Gravity.RIGHT);
        q.setTypeface(null, android.graphics.Typeface.BOLD);
        q.setTag(mine ? "rqtM" : "rqtO");
        int icoRes = ("image".equals(qKind) && "صورة".equals(qText)) ? R.drawable.ic_rq_image
                : ("audio".equals(qKind) && "بصمة صوتية".equals(qText)) ? R.drawable.ic_rq_mic : 0;
        if (icoRes != 0) {
            android.graphics.drawable.Drawable ico = a.getResources().getDrawable(icoRes, null).mutate();
            ico.setColorFilter(Tk.chatPal == null ? (mine ? 0xFFFFFFFF : 0xFFC29D30) : Tk.rqAccent(mine), android.graphics.PorterDuff.Mode.SRC_IN);
            q.setCompoundDrawablePadding(dp(a, 4));
            q.setCompoundDrawablesWithIntrinsicBounds(null, null, ico, null);
        }
        col.addView(q, new LinearLayout.LayoutParams(-1, -2));
        box.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        View bar = new View(a);
        bar.setBackgroundColor(Tk.rqAccent(mine));      // هامش الرد يتبع الثيم (بابل: ذهبي)
        bar.setTag(mine ? "rqbM" : "rqbO");
        box.addView(bar, new LinearLayout.LayoutParams(dp(a, 3), -1));
        if (qId.length() > 0) {
            final String rid = qId;
            box.setClickable(true);
            box.setOnClickListener(v -> {
                View tg = owner != null ? owner : (v.getParent() instanceof View ? (View) v.getParent() : null);
                if (replyJump != null && tg != null) replyJump.go(tg, rid);
            });
            box.setOnLongClickListener(v -> { View tg = owner != null ? owner : (v.getParent() instanceof View ? (View) v.getParent() : null); return tg != null && tg.performLongClick(); });
        }
        return box;
    }

    /** اقتباس الرد كعنصر مستقل (للصور) / يُضاف أعلى فقاعة (للصوتيات): الاسم + النص + خط ذهبي، والنقر للانتقال للرسالة الأصلية. */
    public static View quoteView(Activity a, String quote, boolean mine) { return replyQuote(a, quote, mine, null); }
    public static void addQuote(Activity a, LinearLayout bubble, String quote, boolean mine) { bubble.addView(replyQuote(a, quote, mine, null), 0); }

    // ------------------------------------------------------------------ فاصل اليوم
    public static TextView sysPill(Activity a, String label) {
        TextView t = new TextView(a);
        t.setText(label);
        t.setTextSize(12.5f);                       // .sys-msg: font-size 12.5px
        t.setLineSpacing(0f, 1.2f);                 // line-height 1.5 تقريباً
        t.setGravity(Gravity.CENTER);               // text-align:center
        t.setIncludeFontPadding(false);
        t.setMaxWidth((int) (a.getResources().getDisplayMetrics().widthPixels * 0.88f));   // max-width:88%
        t.setTag("sysp");
        t.setPadding(dp(a, 13), dp(a, 5), dp(a, 13), dp(a, 5));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.setMargins(0, dp(a, 7), 0, dp(a, 7));
        t.setLayoutParams(lp);
        return t;
    }

    // ------------------------------------------------------------------ الرسالة الصوتية
    public interface Handle {
        void setPlaying(boolean playing);
        void setProgress(float f, String time);
    }

    public interface SeekListener { void onSeek(float fraction); }

    /** شريط التقدم: مسار 3dp + مقبض دائري. */
    public static final class TrackView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float progress; boolean mine; boolean dark;
        boolean flip;      // المستلمة: معكوسة (التقدم من اليسار)
        SeekListener listener;
        public TrackView(Context c, boolean mine) { super(c); this.mine = mine; }
        int accent;        // ثيم غير بابل: accent-color:var(--accent-chat) (0 = الألوان الأصلية)
        public void setDark(boolean dk) { dark = dk; invalidate(); }
        public void setAccent(int c) { accent = c; invalidate(); }
        int thumbAccent;   // لون قلب المقبض: يتبع ثيم المحادثة (0 = الذهبي الأصلي)
        public void setThumbAccent(int c) { thumbAccent = c; invalidate(); }
        public void setProgress(float f) { progress = Math.max(0, Math.min(1, f)); invalidate(); }
        @Override protected void onDraw(Canvas c) {
            float dens = d(getContext());
            float w = getWidth(), h = getHeight(), cy = h / 2f, th = 3 * dens, r = 6 * dens;
            float left = r, right = w - r;
            p.setStyle(Paint.Style.FILL);
            p.setColor(mine ? 0x66FFFFFF : (dark ? 0x30FFFFFF : 0xFFDCE4EE));
            c.drawRoundRect(left, cy - th / 2, right, cy + th / 2, th, th, p);
            // المرسلة: يبدأ التقدم من اليمين، المستلمة: من اليسار (معكوسة تماماً)
            float x = flip ? left + (right - left) * progress : right - (right - left) * progress;
            p.setColor(accent != 0 ? accent : (mine ? 0xFFFFFFFF : 0xFFC29D30));
            c.drawRoundRect(flip ? left : x, cy - th / 2, flip ? x : right, cy + th / 2, th, th, p);
            p.setColor(0xFFFFFFFF);
            c.drawCircle(x, cy, r, p);
            p.setColor(thumbAccent != 0 ? thumbAccent : (accent != 0 ? accent : (mine ? 0xFFA9791A : 0xFFB8861B)));
            c.drawCircle(x, cy, r - 2 * dens, p);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (listener == null) return false;
            float dens = d(getContext()), r = 6 * dens, w = getWidth();
            float f0 = Math.max(0, Math.min(1, (e.getX() - r) / Math.max(1, w - 2 * r)));
            float f = flip ? f0 : 1f - f0;
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) getParent().requestDisallowInterceptTouchEvent(true);
            setProgress(f);
            if (e.getActionMasked() == MotionEvent.ACTION_UP) listener.onSeek(f);
            return true;
        }
    }

    /** موجة الصوت: أعمدة بعرض 2dp، ارتفاعاتها من قواعد nth-child في الـ CSS. */
    public static final class WaveView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean flip;      // المستلمة: العمود الأول عند اليسار
        float progress; boolean playing, anim; int base = 0xFF17212B, gold = 0xFFC29D30, playColor = 0xFFFFFFFF;
        private long t0;
        public WaveView(Context c) { super(c); }
        public void setColors(int baseColor, int goldColor) { base = baseColor; gold = goldColor; invalidate(); }
        public void setColors(int baseColor, int goldColor, int playingColor) { playColor = playingColor; setColors(baseColor, goldColor); }
        public void setProgress(float f, boolean pl) { progress = f; playing = pl; invalidate(); }
        /** أثناء التشغيل: الموجة متحركة مثل موجة التسجيل وكلها بلون واحد (أبيض). */
        public void setAnimating(boolean on) {
            if (on == anim) return;
            anim = on; t0 = System.nanoTime(); lastNs = t0;
            invalidate();
        }
        private float mix;          // 0 = الموجة الثابتة نفسها، 1 = الموجة المتحركة بالكامل (انتقال سلس بلا قفزة)
        private long lastNs;
        private static int lerpArgb(int a, int b, float f) {
            int aa = Math.round(((a >>> 24) & 255) + (((b >>> 24) & 255) - ((a >>> 24) & 255)) * f);
            int rr = Math.round(((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * f);
            int gg = Math.round(((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * f);
            int bb = Math.round((a & 255) + ((b & 255) - (a & 255)) * f);
            return (aa << 24) | (rr << 16) | (gg << 8) | bb;
        }
        @Override protected void onDraw(Canvas c) {
            float dens = d(getContext());
            float w = getWidth(), h = getHeight(), bw = 2 * dens;
            float step = bw + 2 * dens;                       // عرض العمود 2dp + مسافة ثابتة 2dp (في التشغيل وقبله)
            int n = Math.max(1, (int) Math.floor((w - bw) / step + 1e-3f) + 1);
            long now = System.nanoTime();
            float dt = Math.max(0f, Math.min(0.1f, (now - lastNs) / 1e9f));
            lastNs = now;
            if (anim) mix = Math.min(1f, mix + dt / 0.35f); else mix = Math.max(0f, mix - dt / 0.25f);
            double t = (now - t0) / 1e9;
            int idleCol = (base & 0x00FFFFFF) | (115 << 24);   // الموجة الثابتة: opacity .45
            for (int i = 1; i <= n; i++) {
                float hh = 4;
                if (i % 3 == 0) hh = 7; else if (i % 4 == 0) hh = 6; else if (i % 5 == 0) hh = 8;
                hh += (i * 7 % 6) + (i % 9 == 0 ? 3 : 0);
                hh *= dens;
                float x = flip ? (i - 1) * step : w - bw - (i - 1) * step;   // المرسلة: الأول عند اليمين، المستلمة: عند اليسار
                float cur = hh; int col = idleCol;
                if (mix > 0f) {
                    // نفس الأعمدة بالضبط: تتحرك حول ارتفاعها الأصلي وتنتقل تدريجياً من لونها الثابت إلى لون التشغيل
                    int k = i % 4;                    // نفس تأخيرات موجة التسجيل
                    double dly = k == 1 ? 0.2 : k == 2 ? 0.5 : k == 3 ? 0.35 : 0;
                    double xx = (t + dly) / 0.72, fr = xx - Math.floor(xx);
                    double vv = (((long) Math.floor(xx)) % 2 == 0) ? fr : 1 - fr;
                    double e = vv * vv * (3 - 2 * vv);
                    float hm = Math.min(h, hh * 1.6f);
                    float animH = (float) (hm * (0.45 + 0.55 * e));
                    cur = hh + (animH - hh) * mix;
                    int playCol = (playColor & 0x00FFFFFF) | (((int) (255 * (0.6 + 0.4 * e))) << 24);
                    col = lerpArgb(idleCol, playCol, mix);
                }
                p.setColor(col);
                c.drawRoundRect(x, (h - cur) / 2, x + bw, (h + cur) / 2, bw * 2, bw * 2, p);
            }
            if (anim || mix > 0f) postInvalidateOnAnimation();
        }
    }

    public static final class VoiceBubble extends Bub implements Handle {
        public final FrameLayout playBtn;
        public final ImageView playIcon;
        public final TextView timeTv;
        public final TrackView track;
        public final WaveView wave;
        private final boolean mine;
        public VoiceBubble(Activity a, boolean mine, String totalTime) {
            super(a);
            this.mine = mine;
            setOrientation(VERTICAL);
            setTag(mine ? "bubMine" : "bubOther");
            setPadding(dp(a, 10), dp(a, 8), dp(a, 10), dp(a, 5));
            setMinimumWidth(dp(a, 230));

            // نفس ترتيب الـ HTML (RTL) لكل المرسلين: زر التشغيل في أقصى اليمين، وعلى يساره المحتوى:
            // العنوان (محاذاة لليمين) ثم صف [المدة | شريط التقدم] ثم الموجة. الترتيب مضبوط يدوياً لأن RTL غير مفعّل في التطبيق.
            LinearLayout card = new LinearLayout(a);
            card.setOrientation(HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);

            LinearLayout content = new LinearLayout(a);
            content.setOrientation(VERTICAL);
            LayoutParams cl = new LayoutParams(0, -2, 1f);
            if (mine) cl.setMarginEnd(dp(a, 8)); else cl.setMarginStart(dp(a, 8));

            playBtn = new FrameLayout(a);
            playBtn.setFocusable(false); playBtn.setFocusableInTouchMode(false);
            playBtn.setTag(mine ? "vpM" : "vpO");
            playIcon = new ImageView(a);
            playIcon.setImageResource(R.drawable.ic_voice_play);
            playBtn.addView(playIcon, new FrameLayout.LayoutParams(dp(a, 23), dp(a, 23), Gravity.CENTER));
            // المرسلة: المحتوى ثم الزر (يمين). المستلمة: الزر (يسار) ثم المحتوى — معكوس تماماً
            if (mine) { card.addView(content, cl); card.addView(playBtn, new LayoutParams(dp(a, 35), dp(a, 35))); }
            else { card.addView(playBtn, new LayoutParams(dp(a, 35), dp(a, 35))); card.addView(content, cl); }

            TextView title = new TextView(a);
            title.setText("رسالة صوتية");
            title.setTextSize(11.5f);
            title.setTypeface(null, android.graphics.Typeface.BOLD);
            title.setGravity(mine ? Gravity.RIGHT : Gravity.LEFT);
            title.setTag(mine ? "vtTM" : "vtTO");
            content.addView(title);

            LinearLayout row = new LinearLayout(a);
            row.setOrientation(HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            timeTv = new TextView(a);
            timeTv.setText(totalTime);
            timeTv.setTextSize(9.5f);
            timeTv.setAlpha(0.8f);
            timeTv.setTypeface(null, android.graphics.Typeface.BOLD);
            timeTv.setTag(mine ? "vtM" : "vtO");
            timeTv.setMinWidth(dp(a, 30));      // عرض ثابت: تغيّر الأرقام أثناء التشغيل لا يعيد قياس المحادثة كل 80ms
            timeTv.setGravity(mine ? Gravity.LEFT : Gravity.RIGHT);
            track = new TrackView(a, mine);
            track.flip = !mine;
            track.setTag(mine ? "vkM" : "vkO");
            LayoutParams tl = new LayoutParams(0, dp(a, 14), 1f);
            if (mine) { row.addView(timeTv); tl.setMarginStart(dp(a, 8)); row.addView(track, tl); }
            else { tl.setMarginEnd(dp(a, 8)); row.addView(track, tl); row.addView(timeTv); }
            LayoutParams rl = new LayoutParams(-1, -2);
            rl.topMargin = dp(a, 3);
            content.addView(row, rl);

            wave = new WaveView(a);
            wave.flip = !mine;
            wave.setTag(mine ? "wvM" : "wvO");
            LayoutParams wl = new LayoutParams(-1, dp(a, 16));
            wl.topMargin = dp(a, 3);
            content.addView(wave, wl);
            // الموجة تمتد بنفس امتداد خط التشغيل فقط (من بداية الخط إلى نهايته) وليس إلى آخر الرسالة
            final LinearLayout fContent = content;
            final WaveView fWave = wave;
            final float trackInset = 6 * d(a);
            track.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> {
                int lm = Math.round(l + trackInset);
                int rm = Math.round(fContent.getWidth() - r + trackInset);
                LayoutParams cur = (LayoutParams) fWave.getLayoutParams();
                if (cur.leftMargin != lm || cur.rightMargin != rm) {
                    cur.leftMargin = lm; cur.rightMargin = rm;
                    fWave.setLayoutParams(cur);
                }
            });

            addView(card, new LayoutParams(-1, -2));
        }
        @Override public void setPlaying(boolean playing) {
            playIcon.setImageResource(playing ? R.drawable.ic_voice_pause : R.drawable.ic_voice_play);
            wave.setAnimating(playing);
        }
        @Override public void setProgress(float f, String time) {
            track.setProgress(f);
            wave.setProgress(f, f > 0);
            if (time != null && !time.contentEquals(timeTv.getText())) timeTv.setText(time);
        }
    }

    // ------------------------------------------------------------------ معاينة آخر رسالة في قائمة المحادثات
    /** 0 = نص عادي، 1 = صورة، 2 = رسالة صوتية، 3 = مقطع مرئي (يتعرّف أيضاً على الصيغ القديمة المخزّنة بإيموجي). */
    public static int mediaKindOf(String s) {
        if (s == null) return 0;
        String t = s.trim();
        if (t.equals("📷 صورة") || t.equals("صورة") || t.equals("▧ صورة")) return 1;
        if (t.equals("🎤 رسالة صوتية") || t.equals("رسالة صوتية") || t.equals("♬ بصمة صوتية") || t.equals("بصمة صوتية")) return 2;
        if (t.equals("🎬 مقطع مرئي") || t.equals("📹 مقطع مرئي") || t.equals("🎥 مقطع مرئي")
                || t.equals("مقطع مرئي") || t.equals("🎬 فيديو") || t.equals("📹 فيديو") || t.equals("فيديو")) return 3;
        return 0;
    }
    public static String mediaLabelOf(int kind) {
        return kind == 1 ? "صورة" : kind == 2 ? "رسالة صوتية" : kind == 3 ? "مقطع مرئي" : "";
    }
    public static int mediaIconOf(int kind) {
        return kind == 1 ? R.drawable.ic_cl_image : kind == 2 ? R.drawable.ic_cl_mic : kind == 3 ? R.drawable.ic_cl_video : 0;
    }
}
