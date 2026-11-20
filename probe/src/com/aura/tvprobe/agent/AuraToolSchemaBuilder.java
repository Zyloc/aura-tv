package com.aura.tvprobe;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Concise Gemini v1beta JSON Schema builder for AuraTool declarations.
 */
public final class AuraToolSchemaBuilder {

    private AuraToolSchemaBuilder() {}

    public static JSONObject declare(String name, String description, JSONObject properties, String... required) {
        try {
            JSONObject params = new JSONObject().put("type", "OBJECT")
                .put("properties", properties != null ? properties : new JSONObject());
            if (required != null && required.length > 0) params.put("required", toJsonArray(required));
            return new JSONObject().put("name", name).put("description", description).put("parameters", params);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static JSONObject strProp(String description) {
        return typedProp("STRING", description);
    }

    public static JSONObject intProp(String description) {
        return typedProp("INTEGER", description);
    }

    public static JSONObject boolProp(String description) {
        return typedProp("BOOLEAN", description);
    }

    public static JSONObject strArrayProp(String description) {
        try {
            return new JSONObject().put("type", "ARRAY").put("description", description)
                .put("items", new JSONObject().put("type", "STRING"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static JSONObject enumProp(String description, String... values) {
        try {
            return new JSONObject().put("type", "STRING").put("description", description)
                .put("enum", toJsonArray(values));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static JSONObject typedProp(String type, String description) {
        try { return new JSONObject().put("type", type).put("description", description); }
        catch (Exception e) { return new JSONObject(); }
    }

    private static JSONArray toJsonArray(String[] values) {
        JSONArray arr = new JSONArray();
        for (String v : values) arr.put(v);
        return arr;
    }
}
