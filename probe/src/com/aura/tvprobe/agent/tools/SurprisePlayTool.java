package com.aura.tvprobe;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Tool: play_surprise_pick
 * Pillar 2 "Just Play Something Good": selects the #1 highest-confidence match
 * honoring mood, max runtime, dismissed titles, and Taste DNA, then launches
 * direct OTT playback immediately.
 */
public final class SurprisePlayTool implements AuraTool {

    @Override
    public String name() { return "play_surprise_pick"; }

    @Override
    public String statusLabel() { return "Picking your best match & launching\u2026"; }

    @Override
    public JSONObject declaration() {
        JSONObject props = buildProps();
        return AuraToolSchemaBuilder.declare(name(),
            "Pick the single best movie or show for the user right now and play it directly on its OTT app.", props);
    }

    private static JSONObject buildProps() {
        JSONObject props = new JSONObject();
        try {
            props.put("mood", AuraToolSchemaBuilder.enumProp("Optional mood preference.",
                TasteIntelligenceEngine.MOOD_ALL, TasteIntelligenceEngine.MOOD_MIND_BENDING,
                TasteIntelligenceEngine.MOOD_LIGHT_SMART, TasteIntelligenceEngine.MOOD_EDGE_OF_SEAT,
                TasteIntelligenceEngine.MOOD_UNDER_2H));
            props.put("max_runtime_min", AuraToolSchemaBuilder.intProp("Optional max runtime in minutes (e.g., 45 or 110)."));
        } catch (Exception ignored) {}
        return props;
    }

    @Override
    public JSONObject execute(JSONObject args, AuraToolContext ctx) {
        String mood = args.optString("mood", TasteIntelligenceEngine.MOOD_ALL);
        int maxRuntime = args.optInt("max_runtime_min", 0);
        JSONObject pick = selectTopMatch(ctx, mood, maxRuntime);
        if (pick == null) return buildOutput(null, false);
        boolean ok = ctx.uiBridge != null && ctx.uiBridge.openOrPlayTitle(pick, true);
        ctx.openedItem = pick;
        if (ok) ctx.actionExecutedDirectly = true;
        return buildOutput(pick, ok);
    }

    private JSONObject selectTopMatch(AuraToolContext ctx, String mood, int maxRuntime) {
        JSONArray base = ctx.db != null ? ctx.db.getProfileRecommendations(ctx.profileId) : new JSONArray();
        JSONArray filtered = ctx.db != null ? TasteIntelligenceEngine.filterOutDismissed(ctx.db, ctx.profileId, base) : base;
        Calendar cal = Calendar.getInstance();
        List<JSONObject> ranked = TasteIntelligenceEngine.rerankForMoodAndContext(
            toList(filtered), mood, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.DAY_OF_WEEK));
        for (JSONObject item : ranked) {
            if (maxRuntime <= 0 || item.optInt("runtime_min", 0) <= maxRuntime) return item;
        }
        return ranked.isEmpty() ? null : ranked.get(0);
    }

    private static List<JSONObject> toList(JSONArray arr) {
        List<JSONObject> out = new ArrayList<>();
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) out.add(o);
        }
        return out;
    }

    private JSONObject buildOutput(JSONObject pick, boolean launched) {
        JSONObject out = new JSONObject();
        try {
            out.put("launched", launched);
            if (pick != null) {
                out.put("title", pick.optString("title", "")).put("app", pick.optString("app", ""))
                    .put("reason", pick.optString("reason", "")).put("runtime_min", pick.optInt("runtime_min", 0));
            }
        } catch (Exception ignored) {}
        return out;
    }
}
