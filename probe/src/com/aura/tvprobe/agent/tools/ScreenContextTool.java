package com.aura.tvprobe;

import org.json.JSONObject;

/**
 * Tool: get_screen_context
 * Inspects what is currently on the TV screen: active OTT playback, open Detail page,
 * focused rail card, Hero slide, or the active show from conversation memory.
 */
public final class ScreenContextTool implements AuraTool {

    @Override
    public String name() { return "get_screen_context"; }

    @Override
    public String statusLabel() { return "Checking what's on your screen\u2026"; }

    @Override
    public JSONObject declaration() {
        return AuraToolSchemaBuilder.declare(name(),
            "Inspect the currently visible TV screen, active OTT playback, focused Launcher card, or open Detail page.",
            new JSONObject());
    }

    @Override
    public JSONObject execute(JSONObject args, AuraToolContext ctx) {
        JSONObject out = new JSONObject();
        try {
            out.put("foreground_app", ctx.getForegroundAppLabel());
            out.put("is_external_ott_app", ctx.isInsideExternalApp());
            JSONObject detected = detectVisibleShow(ctx);
            out.put("found_show", detected != null);
            if (detected != null) putShowSummary(out, detected);
        } catch (Exception ignored) {}
        return out;
    }

    private JSONObject detectVisibleShow(AuraToolContext ctx) {
        if (!ctx.isInsideExternalApp() && ctx.uiBridge != null) {
            JSONObject uiItem = ctx.uiBridge.getActiveScreenItem(ctx.db, ctx.profileId);
            if (uiItem != null) return uiItem;
        }
        if (ctx.inspector != null) ctx.inspector.recordActivePlaybackOnly();
        JSONObject live = InAppVoiceRouter.findActiveItemForPackage(
            ctx.androidContext, ctx.db, ctx.inspector, ctx.profileId, ctx.foregroundPkg);
        return live != null ? live : AuraConversationSession.getActiveShow();
    }

    private void putShowSummary(JSONObject out, JSONObject item) throws Exception {
        String series = item.optString("series_title", "").trim();
        String title = !series.isEmpty() ? series : item.optString("title", "");
        out.put("title", title).put("season", item.optInt("season", 1));
        out.put("episode", item.optInt("episode", 1)).put("position_sec", item.optInt("position_sec", 0));
        out.put("app", item.optString("app", ""));
        AuraConversationSession.setActiveShow(item);
    }
}
