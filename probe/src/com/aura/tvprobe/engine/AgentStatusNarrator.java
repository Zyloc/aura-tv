package com.aura.tvprobe;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Streams real-time dynamic LLM reasoning and retrieval status messages
 * to the AgenticOrbView during Voice Search, Similar Exploration, and Home Refresh.
 */
public final class AgentStatusNarrator {

    private static volatile AgentStatusNarrator currentActive;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final List<String> stepQueue = Collections.synchronizedList(new ArrayList<>());
    private final AiRecommender.AgentProgressListener listener;
    private final long startMs = System.currentTimeMillis();

    private AgentStatusNarrator(AiRecommender.AgentProgressListener listener) {
        this.listener = listener;
    }

    public static AgentStatusNarrator start(
        AiRecommender ai, AuraDb db, String profileId, String userQuery, AiRecommender.AgentProgressListener progress
    ) {
        AgentStatusNarrator narrator = new AgentStatusNarrator(progress);
        currentActive = narrator;
        if (progress != null) narrator.launchAsyncNarration(ai, db, profileId, userQuery);
        return narrator;
    }

    public static void publishPlanSteps(JSONObject plan) {
        AgentStatusNarrator cur = currentActive;
        if (cur == null || plan == null || !cur.active.get()) return;
        cur.appendSteps(plan.optJSONArray("narrator_steps"));
        JSONArray pq = plan.optJSONArray("primary_queries");
        if (pq != null && pq.length() > 0) cur.addSingleStep("Matched " + pq.optString(0, ""));
    }

    public static void publishLiveStep(String rawStep) {
        AgentStatusNarrator cur = currentActive;
        if (cur != null && cur.active.get()) cur.addSingleStep(rawStep);
    }

    public void stop() {
        if (listener != null) {
            long rem = 2800L - (System.currentTimeMillis() - startMs);
            if (rem > 0) sleepQuietly(rem);
        }
        active.set(false);
    }

    public void appendSteps(JSONArray arr) {
        if (arr == null || !active.get()) return;
        for (int i = 0; i < arr.length(); i++) {
            addSingleStep(arr.optString(i, ""));
        }
    }

    public void addSingleStep(String raw) {
        String clean = sanitizeStep(raw);
        if (!clean.isEmpty() && !stepQueue.contains(clean)) {
            stepQueue.add(clean);
        }
    }

    private void launchAsyncNarration(AiRecommender ai, AuraDb db, String profileId, String userQuery) {
        Thread fetcher = new Thread(() -> fetchLlmStatusSteps(ai, db, profileId, userQuery), "aura-orb-llm");
        fetcher.setDaemon(true);
        fetcher.start();
        Thread worker = new Thread(this::runNarrationLoop, "aura-orb-narrator");
        worker.setDaemon(true);
        worker.start();
    }

    private void runNarrationLoop() {
        int idx = 0;
        while (active.get()) {
            if (idx < stepQueue.size()) {
                emitStepIfActive(stepQueue.get(idx++));
                sleepQuietly(950L);
            } else {
                sleepQuietly(200L);
            }
        }
    }

    private void fetchLlmStatusSteps(AiRecommender ai, AuraDb db, String profileId, String userQuery) {
        try {
            if (ai == null || db == null) return;
            if (userQuery == null || !userQuery.startsWith("Similar to ")) sleepQuietly(1200L);
            if (!stepQueue.isEmpty() || !active.get()) return;
            String raw = ai.callGeminiJson(buildNarrationPrompt(db, profileId, userQuery));
            if (raw != null && !raw.isEmpty()) appendSteps(parseStepsJson(raw.trim()));
        } catch (Exception ignored) {}
    }

    private JSONArray parseStepsJson(String trimmed) throws Exception {
        if (trimmed.startsWith("[")) return new JSONArray(trimmed);
        JSONObject obj = new JSONObject(trimmed);
        JSONArray names = obj.names();
        if (names == null) return null;
        for (int i = 0; i < names.length(); i++) {
            JSONArray arr = obj.optJSONArray(names.getString(i));
            if (arr != null && arr.length() > 0) return arr;
        }
        return null;
    }

    private String buildNarrationPrompt(AuraDb db, String profileId, String userQuery) {
        List<String> anchors = db.getDynamicTasteAnchors(profileId);
        List<String> recent = anchors.subList(0, Math.min(3, anchors.size()));
        String q = userQuery == null ? "" : userQuery.trim();
        String taskDesc = q.isEmpty() ? "refreshing recommendations from " + recent : "understanding intent for \"" + q + "\"";
        return "You are narrating what the search engine is doing right now.\nTask: " + taskDesc + ".\n" +
            "Decode any actor, show, or plot clue in the query and generate 4 specific status phrases (3 to 5 words MAX each, e.g. 'Matching Brooklyn cast clue', 'Found About Time movie', 'Checking streaming apps', 'Ranking top time-travel films').\n" +
            "Return ONLY a JSON object: {\"steps\": [\"...\", \"...\", \"...\", \"...\"]}.";
    }

    private void emitStepIfActive(String msg) {
        if (active.get() && listener != null && msg != null && !msg.isEmpty()) {
            listener.onStep(msg);
        }
    }

    public static String sanitizeStep(String raw) {
        if (raw == null) return "";
        String s = raw.trim().replaceAll("(?i)\\b(JustWatch|GraphQL|graph|LLM|vector|Bayesian|metadata|algorithm|AI)\\b", "")
            .replaceAll("[.\u2026]+$", "").replaceAll("\\s{2,}", " ").trim();
        if (s.isEmpty()) return "";
        return capWordCount(s, 5) + "\u2026";
    }

    private static String capWordCount(String text, int maxWords) {
        String[] words = text.split("\\s+");
        if (words.length <= maxWords) return text;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxWords; i++) {
            if (i > 0) sb.append(' ');
            sb.append(words[i]);
        }
        return sb.toString();
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
