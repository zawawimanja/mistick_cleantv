package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Manages unique, non-repeating HD animal wallpapers.
 * Uses a persistent cycle index stored on disk to guarantee that ALL wallpapers
 * in the pool are shown in a sequence before any wallpaper is ever repeated.
 */
public final class OnlineWallpaperManager {
    private static final String TAG = "TvHome.OnlineWallpaper";
    private static final String PREFS_NAME = "wallpaper_rotation_prefs";
    private static final String KEY_INDEX = "wallpaper_cycle_index";
    private static final String ASSET_FILE = "wallpapers_nature.txt";

    // Stable deterministic seed so the shuffled non-repeating sequence is 100% persistent across reboots
    private static final long SEED = 20261030L;

    private static List<String> sUrls = null;

    public static synchronized void init(Context context) {
        if (sUrls != null && !sUrls.isEmpty()) return;
        List<String> list = new ArrayList<>();
        try (InputStream is = context.getAssets().open(ASSET_FILE);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    list.add(line);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load wallpapers asset: " + e.getMessage());
        }

        if (!list.isEmpty()) {
            // Deterministic shuffle with fixed seed: guarantees a perfectly balanced, fixed, non-repeating sequence
            Collections.shuffle(list, new Random(SEED));
            sUrls = list;
            Log.i(TAG, "Initialized " + sUrls.size() + " unique non-repeating animal wallpapers");
        }
    }

    /**
     * Gets the next URL in the sequence, incrementing the persistent index on disk.
     * Guarantees that ALL 510 wallpapers must be shown before any wallpaper can repeat.
     */
    public static synchronized String getNextUrl(Context context) {
        if (sUrls == null || sUrls.isEmpty()) {
            init(context);
        }
        if (sUrls == null || sUrls.isEmpty()) return null;

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int currentIndex = prefs.getInt(KEY_INDEX, 0);

        int selectedIndex = currentIndex % sUrls.size();
        String chosenUrl = sUrls.get(selectedIndex);

        // Advance to next index and persist immediately to disk
        int nextIndex = (selectedIndex + 1) % sUrls.size();
        prefs.edit().putInt(KEY_INDEX, nextIndex).apply();

        Log.i(TAG, "Serving wallpaper [" + (selectedIndex + 1) + "/" + sUrls.size() + "]: " + chosenUrl);
        return chosenUrl;
    }

    /**
     * Fetches the next unique wallpaper bitmap.
     * Retries up to 3 consecutive unique URLs if network fails, ensuring no repeat.
     */
    public static Bitmap fetchNextBitmap(Context context, int targetWidth, int targetHeight, Bitmap.Config bitmapConfig) {
        for (int attempt = 0; attempt < 3; attempt++) {
            String urlString = getNextUrl(context);
            if (urlString == null) return null;

            Bitmap bmp = downloadAndDecode(context, urlString, targetWidth, targetHeight, bitmapConfig);
            if (bmp != null) {
                return bmp;
            }
            Log.w(TAG, "Attempt " + (attempt + 1) + " failed for " + urlString + ", trying next unique wallpaper...");
        }
        return null;
    }

    private static Bitmap downloadAndDecode(Context context, String urlString, int targetWidth, int targetHeight, Bitmap.Config bitmapConfig) {
        File cacheDir = new File(context.getCacheDir(), "online_wallpapers");
        if (!cacheDir.exists()) cacheDir.mkdirs();
        File tempFile = new File(cacheDir, "temp_wall_" + System.currentTimeMillis() + ".jpg");

        try {
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV)");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.connect();

            if (conn.getResponseCode() != 200) {
                Log.w(TAG, "HTTP " + conn.getResponseCode() + " for " + urlString);
                return null;
            }

            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }

            if (tempFile.length() < 1000) {
                tempFile.delete();
                return null;
            }

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(tempFile.getAbsolutePath(), bounds);

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = bitmapConfig;
            int width = bounds.outWidth;
            int height = bounds.outHeight;
            if (width > 0 && height > 0) {
                int sample = 1;
                while (width / (sample * 2) >= targetWidth && height / (sample * 2) >= targetHeight) {
                    sample *= 2;
                }
                opts.inSampleSize = sample;
            }

            Bitmap bmp = BitmapFactory.decodeFile(tempFile.getAbsolutePath(), opts);
            tempFile.delete();
            return bmp;
        } catch (Exception e) {
            Log.w(TAG, "Fetch failed for " + urlString + ": " + e.getMessage());
            if (tempFile.exists()) tempFile.delete();
            return null;
        }
    }
}
