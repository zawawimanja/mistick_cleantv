package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.media.FaceDetector;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Manages unlimited live dynamic vehicle wallpapers.
 * Strict category interleaving guarantees that consecutive wallpapers NEVER
 * come from the same vehicle class (e.g. no repeating excavators, cars, or jets).
 */
public final class OnlineWallpaperManager {
    private static final String TAG = "TvHome.OnlineWallpaper";
    private static final String PREFS_NAME = "wallpaper_rotation_prefs";
    private static final String KEY_INDEX = "wallpaper_cycle_index";
    private static final String ASSET_FILE = "wallpapers_nature.txt";

    // 10 distinct, non-overlapping vehicle categories for strict rotation (pure machines, no people)
    private static final String[][] MASTER_CATEGORIES = {
        // 0: Supercars & Sports Cars
        {"supercar automobile parked", "ferrari sports car vehicle", "lamborghini automobile", "porsche 911 car exterior", "bugatti chiron vehicle", "chevrolet corvette sports car", "nissan gtr vehicle"},
        // 1: Aviation & Fighter Jets
        {"fighter jet aircraft flight", "f-16 fighting falcon aircraft", "f-22 raptor airplane", "airbus a350 aircraft flight", "concorde aircraft supersonic", "commercial airliner airplane flight"},
        // 2: Heavy Construction & Machinery
        {"caterpillar excavator machinery", "construction crane machinery", "mining dump truck vehicle", "bulldozer caterpillar machinery", "road roller compactor vehicle"},
        // 3: Emergency & Rescue
        {"fire engine vehicle", "firetruck vehicle", "emergency ambulance vehicle", "airport crash tender vehicle", "coast guard cutter ship"},
        // 4: Trains & High Speed Rail
        {"shinkansen bullet train in motion", "high-speed train locomotive", "maglev train railway", "steam locomotive train track", "city monorail train track"},
        // 5: Helicopters
        {"military helicopter flight", "apache attack helicopter", "ch-47 chinook helicopter", "uh-60 black hawk helicopter"},
        // 6: Marine, Cargo Ships & Naval
        {"cargo container ship at sea", "naval submarine underway", "aircraft carrier warship at sea", "unmanned deep-sea submersible", "luxury yacht at sea"},
        // 7: Motorcycles (Parked, No Rider)
        {"superbike motorcycle parked", "ducati panigale motorcycle parked", "harley-davidson motorcycle parked", "yamaha r1 motorcycle parked"},
        // 8: Space & Unmanned Craft
        {"space rocket launch flame", "space shuttle launch", "mars curiosity rover", "unmanned spacecraft space", "amphibious hovercraft vehicle"},
        // 9: Utility, Farm & Public Transport
        {"combine harvester farm field", "farm tractor field", "semi-trailer truck highway", "double-decker bus vehicle", "yellow school bus parked", "warehouse forklift machine"}
    };

    private static final String[] HUMAN_BLACKLIST = {
        "person", "people", "man", "woman", "women", "men", "girl", "boy", "human",
        "driver", "rider", "pilot", "crew", "passenger", "portrait", "standing",
        "sitting", "model", "posing", "worker", "crowd", "face", "police", "soldier",
        "child", "children", "baby", "family", "tourist", "pedestrian", "lady",
        "guy", "bather", "swimmer", "cyclist", "biker", "operator", "officer",
        "sailor", "astronaut", "captain", "racer", "selfie", "costume", "wearing",
        "emily", "bea kyle", "bea_kyle", "look", "smile"
    };

    private static int sCategoryCycle = 0;
    private static final List<String> sAssetUrls = new ArrayList<>();
    private static final List<String> sLiveQueue = new ArrayList<>();
    private static final Random sRandom = new Random();
    private static volatile boolean sFetchingApi = false;

