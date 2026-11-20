package com.aura.tvprobe;

import org.json.JSONObject;

/**
 * Tool: update_taste_preference
 * Persists user taste feedback ("not_interested", "mark_watched", "set_mood", "refresh_home")
 * and immediately updates Aura's Home rails and Taste DNA.
 */
public final class UpdateTasteTool implements AuraTool {

    @Override
    public String name() { return "update_taste_preference"; }

    @Override
    public String statusLabel() { return "Updating your Taste DNA & Home\u2026"; }

    @Override
    public JSONObject declaration() {
        JSONObject props = buildProps();
        return AuraToolSchemaBuilder.declare(name(),
            "Update user Taste DNA (hide/not interested, mark watched, switch mood, or refresh Home recommendations).",
            props, "action");
    }

    private static JSONObject buildProps() {
        JSONObject props = new JSONObject();
        try {
            props.put("action", AuraToolSchemaBuilder.enumProp("Taste update action.", "not_interested", "mark_watched", "set_mood", "refresh_home"));
            props.put("title", AuraToolSchemaBuilder.strProp("Title to dismiss or mark watched (optional for mood/refresh)."));
            props.put("mood", AuraToolSchemaBuilder.enumProp("Target mood for set_mood.", TasteIntelligenceEngine.MOOD_ALL,
                TasteIntelligenceEngine.MOOD_MIND_BENDING, TasteIntelligenceEngine.MOOD_LIGHT_SMART,
                TasteIntelligenceEngine.MOOD_EDGE_OF_SEAT, TasteIntelligenceEngine.MOOD_UNDER_2H));
        } catch (Exception ignored) {}
        return props;
    }

    @Override
    public JSONObject execute(JSONObject args, AuraToolContext ctx) {
        String action = args.optString("action", "").trim();
        String title = args.optString("title", "").trim();
        String mood = args.optString("mood", TasteIntelligenceEngine.MOOD_ALL);
        boolean ok = applyAction(action, title, mood, ctx);
        return buildOutput(action, title, mood, ok);
    }

    private boolean applyAction(String action, String title, String mood, AuraToolContext ctx) {
        if ("not_interested".equals(action)) return applyNotInterested(title, ctx);
        if ("mark_watched".equals(action)) return applyMarkWatched(title, ctx);
        if ("set_mood".equals(action)) return applyMoodRefresh(mood, false, ctx);
        if ("refresh_home".equals(action)) return applyMoodRefresh(mood, true, ctx);
        return false;
    }

    private boolean applyNotInterested(String title, AuraToolContext ctx) {
        if (ctx.db == null) return false;
        JSONObject item = resolveTargetItem(title, ctx);
        if (item == null) return false;
        TasteIntelligenceEngine.recordNotInterested(ctx.db, ctx.profileId, item);
        if (ctx.uiBridge != null) ctx.uiBridge.applyMoodAndRefresh(TasteIntelligenceEngine.MOOD_ALL, false);
        return true;
    }

    private boolean applyMarkWatched(String title, AuraToolContext ctx) {
        if (ctx.db == null) return false;
        JSONObject item = resolveTargetItem(title, ctx);
        if (item == null) return false;
        ctx.db.markTitleWatched(item, true);
        TasteIntelligenceEngine.buildAndPersistTasteDna(ctx.db, ctx.profileId);
        if (ctx.uiBridge != null) ctx.uiBridge.applyMoodAndRefresh(TasteIntelligenceEngine.MOOD_ALL, false);
        return true;
    }

    private boolean applyMoodRefresh(String mood, boolean forceFresh, AuraToolContext ctx) {
        if (ctx.db != null) ctx.db.setSetting("active_mood_" + ctx.profileId, mood);
        if (ctx.uiBridge != null) ctx.uiBridge.applyMoodAndRefresh(mood, forceFresh);
        return true;
    }

    private JSONObject resolveTargetItem(String title, AuraToolContext ctx) {
        if (!title.isEmpty()) {
            JSONObject fromList = AuraConversationSession.resolveResultByTitleOrOrdinal(title);
            if (fromList != null) return fromList;
            try { return new JSONObject().put("title", title); } catch (Exception ignored) {}
        }
        JSONObject screen = ctx.uiBridge != null ? ctx.uiBridge.getActiveScreenItem(ctx.db, ctx.profileId) : null;
        return screen != null ? screen : AuraConversationSession.getActiveShow();
    }

    private JSONObject buildOutput(String action, String title, String mood, boolean ok) {
        JSONObject out = new JSONObject();
        try { out.put("action", action).put("title", title).put("mood", mood).put("updated", ok); }
        catch (Exception ignored) {}
        return out;
    }
}
