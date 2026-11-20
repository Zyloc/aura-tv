package com.aura.tvprobe;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Unified Multi-Turn Conversation Session for Aura TV's Agentic System.
 * Remembers rolling user/assistant turns, the active show being discussed,
 * the last presented search/recommendation list (for "play the second one"),
 * and pending clarification prompts.
 */
public final class AuraConversationSession {

    private static final long SESSION_TTL_MS = 180_000L;
    private static final int MAX_HISTORY_ENTRIES = 12;

    private static final List<JSONObject> HISTORY_CONTENTS = new ArrayList<>();
    private static volatile JSONObject sActiveShow;
    private static volatile JSONArray sLastPresentedResults = new JSONArray();
    private static volatile boolean sAwaitingClarification = false;
    private static volatile String sPendingIntent = "";
    private static volatile long sLastActivityMs = 0L;

    private AuraConversationSession() {}

    public static synchronized void reset() {
        clearStateOnly();
        CompanionChatSession.reset();
    }

    static synchronized void clearStateOnly() {
        HISTORY_CONTENTS.clear();
        sActiveShow = null;
        sLastPresentedResults = new JSONArray();
        sAwaitingClarification = false;
        sPendingIntent = "";
        sLastActivityMs = 0L;
    }

    public static synchronized boolean isAlive() {
        return sLastActivityMs > 0L && (System.currentTimeMillis() - sLastActivityMs) < SESSION_TTL_MS;
    }

    public static synchronized boolean isAwaitingClarification() {
        return sAwaitingClarification && isAlive();
    }

    public static synchronized String getPendingIntent() {
        return isAlive() ? sPendingIntent : "";
    }

    public static synchronized void markAwaitingClarification(String pendingIntent) {
        sAwaitingClarification = true;
        sPendingIntent = pendingIntent != null ? pendingIntent.trim() : "";
        sLastActivityMs = System.currentTimeMillis();
        CompanionChatSession.markAwaitingClarification(sPendingIntent);
    }

    public static synchronized void clearClarification() {
        sAwaitingClarification = false;
        sPendingIntent = "";
    }

    public static synchronized void setActiveShow(JSONObject item) {
        if (item == null) return;
        sActiveShow = cloneJson(item);
        sLastActivityMs = System.currentTimeMillis();
        CompanionChatSession.setActiveShow(item);
    }

    public static synchronized JSONObject getActiveShow() {
        return isAlive() ? cloneJson(sActiveShow) : null;
    }

    public static synchronized void setLastPresentedResults(JSONArray results) {
        sLastPresentedResults = cloneArray(results);
        sLastActivityMs = System.currentTimeMillis();
    }

    public static synchronized JSONArray getLastPresentedResults() {
        return isAlive() ? cloneArray(sLastPresentedResults) : new JSONArray();
    }

    public static synchronized JSONObject resolveResultByTitleOrOrdinal(String query) {
        JSONArray list = getLastPresentedResults();
        if (query == null || list.length() == 0) return null;
        int idx = parseOrdinalIndex(query);
        if (idx >= 0 && idx < list.length()) return list.optJSONObject(idx);
        return findTitleInArray(list, query);
    }

    public static synchronized void recordCompletedTurn(String userText, String assistantReply) {
        if (!isAlive()) HISTORY_CONTENTS.clear();
        appendRoleText("user", userText);
        appendRoleText("model", assistantReply);
        sLastActivityMs = System.currentTimeMillis();
    }

    public static synchronized JSONArray copyHistoryContents() {
        JSONArray out = new JSONArray();
        if (!isAlive()) return out;
        for (JSONObject entry : HISTORY_CONTENTS) out.put(cloneJson(entry));
        return out;
    }

    private static void appendRoleText(String role, String text) {
        if (text == null || text.trim().isEmpty()) return;
        String clean = text.trim();
        if (clean.length() > 360) clean = clean.substring(0, 360) + "...";
        try {
            JSONArray parts = new JSONArray().put(new JSONObject().put("text", clean));
            HISTORY_CONTENTS.add(new JSONObject().put("role", role).put("parts", parts));
            while (HISTORY_CONTENTS.size() > MAX_HISTORY_ENTRIES) HISTORY_CONTENTS.remove(0);
        } catch (Exception ignored) {}
    }

    private static int parseOrdinalIndex(String q) {
        String low = q.trim().toLowerCase(Locale.US);
        if (low.equals("1") || low.equals("#1") || low.contains("first")) return 0;
        if (low.equals("2") || low.equals("#2") || low.contains("second")) return 1;
        if (low.equals("3") || low.equals("#3") || low.contains("third")) return 2;
        if (low.equals("4") || low.equals("#4") || low.contains("fourth")) return 3;
        return (low.equals("5") || low.equals("#5") || low.contains("fifth")) ? 4 : -1;
    }

    private static JSONObject findTitleInArray(JSONArray list, String query) {
        String norm = AuraDb.normalizeTitleKey(query);
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            String key = item != null ? AuraDb.normalizeTitleKey(item.optString("title", "")) : "";
            if (!key.isEmpty() && (key.equals(norm) || key.contains(norm) || norm.contains(key))) return item;
        }
        return null;
    }

    private static JSONObject cloneJson(JSONObject src) {
        try { return src != null ? new JSONObject(src.toString()) : null; } catch (Exception e) { return null; }
    }

    private static JSONArray cloneArray(JSONArray src) {
        try { return src != null ? new JSONArray(src.toString()) : new JSONArray(); } catch (Exception e) { return new JSONArray(); }
    }
}