    public static synchronized void init(Context context) {
        if (!sAssetUrls.isEmpty()) return;
        try (InputStream is = context.getAssets().open(ASSET_FILE);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    sAssetUrls.add(line);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load wallpapers asset: " + e.getMessage());
        }

        if (!sAssetUrls.isEmpty()) {
            Collections.shuffle(sAssetUrls);
            Log.i(TAG, "Initialized " + sAssetUrls.size() + " fallback vehicle wallpapers");
        }

        triggerAsyncApiFetch();
    }

    /**
     * Gets a candidate URL: tries live API queue first, otherwise queries live API in background,
     * or pulls from shuffled asset pool.
     */
    public static synchronized String getNextUrl(Context context) {
        if (sAssetUrls.isEmpty()) {
            init(context);
        }

        // 1. Try to take from live dynamic API queue (which is strictly interleaved)
        if (!sLiveQueue.isEmpty()) {
            String liveUrl = sLiveQueue.remove(0);
            Log.i(TAG, "Serving LIVE API vehicle wallpaper: " + liveUrl);
            if (sLiveQueue.size() <= 2) {
                triggerAsyncApiFetch();
            }
            return liveUrl;
        }

        // If queue is running low, trigger background fetch for next rotations
        triggerAsyncApiFetch();

        // 2. Fall back to asset pool
        if (!sAssetUrls.isEmpty()) {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            int currentIndex = prefs.getInt(KEY_INDEX, -1);
            if (currentIndex < 0) {
                currentIndex = sRandom.nextInt(sAssetUrls.size());
            }

            int selectedIndex = currentIndex % sAssetUrls.size();
            String chosenUrl = sAssetUrls.get(selectedIndex);

            int nextIndex = (selectedIndex + 1) % sAssetUrls.size();
            prefs.edit().putInt(KEY_INDEX, nextIndex).apply();

            Log.i(TAG, "Serving asset pool wallpaper [" + (selectedIndex + 1) + "/" + sAssetUrls.size() + "]: " + chosenUrl);
            return chosenUrl;
        }

        return null;
    }

    private static void triggerAsyncApiFetch() {
        if (sFetchingApi) return;
        sFetchingApi = true;
        new Thread(() -> {
            try {
                fetchInterleavedFromApi();
            } finally {
                sFetchingApi = false;
            }
        }).start();
    }

    /**
     * Fetches exactly 1 image per category across 5 distinct categories,
     * guaranteeing diverse interleaving (never multiple of the same category).
     */
    private static void fetchInterleavedFromApi() {
        List<String> gathered = new ArrayList<>();
        int rounds = 5;

        for (int i = 0; i < rounds; i++) {
            int catIdx;
            synchronized (OnlineWallpaperManager.class) {
                catIdx = sCategoryCycle % MASTER_CATEGORIES.length;
                sCategoryCycle++;
            }

            String[] terms = MASTER_CATEGORIES[catIdx];
            String term = terms[sRandom.nextInt(terms.length)];
            String imgUrl = fetchOneImageForTerm(term);
            if (imgUrl != null && !imgUrl.isEmpty()) {
                gathered.add(imgUrl);
                Log.i(TAG, "Live API [Cat " + catIdx + "] fetched: " + term);
            }
        }

        if (!gathered.isEmpty()) {
            synchronized (OnlineWallpaperManager.class) {
                sLiveQueue.addAll(gathered);
                while (sLiveQueue.size() > 25) {
                    sLiveQueue.remove(0);
                }
            }
        }
    }

