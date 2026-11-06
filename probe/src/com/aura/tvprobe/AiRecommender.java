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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AiRecommender {
    public interface AgentProgressListener {
        void onStep(String stepMessage);
    }

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
        return getOrRefreshForProfile(profileId, nowPlaying, history, forceManual, null);
    }

    public JSONArray getOrRefreshForProfile(
        String profileId,
        JSONObject nowPlaying,
        JSONArray history,
        boolean forceManual,
        AgentProgressListener progress
    ) {
        JSONArray cached = db.getProfileRecommendations(profileId);
        if (!forceManual && cached.length() >= 10 && db.isRecoCacheFresh(profileId)) {
            return cached;
        }

        int currentYear = JustWatchGraph.getCurrentYear();
        int minModernYear = JustWatchGraph.getDefaultMinModernYear();
        List<String> watchedList = collectWatchedTitles(history);

        // Agent Step 1: LLM Query Planner guides primary queries + watched-show second-hop traversal seeds
        if (progress != null) progress.onStep("Agent 1/3 · LLM planning queries & watched seeds…");
        JSONObject seedPlan = planAgentSearchStrategy(profileId, "", watchedList, minModernYear, currentYear);

        // Agent Step 2: Multi-Hop Graph Traversal from Watched Shows (2nd Query) & Guide AI Seeds
        if (progress != null) progress.onStep("Agent 2/3 · Traversing " + minModernYear + "–" + currentYear + " graph from watched & Guide AI…");
        List<String> verifiedCandidates = executeMultiHopTraversal(profileId, seedPlan, minModernYear);

        // Agent Step 3: LLM Agentic Curation, Ranking & Reasoning
        if (progress != null) progress.onStep("Agent 3/3 · LLM ranking 20 modern (" + minModernYear + "–" + currentYear + ") picks…");
        String prompt = buildAgentRankingPrompt(profileId, nowPlaying, watchedList, verifiedCandidates, minModernYear, currentYear);
        for (String model : MODELS) {
            JSONArray raw = callGemini(model, prompt);
            if (raw != null && raw.length() > 0) {
                JSONArray enriched = enrichAll(raw, minModernYear, currentYear);
                if (enriched.length() > 0) {
                    db.saveProfileRecommendations(profileId, enriched);
                    return enriched;
                }
            }
        }
        return cached;
    }

    private JSONObject buildFastSeedPlan(String profileId, String userQuery) {
        JSONObject plan = new JSONObject();
        try {
            JSONArray pq = new JSONArray();
            if (userQuery != null && !userQuery.trim().isEmpty()) {
                pq.put(userQuery.trim());
            }
            String personal = db.getProfileTaste(profileId);
            if (!personal.isEmpty()) {
                for (String part : personal.split(",")) {
                    String kw = part.trim();
                    if (!kw.isEmpty() && pq.length() < 4) pq.put(kw);
                }
            }
            List<String> anchors = db.getDynamicTasteAnchors(profileId);
            for (String a : anchors) {
                if (pq.length() < 5) pq.put(a);
            }
            plan.put("primary_queries", pq);
            plan.put("watched_traversal_seeds", new JSONArray(anchors));
        } catch (Exception ignored) {}
        return plan;
    }

    /**
     * Triggered as a second-hop traversal when the user marks a title as Watched or clicks "Similar".
     */
    public JSONArray traverseSimilarToWatched(String profileId, String seedTitle, AgentProgressListener progress) {
        int currentYear = JustWatchGraph.getCurrentYear();
        int minModernYear = JustWatchGraph.getDefaultMinModernYear();
        if (progress != null) progress.onStep("Traversing " + minModernYear + "–" + currentYear + " titles similar to " + seedTitle + "…");

        JSONArray sim = JustWatchGraph.fetchCollaborativeSimilar(seedTitle, minModernYear);
        List<String> candidateSummaries = new ArrayList<>();
        for (int i = 0; i < sim.length() && i < 12; i++) {
            JSONObject o = sim.optJSONObject(i);
            if (o == null) continue;
            String t = o.optString("title");
            if (!t.isEmpty() && !db.isTitleWatched(t)) {
                db.saveCachedGti("jw_imdb_v5:" + t.trim().toLowerCase(), o.toString());
                candidateSummaries.add(t + " (" + o.optInt("year") + ", " + o.optString("genres") + ", " + o.optString("rating") + ", " + o.optString("app") + ")");
            }
        }

        List<String> watchedList = collectWatchedTitles(db.listHistory(profileId));
        String personalGuide = db.getProfileTaste(profileId);
        String defaultGuide = db.getDefaultTasteGuide();
        String todayDate = JustWatchGraph.getCurrentDateString();

        String prompt = "Current TV System Date: " + todayDate + " (titles released up to today in " + currentYear + " are valid).\n" +
            "User just watched/selected '" + seedTitle + "' and wants a second-hop traversal of similar modern titles.\n" +
            "Active Profile: '" + db.getProfileLabel(profileId) + "'.\n" +
            "Personal Guide AI: " + (personalGuide.isEmpty() ? "(Use Default Guide AI)" : personalGuide) + "\n" +
            "Default Guide AI: " + defaultGuide + "\n" +
            "Already Watched (STRICT EXCLUSION): " + watchedList.toString() + "\n" +
            "Graph Traversal Candidates for '" + seedTitle + "' (" + minModernYear + "-" + currentYear + "): " + candidateSummaries.toString() + "\n\n" +
            "Select & rank 15 modern released titles (" + minModernYear + " to " + currentYear + " ONLY — NO pre-" + minModernYear + " old shows and NO unreleased " + (currentYear + 1) + "+ titles) that someone who watched '" + seedTitle + "' will love next.\n" +
            "Return ONLY a JSON array of 15 objects with keys: 'title', 'app' (Netflix, Disney+ Hotstar, Prime Video, Apple TV+, SonyLIV, or ZEE5), 'genres', 'runtime_min' (integer), and 'reason' (1-2 sentences explaining why it connects to '" + seedTitle + "' and their taste).";

        for (String model : MODELS) {
            JSONArray raw = callGemini(model, prompt);
            if (raw != null && raw.length() > 0) {
                JSONArray enriched = enrichAll(raw, minModernYear, currentYear);
                if (enriched.length() > 0) {
                    return enriched;
                }
            }
        }
        return sim;
    }

    public JSONObject planAgentSearchStrategy(
        String profileId,
        String userQuery,
        List<String> watchedList,
        int minModernYear,
        int currentYear
    ) {
        String profileName = db.getProfileLabel(profileId);
        String personalGuide = db.getProfileTaste(profileId);
        String defaultGuide = db.getDefaultTasteGuide();
        String ratingsFeedback = db.getRatingFeedbackSummary();
        List<String> anchors = db.getDynamicTasteAnchors(profileId);

        String todayDate = JustWatchGraph.getCurrentDateString();
        String plannerPrompt = "You are Aura's Search Query Planner Agent. Current TV System Date: " + todayDate + ".\n" +
            "Profile: '" + profileName + "'.\n" +
            "Personal Guide AI: " + (personalGuide.isEmpty() ? "(None)" : personalGuide) + "\n" +
            "Default Guide AI: " + defaultGuide + "\n" +
            "User Ratings Feedback: " + ratingsFeedback + "\n" +
            "Recent Watched/Liked Anchors: " + anchors.toString() + "\n" +
            "Already Watched (do not recommend): " + watchedList.toString() + "\n" +
            "User Search Query (if any): \"" + (userQuery == null ? "" : userQuery) + "\"\n\n" +
            "Task:\n" +
            "1. Plan 'primary_queries': 5 specific modern (" + minModernYear + "-" + currentYear + ") critically acclaimed show/movie titles that match the user's query and Guide AI.\n" +
            "2. Plan 'watched_traversal_seeds': 3 titles from the user's watched/rated list (or top affinity anchors) to run a second co-watch graph traversal query ('user watched X -> find what viewers watched next').\n" +
            "3. Plan 'traversal_chips': 3 short clickable search-engine traversal phrases (max 24 chars each, e.g. 'Similar to " + (!anchors.isEmpty() ? anchors.get(0) : "Severance") + "', '" + (currentYear - 2) + "-" + currentYear + " Sci-Fi', 'High-Pace Thrillers').\n" +
            "Return ONLY a JSON object with keys: 'primary_queries' (array of strings), 'watched_traversal_seeds' (array of strings), 'traversal_chips' (array of 3 strings).";

        String raw = callGeminiJson(plannerPrompt);
        if (raw != null) {
            try {
                JSONObject plan = new JSONObject(raw);
                JSONArray chips = plan.optJSONArray("traversal_chips");
                if (chips != null && chips.length() > 0) {
                    db.saveTraversalChips(profileId, chips);
                }
                return plan;
            } catch (Exception ignored) {}
        }

        // Deterministic fallback plan if offline
        JSONObject fallback = new JSONObject();
        try {
            JSONArray pq = new JSONArray();
            if (userQuery != null && !userQuery.isEmpty()) pq.put(userQuery);
            for (String a : anchors) pq.put(a);
            fallback.put("primary_queries", pq);
            fallback.put("watched_traversal_seeds", new JSONArray(anchors));
        } catch (Exception ignored) {}
        return fallback;
    }

    public List<String> executeMultiHopTraversal(String profileId, JSONObject searchPlan, int minModernYear) {
        final List<String> summaries = java.util.Collections.synchronizedList(new ArrayList<>());
        final Set<String> seenNorm = java.util.Collections.synchronizedSet(new HashSet<>());
        List<String> queriesToRun = new ArrayList<>();
        List<String> seedsToTraverse = new ArrayList<>();

        if (searchPlan != null) {
            JSONArray pq = searchPlan.optJSONArray("primary_queries");
            if (pq != null) {
                for (int i = 0; i < pq.length() && i < 5; i++) {
                    String q = pq.optString(i).trim();
                    if (!q.isEmpty()) queriesToRun.add(q);
                }
            }
            JSONArray ws = searchPlan.optJSONArray("watched_traversal_seeds");
            if (ws != null) {
                for (int i = 0; i < ws.length() && i < 4; i++) {
                    String s = ws.optString(i).trim();
                    if (!s.isEmpty() && !seedsToTraverse.contains(s)) seedsToTraverse.add(s);
                }
            }
        }
        for (String a : db.getDynamicTasteAnchors(profileId)) {
            if (!seedsToTraverse.contains(a) && seedsToTraverse.size() < 5) seedsToTraverse.add(a);
        }

        ExecutorService pool = Executors.newFixedThreadPool(5);
        // Hop 1: Execute LLM-planned search queries + direct title verification
        for (final String q : queriesToRun) {
            pool.submit(() -> {
                JSONObject exact = JustWatchGraph.lookupTitleInIndia(q);
                if (JustWatchGraph.isValidModernStreamingCandidate(exact, minModernYear, 7.0)) {
                    addCandidateIfValid(exact, seenNorm, summaries);
                }
                JSONArray hits = JustWatchGraph.fetchDeterministicSearchCandidates(q, minModernYear);
                for (int i = 0; i < hits.length() && i < 3; i++) {
                    addCandidateIfValid(hits.optJSONObject(i), seenNorm, summaries);
                }
            });
        }
        // Hop 2 ("Watched -> Second Query"): Traverse similarTitles graph from watched/seed shows
        for (final String seed : seedsToTraverse) {
            pool.submit(() -> {
                JSONArray sim = JustWatchGraph.fetchCollaborativeSimilar(seed, minModernYear);
                for (int i = 0; i < sim.length() && i < 6; i++) {
                    addCandidateIfValid(sim.optJSONObject(i), seenNorm, summaries);
                }
            });
        }
        pool.shutdown();
        try { pool.awaitTermination(5, TimeUnit.SECONDS); } catch (Exception ignored) {}
        return summaries;
    }

    private void addCandidateIfValid(JSONObject o, Set<String> seenNorm, List<String> summaries) {
        if (o == null) return;
        String t = o.optString("title").trim();
        String norm = AuraDb.normalizeTitleKey(t);
        if (t.isEmpty() || seenNorm.contains(norm) || db.isTitleWatched(t)) return;
        seenNorm.add(norm);
        db.saveCachedGti("jw_imdb_v5:" + t.toLowerCase(), o.toString());
        summaries.add(t + " (" + o.optInt("year") + ", " + o.optString("genres") + ", " + o.optString("rating") + ", " + o.optString("app") + ")");
    }

    private List<String> collectWatchedTitles(JSONArray history) {
        List<String> seen = new ArrayList<>();
        JSONArray manual = db.listManuallyWatched();
        for (int i = 0; i < manual.length(); i++) {
            JSONObject m = manual.optJSONObject(i);
            if (m != null) {
                String t = m.optString("series_title").isEmpty() ? m.optString("title") : m.optString("series_title");
                if (!t.isEmpty() && !seen.contains(t)) seen.add(t);
            }
        }
        if (history != null) {
            for (int i = 0; i < history.length() && seen.size() < 45; i++) {
                JSONObject h = history.optJSONObject(i);
                if (h == null || h.optString("app_pkg").contains("youtube")) continue;
                String t = h.optString("series_title").isEmpty() ? h.optString("title") : h.optString("series_title");
                if (!t.isEmpty() && !seen.contains(t)) seen.add(t);
            }
        }
        return seen;
    }

    private String buildAgentRankingPrompt(
        String profileId,
        JSONObject np,
        List<String> watchedList,
        List<String> verifiedCandidates,
        int minModernYear,
        int currentYear
    ) {
        String profileName = db.getProfileLabel(profileId);
        String personalGuide = db.getProfileTaste(profileId);
        String defaultGuide = db.getDefaultTasteGuide();
        String ratingsFeedback = db.getRatingFeedbackSummary();
        List<String> activeFilters = db.getActiveFilters(profileId);
        String cur = np != null ? (np.optString("series_title").isEmpty() ? np.optString("title") : np.optString("series_title")) : "";

        String todayDate = JustWatchGraph.getCurrentDateString();
        int recentPriorityStart = Math.max(minModernYear, currentYear - 5);

        return "You are Aura's Ranking & Reasoning Agent for Android TV in India.\n" +
            "Current TV System Date: " + todayDate + " (any title released up to " + todayDate + " in " + currentYear + " is valid; " + (currentYear + 1) + "+ titles are unreleased).\n" +
            "Active Profile: '" + profileName + "' (profile_id: " + profileId + ").\n" +
            "1. PERSONAL GUIDE AI (Highest Priority for " + profileName + "): " +
            (personalGuide.isEmpty() ? "(Use Default Guide AI and Watch History)" : "\"" + personalGuide + "\"") + "\n" +
            "2. DEFAULT GUIDE AI (Global Baseline): \"" + defaultGuide + "\"\n" +
            "3. USER 1-10 RATINGS FEEDBACK: " + (ratingsFeedback.isEmpty() ? "None yet" : ratingsFeedback) + "\n" +
            "4. ACTIVE UI FILTERS: " + (activeFilters.isEmpty() ? "All categories" : activeFilters.toString()) + "\n" +
            "5. RECENTLY ACTIVE ON TV: '" + cur + "'.\n" +
            "6. STRICT WATCHED EXCLUSION LIST (NEVER recommend any of these): " + watchedList.toString() + ".\n" +
            "7. MULTI-HOP GRAPH TRAVERSAL CANDIDATES (" + minModernYear + "–" + currentYear + " verified streaming in India): " + verifiedCandidates.toString() + ".\n\n" +
            "STRICT CURATION RULES:\n" +
            "- MODERN RECENCY WINDOW: ONLY recommend shows and movies released between " + minModernYear + " and " + currentYear + " (strongly prioritize " + recentPriorityStart + "–" + currentYear + " releases, including recent releases up to " + todayDate + ").\n" +
            "- NEVER recommend old shows/movies from the 1980s, 1990s, 2000s, or early 2010s (nothing older than " + minModernYear + ").\n" +
            "- NEVER recommend unreleased " + (currentYear + 1) + "+ titles.\n" +
            "- Select & rank 20 titles (for a 4-row x 5-column grid) combining the best matches from the Multi-Hop Graph Traversal Candidates with top " + recentPriorityStart + "–" + currentYear + " releases on Netflix, Disney+ Hotstar, Prime Video, Apple TV+, SonyLIV, or ZEE5.\n" +
            "- For EVERY title, write a specific 1-2 sentence 'reason' (18-28 words) explaining WHY it was selected for '" + profileName + "' based on their watched shows, Personal Guide AI, or Default Guide AI.\n" +
            "Return ONLY a JSON array of 20 objects with keys: 'title', 'app' (Disney+ Hotstar, Netflix, Prime Video, Apple TV+, SonyLIV, or ZEE5), " +
            "'genres' (comma-separated), 'runtime_min' (integer minutes), and 'reason' (personalized explanation).";
    }

    public JSONArray enrichAll(JSONArray raw) {
        return enrichAll(raw, JustWatchGraph.getDefaultMinModernYear(), JustWatchGraph.getCurrentYear());
    }

    public JSONArray enrichAll(JSONArray raw, final int minAllowedYear, final int maxAllowedYear) {
        JSONArray out = new JSONArray();
        if (raw == null) return out;
        int n = raw.length();
        final JSONObject[] results = new JSONObject[n];
        ExecutorService pool = Executors.newFixedThreadPool(4);
        for (int i = 0; i < n; i++) {
            final int idx = i;
            final JSONObject p = raw.optJSONObject(i);
            if (p == null) continue;
            pool.submit(() -> {
                String title = p.optString("title").trim();
                if (title.isEmpty() || db.isTitleWatched(title)) return;
                JSONObject meta = fetchOpenMetadata(title);
                int year = meta.optInt("year", p.optInt("year", maxAllowedYear));
                // Enforce modern recency window [minAllowedYear .. maxAllowedYear]
                if (year > maxAllowedYear || year < minAllowedYear) return;
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
        try { pool.awaitTermination(8, TimeUnit.SECONDS); } catch (Exception ignored) {}
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
        String cacheKey = "jw_imdb_v5:" + title.trim().toLowerCase();
        String cached = db.getCachedGti(cacheKey);
        if (cached != null) {
            try { return new JSONObject(cached); } catch (Exception ignored) {}
        }
        res = JustWatchGraph.lookupTitleInIndia(title);
        int maxYear = JustWatchGraph.getCurrentYear();

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
                        int y = item.optInt("y", maxYear);
                        if (y > maxYear) continue;
                        JSONObject iObj = item.optJSONObject("i");
                        if (iObj != null && !iObj.optString("imageUrl").isEmpty()) {
                            res.put("year", y);
                            res.put("poster_url", iObj.optString("imageUrl").replace("._V1_.jpg", "._V1_UX400_.jpg"));
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        if (!res.optString("poster_url").isEmpty() && res.optInt("year", maxYear) <= maxYear) {
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
                body.put("generationConfig", new JSONObject().put("responseMimeType", "application/json").put("temperature", 0.5));
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
            body.put("generationConfig", new JSONObject().put("responseMimeType", "application/json").put("temperature", 0.5));
            String resp = httpPost(url, body.toString());
            JSONObject root = new JSONObject(resp);
            String text = root.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text").trim();
            if (text.startsWith("[")) {
                return new JSONArray(text);
            }
            if (text.startsWith("{")) {
                JSONObject obj = new JSONObject(text);
                JSONArray names = obj.names();
                if (names != null) {
                    for (int i = 0; i < names.length(); i++) {
                        JSONArray arr = obj.optJSONArray(names.getString(i));
                        if (arr != null && arr.length() > 0) return arr;
                    }
                }
            }
            return null;
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
