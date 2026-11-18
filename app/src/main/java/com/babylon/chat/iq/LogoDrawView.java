package com.babylon.chat.iq;

import android.animation.ArgbEvaluator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * لوكو السبلاش برسم خطي (Line Drawing):
 * 1) يُرسم مسار الشعار بخط ذهبي رفيع يتتبّع محيطه (بلا مربع/خطوط المخطط الهندسي)
 * 2) عند التلوين يتحوّل الخط إلى الأبيض ويمتلئ الشعار بنعومة فيستقر بشكله الكامل
 * التوقيت تتحكّم به MainActivity عبر setDrawProgress / setFillProgress.
 */
public class LogoDrawView extends View {

    private static final float VW = 902f, VH = 1024f;
    private static final String DATA = "M321.0,6.5L320.0,7.5L317.5,8.0L309.0,12.0L299.5,18.0L292.0,24.0L280.5,35.5L272.0,46.5L264.5,58.5L258.0,73.0L254.0,86.5L254.0,89.5L252.0,99.0L252.0,119.0L254.0,132.5L256.0,138.5L256.5,142.5L264.0,160.5L270.0,170.5L278.0,181.0L289.0,191.5L305.5,203.5L321.5,211.5L340.0,217.0L355.5,219.0L512.0,219.0L520.5,220.0L535.5,224.0L544.5,228.0L553.5,233.5L563.0,241.5L572.0,251.0L579.5,261.0L586.0,273.0L586.0,274.5L587.5,277.0L588.0,280.0L590.0,284.5L592.0,293.5L593.0,303.0L593.0,318.0L591.5,329.5L588.0,342.0L583.5,352.0L577.5,361.5L572.0,368.5L560.5,380.0L550.0,388.0L549.0,388.0L544.0,391.5L534.5,396.0L533.0,396.0L530.5,397.5L527.0,398.0L523.5,399.5L508.0,402.0L477.0,402.5L459.5,406.0L448.0,410.0L435.0,416.5L421.5,426.0L408.0,439.0L400.5,448.5L392.5,461.5L386.5,475.5L382.5,490.5L381.0,503.0L381.0,519.0L382.5,531.5L388.0,550.5L394.5,564.0L400.0,573.0L412.0,588.0L421.0,596.0L433.5,605.0L441.5,609.5L450.0,613.5L451.5,613.5L454.0,615.0L457.0,615.5L461.5,617.5L481.5,621.0L594.5,621.0L595.0,621.5L603.5,621.5L616.5,624.0L628.5,628.0L638.5,633.5L646.5,639.5L658.5,651.0L667.5,662.0L672.0,669.0L677.5,681.5L680.0,689.5L680.0,692.5L682.0,701.0L682.5,718.0L681.5,728.0L678.0,742.0L674.0,751.5L668.0,762.0L658.0,774.5L649.5,782.5L639.5,790.0L624.0,798.0L618.5,800.0L604.0,803.5L593.0,804.0L592.5,804.5L527.5,804.5L515.0,806.5L502.0,810.5L489.5,816.5L480.5,822.5L467.5,834.0L456.0,847.0L448.0,859.0L440.5,876.0L436.5,891.5L435.0,903.5L435.0,922.5L436.0,932.5L438.0,939.5L438.0,942.0L440.0,947.0L440.5,950.5L442.0,953.0L442.0,954.5L446.5,964.5L456.5,980.5L462.0,987.5L474.5,999.0L489.0,1009.0L502.0,1015.5L503.5,1015.5L506.0,1017.0L513.5,1019.5L522.0,1021.5L528.5,1022.0L531.0,1023.5L608.0,1023.5L609.0,1022.5L611.5,1022.0L624.5,1021.5L635.5,1019.5L640.0,1019.5L654.0,1017.0L658.5,1015.5L661.0,1015.5L665.0,1014.0L671.5,1013.0L673.0,1012.0L678.5,1011.0L682.0,1009.5L684.0,1009.5L694.0,1005.5L697.0,1005.0L699.5,1003.5L702.5,1003.0L724.5,993.5L740.5,985.0L754.0,977.0L777.5,960.5L795.0,946.0L813.5,928.5L829.5,911.0L843.5,893.5L859.5,870.0L859.5,869.0L867.5,856.0L873.5,844.0L875.5,838.5L877.0,836.5L877.5,834.0L883.0,821.5L887.5,808.5L888.0,805.0L889.5,802.0L889.5,800.0L891.0,796.5L891.5,792.5L895.0,780.0L899.0,756.0L900.0,741.0L901.5,738.0L901.5,690.5L900.0,686.5L899.5,675.5L897.0,658.0L889.5,625.5L888.0,622.5L885.0,611.5L884.0,610.5L883.5,607.5L875.5,588.0L863.0,563.5L859.5,558.5L859.0,556.5L856.0,552.5L855.0,550.0L853.5,548.5L853.5,547.5L843.5,532.5L830.0,515.0L821.0,504.5L800.5,484.0L786.5,472.0L773.5,462.0L773.5,459.5L775.0,458.0L789.5,427.5L789.5,426.0L791.5,422.5L792.0,419.5L793.0,418.5L793.5,415.5L795.5,411.5L795.5,410.0L797.5,405.5L797.5,403.5L799.5,399.0L799.5,397.0L801.5,392.0L807.0,368.0L807.5,362.0L809.5,352.5L809.5,347.5L811.5,332.5L812.0,300.5L811.5,300.0L811.5,286.5L809.5,271.0L809.5,266.0L807.5,256.5L807.5,253.0L805.5,245.5L805.5,242.5L803.5,236.0L803.5,233.5L793.5,201.0L781.5,173.5L765.0,145.0L753.0,128.0L743.5,116.0L726.5,97.0L698.5,70.5L674.5,52.0L660.0,42.5L646.0,34.5L630.0,26.5L627.5,26.0L624.5,24.0L623.0,24.0L616.0,20.5L613.0,20.0L610.5,18.5L607.5,18.0L603.5,16.0L602.0,16.0L597.5,14.0L595.5,14.0L592.5,12.5L569.0,6.5L545.0,2.5L529.5,1.5L526.5,0.0L345.0,0.0L344.5,1.0L334.5,2.5ZM71.0,6.5L58.0,12.5L46.5,20.0L38.0,27.0L30.5,34.5L21.5,45.0L14.0,55.5L14.0,56.5L10.0,63.0L7.5,70.0L6.5,71.0L2.5,85.0L1.5,94.0L0.0,96.0L0.0,926.0L1.5,928.5L2.5,937.5L6.5,952.0L7.5,953.0L8.0,956.0L14.0,968.5L20.5,978.5L27.5,987.5L40.0,999.0L51.5,1007.0L67.0,1015.0L77.0,1018.0L78.5,1019.0L89.0,1021.5L96.5,1022.0L99.0,1023.5L449.5,1023.5L448.5,1021.5L436.0,1009.0L428.0,999.0L420.0,987.0L419.5,985.0L417.5,982.5L413.5,974.5L411.0,967.5L410.0,966.5L409.5,963.5L408.0,961.0L407.5,957.5L406.0,954.5L402.0,936.0L401.5,926.0L401.0,925.5L401.0,901.0L401.5,900.5L402.0,890.5L405.5,873.5L409.5,862.5L409.5,861.0L416.0,846.5L425.5,831.0L435.0,819.0L449.0,805.0L448.5,804.0L445.0,804.5L221.0,804.5L219.0,802.5L219.5,801.0L219.5,95.5L221.5,85.0L221.5,81.5L224.0,71.0L225.5,68.0L225.5,66.0L227.0,63.0L227.5,60.0L229.5,56.5L229.5,55.0L236.0,41.5L242.0,31.5L250.5,20.0L268.0,1.0L268.0,0.0L94.5,0.0L94.0,1.0L84.0,2.5Z";

