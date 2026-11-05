package com.aura.tvprobe;

import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AiRecommender {
    private static final String[] MODELS = {
        "gemini-3.1-flash-lite",
        "gemini-3.5-flash-lite",
        "gemini-3-flash-preview",
        "gemini-flash-lite-latest"
    };
    private final AuraDb db;

    public AiRecommender(AuraDb db) {
        this.db = db;
    }

    private String getApiKey() {
        return db.getSetting("gemini_api_key", "").trim();
    }

    public JSONArray getOrRefreshForProfile(String profileId, JSONObject nowPlaying, JSONArray history, boolean forceManual) {
        JSONArray cached = db.getProfileRecommendations(profileId);
        if (!forceManual && cached.length() > 0 && db.isRecoCacheFresh(profileId)) {
            return cached;
        }
        String prompt = buildProfilePrompt(profileId, nowPlaying, history);
        for (String model : MODELS) {
            JSONArray raw = callGemini(model, prompt);
            if (raw != null && raw.length() > 0) {
                JSONArray enriched = enrichAll(raw);
                db.saveProfileRecommendations(profileId, enriched);
                return enriched;
            }
        }
        if (cached.length() > 0) return cached;
        JSONArray openFallback = buildOpenSourceRecommendations(profileId);
        db.saveProfileRecommendations(profileId, openFallback);
        return openFallback;
    }

    private String buildProfilePrompt(String profileId, JSONObject np, JSONArray history) {
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < history.length() && seen.size() < 40; i++) {
            JSONObject h = history.optJSONObject(i);
            if (h == null) continue;
            String pkg = h.optString("app_pkg");
            if (pkg.contains("youtube")) continue;
            String t = h.optString("series_title").isEmpty() ? h.optString("title") : h.optString("series_title");
            if (!t.isEmpty() && !seen.contains(t)) seen.add(t + " (" + h.optString("app_name") + ")");
        }
        String persona = profilePersona(profileId);
        String cur = np != null ? (np.optString("series_title").isEmpty() ? np.optString("title") : np.optString("series_title")) : "";
        return "Active Household Profile: " + persona + ".\n" +
            "Currently/recently watched on TV: '" + cur + "'.\n" +
            "TV watch history: " + seen.toString() + ".\n" +
            "Recommend 14 specific next shows or movies not yet watched: include at least 4 Quick Watch / Short episodes (<30 min sitcoms/shorts) " +
            "and 10 across Sci-Fi/Thriller, Drama/Romance/K-Drama, and Action/Movies. " +
            "Return ONLY a JSON array of 14 objects with keys: 'title', 'app' (Prime Video, Netflix, Disney+ Hotstar, Apple TV+, ZEE5, or SmartTube), " +
            "'genres' (e.g. 'Sci-Fi, Thriller' or 'Comedy, Short' or 'Drama, Romance' or 'Action, Crime'), " +
            "'runtime_min' (integer minutes per episode or movie, e.g. 22 for short sitcoms, 55 for drama, 120 for movie), and 'reason' (max 16 words).";
    }

    private String profilePersona(String profileId) {
        String name = db.getProfileLabel(profileId);
        if ("partner".equals(profileId)) return name + " (loves K-dramas like Marry My Husband, emotional romance, suspense thrillers, feel-good series)";
        if ("member3".equals(profileId)) return name + " (loves Indian web series, crime thrillers, Gullak, Rocket Boys, biopics, Bollywood blockbusters)";
        if ("kids".equals(profileId)) return name + " (animated adventures, family movies, comedy shorts under 25 mins, sci-fi adventure)";
        if ("family".equals(profileId)) return name + " (crowd-pleasing movies, Indian cinema, comedy sitcoms, mystery adventures everyone enjoys together)";
        return name + " (loves mind-bending Sci-Fi like Severance/Dark Matter/Foundation/Matrix, high-octane action like Rush/Sisu, and top K-dramas)";
    }

    private JSONArray buildOpenSourceRecommendations(String profileId) {
        String[] seeds = "partner".equals(profileId)
            ? new String[]{"Lovely Runner", "The Glory", "Crash Landing on You", "Brooklyn Nine-Nine", "Modern Family", " Vincenzo", "Perfect Marriage Revenge", " Pachinko"}
            : "kids".equals(profileId)
            ? new String[]{"Avatar: The Last Airbender", "Gravity Falls", "Bluey", "Spider-Man", "Paddington", "Young Sheldon", "The Mitchells vs. the Machines", "Sonic Prime"}
            : new String[]{"Dark", "Silo", "The Family Man", "Brooklyn Nine-Nine", "The Office", "Reborn Rich", "Special Ops", "The Bear", "Farzi", "Slow Horses"};
        JSONArray raw = new JSONArray();
        for (String s : seeds) {
            try {
                JSONObject o = new JSONObject();
                o.put("title", s);
                o.put("app", "Prime Video / Netflix");
                o.put("reason", "Open-source TVMaze & IMDb graph match for " + profileId + " profile.");
                raw.put(o);
            } catch (Exception ignored) {}
        }
        return enrichAll(raw);
    }

    public JSONArray enrichAll(JSONArray raw) {
        JSONArray out = new JSONArray();
        if (raw == null) return out;
        int n = raw.length();
        final JSONObject[] results = new JSONObject[n];
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(6);
        for (int i = 0; i < n; i++) {
            final int idx = i;
            final JSONObject p = raw.optJSONObject(i);
            if (p == null) continue;
            pool.submit(() -> {
                String title = p.optString("title");
                String app = p.optString("app", "Prime Video");
                JSONObject meta = fetchOpenMetadata(title);
                JSONObject card = new JSONObject();
                try {
                    card.put("title", title);
                    card.put("badge", app);
                    card.put("app", app);
                    card.put("reason", p.optString("reason"));
                    card.put("poster_url", meta.optString("poster_url"));
                    String genres = meta.optString("genres", p.optString("genres", p.optString("genre", "Drama")));
                    card.put("genres", genres);
                    int rt = p.optInt("runtime_min", meta.optInt("runtime_min", 45));
                    card.put("runtime_min", rt);
                    card.put("rating", meta.optString("rating", "★ 8.2"));
                    String nativeUri = db.findNativeLaunchUri(title);
                    String nativePkg = AuraDb.resolveAppPackage(app);
                    card.put("launch_uri", nativeUri);
                    card.put("launch_pkg", nativePkg);
                    results[idx] = card;
                } catch (Exception ignored) {}
            });
        }
        pool.shutdown();
        try { pool.awaitTermination(8, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignored) {}
        for (JSONObject r : results) {
            if (r != null) out.put(r);
        }
        return out;
    }

    public JSONObject fetchOpenMetadata(String title) {
        JSONObject res = new JSONObject();
        if (title == null || title.isEmpty()) return res;
        String cacheKey = "imdb_meta_v2:" + title.trim().toLowerCase();
        String cached = db.getCachedGti(cacheKey);
        if (cached != null) {
            try { return new JSONObject(cached); } catch (Exception ignored) {}
        }
        // 1. Query IMDb Suggestion API first (covers both Movies and TV Shows accurately by popularity)
        try {
            String clean = title.trim().toLowerCase().replaceAll("[^a-z0-9 ]", "").replaceAll("\\s+", "_");
            char first = clean.isEmpty() ? 'x' : clean.charAt(0);
            String imdbUrl = "https://v3.sg.media-imdb.com/suggestion/" + first + "/" + Uri.encode(clean) + ".json";
            JSONObject imdb = new JSONObject(httpGet(imdbUrl));
            JSONArray d = imdb.optJSONArray("d");
            if (d != null) {
                for (int k = 0; k < d.length(); k++) {
                    JSONObject item = d.optJSONObject(k);
                    if (item == null) continue;
                    JSONObject iObj = item.optJSONObject("i");
                    if (iObj != null && !iObj.optString("imageUrl").isEmpty()) {
                        res.put("poster_url", iObj.optString("imageUrl").replace("._V1_.jpg", "._V1_UX360_.jpg"));
                        break;
                    }
                }
            }
        } catch (Exception ignored) {}

        // 2. Query TVMaze only for exact-name TV show metadata (genres / rating) or fallback poster
        try {
            String tvmJson = httpGet("https://api.tvmaze.com/singlesearch/shows?q=" + Uri.encode(title));
            JSONObject data = new JSONObject(tvmJson);
            if (title.trim().equalsIgnoreCase(data.optString("name").trim())) {
                if (res.optString("poster_url").isEmpty()) {
                    JSONObject img = data.optJSONObject("image");
                    if (img != null) res.put("poster_url", img.optString("medium", img.optString("original")));
                }
                JSONArray g = data.optJSONArray("genres");
                if (g != null && g.length() > 0) res.put("genres", g.join(", ").replace("\"", ""));
                int rt = data.optInt("averageRuntime", data.optInt("runtime", 45));
                if (rt > 0) res.put("runtime_min", rt);
                JSONObject rat = data.optJSONObject("rating");
                if (rat != null && !rat.isNull("average")) res.put("rating", "★ " + rat.optDouble("average"));
            }
        } catch (Exception ignored) {}

        if (res.optString("poster_url").isEmpty() && title.contains(":")) {
            JSONObject baseMeta = fetchOpenMetadata(title.split(":")[0].trim());
            if (!baseMeta.optString("poster_url").isEmpty()) {
                try { res.put("poster_url", baseMeta.optString("poster_url")); } catch (Exception ignored) {}
            }
        }
        if (!res.optString("poster_url").isEmpty()) {
            db.saveCachedGti(cacheKey, res.toString());
        }
        return res;
    }

    public JSONObject resolvePrimeGti(String gti) {
        try {
            String cached = db.getCachedGti(gti);
            if (cached != null) return new JSONObject(cached);

            String html = httpGet("https://www.primevideo.com/detail/" + gti);
            JSONObject info = new JSONObject();
            String parent = extractRegex(html, "\"parentTitle\"\\s*:\\s*\"([^\"]+)\"");
            String epTitle = extractRegex(html, Pattern.quote(gti) + "\":\\{\"title\":\"([^\"]+)\"");
            String syn = extractRegex(html, Pattern.quote(gti) + "\":\\{\"title\":\"[^\"]+\",\"synopsis\":\"([^\"]+)\"");
            String seq = extractRegex(html, Pattern.quote(gti) + "\",\"isLaunched\":true,\"link\":\"[^\"]+\",\"sequenceNumber\":(\\d+)");

            info.put("series_title", parent.isEmpty() ? "Prime Video" : parent);
            info.put("title", epTitle.isEmpty() ? info.optString("series_title") : epTitle);
            info.put("synopsis", syn);
            if (!seq.isEmpty()) info.put("episode", Integer.parseInt(seq));
            info.put("season", 1);

            JSONObject tv = fetchOpenMetadata(info.optString("series_title"));
            info.put("poster_url", tv.optString("poster_url"));
            info.put("genres", tv.optString("genres", "Drama"));
            info.put("runtime_min", tv.optInt("runtime_min", 60));

            db.saveCachedGti(gti, info.toString());
            return info;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public String callGeminiJson(String prompt) {
        String apiKey = getApiKey();
        if (apiKey.isEmpty()) return null;
        for (String model : MODELS) {
            try {
                String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + apiKey;
                JSONObject body = new JSONObject();
                JSONArray contents = new JSONArray().put(new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", prompt))));
                body.put("contents", contents);
                body.put("generationConfig", new JSONObject().put("responseMimeType", "application/json").put("temperature", 0.65));
                String resp = httpPost(url, body.toString());
                JSONObject root = new JSONObject(resp);
                return root.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text");
            } catch (Exception ignored) {}
        }
        return null;
    }

    private JSONArray callGemini(String model, String prompt) {
        String apiKey = getApiKey();
        if (apiKey.isEmpty()) return null;
        try {
            String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + apiKey;
            JSONObject body = new JSONObject();
            JSONArray contents = new JSONArray().put(new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", prompt))));
            body.put("contents", contents);
            body.put("generationConfig", new JSONObject().put("responseMimeType", "application/json").put("temperature", 0.6));

            String resp = httpPost(url, body.toString());
            JSONObject root = new JSONObject(resp);
            String txt = root.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text");
            return new JSONArray(txt);
        } catch (Exception e) {
            return null;
        }
    }

    private String extractRegex(String text, String pattern) {
        Matcher m = Pattern.compile(pattern).matcher(text);
        return m.find() ? m.group(1) : "";
    }

    private String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    private String httpPost(String urlStr, String jsonBody) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(12000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }
        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }
}
