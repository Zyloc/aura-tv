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

    private static final long STEP_INTERVAL_MS = 2200L;
    private static final String[] CONTINUATION_STEPS = {
        "Checking streaming availability\u2026",
        "Selecting top matches for you\u2026",
        "Preparing artwork & deep links\u2026",
        "Publishing recommendations\u2026"
    };

    private static volatile AgentStatusNarrator currentActive;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final List<String> stepQueue = Collections.synchronizedList(new ArrayList<>());
    private final AiRecommender.AgentProgressListener listener;
    private volatile long lastEmitMs = 0L;
    private volatile String lastEmitted = "";

    private AgentStatusNarrator(AiRecommender.AgentProgressListener listener) {
        this.listener = listener;
    }

    public static AgentStatusNarrator start(
        AiRecommender ai, AuraDb db, String profileId, String userQuery, AiRecommender.AgentProgressListener progress
    ) {
        AgentStatusNarrator narrator = new AgentStatusNarrator(progress);
        currentActive = narrator;
        if (progress != null) narrator.launchAsyncNarration(db, profileId, userQuery);
        return narrator;
    }

    public static void publishPlanSteps(JSONObject plan) {
        AgentStatusNarrator cur = currentActive;
        if (cur == null || plan == null || !cur.active.get()) return;
        JSONArray arr = plan.optJSONArray("narrator_steps");
        if (arr != null && arr.length() > 0) cur.addSingleStep(arr.optString(0, ""));
        JSONArray pq = plan.optJSONArray("primary_queries");
        if (pq != null && pq.length() > 0) cur.addSingleStep("Matched " + pq.optString(0, ""));
    }

    public static void publishLiveStep(String rawStep) {
        AgentStatusNarrator cur = currentActive;
        if (cur != null && cur.active.get()) cur.prioritizeLiveStep(rawStep);
    }

    public void stop() {
        active.set(false);
    }

    public void appendSteps(JSONArray arr) {
        if (arr == null || !active.get()) return;
        for (int i = 0; i < arr.length() && i < 2; i++) {
            addSingleStep(arr.optString(i, ""));
        }
    }

    public void addSingleStep(String raw) {
        String clean = sanitizeStep(raw);
        if (!clean.isEmpty() && !stepQueue.contains(clean) && !clean.equals(lastEmitted)) {
            stepQueue.add(clean);
        }
    }

    private void prioritizeLiveStep(String raw) {
        String clean = sanitizeStep(raw);
        if (clean.isEmpty() || clean.equals(lastEmitted)) return;
        synchronized (stepQueue) {
            stepQueue.remove(clean);
            stepQueue.add(0, clean);
        }
    }

    private void launchAsyncNarration(AuraDb db, String profileId, String userQuery) {
        seedInitialContextSteps(db, profileId, userQuery);
        Thread worker = new Thread(this::runNarrationLoop, "aura-orb-narrator");
        worker.setDaemon(true);
        worker.start();
    }

    private void seedInitialContextSteps(AuraDb db, String profileId, String userQuery) {
        String q = userQuery == null ? "" : userQuery.trim();
        if (!q.isEmpty()) {
            addSingleStep("Understanding your search");
            addSingleStep("Searching streaming catalogs");
        } else {
            List<String> anchors = db != null ? db.getDynamicTasteAnchors(profileId) : Collections.emptyList();
            if (!anchors.isEmpty()) addSingleStep("Matching " + anchors.get(0));
            addSingleStep("Scanning your streaming apps");
        }
    }

    private void runNarrationLoop() {
        int contIdx = 0;
        while (active.get()) {
            String next = pollNextOrContinuation(contIdx);
            if (next != null) {
                if ( isContinuationStep(next)) contIdx++;
                emitStepIfActive(next);
            }
            sleepQuietly(STEP_INTERVAL_MS);
        }
    }

    private String pollNextOrContinuation(int contIdx) {
        synchronized (stepQueue) {
            if (!stepQueue.isEmpty()) return stepQueue.remove(0);
        }
        return contIdx < CONTINUATION_STEPS.length ? CONTINUATION_STEPS[contIdx] : null;
    }

    private boolean isContinuationStep(String step) {
        for (String c : CONTINUATION_STEPS) {
            if (c.equals(step)) return true;
        }
        return false;
    }

    private void emitStepIfActive(String msg) {
        if (active.get() && listener != null && msg != null && !msg.isEmpty()) {
            lastEmitted = msg;
            lastEmitMs = System.currentTimeMillis();
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
