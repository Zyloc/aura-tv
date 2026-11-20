package com.aura.tvprobe;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;

/**
 * Unified Tool-Calling Agent Orchestrator for Aura TV.
 * Drives search, personalized recommendations, show companion Q&A, multi-turn
 * conversations, clarification prompts, taste updates, and direct playback
 * through one bounded Gemini Function-Calling loop.
 */
public final class AuraAgentOrchestrator {

    public static final int MAX_TOOL_STEPS = 4;
    private static final int STEP_TIMEOUT_MS = 10_000;

    private final ToolModelClient modelClient;
    private final AuraToolRegistry registry;

    public AuraAgentOrchestrator(ToolModelClient modelClient, AuraToolRegistry registry) {
        this.modelClient = modelClient;
        this.registry = registry != null ? registry : AuraToolRegistry.createDefaultRegistry();
    }

    public static AuraAgentOrchestrator createDefault(AiRecommender ai) {
        return new AuraAgentOrchestrator(new GeminiToolClient(ai), AuraToolRegistry.createDefaultRegistry());
    }

    public static final class AgentTurnOutcome {
        public final String spokenText;
        public final String headline;
        public final JSONArray visualResults;
        public final JSONArray followUpChips;
        public final JSONObject companionRecap;
        public final JSONObject openedItem;
        public final boolean isClarification;
        public final boolean actionExecutedDirectly;
        public final boolean usedOfflineFallback;
        public final int toolCallsExecuted;

        public AgentTurnOutcome(
                String spokenText, String headline, JSONArray visualResults, JSONArray followUpChips,
                JSONObject companionRecap, JSONObject openedItem, boolean isClarification,
                boolean actionExecutedDirectly, boolean usedOfflineFallback, int toolCallsExecuted) {
            this.spokenText = spokenText != null ? spokenText : "";
            this.headline = headline != null ? headline : "Aura AI";
            this.visualResults = visualResults;
            this.followUpChips = followUpChips;
            this.companionRecap = companionRecap;
            this.openedItem = openedItem;
            this.isClarification = isClarification;
            this.actionExecutedDirectly = actionExecutedDirectly;
            this.usedOfflineFallback = usedOfflineFallback;
            this.toolCallsExecuted = toolCallsExecuted;
        }

        public boolean hasVisualResults() {
            return visualResults != null && visualResults.length() > 0;
        }
    }

    public AgentTurnOutcome runTurn(
            String userUtterance, AuraToolContext ctx, AiRecommender.AgentProgressListener progress) {
        String cleanInput = userUtterance != null ? userUtterance.trim() : "";
        if (cleanInput.isEmpty()) return buildOutcome("", ctx, false, 0);
        ctx.progressListener = progress;
        JSONArray contents = buildInitialContents(cleanInput, ctx);
        int[] callsCounter = new int[]{0};
        String finalText = executeToolLoop(contents, ctx, progress, callsCounter);
        if (finalText == null) return runOfflineDeterministicFallback(cleanInput, ctx);
        return finalizeTurnOutcome(cleanInput, finalText, ctx, false, callsCounter[0]);
    }

    private String executeToolLoop(
            JSONArray contents, AuraToolContext ctx, AiRecommender.AgentProgressListener progress, int[] callsCounter) {
        JSONArray declarations = registry.buildFunctionDeclarations();
        for (int step = 0; step < MAX_TOOL_STEPS; step++) {
            if (Thread.currentThread().isInterrupted()) return "";
            ToolModelClient.ModelTurnResult turn = modelClient.generateNextTurn(buildPayload(contents, declarations), STEP_TIMEOUT_MS);
            if (turn == null) return null;
            if (!turn.hasToolCalls()) return turn.textReply;
            callsCounter[0] += turn.toolCalls.size();
            publishToolProgress(turn.toolCalls, progress);
            appendModelAndToolResponses(contents, turn, registry.executeBatch(turn.toolCalls, ctx));
            if (shouldStopLoopEarly(ctx)) return selectEarlyReply(ctx, turn.textReply);
        }
        return selectEarlyReply(ctx, "");
    }

    private boolean shouldStopLoopEarly(AuraToolContext ctx) {
        return ctx.isClarification || ctx.actionExecutedDirectly;
    }

    private String selectEarlyReply(AuraToolContext ctx, String modelText) {
        if (ctx.isClarification && !ctx.clarificationQuestion.isEmpty()) return ctx.clarificationQuestion;
        if (!ctx.visualSpokenSummary.isEmpty()) return ctx.visualSpokenSummary;
        if (ctx.companionRecap != null && !ctx.companionRecap.optString("spoken_narration").isEmpty()) {
            return ctx.companionRecap.optString("spoken_narration");
        }
        return modelText != null ? modelText : "";
    }

