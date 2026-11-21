package com.aura.tvprobe;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Native OpenAI-compatible Local LLM client (Ollama, LM Studio, llama.cpp, vLLM).
 * Translates Aura's ToolModelClient payloads to standard /chat/completions JSON
 * with zero external dependencies so Aura TV runs 100% local-first when configured.
 */
public final class LocalLlmClient implements ToolModelClient {

    private final AuraDb db;

    public LocalLlmClient(AuraDb db) {
        this.db = db;
    }

    public boolean isConfigured() {
        return !getBaseUrl().isEmpty();
    }

    public String getProviderMode() {
        String mode = db != null ? db.getSetting("llm_provider", "auto").trim().toLowerCase(java.util.Locale.US) : "auto";
        return ("gemini".equals(mode) || "local".equals(mode)) ? mode : "auto";
    }

    public boolean shouldTryLocal() {
        return isConfigured() && !"gemini".equals(getProviderMode());
    }

    public boolean shouldAllowGemini() {
        return !"local".equals(getProviderMode());
    }

    public String getBaseUrl() {
        return db != null ? db.getSetting("local_llm_url", "").trim() : "";
    }

    public String getModelName() {
        String m = db != null ? db.getSetting("local_llm_model", "").trim() : "";
        return m.isEmpty() ? "llama3.2" : m;
    }

    public String getApiKey() {
        return db != null ? db.getSetting("local_llm_api_key", "").trim() : "";
    }

    @Override
    public ModelTurnResult generateNextTurn(JSONObject geminiPayload, int timeoutMs) {
        if (!isConfigured() || geminiPayload == null) return null;
        JSONObject openAiBody = convertGeminiPayloadToOpenAi(geminiPayload, getModelName());
        String raw = postChatCompletions(getBaseUrl(), getApiKey(), openAiBody.toString(), timeoutMs);
        return parseOpenAiToolResponse(raw);
    }

    public String generateSimpleText(String prompt, boolean jsonMode, int timeoutMs) {
        if (!isConfigured() || prompt == null || prompt.isEmpty()) return null;
        JSONObject body = buildSimpleChatBody(getModelName(), prompt, jsonMode);
        String raw = postChatCompletions(getBaseUrl(), getApiKey(), body.toString(), timeoutMs);
        ModelTurnResult res = parseOpenAiToolResponse(raw);
        return res != null && !res.textReply.isEmpty() ? res.textReply : null;
    }

    static JSONObject buildSimpleChatBody(String model, String prompt, boolean jsonMode) {
        JSONObject body = new JSONObject();
        try {
            JSONArray msgs = new JSONArray().put(new JSONObject().put("role", "user").put("content", prompt));
            body.put("model", model).put("messages", msgs).put("temperature", 0.3);
            if (jsonMode) body.put("response_format", new JSONObject().put("type", "json_object"));
        } catch (Exception ignored) {}
        return body;
    }

    static JSONObject convertGeminiPayloadToOpenAi(JSONObject geminiPayload, String model) {
        JSONObject body = new JSONObject();
        try {
            JSONArray messages = new JSONArray();
            appendSystemInstruction(geminiPayload, messages);
            appendContentsAsMessages(geminiPayload.optJSONArray("contents"), messages);
            body.put("model", model).put("messages", messages).put("temperature", 0.25);
            JSONArray tools = convertToolsArray(geminiPayload.optJSONArray("tools"));
            if (tools.length() > 0) body.put("tools", tools).put("tool_choice", "auto");
        } catch (Exception ignored) {}
        return body;
    }

    private static void appendSystemInstruction(JSONObject payload, JSONArray messages) throws Exception {
        JSONObject sys = payload.optJSONObject("systemInstruction");
        JSONArray parts = sys != null ? sys.optJSONArray("parts") : null;
        String text = parts != null && parts.length() > 0 ? parts.optJSONObject(0).optString("text", "") : "";
        if (!text.isEmpty()) messages.put(new JSONObject().put("role", "system").put("content", text));
    }

    private static void appendContentsAsMessages(JSONArray contents, JSONArray messages) throws Exception {
        if (contents == null) return;
        for (int i = 0; i < contents.length(); i++) {
            JSONObject c = contents.optJSONObject(i);
            if (c != null) convertContentEntry(c, messages);
        }
    }

