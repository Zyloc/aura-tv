package com.aura.tvprobe;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Tool: present_visual_results
 * Attaches curated visual cards, headline, spoken summary, and follow-up chips
 * to the agent turn so Aura's UI renders the results while narrating concisely.
 */
public final class PresentResultsTool implements AuraTool {

    @Override
    public String name() { return "present_visual_results"; }

    @Override
    public String statusLabel() { return "Preparing your picks\u2026"; }

    @Override
    public JSONObject declaration() {
        JSONObject props = new JSONObject();
        try {
            props.put("headline", AuraToolSchemaBuilder.strProp("Short title for the results shelf."));
            props.put("spoken_summary", AuraToolSchemaBuilder.strProp("Concise 1-2 sentence spoken summary for TTS."));
            props.put("follow_up_chips", AuraToolSchemaBuilder.strArrayProp("2-3 short follow-up suggestion chips."));
        } catch (Exception ignored) {}
        return AuraToolSchemaBuilder.declare(name(),
            "Display the search or recommendation cards on screen along with a concise spoken summary.",
            props, "headline", "spoken_summary");
    }

    @Override
    public JSONObject execute(JSONObject args, AuraToolContext ctx) {
        ctx.visualHeadline = args.optString("headline", "Aura Picks").trim();
        ctx.visualSpokenSummary = args.optString("spoken_summary", "").trim();
        ctx.followUpChips = args.optJSONArray("follow_up_chips");
        if (ctx.visualResults == null || ctx.visualResults.length() == 0) {
            ctx.visualResults = AuraConversationSession.getLastPresentedResults();
        }
        publishSessionToDbAndUi(ctx);
        int count = ctx.visualResults != null ? ctx.visualResults.length() : 0;
        try { return new JSONObject().put("presented_count", count); } catch (Exception e) { return new JSONObject(); }
    }

    private void publishSessionToDbAndUi(AuraToolContext ctx) {
        JSONObject payload = buildSessionPayload(ctx);
        if (ctx.db != null) ctx.db.saveVoiceSession(ctx.profileId, payload);
        if (ctx.uiBridge != null) ctx.uiBridge.presentVisualResults(payload);
    }

    private JSONObject buildSessionPayload(AuraToolContext ctx) {
        JSONObject o = new JSONObject();
        try {
            o.put("reply", ctx.visualSpokenSummary.isEmpty() ? ctx.visualHeadline : ctx.visualSpokenSummary);
            o.put("headline", ctx.visualHeadline);
            o.put("results", ctx.visualResults != null ? ctx.visualResults : new JSONArray());
            o.put("follow_ups", ctx.followUpChips != null ? ctx.followUpChips : new JSONArray());
        } catch (Exception ignored) {}
        return o;
    }
}
