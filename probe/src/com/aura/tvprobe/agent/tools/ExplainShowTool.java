package com.aura.tvprobe;

import org.json.JSONObject;

/**
 * Tool: explain_show_or_scene
 * Runs spoiler-safe ShowCompanionAgent synthesis for recaps, character/plot questions,
 * cast X-Ray, and multi-turn follow-ups anchored to the user's exact watch progress.
 */
public final class ExplainShowTool implements AuraTool {

    @Override
    public String name() { return "explain_show_or_scene"; }

    @Override
    public String statusLabel() { return "Checking spoiler-safe show & episode details\u2026"; }

    @Override
    public JSONObject declaration() {
        JSONObject props = buildProps();
        return AuraToolSchemaBuilder.declare(name(),
            "Explain a show, recap up to the user's spoiler-safe episode/timestamp, identify cast, give a spoiler-free episode vibe check (heavy vs light/fun), or answer follow-up questions.",
            props, "question");
    }

    private static JSONObject buildProps() {
        JSONObject props = new JSONObject();
        try {
            props.put("title", AuraToolSchemaBuilder.strProp("Show or movie title, or ordinal like '#2' from last results."));
            props.put("question", AuraToolSchemaBuilder.strProp("The user's exact question or follow-up."));
            props.put("lens", AuraToolSchemaBuilder.enumProp("Explanation lens.",
                ShowCompanionAgent.LENS_FULL_RECAP, ShowCompanionAgent.LENS_RECENT_SCENE,
                ShowCompanionAgent.LENS_CHARACTER_OR_PLOT, ShowCompanionAgent.LENS_ACTOR_XRAY));
        } catch (Exception ignored) {}
        return props;
    }

    @Override
    public JSONObject execute(JSONObject args, AuraToolContext ctx) {
        String titleArg = args.optString("title", "").trim();
        String question = args.optString("question", "Catch me up").trim();
        JSONObject target = resolveTargetItem(titleArg, args.optString("lens", ""), ctx);
        JSONObject recap = ShowCompanionAgent.executeCompanionTurn(
            ctx.ai, ctx.db, ctx.profileId, question, target, ctx.progressListener);
        ctx.companionRecap = recap;
        if (target != null) AuraConversationSession.setActiveShow(target);
        return buildOutput(recap);
    }

    private JSONObject resolveTargetItem(String titleArg, String lens, AuraToolContext ctx) {
        JSONObject item = lookupTitleItem(titleArg, ctx);
        if (item == null) item = AuraConversationSession.getActiveShow();
        if (item == null && ctx.uiBridge != null) item = ctx.uiBridge.getActiveScreenItem(ctx.db, ctx.profileId);
        if (item != null && lens != null && !lens.isEmpty()) {
            try { item.put("agent_lens", lens); } catch (Exception ignored) {}
        }
        return item;
    }

    private JSONObject lookupTitleItem(String titleArg, AuraToolContext ctx) {
        if (titleArg.isEmpty()) return null;
        JSONObject active = AuraConversationSession.getActiveShow();
        if (matchesActiveShow(active, titleArg)) return active;
        JSONObject fromList = AuraConversationSession.resolveResultByTitleOrOrdinal(titleArg);
        if (fromList != null) return ShowCompanionAgent.mergeDetectedShowWithHistory(ctx.db, ctx.profileId, fromList, fromList);
        try {
            JSONObject stub = new JSONObject().put("title", titleArg).put("series_title", titleArg);
            return ShowCompanionAgent.mergeDetectedShowWithHistory(ctx.db, ctx.profileId, stub, stub);
        } catch (Exception e) { return null; }
    }

    private boolean matchesActiveShow(JSONObject active, String titleArg) {
        if (active == null) return false;
        String norm = AuraDb.normalizeTitleKey(titleArg);
        String sKey = AuraDb.normalizeTitleKey(active.optString("series_title", ""));
        String tKey = AuraDb.normalizeTitleKey(active.optString("title", ""));
        return !norm.isEmpty() && (norm.equals(sKey) || norm.equals(tKey));
    }

    private JSONObject buildOutput(JSONObject recap) {
        JSONObject out = new JSONObject();
        try {
            out.put("title", recap.optString("title", ""));
            out.put("headline", recap.optString("headline", "Catch Me Up"));
            out.put("spoken_narration", recap.optString("spoken_narration", ""));
            out.put("cast_xray", recap.optString("cast_xray", ""));
        } catch (Exception ignored) {}
        return out;
    }
}
