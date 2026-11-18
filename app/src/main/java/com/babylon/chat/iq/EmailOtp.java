package com.babylon.chat.iq;

import com.google.firebase.functions.FirebaseFunctions;
import com.google.firebase.functions.FirebaseFunctionsException;

import java.util.HashMap;
import java.util.Map;

/**
 * إرسال/فحص رمز التحقق (6 أرقام) عبر Cloud Functions (انظر مجلد functions/ في جذر المشروع).
 * الرمز يُولَّد ويُخزَّن ويُفحص على الخادم فقط، فلا يمكن للتطبيق قراءته أو تجاوزه.
 */
final class EmailOtp {
    private EmailOtp() { }

    interface Callback { void done(boolean ok, String message); }

    static void send(final Callback cb) {
        FirebaseFunctions.getInstance().getHttpsCallable("sendEmailOtp").call()
                .addOnSuccessListener(r -> cb.done(true, null))
                .addOnFailureListener(e -> cb.done(false, error(e, "تعذّر إرسال الرمز، حاول مرة أخرى")));
    }

    static void verify(String code, final Callback cb) {
        Map<String, Object> data = new HashMap<>();
        data.put("code", code);
        FirebaseFunctions.getInstance().getHttpsCallable("verifyEmailOtp").call(data)
                .addOnSuccessListener(r -> cb.done(true, null))
                .addOnFailureListener(e -> cb.done(false, error(e, "تعذّر التحقق، حاول مرة أخرى")));
    }

    private static String error(Exception e, String fallback) {
        if (e instanceof FirebaseFunctionsException) {
            switch (((FirebaseFunctionsException) e).getCode()) {
                case INVALID_ARGUMENT:    return "الرمز غير صحيح";
                case DEADLINE_EXCEEDED:   return "انتهت صلاحية الرمز، اطلب رمزاً جديداً";
                case RESOURCE_EXHAUSTED:  return "محاولات كثيرة، انتظر قليلاً ثم أعد المحاولة";
                case UNAUTHENTICATED:     return "سجّل الدخول أولاً";
                case UNAVAILABLE:         return "تعذّر الاتصال، تحقق من الإنترنت";
                default: break;
            }
        }
        return fallback;
    }
}
