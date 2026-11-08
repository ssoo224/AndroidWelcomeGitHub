package com.babylon.chat.iq;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
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
    }

    private Controller controller;
    private float downY;
    private float tapX, tapY;
    private boolean tapOk;
    private boolean pulling;
    private final int slop;

    public PullScrollView(Context c) { this(c, null); }

    public PullScrollView(Context c, AttributeSet a) {
        super(c, a);
        setVerticalScrollBarEnabled(false);
        setHorizontalScrollBarEnabled(false);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        // الأزرار/الصورة تصعد خارج حدود أبيها أثناء التكبير ولا يجب أن تُقصّ
        setClipChildren(false);
        setClipToPadding(false);
    }

    public void setController(Controller c) { controller = c; }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (controller != null) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY = ev.getY();
                    pulling = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dy = ev.getY() - downY;
                    if (!pulling && Math.abs(dy) > Math.min(slop, 6)
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
            if (pulling) return true;
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
        if (controller != null && !pulling) {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downY = ev.getY();
            } else if (ev.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float dy = ev.getY() - downY;
                if (Math.abs(dy) > Math.min(slop, 6) && controller.canStart(dy, getScrollY())) {
                    pulling = true;
                    downY = ev.getY();
                    controller.onStart();
                    return true;
                }
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
