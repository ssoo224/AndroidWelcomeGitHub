package com.babylon.chat.iq;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.google.firebase.auth.FirebaseAuth;

/** بعد إعادة تشغيل الهاتف أو تحديث التطبيق: يعيد خدمة الإشعارات إن كان هناك حساب مسجّل. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        try {
            if (FirebaseAuth.getInstance().getCurrentUser() != null) MessageListenerService.start(c);
        } catch (Throwable ignored) { }
    }
}
