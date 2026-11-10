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
 * Pure Agentic Show Companion & "Catch Me Up" Audio Narrator Engine.
 * Routes across 4 lenses (Full Recap, Character/Plot Grep, Immediate 5-Min Scene,
 * and On-Screen Actor X-Ray + Movie Search Handoff) with minimal LLM token usage.
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
        String lens = detectLens(userQuery);
        JSONObject ctx = resolveTargetContext(db, profileId, userQuery, explicitItem);
        String cacheKey = buildRecapCacheKey(ctx, lens, userQuery);
        if (LENS_FULL_RECAP.equals(lens)) {
            JSONObject cached = tryReadCachedRecap(db, cacheKey);
            if (cached != null) return cached;
        }
        JSONObject result = runAgenticLensSynthesis(ai, db, profileId, userQuery, lens, ctx);
        if (LENS_FULL_RECAP.equals(lens) && !result.optString("spoken_narration").isEmpty()) {
            db.saveCachedGti(cacheKey, result.toString());
        }
        return result;
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
        return "recap_v2:" + norm + ":s" + s + "e" + e + ":b" + bucket5m + ":" + lens + ":" + qHash;
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
        String low = cand.toLowerCase(Locale.US);
        return low.equals("what happened") || low.equals("so far") || low.equals("this show")
            || low.equals("this movie") || low.equals("this scene") || low.equals("the last 5 minutes");
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
            boolean isSeries = !"Movie".equalsIgnoreCase(base.optString("type", "Series")) || base.optInt("season", 0) > 0 || base.optInt("episode", 0) > 0;
            base.put("is_series", isSeries);
            if (isSeries && base.optInt("season", 0) <= 0) base.put("season", 1);
            if (isSeries && base.optInt("episode", 0) <= 0) base.put("episode", 1);
            if (base.optInt("position_sec", 0) <= 0) base.put("position_sec", 1450);
            if (base.optInt("duration_sec", 0) <= 0) base.put("duration_sec", 3000);
        } catch (Exception ignored) {}
    }

    private static JSONObject runAgenticLensSynthesis(
            AiRecommender ai, AuraDb db, String profileId, String userQuery, String lens, JSONObject ctx) {
        String title = ctx.optString("title", "MobLand");
        boolean isSeries = ctx.optBoolean("is_series", true);
        int s = ctx.optInt("season", 1);
        int e = ctx.optInt("episode", 1);
        int posSec = ctx.optInt("position_sec", 1450);
        AgentStatusNarrator.publishLiveStep("Stitching " + title + (isSeries ? " S" + s + "E" + e : "") + " transcript up to " + SubtitleAndGuideStore.formatMmSs(posSec));
        JSONObject guide = SubtitleAndGuideStore.getOrFetchShowGuide(db, title, isSeries);
        JSONArray cues = SubtitleAndGuideStore.getOrFetchEpisodeSrtCues(db, title, guide, s, e, isSeries);
        SubtitleAndGuideStore.proactivelyCacheSeriesAsync(db, title, s, e);
        return synthesizeWithTools(ai, db, profileId, userQuery, lens, ctx, guide, cues);
    }

    private static JSONObject synthesizeWithTools(
            AiRecommender ai, AuraDb db, String profileId, String userQuery,
            String lens, JSONObject ctx, JSONObject guide, JSONArray cues) {
        int posSec = ctx.optInt("position_sec", 1450);
        JSONArray sceneActors = SubtitleAndGuideStore.inferActiveSceneActors(guide, cues, posSec);
        String screenFrameB64 = LENS_ACTOR_XRAY.equals(lens) ? captureScreenVisionFrameBase64() : null;
        String prompt = buildCompanionPrompt(userQuery, lens, ctx, guide, cues, sceneActors, screenFrameB64 != null);
        AgentStatusNarrator.publishLiveStep("Preparing 2-minute spoken narration");
        String rawLlm = ai.callGeminiMultimodalJson(prompt, screenFrameB64);
        JSONObject out = parseOrBuildFallbackRecap(rawLlm, lens, ctx, guide, cues, sceneActors);
        maybeChainActorMovieSearch(ai, db, profileId, userQuery, lens, out, sceneActors);
        return out;
    }

    private static String buildCompanionPrompt(
            String userQuery, String lens, JSONObject ctx, JSONObject guide,
            JSONArray cues, JSONArray sceneActors, boolean hasScreenFrame) {
        String title = ctx.optString("title", "MobLand");
        int s = ctx.optInt("season", 1);
        int e = ctx.optInt("episode", 1);
        int posSec = ctx.optInt("position_sec", 1450);
        String toolPacket = buildToolContextPacket(userQuery, lens, guide, cues, sceneActors, s, e, posSec);
        return "You are Aura's Agentic Show Companion & Audio Narrator on Android TV.\n" +
            "Active Show/Movie: \"" + title + "\" | " + (ctx.optBoolean("is_series", true) ? ("Season " + s + ", Episode " + e) : "Movie") +
            " | Current Timestamp: " + SubtitleAndGuideStore.formatMmSs(posSec) + " (" + (posSec / 60) + " mins in).\n" +
            "Detected Intent Lens: " + lens + " | User Query: \"" + (userQuery == null || userQuery.isEmpty() ? "Catch me up on what has happened so far" : userQuery) + "\"\n" +
            (hasScreenFrame ? "A live TV screen frame is attached—inspect it if visible, otherwise rely on the X-Ray Cast & Subtitle tools below.\n" : "") +
            toolPacket + "\n" +
            "Task:\n" +
            "1. Directly fulfill the user's lens using the stitched episode guide, time-sliced subtitle dialogue up to " + SubtitleAndGuideStore.formatMmSs(posSec) + ", and Cast/Character X-Ray.\n" +
            "2. Write 'spoken_narration' (160–240 words, natural conversational storytelling meant to be read aloud by Google Assistant TTS—no markdown symbols or bullet characters in spoken_narration).\n" +
            "3. Return ONLY a JSON object with keys:\n" +
            "   - 'headline': short title (e.g. '" + title + " · S" + s + "E" + e + " (Up to " + SubtitleAndGuideStore.formatMmSs(posSec) + ")')\n" +
            "   - 'spoken_narration': the 160-240 word spoken audio script\n" +
            "   - 'story_so_far': 2-3 sentence summary of prior episodes/setup\n" +
            "   - 'current_episode_beats': 2-3 sentences of what happened up to " + SubtitleAndGuideStore.formatMmSs(posSec) + " in this episode\n" +
            "   - 'cast_xray': string listing the key actors & characters in this scene\n" +
            "   - 'actor_to_search': exact real Actor Name ONLY if the user asked to find/search movies or shows with an actor, else \"\"";
    }

    private static String buildToolContextPacket(
            String userQuery, String lens, JSONObject guide, JSONArray cues,
            JSONArray sceneActors, int s, int e, int posSec) {
        StringBuilder sb = new StringBuilder();
        sb.append("TOOL 1 [EPISODE GUIDE UP TO S").append(s).append("E").append(e).append("]:\n")
          .append(SubtitleAndGuideStore.buildHierarchicalEpisodeStitch(guide, s, e)).append("\n");
        sb.append("TOOL 2 [ACTIVE SCENE CAST X-RAY (Actor <-> Character)]:\n")
          .append(sceneActors.toString()).append("\n");
        appendLensSpecificSrtTool(userQuery, lens, cues, posSec, sb);
        return sb.toString();
    }

    private static void appendLensSpecificSrtTool(
            String userQuery, String lens, JSONArray cues, int posSec, StringBuilder sb) {
        if (LENS_RECENT_SCENE.equals(lens) || LENS_ACTOR_XRAY.equals(lens)) {
            sb.append("TOOL 3 [VERBATIM SUBTITLE DIALOGUE IN CURRENT SCENE (Last 4 Mins up to ")
              .append(SubtitleAndGuideStore.formatMmSs(posSec)).append(")]:\n")
              .append(SubtitleAndGuideStore.sliceRawWindow(cues, posSec - 240, posSec)).append("\n");
        } else if (LENS_CHARACTER_OR_PLOT.equals(lens)) {
            List<String> terms = extractQuerySearchTerms(userQuery);
            sb.append("TOOL 3 [GREPPED SUBTITLE DIALOGUE MENTIONS FOR ").append(terms).append(" UP TO ")
              .append(SubtitleAndGuideStore.formatMmSs(posSec)).append("]:\n")
              .append(SubtitleAndGuideStore.grepCharacterOrTopic(cues, posSec, terms)).append("\n");
        } else {
            sb.append("TOOL 3 [5-MIN SCENE-BEAT COMPRESSED SUBTITLES (00:00 -> ")
              .append(SubtitleAndGuideStore.formatMmSs(posSec)).append(")]:\n")
              .append(SubtitleAndGuideStore.sliceCompressedSceneBeats(cues, posSec)).append("\n");
        }
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
        try {
            if (rawLlm != null && !rawLlm.trim().isEmpty()) {
                JSONObject parsed = new JSONObject(rawLlm.trim());
                if (!parsed.optString("spoken_narration").isEmpty()) return attachContextMeta(parsed, lens, ctx, sceneActors);
            }
        } catch (Exception ignored) {}
        return buildDeterministicFallbackRecap(lens, ctx, guide, cues, sceneActors);
    }

    private static JSONObject buildDeterministicFallbackRecap(
            String lens, JSONObject ctx, JSONObject guide, JSONArray cues, JSONArray sceneActors) {
        JSONObject o = new JSONObject();
        try {
            String title = ctx.optString("title", "MobLand");
            int s = ctx.optInt("season", 1);
            int e = ctx.optInt("episode", 1);
            int posSec = ctx.optInt("position_sec", 1450);
            String stitch = SubtitleAndGuideStore.buildHierarchicalEpisodeStitch(guide, s, e);
            String castStr = formatSceneActorsReadable(sceneActors);
            String spoken = "Here is your catch up for " + title + ", Season " + s + ", Episode " + e + " up to minute " + (posSec / 60) + ". " +
                stitch.replace("\n", " ") + (castStr.isEmpty() ? "" : " Key cast in this episode includes " + castStr + ".");
            o.put("headline", title + " · S" + s + "E" + e + " (Up to " + SubtitleAndGuideStore.formatMmSs(posSec) + ")");
            o.put("spoken_narration", spoken).put("story_so_far", stitch).put("cast_xray", castStr);
        } catch (Exception ignored) {}
        return attachContextMeta(o, lens, ctx, sceneActors);
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
            if (sb.length() > 0) sb.append(" · ");
            sb.append(c.optString("actor")).append(" (").append(c.optString("character")).append(")");
        }
        return sb.toString();
    }

    private static void maybeChainActorMovieSearch(
            AiRecommender ai, AuraDb db, String profileId, String userQuery,
            String lens, JSONObject out, JSONArray sceneActors) {
        String low = userQuery != null ? userQuery.toLowerCase(Locale.US) : "";
        boolean wantsMovies = low.contains("movie") || low.contains("show") || low.contains("search") || low.contains("find") || low.contains("other");
        String actor = out.optString("actor_to_search", "").trim();
        if (actor.isEmpty() && wantsMovies && sceneActors != null && sceneActors.length() > 0) {
            actor = sceneActors.optJSONObject(0).optString("actor", "");
        }
        if (actor.isEmpty() || !wantsMovies) return;
        appendActorSearchResults(ai, db, profileId, actor, out);
    }

    private static void appendActorSearchResults(
            AiRecommender ai, AuraDb db, String profileId, String actor, JSONObject out) {
        try {
            AgentStatusNarrator.publishLiveStep("Searching movies & shows starring " + actor);
            JSONArray specs = new JSONArray().put(new JSONObject().put("searchQuery", actor).put("includeSimilar", false).put("minYear", 1995));
            JSONArray castHits = JustWatchGraph.executeScatterGatherSpecs(specs);
            JSONArray enriched = ai.enrichAll(castHits, 1995, JustWatchGraph.getCurrentYear());
            out.put("actor_to_search", actor).put("results", enriched);
        } catch (Exception ignored) {}
    }

    /**
     * Captures a live TV frame via screencap and returns Base64 JPEG ONLY if the frame
     * contains non-black visual pixels (e.g. YouTube/SmartTube/Plex or unencrypted pause UI).
     */
    public static String captureScreenVisionFrameBase64() {
        try {
            Process proc = Runtime.getRuntime().exec(new String[]{"sh", "-c", "screencap -p"});
            Bitmap bmp = decodeProcessBitmap(proc.getInputStream());
            if (bmp == null || isMostlyBlackDrmFrame(bmp)) return null;
            Bitmap scaled = Bitmap.createScaledBitmap(bmp, 640, 360, true);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, 72, bos);
            return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable t) {
            return null;
        }
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
