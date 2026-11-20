package com.aura.tvprobe;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Shared execution context passed into every AuraTool invocation during an agent turn.
 * Collects side-effect outputs (visual results, playback actions, clarification flags).
 */
public final class AuraToolContext {

    public interface UiActionBridge {
        JSONObject getActiveScreenItem(AuraDb db, String profileId);
        boolean openOrPlayTitle(JSONObject item, boolean autoPlay);
        void applyMoodAndRefresh(String mood, boolean forceFresh);
        void presentVisualResults(JSONObject sessionPayload);
        boolean performInAppSearch(String appPkg, String query);
        void performTvControl(String command);
    }

    public final Context androidContext;
    public final AuraDb db;
    public final AiRecommender ai;
    public final SessionInspector inspector;
    public final TvOverlayManager overlay;
    public final String profileId;
    public final String foregroundPkg;
    public final UiActionBridge uiBridge;

    public JSONArray visualResults;
    public JSONArray followUpChips;
    public String visualHeadline = "";
    public String visualSpokenSummary = "";
    public JSONObject companionRecap;
    public JSONObject openedItem;
    public interface VisionFrameProvider {
        String captureBase64Jpeg();
    }

    public boolean isClarification = false;
    public String clarificationQuestion = "";
    public boolean actionExecutedDirectly = false;
    public AiRecommender.AgentProgressListener progressListener;
    public VisionFrameProvider visionFrameProvider;

    public AuraToolContext(
            Context androidContext, AuraDb db, AiRecommender ai, SessionInspector inspector,
            TvOverlayManager overlay, String profileId, String foregroundPkg, UiActionBridge uiBridge) {
        this.androidContext = androidContext;
        this.db = db;
        this.ai = ai;
        this.inspector = inspector;
        this.overlay = overlay;
        this.profileId = (profileId != null && !profileId.isEmpty()) ? profileId : AuraDb.DEFAULT_PROFILE;
        this.foregroundPkg = foregroundPkg != null ? foregroundPkg : "";
        this.uiBridge = uiBridge;
    }

    public boolean isInsideExternalApp() {
        return !foregroundPkg.isEmpty() && !foregroundPkg.contains("com.aura.tvprobe");
    }

    public String getForegroundAppLabel() {
        if (!isInsideExternalApp()) return "Aura TV Launcher";
        String label = SessionInspector.friendlyApp(foregroundPkg);
        return (label != null && !label.isEmpty()) ? label : foregroundPkg;
    }
}
