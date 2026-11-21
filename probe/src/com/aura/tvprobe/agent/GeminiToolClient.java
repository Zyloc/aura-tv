package com.aura.tvprobe;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Gemini v1beta native function-calling client.
 * Uses gemini-flash-lite-latest first and falls back to gemini-flash-latest on failure/quota.
 */
public final class GeminiToolClient implements ToolModelClient {

    private final AiRecommender ai;

    public GeminiToolClient(AiRecommender ai) {
        this.ai = ai;
    }

    @Override
    public ModelTurnResult generateNextTurn(JSONObject requestPayload, int timeoutMs) {
        if (ai == null || requestPayload == null) return null;
        ModelTurnResult local = ai.callLocalLlmToolTurn(requestPayload, timeoutMs);
        if (local != null) return local;
        if (!ai.shouldAllowGemini() || !ai.hasGeminiApiKey()) return null;
        String body = requestPayload.toString();
        for (String model : ai.getGeminiModels()) {
            if (Thread.currentThread().isInterrupted()) return null;
            ModelTurnResult parsed = parseGeminiResponse(ai.postGeminiRaw(model, body, timeoutMs));
            if (parsed != null) return parsed;
        }
        return null;
    }

    static ModelTurnResult parseGeminiResponse(String rawJson) {
        if (rawJson == null || rawJson.trim().isEmpty()) return null;
        try {
            JSONObject content = extractFirstCandidateContent(new JSONObject(rawJson));
            if (content == null) return null;
            return buildTurnResultFromContent(content);
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONObject extractFirstCandidateContent(JSONObject root) {
        JSONArray cands = root.optJSONArray("candidates");
        if (cands == null || cands.length() == 0) return null;
        JSONObject first = cands.optJSONObject(0);
        return first != null ? first.optJSONObject("content") : null;
    }

    private static ModelTurnResult buildTurnResultFromContent(JSONObject content) {
        JSONArray parts = content.optJSONArray("parts");
        if (parts == null || parts.length() == 0) return null;
        List<ToolCall> calls = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        collectParts(parts, calls, text);
        if (calls.isEmpty() && text.length() == 0) return null;
        return new ModelTurnResult(calls, text.toString().trim(), content);
    }

    private static void collectParts(JSONArray parts, List<ToolCall> calls, StringBuilder text) {
        for (int i = 0; i < parts.length(); i++) {
            JSONObject p = parts.optJSONObject(i);
            if (p == null) continue;
            JSONObject fc = p.optJSONObject("functionCall");
            if (fc != null && !fc.optString("name", "").isEmpty()) {
                calls.add(new ToolCall(fc.optString("name"), fc.optJSONObject("args")));
            } else if (p.has("text")) {
                appendNonEmpty(text, p.optString("text", ""));
            }
        }
    }

    private static void appendNonEmpty(StringBuilder sb, String piece) {
        if (piece == null || piece.trim().isEmpty()) return;
        if (sb.length() > 0) sb.append(' ');
        sb.append(piece.trim());
    }
}
