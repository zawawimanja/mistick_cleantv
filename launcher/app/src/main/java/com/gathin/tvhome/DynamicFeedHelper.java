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

import org.json.JSONArray;
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
        public final String type;       // BERITA, SUKAN, EKONOMI, TAZKIRAH
        public final String source;     // e.g. "Google News", "BBC Football", "Hadith Sahih", "Al-Quran"
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
    private int totalShownCount = 0;
    private boolean isRunning = false;
    private View attachedRoot = null;

    // Set of seen headlines to guarantee no repeating in sequence
    private final java.util.Set<String> seenHeadlines = new java.util.LinkedHashSet<>();

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
        if (totalShownCount == 0) totalShownCount = 1;
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
        totalShownCount++;
        currentIndex = (currentIndex + 1) % feedItems.size();
        if (feedItems.size() - currentIndex <= 10) {
            fetchFeedsAsync();
        }
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
            index.setText("#" + totalShownCount + " • LIVE");
        }
        headline.setText(item.headline);
    }

    private void loadInitialData() {
        feedItems.clear();
        feedItems.add(new FeedItem("BERITA", "Utama", Color.parseColor("#D32F2F"),
                "Selamat datang ke Clean TV Launcher. Sistem beroperasi lancar."));
        feedItems.add(new FeedItem("EKONOMI", "Perniagaan", Color.parseColor("#E65100"),
                "Memperkasa keusahawanan dan ekonomi mampan komuniti."));
        feedItems.add(new FeedItem("SUKAN", "Bola Sepak", Color.parseColor("#2E7D32"),
                "Liga Perdana Inggeris & Liga Super Malaysia bersambung minggu ini."));
        List<FeedItem> tazkirahPool = getCuratedTazkirah();
        if (!tazkirahPool.isEmpty()) feedItems.add(tazkirahPool.get(0));
    }

    public void fetchFeedsAsync() {
        new Thread(() -> {
            try {
                List<FeedItem> newsUtama = fetchRssFeed("https://news.google.com/rss?hl=ms&gl=MY&ceid=MY:ms", "BERITA", "Utama", Color.parseColor("#D32F2F"), 45);
                List<FeedItem> dynamicQuran = fetchDynamicQuranAyahs(20);
                List<FeedItem> newsDunia = fetchRssFeed("https://news.google.com/rss/headlines/section/topic/WORLD?hl=ms&gl=MY&ceid=MY:ms", "DUNIA", "Antarabangsa", Color.parseColor("#C2185B"), 40);
                List<FeedItem> newsSukan = fetchRssFeed("https://news.google.com/rss/headlines/section/topic/SPORTS?hl=ms&gl=MY&ceid=MY:ms", "SUKAN", "Sukan", Color.parseColor("#2E7D32"), 40);
                List<FeedItem> sportsBbc = fetchBbcSports();
                List<FeedItem> newsBisnes = fetchRssFeed("https://news.google.com/rss/headlines/section/topic/BUSINESS?hl=ms&gl=MY&ceid=MY:ms", "EKONOMI", "Perniagaan", Color.parseColor("#E65100"), 40);
                List<FeedItem> newsTech = fetchRssFeed("https://news.google.com/rss/headlines/section/topic/TECHNOLOGY?hl=en-MY&gl=MY&ceid=MY:en", "TEKNOLOGI", "Teknologi", Color.parseColor("#0288D1"), 40);
                List<FeedItem> tazkirahPool = getCuratedTazkirah();

                List<FeedItem> candidates = new ArrayList<>();
                int maxLen = Math.max(newsUtama.size(), Math.max(newsDunia.size(), Math.max(newsSukan.size(), newsBisnes.size())));

                for (int i = 0; i < maxLen; i++) {
                    if (i < newsUtama.size()) candidates.add(newsUtama.get(i));
                    if (i < dynamicQuran.size()) candidates.add(dynamicQuran.get(i));
                    if (i < newsDunia.size()) candidates.add(newsDunia.get(i));
                    if (i < newsSukan.size()) candidates.add(newsSukan.get(i));
                    if (i < tazkirahPool.size()) candidates.add(tazkirahPool.get(i));
                    if (i < newsBisnes.size()) candidates.add(newsBisnes.get(i));
                    if (i < sportsBbc.size()) candidates.add(sportsBbc.get(i));
                    if (i < newsTech.size()) candidates.add(newsTech.get(i));
                }

                // Filter out already seen headlines (UNLIMITED & JANGAN ULANG)
                List<FeedItem> fresh = new ArrayList<>();
                for (FeedItem fi : candidates) {
                    if (fi != null && fi.headline != null && !fi.headline.isEmpty()) {
                        if (!seenHeadlines.contains(fi.headline)) {
                            seenHeadlines.add(fi.headline);
                            fresh.add(fi);
                        }
                    }
                }

                if (seenHeadlines.size() > 2000) {
                    seenHeadlines.clear();
                }

                if (!fresh.isEmpty()) {
                    mainHandler.post(() -> {
                        feedItems.addAll(fresh);
                        // Trim old items from head when queue grows beyond 100 to keep memory lean
                        if (currentIndex > 35 && feedItems.size() > 100) {
                            int toRemove = currentIndex - 5;
                            for (int r = 0; r < toRemove; r++) {
                                feedItems.remove(0);
                            }
                            currentIndex = 5;
                        }
                        renderCurrentItem(false);
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Error fetching live feeds: " + e.getMessage());
            }

            mainHandler.postDelayed(this::fetchFeedsAsync, REFRESH_INTERVAL_MS);
        }).start();
    }

    private List<FeedItem> fetchDynamicQuranAyahs(int count) {
        List<FeedItem> list = new ArrayList<>();
        java.util.Random rnd = new java.util.Random();
        int color = Color.parseColor("#00897B");
        for (int i = 0; i < count; i++) {
            int ayah = rnd.nextInt(6236) + 1;
            try {
                String json = httpGet("https://api.alquran.cloud/v1/ayah/" + ayah + "/editions/ms.basmeih", 4000);
                if (json != null) {
                    JSONObject root = new JSONObject(json);
                    JSONArray data = root.optJSONArray("data");
                    if (data != null && data.length() > 0) {
                        JSONObject item = data.getJSONObject(0);
                        String text = item.optString("text", "").trim();
                        JSONObject surah = item.optJSONObject("surah");
                        String surahName = surah != null ? surah.optString("englishName", "") : "";
                        int numInSurah = item.optInt("numberInSurah", 1);
                        if (!text.isEmpty() && text.length() <= 190) {
                            String headline = "\"" + text + "\" (Surah " + surahName + ": " + numInSurah + ")";
                            list.add(new FeedItem("TAZKIRAH", "Al-Quran (" + surahName + ")", color, headline));
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        return list;
    }

    private List<FeedItem> fetchRssFeed(String url, String badge, String defaultSource, int color, int limit) {
        List<FeedItem> list = new ArrayList<>();
        try {
            String xml = httpGet(url, 6000);
            if (xml != null && !xml.isEmpty()) {
                List<String> titles = parseRssTitles(xml, limit);
                for (String t : titles) {
                    String cleanTitle = t;
                    String srcName = defaultSource;
                    int lastDash = t.lastIndexOf(" - ");
                    if (lastDash > 0) {
                        cleanTitle = t.substring(0, lastDash).trim();
                        srcName = t.substring(lastDash + 3).trim();
                    }
                    if (!isExcluded(cleanTitle)) {
                        list.add(new FeedItem(badge, srcName, color, cleanTitle));
                    }
                }
            }
        } catch (Exception ignored) {}
        return list;
    }

    private boolean isExcluded(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        String[] excludedTerms = {
            // Politik & Kontroversi
            "politik", "parlimen", "dewan rakyat", "dewan negara", "dun",
            "pilihan raya", "pru", "prk", "prn", "parti", "pengundi", "undi",
            "perdana menteri", "menteri", "tpm", "kabinet", "kerajaan perpaduan",
            "pembangkang", "umno", "pkr", "pas", "dap", "bersatu", "amanah",
            "barisan nasional", "pakatan harapan", "perikatan nasional",
            "anwar ibrahim", "muhyiddin", "mahathir", "najib", "zahid hamidi",
            "hadi awang", "sanusi", "rafizi", "akmal saleh", "ahli parlimen",
            "adun", "usul", "sidang parlimen", "manifesto", "wakil rakyat",
            "presiden parti", "setiausaha agung", "parti politik",

            // Non-Syariah: Forex, spekulasi mata wang & kripto
            "forex", "foreign exchange", "kadar tukaran", "mata wang asing", "wang kripto",
            "kripto", "crypto", "cryptocurrency", "bitcoin", "ethereum", "nft",

            // Non-Syariah: Judi, kasino, loteri, pertaruhan
            "judi", "kasino", "casino", "pertaruhan", "taruhan", "loteri", "lottery",
            "nombor ekor", "magnum 4d", "sports toto", "damacai", "genting casino",
            "poker", "baccarat", "blackjack", "betting",

            // Non-Syariah: Riba, pinjaman haram/riba
            "riba", "ah long", "ceti haram", "pinjaman wang berlesen",

            // Non-Syariah: Arak, minuman keras, maksiat
            "arak", "alkohol", "minuman keras", "kelab malam", "pelacuran",

            // Penipuan / Skim
            "skim cepat kaya", "ponzi", "scam pelaburan"
        };
        for (String term : excludedTerms) {
            if (lower.contains(term)) return true;
        }
        return false;
    }

    private List<FeedItem> fetchBbcSports() {
        List<FeedItem> list = new ArrayList<>();
        try {
            String xml = httpGet("https://feeds.bbci.co.uk/sport/football/rss.xml", 6000);
            if (xml != null && !xml.isEmpty()) {
                List<String> titles = parseRssTitles(xml, 25);
                for (String t : titles) {
                    if (!isExcluded(t)) {
                        list.add(new FeedItem("SUKAN", "BBC Football", Color.parseColor("#2E7D32"), t));
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "Sports fetch skipped: " + e.getMessage());
        }
        return list;
    }

    private List<FeedItem> getCuratedTazkirah() {
        List<FeedItem> pool = new ArrayList<>();
        int qColor = Color.parseColor("#00897B");
        int hColor = Color.parseColor("#1565C0");

        pool.add(new FeedItem("TAZKIRAH", "Al-Quran", qColor,
                "\"Bersama setiap kesulitan ada kemudahan.\" (Al-Insyirah: 6)"));
        pool.add(new FeedItem("TAZKIRAH", "Al-Quran", qColor,
                "\"Hanya dengan mengingati Allah hati menjadi tenang.\" (Ar-Ra'd: 28)"));
        pool.add(new FeedItem("TAZKIRAH", "Al-Quran", qColor,
                "\"Bertawakallah kepada Allah. Cukuplah Dia sebagai Pengawas.\" (Al-Ahzab: 3)"));
        pool.add(new FeedItem("TAZKIRAH", "Al-Quran", qColor,
                "\"Mintalah pertolongan dengan sabar dan solat.\" (Al-Baqarah: 45)"));
        pool.add(new FeedItem("TAZKIRAH", "Al-Quran", qColor,
                "\"Sesungguhnya Allah beserta orang-orang yang sabar.\" (Al-Baqarah: 153)"));
        pool.add(new FeedItem("TAZKIRAH", "Al-Quran", qColor,
                "\"Allah tidak membebani seseorang melainkan sesuai kesanggupannya.\" (Al-Baqarah: 286)"));
        pool.add(new FeedItem("TAZKIRAH", "Al-Quran", qColor,
                "\"Janganlah berputus asa dari rahmat Allah.\" (Az-Zumar: 53)"));
        pool.add(new FeedItem("TAZKIRAH", "Al-Quran", qColor,
                "\"Barang siapa bertakwa, Allah bukakan jalan keluar baginya.\" (At-Talaq: 2)"));
        pool.add(new FeedItem("TAZKIRAH", "Al-Quran", qColor,
                "\"Solat mencegah dari perbuatan keji dan mungkar.\" (Al-'Ankabut: 45)"));
        pool.add(new FeedItem("TAZKIRAH", "Hadis", hColor,
                "\"Senyummu kepada saudaramu adalah sedekah.\" (HR. Tirmizi)"));
        pool.add(new FeedItem("TAZKIRAH", "Hadis", hColor,
                "\"Sebaik-baik manusia ialah yang paling bermanfaat bagi orang lain.\" (HR. Ahmad)"));
        pool.add(new FeedItem("TAZKIRAH", "Hadis", hColor,
                "\"Sesiapa menuntut ilmu, Allah mudahkan jalan ke syurga.\" (HR. Muslim)"));
        pool.add(new FeedItem("TAZKIRAH", "Hadis", hColor,
                "\"Setiap amal bergantung kepada niatnya.\" (HR. Bukhari & Muslim)"));

        // Shuffle so order is different every refresh cycle
        Collections.shuffle(pool);
        return pool;
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

