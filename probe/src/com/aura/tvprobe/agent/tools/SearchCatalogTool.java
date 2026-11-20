package com.aura.tvprobe;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;

/**
 * Tool: search_catalog
 * Searches JustWatch Graph & Aura's catalog with optional mood, OTT app,
 * max runtime, and minimum release year filters.
 */
public final class SearchCatalogTool implements AuraTool {

    @Override
    public String name() { return "search_catalog"; }

    @Override
    public String statusLabel() { return "Searching across your streaming apps\u2026"; }

    @Override
    public JSONObject declaration() {
        JSONObject props = new JSONObject();
        try {
            props.put("query", AuraToolSchemaBuilder.strProp("Search query, title, actor, or genre/vibe."));
            props.put("ott_app", AuraToolSchemaBuilder.strProp("Optional OTT app filter (e.g., Netflix, Prime Video, Hotstar, Apple TV+)."));
            props.put("max_runtime_min", AuraToolSchemaBuilder.intProp("Optional maximum runtime in minutes (e.g., 120)."));
            props.put("min_year", AuraToolSchemaBuilder.intProp("Optional minimum release year (e.g., 2020)."));
        } catch (Exception ignored) {}
        return AuraToolSchemaBuilder.declare(name(),
            "Search movies and TV shows across streaming apps and return ranked matches.", props, "query");
    }

    @Override
    public JSONObject execute(JSONObject args, AuraToolContext ctx) {
        String query = args.optString("query", "").trim();
        JSONArray raw = fetchCandidates(query, ctx);
        JSONArray filtered = applyFilters(raw, args.optString("ott_app", ""), args.optInt("max_runtime_min", 0), args.optInt("min_year", 0));
        if (filtered.length() == 0) filtered = raw;
        AuraConversationSession.setLastPresentedResults(filtered);
        ctx.visualResults = filtered;
        return buildToolOutput(query, filtered);
    }

    private JSONArray fetchCandidates(String query, AuraToolContext ctx) {
        JSONArray hits = !query.isEmpty() ? JustWatchGraph.fetchDeterministicSearchCandidates(query) : new JSONArray();
        if (hits != null && hits.length() > 0) return hits;
        return ctx.db != null ? filterLocalRecos(ctx.db.getProfileRecommendations(ctx.profileId), query) : new JSONArray();
    }

    private JSONArray filterLocalRecos(JSONArray recos, String query) {
        JSONArray out = new JSONArray();
        String low = query.toLowerCase(Locale.US);
        for (int i = 0; i < recos.length() && out.length() < 8; i++) {
            JSONObject o = recos.optJSONObject(i);
            if (o != null && matchesLocalQuery(o, low)) out.put(o);
        }
        return out.length() > 0 ? out : recos;
    }

    private boolean matchesLocalQuery(JSONObject o, String low) {
        String hay = (o.optString("title", "") + " " + o.optString("genres", "") + " " + o.optString("synopsis", "")).toLowerCase(Locale.US);
        return low.isEmpty() || hay.contains(low);
    }

    private JSONArray applyFilters(JSONArray raw, String ottApp, int maxRuntime, int minYear) {
        JSONArray out = new JSONArray();
        String appLow = ottApp != null ? ottApp.trim().toLowerCase(Locale.US) : "";
        for (int i = 0; i < raw.length() && out.length() < 10; i++) {
            JSONObject item = raw.optJSONObject(i);
            if (item != null && passesFilters(item, appLow, maxRuntime, minYear)) out.put(item);
        }
        return out;
    }

    private boolean passesFilters(JSONObject item, String appLow, int maxRuntime, int minYear) {
        if (!appLow.isEmpty() && !item.optString("app", "").toLowerCase(Locale.US).contains(appLow)) return false;
        if (maxRuntime > 0 && item.optInt("runtime_min", 0) > maxRuntime) return false;
        return minYear <= 0 || item.optInt("year", 9999) >= minYear;
    }

    private JSONObject buildToolOutput(String query, JSONArray items) {
        JSONObject out = new JSONObject();
        try {
            out.put("query", query).put("count", items.length()).put("results", compactSummaryArray(items));
        } catch (Exception ignored) {}
        return out;
    }

    static JSONArray compactSummaryArray(JSONArray items) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < items.length() && i < 8; i++) {
            JSONObject it = items.optJSONObject(i);
            if (it != null) out.put(compactSummaryItem(i + 1, it));
        }
        return out;
    }

    private static JSONObject compactSummaryItem(int rank, JSONObject it) {
        JSONObject s = new JSONObject();
        try {
            s.put("rank", rank).put("title", it.optString("title", "")).put("year", it.optInt("year", 0));
            s.put("app", it.optString("app", "")).put("rating", it.optString("rating", ""));
            s.put("genres", it.optString("genres", "")).put("reason", it.optString("reason", ""));
        } catch (Exception ignored) {}
        return s;
    }
}
