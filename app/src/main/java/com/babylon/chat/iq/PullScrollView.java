package com.babylon.chat.iq;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.widget.ScrollView;

/**
 * ScrollView يدعم سحب الشاشة للأسفل في أعلاها (يُستعمل لتكبير صورة الملف الشخصي مثل تليجرام).
 * المنطق كله في ProfileHero؛ هذا الصف فقط يلتقط اللمس.
 */
public class PullScrollView extends ScrollView {

    public interface Controller {
        /** هل نبدأ جلسة سحب؟ dy موجب = للأسفل. */
        boolean canStart(float dy, int scrollY);
        void onStart();
        /** totalDy = المسافة منذ بداية الجلسة. */
        void onPull(float totalDy);
        void onRelease();
        /** نقرة (بدون سحب) على منطقة فارغة من الصفحة؛ الإحداثيات نسبةً إلى الـ ScrollView. */
        void onTap(float x, float y);
        /** هل التمرير الأفقي بين الصور مفعّل الآن (الصورة مكبّرة وفيها أكثر من صورة)؟ */
        boolean canSwipe();
        /** سحب أفقي جارٍ: dx = الإزاحة منذ بداية اللمسة (سالب = لليسار). */
        void onSwipeMove(float dx);
        /** انتهاء السحب الأفقي: vx = سرعة الإصبع بالبكسل/ثانية. */
        void onSwipeEnd(float dx, float vx);
    }

    private Controller controller;
    private float downY;
    private float tapX, tapY;
    private boolean tapOk;
    private boolean pulling;
    private boolean downOnButton;   // اللمسة بدأت فوق زر/عنصر قابل للنقر
    private float downX;
    private boolean swiping;        // سحب أفقي بين الصور جارٍ
    private android.view.VelocityTracker vt;
    private final int slop;

    public PullScrollView(Context c) { this(c, null); }

    public PullScrollView(Context c, AttributeSet a) {
        super(c, a);
        setVerticalScrollBarEnabled(false);
        setHorizontalScrollBarEnabled(false);
        // يمنع ارتداد/تمدد النظام (خصوصاً MIUI/HyperOS) الذي يُنزل المحتوى ويكشف خلفية بيضاء في الأعلى
        setOverScrollMode(OVER_SCROLL_NEVER);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        // الأزرار/الصورة تصعد خارج حدود أبيها أثناء التكبير ولا يجب أن تُقصّ
        setClipChildren(false);
        setClipToPadding(false);
    }

    /** هل يوجد عنصر قابل للنقر (زر) تحت هذه النقطة؟ الإحداثيات نسبةً إلى الـ ScrollView. */
    private boolean clickableAt(ViewGroup g, float x, float y) {
        for (int i = g.getChildCount() - 1; i >= 0; i--) {
            View c = g.getChildAt(i);
            if (c.getVisibility() != View.VISIBLE) continue;
            float cx = x + g.getScrollX() - c.getLeft() - c.getTranslationX();
            float cy = y + g.getScrollY() - c.getTop() - c.getTranslationY();
            if (cx < 0 || cy < 0 || cx >= c.getWidth() || cy >= c.getHeight()) continue;
            if (c.isClickable()) return true;
            if (c instanceof ViewGroup && clickableAt((ViewGroup) c, cx, cy)) return true;
        }
        return false;
    }

    /** أقل حركة عمودية تبدأ سحب الصورة: فوق الأزرار نسمح برجفة الإصبع حتى لا يُلغى النقر. */
    private float startDist() { return downOnButton ? slop * 2f : Math.min(slop, 6); }

    public void setController(Controller c) { controller = c; }

    /** سحب عمودي (إغلاق/تكبير): عند تفعيل التمرير الأفقي نشترط أن تكون الحركة عمودية بوضوح حتى لا تتعارضا. */
    private boolean verticalStart(float dx, float dy) {
        if (Math.abs(dy) <= startDist()) return false;
        if (controller.canSwipe()) return Math.abs(dy) > slop * 1.5f && Math.abs(dy) > Math.abs(dx);
        return true;
    }

    private boolean horizontalStart(float dx, float dy) {
        return controller.canSwipe() && Math.abs(dx) > slop * 1.5f && Math.abs(dx) > Math.abs(dy) * 1.2f;
    }

    private void track(MotionEvent ev) {
        if (vt == null) vt = android.view.VelocityTracker.obtain();
        vt.addMovement(ev);
    }

    private void endTrack() {
        if (vt != null) { vt.recycle(); vt = null; }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (controller != null) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY = ev.getY();
                    downX = ev.getX();
                    pulling = false;
                    swiping = false;
                    endTrack();
                    track(ev);
                    downOnButton = clickableAt(this, ev.getX(), ev.getY());
                    break;
                case MotionEvent.ACTION_MOVE:
                    track(ev);
                    float dy = ev.getY() - downY;
                    float dx = ev.getX() - downX;
                    if (!pulling && !swiping && horizontalStart(dx, dy)) {
                        swiping = true;
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    } else if (!pulling && !swiping && verticalStart(dx, dy)
                            && controller.canStart(dy, getScrollY())) {
                        pulling = true;
                        downY = ev.getY();
                        controller.onStart();
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    break;
                default:
                    break;
            }
            if (pulling || swiping) return true;
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        // كشف النقر على الفراغ (الصورة المكبّرة تقع خلف المحتوى فلا تستقبل اللمس بنفسها)
        if (controller != null && !pulling) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    tapX = ev.getX(); tapY = ev.getY(); tapOk = true;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(ev.getX() - tapX) > slop || Math.abs(ev.getY() - tapY) > slop) tapOk = false;
                    break;
                case MotionEvent.ACTION_UP:
                    if (tapOk) { tapOk = false; controller.onTap(ev.getX(), ev.getY()); }
                    break;
                case MotionEvent.ACTION_CANCEL:
                    tapOk = false;
                    break;
                default:
                    break;
            }
        }
        // عندما لا يستهلك أي ابن حدث DOWN لا يُستدعى onIntercept لباقي الأحداث، فنلتقط البداية هنا أيضاً
        if (controller != null && !pulling && !swiping) {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downY = ev.getY();
                downX = ev.getX();
                endTrack();
                track(ev);
            } else if (ev.getActionMasked() == MotionEvent.ACTION_MOVE) {
                track(ev);
                float dy = ev.getY() - downY;
                float dx = ev.getX() - downX;
                if (horizontalStart(dx, dy)) {
                    swiping = true;
                    return true;
                }
                if (verticalStart(dx, dy) && controller.canStart(dy, getScrollY())) {
                    pulling = true;
                    downY = ev.getY();
                    controller.onStart();
                    return true;
                }
            }
        }
        if (controller != null && swiping) {
            track(ev);
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    controller.onSwipeMove(ev.getX() - downX);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    float vx = 0f;
                    if (vt != null) { vt.computeCurrentVelocity(1000); vx = vt.getXVelocity(); }
                    swiping = false;
                    endTrack();
                    float dx = ev.getActionMasked() == MotionEvent.ACTION_CANCEL ? 0f : ev.getX() - downX;
                    controller.onSwipeEnd(dx, vx);
                    return true;
                }
                default:
                    return true;
            }
        }
        if (controller != null && pulling) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    controller.onPull(ev.getY() - downY);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    pulling = false;
                    controller.onRelease();
                    return true;
                default:
                    return true;
            }
        }
        return super.onTouchEvent(ev);
    }
}
