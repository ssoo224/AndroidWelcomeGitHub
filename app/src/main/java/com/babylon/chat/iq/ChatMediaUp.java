package com.babylon.chat.iq;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * أدوات إرسال الصور والفيديو في المحادثة — منقولة من sendImage / sendChatVideo / bbVideoEdit / bbWaitVideoReady /
 * bbTinyThumb في الـ HTML: الرفع إلى Cloudinary مع التقدّم والإلغاء، بناء رابط الفيديو المعدّل (قص/دقة)،
 * انتظار جاهزيته، الصورة المصغّرة، وبطاقة «قيد الإرسال» (.video-pending).
 */
final class ChatMediaUp {
    private ChatMediaUp() { }

    static final String CLD_CLOUD = "xuljmnmh", CLD_PRESET = "stories_unsigned";
    static final int MAX_VIDEO_MB = 100;

    interface Prog { void on(long sent, long total); }

    static final class Cancel {
        volatile boolean cancelled;
        volatile HttpURLConnection conn;
        void cancel() { cancelled = true; try { if (conn != null) conn.disconnect(); } catch (Throwable ignored) { } }
    }

    // ------------------------------------------------------------------ الرفع
    /** يرفع الملف (image|video) إلى Cloudinary ويعيد JSON الاستجابة (secure_url, duration …). */
    static JSONObject upload(File f, String resource, String folder, Cancel cancel, Prog prog) throws Exception {
        final String boundary = "----BabylonChat" + System.currentTimeMillis();
        final String crlf = "\r\n";
        StringBuilder pre = new StringBuilder();
        pre.append("--").append(boundary).append(crlf).append("Content-Disposition: form-data; name=\"upload_preset\"").append(crlf).append(crlf).append(CLD_PRESET).append(crlf);
        pre.append("--").append(boundary).append(crlf).append("Content-Disposition: form-data; name=\"folder\"").append(crlf).append(crlf).append(folder).append(crlf);
        pre.append("--").append(boundary).append(crlf).append("Content-Disposition: form-data; name=\"file\"; filename=\"").append("video".equals(resource) ? "video.mp4" : "image.jpg").append("\"").append(crlf)
           .append("Content-Type: application/octet-stream").append(crlf).append(crlf);
        byte[] head = pre.toString().getBytes(StandardCharsets.UTF_8);
        byte[] tail = (crlf + "--" + boundary + "--" + crlf).getBytes(StandardCharsets.UTF_8);
        final long total = f.length();
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.cloudinary.com/v1_1/" + CLD_CLOUD + "/" + resource + "/upload").openConnection();
        if (cancel != null) cancel.conn = c;
        c.setRequestMethod("POST"); c.setDoOutput(true); c.setConnectTimeout(20000); c.setReadTimeout(120000);
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        c.setFixedLengthStreamingMode(head.length + total + tail.length);
        InputStream in = null;
        try {
            OutputStream out = c.getOutputStream();
            out.write(head);
            in = new FileInputStream(f);
            byte[] buf = new byte[32768]; int n; long sent = 0;
            while ((n = in.read(buf)) > 0) {
                if (cancel != null && cancel.cancelled) throw new Exception("cancelled");
                out.write(buf, 0, n);
                sent += n;
                if (prog != null) prog.on(sent, total);
            }
            out.write(tail); out.flush(); out.close();
            int code = c.getResponseCode();
            InputStream rs = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            if (rs != null) { byte[] b2 = new byte[8192]; int k; while ((k = rs.read(b2)) > 0) bos.write(b2, 0, k); rs.close(); }
            JSONObject r = null;
            try { r = new JSONObject(bos.toString("UTF-8")); } catch (Throwable ignored) { }
            if (code >= 200 && code < 300 && r != null && r.optString("secure_url", "").length() > 0) return r;
            String em = r != null && r.optJSONObject("error") != null ? r.optJSONObject("error").optString("message", "رمز " + code) : "رمز " + code;
            throw new Exception(em);
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
            c.disconnect();
        }
    }

    // ------------------------------------------------------------------ bbVideoEdit
    static final class VideoEdit { String url, poster; boolean edited; int duration; }

    private static int ev2(double n) { return (int) Math.max(2, Math.round(n / 2.0) * 2); }
    private static String f2(double v) { return String.format(Locale.US, "%.2f", v); }

