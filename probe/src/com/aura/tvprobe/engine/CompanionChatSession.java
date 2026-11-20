package com.aura.tvprobe;

import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Stateful Multi-Turn Companion Chat Session.
 * Tracks the active show being discussed, remembers prior Q&A turns for
 * natural follow-ups ("explain further", "who is that villain?"), and handles
 * interactive clarification when Aura needs to ask which show the user means.
 */
public final class CompanionChatSession {

    private static final long SESSION_TTL_MS = 180_000L;
    private static final int MAX_TURNS = 6;

    private static volatile JSONObject sActiveShow;
    private static volatile boolean sAwaitingClarification = false;
    private static volatile String sPendingQuestion = "";
    private static volatile String sLastUserQuery = "";
    private static volatile long sLastActivityMs = 0L;
    private static final List<String> TURNS = new ArrayList<>();

    private CompanionChatSession() {}

    public static synchronized void reset() {
        sActiveShow = null;
        sAwaitingClarification = false;
        sPendingQuestion = "";
        sLastUserQuery = "";
        sLastActivityMs = 0L;
        TURNS.clear();
        AuraConversationSession.clearStateOnly();
    }

    public static synchronized boolean isSessionAlive() {
        return sLastActivityMs > 0L && (System.currentTimeMillis() - sLastActivityMs) < SESSION_TTL_MS;
    }

    public static synchronized boolean isAwaitingClarification() {
        return sAwaitingClarification && isSessionAlive();
    }

    public static synchronized boolean hasActiveConversation() {
        return sActiveShow != null && isSessionAlive();
    }

    public static synchronized boolean hasPriorTurns() {
        return hasActiveConversation() && !TURNS.isEmpty();
    }

    public static synchronized boolean isFollowUpTurn(JSONObject item, String userQuery) {
        if (!hasPriorTurns()) return false;
        String newKey = extractShowKey(item);
        String oldKey = extractShowKey(sActiveShow);
        if (!newKey.isEmpty() && !newKey.equals(oldKey)) return false;
        String q = userQuery != null ? userQuery.trim() : "";
        return !q.isEmpty() && !q.equalsIgnoreCase(sLastUserQuery);
    }

    public static synchronized JSONObject getActiveShow() {
        return hasActiveConversation() ? cloneJson(sActiveShow) : null;
    }

    public static synchronized String consumePendingQuestion(String fallback) {
        String q = sPendingQuestion;
        sPendingQuestion = "";
        sAwaitingClarification = false;
        return (q != null && !q.isEmpty()) ? q : fallback;
    }

    public static synchronized void markAwaitingClarification(String originalQuestion) {
        sAwaitingClarification = true;
        sPendingQuestion = originalQuestion != null ? originalQuestion.trim() : "";
        sLastActivityMs = System.currentTimeMillis();
    }

    public static synchronized void setActiveShow(JSONObject item) {
        if (item == null) return;
        String newKey = extractShowKey(item);
        String oldKey = extractShowKey(sActiveShow);
        if (!newKey.isEmpty() && !newKey.equals(oldKey)) { TURNS.clear(); sLastUserQuery = ""; }
        sActiveShow = cloneJson(item);
        sAwaitingClarification = false;
        sLastActivityMs = System.currentTimeMillis();
    }

    public static synchronized void recordTurn(JSONObject item, String userQuery, String assistantReply) {
        setActiveShow(item);
        String q = userQuery != null ? userQuery.trim() : "";
        if (!q.isEmpty()) { sLastUserQuery = q; appendTurn("User: " + q); }
        if (assistantReply != null && !assistantReply.trim().isEmpty()) {
            String trimmed = assistantReply.trim();
            appendTurn("Aura: " + (trimmed.length() > 260 ? trimmed.substring(0, 260) + "..." : trimmed));
        }
        sLastActivityMs = System.currentTimeMillis();
    }

    private static void appendTurn(String entry) {
        TURNS.add(entry);
        while (TURNS.size() > MAX_TURNS) TURNS.remove(0);
    }

    public static synchronized String formatConversationForPrompt() {
        if (!hasPriorTurns()) return "";
        StringBuilder sb = new StringBuilder();
        for (String t : TURNS) sb.append(t).append('\n');
        return sb.toString().trim();
    }

    public static boolean isLikelyFollowUpPhrase(String spoken) {
        if (spoken == null || spoken.trim().isEmpty()) return false;
        String low = spoken.trim().toLowerCase(Locale.US);
        return isExpansionFollowUp(low) || isQuestionFollowUp(low);
    }

    private static boolean isExpansionFollowUp(String low) {
        return low.contains("explain further") || low.contains("explain more") || low.contains("tell me more")
            || low.contains("go deeper") || low.contains("more detail") || low.contains("what else")
            || low.contains("keep going") || low.contains("elaborate") || low.contains("continue explaining");
    }

    private static boolean isQuestionFollowUp(String low) {
        return low.startsWith("why ") || low.startsWith("who ") || low.startsWith("how ")
            || low.startsWith("what about ") || low.startsWith("and what ") || low.startsWith("what happened after")
            || low.startsWith("what happened before") || low.contains("in that scene") || low.contains("about him")
            || low.contains("about her") || low.contains("about them");
    }

    public static JSONObject buildClarificationPromptRecap(String originalQuestion) {
        JSONObject o = new JSONObject();
        try {
            o.put("headline", "Aura Companion");
            o.put("title", "");
            o.put("is_clarification", true);
            o.put("spoken_narration", "I couldn't spot which show you're looking at. Which show or movie would you like me to explain?");
        } catch (Exception ignored) {}
        return o;
    }

    private static String extractShowKey(JSONObject item) {
        if (item == null) return "";
        String t = item.optString("series_title").isEmpty() ? item.optString("title", "") : item.optString("series_title", "");
        return AuraDb.normalizeTitleKey(t);
    }

    private static JSONObject cloneJson(JSONObject src) {
        try { return src != null ? new JSONObject(src.toString()) : null; } catch (Exception e) { return null; }
    }
}
