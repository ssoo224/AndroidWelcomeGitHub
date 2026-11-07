package com.babylon.chat.iq;

import android.app.*;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.*;
import android.content.SharedPreferences;

public class PartProfileActivity extends Activity {
    private SharedPreferences prefs;
    private View profile, other, edit;
    private EditText nameInput, usernameInput, bioInput, govInput;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.part_profile);
        prefs = getSharedPreferences("babylon_profile", MODE_PRIVATE);
        initializeLogic();
    }

    private <T extends View> T v(int id) { return (T)findViewById(id); }
    private void show(View target) {
        profile.setVisibility(target == profile ? View.VISIBLE : View.GONE);
        other.setVisibility(target == other ? View.VISIBLE : View.GONE);
        edit.setVisibility(target == edit ? View.VISIBLE : View.GONE);
    }
    private String value(EditText e) { return e.getText() == null ? "" : e.getText().toString().trim(); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private void initializeLogic() {
        Window w=getWindow();
        w.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS|WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.setStatusBarColor(0xFFF1F4F9); w.setNavigationBarColor(0xFFEEF2F9);
        if (android.os.Build.VERSION.SDK_INT>=29) { w.setNavigationBarContrastEnforced(false); w.setStatusBarContrastEnforced(false); }
        profile=v(R.id.profileView); other=v(R.id.otherUserProfileView); edit=v(R.id.editProfileView);
        show(profile);
        nameInput=v(R.id.modalNameInput); usernameInput=v(R.id.modalUsernameInput); bioInput=v(R.id.modalBioInput); govInput=v(R.id.modalGovInput);
        String name=prefs.getString("name", "محمد");
        String username=prefs.getString("username", "babylon_user");
        String bio=prefs.getString("bio", "مرحباً بك في Babylon Chat");
        String gov=prefs.getString("gov", "" );
        ((TextView)v(R.id.pHeaderName)).setText(name); ((TextView)v(R.id.pHeaderUser)).setText("@"+username);
        ((TextView)v(R.id.pBioDisplay)).setText(bio.isEmpty()?"لم تتم إضافة نبذة بعد":bio);
        ((TextView)v(R.id.pGovDisplay)).setText(gov.isEmpty()?"غير محدد":gov);
        nameInput.setText(name); usernameInput.setText(username); bioInput.setText(bio); govInput.setText(gov);
        v(R.id.pEdit).setOnClickListener(x->{ nameInput.setText(((TextView)v(R.id.pHeaderName)).getText()); usernameInput.setText(prefs.getString("username", "babylon_user")); bioInput.setText(prefs.getString("bio", "مرحباً بك في Babylon Chat")); govInput.setText(prefs.getString("gov", "")); show(edit); });
        v(R.id.editBack).setOnClickListener(x->show(profile));
        v(R.id.btnCancelEdit).setOnClickListener(x->show(profile));
        v(R.id.btnSaveProfile).setOnClickListener(x->{
            String n=value(nameInput), u=value(usernameInput).replace("@", "").replace(" ", ""), b=value(bioInput), g=value(govInput);
            if(n.isEmpty()){nameInput.setError("اكتب اسم العرض");return;}
            if(u.isEmpty()){usernameInput.setError("اكتب اسم المستخدم");return;}
            prefs.edit().putString("name",n).putString("username",u).putString("bio",b).putString("gov",g).apply();
            ((TextView)v(R.id.pHeaderName)).setText(n); ((TextView)v(R.id.pHeaderUser)).setText("@"+u);
            ((TextView)v(R.id.pBioDisplay)).setText(b.isEmpty()?"لم تتم إضافة نبذة بعد":b); ((TextView)v(R.id.pGovDisplay)).setText(g.isEmpty()?"غير محدد":g);
            show(profile); toast("تم حفظ بيانات الملف الشخصي");
        });
        v(R.id.pTabStoriesBtn).setOnClickListener(x->{v(R.id.pTabStories).setVisibility(View.VISIBLE);v(R.id.pTabGifts).setVisibility(View.GONE);});
        v(R.id.pTabGiftsBtn).setOnClickListener(x->{v(R.id.pTabStories).setVisibility(View.GONE);v(R.id.pTabGifts).setVisibility(View.VISIBLE);});
        v(R.id.otherBack).setOnClickListener(x->show(profile));
        v(R.id.otherMsgBtn).setOnClickListener(x->toast("افتح المحادثة لإرسال رسالة"));
        v(R.id.otherStarsBtn).setOnClickListener(x->toast("ميزة النجوم غير مربوطة بخدمة بعد"));
        v(R.id.otherGiftBtn).setOnClickListener(x->toast("ميزة الهدايا غير مربوطة بخدمة بعد"));
        v(R.id.otherBlockBtn).setOnClickListener(x->new AlertDialog.Builder(this).setMessage("هل تريد حظر هذا المستخدم؟").setNegativeButton("إلغاء",null).setPositiveButton("حظر",(d,k)->toast("تم تنفيذ طلب الحظر محلياً")).show());
        v(R.id.pPhoto).setOnClickListener(x->toast("اربط هذا الزر بمنتقي الصور لتغيير الصورة الشخصية"));
        v(R.id.pCover).setOnClickListener(x->toast("اربط هذا الزر بمنتقي الصور لتغيير الغلاف"));
    }
}
