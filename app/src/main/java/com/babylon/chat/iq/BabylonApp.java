package com.babylon.chat.iq;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/** يطبّق خط التطبيق المختار على كل شاشة تُفتح. */
public class BabylonApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        // كاش Firestore على الجهاز بلا حد أقصى (الافتراضي 100MB ويُحذف القديم): الرسائل تبقى محلياً وتُقرأ منه بدل إعادة تنزيلها
        try {
            com.google.firebase.firestore.FirebaseFirestore.getInstance().setFirestoreSettings(
                    new com.google.firebase.firestore.FirebaseFirestoreSettings.Builder()
                            .setPersistenceEnabled(true)
                            .setCacheSizeBytes(com.google.firebase.firestore.FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
                            .build());
        } catch (Throwable ignored) { }
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity a, Bundle b) { }
            @Override public void onActivityStarted(Activity a) { FontManager.install(a); }
            @Override public void onActivityResumed(Activity a) { FontManager.install(a); }
            @Override public void onActivityPaused(Activity a) { }
            @Override public void onActivityStopped(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { }
        });
    }
}
