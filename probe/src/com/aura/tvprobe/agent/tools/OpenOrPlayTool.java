package com.aura.tvprobe;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Tool: open_title_or_play
 * Resolves a movie or series by title, ordinal ("#2", "second"), or active show,
 * and launches direct OTT playback by default (auto_play = true).
 */
public final class OpenOrPlayTool implements AuraTool {

    @Override
    public String name() { return "open_title_or_play"; }

    @Override
    public String statusLabel() { return "Launching playback\u2026"; }

    @Override
    public JSONObject declaration() {
        JSONObject props = new JSONObject();
        try {
            props.put("title", AuraToolSchemaBuilder.strProp("Title name, ordinal like '#2'/'second', or 'it' for the active show."));
            props.put("auto_play", AuraToolSchemaBuilder.boolProp("True to play directly on OTT app (default), false to open Detail page."));
        } catch (Exception ignored) {}
        return AuraToolSchemaBuilder.declare(name(),
            "Play a movie or series directly on its OTT app or open its detail page.", props, "title");
    }

    @Override
    public JSONObject execute(JSONObject args, AuraToolContext ctx) {
        String title = args.optString("title", "").trim();
        boolean autoPlay = args.optBoolean("auto_play", true);
        JSONObject item = resolveItem(title, ctx);
        if (item == null) return buildResult(title, false, autoPlay);
        boolean ok = ctx.uiBridge != null && ctx.uiBridge.openOrPlayTitle(item, autoPlay);
        ctx.openedItem = item;
        if (ok) ctx.actionExecutedDirectly = true;
        return buildResult(item.optString("title", title), ok, autoPlay);
    }

    private JSONObject resolveItem(String title, AuraToolContext ctx) {
        JSONObject fromList = AuraConversationSession.resolveResultByTitleOrOrdinal(title);
        if (fromList != null) return fromList;
        if (isPronounReference(title)) return AuraConversationSession.getActiveShow();
        JSONObject fromRecos = findInProfileRecos(ctx, title);
        return fromRecos != null ? fromRecos : JustWatchGraph.lookupTitle(title);
    }

    private boolean isPronounReference(String title) {
        String low = title.toLowerCase(java.util.Locale.US);
        return low.equals("it") || low.equals("this") || low.equals("that") || low.equals("that one");
    }

    private JSONObject findInProfileRecos(AuraToolContext ctx, String title) {
        if (ctx.db == null) return null;
        String norm = AuraDb.normalizeTitleKey(title);
        JSONArray recos = ctx.db.getProfileRecommendations(ctx.profileId);
        for (int i = 0; i < recos.length(); i++) {
            JSONObject o = recos.optJSONObject(i);
            if (o != null && AuraDb.normalizeTitleKey(o.optString("title", "")).equals(norm)) return o;
        }
        return null;
    }

    private JSONObject buildResult(String title, boolean launched, boolean autoPlay) {
        JSONObject out = new JSONObject();
        try { out.put("title", title).put("launched", launched).put("auto_play", autoPlay); }
        catch (Exception ignored) {}
        return out;
    }
}
