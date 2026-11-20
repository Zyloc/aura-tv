package com.aura.tvprobe;

import org.json.JSONObject;
import java.util.Collections;
import java.util.List;

/**
 * Abstraction for a Gemini-compatible function-calling model turn.
 * Allows production GeminiToolClient and deterministic test fakes.
 */
public interface ToolModelClient {

    ModelTurnResult generateNextTurn(JSONObject requestPayload, int timeoutMs);

    final class ToolCall {
        public final String name;
        public final JSONObject args;

        public ToolCall(String name, JSONObject args) {
            this.name = name != null ? name : "";
            this.args = args != null ? args : new JSONObject();
        }
    }

    final class ModelTurnResult {
        public final List<ToolCall> toolCalls;
        public final String textReply;
        public final JSONObject rawModelContent;

        public ModelTurnResult(List<ToolCall> toolCalls, String textReply, JSONObject rawModelContent) {
            this.toolCalls = toolCalls != null ? toolCalls : Collections.emptyList();
            this.textReply = textReply != null ? textReply : "";
            this.rawModelContent = rawModelContent;
        }

        public boolean hasToolCalls() {
            return !toolCalls.isEmpty();
        }
    }
}