    private void publishToolProgress(List<ToolModelClient.ToolCall> calls, AiRecommender.AgentProgressListener progress) {
        if (progress == null || calls == null || calls.isEmpty()) return;
        AuraTool first = registry.get(calls.get(0).name);
        if (first != null && !first.statusLabel().isEmpty()) progress.onStep(first.statusLabel());
    }

    private void appendModelAndToolResponses(
            JSONArray contents, ToolModelClient.ModelTurnResult turn, List<JSONObject> responseParts) {
        try {
            if (turn.rawModelContent != null) contents.put(turn.rawModelContent);
            JSONArray parts = new JSONArray();
            for (JSONObject rp : responseParts) parts.put(rp);
            contents.put(new JSONObject().put("role", "user").put("parts", parts));
        } catch (Exception ignored) {}
    }

    private AgentTurnOutcome finalizeTurnOutcome(
            String userText, String finalText, AuraToolContext ctx, boolean offline, int toolCount) {
        String spoken = resolveSpokenReply(finalText, ctx);
        if (!ctx.isClarification) AuraConversationSession.clearClarification();
        AuraConversationSession.recordCompletedTurn(userText, spoken);
        return buildOutcome(spoken, ctx, offline, toolCount);
    }

    private String resolveSpokenReply(String finalText, AuraToolContext ctx) {
        if (finalText != null && !finalText.trim().isEmpty()) return finalText.trim();
        return selectEarlyReply(ctx, "");
    }

    private AgentTurnOutcome buildOutcome(String spoken, AuraToolContext ctx, boolean offline, int toolCount) {
        String headline = resolveHeadline(ctx);
        return new AgentTurnOutcome(spoken, headline, ctx.visualResults, ctx.followUpChips,
            ctx.companionRecap, ctx.openedItem, ctx.isClarification, ctx.actionExecutedDirectly, offline, toolCount);
    }

    private String resolveHeadline(AuraToolContext ctx) {
        if (!ctx.visualHeadline.isEmpty()) return ctx.visualHeadline;
        if (ctx.companionRecap != null && !ctx.companionRecap.optString("headline").isEmpty()) {
            return ctx.companionRecap.optString("headline");
        }
        return ctx.isClarification ? "Aura Companion" : "Aura AI";
    }

    private JSONArray buildInitialContents(String userUtterance, AuraToolContext ctx) {
        JSONArray contents = AuraConversationSession.copyHistoryContents();
        String envelope = buildUserTurnEnvelope(userUtterance, ctx);
        try {
            JSONArray parts = new JSONArray().put(new JSONObject().put("text", envelope));
            contents.put(new JSONObject().put("role", "user").put("parts", parts));
        } catch (Exception ignored) {}
        return contents;
    }

    private String buildUserTurnEnvelope(String userUtterance, AuraToolContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("[TV Context: app=").append(ctx.getForegroundAppLabel());
        appendSessionContextHints(sb, ctx);
        return sb.append("]\nUser: ").append(userUtterance).toString();
    }

    private void appendSessionContextHints(StringBuilder sb, AuraToolContext ctx) {
        JSONObject active = AuraConversationSession.getActiveShow();
        if (active != null && !active.optString("title").isEmpty()) {
            sb.append(", activeShow=").append(active.optString("title"));
        }
        if (AuraConversationSession.isAwaitingClarification()) {
            sb.append(", awaitingClarificationFor=").append(AuraConversationSession.getPendingIntent());
        }
        String mem = UserMemoryStore.formatMemoriesForPrompt(ctx.db, ctx.profileId);
        if (!mem.isEmpty()) sb.append(", userMemories=").append(mem);
    }