    /** يبني رابط Cloudinary للفيديو المعدّل (قص زمني + قص مكاني + دقة) ورابط الصورة المصغّرة. */
    static VideoEdit videoEdit(String secureUrl, MediaComposer.Req o, double dur0) {
        VideoEdit out = new VideoEdit();
        out.url = secureUrl; out.poster = null; out.edited = false; out.duration = 0;
        double dur = o != null && o.dur > 0 ? o.dur : dur0;
        java.util.ArrayList<String> parts = new java.util.ArrayList<>(), posterParts = new java.util.ArrayList<>();
        boolean trimmed = o != null && dur > 0 && (o.start > 0.05 || o.end < dur - 0.05);
        double startAt = trimmed ? o.start : 0;
        if (trimmed) parts.add("so_" + f2(o.start) + ",eo_" + f2(o.end));
        double w = o == null ? 0 : o.natW, h = o == null ? 0 : o.natH;
        String cropPart = "";
        if (o != null && o.crop != null && o.natW > 0 && o.natH > 0) {
            int cw = ev2(o.crop[2]), ch = ev2(o.crop[3]);
            int cx = Math.max(0, Math.min(o.natW - cw, o.crop[0])), cy = Math.max(0, Math.min(o.natH - ch, o.crop[1]));
            cropPart = "c_crop,g_north_west,x_" + cx + ",y_" + cy + ",w_" + cw + ",h_" + ch;
            parts.add(cropPart); w = cw; h = ch;
        }
        double side = Math.min(w, h);
        if (o != null && o.res > 0 && side > 0 && o.res < side - 1) {
            double kk = o.res / side;
            parts.add("c_scale,w_" + ev2(w * kk) + ",h_" + ev2(h * kk));
        }
        out.duration = (int) Math.round(trimmed ? (o.end - o.start) : dur);
        posterParts.add("so_" + f2(startAt));
        if (cropPart.length() > 0) posterParts.add(cropPart);
        posterParts.add("w_480,c_limit");
        out.poster = secureUrl.replace("/video/upload/", "/video/upload/" + join(posterParts) + "/").replaceAll("\\.[^/.]+$", ".jpg");
        if (parts.isEmpty()) return out;
        String q = o.res == 1080 ? "q_auto:best" : (o.res == 480 || o.res == 720) ? "q_auto:good" : "q_auto";
        parts.add("vc_h264,ac_aac," + q);
        out.url = secureUrl.replace("/video/upload/", "/video/upload/" + join(parts) + "/").replaceAll("\\.[^/.]+$", ".mp4");
        out.edited = true;
        return out;
    }

