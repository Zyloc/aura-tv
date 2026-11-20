package com.aura.tvprobe;

import android.content.Context;
import org.json.JSONObject;

/**
 * Production UiActionBridge wiring Aura's agent tools to live Android TV UI,
 * deep-link playback, in-app OTT search, and Companion audio/overlay controls.
 */
public final class AuraDefaultUiBridge implements AuraToolContext.UiActionBridge {

    private final Context ctx;
    private final AuraDb db;
    private final TvOverlayManager overlay;
    private final String fgPkg;

    public AuraDefaultUiBridge(Context ctx, AuraDb db, TvOverlayManager overlay, String fgPkg) {
        this.ctx = ctx;
        this.db = db;
        this.overlay = overlay;
        this.fgPkg = fgPkg != null ? fgPkg : "";
    }

    @Override
    public JSONObject getActiveScreenItem(AuraDb auraDb, String profileId) {
        return AuraActivity.getActiveCompanionItem(auraDb != null ? auraDb : db, profileId);
    }

    @Override
    public boolean openOrPlayTitle(JSONObject item, boolean autoPlay) {
        return AuraActivity.openItemDetailOrPlay(ctx, db, item, autoPlay);
    }

    @Override
    public void applyMoodAndRefresh(String mood, boolean forceFresh) {
        AuraActivity.applyMoodAndRefreshFromAgent(mood, forceFresh);
    }

    @Override
    public void presentVisualResults(JSONObject sessionPayload) {
        AuraActivity.displayAgentSearchSession(ctx, sessionPayload);
    }

    @Override
    public boolean performInAppSearch(String appPkg, String query) {
        String pkg = (appPkg != null && !appPkg.isEmpty()) ? appPkg : fgPkg;
        if (pkg.isEmpty() || query == null || query.trim().isEmpty()) return false;
        InAppVoiceRouter.executeInAppSearchDirect(ctx, overlay, pkg, query.trim());
        return true;
    }

    @Override
    public void performTvControl(String command) {
        if ("go_home".equals(command)) { AuraActivity.onHardwareHomeKeyPressed(ctx); return; }
        if ("stop_narration".equals(command) || "dismiss_overlay".equals(command)) {
            InAppVoiceRouter.cancelActiveCompanionTurn(ctx, overlay, fgPkg);
            return;
        }
        if ("pause".equals(command)) CompanionAudioFocusController.engageCompanionSilence(ctx);
        if ("resume".equals(command)) CompanionAudioFocusController.releaseCompanionSilence(ctx);
    }
}
