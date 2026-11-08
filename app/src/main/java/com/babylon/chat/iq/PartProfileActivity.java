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
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import com.babylon.chat.iq.databinding.*;
import java.io.*;
import java.text.*;
import java.util.*;
import java.util.regex.*;
import org.json.*;

public class PartProfileActivity extends Activity {
	
	private PartProfileBinding binding;
	
	@Override
	protected void onCreate(Bundle _savedInstanceState) {
		super.onCreate(_savedInstanceState);
		binding = PartProfileBinding.inflate(getLayoutInflater());
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
		
		// شريط الحالة شفاف تماماً: الغلاف/الصورة تظهر خلفه مثل الـ HTML
		w.setStatusBarColor(0x00000000);
		w.setNavigationBarColor(0x00000000);
		if (android.os.Build.VERSION.SDK_INT >= 29) {
			w.setNavigationBarContrastEnforced(false);
			w.setStatusBarContrastEnforced(false);
		}
		// الصورة/الغلاف تمتد أيضاً خلف القصّة (notch)
		if (android.os.Build.VERSION.SDK_INT >= 28) {
			android.view.WindowManager.LayoutParams lp = w.getAttributes();
			lp.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
			w.setAttributes(lp);
		}
		
		// الأيقونات بيضاء دائماً فوق الغلاف/الصورة (بلا LIGHT_STATUS_BAR)؛ الشريط السفلي حسب الخلفية الفاتحة
		int flags = android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
		| android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
		| android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
		if (android.os.Build.VERSION.SDK_INT >= 26) {
			flags |= android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
		}
		w.getDecorView().setSystemUiVisibility(flags);
		
		// الجذر هنا هو partProfile (لا يوجد id باسم root في part_profile.xml)
		final android.view.View rootView = binding.getRoot();
		final float density = getResources().getDisplayMetrics().density;
		rootView.setOnApplyWindowInsetsListener(new android.view.View.OnApplyWindowInsetsListener() {
			@Override
			public android.view.WindowInsets onApplyWindowInsets(android.view.View v, android.view.WindowInsets insets) {
				int top = insets.getSystemWindowInsetTop();
				// بلا padding علوي: الصفحة تبدأ من أعلى الشاشة ويبقى أسفلها فوق شريط الإيماءات
				v.setPadding(0, 0, 0, insets.getSystemWindowInsetBottom());
				// calc(176px + var(--sat)) : الغلاف يمتد خلف شريط الحالة ومحتواه ينزل تحته
				int[] covers = {R.id.frame_layout642, R.id.frame_layout657};
				for (int id : covers) {
					android.view.View c = v.findViewById(id);
					if (c == null) continue;
					c.getLayoutParams().height = (int) (176 * density + 0.5f) + top;
					c.setPadding(0, top, 0, 0);
					c.requestLayout();
				}
				return insets;
			}
		});
		rootView.requestApplyInsets();
	}
	
}
