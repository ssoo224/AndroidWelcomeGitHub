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

public class PartSettingsActivity extends Activity {
	
	private PartSettingsBinding binding;
	
	@Override
	protected void onCreate(Bundle _savedInstanceState) {
		super.onCreate(_savedInstanceState);
		binding = PartSettingsBinding.inflate(getLayoutInflater());
		setContentView(binding.getRoot());
		initialize(_savedInstanceState);
		initializeLogic();
	}
	
	private void initialize(Bundle _savedInstanceState) {
	}
	
	private void initializeLogic() {
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
	}
	
}