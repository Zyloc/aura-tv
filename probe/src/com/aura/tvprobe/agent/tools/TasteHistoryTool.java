package com.aura.tvprobe;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Tool: get_user_taste_and_history
 * Returns the active profile's Taste DNA, watch anchors, recent episode progress,
 * and optional lookup for a specific title in watch history.
 */
public final class TasteHistoryTool implements AuraTool {

    @Override
    public String name() { return "get_user_taste_and_history"; }

    @Override
    public String statusLabel() { return "Checking your watch history & Taste DNA\u2026"; }

    @Override
    public JSONObject declaration() {
        JSONObject props = new JSONObject();
        try { props.put("title_filter", AuraToolSchemaBuilder.strProp("Optional show or movie title to look up progress for.")); }
        catch (Exception ignored) {}
        return AuraToolSchemaBuilder.declare(name(),
            "Retrieve the user's Taste DNA, favorite genres, recent watch history, and episode progress.", props);
    }

    @Override
    public JSONObject execute(JSONObject args, AuraToolContext ctx) {
        JSONObject out = new JSONObject();
        if (ctx.db == null) return out;
        try {
            out.put("profile_id", ctx.profileId);
            out.put("taste_dna", TasteIntelligenceEngine.getTasteDnaPromptSummary(ctx.db, ctx.profileId));
            out.put("recent_history", compactHistory(ctx.db.listHistory(ctx.profileId), args.optString("title_filter", "")));
        } catch (Exception ignored) {}
        return out;
    }

    private JSONArray compactHistory(JSONArray history, String titleFilter) {
        JSONArray out = new JSONArray();
        String normFilter = titleFilter != null ? AuraDb.normalizeTitleKey(titleFilter) : "";
        for (int i = 0; i < history.length() && out.length() < 8; i++) {
            JSONObject h = history.optJSONObject(i);
            if (h != null && matchesFilter(h, normFilter)) out.put(compactEntry(h));
        }
        return out;
    }

    private boolean matchesFilter(JSONObject h, String normFilter) {
        if (normFilter.isEmpty()) return true;
        String key = AuraDb.normalizeTitleKey(h.optString("title", h.optString("series_title", "")));
        return key.contains(normFilter) || normFilter.contains(key);
    }

    private JSONObject compactEntry(JSONObject h) {
        JSONObject o = new JSONObject();
        try {
            o.put("title", h.optString("title", h.optString("series_title", "")));
            o.put("season", h.optInt("season", 1)).put("episode", h.optInt("episode", 1));
            o.put("position_sec", h.optInt("position_sec", 0)).put("app", h.optString("app", ""));
        } catch (Exception ignored) {}
        return o;
    }
}
