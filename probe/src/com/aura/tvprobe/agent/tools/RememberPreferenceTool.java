package com.aura.tvprobe;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;

/**
 * Tool: remember_user_preference
 * Persists, forgets, or lists long-term natural-language viewing preferences
 * so every future recommendation, search, and conversation honors them.
 */
public final class RememberPreferenceTool implements AuraTool {

    @Override
    public String name() { return "remember_user_preference"; }

    @Override
    public String statusLabel() { return "Saving to your long-term memory\u2026"; }

    @Override
    public JSONObject declaration() {
        JSONObject props = buildProps();
        return AuraToolSchemaBuilder.declare(name(),
            "Save, forget, or list persistent natural-language viewing preferences in the user's long-term memory.",
            props, "action");
    }

    private static JSONObject buildProps() {
        JSONObject props = new JSONObject();
        try {
            props.put("action", AuraToolSchemaBuilder.enumProp("Memory operation.", "save", "forget", "list"));
            props.put("rule", AuraToolSchemaBuilder.strProp("Natural-language preference rule (e.g., 'No jump scares', 'Love slow-burn sci-fi')."));
        } catch (Exception ignored) {}
        return props;
    }

    @Override
    public JSONObject execute(JSONObject args, AuraToolContext ctx) {
        String action = args.optString("action", "list").trim();
        String rule = args.optString("rule", "").trim();
        boolean ok = applyMemoryAction(action, rule, ctx);
        return buildOutput(action, ok, UserMemoryStore.listMemories(ctx.db, ctx.profileId),
            UserMemoryStore.listAutoMemories(ctx.db, ctx.profileId));
    }

    private boolean applyMemoryAction(String action, String rule, AuraToolContext ctx) {
        if ("save".equals(action)) {
            boolean saved = UserMemoryStore.saveMemory(ctx.db, ctx.profileId, rule);
            if (saved && ctx.db != null) ctx.db.setSetting("reserve_slate_" + ctx.profileId, "");
            return saved;
        }
        if ("forget".equals(action)) {
            boolean removed = UserMemoryStore.forgetMemory(ctx.db, ctx.profileId, rule);
            if (removed && ctx.db != null) ctx.db.setSetting("reserve_slate_" + ctx.profileId, "");
            return removed;
        }
        return "list".equals(action);
    }

    private JSONObject buildOutput(String action, boolean ok, List<String> explicit, List<String> autoLearned) {
        JSONObject out = new JSONObject();
        try {
            out.put("action", action).put("success", ok)
                .put("explicit_memories", toJsonArray(explicit))
                .put("auto_learned_memories", toJsonArray(autoLearned));
        } catch (Exception ignored) {}
        return out;
    }

    private static JSONArray toJsonArray(List<String> list) {
        JSONArray arr = new JSONArray();
        for (String s : list) arr.put(s);
        return arr;
    }
}
