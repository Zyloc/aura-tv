package com.aura.tvprobe;

import org.json.JSONObject;

/**
 * Tool: control_tv_or_playback
 * Executes navigation and playback control commands: go_home, pause, resume,
 * stop_narration, or dismiss_overlay.
 */
public final class TvControlTool implements AuraTool {

    @Override
    public String name() { return "control_tv_or_playback"; }

    @Override
    public String statusLabel() { return "Executing TV command\u2026"; }

    @Override
    public JSONObject declaration() {
        JSONObject props = new JSONObject();
        try {
            props.put("command", AuraToolSchemaBuilder.enumProp("TV or playback control command.",
                "go_home", "pause", "resume", "stop_narration", "dismiss_overlay"));
        } catch (Exception ignored) {}
        return AuraToolSchemaBuilder.declare(name(),
            "Control TV navigation or playback (go home, pause, resume, stop narration, dismiss overlay).",
            props, "command");
    }

    @Override
    public JSONObject execute(JSONObject args, AuraToolContext ctx) {
        String command = args.optString("command", "").trim();
        if (!command.isEmpty() && ctx.uiBridge != null) {
            ctx.uiBridge.performTvControl(command);
            ctx.actionExecutedDirectly = true;
        }
        JSONObject out = new JSONObject();
        try { out.put("command", command).put("executed", !command.isEmpty()); }
        catch (Exception ignored) {}
        return out;
    }
}
