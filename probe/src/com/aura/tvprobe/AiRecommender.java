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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
        if (!forceManual && cached.length() >= 10 && db.isRecoCacheFresh(profileId)) {
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
        String personalGuide = db.getProfileTaste(profileId);
        String defaultGuide = db.getDefaultTasteGuide();
        String ratingsFeedback = db.getRatingFeedbackSummary();
        List<String> activeFilters = db.getActiveFilters(profileId);
        List<String> cfPool = fetchCollaborativeCandidateNames(profileId);
        String cur = np != null ? (np.optString("series_title").isEmpty() ? np.optString("title") : np.optString("series_title")) : "";

        return "You are Aura, a deeply personalized movie & TV recommendation engine for Android TV in India.\n" +
            "Active Profile: '" + profileName + "' (profile_id: " + profileId + ").\n" +
            "1. PERSONAL GUIDE AI (Highest Priority — specific to " + profileName + "): " +
            (personalGuide.isEmpty() ? "(None set yet — rely on Default Guide AI, 1-10 Ratings, and Watch History)" : "\"" + personalGuide + "\"") + "\n" +
            "2. DEFAULT GUIDE AI (Global Household Baseline): \"" + defaultGuide + "\"\n" +
            "3. USER'S 1-10 RATINGS FEEDBACK: " + (ratingsFeedback.isEmpty() ? "None yet" : ratingsFeedback) + "\n" +
            "4. ACTIVE UI FILTERS: " + (activeFilters.isEmpty() ? "All categories" : activeFilters.toString()) + "\n" +
            "5. RECENTLY ACTIVE ON TV: '" + cur + "'.\n" +
            "6. STRICT WATCHED EXCLUSION LIST (NEVER recommend any of these): " + seen.toString() + ".\n" +
            "7. VERIFIED COLLABORATIVE CANDIDATES STREAMING IN INDIA: " + cfPool.toString() + ".\n\n" +
            "STRICT CURATION RULES:\n" +
            "- ONLY recommend ALREADY-RELEASED movies or series (release year <= 2025) that are currently streaming on subscription OTT in India (Netflix, Disney+ Hotstar, Prime Video, Apple TV+, SonyLIV, or ZEE5).\n" +
            "- NEVER recommend unreleased/upcoming 2026 or 2027 titles (such as 'The Batman: Part II', 'The Odyssey', 'Project Hail Mary', 'The Hunt for Gollum', 'Beyond the Spider-Verse').\n" +
            "- Prioritize Personal Guide AI first, then Default Guide AI, then high-affinity matches to their 8-10/10 rated shows.\n" +
            "- Ensure strong variety across 18 picks (mixing top matches for their personal taste, critically acclaimed thrillers/sci-fi/dramas, and hidden gems with IMDb >= 7.5).\n" +
            "- For EVERY title, write a natural, specific 1-2 sentence 'reason' (18-28 words) explaining WHY '" + profileName + "' will love it based on their Personal Guide AI, Default Guide AI, or watch history.\n" +
            "Return ONLY a JSON array of 18 objects with keys: 'title', 'app' (Disney+ Hotstar, Netflix, Prime Video, Apple TV+, SonyLIV, or ZEE5), " +
            "'genres' (comma-separated), 'runtime_min' (integer minutes), and 'reason' (personalized explanation).";
    }

    public List<String> fetchCollaborativeCandidateNames(String profileId) {
        List<String> dynamicAnchors = db.getDynamicTasteAnchors(profileId);
        List<String> names = new ArrayList<>();
        Set<String> seenNorm = new HashSet<>();
        for (String seed : dynamicAnchors) {
            if (seed == null || seed.trim().isEmpty()) continue;
            JSONArray sim = JustWatchGraph.fetchCollaborativeSimilar(seed);
            for (int i = 0; i < sim.length() && i < 6; i++) {
                JSONObject o = sim.optJSONObject(i);
                if (o == null) continue;
                String t = o.optString("title");
                String norm = AuraDb.normalizeTitleKey(t);
                if (!t.isEmpty() && !seenNorm.contains(norm) && !db.isTitleWatched(t)
                    && o.optInt("year", 9999) <= JustWatchGraph.MAX_RELEASED_YEAR
                    && o.optBoolean("has_flatrate_offer", false)) {
                    seenNorm.add(norm);
                    db.saveCachedGti("jw_imdb_v4:" + t.trim().toLowerCase(), o.toString());
                    names.add(t + " (" + o.optInt("year") + ", " + o.optString("genres") + ", " + o.optString("rating") + ", " + o.optString("app", "Streaming") + ")");
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
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        for (int i = 0; i < n; i++) {
            final int idx = i;
            final JSONObject p = raw.optJSONObject(i);
            if (p == null) continue;
            pool.submit(() -> {
                String title = p.optString("title").trim();
                if (title.isEmpty() || db.isTitleWatched(title)) return;
                JSONObject meta = fetchOpenMetadata(title);
                int year = meta.optInt("year", p.optInt("year", 2023));
                // Strictly reject unreleased future titles (2026+) or titles without posters
                if (year > JustWatchGraph.MAX_RELEASED_YEAR) return;
                String posterUrl = meta.optString("poster_url");
                if (posterUrl.isEmpty()) return;

                String verifiedApp = !meta.optString("app").isEmpty() ? meta.optString("app") : p.optString("app", "Netflix");
                if (verifiedApp.toLowerCase().contains("store") || "Streaming".equalsIgnoreCase(verifiedApp)) {
                    verifiedApp = !p.optString("app").isEmpty() && !p.optString("app").equalsIgnoreCase("Streaming")
                        ? p.optString("app")
                        : "Netflix";
                }

                JSONObject card = new JSONObject();
                try {
                    card.put("title", !meta.optString("title").isEmpty() ? meta.optString("title") : title);
                    card.put("badge", verifiedApp);
                    card.put("app", verifiedApp);
                    card.put("year", year);
                    card.put("type", meta.optString("type", "Series"));
                    String reason = p.optString("reason");
                    if (reason.isEmpty()) reason = meta.optString("synopsis");
                    card.put("reason", reason);
                    card.put("synopsis", !meta.optString("synopsis").isEmpty() ? meta.optString("synopsis") : reason);
                    card.put("poster_url", posterUrl);
                    String genres = !meta.optString("genres").isEmpty()
                        ? meta.optString("genres")
                        : p.optString("genres", p.optString("genre", "Drama"));
                    card.put("genres", genres);
                    int rt = meta.optInt("runtime_min", p.optInt("runtime_min", 48));
                    card.put("runtime_min", rt);
                    card.put("rating", meta.optString("rating", p.optString("rating", "8.1")));
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
        Set<String> seenKeys = new HashSet<>();
        for (JSONObject r : results) {
            if (r != null) {
                String k = AuraDb.normalizeTitleKey(r.optString("title"));
                if (!k.isEmpty() && !seenKeys.contains(k)) {
                    seenKeys.add(k);
                    out.put(r);
                }
            }
        }
        return out;
    }

    public JSONObject fetchOpenMetadata(String title) {
        JSONObject res = new JSONObject();
        if (title == null || title.isEmpty()) return res;
        String cacheKey = "jw_imdb_v4:" + title.trim().toLowerCase();
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
                        int y = item.optInt("y", 2023);
                        if (y > JustWatchGraph.MAX_RELEASED_YEAR) continue;
                        JSONObject iObj = item.optJSONObject("i");
                        if (iObj != null && !iObj.optString("imageUrl").isEmpty()) {
                            res.put("year", y);
                            res.put("poster_url", iObj.optString("imageUrl").replace("._V1_.jpg", "._V1_UX360_.jpg"));
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        if (!res.optString("poster_url").isEmpty() && res.optInt("year", 2024) <= JustWatchGraph.MAX_RELEASED_YEAR) {
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
                body.put("generationConfig", new JSONObject().put("responseMimeType", "application/json").put("temperature", 0.55));
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
            body.put("generationConfig", new JSONObject().put("responseMimeType", "application/json").put("temperature", 0.55));
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
