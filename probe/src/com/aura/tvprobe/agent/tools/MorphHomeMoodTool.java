package com.aura.tvprobe;

import org.json.JSONObject;

/**
 * Tool #17: morph_home_mood
 * Voice-steered Home screen morphing: immediately re-ranks the live Aura TV Home rails
 * to match the user's requested mood ("cozy", "light", "mind-bending", "thriller", "under 2 hours")
 * and optionally persists a preference note.
 */
public final class MorphHomeMoodTool implements AuraTool {

    @Override
    public String name() { return "morph_home_mood"; }

    @Override
    public String statusLabel() { return "Morphing your Home screen to match your mood\u2026"; }

    @Override
    public JSONObject declaration() {
        JSONObject props = buildProps();
        return AuraToolSchemaBuilder.declare(name(),
            "Instantly morph and re-rank the live Aura TV Home screen to match the user's mood (e.g. cozy/light, mind-bending sci-fi, edge-of-seat thriller, or quick under-2h picks).",
            props, "mood");
    }

    private static JSONObject buildProps() {
        JSONObject props = new JSONObject();
        try {
            props.put("mood", AuraToolSchemaBuilder.enumProp("Target Home mood preset.",
                TasteIntelligenceEngine.MOOD_ALL, TasteIntelligenceEngine.MOOD_LIGHT_SMART,
                TasteIntelligenceEngine.MOOD_MIND_BENDING, TasteIntelligenceEngine.MOOD_EDGE_OF_SEAT,
                TasteIntelligenceEngine.MOOD_UNDER_2H));
            props.put("remember_note", AuraToolSchemaBuilder.strProp("Optional preference to remember (e.g. 'Prefers cozy light shows this week')."));
            props.put("force_fresh", AuraToolSchemaBuilder.boolProp("Set true to fetch brand-new picks in addition to instant re-ranking."));
        } catch (Exception ignored) {}
        return props;
    }

    @Override
    public JSONObject execute(JSONObject args, AuraToolContext ctx) {
        String mood = args.optString("mood", TasteIntelligenceEngine.MOOD_ALL);
        String note = args.optString("remember_note", "").trim();
        boolean forceFresh = args.optBoolean("force_fresh", false);
        if (!note.isEmpty() && ctx.db != null) UserMemoryStore.saveMemory(ctx.db, ctx.profileId, note);
        if (ctx.uiBridge != null) ctx.uiBridge.applyMoodAndRefresh(mood, forceFresh);
        ctx.visualHeadline = "Home Mood Updated";
        ctx.visualSpokenSummary = buildSpokenConfirmation(mood, note);
        ctx.actionExecutedDirectly = true;
        return buildResponse(mood, note, forceFresh);
    }

    private static String buildSpokenConfirmation(String mood, String note) {
        String label = TasteIntelligenceEngine.MOOD_LIGHT_SMART.equals(mood) ? "Light & Smart"
            : TasteIntelligenceEngine.MOOD_MIND_BENDING.equals(mood) ? "Mind-Bending"
            : TasteIntelligenceEngine.MOOD_EDGE_OF_SEAT.equals(mood) ? "Edge-of-Seat"
            : TasteIntelligenceEngine.MOOD_UNDER_2H.equals(mood) ? "Quick Under-2h" : "All";
        return note.isEmpty() ? ("Morphed your Home screen to " + label + " mode.")
            : ("Morphed your Home screen to " + label + " mode and remembered your preference.");
    }

    private static JSONObject buildResponse(String mood, String note, boolean forceFresh) {
        JSONObject out = new JSONObject();
        try {
            out.put("status", "morphed").put("mood", mood).put("saved_note", !note.isEmpty()).put("force_fresh", forceFresh);
        } catch (Exception ignored) {}
        return out;
    }
}
