package com.babylon.chat.iq;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.LinearLayout;

/**
 * حاوية الشاشة الرئيسية (#homeView في الـ HTML): تلتقط لمسات السحب للأعلى/للأسفل
 * لطيّ وفتح شريط القصص بنفس منطق babylon stories smooth في الـ HTML.
 */
public class StoriesHost extends LinearLayout {
    private StoriesController ctl;

    public StoriesHost(Context c) { super(c); }
    public StoriesHost(Context c, AttributeSet a) { super(c, a); }

    public void setController(StoriesController c) { ctl = c; }

    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        if (ctl != null && ctl.intercept(e)) return true;
        return super.onInterceptTouchEvent(e);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (ctl != null && ctl.touch(e)) return true;
        return super.onTouchEvent(e);
    }
}