    private final Path full = new Path();
    private final List<Path> contours = new ArrayList<>();
    private final List<Float> lengths = new ArrayList<>();
    private final List<Integer> order = new ArrayList<>();   // ترتيب الرسم: الجذع (اليسار) أولاً ثم الانحناءات
    private float totalLen = 0f;
    private final PathMeasure measure = new PathMeasure();
    private final Path seg = new Path();
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArgbEvaluator argb = new ArgbEvaluator();
    private float density;
    private float drawP = 1f;   // 0..1 رسم المحيط
    private float fillP = 1f;   // 0..1 التلوين (الخط يصير أبيض ثم تمتلئ الأشكال)
    private int outlineColor = 0xFFD9B04A;
    private boolean built = false;

    public LogoDrawView(Context c) { super(c); init(); }
    public LogoDrawView(Context c, AttributeSet a) { super(c, a); init(); }
    public LogoDrawView(Context c, AttributeSet a, int s) { super(c, a, s); init(); }

    private void init() {
        density = getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(Color.WHITE);
        outlineColor = AuthTheme.isDark(getContext()) ? 0xFFE6B93A : 0xFFCFA020;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        build(w, h);
    }

    private void build(int w, int h) {
        if (w <= 0 || h <= 0) return;
        float inset = 3f * density;
        float s = Math.min((w - 2 * inset) / VW, (h - 2 * inset) / VH);
        float ox = (w - VW * s) / 2f, oy = (h - VH * s) / 2f;
        full.reset();
        contours.clear();
        lengths.clear();
        Path cur = null;
        Matcher m = Pattern.compile("([MLZ])|(-?\\d+(?:\\.\\d+)?)").matcher(DATA);
        List<Float> nums = new ArrayList<>();
        char cmd = 0;
        while (m.find()) {
            if (m.group(1) != null) {
                if (cmd != 0) cur = flush(cur, cmd, nums, s, ox, oy);
                cmd = m.group(1).charAt(0);
                nums.clear();
                if (cmd == 'Z') { cur = flush(cur, cmd, nums, s, ox, oy); cmd = 0; }
            } else nums.add(Float.parseFloat(m.group(2)));
        }
        if (cmd != 0) flush(cur, cmd, nums, s, ox, oy);
        full.setFillType(Path.FillType.EVEN_ODD);
        for (Path p : contours) lengths.add(new PathMeasure(p, true).getLength());
        // ترتيب الكتابة كحرف B: الجذع الأيسر أولاً ثم الانحناءات من الأعلى للأسفل (الأقرب لليسار يُرسم أولاً)
        order.clear();
        totalLen = 0f;
        final RectF[] bounds = new RectF[contours.size()];
        for (int i = 0; i < contours.size(); i++) {
            bounds[i] = new RectF();
            contours.get(i).computeBounds(bounds[i], true);
            order.add(i);
            totalLen += lengths.get(i);
        }
        java.util.Collections.sort(order, (a, b) -> Float.compare(bounds[a].left, bounds[b].left));
        stroke.setStrokeWidth(1.5f * density);
        built = true;
        invalidate();
    }

