package com.babylon.chat.iq;

import android.app.*;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.*;
import android.content.SharedPreferences;

public class PartSettingsActivity extends Activity {
    private SharedPreferences prefs;
    private View main, privacy, notifications, chat;
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    private <T extends View> T v(int id){return (T)findViewById(id);}
    private void show(View page){main.setVisibility(page==main?View.VISIBLE:View.GONE);privacy.setVisibility(page==privacy?View.VISIBLE:View.GONE);notifications.setVisibility(page==notifications?View.VISIBLE:View.GONE);chat.setVisibility(page==chat?View.VISIBLE:View.GONE);}
    private void togglePair(int onId,int offId,boolean on){View a=v(onId),b=v(offId);a.setAlpha(on?1f:.48f);b.setAlpha(on?.48f:1f);a.setSelected(on);b.setSelected(!on);}
    @Override protected void onCreate(Bundle state){super.onCreate(state);setContentView(R.layout.part_settings);prefs=getSharedPreferences("babylon_settings",MODE_PRIVATE);initializeLogic();}
    private void initializeLogic(){
        Window w=getWindow();w.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS|WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);w.setStatusBarColor(0xFFF1F4F9);w.setNavigationBarColor(0xFFEEF2F9);if(android.os.Build.VERSION.SDK_INT>=29){w.setNavigationBarContrastEnforced(false);w.setStatusBarContrastEnforced(false);}
        main=v(R.id.settingsView);privacy=v(R.id.privacySettingsView);notifications=v(R.id.notificationSettingsView);chat=v(R.id.chatSettingsView);show(main);
        v(R.id.itemPrivacy).setOnClickListener(x->show(privacy));v(R.id.itemNotif).setOnClickListener(x->show(notifications));v(R.id.itemChatSettings).setOnClickListener(x->show(chat));
        v(R.id.privacyBack).setOnClickListener(x->show(main));v(R.id.notifBack).setOnClickListener(x->show(main));v(R.id.chatSetBack).setOnClickListener(x->show(main));
        boolean last=prefs.getBoolean("last_seen",true), avatar=prefs.getBoolean("avatar_visible",true), push=prefs.getBoolean("push",true), sound=prefs.getBoolean("sound",true);
        togglePair(R.id.segLastSeen_show,R.id.segLastSeen_hide,last);togglePair(R.id.segAvatar_show,R.id.segAvatar_hide,avatar);togglePair(R.id.pushOnBtn,R.id.pushOffBtn,push);togglePair(R.id.soundOnBtn,R.id.soundOffBtn,sound);
        v(R.id.segLastSeen_show).setOnClickListener(x->saveToggle("last_seen",true,R.id.segLastSeen_show,R.id.segLastSeen_hide));v(R.id.segLastSeen_hide).setOnClickListener(x->saveToggle("last_seen",false,R.id.segLastSeen_show,R.id.segLastSeen_hide));
        v(R.id.segAvatar_show).setOnClickListener(x->saveToggle("avatar_visible",true,R.id.segAvatar_show,R.id.segAvatar_hide));v(R.id.segAvatar_hide).setOnClickListener(x->saveToggle("avatar_visible",false,R.id.segAvatar_show,R.id.segAvatar_hide));
        v(R.id.pushOnBtn).setOnClickListener(x->saveToggle("push",true,R.id.pushOnBtn,R.id.pushOffBtn));v(R.id.pushOffBtn).setOnClickListener(x->saveToggle("push",false,R.id.pushOnBtn,R.id.pushOffBtn));
        v(R.id.soundOnBtn).setOnClickListener(x->saveToggle("sound",true,R.id.soundOnBtn,R.id.soundOffBtn));v(R.id.soundOffBtn).setOnClickListener(x->saveToggle("sound",false,R.id.soundOnBtn,R.id.soundOffBtn));
        v(R.id.itemShare).setOnClickListener(x->{Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/plain");i.putExtra(Intent.EXTRA_TEXT,"جرّب Babylon Chat");startActivity(Intent.createChooser(i,"مشاركة التطبيق"));});
        v(R.id.itemLogout).setOnClickListener(x->confirm("تسجيل الخروج؟","لن يتم حذف بياناتك المحلية.","خروج"));
        v(R.id.itemDelete).setOnClickListener(x->confirm("حذف الحساب؟","هذا الإجراء يحتاج ربطاً بخادم الحسابات ولا يمكن تنفيذه محلياً.","متابعة"));
        v(R.id.itemChangePass).setOnClickListener(x->toast("اربط تغيير كلمة المرور بخدمة المصادقة"));v(R.id.itemChangeEmail).setOnClickListener(x->toast("اربط تغيير البريد الإلكتروني بخدمة الحساب"));v(R.id.itemBlocked).setOnClickListener(x->toast("لا توجد قائمة حظر محلية محفوظة"));
        v(R.id.itemClearCache).setOnClickListener(x->new AlertDialog.Builder(this).setMessage("مسح البيانات المؤقتة للتطبيق؟").setNegativeButton("إلغاء",null).setPositiveButton("مسح",(d,k)->toast("تم طلب مسح الذاكرة المؤقتة")).show());
        v(R.id.itemWipe).setOnClickListener(x->confirm("مسح جميع البيانات؟","سيؤدي ذلك إلى حذف التفضيلات المحلية لهذا الجزء.","مسح"));
        v(R.id.itemAbout).setOnClickListener(x->new AlertDialog.Builder(this).setTitle("حول Babylon Chat").setMessage("Babylon Chat — واجهة محادثات وملف شخصي وإعدادات.").setPositiveButton("حسناً",null).show());
        v(R.id.settingsThemeToggle).setOnClickListener(x->{boolean dark=prefs.getBoolean("dark",false);prefs.edit().putBoolean("dark",!dark).apply();toast(!dark?"تم تفعيل تفضيل الوضع الداكن":"تم تفعيل تفضيل الوضع الفاتح");});
        SeekBar font=v(R.id.fontRange),radius=v(R.id.radiusRange);font.setProgress(prefs.getInt("font",15)-10);radius.setProgress(prefs.getInt("radius",16));
        font.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int p,boolean u){((TextView)v(R.id.fontVal)).setText(String.valueOf(p+10));if(u)prefs.edit().putInt("font",p+10).apply();}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}});
        radius.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int p,boolean u){((TextView)v(R.id.radiusVal)).setText(String.valueOf(p));if(u)prefs.edit().putInt("radius",p).apply();}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}});
    }
    private void saveToggle(String key,boolean value,int a,int b){prefs.edit().putBoolean(key,value).apply();togglePair(a,b,value);}
    private void confirm(String title,String message,String positive){new AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton("إلغاء",null).setPositiveButton(positive,(d,k)->toast("هذه العملية تحتاج ربطاً بخادم التطبيق")).show();}
    @Override public void onBackPressed(){if(privacy.getVisibility()==View.VISIBLE||notifications.getVisibility()==View.VISIBLE||chat.getVisibility()==View.VISIBLE)show(main);else super.onBackPressed();}
}
