package com.babylon.chat.iq;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * أيقونة إشعار متحركة مرسومة بالكود: تُختار حسب نوع العملية (تثبيت، إلغاء تثبيت، حذف، نسخ، حفظ، إرسال، كتم، حظر، تعديل)،
 * وللباقي علامة ✓ تُرسم تدريجياً، أو ✕ مع اهتزاز، أو «i». تعمل مرة واحدة عند ظهور الإشعار.
 */
final class ToastIcon extends View {
    static final int CHECK = 0, ERROR = 1, INFO = 2, PIN = 3, UNPIN = 4, DELETE = 5, COPY = 6, DOWNLOAD = 7,
            SEND = 8, BELL = 9, BELL_OFF = 10, BLOCK = 11, EDIT = 12;

    private final int motif;
    private final int color;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path part = new Path();
    private final PathMeasure pm = new PathMeasure();
    private final RectF rf = new RectF();
    private float t = 0f;
    private ValueAnimator anim;

    ToastIcon(Context c, int motif, int color) {
        super(c);
        this.motif = motif;
        this.color = color;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
    }

    /** يختار الحركة من نص الإشعار ونوعه. */
    static int motifFor(String text, String kind) {
        String m = text.replaceAll("[ً-ْٰ]", "");
        if (kind.equals("error")) return ERROR;
        if (m.contains("إلغاء تثبيت") || m.contains("الغاء تثبيت")) return UNPIN;
        if (m.contains("تثبيت")) return PIN;
        if (m.contains("كتم")) return m.contains("إلغاء كتم") || m.contains("الغاء كتم") ? BELL : BELL_OFF;
        if (kind.equals("danger") || m.contains("حظر") || m.contains("محظور")) return BLOCK;
        if (m.contains("حذف") || m.contains("حُذف") || m.contains("مسح")) return DELETE;
        if (kind.equals("success")) {
            if (m.contains("نسخ")) return COPY;
            if (m.contains("حفظ الصورة") || m.contains("حفظ الملف") || m.contains("تنزيل")) return DOWNLOAD;
            if (m.contains("إرسال") || m.contains("توجيه") || m.contains("نشر")) return SEND;
            if (m.contains("تعديل") || m.contains("تحديث") || m.contains("تغيير") || m.contains("اعتماد")) return EDIT;
            return CHECK;
        }
        return INFO;
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(1000);
        anim.setStartDelay(120);   // بعد ظهور البطاقة
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> { t = (float) a.getAnimatedValue(); invalidate(); });
        anim.start();
    }

    @Override protected void onDetachedFromWindow() {
        if (anim != null) { anim.cancel(); anim = null; }
        super.onDetachedFromWindow();
    }

    // ------------------------------------------------------------------ دوال مساعدة
    private static float c01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }
    private static float seg(float t, float a, float b) { return c01((t - a) / (b - a)); }
    private static float easeOut(float x) { float k = 1f - x; return 1f - k * k * k; }
    private static float backOut(float x) {
        float c1 = 1.70158f, c3 = c1 + 1f, k = x - 1f;
        return 1f + c3 * k * k * k + c1 * k * k;
    }
    private static float bounceOut(float x) {
        float n1 = 7.5625f, d1 = 2.75f;
        if (x < 1f / d1) return n1 * x * x;
        if (x < 2f / d1) { x -= 1.5f / d1; return n1 * x * x + 0.75f; }
        if (x < 2.5f / d1) { x -= 2.25f / d1; return n1 * x * x + 0.9375f; }
        x -= 2.625f / d1; return n1 * x * x + 0.984375f;
    }

    /** يرسم الجزء الأول (frac) من المسار: تأثير «الرسم بالقلم». */
    private void drawOn(Canvas c, Path src, float frac) {
        if (frac <= 0f) return;
        if (frac >= 1f) { c.drawPath(src, p); return; }
        pm.setPath(src, false);
        float len = pm.getLength();
        part.reset();
        pm.getSegment(0f, len * frac, part, true);
        part.rLineTo(0f, 0f);
        c.drawPath(part, p);
    }

    private void setA(float a) { p.setColor(color); p.setAlpha((int) (255f * c01(a))); }

    // ------------------------------------------------------------------ الرسم
    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float s = Math.min(w, h) * 0.62f / 24f;   // مساحة الرسم 24×24 تشغل 62% من الدائرة
        c.save();
        c.translate(w / 2f - 12f * s, h / 2f - 12f * s);
        c.scale(s, s);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.2f);
        p.setColor(color);
        switch (motif) {
            case CHECK: drawCheck(c); break;
            case ERROR: drawError(c); break;
            case PIN: drawPinAnim(c); break;
            case UNPIN: drawUnpinAnim(c); break;
            case DELETE: drawDelete(c); break;
            case COPY: drawCopy(c); break;
            case DOWNLOAD: drawDownload(c); break;
            case SEND: drawSend(c); break;
            case BELL: drawBell(c, false); break;
            case BELL_OFF: drawBell(c, true); break;
            case BLOCK: drawBlock(c); break;
            case EDIT: drawEdit(c); break;
            default: drawInfo(c); break;
        }
        c.restore();
    }

    private void drawCheck(Canvas c) {
        float sc = 0.75f + 0.25f * backOut(seg(t, 0f, 0.5f));
        c.save(); c.scale(sc, sc, 12f, 12f);
        path.reset(); path.moveTo(5f, 12.5f); path.lineTo(9.5f, 17f); path.lineTo(19f, 7.5f);
        drawOn(c, path, easeOut(seg(t, 0.05f, 0.55f)));
        c.restore();
    }

    private void drawError(Canvas c) {
        float k = seg(t, 0.35f, 0.8f);
        float shake = (float) Math.sin(k * Math.PI * 4) * 1.3f * (1f - k);
        c.save(); c.translate(shake, 0f);
        path.reset(); path.moveTo(7f, 7f); path.lineTo(17f, 17f);
        drawOn(c, path, easeOut(seg(t, 0f, 0.3f)));
        path.reset(); path.moveTo(17f, 7f); path.lineTo(7f, 17f);
        drawOn(c, path, easeOut(seg(t, 0.2f, 0.5f)));
        c.restore();
    }

    private void drawInfo(Canvas c) {
        float pop = backOut(seg(t, 0f, 0.45f));
        c.save(); c.scale(pop, pop, 12f, 12f);
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(12f, 7.2f, 1.5f, p);
        p.setStyle(Paint.Style.STROKE);
        path.reset(); path.moveTo(12f, 11f); path.lineTo(12f, 17.5f);
        drawOn(c, path, easeOut(seg(t, 0.2f, 0.6f)));
        c.restore();
    }

    private void drawPinShape(Canvas c) {
        path.reset(); path.moveTo(8.8f, 3.5f); path.lineTo(15.2f, 3.5f);
        c.drawPath(path, p);
        path.reset();
        path.moveTo(10f, 3.5f); path.lineTo(10f, 9f); path.lineTo(7.5f, 13f);
        path.lineTo(16.5f, 13f); path.lineTo(14f, 9f); path.lineTo(14f, 3.5f);
        c.drawPath(path, p);
        path.reset(); path.moveTo(12f, 13f); path.lineTo(12f, 20.5f);
        c.drawPath(path, p);
    }

    /** تثبيت: الدبوس يسقط من الأعلى ويرتدّ ثم تنتشر موجة صغيرة عند رأسه. */
    private void drawPinAnim(Canvas c) {
        float d = seg(t, 0f, 0.55f);
        float y = -10f * (1f - bounceOut(d));
        float tilt = 25f * (1f - easeOut(seg(t, 0f, 0.5f)));
        setA(d * 3f);
        c.save(); c.translate(0f, y); c.rotate(tilt, 12f, 20.5f);
        drawPinShape(c);
        c.restore();
        float r = seg(t, 0.5f, 1f);
        if (r > 0f) {
            setA((1f - r) * 0.8f);
            p.setStrokeWidth(1.4f);
            float ra = 1f + 6f * r;
            rf.set(12f - ra, 20.5f - ra * 0.35f, 12f + ra, 20.5f + ra * 0.35f);
            c.drawOval(rf, p);
        }
        p.setColor(color); p.setStrokeWidth(2.2f);
    }

    /** إلغاء التثبيت: الدبوس يظهر ثم يرتفع ويميل وتُرسم عليه شرطة مائلة. */
    private void drawUnpinAnim(Canvas c) {
        float a = seg(t, 0f, 0.3f);
        float sc = 0.8f + 0.2f * backOut(a);
        float lift = easeOut(seg(t, 0.3f, 0.65f));
        setA(a * 2f);
        c.save();
        c.scale(sc, sc, 12f, 12f);
        c.translate(0f, -2.5f * lift);
        c.rotate(-12f * lift, 12f, 20.5f);
        drawPinShape(c);
        c.restore();
        p.setColor(color);
        path.reset(); path.moveTo(4.5f, 4.5f); path.lineTo(19.5f, 19.5f);
        drawOn(c, path, easeOut(seg(t, 0.35f, 0.8f)));
    }

    private void drawDelete(Canvas c) {
        float pop = 0.8f + 0.2f * backOut(seg(t, 0f, 0.3f));
        c.save(); c.scale(pop, pop, 12f, 12f);
        path.reset();
        path.moveTo(7f, 9f); path.lineTo(7.8f, 19f); path.quadTo(7.9f, 20.3f, 9.2f, 20.3f);
        path.lineTo(14.8f, 20.3f); path.quadTo(16.1f, 20.3f, 16.2f, 19f); path.lineTo(17f, 9f);
        c.drawPath(path, p);
        c.drawLine(10.3f, 11.5f, 10.5f, 17.5f, p);
        c.drawLine(13.7f, 11.5f, 13.5f, 17.5f, p);
        float open = easeOut(seg(t, 0.15f, 0.4f)) - easeOut(seg(t, 0.65f, 0.95f));   // 0 → 1 → 0
        c.save(); c.translate(0f, -1.2f * open); c.rotate(-20f * open, 5.5f, 6.5f);
        c.drawLine(5f, 6.5f, 19f, 6.5f, p);
        path.reset(); path.moveTo(9.7f, 6.5f); path.lineTo(9.7f, 4.8f); path.lineTo(14.3f, 4.8f); path.lineTo(14.3f, 6.5f);
        c.drawPath(path, p);
        c.restore();
        c.restore();
    }

    private void drawCopy(Canvas c) {
        float f = easeOut(seg(t, 0f, 0.45f));
        float off = 6f * (1f - f);
        setA(seg(t, 0f, 0.3f));
        path.reset();
        path.moveTo(9f, 14f); path.lineTo(5.5f, 14f); path.quadTo(4f, 14f, 4f, 12.5f); path.lineTo(4f, 5.5f);
        path.quadTo(4f, 4f, 5.5f, 4f); path.lineTo(12.5f, 4f); path.quadTo(14f, 4f, 14f, 5.5f); path.lineTo(14f, 9f);
        c.drawPath(path, p);
        c.save(); c.translate(off, off);
        rf.set(9.5f, 9.5f, 20f, 20f);
        c.drawRoundRect(rf, 2f, 2f, p);
        c.restore();
        p.setColor(color);
        path.reset(); path.moveTo(12.3f, 13.8f); path.lineTo(17.2f, 13.8f);
        drawOn(c, path, easeOut(seg(t, 0.45f, 0.7f)));
        path.reset(); path.moveTo(12.3f, 16.6f); path.lineTo(15.4f, 16.6f);
        drawOn(c, path, easeOut(seg(t, 0.55f, 0.8f)));
    }

    private void drawDownload(Canvas c) {
        setA(seg(t, 0f, 0.25f));
        path.reset();
        path.moveTo(5f, 15f); path.lineTo(5f, 18f); path.quadTo(5f, 19.5f, 6.5f, 19.5f);
        path.lineTo(17.5f, 19.5f); path.quadTo(19f, 19.5f, 19f, 18f); path.lineTo(19f, 15f);
        c.drawPath(path, p);
        float d = seg(t, 0.05f, 0.6f);
        setA(d * 4f);
        c.save(); c.translate(0f, -7f * (1f - bounceOut(d)));
        c.drawLine(12f, 4f, 12f, 14f, p);
        path.reset(); path.moveTo(8f, 10.5f); path.lineTo(12f, 14.5f); path.lineTo(16f, 10.5f);
        c.drawPath(path, p);
        c.restore();
        p.setColor(color);
    }

    private void drawSend(Canvas c) {
        float f = easeOut(seg(t, 0f, 0.6f));
        float n = (float) Math.sin(seg(t, 0.6f, 1f) * Math.PI) * 1.2f;   // دفعة خفيفة بعد الوصول
        setA(f * 2.5f);
        c.save();
        c.translate(-9f * (1f - f) + n, 9f * (1f - f) - n);
        path.reset();
        path.moveTo(3.5f, 11.5f); path.lineTo(20.5f, 4f); path.lineTo(15f, 20.5f); path.lineTo(11.5f, 13f); path.close();
        c.drawPath(path, p);
        c.drawLine(20.5f, 4f, 11.5f, 13f, p);
        c.restore();
        p.setColor(color);
    }

    private void drawBell(Canvas c, boolean off) {
        float k = seg(t, 0.1f, 1f);
        float swing = (float) Math.sin(k * Math.PI * 5) * 20f * (1f - k);
        c.save(); c.rotate(swing, 12f, 4.5f);
        path.reset();
        path.moveTo(6f, 16.5f); path.lineTo(6f, 11f);
        path.cubicTo(6f, 7.5f, 8.5f, 6f, 12f, 6f);
        path.cubicTo(15.5f, 6f, 18f, 7.5f, 18f, 11f);
        path.lineTo(18f, 16.5f); path.lineTo(19.5f, 18f); path.lineTo(4.5f, 18f); path.close();
        c.drawPath(path, p);
        c.drawLine(12f, 3.8f, 12f, 6f, p);
        path.reset(); path.moveTo(10.2f, 20.2f); path.quadTo(12f, 22f, 13.8f, 20.2f);
        c.drawPath(path, p);
        c.restore();
        if (off) {
            path.reset(); path.moveTo(4.5f, 4.5f); path.lineTo(19.5f, 19.5f);
            drawOn(c, path, easeOut(seg(t, 0.45f, 0.8f)));
        }
    }

    private void drawBlock(Canvas c) {
        float a = easeOut(seg(t, 0f, 0.5f));
        rf.set(4f, 4f, 20f, 20f);
        if (a > 0f) c.drawArc(rf, -90f, 360f * a, false, p);
        path.reset(); path.moveTo(6.3f, 6.3f); path.lineTo(17.7f, 17.7f);
        drawOn(c, path, easeOut(seg(t, 0.4f, 0.8f)));
    }

    private void drawEdit(Canvas c) {
        float k = seg(t, 0.1f, 0.9f);
        float wig = (float) Math.sin(k * Math.PI * 3) * 5f * (1f - k);
        float pop = 0.8f + 0.2f * backOut(seg(t, 0f, 0.35f));
        c.save(); c.scale(pop, pop, 12f, 12f); c.rotate(wig, 4f, 20f);
        path.reset();
        path.moveTo(4f, 20f); path.lineTo(4.8f, 16f); path.lineTo(16.5f, 4.3f);
        path.quadTo(17.5f, 3.3f, 18.7f, 4.3f); path.lineTo(19.7f, 5.3f);
        path.quadTo(20.7f, 6.5f, 19.7f, 7.5f); path.lineTo(8f, 19.2f); path.close();
        c.drawPath(path, p);
        c.drawLine(14.5f, 6.3f, 17.7f, 9.5f, p);
        c.restore();
        path.reset(); path.moveTo(10f, 21.5f); path.lineTo(20.5f, 21.5f);
        drawOn(c, path, easeOut(seg(t, 0.3f, 0.8f)));
    }
}
