package com.babylon.chat.iq;

import android.animation.*;
import android.app.*;
import android.app.Activity;
import android.app.DialogFragment;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.*;
import android.content.res.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.media.*;
import android.net.*;
import android.os.*;
import android.text.*;
import android.text.style.*;
import android.util.*;
import android.view.*;
import android.view.View;
import android.view.View.*;
import android.view.animation.*;
import android.webkit.*;
import android.widget.*;
import com.babylon.chat.iq.databinding.*;
import java.io.*;
import java.text.*;
import java.util.*;
import java.util.regex.*;
import org.json.*;

public class Main2Activity extends Activity {
	
	private Main2Binding binding;
	
	@Override
	protected void onCreate(Bundle _savedInstanceState) {
		super.onCreate(_savedInstanceState);
		binding = Main2Binding.inflate(getLayoutInflater());
		setContentView(binding.getRoot());
		initialize(_savedInstanceState);
		initializeLogic();
	}
	
	private void initialize(Bundle _savedInstanceState) {
		
		binding.btnLogin.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View _view) {
				Intent i = new Intent(getApplicationContext(), MassActivity.class);
				startActivity(i);
				finish();
			}
		});
	}
	
	private void initializeLogic() {
		final android.view.View loginForm = findViewById(R.id.login_form);
		final android.view.View registerForm = findViewById(R.id.register_form);
		final android.widget.TextView tabLogin = (android.widget.TextView) findViewById(R.id.tab_login);
		final android.widget.TextView tabRegister = (android.widget.TextView) findViewById(R.id.tab_register);
		
		tabLogin.setOnClickListener(new android.view.View.OnClickListener() {
			@Override
			public void onClick(android.view.View v) {
				loginForm.setVisibility(android.view.View.VISIBLE);
				registerForm.setVisibility(android.view.View.GONE);
				tabLogin.setBackgroundResource(R.drawable.bg_tab_active);
				tabLogin.setTextColor(0xFFFFFFFF);
				tabRegister.setBackgroundColor(0x00000000);
				tabRegister.setTextColor(0xFF475569);
			}
		});
		
		tabRegister.setOnClickListener(new android.view.View.OnClickListener() {
			@Override
			public void onClick(android.view.View v) {
				loginForm.setVisibility(android.view.View.GONE);
				registerForm.setVisibility(android.view.View.VISIBLE);
				tabRegister.setBackgroundResource(R.drawable.bg_tab_active);
				tabRegister.setTextColor(0xFFFFFFFF);
				tabLogin.setBackgroundColor(0x00000000);
				tabLogin.setTextColor(0xFF475569);
			}
		});
		findViewById(R.id.btn_login).setClickable(true);
		findViewById(R.id.btn_register).setClickable(true);
		
		android.view.View.OnTouchListener pressAnim = new android.view.View.OnTouchListener() {
			@Override
			public boolean onTouch(android.view.View v, android.view.MotionEvent e) {
				switch (e.getAction()) {
					case android.view.MotionEvent.ACTION_DOWN:
					v.animate().scaleX(0.94f).scaleY(0.94f).alpha(0.85f).setDuration(100).start();
					break;
					case android.view.MotionEvent.ACTION_UP:
					case android.view.MotionEvent.ACTION_CANCEL:
					v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(150).start();
					break;
				}
				return false;
			}
		};
		
		findViewById(R.id.btn_login).setOnTouchListener(pressAnim);
		findViewById(R.id.btn_register).setOnTouchListener(pressAnim);
		findViewById(R.id.tab_login).setOnTouchListener(pressAnim);
		findViewById(R.id.tab_register).setOnTouchListener(pressAnim);
		findViewById(R.id.btn_forgot).setOnTouchListener(pressAnim);
		android.view.Window w = getWindow();
		w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
		| android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
		w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
		w.setStatusBarColor(0x00000000);
		w.setNavigationBarColor(0x00000000);
		
		if (android.os.Build.VERSION.SDK_INT >= 29) {
			w.setNavigationBarContrastEnforced(false);
			w.setStatusBarContrastEnforced(false);
		}
		
		// لون الخلفية خلف كل شريط (غيّره إذا غيّرت الخلفية)
		int topBg = 0xFFF1F4F9;
		int botBg = 0xFFEEF2F9;
		boolean topLight = (0.299 * ((topBg >> 16) & 255) + 0.587 * ((topBg >> 8) & 255) + 0.114 * (topBg & 255)) > 150;
		boolean botLight = (0.299 * ((botBg >> 16) & 255) + 0.587 * ((botBg >> 8) & 255) + 0.114 * (botBg & 255)) > 150;
		
		int flags = android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
		| android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
		| android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
		if (android.os.Build.VERSION.SDK_INT >= 23 && topLight) {
			flags |= android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
		}
		if (android.os.Build.VERSION.SDK_INT >= 26 && botLight) {
			flags |= android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
		}
		w.getDecorView().setSystemUiVisibility(flags);
		
		final android.view.View rootView = findViewById(R.id.root);
		rootView.setOnApplyWindowInsetsListener(new android.view.View.OnApplyWindowInsetsListener() {
			@Override
			public android.view.WindowInsets onApplyWindowInsets(android.view.View v, android.view.WindowInsets insets) {
				v.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
				return insets;
			}
		});
		rootView.requestApplyInsets();
		final android.widget.TextView titleView = (android.widget.TextView) findViewById(R.id.title);
		float titleW = titleView.getPaint().measureText(titleView.getText().toString());
		titleView.getPaint().setShader(new android.graphics.LinearGradient(
		0, 0, titleW, 0,
		new int[]{0xFF8B6508, 0xFFC9A227, 0xFFE6C45F},
		null, android.graphics.Shader.TileMode.CLAMP));
		titleView.invalidate();
		// ===== تدرج Babylon Chat اللامع في واجهة تسجيل الدخول: دورة 15 ثانية =====
		final TextView bbLoginTitle = (TextView) findViewById(R.id.title);
		bbLoginTitle.post(new Runnable() {
			@Override
			public void run() {
				float bbLoginW = bbLoginTitle.getPaint().measureText(bbLoginTitle.getText().toString());
				if (bbLoginW <= 0) return;
				final float bbLoginTile = bbLoginW * 2f;
				
				int[] bbLoginColors = {
					0xFF8F6C10, 0xFFC39A2A, 0xFFFFE58A,
					0xFFC39A2A, 0xFF8F6C10, 0xFF8F6C10
				};
				float[] bbLoginPos = {0f, 0.2f, 0.4f, 0.6f, 0.8f, 1f};
				
				final LinearGradient bbLoginGrad = new LinearGradient(
				0, 0, bbLoginTile, 0, bbLoginColors, bbLoginPos, Shader.TileMode.REPEAT);
				bbLoginTitle.getPaint().setShader(bbLoginGrad);
				
				final Matrix bbLoginMatrix = new Matrix();
				ValueAnimator bbLoginShine = ValueAnimator.ofFloat(0f, bbLoginTile);
				bbLoginShine.setDuration(15000);
				bbLoginShine.setInterpolator(new LinearInterpolator());
				bbLoginShine.setRepeatCount(ValueAnimator.INFINITE);
				bbLoginShine.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
					@Override
					public void onAnimationUpdate(ValueAnimator animation) {
						bbLoginMatrix.setTranslate((float) animation.getAnimatedValue(), 0f);
						bbLoginGrad.setLocalMatrix(bbLoginMatrix);
						bbLoginTitle.invalidate();
					}
				});
				bbLoginShine.start();
			}
		});
	}
	
}