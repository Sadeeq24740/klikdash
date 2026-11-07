package com.ashafizullah.klikdash;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.IBinder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Azan otomatis saat masuk waktu sholat, dari fail MP3 pilihan pengguna (KlikDash tidak membawa rekaman azan: rekaman
 * yang beredar umumnya berhak cipta). Mati secara bawaan; dinyalakan di panel cepat › AZAN. Fail disalin ke penyimpanan
 * DE (azan.mp3, dan azan_subuh.mp3 bila Subuh memakai fail lain). {@link PrayerAlarm} memanggil {@link #play} pada
 * waktunya; {@link Player} memutar dengan audio focus sementara, sehingga musik (Spotify, murattal) dijeda dan
 * berlanjut sesudahnya. Prefs (DE "klikdash"): azanOn, azanP0..azanP4 (per waktu), azanVol (0–100).
 */
final class Azan {
    private Azan() { }

    static final String CHANNEL = "azan";
    static final int NOTIFICATION_ID = 6; // 1 murattal, 2 pengingat sholat, 3 KlikBeam, 4 ADB/dzikir, 5 perjalanan
    static final String ACTION_STOP = "com.ashafizullah.klikdash.azan.STOP";

    /** Sedang berkumandang, dan untuk waktu sholat mana (indeks Lang.prayers; -1 = coba dari Pengaturan). */
    static volatile boolean playing;
    static volatile int prayer = -1;
    /** Home yang tampil: digambar ulang saat azan mulai/berhenti. */
    static volatile Runnable listener;
    private static MediaPlayer mp;

    static boolean enabled(SharedPreferences p) { return p.getBoolean("azanOn", false); }

    static boolean forPrayer(SharedPreferences p, int i) { return p.getBoolean("azanP" + i, true); }

    static int volume(SharedPreferences p) { return p.getInt("azanVol", 70); }

    static File file(Context c, boolean subuh) {
        return new File(c.createDeviceProtectedStorageContext().getFilesDir(), subuh ? "azan_subuh.mp3" : "azan.mp3");
    }

    /** Fail untuk waktu {@code i}: Subuh memakai fail sendiri bila ada. */
    static File fileFor(Context c, int i) {
        File s = file(c, true);
        return i == 0 && s.isFile() ? s : file(c, false);
    }

    /** Waktu sholat {@code i} masuk (PrayerAlarm): putar bila azan dinyalakan untuk waktu itu dan failnya ada. */
    static void onPrayer(Context c, int i) {
        SharedPreferences p = Prefs.of(c);
        if (!enabled(p) || !forPrayer(p, i) || !fileFor(c, i).isFile()) return;
        play(c, i);
    }

    static void play(Context c, int i) {
        Compat.startForegroundService(c, new Intent(c, Player.class).putExtra("i", i));
    }

    static void stop(Context c) {
        if (!playing) return;
        c.startService(new Intent(c, Player.class).setAction(ACTION_STOP));
    }

    static long position() {
        MediaPlayer m = mp;
        try { return m != null ? m.getCurrentPosition() : 0; } catch (IllegalStateException e) { return 0; }
    }

    static long duration() {
        MediaPlayer m = mp;
        try { return m != null ? Math.max(0, m.getDuration()) : 0; } catch (IllegalStateException e) { return 0; }
    }

    private static void changed() {
        Runnable r = listener;
        if (r != null) r.run();
    }

    /** Salin fail pilihan pengguna (lewat pemilih fail) ke penyimpanan DE; dijalankan di thread latar. */
    static boolean copy(Context c, Uri uri, boolean subuh) {
        File f = file(c, subuh), tmp = new File(f.getPath() + ".tmp");
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) return false;
            try (OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            }
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
        // Harus bisa diputar: fail rusak atau bukan audio ditolak.
        MediaPlayer test = new MediaPlayer();
        try {
            test.setDataSource(tmp.getPath());
            test.prepare();
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        } finally {
            test.release();
        }
        return tmp.renameTo(f);
    }

    /** Panjang fail azan (ms), atau 0 bila tidak ada/tidak bisa dibaca. */
    static long length(File f) {
        if (!f.isFile()) return 0;
        MediaPlayer m = new MediaPlayer();
        try {
            m.setDataSource(f.getPath());
            m.prepare();
            return m.getDuration();
        } catch (Exception e) {
            return 0;
        } finally {
            m.release();
        }
    }

    /** Pemutar azan: layanan latar dengan notifikasi berisi tombol henti; berhenti sendiri saat fail selesai. */
    public static class Player extends Service {
        private Compat.Focus focus;

        @Override public int onStartCommand(Intent i, int flags, int id) {
            startForeground(NOTIFICATION_ID, notification(i != null ? i.getIntExtra("i", prayer) : prayer));
            if (i != null && ACTION_STOP.equals(i.getAction())) {
                finish();
                return START_NOT_STICKY;
            }
            int which = i != null ? i.getIntExtra("i", -1) : -1;
            start(which);
            return START_NOT_STICKY;
        }

        private Notification notification(int which) {
            Context l = Lang.wrap(this);
            String name = which >= 0 && which < 5 ? l.getString(R.string.azan_now, HomeCards.title(Lang.prayers[which]))
                    : l.getString(R.string.azan_title);
            PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, Player.class).setAction(ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT);
            return Compat.notification(this, CHANNEL, l.getString(R.string.azan_channel))
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle(name)
                    .setContentText(l.getString(R.string.azan_tap_stop))
                    .setContentIntent(stop)
                    .setOngoing(true)
                    .build();
        }

        private void start(int which) {
            release();
            File f = fileFor(this, Math.max(0, which));
            if (!f.isFile()) {
                finish();
                return;
            }
            AudioAttributes attrs = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            focus = new Compat.Focus(this, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, attrs, change -> {
                if (change == AudioManager.AUDIOFOCUS_LOSS) finish(); // aplikasi lain mengambil alih (mis. panggilan)
            });
            focus.request();
            MediaPlayer m = new MediaPlayer();
            try {
                m.setAudioAttributes(attrs);
                m.setDataSource(f.getPath());
                m.prepare();
                float v = volume(Prefs.of(this)) / 100f;
                m.setVolume(v, v);
                m.setOnCompletionListener(x -> finish());
                m.start();
            } catch (Exception e) {
                m.release();
                finish();
                return;
            }
            mp = m;
            prayer = which;
            playing = true;
            changed();
        }

        private void release() {
            MediaPlayer m = mp;
            mp = null;
            if (m != null) {
                try { m.stop(); } catch (IllegalStateException ignored) { }
                m.release();
            }
        }

        private void finish() {
            release();
            if (focus != null) focus.abandon(); // musik yang dijeda berlanjut
            focus = null;
            playing = false;
            changed();
            stopForeground(true);
            stopSelf();
        }

        @Override public void onDestroy() {
            release();
            if (focus != null) focus.abandon();
            playing = false;
            changed();
            super.onDestroy();
        }

        @Override public IBinder onBind(Intent i) { return null; }
    }
}
