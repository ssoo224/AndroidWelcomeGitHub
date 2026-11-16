package com.babylon.chat.iq;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;

/**
 * مجلدات الوسائط داخل ذاكرة التطبيق نفسه (filesDir/media) — لا تظهر في المعرض ولا تحتاج أي صلاحية،
 * وتُمسح مع مسح بيانات التطبيق:
 *   media/images   الصور (صور الرسائل + avatars + emoji)
 *   media/videos   الفيديوهات
 *   media/audio    الملفات الصوتية (رسائل صوتية + voice للتسجيلات + played للتشغيل المؤقت)
 * قاعدة البيانات تحفظ المسار النسبي فقط (مثل "images/msg_ab12.jpg").
 */
final class MediaFiles {
    private MediaFiles() { }

    private static Context app;

    static synchronized void init(Context c) {
        if (c == null) return;
        app = c.getApplicationContext();
        for (File d : new File[]{images(), videos(), audio(), avatars(), emoji(), voice(), played()}) {
            try { if (d != null && !d.exists()) d.mkdirs(); } catch (Throwable ignored) { }
        }
    }

    static File root() {
        Context c = app;
        if (c == null) return null;
        return new File(c.getFilesDir(), "media");
    }

    private static File sub(String rel) {
        File r = root();
        if (r == null) return null;
        File d = new File(r, rel);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static File images() { return sub("images"); }
    static File videos() { return sub("videos"); }
    static File audio()  { return sub("audio"); }
    static File avatars() { return sub("images/avatars"); }
    static File emoji()   { return sub("images/emoji"); }
    static File voice()   { return sub("audio/voice"); }
    static File played()  { return sub("audio/played"); }

    /** المسار الكامل لمسار نسبي محفوظ في القاعدة. */
    static File resolve(String rel) {
        File r = root();
        if (r == null || rel == null || rel.isEmpty() || rel.contains("..")) return null;
        return new File(r, rel);
    }

    // ------------------------------------------------------------------ حفظ/قراءة وسائط الرسائل
    private static String sha(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i++) sb.append(String.format("%02x", h[i]));
            return sb.toString();
        } catch (Throwable t) { return Integer.toHexString(s.hashCode()); }
    }

    private static String extFor(String type, String b64, String mime) {
        if ("audio".equals(type)) return b64.startsWith("T2dnUw") ? ".ogg" : ".m4a";   // "OggS" بصيغة base64
        if (mime != null) {
            if (mime.contains("gif")) return ".gif";
            if (mime.contains("png")) return ".png";
            if (mime.contains("webp")) return ".webp";
        }
        if (b64.startsWith("R0lGOD")) return ".gif";
        if (b64.startsWith("iVBOR")) return ".png";
        return ".jpg";
    }

    /** يفك base64 (اختيارياً مع بادئة data:) ويحفظه ملفاً في المجلد المناسب؛ يعيد المسار النسبي أو null. */
    static String saveMessageMedia(String chatId, String msgId, String type, String mime, String raw) {
        try {
            if (raw == null || raw.isEmpty()) return null;
            String b64 = raw.startsWith("data:") && raw.contains(",") ? raw.substring(raw.indexOf(',') + 1) : raw;
            byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
            if (bytes.length == 0) return null;
            String kind = "audio".equals(type) ? "audio" : "video".equals(type) ? "videos" : "images";
            File dir = "audio".equals(kind) ? audio() : "videos".equals(kind) ? videos() : images();
            if (dir == null) return null;
            String name = "msg_" + sha(chatId + "/" + msgId) + extFor(type, b64, mime);
            File f = new File(dir, name);
            File tmp = new File(dir, name + ".tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(bytes); }
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f); }
            return ("audio".equals(kind) ? "audio/" : "videos".equals(kind) ? "videos/" : "images/") + name;
        } catch (Throwable t) { return null; }
    }

    /** يقرأ ملف الوسائط ويعيده base64 (NO_WRAP) كما كان يُخزَّن سابقاً في حقل data. */
    static String readAsBase64(String rel) {
        try {
            File f = resolve(rel);
            if (f == null || !f.isFile()) return null;
            try (FileInputStream in = new FileInputStream(f)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(256, f.length()));
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            }
        } catch (Throwable t) { return null; }
    }

    static void delete(String rel) {
        try { File f = resolve(rel); if (f != null) f.delete(); } catch (Throwable ignored) { }
    }

    static void deleteTree(File f) {
        try {
            if (f == null || !f.exists()) return;
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
            f.delete();
        } catch (Throwable ignored) { }
    }

    /** نقل مجلد قديم إلى مكانه الجديد (إن لم يوجد الجديد)، وإلا حذفه. */
    static void moveOrDrop(File old, File dst) {
        try {
            if (old == null || !old.exists()) return;
            if (dst != null && (!dst.exists() || (dst.isDirectory() && dst.list() != null && dst.list().length == 0))) {
                if (dst.exists()) dst.delete();
                File parent = dst.getParentFile();
                if (parent != null) parent.mkdirs();
                if (old.renameTo(dst)) return;
            }
            deleteTree(old);
        } catch (Throwable ignored) { }
    }
}
