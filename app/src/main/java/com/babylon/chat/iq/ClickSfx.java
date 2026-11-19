package com.babylon.chat.iq;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.SoundPool;

/** أصوات نقرة/فقعة خفيفة لإرسال واستقبال الرسائل داخل المحادثة (تحترم خيار «الصوت» في إعدادات الإشعارات). */
final class ClickSfx {
    private static SoundPool pool;
    private static int sendId, recvId;
    private static boolean ready;
    private static Context appCtx;
    private static long lastRecv, lastSend;

    private static void init(Context c) {
        appCtx = c.getApplicationContext();
        if (pool != null) return;
        try {
            Context a = c.getApplicationContext();
            pool = new SoundPool.Builder().setMaxStreams(3)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
            sendId = pool.load(a, R.raw.sfx_send, 1);
            recvId = pool.load(a, R.raw.sfx_recv, 1);
            pool.setOnLoadCompleteListener((p, id, st) -> ready = true);
        } catch (Throwable t) { pool = null; }
    }

    private static boolean enabled(SharedPreferences sp) {
        try {
            android.media.AudioManager am = (android.media.AudioManager) appCtx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null && am.getRingerMode() != android.media.AudioManager.RINGER_MODE_NORMAL) return false;   // صامت/اهتزاز: بلا صوت
        } catch (Throwable ignored) { }
        try { return sp == null || sp.getBoolean("sound", true); } catch (Throwable t) { return true; }
    }

    static void send(Context c, SharedPreferences sp) {
        long n = System.currentTimeMillis();
        init(c);
        if (n - lastSend < 60 || !enabled(sp)) return;
        lastSend = n;
        try { if (pool != null && ready) pool.play(sendId, 0.35f, 0.35f, 1, 0, 1f); } catch (Throwable ignored) { }
    }

    static void receive(Context c, SharedPreferences sp) {
        long n = System.currentTimeMillis();
        init(c);
        if (n - lastRecv < 60 || !enabled(sp)) return;
        lastRecv = n;
        try { if (pool != null && ready) pool.play(recvId, 0.35f, 0.35f, 1, 0, 1f); } catch (Throwable ignored) { }
    }

    /** تحميل مسبق كي يعمل أول صوت فوراً. */
    static void warm(Context c) { init(c); }
}
