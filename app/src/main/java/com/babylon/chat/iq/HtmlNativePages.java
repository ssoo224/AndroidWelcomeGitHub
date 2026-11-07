package com.babylon.chat.iq;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

/** Native Android counterparts for HTML-only overlay pages.
 *  This class deliberately uses Android Views/XML and never WebView or Firebase.
 */
public final class HtmlNativePages {
    public interface ColorCallback { void onSelected(int color); }
    private HtmlNativePages() { }

    public static void showStoryComposer(Activity activity) {
        View content = LayoutInflater.from(activity).inflate(R.layout.native_story_composer, null, false);
        AlertDialog dialog = new AlertDialog.Builder(activity).setView(content)
                .setPositiveButton("حفظ كمسودة", (d, w) -> {
                    String text = captionValue(content);
                    activity.getSharedPreferences("babylon_story_drafts", Activity.MODE_PRIVATE)
                            .edit().putString("last_caption", text).apply();
                    Toast.makeText(activity, "تم حفظ وصف القصة كمسودة محلية", Toast.LENGTH_SHORT).show();
                }).setNegativeButton("إغلاق", null).create();
        Button pick = content.findViewById(R.id.storyPickMedia);
        EditText caption = content.findViewById(R.id.storyCaption);
        TextView hint = content.findViewById(R.id.storyMediaHint);
        pick.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("image/*,video/*");
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                // ACTION_GET_CONTENT accepts a wildcard MIME type on Android providers.
                intent.setType("*/*");
                activity.startActivityForResult(Intent.createChooser(intent, "اختيار وسائط القصة"), 771);
                hint.setText("اختر ملف الوسائط من المعرض");
            } catch (Exception e) {
                Toast.makeText(activity, "تعذر فتح منتقي الملفات", Toast.LENGTH_SHORT).show();
            }
        });
        dialog.show();
    }

    private static String captionValue(View content) {
        EditText caption = content.findViewById(R.id.storyCaption);
        return caption == null ? "" : caption.getText().toString().trim();
    }

    public static void showCoverPicker(Activity activity, ColorCallback onSelected) {
        View content = LayoutInflater.from(activity).inflate(R.layout.native_cover_picker, null, false);
        AlertDialog dialog = new AlertDialog.Builder(activity).setView(content)
                .setNegativeButton("إلغاء", null).create();
        int[] ids = {R.id.coverNavy, R.id.coverPurple, R.id.coverGreen,
                R.id.coverOrange, R.id.coverGray, R.id.coverGold};
        int[] colors = {0xFF1B2A4A, 0xFF3B2A4A, 0xFF0F5132,
                0xFF7C2D12, 0xFF334155, 0xFFB8891A};
        for (int i = 0; i < ids.length; i++) {
            final int color = colors[i];
            View swatch = content.findViewById(ids[i]);
            if (swatch != null) swatch.setOnClickListener(v -> {
                onSelected.onSelected(color);
                dialog.dismiss();
            });
        }
        dialog.show();
    }
}
