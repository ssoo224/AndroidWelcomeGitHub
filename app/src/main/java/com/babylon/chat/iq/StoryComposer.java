package com.babylon.chat.iq;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.VideoView;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.SetOptions;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * نشر قصة: منقول من دوال الـ HTML
 *   startStoryPublish / onStoryMediaSelected / handleStoryVideo / openComposer / publishStory / syncStoryMeta
 *
 *  - الحد الأقصى 3 قصص نشطة (expiresAt > الآن).
 *  - الصورة تُضغط إلى JPEG (أطول ضلع 1024، جودة 0.72، تتدرّج للأسفل) وتُحفظ داخل المستند كـ data URL في imageUrl.
 *  - الفيديو: حتى 30 ثانية و100MB، يُرفع إلى Cloudinary (unsigned) وتُحفظ صورة مصغّرة في imageUrl + videoUrl.
 *  - الحقول المحفوظة في stories/{auto}: userId, imageUrl, caption, createdAt, expiresAt, pinned
 *    (+ mediaType='video', videoUrl, videoPath, duration للفيديو).
 *  - بعد النشر تُحدَّث users/{uid}.storyUntil / storyAt لتظهر الحلقة الذهبية عند الجميع.
 */
public final class StoryComposer {

    public static final int REQ_PICK_MEDIA = 1003;

    // نفس ثوابت الـ HTML
    private static final int MAX_ACTIVE = 3;
    private static final int MAX_VIDEO_SEC = 30;
    private static final int MAX_VIDEO_MB = 100;
    private static final int MAX_CAPTION = 200;
    private static final int[] DURS = {6, 12, 24, 48};
    private static final String CLD_CLOUD = "xuljmnmh";
    private static final String CLD_PRESET = "stories_unsigned";

    private static final String LBL_PUBLISH = "نشر القصة";

    public interface Listener { void onPublished(long storyUntil); }

    private final Activity act;
    private final FirebaseFirestore db;
    private final String uid;
    private final boolean dark;
    private final Listener listener;
    private final float dens;

    // حالة المحرّر
    private Dialog dlg;
    private ImageView preview;
    private VideoView previewVid;
    private TextView playBtn, publishBtn, errTv, countTv;
    private EditText caption;
    private LinearLayout chipsRow;
    private boolean pin = false;
    private View pinSwitchTrack, pinSwitchKnob;
    private int hours = 24;
    private String posterDataUrl;
    private Uri videoUri;           // null = صورة
    private double videoDuration;
    private long videoSize;
    private boolean publishing = false;
    private boolean checking = false;

    public StoryComposer(Activity act, FirebaseFirestore db, String uid, boolean dark, Listener l) {
        this.act = act; this.db = db; this.uid = uid; this.dark = dark; this.listener = l;
        this.dens = act.getResources().getDisplayMetrics().density;
    }

    // ============================================================ ألوان وأدوات
    private int dp(float d) { return Math.round(d * dens); }
    private int cBg() { return Tk.pageBg(dark); }
    private int cSf() { return dark ? 0xFF17212B : 0xFFFFFFFF; }
    private int cText() { return dark ? 0xFFF1F5F9 : 0xFF17212B; }
    private int cMuted() { return Tk.muted(dark); }
    private int cBorder() { return Tk.border(dark); }
    private int cGold() { return Tk.gold(dark); }
    private int cRed() { return dark ? 0xFFFB7185 : 0xFFDC2626; }