    private static void convertContentEntry(JSONObject c, JSONArray messages) throws Exception {
        String role = "model".equals(c.optString("role")) ? "assistant" : "user";
        JSONArray parts = c.optJSONArray("parts");
        if (parts == null || parts.length() == 0) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) appendPartText(parts.optJSONObject(i), sb);
        if (sb.length() > 0) messages.put(new JSONObject().put("role", role).put("content", sb.toString().trim()));
    }

    private static void appendPartText(JSONObject part, StringBuilder sb) {
        if (part == null) return;
        String txt = part.optString("text", "");
        if (!txt.isEmpty()) { sb.append(txt).append("\n"); return; }
        JSONObject fr = part.optJSONObject("functionResponse");
        if (fr != null) sb.append("Tool ").append(fr.optString("name")).append(" result: ").append(fr.opt("response")).append("\n");
        JSONObject fc = part.optJSONObject("functionCall");
        if (fc != null) sb.append("Called ").append(fc.optString("name")).append("(").append(fc.opt("args")).append(")\n");
    }

    private static JSONArray convertToolsArray(JSONArray geminiTools) throws Exception {
        JSONArray out = new JSONArray();
        if (geminiTools == null || geminiTools.length() == 0) return out;
        JSONArray decls = geminiTools.optJSONObject(0).optJSONArray("functionDeclarations");
        if (decls == null) return out;
        for (int i = 0; i < decls.length(); i++) {
            JSONObject d = decls.optJSONObject(i);
            if (d != null) out.put(wrapOpenAiFunction(d));
        }
        return out;
    }

    private static JSONObject wrapOpenAiFunction(JSONObject decl) throws Exception {
        JSONObject rawParams = decl.optJSONObject("parameters");
        JSONObject normParams = rawParams != null ? normalizeSchemaTypes(rawParams) : new JSONObject().put("type", "object");
        JSONObject fn = new JSONObject()
            .put("name", decl.optString("name", ""))
            .put("description", decl.optString("description", ""))
            .put("parameters", normParams);
        return new JSONObject().put("type", "function").put("function", fn);
    }

    static JSONObject normalizeSchemaTypes(JSONObject src) throws Exception {
        JSONObject out = new JSONObject();
        java.util.Iterator<String> it = src.keys();
        while (it.hasNext()) {
            String k = it.next();
            Object v = src.opt(k);
            if ("type".equals(k) && v instanceof String) out.put(k, ((String) v).toLowerCase(java.util.Locale.US));
            else if (v instanceof JSONObject) out.put(k, normalizeSchemaTypes((JSONObject) v));
            else out.put(k, v);
        }
        return out;
    }

    static ModelTurnResult parseOpenAiToolResponse(String rawJson) {
        if (rawJson == null || rawJson.trim().isEmpty()) return null;
        try {
            JSONArray choices = new JSONObject(rawJson).optJSONArray("choices");
            JSONObject msg = choices != null && choices.length() > 0 ? choices.optJSONObject(0).optJSONObject("message") : null;
            if (msg == null) return null;
            return buildTurnFromOpenAiMessage(msg);
        } catch (Exception e) {
            return null;
        }
    }

    private static ModelTurnResult buildTurnFromOpenAiMessage(JSONObject msg) throws Exception {
        List<ToolCall> calls = extractOpenAiToolCalls(msg.optJSONArray("tool_calls"));
        String text = msg.isNull("content") ? "" : msg.optString("content", "").trim();
        if (calls.isEmpty() && isRawToolSchemaEcho(text)) return null;
        if (calls.isEmpty() && text.isEmpty()) return null;
        return new ModelTurnResult(calls, text, buildGeminiRawModelContent(calls, text));
    }

    private static boolean isRawToolSchemaEcho(String text) {
        return text.startsWith("{") && text.contains("\"type\":\"function\"") && text.contains("\"properties\"");
    }

    private static List<ToolCall> extractOpenAiToolCalls(JSONArray arr) {
        List<ToolCall> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject tc = arr.optJSONObject(i);
            JSONObject fn = tc != null ? tc.optJSONObject("function") : null;
            if (fn != null && !fn.optString("name", "").isEmpty()) {
                out.add(new ToolCall(fn.optString("name"), parseJsonArgs(fn.optString("arguments", "{}"))));
            }
        }
        return out;
    }

    private static JSONObject parseJsonArgs(String rawArgs) {
        try { return new JSONObject(rawArgs == null || rawArgs.trim().isEmpty() ? "{}" : rawArgs); }
        catch (Exception e) { return new JSONObject(); }
    }

    private static JSONObject buildGeminiRawModelContent(List<ToolCall> calls, String text) throws Exception {
        JSONArray parts = new JSONArray();
        if (!text.isEmpty()) parts.put(new JSONObject().put("text", text));
        for (ToolCall tc : calls) {
            parts.put(new JSONObject().put("functionCall", new JSONObject().put("name", tc.name).put("args", tc.args)));
        }
        return new JSONObject().put("role", "model").put("parts", parts);
    }

    static String resolveEndpointUrl(String baseUrl) {
        String clean = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return clean.endsWith("/chat/completions") ? clean : clean + "/chat/completions";
    }

    private static String postChatCompletions(String baseUrl, String apiKey, String jsonBody, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = openJsonPost(resolveEndpointUrl(baseUrl), apiKey, timeoutMs);
            try (OutputStream os = conn.getOutputStream()) { os.write(jsonBody.getBytes(StandardCharsets.UTF_8)); }
            return conn.getResponseCode() == 200 ? readAll(conn.getInputStream()) : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static HttpURLConnection openJsonPost(String urlStr, String apiKey, int timeoutMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(Math.min(4000, timeoutMs));
        conn.setReadTimeout(timeoutMs);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        if (apiKey != null && !apiKey.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        return conn;
    }

    private static String readAll(InputStream is) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }
}
