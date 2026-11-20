package com.aura.tvprobe;

import org.json.JSONObject;

/**
 * Tool: capture_screen_frame
 * Pillar 1 Live Co-Watcher: captures the current TV screen frame on demand
 * and runs Gemini Multimodal Vision to answer visual questions ("Who is that actor?",
 * "What's written on that sign?", "What is on that map?").
 */
public final class ScreenVisionTool implements AuraTool {

    @Override
    public String name() { return "capture_screen_frame"; }

    @Override
    public String statusLabel() { return "Looking at your TV screen\u2026"; }

    @Override
    public JSONObject declaration() {
        JSONObject props = new JSONObject();
        try {
            props.put("question", AuraToolSchemaBuilder.strProp("The visual question about what is currently on the TV screen."));
        } catch (Exception ignored) {}
        return AuraToolSchemaBuilder.declare(name(),
            "Capture the current TV screen frame and answer visual questions about actors, objects, text, or scenes on screen.",
            props, "question");
    }

    @Override
    public JSONObject execute(JSONObject args, AuraToolContext ctx) {
        String question = args.optString("question", "Describe what is on screen right now.").trim();
        String frameB64 = captureFrame(ctx);
        if (frameB64 == null || frameB64.isEmpty()) {
            return buildOutput(false, "I couldn't capture the TV screen right now (protected DRM video or unavailable frame).");
        }
        String answer = analyzeFrameWithGemini(ctx.ai, question, frameB64);
        return buildOutput(!answer.isEmpty(), !answer.isEmpty() ? answer : "I see the screen, but couldn't identify that detail clearly.");
    }

    private String captureFrame(AuraToolContext ctx) {
        if (ctx.visionFrameProvider != null) return ctx.visionFrameProvider.captureBase64Jpeg();
        return ShowCompanionAgent.captureScreenVisionFrameBase64();
    }

    private String analyzeFrameWithGemini(AiRecommender ai, String question, String frameB64) {
        if (ai == null) return "";
        String prompt = "You are Aura, an Android TV Live Co-Watcher. Answer the user's visual question about this TV frame "
            + "concisely in 1-2 natural spoken sentences (no spoilers, no markdown). Return JSON: {\"answer\":\"...\"}.\n"
            + "User question: " + question;
        String raw = ai.callGeminiMultimodalJson(prompt, frameB64);
        if (raw == null || raw.isEmpty()) return "";
        try { return new JSONObject(raw).optString("answer", raw).trim(); }
        catch (Exception e) { return raw.trim(); }
    }

    private JSONObject buildOutput(boolean captured, String answer) {
        JSONObject out = new JSONObject();
        try { out.put("frame_captured", captured).put("answer", answer); }
        catch (Exception ignored) {}
        return out;
    }
}
