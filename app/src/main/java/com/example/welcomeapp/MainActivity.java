package com.example.welcomeapp;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int PERMISSION_REQUEST = 71;
    private MediaRecorder recorder;
    private MediaPlayer player;
    private File tempFile;
    private Uri lastUri;
    private boolean recording = false;
    private long startedAt;
    private final Handler handler = new Handler();
    private TextView stateText, timerText;
    private Button recordButton, stopButton, playButton;
    private LinearLayout recordingsList;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (recording) {
                long seconds = (System.currentTimeMillis() - startedAt) / 1000;
                timerText.setText(String.format(Locale.getDefault(), "%02d:%02d", seconds / 60, seconds % 60));
                handler.postDelayed(this, 500);
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        stateText = findViewById(R.id.stateText);
        timerText = findViewById(R.id.timerText);
        recordButton = findViewById(R.id.recordButton);
        stopButton = findViewById(R.id.stopButton);
        playButton = findViewById(R.id.playButton);
        recordingsList = findViewById(R.id.recordingsList);

        recordButton.setOnClickListener(v -> beginRecording());
        stopButton.setOnClickListener(v -> finishRecording());
        playButton.setOnClickListener(v -> playUri(lastUri));
        findViewById(R.id.refreshButton).setOnClickListener(v -> loadRecordings());
        loadRecordings();
    }

    private boolean hasPermissions() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false;
        return Build.VERSION.SDK_INT >= 29 || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void beginRecording() {
        if (!hasPermissions()) {
            if (Build.VERSION.SDK_INT >= 23) {
                if (Build.VERSION.SDK_INT >= 29)
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST);
                else requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSION_REQUEST);
            }
            return;
        }
        try {
            stopPlayer();
            File dir = getExternalCacheDir();
            if (dir == null) dir = getCacheDir();
            tempFile = File.createTempFile("voice_", ".m4a", dir);
            if (Build.VERSION.SDK_INT >= 31) recorder = new MediaRecorder(this);
            else recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(44100);
            recorder.setAudioEncodingBitRate(128000);
            recorder.setOutputFile(tempFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            recording = true;
            startedAt = System.currentTimeMillis();
            timerText.setText("00:00");
            stateText.setText("● جارٍ التسجيل...");
            recordButton.setEnabled(false);
            stopButton.setEnabled(true);
            playButton.setEnabled(false);
            handler.post(ticker);
        } catch (Exception e) {
            releaseRecorder();
            recording = false;
            toast("تعذر بدء التسجيل: " + e.getMessage());
        }
    }

    private void finishRecording() {
        if (!recording) return;
        try {
            recorder.stop();
            recording = false;
            handler.removeCallbacks(ticker);
            releaseRecorder();
            Uri saved = saveToPhone(tempFile);
            if (saved != null) {
                lastUri = saved;
                stateText.setText("تم حفظ التسجيل بنجاح");
                playButton.setEnabled(true);
                toast("تم حفظ التسجيل في Music/VoiceRecorder");
                loadRecordings();
            } else {
                stateText.setText("تعذر حفظ التسجيل");
                toast("لم يتمكن التطبيق من حفظ التسجيل");
            }
        } catch (RuntimeException e) {
            recording = false;
            handler.removeCallbacks(ticker);
            releaseRecorder();
            stateText.setText("التسجيل قصير جداً أو غير صالح");
            toast("حاول التسجيل لمدة أطول");
        } finally {
            recordButton.setEnabled(true);
            stopButton.setEnabled(false);
            if (tempFile != null && tempFile.exists()) tempFile.delete();
        }
    }

    private Uri saveToPhone(File source) {
        if (source == null || !source.exists() || source.length() == 0) return null;
        String name = "Voice_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".m4a";
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Audio.Media.DISPLAY_NAME, name);
                values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4");
                values.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/VoiceRecorder");
                values.put(MediaStore.Audio.Media.IS_PENDING, 1);
                Uri uri = getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) return null;
                try (FileInputStream in = new FileInputStream(source);
                     OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new Exception("تعذر فتح ملف الحفظ");
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = in.read(buffer)) > 0) out.write(buffer, 0, count);
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.Audio.Media.IS_PENDING, 0);
                getContentResolver().update(uri, done, null, null);
                return uri;
            } else {
                File folder = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "VoiceRecorder");
                if (!folder.exists() && !folder.mkdirs()) return null;
                File target = new File(folder, name);
                try (FileInputStream in = new FileInputStream(source); OutputStream out = new java.io.FileOutputStream(target)) {
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = in.read(buffer)) > 0) out.write(buffer, 0, count);
                }
                return Uri.fromFile(target);
            }
        } catch (Exception e) { return null; }
    }

    private void playUri(Uri uri) {
        if (uri == null) { toast("لا يوجد تسجيل لتشغيله"); return; }
        stopPlayer();
        try {
            player = new MediaPlayer();
            if ("file".equals(uri.getScheme())) player.setDataSource(uri.getPath());
            else player.setDataSource(this, uri);
            player.setOnCompletionListener(mp -> {
                stopPlayer();
                stateText.setText("انتهى تشغيل التسجيل");
                playButton.setText("▶  تشغيل آخر تسجيل");
            });
            player.prepare();
            player.start();
            stateText.setText("▶ جارٍ تشغيل التسجيل");
            playButton.setText("Ⅱ  إيقاف التشغيل");
            playButton.setOnClickListener(v -> {
                if (player != null && player.isPlaying()) {
                    stopPlayer(); stateText.setText("تم إيقاف التشغيل");
                    playButton.setText("▶  تشغيل آخر تسجيل");
                } else playUri(uri);
            });
        } catch (Exception e) { stopPlayer(); toast("تعذر تشغيل هذا التسجيل"); }
    }

    private void stopPlayer() {
        if (player != null) {
            try { if (player.isPlaying()) player.stop(); } catch (Exception ignored) {}
            player.release(); player = null;
        }
        playButton.setText("▶  تشغيل آخر تسجيل");
        playButton.setOnClickListener(v -> playUri(lastUri));
    }

    private void loadRecordings() {
        recordingsList.removeAllViews();
        ArrayList<Uri> uris = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 29) {
            String[] projection = {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME};
            String selection = MediaStore.Audio.Media.RELATIVE_PATH + "=?";
            try (Cursor c = getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection,
                    selection, new String[]{Environment.DIRECTORY_MUSIC + "/VoiceRecorder/"}, MediaStore.Audio.Media.DATE_ADDED + " DESC")) {
                if (c != null) while (c.moveToNext()) {
                    long id = c.getLong(0);
                    names.add(c.getString(1));
                    uris.add(Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, String.valueOf(id)));
                }
            } catch (Exception ignored) {}
        } else {
            File folder = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "VoiceRecorder");
            File[] files = folder.listFiles();
            if (files != null) {
                java.util.Arrays.sort(files, (a,b) -> Long.compare(b.lastModified(), a.lastModified()));
                for (File f : files) if (f.isFile()) { names.add(f.getName()); uris.add(Uri.fromFile(f)); }
            }
        }
        for (int i=0; i<names.size(); i++) {
            final Uri itemUri = uris.get(i);
            final String itemName = names.get(i);
            TextView item = new TextView(this);
            item.setText("▶   " + itemName);
            item.setTextColor(0xFF39362F);
            item.setTextSize(14);
            item.setPadding(18, 18, 18, 18);
            item.setBackgroundColor(0xFFFFFFFF);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = 8;
            recordingsList.addView(item, lp);
            item.setOnClickListener(v -> { lastUri = itemUri; playButton.setEnabled(true); playUri(itemUri); });
        }
        if (names.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("لا توجد تسجيلات محفوظة بعد");
            empty.setTextColor(0xFF88847B); empty.setPadding(12, 18, 12, 18);
            recordingsList.addView(empty);
        } else if (lastUri == null) {
            lastUri = uris.get(0);
            playButton.setEnabled(true);
        }
    }

    private void releaseRecorder() {
        if (recorder != null) { try { recorder.reset(); } catch (Exception ignored) {} recorder.release(); recorder = null; }
    }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST) {
            boolean ok = grantResults.length > 0;
            for (int result : grantResults) ok &= result == PackageManager.PERMISSION_GRANTED;
            if (ok) beginRecording(); else toast("يجب السماح باستخدام الميكروفون لحفظ التسجيلات");
        }
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(ticker);
        if (recording) { try { recorder.stop(); } catch (Exception ignored) {} }
        releaseRecorder(); stopPlayer();
        super.onDestroy();
    }
}