    private JSONObject buildPayload(JSONArray contents, JSONArray declarations) {
        try {
            JSONObject sys = new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", buildSystemPrompt())));
            JSONObject toolObj = new JSONObject().put("functionDeclarations", declarations);
            JSONObject cfg = new JSONObject().put("temperature", 0.35);
            return new JSONObject().put("systemInstruction", sys).put("contents", contents)
                .put("tools", new JSONArray().put(toolObj)).put("generationConfig", cfg);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static String buildSystemPrompt() {
        return "You are Aura, an intelligent Android TV AI agent. You have tools for screen awareness, "
            + "on-demand screen vision (capture_screen_frame), watch history & Taste DNA, long-term memory "
            + "(remember_user_preference), catalog search, personalized recommendations, spoiler-safe show/scene/cast "
            + "explanations, in-app OTT search, direct playback, surprise play (play_surprise_pick), taste updates, "
            + "TV controls, visual results, and clarification. "
            + "RULES: (1) Always call tools rather than guessing titles, progress, or what is on screen. "
            + "(2) For visual questions about what's on the paused TV screen ('what is that?', 'translate that sign', 'who is on screen'), call capture_screen_frame. "
            + "(3) If user asks about 'this show'/'catch me up' without naming a show, call get_screen_context first; if none found, call ask_user_clarification. "
            + "(4) When searching or recommending, call search_catalog or recommend_for_user, then present_visual_results. "
            + "(5) For 'play it' or 'play the second one', call open_title_or_play(auto_play=true). For 'just play something good'/'surprise me', call play_surprise_pick. "
            + "(6) For mood/dislike ('hide horror', 'light smart'), call update_taste_preference. For permanent rules ('remember I hate jump scares'), call remember_user_preference. "
            + "(7) Keep final spoken replies warm, concise, and natural for TV TTS (2-4 short sentences, no markdown).";
    }

    private AgentTurnOutcome runOfflineDeterministicFallback(String userText, AuraToolContext ctx) {
        if (AuraConversationSession.isAwaitingClarification() || CompanionChatSession.isAwaitingClarification()) {
            return runOfflineClarificationReply(userText, ctx);
        }
        InAppVoiceRouter.VoiceDecision dec = InAppVoiceRouter.classifyIntent(ctx.ai, userText, ctx.getForegroundAppLabel());
        if (dec.action == InAppVoiceRouter.VoiceAction.COMPANION) {
            return runOfflineCompanionTools(userText, dec, ctx);
        }
        return runOfflineSearchTool(userText, dec, ctx);
    }

    private AgentTurnOutcome runOfflineClarificationReply(String replyTitle, AuraToolContext ctx) {
        String cleanTitle = InAppVoiceRouter.extractCleanSearchQuery(replyTitle, "");
        String pendingQ = CompanionChatSession.consumePendingQuestion("Catch me up on " + cleanTitle);
        AuraConversationSession.clearClarification();
        invokeSingleTool("explain_show_or_scene", jsonArgs("title", cleanTitle, "question", pendingQ), ctx);
        return finalizeTurnOutcome(replyTitle, selectEarlyReply(ctx, ""), ctx, true, 1);
    }

    private AgentTurnOutcome runOfflineCompanionTools(
            String userText, InAppVoiceRouter.VoiceDecision dec, AuraToolContext ctx) {
        String target = resolveOfflineCompanionTitle(userText, dec, ctx);
        if (target.isEmpty()) {
            invokeSingleTool("ask_user_clarification", jsonArgs("question",
                "I couldn't spot which show you're looking at. Which show or movie would you like me to explain?",
                "pending_intent", userText), ctx);
            return finalizeTurnOutcome(userText, selectEarlyReply(ctx, ""), ctx, true, 2);
        }
        JSONObject args = jsonArgs("title", target, "question", userText);
        if (dec != null && !dec.lens.isEmpty()) try { args.put("lens", dec.lens); } catch (Exception ignored) {}
        invokeSingleTool("explain_show_or_scene", args, ctx);
        return finalizeTurnOutcome(userText, selectEarlyReply(ctx, ""), ctx, true, 2);
    }

    private String resolveOfflineCompanionTitle(String userText, InAppVoiceRouter.VoiceDecision dec, AuraToolContext ctx) {
        if (dec != null && !dec.targetTitle.isEmpty()) return dec.targetTitle;
        String named = ShowCompanionAgent.extractTitleFromCompanionQuery(userText);
        if (!named.isEmpty()) return named;
        JSONObject screen = invokeSingleTool("get_screen_context", new JSONObject(), ctx);
        if (screen != null && screen.optBoolean("found_show", false)) return screen.optString("title", "");
        JSONObject active = AuraConversationSession.getActiveShow();
        return active != null ? active.optString("title", "") : "";
    }

    private AgentTurnOutcome runOfflineSearchTool(
            String userText, InAppVoiceRouter.VoiceDecision dec, AuraToolContext ctx) {
        String q = (dec != null && !dec.cleanQuery.isEmpty()) ? dec.cleanQuery : userText;
        String tool = ctx.isInsideExternalApp() ? "in_app_ott_search" : "search_catalog";
        invokeSingleTool(tool, jsonArgs("query", q), ctx);
        return finalizeTurnOutcome(userText, "Here is what I found for " + q + ".", ctx, true, 1);
    }

    private JSONObject invokeSingleTool(String name, JSONObject args, AuraToolContext ctx) {
        List<JSONObject> res = registry.executeBatch(
            java.util.Collections.singletonList(new ToolModelClient.ToolCall(name, args)), ctx);
        if (res.isEmpty()) return null;
        JSONObject fr = res.get(0).optJSONObject("functionResponse");
        return fr != null ? fr.optJSONObject("response") : null;
    }

    private static JSONObject jsonArgs(String... kv) {
        JSONObject o = new JSONObject();
        try { for (int i = 0; i + 1 < kv.length; i += 2) o.put(kv[i], kv[i + 1]); }
        catch (Exception ignored) {}
        return o;
    }
}