    private static String join(java.util.ArrayList<String> a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.size(); i++) { if (i > 0) sb.append('/'); sb.append(a.get(i)); }
        return sb.toString();
    }

    /** bbWaitVideoReady: ok / bad / timeout (حتى 45 محاولة كل 3 ثوانٍ). */
    static String waitVideoReady(String url, Cancel cancel) {
        for (int tries = 0; tries < 45; tries++) {
            if (cancel != null && cancel.cancelled) return "bad";
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestMethod("HEAD"); c.setConnectTimeout(15000); c.setReadTimeout(20000); c.setUseCaches(false);
                int code = c.getResponseCode();
                c.disconnect();
                if (code >= 200 && code < 300) return "ok";
                if (!(code == 423 || code == 202 || code == 404 || code >= 500)) return "bad";
            } catch (Throwable ignored) { }
            try { Thread.sleep(3000); } catch (InterruptedException e) { return "bad"; }
        }
        return "timeout";
    }

    // ------------------------------------------------------------------ bbTinyThumb
    /** صورة مصغّرة (40px، JPEG .5) كـ data URL؛ "" عند الفشل. */
    static String tinyThumb(File f, boolean video) {
        try {
            Bitmap src = video ? frame(f, 100000L) : decodeSmall(f, 128);
            if (src == null) return "";
            float k = Math.min(1f, 40f / Math.max(src.getWidth(), src.getHeight()));
            Bitmap b = Bitmap.createScaledBitmap(src, Math.max(1, Math.round(src.getWidth() * k)), Math.max(1, Math.round(src.getHeight() * k)), true);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.JPEG, 50, bo);
            return "data:image/jpeg;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable t) { return ""; }
    }

    static Bitmap decodeSmall(File f, int maxSide) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int s = 1;
            while (Math.max(o.outWidth, o.outHeight) / s > maxSide * 2) s *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
        } catch (Throwable t) { return null; }
    }

    static Bitmap frame(File f, long us) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            Bitmap b = r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (b == null) b = r.getFrameAtTime();
            return b;
        } catch (Throwable t) { return null; } finally { try { r.release(); } catch (Throwable ignored) { } }
    }

    // ------------------------------------------------------------------ صورة بنسبة أبعادها (img عرضها 100% وارتفاعها تلقائي)
    static final class AspectImageView extends ImageView {
        private final int loadH, minH, maxH;
        AspectImageView(Context c, int loadH, int minH, int maxH) {
            super(c);
            this.loadH = loadH; this.minH = minH; this.maxH = maxH;
            setScaleType(ScaleType.CENTER_CROP);
        }
        @Override protected void onMeasure(int ws, int hs) {
            int w = MeasureSpec.getSize(ws);
            Drawable d = getDrawable();
            int h;
            if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null && d.getIntrinsicWidth() > 0 && d.getIntrinsicHeight() > 0) {
                h = Math.round(w * (float) d.getIntrinsicHeight() / d.getIntrinsicWidth());
                if (maxH > 0) h = Math.min(h, maxH);
                h = Math.max(h, minH);
            } else h = loadH;
            setMeasuredDimension(w, h);
        }
    }

    // ------------------------------------------------------------------ الحلقة الدوّارة (.vp-ring)
    static final class SpinRing extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rf = new RectF();
        private final float dens;
        SpinRing(Context c) { super(c); dens = c.getResources().getDisplayMetrics().density; p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND); }
        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            animate().cancel();
            android.view.animation.RotateAnimation a = new android.view.animation.RotateAnimation(0, 360, android.view.animation.Animation.RELATIVE_TO_SELF, .5f, android.view.animation.Animation.RELATIVE_TO_SELF, .5f);
            a.setDuration(1000); a.setRepeatCount(android.view.animation.Animation.INFINITE); a.setInterpolator(new android.view.animation.LinearInterpolator());
            startAnimation(a);
        }
        @Override protected void onDetachedFromWindow() { clearAnimation(); super.onDetachedFromWindow(); }
        @Override protected void onDraw(Canvas cv) {
            float s = getWidth() / 76f, sw = 3f * s;
            p.setStrokeWidth(sw);
            rf.set(3 * s, 3 * s, 73 * s, 73 * s);
            p.setColor(0x47FFFFFF);
            cv.drawOval(rf, p);
            p.setColor(0xFFFFFFFF);
            cv.drawArc(rf, -90f, 90f, false, p);
        }
    }

    // ------------------------------------------------------------------ بطاقة «قيد الإرسال»
    static final class Pending {
        FrameLayout view;
        TextView pct;
        ImageView img;
        View cancelBtn;
    }

    private static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    static float bs(Context c) {
        android.util.DisplayMetrics m = c.getResources().getDisplayMetrics();
        float w = Math.min(m.widthPixels, m.heightPixels) / m.density;
        return Math.max(0.9f, Math.min(1.25f, (float) Math.sqrt(w / 360f)));
    }

    static int mediaWidth(Context c) {
        return Math.min((int) (c.getResources().getDisplayMetrics().widthPixels * 0.72f), dp(c, 280));
    }

    /** كبسولة الوقت والعلامة فوق الوسائط: أسفل اليسار 7/8dp، خلفية rgba(0,0,0,.28)، 10.5sp. */
    static LinearLayout timeChip(Activity a, boolean mine, String time, int state) {
        float bs = bs(a);
        LinearLayout t = new LinearLayout(a);
        t.setOrientation(LinearLayout.HORIZONTAL);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        TextView tt = new TextView(a);
        tt.setText(time);
        tt.setTextColor(0xFFFFFFFF);
        tt.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 10.5f * a.getResources().getDisplayMetrics().density * bs);
        tt.setIncludeFontPadding(false);
        tt.setTextDirection(View.TEXT_DIRECTION_LTR);
        t.addView(tt);
        if (mine) {
            ImageView tick = new ImageView(a);
            tick.setTag("tick");
            tick.setImageDrawable(new MsgViews.TickDrawable(a, state, 0, Tk.tickColor(state)));
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
            tl.setMarginStart(dp(a, 3));
            t.addView(tick, tl);
        }
        t.setPadding(dp(a, 7), dp(a, 2), dp(a, 7), dp(a, 2));
        GradientDrawable tg = new GradientDrawable();
        tg.setColor(0x47000000); tg.setCornerRadius(dp(a, 10));
        t.setBackground(tg);
        return t;
    }

    /** بطاقة الإرسال: معاينة مموّهة + حلقة وشعار B + «الحجم | المرفوع» + زر إلغاء + الوقت وعلامة الانتظار. */
    static Pending pending(Activity a, String time, String pctText, boolean showPct) {
        float dens = a.getResources().getDisplayMetrics().density, bs = bs(a);
        Pending p = new Pending();
        FrameLayout f = new FrameLayout(a);
        f.setBackgroundColor(0x29808080);   // rgba(128,128,128,.16)
        f.setClipToOutline(true);
        f.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(v.getContext(), 16)); }
        });
        p.img = new AspectImageView(a, dp(a, 150), Math.round(120 * dens * bs), dp(a, 360));
        f.addView(p.img, new FrameLayout.LayoutParams(-1, -2));
        FrameLayout cover = new FrameLayout(a);
        cover.setBackgroundColor(0x0FFFFFFF);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        FrameLayout logo = new FrameLayout(a);
        int ls = Math.round(76 * dens * bs);
        logo.addView(new SpinRing(a), new FrameLayout.LayoutParams(-1, -1));
        TextView b = new TextView(a);
        b.setText("B"); b.setTextColor(0xFFFFFFFF); b.setTypeface(null, android.graphics.Typeface.BOLD);
        b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 30 * dens * bs);
        b.setIncludeFontPadding(false);
        b.setPadding(dp(a, 2), 0, 0, 0);
        logo.addView(b, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        col.addView(logo, new LinearLayout.LayoutParams(ls, ls));
        p.pct = new TextView(a);
        p.pct.setText(pctText == null ? "" : pctText);
        p.pct.setTextColor(0xFFFFFFFF);
        p.pct.setTypeface(null, android.graphics.Typeface.BOLD);
        p.pct.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 12 * dens * bs);
        p.pct.setTextDirection(View.TEXT_DIRECTION_LTR);
        p.pct.setGravity(Gravity.CENTER);
        p.pct.setSingleLine(true);
        p.pct.setShadowLayer(dp(a, 3), 0, dp(a, 1), 0x80000000);
        p.pct.setVisibility(showPct ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-2, -2);
        pl.topMargin = dp(a, 8);
        col.addView(p.pct, pl);
        cover.addView(col, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        f.addView(cover, new FrameLayout.LayoutParams(-1, -1));
        // زر الإلغاء أعلى اليمين
        FrameLayout cb = new FrameLayout(a);
        GradientDrawable cg = new GradientDrawable();
        cg.setShape(GradientDrawable.OVAL); cg.setColor(0x6B000000);
        cb.setBackground(cg);
        ImageView x = new ImageView(a);
        x.setImageResource(R.drawable.ic_mc_cancel);
        x.setColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
        cb.addView(x, new FrameLayout.LayoutParams(dp(a, 14), dp(a, 14), Gravity.CENTER));
        cb.setContentDescription("إلغاء الإرسال");
        int cs = Math.round(26 * dens * bs);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(cs, cs, Gravity.TOP | Gravity.RIGHT);
        cl.topMargin = dp(a, 8); cl.rightMargin = dp(a, 8);
        f.addView(cb, cl);
        p.cancelBtn = cb;
        LinearLayout chip = timeChip(a, true, time, MsgViews.WAIT);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.LEFT);
        tl.bottomMargin = dp(a, 7); tl.leftMargin = dp(a, 8);
        f.addView(chip, tl);
        p.view = f;
        return p;
    }

    static String fmtVP(long n) {
        return n < 1024 ? n + " B" : n < 1048576 ? Math.round(n / 1024.0) + " KB" : String.format(Locale.US, "%.1f MB", n / 1048576.0);
    }
}
