package com.aura.tvprobe;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Central registry and parallel dispatcher for Aura's Gemini function-calling tools.
 */
public final class AuraToolRegistry {

    private static final ExecutorService TOOL_POOL = Executors.newCachedThreadPool();
    private final Map<String, AuraTool> tools = new LinkedHashMap<>();

    public AuraToolRegistry register(AuraTool tool) {
        if (tool != null && tool.name() != null && !tool.name().isEmpty()) {
            tools.put(tool.name(), tool);
        }
        return this;
    }

    public AuraTool get(String name) {
        return name != null ? tools.get(name) : null;
    }

    public int size() {
        return tools.size();
    }

    public JSONArray buildFunctionDeclarations() {
        JSONArray arr = new JSONArray();
        for (AuraTool tool : tools.values()) {
            JSONObject decl = tool.declaration();
            if (decl != null) arr.put(decl);
        }
        return arr;
    }

    public List<JSONObject> executeBatch(List<ToolModelClient.ToolCall> calls, AuraToolContext ctx) {
        if (calls == null || calls.isEmpty()) return new ArrayList<>();
        if (calls.size() == 1) return executeSingleAsList(calls.get(0), ctx);
        return executeParallelBatch(calls, ctx);
    }

    private List<JSONObject> executeSingleAsList(ToolModelClient.ToolCall call, AuraToolContext ctx) {
        List<JSONObject> out = new ArrayList<>(1);
        out.add(buildFunctionResponsePart(call.name, executeSingleSafe(call, ctx)));
        return out;
    }

    private List<JSONObject> executeParallelBatch(List<ToolModelClient.ToolCall> calls, AuraToolContext ctx) {
        List<Future<JSONObject>> futures = new ArrayList<>(calls.size());
        for (ToolModelClient.ToolCall call : calls) {
            futures.add(TOOL_POOL.submit(() -> buildFunctionResponsePart(call.name, executeSingleSafe(call, ctx))));
        }
        return collectFutures(calls, futures);
    }

    private List<JSONObject> collectFutures(List<ToolModelClient.ToolCall> calls, List<Future<JSONObject>> futures) {
        List<JSONObject> out = new ArrayList<>(futures.size());
        for (int i = 0; i < futures.size(); i++) {
            try { out.add(futures.get(i).get()); }
            catch (Exception e) { out.add(buildFunctionResponsePart(calls.get(i).name, errorJson("tool_interrupted"))); }
        }
        return out;
    }

    private JSONObject executeSingleSafe(ToolModelClient.ToolCall call, AuraToolContext ctx) {
        AuraTool tool = tools.get(call.name);
        if (tool == null) return errorJson("unknown_tool:" + call.name);
        try {
            JSONObject res = tool.execute(call.args, ctx);
            return res != null ? res : new JSONObject().put("status", "ok");
        } catch (Exception e) {
            return errorJson(e.getMessage() != null ? e.getMessage() : "tool_error");
        }
    }

    private static JSONObject buildFunctionResponsePart(String name, JSONObject responseObj) {
        try {
            JSONObject fr = new JSONObject().put("name", name).put("response", responseObj);
            return new JSONObject().put("functionResponse", fr);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static JSONObject errorJson(String message) {
        try { return new JSONObject().put("error", message); }
        catch (Exception e) { return new JSONObject(); }
    }

    public static AuraToolRegistry createDefaultRegistry() {
        AuraToolRegistry r = new AuraToolRegistry();
        AuraTool[] defaults = { new ScreenContextTool(), new TasteHistoryTool(), new SearchCatalogTool(),
            new RecommendTool(), new ExplainShowTool(), new InAppOttSearchTool(), new OpenOrPlayTool(),
            new UpdateTasteTool(), new TvControlTool(), new PresentResultsTool(), new AskClarificationTool() };
        for (AuraTool t : defaults) r.register(t);
        return r;
    }
}
