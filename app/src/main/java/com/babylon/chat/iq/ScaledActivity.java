package com.babylon.chat.iq;

import android.app.Activity;
import android.content.Context;

/**
 * أساس كل شاشة في التطبيق: يُطبّق التحجيم الديناميكي (220dp–711dp، انظر ScreenScale) على كل ما يُبنى داخلها
 * (XML، Views، Canvas، Dialogs، PopupWindows، Toasts).
 * قاعدة: أي Activity جديدة تمتد من ScaledActivity لا من Activity (مهمة gradle checkDynamicScale ترفض البناء غير ذلك).
 */
public class ScaledActivity extends Activity {
    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(ScreenScale.wrap(base)); }
}