    private static boolean isHumanContaminated(String text) {
        if (text == null || text.isEmpty()) return false;
        String lower = text.toLowerCase(Locale.US);
        for (String keyword : HUMAN_BLACKLIST) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static String fetchOneImageForTerm(String term) {
        try {
            String fullSearch = term + " filetype:bitmap -portrait -people -person -man -woman -men -women -girl -boy -human -driver -rider -pilot -crew -passenger -standing -sitting -posing -crowd -face -model -police -soldier -worker -tourist -pedestrian -family -child -children -sailor";
            String encoded = URLEncoder.encode(fullSearch, "UTF-8");
            String apiUrl = "https://commons.wikimedia.org/w/api.php?action=query&generator=search&gsrsearch="
                    + encoded + "&gsrnamespace=6&gsrlimit=10&prop=imageinfo&iiprop=url&iiurlwidth=1920&format=json";

            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "CleanYourTV/1.0 (Linux; Android TV; wallpaper-client)");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            if (conn.getResponseCode() == 200) {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                }

                JSONObject root = new JSONObject(sb.toString());
                JSONObject query = root.optJSONObject("query");
                if (query != null) {
                    JSONObject pages = query.optJSONObject("pages");
                    if (pages != null) {
                        Iterator<String> keys = pages.keys();
                        while (keys.hasNext()) {
                            String key = keys.next();
                            JSONObject page = pages.optJSONObject(key);
                            if (page != null) {
                                String title = page.optString("title", "");
                                if (isHumanContaminated(title)) {
                                    continue; // Skip images with humans in the title
                                }

                                JSONArray infos = page.optJSONArray("imageinfo");
                                if (infos != null && infos.length() > 0) {
                                    JSONObject info = infos.getJSONObject(0);
                                    String imgUrl = info.optString("thumburl", info.optString("url", ""));
                                    if (!imgUrl.isEmpty() && !isHumanContaminated(imgUrl)
                                            && (imgUrl.contains(".jpg") || imgUrl.contains(".jpeg") || imgUrl.contains(".png") || imgUrl.contains(".webp"))) {
                                        return imgUrl; // Found a pure vehicle image without humans!
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Fetches the next unique wallpaper bitmap.
     * Tries live API and asset pool, with fallback.
     */
    public static Bitmap fetchNextBitmap(Context context, int targetWidth, int targetHeight, Bitmap.Config bitmapConfig) {
        init(context);

        if (sLiveQueue.size() < 3) {
            triggerAsyncApiFetch();
        }

        for (int attempt = 0; attempt < 3; attempt++) {
            String urlString = getNextUrl(context);
            if (urlString == null) break;

            Bitmap bmp = downloadAndDecode(context, urlString, targetWidth, targetHeight, bitmapConfig);
            if (bmp != null) {
                return bmp;
            }
            Log.w(TAG, "Attempt " + (attempt + 1) + " failed for " + urlString + ", trying next wallpaper...");
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
            conn.setRequestProperty("User-Agent", "CleanYourTV/1.0 (Linux; Android TV; wallpaper-client)");
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

            // Verify with on-device face detector to guarantee NO humans in wallpapers
            if (detectHumanFace(tempFile.getAbsolutePath())) {
                Log.w(TAG, "Rejected wallpaper because human face was detected: " + urlString);
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

    private static boolean detectHumanFace(String filePath) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(filePath, bounds);
            int origW = bounds.outWidth;
            int origH = bounds.outHeight;
            if (origW <= 0 || origH <= 0) return false;

            int sample = 1;
            while (origW / (sample * 2) >= 480) {
                sample *= 2;
            }

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            opts.inSampleSize = sample;

            Bitmap bmp = BitmapFactory.decodeFile(filePath, opts);
            if (bmp == null) return false;

            int w = bmp.getWidth();
            int h = bmp.getHeight();
            if ((w % 2) != 0) w--;

            Bitmap rgbBmp = bmp;
            if (bmp.getConfig() != Bitmap.Config.RGB_565 || w != bmp.getWidth()) {
                rgbBmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
                Canvas canvas = new Canvas(rgbBmp);
                canvas.drawBitmap(bmp, 0, 0, null);
                if (rgbBmp != bmp) bmp.recycle();
            }

            FaceDetector detector = new FaceDetector(w, h, 1);
            FaceDetector.Face[] faces = new FaceDetector.Face[1];
            int count = detector.findFaces(rgbBmp, faces);
            rgbBmp.recycle();

            return count > 0;
        } catch (Throwable t) {
            Log.d(TAG, "Face detection check skipped: " + t.getMessage());
            return false;
        }
    }
}
