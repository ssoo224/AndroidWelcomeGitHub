package com.babylon.chat.iq;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.util.AttributeSet;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.EditText;

import androidx.core.view.inputmethod.EditorInfoCompat;
import androidx.core.view.inputmethod.InputConnectionCompat;
import androidx.core.view.inputmethod.InputContentInfoCompat;

/**
 * حقل كتابة يقبل المحتوى الغني من لوحة المفاتيح: ملصقات، GIF، صور (Gboard وSamsung وغيرها).
 * بدون هذا الإعلان (contentMimeTypes) تظهر أيقونتا GIF والملصقات رمادية ومعطّلة في لوحة المفاتيح.
 * الإيموجي نصّ عادي فيعمل دائماً. لا حاجة لأي إذن في المانيفست.
 */
public class RichEditText extends EditText {

    public interface OnRichContent {
        /** release: يُستدعى بعد الانتهاء من قراءة الملف لتحرير إذن القراءة المؤقت. */
        void onContent(Uri uri, String mime, Runnable release);
    }

    private static final String[] MIMES = {"image/gif", "image/png", "image/webp", "image/jpeg", "image/*"};
    private OnRichContent listener;

    public RichEditText(Context c) { super(c); }
    public RichEditText(Context c, AttributeSet a) { super(c, a); }
    public RichEditText(Context c, AttributeSet a, int s) { super(c, a, s); }

    public void setOnRichContent(OnRichContent l) { listener = l; }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        InputConnection ic = super.onCreateInputConnection(outAttrs);
        if (ic == null) return null;
        EditorInfoCompat.setContentMimeTypes(outAttrs, MIMES);
        return InputConnectionCompat.createWrapper(ic, outAttrs, (InputContentInfoCompat info, int flags, android.os.Bundle opts) -> {
            if (listener == null) return false;
            if (Build.VERSION.SDK_INT >= 25 && (flags & InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION) != 0) {
                try { info.requestPermission(); } catch (Exception e) { return false; }
            }
            final InputContentInfoCompat fi = info;
            String mime = info.getDescription().getMimeTypeCount() > 0 ? info.getDescription().getMimeType(0) : "image/*";
            listener.onContent(info.getContentUri(), mime, () -> {
                if (Build.VERSION.SDK_INT >= 25) { try { fi.releasePermission(); } catch (Throwable ignored) { } }
            });
            return true;
        });
    }
}
