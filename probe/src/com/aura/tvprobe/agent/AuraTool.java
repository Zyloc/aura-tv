package com.aura.tvprobe;

import org.json.JSONObject;

/**
 * Contract for every tool exposed to Aura's unified function-calling agent.
 * Implementations must be small, deterministic, and return structured JSON.
 */
public interface AuraTool {

    String name();

    String statusLabel();

    JSONObject declaration();

    JSONObject execute(JSONObject args, AuraToolContext ctx);
}
