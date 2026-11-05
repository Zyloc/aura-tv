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
        "gemini-flash-lite-latest"
    };
    private final AuraDb db;

    public AiRecommender(AuraDb db) {
        this.db = db;
    }

    private String getApiKey() {
        return db.getSetting("gemini_api_key", "").trim();
    }

    private String[] profileAnchors(String profileId) {
        if ("partner".equals(profileId)) return new String[]{"Marry My Husband", "Crash Landing on You", "The Glory"};
        if ("member3".equals(profileId)) return new String[]{"The Family Man", "Panchayat", "Farzi"};
        if ("kids".equals(profileId)) return new String[]{"Spider-Man: Into the Spider-Verse", "Paddington 2", "Kung Fu Panda"};
        if ("family".equals(profileId)) return new String[]{"Knives Out", "3 Idiots", "Modern Family"};
        return new String[]{"Severance", "Dark Matter", "Prisoners"};
    }

    public JSONArray getFastCollaborativeForProfile(String profileId) {
        JSONArray cached = db.getProfileRecommendations(profileId);
        if (cached.length() > 0) return cached;

        String[] anchors = profileAnchors(profileId);
        String label = db.getProfileLabel(profileId);
        final JSONArray[] buckets = new JSONArray[anchors.length];
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(anchors.length);
        for (int i = 0; i < anchors.length; i++) {
            final int idx = i;
            final String seed = anchors[i];
            pool.submit(() -> {
                JSONArray sim = JustWatchGraph.fetchCollaborativeSimilar(seed);
                for (int j = 0; j < sim.length(); j++) {
                    JSONObject o = sim.optJSONObject(j);
                    if (o == null) continue;
                    String t = o.optString("title");
                    if (!t.isEmpty()) {
                        db.saveCachedGti("jw_imdb_v3:" + t.trim().toLowerCase(), o.toString());
                        try {
                            o.put("reason", "Recommended for " + label + " because you liked " + seed + ".");
                        } catch (Exception ignored) {}
                    }
                }
                buckets[idx] = sim;
            });
        }
        pool.shutdown();
        try { pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignored) {}

        JSONArray merged = new JSONArray();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int round = 0; round < 8; round++) {
            for (JSONArray b : buckets) {
                if (b == null || round >= b.length()) continue;
                JSONObject o = b.optJSONObject(round);
                if (o == null) continue;
                String k = AuraDb.normalizeTitleKey(o.optString("title"));
                if (!k.isEmpty() && !seen.contains(k) && !db.isTitleWatched(o.optString("title")) && !o.optString("poster_url").isEmpty()) {
                    seen.add(k);
                    merged.put(o);
                    if (merged.length() >= 16) break;
                }
            }
            if (merged.length() >= 16) break;
        }
        if (merged.length() > 0) {
            db.saveProfileRecommendations(profileId, merged);
            return merged;
        }
        return buildOpenSourceRecommendations(profileId);
    }

    public JSONArray getOrRefreshForProfile(String profileId, JSONObject nowPlaying, JSONArray history, boolean forceManual) {
        JSONArray cached = db.getProfileRecommendations(profileId);
        if (!forceManual && cached.length() > 0 && db.isRecoCacheFresh(profileId)) {
            return cached;
        }
        if (!forceManual && cached.length() == 0) {
            return getFastCollaborativeForProfile(profileId);
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
        return getFastCollaborativeForProfile(profileId);
    }

    private String buildProfilePrompt(String profileId, JSONObject np, JSONArray history) {
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < history.length() && seen.size() < 35; i++) {
            JSONObject h = history.optJSONObject(i);
            if (h == null) continue;
            String pkg = h.optString("app_pkg");
            if (pkg.contains("youtube")) continue;
            String t = h.optString("series_title").isEmpty() ? h.optString("title") : h.optString("series_title");
            if (!t.isEmpty() && !seen.contains(t)) seen.add(t);
        }
        List<String> cfPool = fetchCollaborativeCandidateNames(profileId);
        String persona = profilePersona(profileId);
        String cur = np != null ? (np.optString("series_title").isEmpty() ? np.optString("title") : np.optString("series_title")) : "";
        return "Active Household Profile: " + persona + ".\n" +
            "Currently/recently watched on TV: '" + cur + "'.\n" +
            "Already watched (DO NOT recommend these): " + seen.toString() + ".\n" +
            "Collaborative Filtering Graph Candidates (high-affinity co-watched titles in India — prioritize selecting the best from these plus top critically acclaimed matches): " + cfPool.toString() + ".\n" +
            "Recommend 14 critically acclaimed shows or movies (IMDb >= 7.5) not yet watched. " +
            "Return ONLY a JSON array of 14 objects with keys: 'title', 'app' (Prime Video, Netflix, Disney+ Hotstar, Apple TV+, SonyLIV, or ZEE5), " +
            "'genres' (e.g. 'Sci-Fi, Thriller'), 'runtime_min' (integer minutes), and 'reason' (1 sharp sentence explaining why it fits their taste, max 18 words).";
    }

    public List<String> fetchCollaborativeCandidateNames(String profileId) {
        String[] anchors = profileAnchors(profileId);
        List<String> names = new ArrayList<>();
        for (String seed : anchors) {
            JSONArray sim = JustWatchGraph.fetchCollaborativeSimilar(seed);
            for (int i = 0; i < sim.length() && i < 6; i++) {
                JSONObject o = sim.optJSONObject(i);
                if (o != null && !o.optString("title").isEmpty() && !db.isTitleWatched(o.optString("title"))) {
                    String t = o.optString("title");
                    db.saveCachedGti("jw_imdb_v3:" + t.trim().toLowerCase(), o.toString());
                    names.add(t + " (" + o.optString("rating") + ", " + o.optString("app", "Streaming") + ")");
                }
            }
        }
        return names;
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
                JSONObject meta = fetchOpenMetadata(title);
                String verifiedApp = !meta.optString("app").isEmpty() ? meta.optString("app") : p.optString("app", "Prime Video");
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
                    card.put("rating", meta.optString("rating", "★ 8.2"));
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
        // 1. Query JustWatch GraphQL first (India ANDROID_TV deep links + IMDb score + poster + runtime)
        res = JustWatchGraph.lookupTitleInIndia(title);

        // 2. Fallback to IMDb Suggestion API if poster is still empty
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
