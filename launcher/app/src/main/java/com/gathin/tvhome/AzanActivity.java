package com.gathin.tvhome;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;

public class AzanActivity extends Activity {
    private static final String TAG = "TvHome.AzanActivity";
    private static volatile AzanActivity sInstance = null;

    private final Handler dismissHandler = new Handler(Looper.getMainLooper());
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private MediaPlayer mediaPlayer;

    public static boolean isRunning() {
        return sInstance != null;
    }

    public static void stopAndFinish() {
        if (sInstance != null) {
            sInstance.finishGracefully();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sInstance = this;

        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(1001);
        } catch (Exception ignored) {}

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);

        setContentView(R.layout.activity_azan);

        ImageView iconView = findViewById(R.id.azanIcon);
        TextView txtTitle = findViewById(R.id.azanTitle);
        TextView txtLocation = findViewById(R.id.azanLocation);
        TextView txtSubtitle = findViewById(R.id.azanSubtitle);

        String prayerName = getIntent().getStringExtra("prayer_name");
        if (prayerName == null || prayerName.isEmpty()) prayerName = "SOLAT";
        String location = getIntent().getStringExtra("location");
        if (location == null || location.isEmpty()) location = "Kuala Lumpur (WLY01)";

        if (txtTitle != null) {
            txtTitle.setText("WAKTU SOLAT " + prayerName.toUpperCase());
        }
        if (txtLocation != null) {
            txtLocation.setText(location);
        }
        if (txtSubtitle != null) {
            txtSubtitle.setText("Tekan sebarang butang remote untuk tutup / sambung semula");
        }

        if (iconView != null) {
            String pLower = prayerName.toLowerCase();
            if (pLower.contains("subuh") || pLower.contains("fajr")) {
                iconView.setImageResource(R.drawable.ic_solat_fajr);
            } else if (pLower.contains("zohor") || pLower.contains("dhuhr")) {
                iconView.setImageResource(R.drawable.ic_solat_dhuhr);
            } else if (pLower.contains("asar") || pLower.contains("asr")) {
                iconView.setImageResource(R.drawable.ic_solat_asr);
            } else if (pLower.contains("maghrib")) {
                iconView.setImageResource(R.drawable.ic_solat_maghrib);
            } else {
                iconView.setImageResource(R.drawable.ic_solat_isha);
            }
        }

        pauseForegroundMediaAndSetFocus();
        playAzanAudio();
    }

    private void pauseForegroundMediaAndSetFocus() {
        try {
            audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (audioManager != null) {
                // Set volume to level 1 (gentle audible alert)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 1, AudioManager.FLAG_SHOW_UI);

                // Pause active media session on TV (Netflix, YouTube, SmartTube, etc.)
                long now = SystemClock.uptimeMillis();
                audioManager.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
                audioManager.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0));

                AudioAttributes playbackAttributes = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build();

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                            .setAudioAttributes(playbackAttributes)
                            .setAcceptsDelayedFocusGain(false)
                            .build();
                    audioManager.requestAudioFocus(focusRequest);
                } else {
                    audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Audio focus / media pause error: " + e.getMessage());
        }
    }

    private void playAzanAudio() {
        File[] candidates = new File[] {
                new File("/sdcard/Music/azan.mp3"),
                new File("/sdcard/Movies/azan.mp3"),
                new File("/sdcard/azan.mp3"),
                new File("/sdcard/Movies/azan.mp4"),
                new File(getExternalFilesDir(null), "azan.mp3"),
                new File("/data/local/tmp/azan.mp3")
        };
        File chosen = null;
        for (File f : candidates) {
            if (f.exists() && f.canRead()) {
                chosen = f;
                break;
            }
        }

        if (chosen != null) {
            try {
                mediaPlayer = new MediaPlayer();
                mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
                mediaPlayer.setDataSource(chosen.getAbsolutePath());
                mediaPlayer.setOnCompletionListener(mp -> finishGracefully());
                mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                    Log.e(TAG, "MediaPlayer error: " + what + ", " + extra);
                    finishGracefully();
                    return true;
                });
                mediaPlayer.prepare();
                mediaPlayer.start();
                Log.i(TAG, "Azan audio playback started from: " + chosen.getAbsolutePath());
            } catch (Exception e) {
                Log.e(TAG, "Error starting mediaPlayer: " + e.getMessage(), e);
                dismissHandler.postDelayed(this::finishGracefully, 20000);
            }
        } else {
            Log.i(TAG, "Azan audio file not found, showing serene visual card for 20 seconds");
            dismissHandler.postDelayed(this::finishGracefully, 20000);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int code = event.getKeyCode();
            // Allow volume keys to adjust volume without closing
            if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE) {
                return super.dispatchKeyEvent(event);
            }
            // Any other key (OK, Back, Home, D-pad, etc.) dismisses Azan immediately
            finishGracefully();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        finishGracefully();
    }

    public synchronized void finishGracefully() {
        try {
            dismissHandler.removeCallbacksAndMessages(null);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(1001);
        } catch (Exception ignored) {}

        try {
            if (mediaPlayer != null) {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.release();
                mediaPlayer = null;
            }
        } catch (Exception ignored) {}

        try {
            if (audioManager != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
                    audioManager.abandonAudioFocusRequest(focusRequest);
                    focusRequest = null;
                } else {
                    audioManager.abandonAudioFocus(null);
                }
            }
        } catch (Exception ignored) {}

        sInstance = null;
        finish();
    }

    @Override
    protected void onDestroy() {
        finishGracefully();
        super.onDestroy();
    }
}
