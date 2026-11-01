package com.gathin.tvhome;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * BroadcastReceiver for prayer time Azan alarm.
 * Takes over the screen out of respect for prayer time (stops foreground playback),
 * shows a serene still prayer card and plays gentle Azan audio (no video).
 */
public class AzanReceiver extends BroadcastReceiver {
    private static final String TAG = "TvHome.AzanReceiver";
    private static final String CHANNEL_ID = "azan_alarm_channel";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        PrayerHelper helper = PrayerHelper.getInstance(context);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            Log.i(TAG, "Boot completed, scheduling prayer alarms");
            helper.schedulePrayerAlarms();
            return;
        }

        if ("com.gathin.tvhome.ACTION_DISMISS_AZAN".equals(action)) {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(1001);
            }
            return;
        }

        String prayerName = intent != null ? intent.getStringExtra("prayer_name") : null;
        if (prayerName == null || prayerName.isEmpty()) {
            prayerName = "Solat";
        }
        Log.i(TAG, "AzanReceiver triggered for " + prayerName);

        // 1. Wake the CPU and Screen (Turn on display if sleeping)
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = null;
        if (pm != null) {
            try {
                wakeLock = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE,
                        "TvHome:AzanWakeLock"
                );
                wakeLock.acquire(4 * 60 * 1000L); // 4 minutes
            } catch (Exception e) {
                Log.w(TAG, "WakeLock error: " + e.getMessage());
            }
        }

        // 2. Prevent duplicate triggers today
        SharedPreferences prefs = context.getSharedPreferences("prayer_times_cache", Context.MODE_PRIVATE);
        String todayStr = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        String key = "played_" + todayStr + "_" + prayerName;
        boolean forced = intent != null && intent.getBooleanExtra("forced", false);
        if (!forced && prefs.getBoolean(key, false)) {
            Log.d(TAG, "Azan already played today for " + prayerName);
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            return;
        }
        prefs.edit().putBoolean(key, true).apply();

        // 3. Prepare AzanActivity Intent to take over foreground (stops whatever was playing)
        Intent azanIntent = new Intent(context, AzanActivity.class);
        azanIntent.putExtra("prayer_name", prayerName);
        azanIntent.putExtra("location", helper.getLocation());
        azanIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                | Intent.FLAG_ACTIVITY_CLEAR_TOP 
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);

        // 4. Guaranteed Full-Screen Intent via High-Priority Notification (Android 10+ background takeover)
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    NotificationChannel chan = new NotificationChannel(CHANNEL_ID, "Waktu Solat", NotificationManager.IMPORTANCE_HIGH);
                    chan.setDescription("Azan Waktu Solat");
                    chan.setSound(null, null);
                    nm.createNotificationChannel(chan);
                }

                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    flags |= PendingIntent.FLAG_IMMUTABLE;
                }
                PendingIntent pi = PendingIntent.getActivity(context, 1001, azanIntent, flags);

                NotificationCompat.Builder nb = new NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_solat_fajr)
                        .setContentTitle("WAKTU SOLAT " + prayerName.toUpperCase())
                        .setContentText(helper.getLocation())
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setCategory(NotificationCompat.CATEGORY_EVENT)
                        .setFullScreenIntent(pi, true)
                        .setTimeoutAfter(20000)
                        .setAutoCancel(true);

                nm.notify(1001, nb.build());
            }
        } catch (Exception e) {
            Log.e(TAG, "Notification fullScreenIntent error: " + e.getMessage());
        }

        // 5. Also launch directly
        try {
            context.startActivity(azanIntent);
            Log.i(TAG, "Called startActivity for AzanActivity");
        } catch (Exception e) {
            Log.w(TAG, "Direct startActivity error: " + e.getMessage());
        }

        helper.schedulePrayerAlarms();
    }
}
