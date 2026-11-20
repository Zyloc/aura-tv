package com.aura.tvprobe;

import org.json.JSONObject;

/**
 * Tool: in_app_ott_search
 * Executes native search directly inside the currently open external OTT app
 * (Netflix, Prime Video, Hotstar, YouTube, etc.).
 */
public final class InAppOttSearchTool implements AuraTool {

    @Override
    public String name() { return "in_app_ott_search"; }

    @Override
    public String statusLabel() { return "Searching inside app\u2026"; }

    @Override
    public JSONObject declaration() {
        JSONObject props = new JSONObject();
        try {
            props.put("query", AuraToolSchemaBuilder.strProp("Clean title or keyword to type into the active OTT app's search."));
            props.put("app_name", AuraToolSchemaBuilder.strProp("Optional OTT app name if known."));
        } catch (Exception ignored) {}
        return AuraToolSchemaBuilder.declare(name(),
            "Run a native search inside the currently open external OTT app (e.g., Netflix, Prime Video, YouTube).",
            props, "query");
    }

    @Override
    public JSONObject execute(JSONObject args, AuraToolContext ctx) {
        String query = args.optString("query", "").trim();
        boolean launched = !query.isEmpty() && ctx.uiBridge != null
            && ctx.uiBridge.performInAppSearch(ctx.foregroundPkg, query);
        if (launched) ctx.actionExecutedDirectly = true;
        return buildResult(query, launched);
    }

    private JSONObject buildResult(String query, boolean launched) {
        JSONObject out = new JSONObject();
        try { out.put("query", query).put("dispatched", launched); }
        catch (Exception ignored) {}
        return out;
    }
}
