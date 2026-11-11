package com.aura.tvprobe;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agentic Show Companion & "Catch Me Up" Summarizer.
 * Fetches episode guide + SRT subtitles up to the viewer's timestamp, emits live
 * agentic function steps to the loader, and passes the compressed subtitle + prior
 * episode text directly into a clean conversational summarize call.
 */
public final class ShowCompanionAgent {

    public static final String LENS_FULL_RECAP        = "FULL_RECAP";
    public static final String LENS_CHARACTER_OR_PLOT = "CHARACTER_OR_PLOT";
    public static final String LENS_RECENT_SCENE      = "RECENT_SCENE";
    public static final String LENS_ACTOR_XRAY        = "ON_SCREEN_ACTOR_XRAY";

    private ShowCompanionAgent() {}

    public static boolean isShowCompanionQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.trim().isEmpty()) return false;
        String low = rawQuery.trim().toLowerCase(Locale.US);
        return isRecapPhrase(low) || isRecentScenePhrase(low) || isActorXrayPhrase(low) || isCharacterPlotPhrase(low);
    }

    private static boolean isRecapPhrase(String low) {
        return low.contains("catch me up") || low.contains("what happened so far") || low.contains("what has happened")
            || low.contains("story so far") || low.startsWith("recap") || low.contains("recap of ")
            || low.contains("summarize what i") || low.contains("summary so far");
    }

    private static boolean isRecentScenePhrase(String low) {
        return low.contains("last 5 min") || low.contains("last five min") || low.contains("what just happened")
            || low.contains("what did he just say") || low.contains("what did she just say") || low.contains("what did they just say")
            || low.contains("explain this scene");
    }

    private static boolean isActorXrayPhrase(String low) {
        return low.contains("who is on screen") || low.contains("who's on screen") || low.contains("actor on screen")
            || low.contains("who is this actor") || low.contains("who is in this scene") || low.contains("who plays ")
            || low.contains("who is playing ") || low.contains("actor in this scene");
    }

    private static boolean isCharacterPlotPhrase(String low) {
        return (low.startsWith("who is ") || low.startsWith("why did ") || low.startsWith("why is ")
            || low.startsWith("how are ") || low.startsWith("what happened to "))
            && !low.contains("recommend") && !low.contains("movies like");
    }

    public static String detectLens(String userQuery) {
        if (userQuery == null || userQuery.trim().isEmpty()) return LENS_FULL_RECAP;
        String low = userQuery.trim().toLowerCase(Locale.US);
        if (isActorXrayPhrase(low)) return LENS_ACTOR_XRAY;
        if (isRecentScenePhrase(low)) return LENS_RECENT_SCENE;
        if (isCharacterPlotPhrase(low) && !isRecapPhrase(low)) return LENS_CHARACTER_OR_PLOT;
        return LENS_FULL_RECAP;
    }

    public static JSONObject executeCompanionTurn(
            AiRecommender ai, AuraDb db, String profileId, String userQuery, JSONObject explicitItem) {
        return executeCompanionTurn(ai, db, profileId, userQuery, explicitItem, null);
    }

    public static JSONObject executeCompanionTurn(
            AiRecommender ai, AuraDb db, String profileId, String userQuery,
            JSONObject explicitItem, AiRecommender.AgentProgressListener stepListener) {
        String lens = detectLens(userQuery);
        JSONObject ctx = resolveTargetContext(db, profileId, userQuery, explicitItem);
        String cacheKey = buildRecapCacheKey(ctx, lens, userQuery);
        if (LENS_FULL_RECAP.equals(lens)) {
            JSONObject cached = tryReadCachedRecap(db, cacheKey);
            if (cached != null) return cached;
        }
        JSONObject result = runAgenticLensSynthesis(ai, db, profileId, userQuery, lens, ctx, stepListener);
        cacheRecapIfFromLlm(db, cacheKey, lens, result);
        return result;
    }

    private static void cacheRecapIfFromLlm(AuraDb db, String cacheKey, String lens, JSONObject result) {
        if (db == null || result == null || !LENS_FULL_RECAP.equals(lens)) return;
        if (!result.optBoolean("is_fallback", false) && !result.optString("spoken_narration").isEmpty()) {
            db.saveCachedGti(cacheKey, result.toString());
        }
    }

    private static JSONObject tryReadCachedRecap(AuraDb db, String cacheKey) {
        String raw = db != null ? db.getCachedGti(cacheKey) : null;
        if (raw == null || raw.isEmpty()) return null;
        try { return new JSONObject(raw); } catch (Exception e) { return null; }
    }

    private static String buildRecapCacheKey(JSONObject ctx, String lens, String query) {
        String norm = AuraDb.normalizeTitleKey(ctx.optString("title", "show"));
        int s = ctx.optInt("season", 1);
        int e = ctx.optInt("episode", 1);
        int bucket5m = ctx.optInt("position_sec", 1200) / 300;
        String qHash = Integer.toHexString((query == null ? "" : query.toLowerCase(Locale.US)).hashCode());
        return "recap_v3:" + norm + ":s" + s + "e" + e + ":b" + bucket5m + ":" + lens + ":" + qHash;
    }

    public static JSONObject resolveTargetContext(
            AuraDb db, String profileId, String userQuery, JSONObject explicitItem) {
        JSONObject base = explicitItem != null && explicitItem.length() > 0
            ? cloneJson(explicitItem)
            : findBestHistoryItem(db, profileId, userQuery);
        applyExplicitQueryOverrides(base, userQuery);
        normalizeContextFields(base);
        return base;
    }

    private static JSONObject findBestHistoryItem(AuraDb db, String profileId, String userQuery) {
        JSONArray hist = db != null ? db.listHistory(profileId) : new JSONArray();
        String qLow = userQuery != null ? userQuery.toLowerCase(Locale.US) : "";
        for (int i = 0; i < hist.length(); i++) {
            JSONObject h = hist.optJSONObject(i);
            String t = h != null ? (h.optString("series_title").isEmpty() ? h.optString("title") : h.optString("series_title")) : "";
            if (!t.isEmpty() && qLow.contains(t.toLowerCase(Locale.US))) return cloneJson(h);
        }
        return pickFirstNonOrphanHistoryItem(hist);
    }

    private static JSONObject pickFirstNonOrphanHistoryItem(JSONArray hist) {
        for (int i = 0; i < hist.length(); i++) {
            JSONObject h = hist.optJSONObject(i);
            if (h != null && !SessionInspector.isOrphanEpisodeCodeTitle(h.optString("series_title"), h.optString("title"))) {
                return cloneJson(h);
            }
        }
        return hist.length() > 0 ? cloneJson(hist.optJSONObject(0)) : new JSONObject();
    }

    private static void applyExplicitQueryOverrides(JSONObject base, String userQuery) {
        if (userQuery == null || userQuery.trim().isEmpty()) return;
        try {
            String extractedTitle = extractTitleFromCompanionQuery(userQuery);
            if (!extractedTitle.isEmpty()) base.put("title", extractedTitle).put("series_title", extractedTitle);
            parseSeasonEpisodeMinutesFromQuery(userQuery, base);
        } catch (Exception ignored) {}
    }

    public static String extractTitleFromCompanionQuery(String q) {
        if (q == null) return "";
        Matcher m = Pattern.compile("(?i)(?:catch me up on|recap of|recap|in|for|watching|about)\\s+([A-Za-z0-9][A-Za-z0-9 ':&-]{1,36}?)\\s*(?:\\band\\b|\\bwho\\b|\\bwhy\\b|\\bwhat\\b|\\bwhere\\b|season|\\bs\\d|episode|\\bat\\b|\\?|$)").matcher(q.trim());
        while (m.find()) {
            String cand = m.group(1).trim();
            if (!isGenericStopPhrase(cand)) return cand;
        }
        return "";
    }

    private static boolean isGenericStopPhrase(String cand) {
        String low = cand == null ? "" : cand.toLowerCase(Locale.US).trim();
        if (low.isEmpty() || low.contains("what happened") || low.contains("what has happened")) return true;
        if (low.contains("so far") || low.contains("last 5") || low.contains("last five")) return true;
        return low.startsWith("this ") || low.startsWith("the ") || low.equals("everything") || low.equals("right now");
    }

    private static void parseSeasonEpisodeMinutesFromQuery(String q, JSONObject base) throws Exception {
        Matcher se = Pattern.compile("(?i)s(?:eason)?\\s*(\\d{1,2})\\s*[,·:\\s-]*e(?:pisode)?\\s*(\\d{1,2})").matcher(q);
        if (se.find()) {
            base.put("season", Integer.parseInt(se.group(1))).put("episode", Integer.parseInt(se.group(2))).put("type", "Series");
        }
        Matcher min = Pattern.compile("(?i)(?:at|minute|min)\\s*(\\d{1,3})\\s*(?:m|min|minutes)?").matcher(q);
        if (min.find()) {
            base.put("position_sec", Integer.parseInt(min.group(1)) * 60);
        }
    }

    private static void normalizeContextFields(JSONObject base) {
        try {
            String rawTitle = base.optString("series_title").isEmpty() ? base.optString("title", "MobLand") : base.optString("series_title");
            String clean = rawTitle.split("\u00B7")[0].trim();
            base.put("title", clean.isEmpty() ? "MobLand" : clean);
            boolean hasExplicitEp = base.optInt("season", 0) > 0 && base.optInt("episode", 0) > 0;
            base.put("has_explicit_ep", hasExplicitEp);
            boolean isSeries = !"Movie".equalsIgnoreCase(base.optString("type", "Series")) || hasExplicitEp;
            base.put("is_series", isSeries);
            ensureDefaultPlaybackNumbers(base, isSeries);
        } catch (Exception ignored) {}
    }

    private static void ensureDefaultPlaybackNumbers(JSONObject base, boolean isSeries) throws Exception {
        if (isSeries && base.optInt("season", 0) <= 0) base.put("season", 1);
        if (isSeries && base.optInt("episode", 0) <= 0) base.put("episode", 1);
        if (base.optInt("position_sec", 0) <= 0) base.put("position_sec", 1450);
        if (base.optInt("duration_sec", 0) <= 0) base.put("duration_sec", 3000);
    }

    private static void emitStep(AiRecommender.AgentProgressListener listener, String msg) {
        AgentStatusNarrator.publishLiveStep(msg);
        if (listener != null) listener.onStep(msg);
    }

    private static JSONObject runAgenticLensSynthesis(
            AiRecommender ai, AuraDb db, String profileId, String userQuery,
            String lens, JSONObject ctx, AiRecommender.AgentProgressListener stepListener) {
        String title = ctx.optString("title", "MobLand");
        boolean isSeries = ctx.optBoolean("is_series", true);
        emitStep(stepListener, "Fetching episode guide for " + title + "\u2026");
        JSONObject guide = SubtitleAndGuideStore.getOrFetchShowGuide(db, title, isSeries);
        resolveEpisodeFromGuideIfNeeded(ctx, guide);
        JSONArray cues = fetchEpisodeSubtitlesStep(db, ctx, guide, title, isSeries, stepListener);
        if (Thread.currentThread().isInterrupted()) return new JSONObject();
        return synthesizeWithSubtitles(ai, db, profileId, userQuery, lens, ctx, guide, cues, stepListener);
    }

    private static JSONArray fetchEpisodeSubtitlesStep(
            AuraDb db, JSONObject ctx, JSONObject guide, String title,
            boolean isSeries, AiRecommender.AgentProgressListener stepListener) {
        int s = ctx.optInt("season", 1);
        int e = ctx.optInt("episode", 1);
        int posSec = ctx.optInt("position_sec", 1450);
        String epLabel = isSeries ? (" Season " + s + ", Episode " + e) : "";
        emitStep(stepListener, "Loading" + epLabel + " subtitles up to " + SubtitleAndGuideStore.formatMmSs(posSec) + "\u2026");
        JSONArray cues = SubtitleAndGuideStore.getOrFetchEpisodeSrtCues(db, title, guide, s, e, isSeries);
        SubtitleAndGuideStore.proactivelyCacheSeriesAsync(db, title, s, e);
        return cues;
    }

    private static void resolveEpisodeFromGuideIfNeeded(JSONObject ctx, JSONObject guide) {
        if (ctx == null || guide == null || ctx.optBoolean("has_explicit_ep", false)) return;
        String syn = ctx.optString("synopsis", "").replaceFirst("(?i)^continue watching\\s+", "").trim();
        if (syn.length() < 3) return;
        JSONArray eps = guide.optJSONArray("episodes");
        if (eps != null) matchEpisodeByTitle(ctx, eps, syn.toLowerCase(Locale.US));
    }

    private static void matchEpisodeByTitle(JSONObject ctx, JSONArray eps, String epHintLow) {
        for (int i = 0; i < eps.length(); i++) {
            JSONObject ep = eps.optJSONObject(i);
            String name = ep != null ? ep.optString("name", "").trim().toLowerCase(Locale.US) : "";
            if (!name.isEmpty() && (epHintLow.contains(name) || name.contains(epHintLow))) {
                try { ctx.put("season", ep.optInt("season", 1)).put("episode", ep.optInt("number", 1)); } catch (Exception ignored) {}
                return;
            }
        }
    }

    private static JSONObject synthesizeWithSubtitles(
            AiRecommender ai, AuraDb db, String profileId, String userQuery,
            String lens, JSONObject ctx, JSONObject guide, JSONArray cues,
            AiRecommender.AgentProgressListener stepListener) {
        int posSec = ctx.optInt("position_sec", 1450);
        emitStep(stepListener, "Compressing subtitle dialogue up to " + SubtitleAndGuideStore.formatMmSs(posSec) + "\u2026");
        JSONArray sceneActors = SubtitleAndGuideStore.inferActiveSceneActors(guide, cues, posSec);
        String screenFrameB64 = LENS_ACTOR_XRAY.equals(lens) ? captureScreenVisionFrameBase64() : null;
        String prompt = buildSimpleSummarizePrompt(userQuery, lens, ctx, guide, cues, sceneActors);
        emitStep(stepListener, "Summarizing story & dialogue so far\u2026");
        String rawLlm = ai.callGeminiMultimodalJson(prompt, screenFrameB64);
        JSONObject out = parseOrBuildFallbackRecap(rawLlm, lens, ctx, guide, cues, sceneActors);
        maybeChainActorMovieSearch(ai, db, profileId, userQuery, out, sceneActors, stepListener);
        return out;
    }

    public static String buildSimpleSummarizePrompt(
            String userQuery, String lens, JSONObject ctx, JSONObject guide,
            JSONArray cues, JSONArray sceneActors) {
        String title = ctx.optString("title", "MobLand");
        int s = ctx.optInt("season", 1);
        int e = ctx.optInt("episode", 1);
        int posSec = ctx.optInt("position_sec", 1450);
        String priorStory = humanizeEpisodeStitch(SubtitleAndGuideStore.buildHierarchicalEpisodeStitch(guide, s, e));
        String dialogue = sliceDialogueForLens(userQuery, lens, cues, posSec);
        String castInfo = LENS_ACTOR_XRAY.equals(lens) ? ("\nCast in Scene: " + formatSceneActorsReadable(sceneActors)) : "";
        return formatSummarizePromptText(title, s, e, posSec, userQuery, priorStory, dialogue, castInfo);
    }

    private static String formatSummarizePromptText(
            String title, int s, int e, int posSec, String userQuery,
            String priorStory, String dialogue, String castInfo) {
        String mmSs = SubtitleAndGuideStore.formatMmSs(posSec);
        String q = (userQuery == null || userQuery.isEmpty()) ? "Catch me up on what has happened so far" : userQuery;
        return "Summarize what has happened so far in \"" + title + "\" (Season " + s + ", Episode " + e + ", paused at " + mmSs + ") for the viewer asking: \"" + q + "\".\n\n" +
            "Previous Episode Context:\n" + priorStory + "\n\n" +
            "Compressed Subtitle Dialogue (00:00 to " + mmSs + "):\n" + (dialogue.isEmpty() ? "(No subtitle file found; use episode context above)" : dialogue) + castInfo + "\n\n" +
            "Write a warm, friendly, natural spoken summary (130 to 200 words) like a friend catching you up on the couch. " +
            "Use character names and actual events from the subtitles up to " + mmSs + " (strictly no spoilers past " + mmSs + "). " +
            "Never use codes like S1E1, brackets, markdown, or bullet symbols. " +
            "Return ONLY JSON: {\"headline\":\"" + title + " \u00B7 Season " + s + ", Episode " + e + "\",\"spoken_narration\":\"...\",\"actor_to_search\":\"\"}";
    }

    private static String sliceDialogueForLens(String userQuery, String lens, JSONArray cues, int posSec) {
        if (LENS_RECENT_SCENE.equals(lens) || LENS_ACTOR_XRAY.equals(lens)) {
            return SubtitleAndGuideStore.sliceRawWindow(cues, posSec - 240, posSec);
        }
        if (LENS_CHARACTER_OR_PLOT.equals(lens)) {
            String grepped = SubtitleAndGuideStore.grepCharacterOrTopic(cues, posSec, extractQuerySearchTerms(userQuery));
            if (!grepped.isEmpty()) return grepped;
        }
        return SubtitleAndGuideStore.sliceCompressedSceneBeats(cues, posSec);
    }

    public static String humanizeEpisodeStitch(String rawStitch) {
        if (rawStitch == null || rawStitch.isEmpty()) return "";
        return rawStitch.replaceAll("\\[[^\\]]*\\]", "")
            .replaceAll("(?i)\\bS(\\d{1,2})\\s*E(\\d{1,2})\\b", "Season $1, Episode $2")
            .replaceAll("(?i)\\bPremise:\\s*", "")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private static List<String> extractQuerySearchTerms(String userQuery) {
        List<String> terms = new ArrayList<>();
        if (userQuery == null) return terms;
        List<String> stop = Arrays.asList("who", "what", "why", "how", "when", "where", "did", "does", "the", "and", "with", "about", "happened", "between", "tell", "explain");
        for (String w : userQuery.replaceAll("[^A-Za-z0-9 ]", " ").split("\\s+")) {
            if (w.length() >= 3 && !stop.contains(w.toLowerCase(Locale.US))) terms.add(w);
        }
        return terms;
    }

    private static JSONObject parseOrBuildFallbackRecap(
            String rawLlm, String lens, JSONObject ctx, JSONObject guide, JSONArray cues, JSONArray sceneActors) {
        JSONObject parsed = tryParseValidLlmRecap(rawLlm);
        if (parsed != null) {
            return attachContextMeta(parsed, lens, ctx, sceneActors);
        }
        return buildDeterministicFallbackRecap(lens, ctx, guide, cues, sceneActors);
    }

    private static JSONObject tryParseValidLlmRecap(String rawLlm) {
        if (rawLlm == null || rawLlm.trim().isEmpty()) return null;
        try {
            JSONObject parsed = new JSONObject(rawLlm.trim());
            String spoken = AuraTtsNarrator.cleanSpokenText(parsed.optString("spoken_narration"));
            if (spoken.isEmpty()) return null;
            return parsed.put("spoken_narration", spoken);
        } catch (Exception ignored) { return null; }
    }

    private static JSONObject buildDeterministicFallbackRecap(
            String lens, JSONObject ctx, JSONObject guide, JSONArray cues, JSONArray sceneActors) {
        JSONObject o = new JSONObject();
        try {
            populateFallbackRecapFields(o, ctx, guide, cues);
        } catch (Exception ignored) {}
        return attachContextMeta(o, lens, ctx, sceneActors);
    }

    private static void populateFallbackRecapFields(
            JSONObject o, JSONObject ctx, JSONObject guide, JSONArray cues) throws Exception {
        String title = ctx.optString("title", "MobLand");
        int s = ctx.optInt("season", 1);
        int e = ctx.optInt("episode", 1);
        int posSec = ctx.optInt("position_sec", 1450);
        String cleanStitch = humanizeEpisodeStitch(SubtitleAndGuideStore.buildHierarchicalEpisodeStitch(guide, s, e));
        String recentLines = SubtitleAndGuideStore.sliceRawWindow(cues, Math.max(0, posSec - 180), posSec);
        String spoken = composeFriendlyFallbackSpeech(title, s, e, posSec, cleanStitch, recentLines);
        o.put("headline", title + " \u00B7 Season " + s + ", Episode " + e);
        o.put("spoken_narration", spoken).put("story_so_far", cleanStitch).put("is_fallback", true);
    }

    private static String composeFriendlyFallbackSpeech(
            String title, int s, int e, int posSec, String cleanStitch, String recentLines) {
        String intro = "Here is what has happened so far in " + title + ", Season " + s + ", Episode " + e + ", up to minute " + Math.max(1, posSec / 60) + ". ";
        String body = !cleanStitch.isEmpty() ? (cleanStitch + " ") : "";
        String subTail = !recentLines.isEmpty() ? ("In the latest scene just before you paused: " + recentLines) : "";
        return AuraTtsNarrator.cleanSpokenText(intro + body + subTail);
    }

    private static JSONObject attachContextMeta(JSONObject o, String lens, JSONObject ctx, JSONArray sceneActors) {
        try {
            o.put("lens", lens).put("title", ctx.optString("title", "MobLand"));
            o.put("season", ctx.optInt("season", 1)).put("episode", ctx.optInt("episode", 1));
            o.put("position_sec", ctx.optInt("position_sec", 1450));
            if (o.optString("cast_xray").isEmpty()) o.put("cast_xray", formatSceneActorsReadable(sceneActors));
        } catch (Exception ignored) {}
        return o;
    }

    public static String formatSceneActorsReadable(JSONArray sceneActors) {
        if (sceneActors == null || sceneActors.length() == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sceneActors.length() && i < 5; i++) {
            JSONObject c = sceneActors.optJSONObject(i);
            if (c == null) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(c.optString("actor")).append(" as ").append(c.optString("character"));
        }
        return sb.toString();
    }

    private static void maybeChainActorMovieSearch(
            AiRecommender ai, AuraDb db, String profileId, String userQuery,
            JSONObject out, JSONArray sceneActors, AiRecommender.AgentProgressListener stepListener) {
        String low = userQuery != null ? userQuery.toLowerCase(Locale.US) : "";
        boolean wantsMovies = low.contains("movie") || low.contains("show") || low.contains("search") || low.contains("find") || low.contains("other");
        String actor = out.optString("actor_to_search", "").trim();
        if (actor.isEmpty() && wantsMovies && sceneActors != null && sceneActors.length() > 0) {
            actor = sceneActors.optJSONObject(0).optString("actor", "");
        }
        if (!actor.isEmpty() && wantsMovies) appendActorSearchResults(ai, actor, out, stepListener);
    }

    private static void appendActorSearchResults(
            AiRecommender ai, String actor, JSONObject out, AiRecommender.AgentProgressListener stepListener) {
        try {
            emitStep(stepListener, "Searching movies & shows starring " + actor + "\u2026");
            JSONArray specs = new JSONArray().put(new JSONObject().put("searchQuery", actor).put("includeSimilar", false).put("minYear", 1995));
            JSONArray castHits = JustWatchGraph.executeScatterGatherSpecs(specs);
            JSONArray enriched = ai.enrichAll(castHits, 1995, JustWatchGraph.getCurrentYear());
            out.put("actor_to_search", actor).put("results", enriched);
        } catch (Exception ignored) {}
    }

    public static String captureScreenVisionFrameBase64() {
        try {
            Process proc = Runtime.getRuntime().exec(new String[]{"sh", "-c", "screencap -p"});
            Bitmap bmp = decodeProcessBitmap(proc.getInputStream());
            if (bmp == null || isMostlyBlackDrmFrame(bmp)) return null;
            return encodeScaledJpegBase64(bmp);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String encodeScaledJpegBase64(Bitmap bmp) {
        Bitmap scaled = Bitmap.createScaledBitmap(bmp, 640, 360, true);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.JPEG, 72, bos);
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
    }

    private static Bitmap decodeProcessBitmap(InputStream is) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 2;
            return BitmapFactory.decodeStream(is, null, opts);
        } catch (Throwable t) { return null; }
    }

    private static boolean isMostlyBlackDrmFrame(Bitmap bmp) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int nonBlack = 0;
        for (int x = w / 5; x < (w * 4) / 5; x += Math.max(1, w / 10)) {
            for (int y = h / 5; y < (h * 4) / 5; y += Math.max(1, h / 10)) {
                int p = bmp.getPixel(x, y);
                if (((p >> 16) & 0xFF) + ((p >> 8) & 0xFF) + (p & 0xFF) > 36) nonBlack++;
            }
        }
        return nonBlack < 6;
    }

    private static JSONObject cloneJson(JSONObject src) {
        try { return src != null ? new JSONObject(src.toString()) : new JSONObject(); }
        catch (Exception e) { return new JSONObject(); }
    }
}
