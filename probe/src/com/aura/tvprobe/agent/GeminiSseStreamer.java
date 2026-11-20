package com.aura.tvprobe;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Gemini v1beta SSE Streamer (`streamGenerateContent?alt=sse`).
 * Buffers incoming token deltas and emits complete sentences immediately
 * so AuraTtsNarrator begins speaking in ~1-1.5s instead of waiting for full output.
 */
public final class GeminiSseStreamer {

    public interface SentenceCallback {
        void onSentence(String sentence, boolean isFirst);
    }

    private GeminiSseStreamer() {}

    public static String streamGenerateText(
            String model, String apiKey, JSONObject requestBody, SentenceCallback callback, int timeoutMs) {
        if (model == null || apiKey == null || apiKey.isEmpty() || requestBody == null) return null;
        try {
            HttpURLConnection conn = openSseConnection(model, apiKey, requestBody.toString(), timeoutMs);
            return consumeSseStream(conn, callback);
        } catch (Exception e) {
            return null;
        }
    }

    public static String consumeSseLines(Iterable<String> lines, SentenceCallback callback) {
        StringBuilder full = new StringBuilder();
        StringBuilder pending = new StringBuilder();
        boolean[] first = new boolean[]{true};
        for (String line : lines) {
            appendDeltaAndFlush(extractTextDeltaFromSseLine(line), full, pending, callback, first);
        }
        emitRemainder(pending, callback, first);
        return full.toString().trim();
    }

    private static void appendDeltaAndFlush(
            String delta, StringBuilder full, StringBuilder pending, SentenceCallback cb, boolean[] first) {
        if (delta.isEmpty()) return;
        full.append(delta);
        pending.append(delta);
        flushCompletedSentences(pending, cb, first);
    }

    private static HttpURLConnection openSseConnection(
            String model, String apiKey, String body, int timeoutMs) throws Exception {
        String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model
            + ":streamGenerateContent?alt=sse&key=" + apiKey;
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(timeoutMs);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream os = conn.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
        return conn;
    }

    private static String consumeSseStream(HttpURLConnection conn, SentenceCallback callback) throws Exception {
        java.util.List<String> lines = new java.util.ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) lines.add(line);
        } finally {
            conn.disconnect();
        }
        return consumeSseLines(lines, callback);
    }

    static String extractTextDeltaFromSseLine(String line) {
        if (line == null || !line.startsWith("data:")) return "";
        String json = line.substring(5).trim();
        if (json.isEmpty() || "[DONE]".equals(json)) return "";
        try {
            JSONArray cands = new JSONObject(json).optJSONArray("candidates");
            if (cands == null || cands.length() == 0) return "";
            JSONArray parts = cands.getJSONObject(0).getJSONObject("content").getJSONArray("parts");
            return parts.length() > 0 ? parts.getJSONObject(0).optString("text", "") : "";
        } catch (Exception e) { return ""; }
    }

    private static void flushCompletedSentences(StringBuilder pending, SentenceCallback cb, boolean[] first) {
        int cut = findSentenceBoundary(pending.toString());
        while (cut > 0) {
            String sentence = pending.substring(0, cut).trim();
            pending.delete(0, cut);
            if (!sentence.isEmpty() && cb != null) { cb.onSentence(sentence, first[0]); first[0] = false; }
            cut = findSentenceBoundary(pending.toString());
        }
    }

    private static void emitRemainder(StringBuilder pending, SentenceCallback cb, boolean[] first) {
        String rem = pending.toString().trim();
        if (!rem.isEmpty() && cb != null) { cb.onSentence(rem, first[0]); first[0] = false; }
    }

    private static int findSentenceBoundary(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean endPunct = (c == '.' || c == '!' || c == '?');
            if (endPunct && i >= 12 && (i + 1 == text.length() || Character.isWhitespace(text.charAt(i + 1)))) {
                return i + 1;
            }
        }
        return -1;
    }
}
