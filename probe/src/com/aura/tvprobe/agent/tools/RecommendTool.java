package com.aura.tvprobe;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Tool: recommend_for_user
 * Generates or re-ranks personalized recommendations tailored to the user's
 * Taste DNA, requested mood, and optional fresh slate refresh.
 */
public final class RecommendTool implements AuraTool {

    @Override
    public String name() { return "recommend_for_user"; }

    @Override
    public String statusLabel() { return "Curating personalized picks for you\u2026"; }

    @Override
    public JSONObject declaration() {
        JSONObject props = buildProps();
        return AuraToolSchemaBuilder.declare(name(),
            "Get personalized movie and series recommendations based on the user's Taste DNA and requested mood.", props);
    }

    private static JSONObject buildProps() {
        JSONObject props = new JSONObject();
        try {
            props.put("mood", AuraToolSchemaBuilder.enumProp("Optional mood filter.",
                TasteIntelligenceEngine.MOOD_ALL, TasteIntelligenceEngine.MOOD_MIND_BENDING,
                TasteIntelligenceEngine.MOOD_LIGHT_SMART, TasteIntelligenceEngine.MOOD_EDGE_OF_SEAT,
                TasteIntelligenceEngine.MOOD_UNDER_2H));
            props.put("vibe_prompt", AuraToolSchemaBuilder.strProp("Optional natural-language vibe or preference."));
            props.put("force_fresh", AuraToolSchemaBuilder.boolProp("True to rotate to a fresh slate of recommendations."));
        } catch (Exception ignored) {}
        return props;
    }

    @Override
    public JSONObject execute(JSONObject args, AuraToolContext ctx) {
        String mood = args.optString("mood", TasteIntelligenceEngine.MOOD_ALL);
        boolean forceFresh = args.optBoolean("force_fresh", false);
        JSONArray picks = fetchAndRankForMood(ctx, mood, forceFresh);
        AuraConversationSession.setLastPresentedResults(picks);
        ctx.visualResults = picks;
        return buildOutput(mood, picks);
    }

    private JSONArray fetchAndRankForMood(AuraToolContext ctx, String mood, boolean forceFresh) {
        JSONArray base = resolveBaseSlate(ctx, forceFresh);
        JSONArray filtered = ctx.db != null ? TasteIntelligenceEngine.filterOutDismissed(ctx.db, ctx.profileId, base) : base;
        java.util.Calendar cal = java.util.Calendar.getInstance();
        java.util.List<JSONObject> ranked = TasteIntelligenceEngine.rerankForMoodAndContext(
            toList(filtered), mood, cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.DAY_OF_WEEK));
        return toJsonArray(ranked);
    }

    private static java.util.List<JSONObject> toList(JSONArray arr) {
        java.util.List<JSONObject> list = new java.util.ArrayList<>();
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) list.add(o);
        }
        return list;
    }

    private static JSONArray toJsonArray(java.util.List<JSONObject> list) {
        JSONArray arr = new JSONArray();
        for (JSONObject o : list) arr.put(o);
        return arr;
    }

    private JSONArray resolveBaseSlate(AuraToolContext ctx, boolean forceFresh) {
        if (ctx.ai != null && ctx.db != null) {
            JSONArray hist = ctx.db.listHistory(ctx.profileId);
            JSONArray fresh = ctx.ai.getOrRefreshForProfile(ctx.profileId, null, hist, forceFresh);
            if (fresh != null && fresh.length() > 0) return fresh;
        }
        return ctx.db != null ? ctx.db.getProfileRecommendations(ctx.profileId) : new JSONArray();
    }

    private JSONObject buildOutput(String mood, JSONArray picks) {
        JSONObject out = new JSONObject();
        try {
            out.put("mood", mood).put("count", picks.length())
                .put("recommendations", SearchCatalogTool.compactSummaryArray(picks));
        } catch (Exception ignored) {}
        return out;
    }
}
