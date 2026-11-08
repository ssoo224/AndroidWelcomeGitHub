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

    private static float d(Context c) { return c.getResources().getDisplayMetrics().density; }
    private static int dp(Context c, float v) { return (int) (v * d(c) + 0.5f); }

    // ------------------------------------------------------------------ العلامات
    /** style 0 = علامة الفقاعة (TICK_SVG)، style 1 = علامة قائمة المحادثات (clTick). */
    public static final class TickDrawable extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int state, style;
        private final float dens;

        public TickDrawable(Context c, int state, int style, int color) {
            this.state = (state == WAIT && style == 0) ? SENT : state; this.style = style;   // عند الإرسال ✓ واحدة (بلا ساعة)، وعند الرؤية ✓✓
            this.dens = d(c);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(color);
        }

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
        if (quote != null) b.addView(replyQuote(a, quote, mine));
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
            tick.setImageDrawable(new TickDrawable(a, state, 0, 0xFFFFFFFF));
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
            tl.setMarginStart(dp(a, 3));
            m.addView(tick, tl);
        }
        return m;
    }

    public static void setState(View bubble, int state) {
        View t = bubble.findViewWithTag("tick");
        if (t instanceof ImageView) ((ImageView) t).setImageDrawable(new TickDrawable(t.getContext(), state, 0, 0xFFFFFFFF));
    }

    /** اقتباس الرد داخل الرسالة مطابق لـ .reply-quote: خلفية خفيفة، حواف 10، حشوة 5×9، خط ذهبي 3dp على اليمين، سطر واحد، محاذاة يمين. */
    private static View replyQuote(Activity a, String quote, boolean mine) {
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
        String qName = null, qText = quote;
        int sep = quote.indexOf('\u0001');
        if (sep >= 0) { qName = quote.substring(0, sep); qText = quote.substring(sep + 1); }
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
        col.addView(q, new LinearLayout.LayoutParams(-1, -2));
        box.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        View bar = new View(a);
        bar.setBackgroundColor(0xFFC29D30);
        box.addView(bar, new LinearLayout.LayoutParams(dp(a, 3), -1));
        return box;
    }

    // ------------------------------------------------------------------ فاصل اليوم
    public static TextView sysPill(Activity a, String label) {
        TextView t = new TextView(a);
        t.setText(label);
        t.setTextSize(13);
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
        public void setDark(boolean dk) { dark = dk; invalidate(); }
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
            p.setColor(mine ? 0xFFFFFFFF : 0xFFC29D30);
            c.drawRoundRect(flip ? left : x, cy - th / 2, flip ? x : right, cy + th / 2, th, th, p);
            p.setColor(0xFFFFFFFF);
            c.drawCircle(x, cy, r, p);
            p.setColor(mine ? 0xFFA9791A : 0xFFB8861B);
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
            anim = on; t0 = System.nanoTime();
            invalidate();
        }
        @Override protected void onDraw(Canvas c) {
            float dens = d(getContext());
            int n = 40;
            float w = getWidth(), h = getHeight(), bw = 2 * dens;
            float step = (w - bw) / (n - 1);
            double t = (System.nanoTime() - t0) / 1e9;
            for (int i = 1; i <= n; i++) {
                float hh = 4;
                if (i % 3 == 0) hh = 7; else if (i % 4 == 0) hh = 6; else if (i % 5 == 0) hh = 8;
                hh += (i * 7 % 6) + (i % 9 == 0 ? 3 : 0);
                hh *= dens;
                float x = flip ? (i - 1) * step : w - bw - (i - 1) * step;   // المرسلة: الأول عند اليمين، المستلمة: عند اليسار
                if (anim) {
                    int k = i % 4;                    // نفس تأخيرات موجة التسجيل
                    double dly = k == 1 ? 0.2 : k == 2 ? 0.5 : k == 3 ? 0.35 : 0;
                    double xx = (t + dly) / 0.72, fr = xx - Math.floor(xx);
                    double vv = (((long) Math.floor(xx)) % 2 == 0) ? fr : 1 - fr;
                    double e = vv * vv * (3 - 2 * vv);
                    float hm = Math.min(h, hh * 1.6f);   // ارتفاع أكبر قليلاً ليظهر التحرك
                    float cur = (float) (hm * (0.45 + 0.55 * e));
                    p.setColor(playColor);
                    p.setAlpha((int) (255 * (0.6 + 0.4 * e)));
                    c.drawRoundRect(x, (h - cur) / 2, x + bw, (h + cur) / 2, bw * 2, bw * 2, p);
                    continue;
                }
                boolean played = ((i - 1f) / (n - 1f)) <= progress;
                p.setColor(played && progress > 0 ? gold : base);
                p.setAlpha(played && progress > 0 ? 242 : 115);
                c.drawRoundRect(x, (h - hh) / 2, x + bw, (h + hh) / 2, bw * 2, bw * 2, p);
            }
            if (anim) postInvalidateOnAnimation();
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
            if (time != null) timeTv.setText(time);
        }
    }
}
