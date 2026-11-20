package com.aura.tvprobe;

import org.json.JSONObject;

/**
 * Tool: ask_user_clarification
 * Asks a concise clarifying question on the translucent lower-third overlay
 * when the show or user intent is ambiguous, preserving pending intent for the next turn.
 */
public final class AskClarificationTool implements AuraTool {

    @Override
    public String name() { return "ask_user_clarification"; }

    @Override
    public String statusLabel() { return "Asking for clarification\u2026"; }

    @Override
    public JSONObject declaration() {
        JSONObject props = new JSONObject();
        try {
            props.put("question", AuraToolSchemaBuilder.strProp("Short, polite question to speak and show on screen."));
            props.put("pending_intent", AuraToolSchemaBuilder.strProp("The user's original intent to resume once they answer."));
        } catch (Exception ignored) {}
        return AuraToolSchemaBuilder.declare(name(),
            "Ask the user a clarifying question when no show is on screen or intent is ambiguous.",
            props, "question");
    }

    @Override
    public JSONObject execute(JSONObject args, AuraToolContext ctx) {
        String question = args.optString("question", "Which show or movie would you like me to explain?").trim();
        String pending = args.optString("pending_intent", "").trim();
        ctx.isClarification = true;
        ctx.clarificationQuestion = question;
        AuraConversationSession.markAwaitingClarification(pending);
        ctx.companionRecap = buildClarificationRecap(question);
        JSONObject out = new JSONObject();
        try { out.put("awaiting_reply", true).put("question", question); }
        catch (Exception ignored) {}
        return out;
    }

    private JSONObject buildClarificationRecap(String question) {
        JSONObject o = new JSONObject();
        try {
            o.put("headline", "Aura Companion").put("title", "")
                .put("is_clarification", true).put("spoken_narration", question);
        } catch (Exception ignored) {}
        return o;
    }
}
