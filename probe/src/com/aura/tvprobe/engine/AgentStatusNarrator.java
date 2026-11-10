package com.aura.tvprobe;

import org.json.JSONArray;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Generates and streams live, non-technical, conversational progress messages
 * from Gemini for the AgenticOrbView during Voice Search, Similar Exploration,
 * and Home Recommendation Refreshes.
 */
public final class AgentStatusNarrator {

    private final AtomicBoolean active = new AtomicBoolean(true);
    private final List<String> stepQueue = Collections.synchronizedList(new ArrayList<>());
    private final AiRecommender.AgentProgressListener listener;

    private AgentStatusNarrator(AiRecommender.AgentProgressListener listener) {
        this.listener = listener;
    }

    public static AgentStatusNarrator start(
        AiRecommender ai, AuraDb db, String profileId, String userQuery, AiRecommender.AgentProgressListener progress
    ) {
        AgentStatusNarrator narrator = new AgentStatusNarrator(progress);
        if (progress != null) narrator.launchAsyncNarration(ai, db, profileId, userQuery);
        return narrator;
    }

    public void stop() {
        active.set(false);
    }

    public void appendSteps(JSONArray arr) {
        if (arr == null || !active.get()) return;
        for (int i = 0; i < arr.length(); i++) {
            String clean = sanitizeStep(arr.optString(i, ""));
            if (!clean.isEmpty() && !stepQueue.contains(clean)) stepQueue.add(clean);
        }
    }

    private void launchAsyncNarration(AiRecommender ai, AuraDb db, String profileId, String userQuery) {
        Thread worker = new Thread(() -> runNarrationLoop(ai, db, profileId, userQuery), "aura-orb-narrator");
        worker.setDaemon(true);
        worker.start();
    }

    private void runNarrationLoop(AiRecommender ai, AuraDb db, String profileId, String userQuery) {
        fetchLlmStatusSteps(ai, db, profileId, userQuery);
        int idx = 0;
        while (active.get() && idx < stepQueue.size()) {
            emitStepIfActive(stepQueue.get(idx++));
            sleepQuietly(1650L);
        }
    }

    private void fetchLlmStatusSteps(AiRecommender ai, AuraDb db, String profileId, String userQuery) {
        try {
            String prompt = buildNarrationPrompt(db, profileId, userQuery);
            String raw = ai.callGeminiJson(prompt);
            if (raw != null && !raw.isEmpty()) appendSteps(new JSONArray(raw));
        } catch (Exception ignored) {}
    }

    private String buildNarrationPrompt(AuraDb db, String profileId, String userQuery) {
        List<String> anchors = db.getDynamicTasteAnchors(profileId);
        List<String> recent = anchors.subList(0, Math.min(4, anchors.size()));
        String q = userQuery == null ? "" : userQuery.trim();
        String taskDesc = q.isEmpty()
            ? "refreshing personalized home recommendations based on recently watched titles " + recent
            : "finding movies or shows for request \"" + q + "\" (viewer also likes " + recent + ")";
        return "You are a warm, friendly living-room TV concierge speaking to a non-technical viewer.\n" +
            "Task right now: " + taskDesc + ".\n" +
            "Generate 4 short, natural progress updates (8 to 13 words each, ending with '\u2026') describing what you are doing step-by-step (e.g. looking at what they liked, exploring matching stories/actors/moods, checking Netflix/Prime/Hotstar, picking tonight's best options).\n" +
            "STRICT RULE: NEVER use technical words like AI, LLM, GraphQL, graph, JustWatch, vector, algorithm, database, metadata, or API.\n" +
            "Return ONLY a JSON array of 4 strings.";
    }

    private void emitStepIfActive(String msg) {
        if (active.get() && listener != null && msg != null && !msg.isEmpty()) {
            listener.onStep(msg);
        }
    }

    public static String sanitizeStep(String raw) {
        if (raw == null) return "";
        String s = raw.trim().replaceAll("(?i)\\b(JustWatch|GraphQL|graph|LLM|vector|Bayesian|metadata|algorithm)\\b", "").replaceAll("\\s{2,}", " ").trim();
        if (s.isEmpty()) return "";
        return s.endsWith("\u2026") || s.endsWith("...") ? s : (s + "\u2026");
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
