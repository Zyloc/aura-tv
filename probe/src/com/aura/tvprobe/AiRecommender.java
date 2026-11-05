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
        "gemini-3.1-flash-lite-preview",
        "gemini-flash-lite-latest",
        "gemini-2.5-flash"
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
                if (enriched.length() > 0) {
                    db.saveProfileRecommendations(profileId, enriched);
                    return enriched;
                }
            }
        }
        return cached;
    }

    private String buildProfilePrompt(String profileId, JSONObject np, JSONArray history) {
        List<String> seen = new ArrayList<>();
        JSONArray manual = db.listManuallyWatched();
        for (int i = 0; i < manual.length(); i++) {
            JSONObject m = manual.optJSONObject(i);
            if (m != null) {
                String t = m.optString("series_title").isEmpty() ? m.optString("title") : m.optString("series_title");
                if (!t.isEmpty() && !seen.contains(t)) seen.add(t);
            }
        }
        for (int i = 0; i < history.length() && seen.size() < 45; i++) {
            JSONObject h = history.optJSONObject(i);
            if (h == null) continue;
            if (h.optString("app_pkg").contains("youtube")) continue;
            String t = h.optString("series_title").isEmpty() ? h.optString("title") : h.optString("series_title");
            if (!t.isEmpty() && !seen.contains(t)) seen.add(t);
        }

        String profileName = db.getProfileLabel(profileId);
        String customTaste = db.getProfileTaste(profileId);
        String ratingsFeedback = db.getRatingFeedbackSummary();
        List<String> activeFilters = db.getActiveFilters(profileId);
        List<String> cfPool = fetchCollaborativeCandidateNames(profileId);
        String cur = np != null ? (np.optString("series_title").isEmpty() ? np.optString("title") : np.optString("series_title")) : "";

        return "You are Aura, an expert personalized TV & movie recommendation engine.\n" +
            "Active Profile Name: '" + profileName + "' (profile_id: " + profileId + ").\n" +
            "User's Explicit Taste Guidance / Interests for this profile: " +
            (customTaste.isEmpty() ? "(Infer appropriate taste from Profile Name, Ratings, and Watch History)" : "\"" + customTaste + "\"") + "\n" +
            "User's 1-10 Show Ratings Feedback: " + (ratingsFeedback.isEmpty() ? "None yet" : ratingsFeedback) + "\n" +
            "Active UI Filters: " + (activeFilters.isEmpty() ? "All categories" : activeFilters.toString()) + "\n" +
            "Currently/recently active on TV: '" + cur + "'.\n" +
            "Already watched (STRICT EXCLUSION — NEVER recommend any of these): " + seen.toString() + ".\n" +
            "Deterministic Candidate Pool (verified titles streaming in India from JustWatch/IMDb APIs): " + cfPool.toString() + ".\n\n" +
            "Task (LLM Heavy Lifting):\n" +
            "1. Filter out any watched titles, anything similar to low-rated titles (<=5/10), or anything violating active filters.\n" +
            "2. Select & rank the 14 best shows or movies (prioritizing high-affinity matches from the Deterministic Candidate Pool plus top critically acclaimed matches for their interests).\n" +
            "3. For EVERY title, write a specific, compelling 'reason' (1-2 sentences, max 24 words) explaining WHY this specific user should watch it and WHY it is recommended to them based on their watched shows, 1-10 ratings, or interests.\n" +
            "Return ONLY a JSON array of 14 objects with keys: 'title', 'app' (Disney+ Hotstar, Netflix, Prime Video, Apple TV+, SonyLIV, or ZEE5), " +
            "'genres' (comma-separated), 'runtime_min' (integer minutes), and 'reason' (personalized explanation of why they should watch it).";
    }

    public List<String> fetchCollaborativeCandidateNames(String profileId) {
        List<String> dynamicAnchors = db.getDynamicTasteAnchors(profileId);
        List<String> names = new ArrayList<>();
        for (String seed : dynamicAnchors) {
            JSONArray sim = JustWatchGraph.fetchCollaborativeSimilar(seed);
            for (int i = 0; i < sim.length() && i < 6; i++) {
                JSONObject o = sim.optJSONObject(i);
                if (o != null && !o.optString("title").isEmpty() && !db.isTitleWatched(o.optString("title"))) {
                    String t = o.optString("title");
                    db.saveCachedGti("jw_imdb_v3:" + t.trim().toLowerCase(), o.toString());
                    names.add(t + " [" + o.optString("genres") + ", " + o.optString("rating") + ", " + o.optString("app", "Streaming") + "]");
                }
            }
        }
        String customInterests = db.getProfileTaste(profileId);
        if (!customInterests.isEmpty()) {
            for (String part : customInterests.split(",")) {
                String kw = part.trim();
                if (kw.isEmpty()) continue;
                JSONArray searchHits = JustWatchGraph.fetchDeterministicSearchCandidates(kw);
                for (int i = 0; i < searchHits.length() && i < 5; i++) {
                    JSONObject o = searchHits.optJSONObject(i);
                    if (o != null && !o.optString("title").isEmpty() && !db.isTitleWatched(o.optString("title"))) {
                        String t = o.optString("title");
                        db.saveCachedGti("jw_imdb_v3:" + t.trim().toLowerCase(), o.toString());
                        String entry = t + " [" + o.optString("genres") + ", " + o.optString("rating") + ", " + o.optString("app", "Streaming") + "]";
                        if (!names.contains(entry)) names.add(entry);
                    }
                }
            }
        }
        return names;
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
                JSONObject meta = fetchOpenMetadata(title);
                String verifiedApp = !meta.optString("app").isEmpty() ? meta.optString("app") : p.optString("app", "Streaming");
                JSONObject card = new JSONObject();
                try {
                    card.put("title", title);
                    card.put("badge", verifiedApp);
                    card.put("app", verifiedApp);
                    card.put("year", meta.optInt("year", 2024));
                    card.put("type", meta.optString("type", "Series"));
                    String reason = p.optString("reason");
                    if (reason.isEmpty()) reason = meta.optString("synopsis");
                    card.put("reason", reason);
                    card.put("synopsis", !meta.optString("synopsis").isEmpty() ? meta.optString("synopsis") : reason);
                    card.put("poster_url", meta.optString("poster_url"));
                    String genres = !meta.optString("genres").isEmpty()
                        ? meta.optString("genres")
                        : p.optString("genres", p.optString("genre", "Drama"));
                    card.put("genres", genres);
                    int rt = meta.optInt("runtime_min", p.optInt("runtime_min", 45));
                    card.put("runtime_min", rt);
                    card.put("rating", meta.optString("rating", p.optString("rating", "8.2")));
                    String deepUri = !meta.optString("launch_uri").isEmpty()
                        ? meta.optString("launch_uri")
                        : db.findNativeLaunchUri(title);
                    String nativePkg = !meta.optString("launch_pkg").isEmpty()
                        ? meta.optString("launch_pkg")
                        : AuraDb.resolveAppPackage(verifiedApp);
                    card.put("launch_uri", deepUri);
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
        String cacheKey = "jw_imdb_v3:" + title.trim().toLowerCase();
        String cached = db.getCachedGti(cacheKey);
        if (cached != null) {
            try { return new JSONObject(cached); } catch (Exception ignored) {}
        }
        res = JustWatchGraph.lookupTitleInIndia(title);

        if (res.optString("poster_url").isEmpty()) {
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
            String text = root.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text");
            return new JSONArray(text);
        } catch (Exception e) {
            return null;
        }
    }

    private String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(4500);
        conn.setReadTimeout(4500);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64)");
        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    private String httpPost(String urlStr, String jsonBody) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(8000);
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

    private String extractRegex(String src, String regex) {
        if (src == null) return "";
        Matcher m = Pattern.compile(regex).matcher(src);
        return m.find() ? m.group(1) : "";
    }
}