    private GradientDrawable shape(int fill, float rDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(rDp));
        if (stroke != 0) g.setStroke(dp(1.2f), stroke);
        return g;
    }

    private void msg(String m) {
        try { new AlertDialog.Builder(act).setMessage(m).setPositiveButton("حسناً", null).show(); }
        catch (Throwable ignored) { }
    }

    // ============================================================ startStoryPublish
    /** يتحقق من عدد القصص النشطة ثم يفتح منتقي الوسائط. */
    public void start() {
        if (checking || publishing || dlg != null) return;
        checking = true;
        db.collection("stories").whereEqualTo("userId", uid).get()
            .addOnSuccessListener(snap -> {
                checking = false;
                if (activeCount(snap) >= MAX_ACTIVE) {
                    msg("يمكنك نشر 3 قصص نشطة كحد أقصى. احذف قصة أو انتظر انتهاء إحداها.");
                    return;
                }
                pickMedia();
            })
            .addOnFailureListener(e -> {
                checking = false;
                msg("تعذر التحقق من عدد القصص، حاول مرة أخرى");
            });
    }

    private static int activeCount(QuerySnapshot snap) {
        int n = 0;
        long now = System.currentTimeMillis();
        for (DocumentSnapshot d : snap.getDocuments()) {
            Long ex = d.getLong("expiresAt");
            if (ex != null && ex > now) n++;
        }
        return n;
    }

    private void pickMedia() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            act.startActivityForResult(Intent.createChooser(i, "اختر صورة أو فيديو"), REQ_PICK_MEDIA);
        } catch (Exception e) { msg("لا توجد تطبيقات لعرض الصور"); }
    }

    /** يُستدعى من Activity.onActivityResult؛ يرجع true إذا كان الطلب يخصّنا. */
    public boolean onActivityResult(int req, int res, Intent data) {
        if (req != REQ_PICK_MEDIA) return false;
        if (res != Activity.RESULT_OK || data == null || data.getData() == null) return true;
        final Uri uri = data.getData();
        String mime = null;
        try { mime = act.getContentResolver().getType(uri); } catch (Throwable ignored) { }
        if (mime == null) mime = "";
        final String fm = mime;
        new Thread(() -> {
            try {
                if (fm.startsWith("video/")) prepareVideo(uri);
                else if (fm.startsWith("image/")) prepareImage(uri, fm);
                else ui(() -> msg("اختر صورة أو مقطع فيديو"));
            } catch (Throwable t) {
                ui(() -> msg("تعذر تجهيز الملف، جرب ملفاً ثانياً"));
            }
        }).start();
        return true;
    }

    /** يُستدعى من زر الرجوع؛ يرجع true إذا أغلق شاشة النشر. */
    public boolean onBack() {
        if (dlg != null && dlg.isShowing()) { close(); return true; }
        return false;
    }

    private void ui(Runnable r) { act.runOnUiThread(r); }

    // ============================================================ تجهيز الوسائط (compressImage / handleStoryVideo)
    private void prepareImage(Uri uri, String mime) throws Exception {
        // gif/webp صغيرة (≤650KB) تُحفظ كما هي
        if ((mime.equals("image/gif") || mime.equals("image/webp")) && sizeOf(uri) <= 650000) {
            byte[] raw = readAll(uri);
            final String url = "data:" + mime + ";base64," + Base64.encodeToString(raw, Base64.NO_WRAP);
            ui(() -> openComposer(url, null, 0, 0));
            return;
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        InputStream is0 = act.getContentResolver().openInputStream(uri);
        BitmapFactory.decodeStream(is0, null, o);
        if (is0 != null) is0.close();
        if (o.outWidth <= 0 || o.outHeight <= 0) throw new Exception("bad image");
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / sample > 2048) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        InputStream is1 = act.getContentResolver().openInputStream(uri);
        Bitmap bmp = BitmapFactory.decodeStream(is1, null, o2);
        if (is1 != null) is1.close();
        if (bmp == null) throw new Exception("bad image");
        bmp = applyExif(bmp, uri);

        float dim = 1024f; int q = 72; String out = "";
        for (int i = 0; i < 6; i++) {
            float sc = Math.min(1f, dim / Math.max(bmp.getWidth(), bmp.getHeight()));
            Bitmap b = sc < 1f ? Bitmap.createScaledBitmap(bmp, Math.max(1, Math.round(bmp.getWidth() * sc)),
                    Math.max(1, Math.round(bmp.getHeight() * sc)), true) : bmp;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.JPEG, q, bos);
            out = "data:image/jpeg;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
            if (out.length() < 850000) break;
            dim *= 0.8f; q = Math.max(50, q - 8);
        }
        if (out.length() >= 900000) { ui(() -> msg("تعذر تجهيز الصورة، جرب صورة ثانية")); return; }
        final String fin = out;
        ui(() -> openComposer(fin, null, 0, 0));
    }

    private Bitmap applyExif(Bitmap bmp, Uri uri) {
        try {
            InputStream is = act.getContentResolver().openInputStream(uri);
            int ori = new android.media.ExifInterface(is).getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL);
            if (is != null) is.close();
            float deg = ori == android.media.ExifInterface.ORIENTATION_ROTATE_90 ? 90
                    : ori == android.media.ExifInterface.ORIENTATION_ROTATE_180 ? 180
                    : ori == android.media.ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
            if (deg != 0) {
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.postRotate(deg);
                return Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            }
        } catch (Throwable ignored) { }
        return bmp;
    }

    private void prepareVideo(Uri uri) throws Exception {
        long size = sizeOf(uri);
        if (size > (long) MAX_VIDEO_MB * 1024 * 1024) {
            ui(() -> msg("حجم المقطع كبير (الأقصى " + MAX_VIDEO_MB + " ميجا)"));
            return;
        }
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        double dur; Bitmap frame;
        try {
            r.setDataSource(act, uri);
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            dur = d == null ? 0 : Long.parseLong(d) / 1000.0;
            if (dur <= 0) { ui(() -> msg("تعذر قراءة مدة المقطع")); return; }
            if (dur > MAX_VIDEO_SEC + 0.5) {
                final long sec = Math.round(dur);
                ui(() -> msg("المقطع أطول من " + MAX_VIDEO_SEC + " ثانية (مدته " + sec + " ث). قصّه وجرب مرة ثانية"));
                return;
            }
            frame = r.getFrameAtTime(Math.min(100000L, (long) (dur * 500000)), MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        } catch (Throwable t) {
            ui(() -> msg("تعذر قراءة المقطع، جرب مقطع ثاني"));
            return;
        } finally {
            try { r.release(); } catch (Throwable ignored) { }
        }
        String poster = "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7";
        if (frame != null) {
            float sc = Math.min(1f, 480f / Math.max(frame.getWidth(), frame.getHeight()));
            Bitmap b = sc < 1f ? Bitmap.createScaledBitmap(frame, Math.max(1, Math.round(frame.getWidth() * sc)),
                    Math.max(1, Math.round(frame.getHeight() * sc)), true) : frame;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.JPEG, 60, bos);
            poster = "data:image/jpeg;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
        }
        final String fp = poster; final double fd = Math.min(dur, MAX_VIDEO_SEC); final long fs = size;
        ui(() -> openComposer(fp, uri, fd, fs));
    }

    private long sizeOf(Uri uri) {
        try (Cursor c = act.getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Throwable ignored) { }
        try (android.os.ParcelFileDescriptor p = act.getContentResolver().openFileDescriptor(uri, "r")) {
            if (p != null) return p.getStatSize();
        } catch (Throwable ignored) { }
        return -1;
    }

    private byte[] readAll(Uri uri) throws Exception {
        InputStream is = act.getContentResolver().openInputStream(uri);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384]; int n;
        while (is != null && (n = is.read(buf)) > 0) bos.write(buf, 0, n);
        if (is != null) is.close();
        return bos.toByteArray();
    }

    // ============================================================ openComposer (واجهة صفحة النشر — مطابقة لـ #storyComposer في الـ HTML)
    // ألوان --sp-* من .story-page (نهاري / ليلي)
    private int spBg()     { return dark ? 0xFF0F172A : 0xFFF4F7FB; }
    private int spCard()   { return dark ? 0xFF17212B : 0xFFFFFFFF; }
    private int spText()   { return dark ? 0xFFF1F5F9 : 0xFF17212B; }
    private int spSub()    { return dark ? 0xFFA5B2C2 : 0xFF68798D; }
    private int spBorder() { return dark ? 0xFF334155 : 0xFFDCE4EE; }
    private int spInput()  { return dark ? 0xFF101A26 : 0xFFF5F7FB; }
    // ---- ترويسة مطابقة لـ .edit-profile-header في صفحة «تعديل البيانات الشخصية»
    private int hdrBg()     { return dark ? 0xFF0E1621 : 0xFFFFFFFF; }
    private int hdrLine()   { return dark ? 0x14FFFFFF : 0xFFE1E7EF; }
    private int hdrBackBg() { return dark ? 0xFF232E3C : 0xFFEDF2F8; }
    private int hdrBackFg() { return dark ? 0xFF8696A7 : 0xFF536579; }
    private int spGold()   { return dark ? 0xFFE4C45B : 0xFFB8861B; }
    private int spSoft()   { return dark ? 0x24E4C45B : 0x1FB8861B; }   // .14 / .12
    private static final int G1 = 0xFFF5D875, G2 = 0xFFB98B23;           // linear-gradient(135deg,#e6bd45,#b8861b)

    /** مقياس --bb-s: sqrt(w/360) محصور بين 0.9 و 1.25 (w = أصغر بُعد للشاشة بالـ dp). */
    private float bbScale() {
        try {
            android.util.DisplayMetrics m = act.getResources().getDisplayMetrics();
            float w = Math.min(m.widthPixels, m.heightPixels) / m.density;
            return Math.max(0.9f, Math.min(1.25f, (float) Math.sqrt(w / 360f)));
        } catch (Throwable t) { return 1f; }
    }
    /** px من قيمة CSS (px) مع مقياس --bb-s */
    private int px(float css) { return Math.round(css * dens * bbScale()); }
    private void tsz(TextView t, float css) { t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, css * dens * bbScale()); }

    private GradientDrawable goldGrad(float rCss) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{G1, G2});
        g.setCornerRadius(px(rCss));
        return g;
    }
    private GradientDrawable box(int fill, float rCss, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(px(rCss));
        if (stroke != 0) g.setStroke(Math.max(1, Math.round(dens)), stroke);
        return g;
    }

    private View spIcon(int res, int color, float boxCss, float icoCss, int bg, float rCss) {
        FrameLayout f = new FrameLayout(act);
        if (bg != 0) f.setBackground(box(bg, rCss, 0));
        ImageView iv = new ImageView(act);
        iv.setImageResource(res);
        iv.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(px(icoCss), px(icoCss));
        lp.gravity = Gravity.CENTER;
        f.addView(iv, lp);
        f.setLayoutParams(new LinearLayout.LayoutParams(px(boxCss), px(boxCss)));
        return f;
    }

    private LinearLayout newCard() {
        LinearLayout c = new LinearLayout(act);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(px(14), px(14), px(14), px(14));
        c.setBackground(box(spCard(), 18, spBorder()));
        return c;
    }

    private LinearLayout.LayoutParams spCardLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = px(12);
        return p;
    }

    /** .sp-label: أيقونة 34 + نص (flex:1) + [اختياري] */
    private LinearLayout spLabel(int iconRes, String text, boolean optional) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(act);
        t.setText(text); tsz(t, 14); t.setTypeface(null, Typeface.BOLD); t.setTextColor(spText());
        t.setGravity(Gravity.RIGHT); t.setTextDirection(View.TEXT_DIRECTION_RTL);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
        tl.setMarginEnd(px(10));
        if (optional) {
            TextView o = new TextView(act);
            o.setText("اختياري"); tsz(o, 11);
            o.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            o.setTextColor(spSub());
            o.setBackground(box(spInput(), 999, spBorder()));
            o.setPadding(px(9), px(3), px(9), px(3));
            row.addView(o);
        }
        row.addView(t, tl);
        row.addView(spIcon(iconRes, spGold(), 34, 19, spSoft(), 11));   // الأيقونة في اليمين
        return row;
    }

    private void openComposer(String posterUrl, Uri video, double duration, long size) {
        if (dlg != null) return;
        posterDataUrl = posterUrl; videoUri = video; videoDuration = duration; videoSize = size;
        hours = 24; pin = false; publishing = false;

        dlg = new Dialog(act, android.R.style.Theme_Black_NoTitleBar);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dlg.setCancelable(false);
        dlg.setOnKeyListener((d, code, ev) -> {
            if (code == android.view.KeyEvent.KEYCODE_BACK && ev.getAction() == android.view.KeyEvent.ACTION_UP) { close(); return true; }
            return code == android.view.KeyEvent.KEYCODE_BACK;
        });

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setBackgroundColor(spBg());

        // ---- .sp-header
        LinearLayout header = new LinearLayout(act);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        // padding: 17dp/18dp مثل الأصل، والجزء العلوي يُضاف له ارتفاع شريط الإشعارات ليمتد الهيدر خلفه
        header.setPadding(px(18), px(17) + statusBarH(), px(18), px(17));
        header.setBackgroundColor(hdrBg());
        FrameLayout back = new FrameLayout(act);
        back.setBackground(box(hdrBackBg(), 14, 0));
        ImageView bi = new ImageView(act);
        bi.setImageResource(R.drawable.ic_sp_back);
        bi.setColorFilter(hdrBackFg(), android.graphics.PorterDuff.Mode.SRC_IN);
        FrameLayout.LayoutParams bil = new FrameLayout.LayoutParams(px(24), px(24));
        bil.gravity = Gravity.CENTER;
        back.addView(bi, bil);
        back.setOnClickListener(v -> close());
        LinearLayout titles = new LinearLayout(act);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.RIGHT);
        TextView t1 = new TextView(act);
        t1.setGravity(Gravity.RIGHT); t1.setTextDirection(View.TEXT_DIRECTION_RTL);
        t1.setText("قصة جديدة"); tsz(t1, 18); t1.setTypeface(null, Typeface.BOLD); t1.setTextColor(spText());
        TextView t2 = new TextView(act);
        t2.setGravity(Gravity.RIGHT); t2.setTextDirection(View.TEXT_DIRECTION_RTL);
        t2.setText("شارك لحظتك مع أصدقائك"); tsz(t2, 12); t2.setTextColor(spSub());
        LinearLayout.LayoutParams t2l = new LinearLayout.LayoutParams(-1, -2);
        t2l.topMargin = px(4);
        titles.addView(t1, new LinearLayout.LayoutParams(-1, -2)); titles.addView(t2, t2l);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
        tl.setMarginEnd(px(12));
        header.addView(titles, tl);
        header.addView(back, new LinearLayout.LayoutParams(px(42), px(42)));   // سهم الخروج في اليمين
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));
        View hl = new View(act);
        hl.setBackgroundColor(hdrLine());
        root.addView(hl, new LinearLayout.LayoutParams(-1, Math.max(1, Math.round(dens))));

        // ---- .sp-scroll
        ScrollView sv = new ScrollView(act);
        sv.setFillViewport(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(px(14), px(14), px(14), px(6));
        sv.addView(body, new ViewGroup.LayoutParams(-1, -2));
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        // ---- .sp-media-card (padding:10) > .sp-preview (radius 13، خلفية سوداء، max-height:46%)
        FrameLayout pvBox = new FrameLayout(act);
        pvBox.setBackground(box(0xFF000000, 13, 0));
        pvBox.setClipToOutline(true);
        preview = new ImageView(act);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setAdjustViewBounds(true);
        preview.setMaxHeight(Math.round(act.getResources().getDisplayMetrics().heightPixels * 0.46f));
        try {
            String b64 = posterDataUrl.substring(posterDataUrl.indexOf(',') + 1);
            byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
            preview.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.length));
        } catch (Throwable ignored) { }
        FrameLayout.LayoutParams pil = new FrameLayout.LayoutParams(-1, -2);
        pil.gravity = Gravity.CENTER;
        pvBox.addView(preview, pil);
        if (videoUri != null) {
            previewVid = new VideoView(act);
            previewVid.setVisibility(View.GONE);
            FrameLayout.LayoutParams vp = new FrameLayout.LayoutParams(-1, -1);
            vp.gravity = Gravity.CENTER;
            pvBox.addView(previewVid, vp);
            playBtn = new TextView(act);
            playBtn.setText("▶");
            tsz(playBtn, 24);
            playBtn.setTextColor(0xFFFFFFFF);
            playBtn.setGravity(Gravity.CENTER);
            playBtn.setBackground(box(0x99000000, 999, 0));
            FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(px(60), px(60));
            pp.gravity = Gravity.CENTER;
            pvBox.addView(playBtn, pp);
            playBtn.setOnClickListener(v -> playPreview());
        }
        LinearLayout pvCard = newCard();
        pvCard.setPadding(px(10), px(10), px(10), px(10));
        pvCard.addView(pvBox, new LinearLayout.LayoutParams(-1, -2));
        body.addView(pvCard, spCardLp());

        // ---- بطاقة الوصف
        LinearLayout capCard = newCard();
        LinearLayout capHead = spLabel(R.drawable.ic_sp_caption, "وصف القصة", true);
        LinearLayout.LayoutParams chl = new LinearLayout.LayoutParams(-1, -2);
        chl.bottomMargin = px(11);
        capCard.addView(capHead, chl);
        caption = new EditText(act);
        caption.setHint("اكتب وصفاً لقصتك...");
        caption.setHintTextColor((spSub() & 0x00FFFFFF) | 0xD9000000);   // opacity .85
        caption.setTextColor(spText());
        tsz(caption, 14);
        caption.setLineSpacing(0, 1.35f);
        caption.setMinHeight(px(92));
        caption.setMinLines(3);
        caption.setMaxLines(3);
        caption.setGravity(Gravity.TOP | Gravity.RIGHT);
        caption.setTextDirection(View.TEXT_DIRECTION_RTL);
        caption.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_CAPTION)});
        caption.setBackground(box(spInput(), 13, spBorder()));
        caption.setPadding(px(13), px(12), px(13), px(12));
        caption.setOnFocusChangeListener((v, has) -> caption.setBackground(box(spInput(), 13, has ? spGold() : spBorder())));
        capCard.addView(caption, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout foot = new LinearLayout(act);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        TextView hint = new TextView(act);
        hint.setText("أضف تفاصيل تساعد أصدقاءك على فهم القصة");
        tsz(hint, 11); hint.setTextColor(spSub());
        hint.setGravity(Gravity.RIGHT); hint.setTextDirection(View.TEXT_DIRECTION_RTL);
        countTv = new TextView(act);
        countTv.setText("0/" + MAX_CAPTION); tsz(countTv, 11); countTv.setTextColor(spSub());
        LinearLayout.LayoutParams cnl = new LinearLayout.LayoutParams(-2, -2);
        cnl.setMarginEnd(px(10));
        foot.addView(countTv, cnl);
        foot.addView(hint, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-1, -2);
        fl.topMargin = px(8);
        capCard.addView(foot, fl);
        caption.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { countTv.setText(s.length() + "/" + MAX_CAPTION); }
        });
        body.addView(capCard, spCardLp());

        // ---- بطاقة المدة (.sc-chips: شبكة 4 أعمدة، gap 8)
        LinearLayout durCard = newCard();
        LinearLayout durHead = spLabel(R.drawable.ic_sp_clock, "مدة ظهور القصة", false);
        LinearLayout.LayoutParams dhl = new LinearLayout.LayoutParams(-1, -2);
        dhl.bottomMargin = px(11);
        durCard.addView(durHead, dhl);
        chipsRow = new LinearLayout(act);
        chipsRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < DURS.length; i++) {
            final int h = DURS[i];
            TextView chip = new TextView(act);
            chip.setText(h + " ساعة");
            tsz(chip, 13);
            chip.setGravity(Gravity.CENTER);
            chip.setSingleLine(true);
            chip.setTag(h);
            chip.setOnClickListener(v -> { if (!publishing) { hours = h; refreshChips(); } });
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, px(39), 1f);
            if (i > 0) cl.setMarginStart(px(8));
            chipsRow.addView(chip, cl);
        }
        durCard.addView(chipsRow, new LinearLayout.LayoutParams(-1, -2));
        refreshChips();
        body.addView(durCard, spCardLp());

        // ---- بطاقة التثبيت (.sp-switch-row)
        LinearLayout pinCard = newCard();
        LinearLayout pinRow = new LinearLayout(act);
        pinRow.setOrientation(LinearLayout.HORIZONTAL);
        pinRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout copy = new LinearLayout(act);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.RIGHT);
        TextView pt = new TextView(act);
        pt.setGravity(Gravity.RIGHT); pt.setTextDirection(View.TEXT_DIRECTION_RTL);
        pt.setText("تثبيت بالملف الشخصي"); tsz(pt, 14); pt.setTypeface(null, Typeface.BOLD); pt.setTextColor(spText());
        TextView ps = new TextView(act);
        ps.setGravity(Gravity.RIGHT); ps.setTextDirection(View.TEXT_DIRECTION_RTL);
        ps.setText("تبقى القصة ظاهرة دائماً بعد انتهاء مدتها"); tsz(ps, 11.5f); ps.setTextColor(spSub());
        ps.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams psl = new LinearLayout.LayoutParams(-1, -2);
        psl.topMargin = px(3);
        copy.addView(pt, new LinearLayout.LayoutParams(-1, -2)); copy.addView(ps, psl);
        LinearLayout.LayoutParams cpl = new LinearLayout.LayoutParams(0, -2, 1f);
        cpl.setMarginStart(px(12)); cpl.setMarginEnd(px(12));
        FrameLayout sw = new FrameLayout(act);
        pinSwitchTrack = new View(act);
        pinSwitchKnob = new View(act);
        sw.addView(pinSwitchTrack, new FrameLayout.LayoutParams(px(48), px(28)));
        FrameLayout.LayoutParams kp = new FrameLayout.LayoutParams(px(22), px(22));
        kp.topMargin = px(3);
        sw.addView(pinSwitchKnob, kp);
        pinRow.addView(sw, new LinearLayout.LayoutParams(px(48), px(28)));
        pinRow.addView(copy, cpl);
        pinRow.addView(spIcon(R.drawable.ic_sp_pin, spGold(), 34, 19, spSoft(), 11));   // الأيقونة في اليمين
        pinRow.setOnClickListener(v -> { if (!publishing) { pin = !pin; refreshPin(); } });
        pinCard.addView(pinRow, new LinearLayout.LayoutParams(-1, -2));
        refreshPin();
        body.addView(pinCard, spCardLp());

        // ---- .sp-footer
        View fline = new View(act);
        fline.setBackgroundColor(spBorder());
        root.addView(fline, new LinearLayout.LayoutParams(-1, Math.max(1, Math.round(dens))));
        final LinearLayout footer = new LinearLayout(act);
        footer.setOrientation(LinearLayout.VERTICAL);
        final int fPadB = px(12);
        footer.setPadding(px(14), px(12), px(14), fPadB);
        footer.setBackgroundColor(spCard());
        errTv = new TextView(act);
        tsz(errTv, 12.5f); errTv.setTextColor(cRed()); errTv.setGravity(Gravity.CENTER);
        errTv.setVisibility(View.GONE);
        LinearLayout.LayoutParams erl = new LinearLayout.LayoutParams(-1, -2);
        erl.bottomMargin = px(8);
        footer.addView(errTv, erl);

        publishBtn = new TextView(act);
        tsz(publishBtn, 16);
        publishBtn.setTypeface(null, Typeface.BOLD);
        publishBtn.setTextColor(0xFF171717);
        publishBtn.setGravity(Gravity.CENTER);
        publishBtn.setBackground(goldGrad(14));
        publishBtn.setElevation(px(4));
        try { if (android.os.Build.VERSION.SDK_INT >= 28) publishBtn.setOutlineSpotShadowColor(0x52B8861B); } catch (Throwable ignored) { }
        publishBtn.setText(publishLabel(LBL_PUBLISH));   // سهم نشر متجه للأعلى + النص في منتصف الزر
        publishBtn.setOnClickListener(v -> publish());
        footer.addView(publishBtn, new LinearLayout.LayoutParams(-1, px(50)));

        TextView cancel = new TextView(act);
        cancel.setText("إلغاء");
        tsz(cancel, 14);
        cancel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        cancel.setTextColor(spSub());
        cancel.setGravity(Gravity.CENTER);
        cancel.setBackground(box(0, 13, spBorder()));
        cancel.setOnClickListener(v -> close());
        LinearLayout.LayoutParams cal = new LinearLayout.LayoutParams(-1, px(44));
        cal.topMargin = px(8);
        footer.addView(cancel, cal);
        root.addView(footer, new LinearLayout.LayoutParams(-1, -2));

        // safe-area السفلية (شريط الأزرار) تُضاف لحشوة الفوتر
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            int bt = ins.getSystemWindowInsetBottom();
            int st = ins.getSystemWindowInsetTop();
            if (st > 0) header.setPadding(px(18), px(17) + st, px(18), px(17));
            footer.setPadding(px(14), px(12), px(14), fPadB + Math.max(0, bt));
            return ins;
        });

        dlg.setContentView(root);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(spBg()));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            // شريط الإشعارات: شفاف/بنفس لون الترويسة، والترويسة تمتد خلفه كما في تعديل البيانات الشخصية
            w.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
            w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            w.setStatusBarColor(hdrBg());
            int uiFlags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
            if (!dark && android.os.Build.VERSION.SDK_INT >= 23) uiFlags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            w.getDecorView().setSystemUiVisibility(uiFlags);
        }
        dlg.show();
    }

    private int statusBarH() {
        try {
            int id = act.getResources().getIdentifier("status_bar_height", "dimen", "android");
            return id > 0 ? act.getResources().getDimensionPixelSize(id) : 0;
        } catch (Throwable t) { return 0; }
    }

    private void refreshChips() {
        if (chipsRow == null) return;
        for (int i = 0; i < chipsRow.getChildCount(); i++) {
            TextView c = (TextView) chipsRow.getChildAt(i);
            boolean on = ((Integer) c.getTag()) == hours;
            if (on) {
                c.setBackground(goldGrad(12));
                c.setTextColor(0xFF171717);
                c.setTypeface(null, Typeface.BOLD);
            } else {
                c.setBackground(box(spInput(), 12, spBorder()));
                c.setTextColor(spText());
                c.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            }
        }
    }

    private void refreshPin() {
        if (pinSwitchTrack == null) return;
        pinSwitchTrack.setBackground(pin ? goldGrad(999) : box(spBorder(), 999, 0));
        pinSwitchKnob.setBackground(box(0xFFFFFFFF, 999, 0));
        pinSwitchKnob.setElevation(px(1.5f));
        FrameLayout.LayoutParams kp = (FrameLayout.LayoutParams) pinSwitchKnob.getLayoutParams();
        // RTL: البداية = اليمين. مطفأ: right:3  |  مشغّل: translateX(-20) أي نحو اليسار
        kp.setMarginStart(pin ? px(23) : px(3));
        pinSwitchKnob.setLayoutParams(kp);
    }

    private void playPreview() {
        if (previewVid == null || videoUri == null) return;
        try {
            preview.setVisibility(View.INVISIBLE);
            playBtn.setVisibility(View.GONE);
            previewVid.setVisibility(View.VISIBLE);
            previewVid.setVideoURI(videoUri);
            previewVid.setOnPreparedListener(mp -> { try { mp.setVolume(0f, 0f); } catch (Throwable ignored) { } previewVid.start(); });
            previewVid.setOnCompletionListener(mp -> {
                previewVid.setVisibility(View.GONE);
                preview.setVisibility(View.VISIBLE);
                playBtn.setVisibility(View.VISIBLE);
            });
        } catch (Throwable ignored) { }
    }

    private void close() {
        if (publishing) return;   // لا نغلق أثناء الرفع
        try { if (previewVid != null) previewVid.stopPlayback(); } catch (Throwable ignored) { }
        try { if (dlg != null) dlg.dismiss(); } catch (Throwable ignored) { }
        dlg = null; previewVid = null; playBtn = null; preview = null;
        posterDataUrl = null; videoUri = null;
    }

    private void setBusy(boolean busy, String label) {
        publishing = busy;
        if (publishBtn != null) {
            publishBtn.setEnabled(!busy);
            publishBtn.setAlpha(busy ? 0.7f : 1f);
            publishBtn.setText(LBL_PUBLISH.equals(label) ? publishLabel(label) : label);
        }
    }

    /** سهم نشر ↑ يسبق النص ويتوسّط الزر معه. */
    private CharSequence publishLabel(String label) {
        android.graphics.drawable.Drawable ico = act.getResources().getDrawable(R.drawable.ic_sp_publish, null).mutate();
        ico.setBounds(0, 0, px(21), px(21));
        SpannableString ss = new SpannableString("\u200F \u00A0" + label);
        ss.setSpan(new CenteredIconSpan(ico), 1, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return ss;
    }

    private static final class CenteredIconSpan extends ImageSpan {
        CenteredIconSpan(android.graphics.drawable.Drawable d) { super(d); }
        @Override public void draw(Canvas c, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint paint) {
            android.graphics.drawable.Drawable d = getDrawable();
            c.save();
            c.translate(x, top + (bottom - top - d.getBounds().height()) / 2f);
            d.draw(c);
            c.restore();
        }
    }

    private void showErr(String m) {
        if (errTv == null) return;
        errTv.setText(m);
        errTv.setVisibility(m == null || m.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ============================================================ publishStory
    private void publish() {
        if (publishing || posterDataUrl == null) return;
        showErr(null);
        setBusy(true, "جارٍ التحقق...");
        db.collection("stories").whereEqualTo("userId", uid).get()
            .addOnSuccessListener(snap -> {
                if (activeCount(snap) >= MAX_ACTIVE) {
                    setBusy(false, LBL_PUBLISH);
                    showErr("وصلت إلى الحد الأقصى: 3 قصص نشطة");
                    return;
                }
                doPublish();
            })
            .addOnFailureListener(e -> {
                setBusy(false, LBL_PUBLISH);
                showErr("تعذر التحقق من عدد القصص، حاول مرة أخرى");
            });
    }

    private void doPublish() {
        final String cap = caption.getText().toString().trim();
        final boolean pinned = pin;
        final int hrs = hours;
        final String poster = posterDataUrl;
        if (videoUri == null) {
            save(poster, cap, pinned, hrs, null, null);
            return;
        }
        final Uri vu = videoUri; final double dur = videoDuration; final long size = videoSize;
        setBusy(true, "جاري الرفع 0%");
        new Thread(() -> {
            try {
                final String url = uploadToCloudinary(vu, size);
                ui(() -> save(poster, cap, pinned, hrs, url, dur));
            } catch (final Exception e) {
                ui(() -> {
                    setBusy(false, LBL_PUBLISH);
                    showErr(e.getMessage() != null && e.getMessage().startsWith("تعذر") ? e.getMessage() : "تعذر رفع المقطع: فشل الاتصال");
                });
            }
        }).start();
    }

    private void save(String poster, String cap, boolean pinned, int hrs, String videoUrl, Double dur) {
        long now = System.currentTimeMillis();
        Map<String, Object> m = new HashMap<>();
        m.put("userId", uid);
        m.put("imageUrl", poster);
        m.put("caption", cap);
        m.put("createdAt", now);
        m.put("expiresAt", now + hrs * 3600000L);
        m.put("pinned", pinned);
        if (videoUrl != null) {
            m.put("mediaType", "video");
            m.put("videoUrl", videoUrl);
            m.put("videoPath", "");
            m.put("duration", dur == null ? 0d : dur);
        }
        setBusy(true, "جارٍ النشر...");
        db.collection("stories").add(m)
            .addOnSuccessListener(ref -> syncStoryMeta(until -> {
                publishing = false;
                close();
                if (listener != null) listener.onPublished(until);
            }))
            .addOnFailureListener(e -> {
                setBusy(false, LBL_PUBLISH);
                showErr("تعذر نشر القصة");
            });
    }

    /** مطابق لـ syncStoryMeta في الـ HTML: users/{uid}.storyUntil/storyAt = أحدث القصص النشطة. */
    private void syncStoryMeta(final java.util.function.Consumer<Long> done) {
        db.collection("stories").whereEqualTo("userId", uid).get()
            .addOnSuccessListener(snap -> {
                long now = System.currentTimeMillis(), until0 = 0, at = 0;
                for (DocumentSnapshot d : snap.getDocuments()) {
                    Long ex = d.getLong("expiresAt"), cr = d.getLong("createdAt");
                    if (ex != null && ex > now) {
                        until0 = Math.max(until0, ex);
                        if (cr != null) at = Math.max(at, cr);
                    }
                }
                final long until = until0;
                Map<String, Object> u = new HashMap<>();
                u.put("storyUntil", until);
                u.put("storyAt", at);
                db.collection("users").document(uid).set(u, SetOptions.merge())
                    .addOnCompleteListener(t -> done.accept(until));
            })
            .addOnFailureListener(e -> done.accept(0L));   // القصة نُشرت؛ فشل تحديث الحلقة لا يُلغيها
    }

    // ============================================================ رفع الفيديو إلى Cloudinary (unsigned)
    private String uploadToCloudinary(Uri uri, long fileSize) throws Exception {
        final String boundary = "----BabylonStory" + System.currentTimeMillis();
        final String crlf = "\r\n";
        StringBuilder pre = new StringBuilder();
        pre.append("--").append(boundary).append(crlf)
           .append("Content-Disposition: form-data; name=\"upload_preset\"").append(crlf).append(crlf)
           .append(CLD_PRESET).append(crlf);
        pre.append("--").append(boundary).append(crlf)
           .append("Content-Disposition: form-data; name=\"folder\"").append(crlf).append(crlf)
           .append("stories").append(crlf);
        pre.append("--").append(boundary).append(crlf)
           .append("Content-Disposition: form-data; name=\"file\"; filename=\"story.mp4\"").append(crlf)
           .append("Content-Type: application/octet-stream").append(crlf).append(crlf);
        byte[] head = pre.toString().getBytes(StandardCharsets.UTF_8);
        byte[] tail = (crlf + "--" + boundary + "--" + crlf).getBytes(StandardCharsets.UTF_8);

        HttpURLConnection c = (HttpURLConnection) new URL("https://api.cloudinary.com/v1_1/" + CLD_CLOUD + "/video/upload").openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(20000);
        c.setReadTimeout(120000);
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        if (fileSize > 0) c.setFixedLengthStreamingMode(head.length + fileSize + tail.length);
        else c.setChunkedStreamingMode(65536);

        InputStream in = null;
        try {
            OutputStream out = c.getOutputStream();
            out.write(head);
            in = act.getContentResolver().openInputStream(uri);
            if (in == null) throw new Exception("تعذر قراءة المقطع");
            byte[] buf = new byte[32768];
            long sent = 0; int n, lastPct = -1;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                sent += n;
                if (fileSize > 0) {
                    final int pct = (int) Math.min(100, sent * 100 / fileSize);
                    if (pct != lastPct) { lastPct = pct; ui(() -> { if (publishBtn != null && publishing) publishBtn.setText("جاري الرفع " + pct + "%"); }); }
                }
            }
            out.write(tail);
            out.flush();
            out.close();

            int code = c.getResponseCode();
            InputStream rs = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            if (rs != null) { byte[] b2 = new byte[8192]; int k; while ((k = rs.read(b2)) > 0) bos.write(b2, 0, k); rs.close(); }
            String body = bos.toString("UTF-8");
            JSONObject r = null;
            try { r = new JSONObject(body); } catch (Throwable ignored) { }
            if (code >= 200 && code < 300 && r != null && r.optString("secure_url", "").length() > 0) {
                return r.getString("secure_url");
            }
            String em = r != null && r.optJSONObject("error") != null ? r.optJSONObject("error").optString("message", "" + code) : "" + code;
            throw new Exception("تعذر رفع المقطع: " + em);
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
            c.disconnect();
        }
    }
}
