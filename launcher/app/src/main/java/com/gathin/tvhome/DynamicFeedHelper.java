package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Xml;
import android.view.View;
import android.widget.TextView;

import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DynamicFeedHelper {
    private static final String TAG = "TvHome.Feed";
    private static final String PREFS_NAME = "dynamic_feed_cache";
    private static final long ROTATE_INTERVAL_MS = 9000; // 9 seconds
    private static final long REFRESH_INTERVAL_MS = 25 * 60 * 1000; // 25 minutes

    private static volatile DynamicFeedHelper sInstance;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;

    public static class FeedItem {
        public final String type;       // BERITA, SUKAN, PASARAN, TAZKIRAH
        public final String source;     // e.g. "Google News", "BBC Football", "Live Forex", "Hadith Sahih"
        public final int badgeColor;    // e.g. Color.RED
        public final String headline;

        public FeedItem(String type, String source, int badgeColor, String headline) {
            this.type = type;
            this.source = source;
            this.badgeColor = badgeColor;
            this.headline = headline;
        }
    }

    private final List<FeedItem> feedItems = new ArrayList<>();
    private int currentIndex = 0;
    private boolean isRunning = false;
    private View attachedRoot = null;

    private final Runnable rotateRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;
            showNextItem();
            mainHandler.postDelayed(this, ROTATE_INTERVAL_MS);
        }
    };

    public static DynamicFeedHelper getInstance(Context context) {
        if (sInstance == null) {
            synchronized (DynamicFeedHelper.class) {
                if (sInstance == null) {
                    sInstance = new DynamicFeedHelper(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    private DynamicFeedHelper(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        loadInitialData();
        fetchFeedsAsync();
    }

    public void attachView(View root) {
        this.attachedRoot = root;
        renderCurrentItem(false);
        resume();
    }

    public void resume() {
        if (!isRunning) {
            isRunning = true;
            mainHandler.removeCallbacks(rotateRunnable);
            mainHandler.postDelayed(rotateRunnable, ROTATE_INTERVAL_MS);
            renderCurrentItem(false);
        }
    }

    public void pause() {
        isRunning = false;
        mainHandler.removeCallbacks(rotateRunnable);
    }

    public void destroy() {
        pause();
        attachedRoot = null;
    }

    private void showNextItem() {
        if (feedItems.isEmpty()) return;
        currentIndex = (currentIndex + 1) % feedItems.size();
        renderCurrentItem(true);
    }

    private void renderCurrentItem(boolean animate) {
        if (attachedRoot == null || feedItems.isEmpty()) return;

        View card = attachedRoot.findViewById(R.id.feedCard);
        TextView badge = attachedRoot.findViewById(R.id.feedBadge);
        TextView source = attachedRoot.findViewById(R.id.feedSource);
        TextView index = attachedRoot.findViewById(R.id.feedIndex);
        TextView headline = attachedRoot.findViewById(R.id.feedHeadline);

        if (card == null || badge == null || source == null || headline == null) return;

        final FeedItem item = feedItems.get(currentIndex);

        if (animate) {
            card.animate().alpha(0.15f).setDuration(180).withEndAction(() -> {
                applyItemData(item, badge, source, index, headline);
                card.animate().alpha(1.0f).setDuration(220).start();
            }).start();
        } else {
            applyItemData(item, badge, source, index, headline);
            card.setAlpha(1.0f);
        }
    }

    private void applyItemData(FeedItem item, TextView badge, TextView source, TextView index, TextView headline) {
        badge.setText(item.type);
        try {
            GradientDrawable gd = (GradientDrawable) badge.getBackground();
            if (gd != null) {
                gd.setColor(item.badgeColor);
            }
        } catch (Exception ignored) {}

        source.setText(item.source);
        if (index != null) {
            index.setText((currentIndex + 1) + "/" + feedItems.size());
        }
        headline.setText(item.headline);
    }

    private void loadInitialData() {
        // Default preloaded items so the UI is immediately alive
        feedItems.clear();
        feedItems.add(new FeedItem("BERITA", "Utama", Color.parseColor("#D32F2F"),
                "Selamat datang ke Clean TV Launcher Tanix TX6. Sistem beroperasi lancar."));
        feedItems.add(new FeedItem("PASARAN", "Forex Semasa", Color.parseColor("#F57F17"),
                "USD/MYR: 4.42 | SGD/MYR: 3.31 | EUR/MYR: 4.78"));
        feedItems.add(new FeedItem("SUKAN", "Bola Sepak", Color.parseColor("#2E7D32"),
                "Liga Perdana Inggeris & Liga Super Malaysia bersambung minggu ini."));
        feedItems.add(new FeedItem("TAZKIRAH", "Al-Quran", Color.parseColor("#00897B"),
                "\"Sesungguhnya bersama setiap kesulitan ada kemudahan.\" (Surah Al-Insyirah: 6)"));
    }

    public void fetchFeedsAsync() {
        new Thread(() -> {
            try {
                List<FeedItem> newsItems = fetchGoogleNews();
                List<FeedItem> sportsItems = fetchBbcSports();
                FeedItem forexItem = fetchForexRate();
                List<FeedItem> tazkirahItems = getCuratedTazkirah();

                List<FeedItem> combined = new ArrayList<>();

                int maxCount = Math.max(newsItems.size(), sportsItems.size());
                for (int i = 0; i < maxCount; i++) {
                    if (i < newsItems.size()) combined.add(newsItems.get(i));
                    if (i < sportsItems.size()) combined.add(sportsItems.get(i));
                    if (i == 0 && forexItem != null) combined.add(forexItem);
                    if (i < tazkirahItems.size()) combined.add(tazkirahItems.get(i));
                }

                if (!combined.isEmpty()) {
                    mainHandler.post(() -> {
                        feedItems.clear();
                        feedItems.addAll(combined);
                        if (currentIndex >= feedItems.size()) currentIndex = 0;
                        renderCurrentItem(false);
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Error fetching live feeds: " + e.getMessage());
            }

            // Schedule next refresh
            mainHandler.postDelayed(this::fetchFeedsAsync, REFRESH_INTERVAL_MS);
        }).start();
    }

    private List<FeedItem> fetchGoogleNews() {
        List<FeedItem> list = new ArrayList<>();
        try {
            // Main news feed (filtered to exclude any politics)
            String xml = httpGet("https://news.google.com/rss?hl=ms&gl=MY&ceid=MY:ms", 6000);
            if (xml != null && !xml.isEmpty()) {
                List<String> titles = parseRssTitles(xml, 25);
                for (String t : titles) {
                    if (list.size() >= 5) break;
                    String cleanTitle = t;
                    String srcName = "Berita Semasa";
                    int lastDash = t.lastIndexOf(" - ");
                    if (lastDash > 0) {
                        cleanTitle = t.substring(0, lastDash).trim();
                        srcName = t.substring(lastDash + 3).trim();
                    }
                    if (!isPolitical(cleanTitle)) {
                        list.add(new FeedItem("BERITA", srcName, Color.parseColor("#D32F2F"), cleanTitle));
                    }
                }
            }
            // If less than 4 items after filtering politics, supplement with technology/science news
            if (list.size() < 4) {
                String techXml = httpGet("https://news.google.com/rss/headlines/section/topic/TECHNOLOGY?hl=ms&gl=MY&ceid=MY:ms", 5000);
                if (techXml != null && !techXml.isEmpty()) {
                    List<String> techTitles = parseRssTitles(techXml, 10);
                    for (String t : techTitles) {
                        if (list.size() >= 5) break;
                        String cleanTitle = t;
                        String srcName = "Teknologi & Sains";
                        int lastDash = t.lastIndexOf(" - ");
                        if (lastDash > 0) {
                            cleanTitle = t.substring(0, lastDash).trim();
                            srcName = t.substring(lastDash + 3).trim();
                        }
                        if (!isPolitical(cleanTitle)) {
                            list.add(new FeedItem("BERITA", srcName, Color.parseColor("#D32F2F"), cleanTitle));
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "News fetch skipped: " + e.getMessage());
        }
        return list;
    }

    private boolean isPolitical(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        String[] politicalTerms = {
            "politik", "parlimen", "dewan rakyat", "dewan negara", "dun",
            "pilihan raya", "pru", "prk", "prn", "parti", "pengundi", "undi",
            "perdana menteri", "menteri", "tpm", "kabinet", "kerajaan perpaduan",
            "pembangkang", "umno", "pkr", "pas", "dap", "bersatu", "amanah",
            "barisan nasional", "pakatan harapan", "perikatan nasional",
            "anwar ibrahim", "muhyiddin", "mahathir", "najib", "zahid hamidi",
            "hadi awang", "sanusi", "rafizi", "akmal saleh", "ahli parlimen",
            "adun", "usul", "sidang parlimen", "manifesto", "wakil rakyat",
            "presiden parti", "setiausaha agung", "parti politik"
        };
        for (String term : politicalTerms) {
            if (lower.contains(term)) return true;
        }
        return false;
    }

    private List<FeedItem> fetchBbcSports() {
        List<FeedItem> list = new ArrayList<>();
        try {
            String xml = httpGet("https://feeds.bbci.co.uk/sport/football/rss.xml", 6000);
            if (xml != null && !xml.isEmpty()) {
                List<String> titles = parseRssTitles(xml, 4);
                for (String t : titles) {
                    list.add(new FeedItem("SUKAN", "BBC Football", Color.parseColor("#2E7D32"), t));
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "Sports fetch skipped: " + e.getMessage());
        }
        return list;
    }

    private FeedItem fetchForexRate() {
        try {
            String jsonStr = httpGet("https://open.er-api.com/v6/latest/USD", 6000);
            if (jsonStr != null) {
                JSONObject obj = new JSONObject(jsonStr);
                JSONObject rates = obj.optJSONObject("rates");
                if (rates != null) {
                    double myr = rates.optDouble("MYR", 0.0);
                    double sgd = rates.optDouble("SGD", 0.0);
                    double eur = rates.optDouble("EUR", 0.0);

                    if (myr > 0) {
                        double sgdToMyr = (sgd > 0) ? (myr / sgd) : 0;
                        double eurToMyr = (eur > 0) ? (myr / eur) : 0;
                        String text = String.format(java.util.Locale.US,
                                "USD/MYR: %.2f | SGD/MYR: %.2f | EUR/MYR: %.2f", myr, sgdToMyr, eurToMyr);
                        return new FeedItem("PASARAN", "Kadar Tukaran Semasa", Color.parseColor("#F57F17"), text);
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "Forex fetch skipped: " + e.getMessage());
        }
        return null;
    }

    private List<FeedItem> getCuratedTazkirah() {
        List<FeedItem> list = new ArrayList<>();
        int color = Color.parseColor("#00897B");
        list.add(new FeedItem("TAZKIRAH", "Al-Quran", color,
                "\"Sesungguhnya bersama setiap kesulitan ada kemudahan.\" (Surah Al-Insyirah: 6)"));
        list.add(new FeedItem("TAZKIRAH", "Al-Quran", color,
                "\"Ingatlah, hanya dengan mengingati Allah hati menjadi tenang.\" (Surah Ar-Ra'd: 28)"));
        list.add(new FeedItem("TAZKIRAH", "Al-Quran", color,
                "\"Dan bertawakallah kepada Allah. Dan cukuplah Allah sebagai Pengawas.\" (Surah Al-Ahzab: 3)"));
        list.add(new FeedItem("TAZKIRAH", "Al-Quran", color,
                "\"Dan mintalah pertolongan (kepada Allah) dengan sabar dan solat.\" (Surah Al-Baqarah: 45)"));
        list.add(new FeedItem("TAZKIRAH", "Al-Quran", color,
                "\"Dan apabila hamba-hamba-Ku bertanya kepadamu tentang Aku, maka sungguh Aku dekat.\" (Surah Al-Baqarah: 186)"));
        list.add(new FeedItem("TAZKIRAH", "Al-Quran", color,
                "\"Cukuplah Allah bagi kami, dan Dia adalah sebaik-baik Pelindung.\" (Surah Ali 'Imran: 173)"));
        list.add(new FeedItem("TAZKIRAH", "Al-Quran", color,
                "\"Dan Dia mendapati engkau mencari-cari (jalan), lalu Dia memberikan petunjuk.\" (Surah Ad-Dhuha: 7)"));
        list.add(new FeedItem("TAZKIRAH", "Al-Quran", color,
                "\"Maka bersabarlah engkau dengan kesabaran yang baik.\" (Surah Al-Ma'arij: 5)"));
        return list;
    }

    private List<String> parseRssTitles(String xml, int limit) {
        List<String> titles = new ArrayList<>();
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(new StringReader(xml));
            int eventType = parser.getEventType();
            boolean insideItem = false;

            while (eventType != XmlPullParser.END_DOCUMENT && titles.size() < limit) {
                String name = parser.getName();
                if (eventType == XmlPullParser.START_TAG) {
                    if ("item".equalsIgnoreCase(name)) {
                        insideItem = true;
                    } else if (insideItem && "title".equalsIgnoreCase(name)) {
                        String titleText = parser.nextText();
                        if (titleText != null && !titleText.trim().isEmpty()) {
                            titles.add(titleText.trim());
                        }
                    }
                } else if (eventType == XmlPullParser.END_TAG) {
                    if ("item".equalsIgnoreCase(name)) {
                        insideItem = false;
                    }
                }
                eventType = parser.next();
            }
        } catch (Exception e) {
            Log.d(TAG, "XML parse error: " + e.getMessage());
        }
        return titles;
    }

    private String httpGet(String urlString, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android TV; Tanix TX6)");

            if (conn.getResponseCode() == 200) {
                InputStream is = conn.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(is));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                reader.close();
                return sb.toString();
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }
}