    private Path flush(Path cur, char cmd, List<Float> n, float s, float ox, float oy) {
        if (cmd == 'M' && n.size() >= 2) {
            cur = new Path();
            cur.moveTo(ox + n.get(0) * s, oy + n.get(1) * s);
            full.moveTo(ox + n.get(0) * s, oy + n.get(1) * s);
        } else if (cmd == 'L' && cur != null && n.size() >= 2) {
            cur.lineTo(ox + n.get(0) * s, oy + n.get(1) * s);
            full.lineTo(ox + n.get(0) * s, oy + n.get(1) * s);
        } else if (cmd == 'Z' && cur != null) {
            cur.close();
            full.close();
            contours.add(cur);
            cur = null;
        }
        return cur;
    }

    /** تقدّم رسم المحيط 0..1. */
    public void setDrawProgress(float p) { drawP = p; invalidate(); }

    /** تقدّم التلوين 0..1 (تحويل الخط للأبيض + امتلاء الشعار). */
    public void setFillProgress(float f) { fillP = f; invalidate(); }

    /** الشعار كاملاً فوراً (يُستخدم قبل الانتقال للشاشة التالية). */
    public void finishNow() { drawP = 1f; fillP = 1f; invalidate(); }

    /** للتوافق مع الاستدعاءات القديمة؛ لا توجد حركة داخلية الآن. */
    public void cancel() { }

    @Override
    protected void onDraw(Canvas c) {
        if (!built) return;

        // 1) رسم المحيط: كل مسار يُرسم تدريجياً بخط رفيع (ذهبي ثم أبيض عند التلوين)
        if (drawP > 0f) {
            stroke.setColor((Integer) argb.evaluate(fillP, outlineColor, Color.WHITE));
            stroke.setAlpha((int) (255 * (1f - fillP * 0.85f)));
            if (drawP >= 0.999f) {
                for (int i = 0; i < contours.size(); i++) c.drawPath(contours.get(i), stroke);
            } else {
                // قلم واحد متصل: يكمل الجذع ثم ينتقل للانحناءات (بدل رسم القطعتين معاً فيبدو الشكل «13»)
                float remaining = totalLen * drawP;
                for (int k = 0; k < order.size() && remaining > 0f; k++) {
                    int i = order.get(k);
                    float len = lengths.get(i);
                    float take = Math.min(len, remaining);
                    seg.reset();
                    measure.setPath(contours.get(i), true);
                    measure.getSegment(0f, take, seg, true);
                    c.drawPath(seg, stroke);
                    remaining -= take;
                }
            }
        }

        // 2) التلوين: يمتلئ الشعار بالأبيض
        if (fillP > 0f) {
            fill.setAlpha((int) (255 * fillP));
            c.drawPath(full, fill);
        }
    }
}
