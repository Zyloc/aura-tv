package com.aura.tvprobe;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;

/**
 * Tool #16: blend_profiles
 * 1-click "Movie Night Blend" that finds high-confidence shows & movies matching the
 * intersection of two household profiles' tastes while excluding anything either has seen.
 */
public final class BlendProfilesTool implements AuraTool {

    @Override
    public String name() { return "blend_profiles"; }

    @Override
    public String statusLabel() { return "Blending household tastes for movie night\u2026"; }

    @Override
    public JSONObject declaration() {
        JSONObject props = buildProps();
        return AuraToolSchemaBuilder.declare(name(),
            "Find shows and movies that match BOTH household profiles for movie night together, excluding anything either person has already watched or disliked.",
            props);
    }

    private static JSONObject buildProps() {
        JSONObject props = new JSONObject();
        try {
            props.put("profile_a", AuraToolSchemaBuilder.strProp("First profile ID (default: active profile, e.g. member1)."));
            props.put("profile_b", AuraToolSchemaBuilder.strProp("Second profile ID (default: partner, or kids / member3 / family)."));
            props.put("limit", AuraToolSchemaBuilder.intProp("Max blended picks to return (default 8)."));
        } catch (Exception ignored) {}
        return props;
    }

    @Override
    public JSONObject execute(JSONObject args, AuraToolContext ctx) {
        String pA = args.optString("profile_a", ctx.profileId != null ? ctx.profileId : "member1");
        String pB = args.optString("profile_b", "partner".equalsIgnoreCase(pA) ? "member1" : "partner");
        int limit = Math.max(4, Math.min(12, args.optInt("limit", 8)));
        List<JSONObject> picks = HouseholdBlendEngine.blendProfiles(ctx.db, pA, pB, limit);
        JSONArray results = toJsonArray(picks);
        ctx.visualResults = results;
        ctx.visualHeadline = "Movie Night Blend";
        presentBlendToUi(ctx, results);
        return buildToolResponse(pA, pB, results);
    }

    private void presentBlendToUi(AuraToolContext ctx, JSONArray results) {
        if (ctx.uiBridge == null || results.length() == 0) return;
        try {
            JSONArray chips = new JSONArray().put("Play the first one").put("Make it lighter").put("Under 2 hours");
            JSONObject payload = new JSONObject()
                .put("headline", "Movie Night Blend")
                .put("reply", "Here are the best picks you’ll both enjoy tonight.")
                .put("results", results).put("follow_ups", chips);
            ctx.uiBridge.presentVisualResults(payload);
        } catch (Exception ignored) {}
    }

    private static JSONArray toJsonArray(List<JSONObject> list) {
        JSONArray arr = new JSONArray();
        for (JSONObject o : list) arr.put(o);
        return arr;
    }

    private static JSONObject buildToolResponse(String pA, String pB, JSONArray results) {
        JSONObject out = new JSONObject();
        try {
            out.put("profile_a", pA).put("profile_b", pB).put("count", results.length()).put("results", results);
        } catch (Exception ignored) {}
        return out;
    }
}
