package com.aura.tvprobe;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;
import java.util.Locale;

/**
 * Tool: get_trending_now
 * Fetches real-time daily trending movies & shows across all OTT apps
 * (Netflix, Prime Video, Apple TV+, Disney+ Hotstar, ZEE5) via JustWatch TRENDING API.
 */
public final class TrendingNowTool implements AuraTool {

    @Override
    public String name() { return "get_trending_now"; }

    @Override
    public String statusLabel() { return "Checking what's trending across your apps\u2026"; }

    @Override
    public JSONObject declaration() {
        JSONObject props = new JSONObject();
        try {
            props.put("ott_app", AuraToolSchemaBuilder.strProp("Optional OTT app filter (e.g., Netflix, Prime Video, Hotstar, Apple TV+)."));
        } catch (Exception ignored) {}
        return AuraToolSchemaBuilder.declare(name(),
            "Fetch live daily trending new shows and movies across all OTT apps in the user's region.", props);
    }

    @Override
    public JSONObject execute(JSONObject args, AuraToolContext ctx) {
        String appFilter = args.optString("ott_app", "").trim().toLowerCase(Locale.US);
        List<JSONObject> trending = LiveTrendingCollector.collectQualityTrending(
            ctx.db, ctx.profileId, JustWatchGraph.getCurrentYear(), 18);
        JSONArray filtered = filterByApp(trending, appFilter);
        AuraConversationSession.setLastPresentedResults(filtered);
        ctx.visualResults = filtered;
        return buildOutput(appFilter, filtered);
    }

    private JSONArray filterByApp(List<JSONObject> trending, String appLow) {
        JSONArray out = new JSONArray();
        for (JSONObject item : trending) {
            if (appLow.isEmpty() || item.optString("app", "").toLowerCase(Locale.US).contains(appLow)) out.put(item);
        }
        return out.length() > 0 ? out : toArray(trending);
    }

    private JSONArray toArray(List<JSONObject> list) {
        JSONArray arr = new JSONArray();
        for (JSONObject o : list) arr.put(o);
        return arr;
    }

    private JSONObject buildOutput(String appFilter, JSONArray items) {
        JSONObject out = new JSONObject();
        try {
            out.put("app_filter", appFilter).put("count", items.length())
                .put("trending", SearchCatalogTool.compactSummaryArray(items));
        } catch (Exception ignored) {}
        return out;
    }
}
