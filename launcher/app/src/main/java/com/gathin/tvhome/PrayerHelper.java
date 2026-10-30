package com.gathin.tvhome;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class PrayerHelper {
    private static final String TAG = "TvHome.Prayer";
    private static final String PREFS_NAME = "prayer_times_cache";
    private static final String JAKIM_BASE_URL = "https://www.e-solat.gov.my/index.php?r=esolatApi/takwimsolat&period=today&zone=";

    private static volatile PrayerHelper sInstance;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;

    private String zone = "SGR01";
    private String location = "Petaling Jaya (Selangor) (SGR01)";
    private String fajr = "05:48";
    private String syuruk = "06:55";
    private String dhuhr = "13:00";
    private String asr = "16:19";
    private String maghrib = "18:59";
    private String isha = "20:09";
    private String hijri = "19 Jamadilawal 1448H";

    private MediaPlayer azanPlayer = null;
    private AudioFocusRequest focusRequest = null;

    public static PrayerHelper getInstance(Context context) {
        if (sInstance == null) {
            synchronized (PrayerHelper.class) {
                if (sInstance == null) {
                    sInstance = new PrayerHelper(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    public static boolean isAzanPlayingStatic() {
        return sInstance != null && sInstance.isAzanPlaying();
    }

    public static void stopAzanStatic() {
        if (sInstance != null) {
            sInstance.stopAzan();
        }
    }

    public PrayerHelper(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        sInstance = this;
        loadCachedData();
        fetchPrayerTimesAsync();
        schedulePrayerAlarms();
    }

    public String getLocation() {
        return location != null ? location : "Kuala Lumpur (WLY01)";
    }

    private void loadCachedData() {
        zone = prefs.getString("zone", zone);
        location = prefs.getString("location", location);
        fajr = prefs.getString("fajr", fajr);
        syuruk = prefs.getString("syuruk", syuruk);
        dhuhr = prefs.getString("dhuhr", dhuhr);
        asr = prefs.getString("asr", asr);
        maghrib = prefs.getString("maghrib", maghrib);
        isha = prefs.getString("isha", isha);
        hijri = prefs.getString("hijri", hijri);
    }

    public void fetchPrayerTimesAsync() {
        new Thread(() -> {
            detectLocationZone();
            fetchFromJakim(zone);
        }).start();
    }

    @SuppressLint("MissingPermission")
    private void detectLocationZone() {
        double lat = 0.0, lon = 0.0;
        boolean hasCoords = false;

        try {
            LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (lm != null) {
                Location loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (loc == null) loc = lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
                if (loc != null) {
                    lat = loc.getLatitude();
                    lon = loc.getLongitude();
                    hasCoords = true;
                }
            }
        } catch (Exception ignored) {}

        if (!hasCoords) {
            try {
                String ipJson = httpGet("http://ip-api.com/json/", 4000);
                if (ipJson != null) {
                    JSONObject obj = new JSONObject(ipJson);
                    if ("success".equalsIgnoreCase(obj.optString("status"))) {
                        lat = obj.optDouble("lat", 0.0);
                        lon = obj.optDouble("lon", 0.0);
                        if (lat != 0.0 && lon != 0.0) hasCoords = true;
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "IP location detection skipped: " + e.getMessage());
            }
        }

        if (hasCoords) {
            try {
                String detectUrl = "https://solat.my/api/detect?latitude=" + lat + "&longitude=" + lon;
                String detectJson = httpGet(detectUrl, 5000);
                if (detectJson != null) {
                    JSONObject obj = new JSONObject(detectJson);
                    String detectedZone = obj.optString("zone");
                    String detectedName = obj.optString("name");
                    String detectedState = obj.optString("state");

                    if (detectedZone != null && !detectedZone.isEmpty()) {
                        zone = detectedZone;
                        String locName = formatLocationName(detectedZone, detectedName, detectedState);
                        location = locName;
                        prefs.edit().putString("zone", zone).putString("location", location).apply();
                        Log.i(TAG, "Detected JAKIM zone: " + zone + " (" + location + ")");
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "Zone detection error: " + e.getMessage());
            }
        }
    }

    private String formatLocationName(String zoneCode, String name, String state) {
        if (name != null && !name.isEmpty()) {
            if (state != null && !state.isEmpty() && !name.equalsIgnoreCase(state)) {
                return name + " (" + state + ") (" + zoneCode + ")";
            }
            return name + " (" + zoneCode + ")";
        }
        return zoneCode;
    }

    private void fetchFromJakim(String targetZone) {
        HttpURLConnection conn = null;
        try {
            String urlStr = JAKIM_BASE_URL + targetZone;
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "CleanYourTV/1.0");

            if (conn.getResponseCode() == 200) {
                BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = in.readLine()) != null) {
                    response.append(line);
                }
                in.close();

                JSONObject root = new JSONObject(response.toString());
                JSONArray array = root.optJSONArray("prayerTime");
                if (array != null && array.length() > 0) {
                    JSONObject today = array.getJSONObject(0);
                    String f = formatTime(today.optString("fajr"));
                    String s = formatTime(today.optString("syuruk"));
                    String d = formatTime(today.optString("dhuhr"));
                    String a = formatTime(today.optString("asr"));
                    String m = formatTime(today.optString("maghrib"));
                    String i = formatTime(today.optString("isha"));
                    String hRaw = today.optString("hijri");
                    String hFormatted = formatHijri(hRaw);

                    prefs.edit()
                            .putString("fajr", f)
                            .putString("syuruk", s)
                            .putString("dhuhr", d)
                            .putString("asr", a)
                            .putString("maghrib", m)
                            .putString("isha", i)
                            .putString("hijri", hFormatted)
                            .putString("zone", targetZone)
                            .putString("location", location)
                            .apply();

                    mainHandler.post(() -> {
                        fajr = f;
                        syuruk = s;
                        dhuhr = d;
                        asr = a;
                        maghrib = m;
                        isha = i;
                        hijri = hFormatted;
                        schedulePrayerAlarms();
                    });
                    Log.i(TAG, "JAKIM prayer times updated successfully for " + targetZone);
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "Using cached prayer times: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public void schedulePrayerAlarms() {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;

            String[] times = {fajr, dhuhr, asr, maghrib, isha};
            String[] names = {"Subuh", "Zohor", "Asar", "Maghrib", "Isyak"};

            Calendar now = Calendar.getInstance();

            for (int i = 0; i < times.length; i++) {
                String t = times[i];
                String name = names[i];
                int m = parseMinutes(t);
                int hour = m / 60;
                int min = m % 60;

                Calendar cal = Calendar.getInstance();
                cal.set(Calendar.HOUR_OF_DAY, hour);
                cal.set(Calendar.MINUTE, min);
                cal.set(Calendar.SECOND, 0);
                cal.set(Calendar.MILLISECOND, 0);

                if (cal.before(now)) {
                    cal.add(Calendar.DAY_OF_YEAR, 1);
                }

                Intent intent = new Intent(context, AzanReceiver.class);
                intent.setAction("com.gathin.tvhome.ACTION_AZAN");
                intent.putExtra("prayer_name", name);
                intent.putExtra("prayer_time", t);

                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    flags |= PendingIntent.FLAG_IMMUTABLE;
                }

                PendingIntent pi = PendingIntent.getBroadcast(context, 100 + i, intent, flags);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
                } else {
                    am.setExact(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
                }
                Log.d(TAG, "Scheduled Azan alarm for " + name + " at " + cal.getTime());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error scheduling prayer alarms: " + e.getMessage(), e);
        }
    }

    private String httpGet(String urlStr, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("User-Agent", "CleanYourTV/1.0");
            if (conn.getResponseCode() == 200) {
                BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = in.readLine()) != null) {
                    sb.append(line);
                }
                in.close();
                return sb.toString();
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    private String formatTime(String raw) {
        if (raw == null || raw.length() < 5) return raw;
        return raw.substring(0, 5);
    }

    private String formatHijri(String raw) {
        if (raw == null || raw.isEmpty()) return hijri;
        String[] parts = raw.split("-");
        if (parts.length != 3) return raw;
        String year = parts[0];
        int monthNum = 1;
        try {
            monthNum = Integer.parseInt(parts[1]);
        } catch (Exception ignored) {}
        String day = parts[2];
        if (day.startsWith("0") && day.length() > 1) day = day.substring(1);

        String[] months = {
                "", "Muharram", "Safar", "Rabiulawal", "Rabiulakhir",
                "Jamadilawal", "Jamadilakhir", "Rejab", "Syaaban",
                "Ramadan", "Syawal", "Zulkaedah", "Zulhijjah"
        };
        String monthName = (monthNum >= 1 && monthNum <= 12) ? months[monthNum] : parts[1];
        return day + " " + monthName + " " + year + "H";
    }

    public void updateView(View root) {
        if (root == null) return;
        TextView txtLocation = root.findViewById(R.id.prayerLocation);
        TextView txtHijri = root.findViewById(R.id.prayerHijri);

        if (txtLocation != null) txtLocation.setText(location);
        if (txtHijri != null) txtHijri.setText(hijri);

        TextView timeSubuh = root.findViewById(R.id.timeSubuh);
        TextView timeSyuruk = root.findViewById(R.id.timeSyuruk);
        TextView timeZohor = root.findViewById(R.id.timeZohor);
        TextView timeAsar = root.findViewById(R.id.timeAsar);
        TextView timeMaghrib = root.findViewById(R.id.timeMaghrib);
        TextView timeIsyak = root.findViewById(R.id.timeIsyak);

        if (timeSubuh != null) timeSubuh.setText(fajr);
        if (timeSyuruk != null) timeSyuruk.setText(syuruk);
        if (timeZohor != null) timeZohor.setText(dhuhr);
        if (timeAsar != null) timeAsar.setText(asr);
        if (timeMaghrib != null) timeMaghrib.setText(maghrib);
        if (timeIsyak != null) timeIsyak.setText(isha);

        View slotSubuh = root.findViewById(R.id.slotSubuh);
        View slotSyuruk = root.findViewById(R.id.slotSyuruk);
        View slotZohor = root.findViewById(R.id.slotZohor);
        View slotAsar = root.findViewById(R.id.slotAsar);
        View slotMaghrib = root.findViewById(R.id.slotMaghrib);
        View slotIsyak = root.findViewById(R.id.slotIsyak);

        TextView nameSubuh = root.findViewById(R.id.nameSubuh);
        TextView nameSyuruk = root.findViewById(R.id.nameSyuruk);
        TextView nameZohor = root.findViewById(R.id.nameZohor);
        TextView nameAsar = root.findViewById(R.id.nameAsar);
        TextView nameMaghrib = root.findViewById(R.id.nameMaghrib);
        TextView nameIsyak = root.findViewById(R.id.nameIsyak);

        // Highlight active prayer
        int nowMins = currentMinutes();
        int mSubuh = parseMinutes(fajr);
        int mSyuruk = parseMinutes(syuruk);
        int mZohor = parseMinutes(dhuhr);
        int mAsar = parseMinutes(asr);
        int mMaghrib = parseMinutes(maghrib);
        int mIsyak = parseMinutes(isha);

        int activeIndex; // 0=Subuh, 1=Syuruk, 2=Zohor, 3=Asar, 4=Maghrib, 5=Isyak
        if (nowMins < mSubuh || nowMins >= mIsyak) {
            activeIndex = 5; // Isyak
        } else if (nowMins < mSyuruk) {
            activeIndex = 0; // Subuh
        } else if (nowMins < mZohor) {
            activeIndex = 1; // Syuruk
        } else if (nowMins < mAsar) {
            activeIndex = 2; // Zohor
        } else if (nowMins < mMaghrib) {
            activeIndex = 3; // Asar
        } else {
            activeIndex = 4; // Maghrib
        }

        View[] slots = {slotSubuh, slotSyuruk, slotZohor, slotAsar, slotMaghrib, slotIsyak};
        TextView[] names = {nameSubuh, nameSyuruk, nameZohor, nameAsar, nameMaghrib, nameIsyak};

        for (int i = 0; i < 6; i++) {
            if (slots[i] != null) {
                if (i == activeIndex) {
                    slots[i].setBackgroundResource(R.drawable.bg_prayer_active);
                    if (names[i] != null) names[i].setTextColor(0xFFFFFFFF);
                } else {
                    slots[i].setBackground(null);
                    if (names[i] != null) names[i].setTextColor(0xB3FFFFFF);
                }
            }
        }
    }

    private int currentMinutes() {
        Calendar cal = Calendar.getInstance();
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
    }

    private int parseMinutes(String time) {
        try {
            String[] p = time.split(":");
            return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
        } catch (Exception e) {
            return 0;
        }
    }

    public synchronized void playAzan(String prayerName) {
        stopAzan();
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                // Set Volume to 1 for gentle alert
                am.setStreamVolume(AudioManager.STREAM_MUSIC, 1, AudioManager.FLAG_SHOW_UI);

                // Request Audio Focus so Netflix / other media ducks or pauses
                AudioAttributes playbackAttributes = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build();

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                            .setAudioAttributes(playbackAttributes)
                            .setAcceptsDelayedFocusGain(false)
                            .setOnAudioFocusChangeListener(focusChange -> {
                                if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                                    stopAzan();
                                }
                            })
                            .build();
                    am.requestAudioFocus(focusRequest);
                } else {
                    am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
                }
            }

            File[] candidates = new File[] {
                    new File("/sdcard/Music/azan.mp3"),
                    new File("/sdcard/Movies/azan.mp3"),
                    new File("/sdcard/azan.mp3"),
                    new File("/sdcard/Movies/azan.mp4"),
                    new File(context.getExternalFilesDir(null), "azan.mp3"),
                    new File("/data/local/tmp/azan.mp3")
            };
            File azanFile = null;
            for (File f : candidates) {
                if (f.exists() && f.canRead()) {
                    azanFile = f;
                    break;
                }
            }

            if (azanFile != null) {
                azanPlayer = new MediaPlayer();
                azanPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
                azanPlayer.setDataSource(azanFile.getAbsolutePath());
                azanPlayer.setOnCompletionListener(mp -> stopAzan());
                azanPlayer.prepare();
                azanPlayer.start();
                Log.i(TAG, "Playing Azan with Audio Focus for " + prayerName + " from " + azanFile.getAbsolutePath());

                mainHandler.post(() -> {
                    Toast.makeText(context, "Masuk Waktu Solat " + prayerName + " (Tekan sebarang butang remote untuk henti)", Toast.LENGTH_LONG).show();
                });
            } else {
                Log.w(TAG, "Azan file not found or unreadable");
                abandonAudioFocus();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error playing azan: " + e.getMessage(), e);
            stopAzan();
        }
    }

    public synchronized void stopAzan() {
        if (azanPlayer != null) {
            try {
                if (azanPlayer.isPlaying()) {
                    azanPlayer.stop();
                }
                azanPlayer.release();
            } catch (Exception ignored) {}
            azanPlayer = null;
            Log.i(TAG, "Azan playback stopped");
        }
        abandonAudioFocus();
    }

    private void abandonAudioFocus() {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
                    am.abandonAudioFocusRequest(focusRequest);
                    focusRequest = null;
                } else {
                    am.abandonAudioFocus(null);
                }
            }
        } catch (Exception ignored) {}
    }

    public synchronized boolean isAzanPlaying() {
        if (azanPlayer == null) return false;
        try {
            return azanPlayer.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }
}
