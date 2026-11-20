package com.aura.tvprobe;

import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Persistent Long-Term Natural-Language Memory Store per profile.
 * Remembers explicit user rules (e.g., "No jump scares", "Love slow-burn British espionage")
 * and injects them into every agent turn, search, and recommendation prompt.
 */
public final class UserMemoryStore {

    private static final int MAX_MEMORIES = 24;

    private UserMemoryStore() {}

    public static List<String> listMemories(AuraDb db, String profileId) {
        List<String> out = new ArrayList<>();
        if (db == null) return out;
        JSONArray arr = loadArray(db, profileId);
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, "").trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    public static boolean saveMemory(AuraDb db, String profileId, String rule) {
        String clean = rule != null ? rule.trim() : "";
        if (db == null || clean.isEmpty()) return false;
        List<String> list = listMemories(db, profileId);
        if (containsCaseInsensitive(list, clean)) return true;
        list.add(clean);
        while (list.size() > MAX_MEMORIES) list.remove(0);
        persistList(db, profileId, list);
        return true;
    }

    public static boolean forgetMemory(AuraDb db, String profileId, String query) {
        String q = query != null ? query.trim().toLowerCase(Locale.US) : "";
        if (db == null || q.isEmpty()) return false;
        List<String> list = listMemories(db, profileId);
        boolean removed = list.removeIf(item -> item.toLowerCase(Locale.US).contains(q));
        if (removed) persistList(db, profileId, list);
        return removed;
    }

    public static List<String> listAutoMemories(AuraDb db, String profileId) {
        if (db == null) return new ArrayList<>();
        String prof = (profileId != null && !profileId.isEmpty()) ? profileId : AuraDb.DEFAULT_PROFILE;
        return AutoMemoryDistiller.distillFromHistory(db, prof, System.currentTimeMillis() / 1000L);
    }

    public static List<String> listAllMemories(AuraDb db, String profileId) {
        List<String> merged = new ArrayList<>(listMemories(db, profileId));
        for (String auto : listAutoMemories(db, profileId)) {
            if (!containsCaseInsensitive(merged, auto)) merged.add(auto);
        }
        return merged;
    }

    public static String formatMemoriesForPrompt(AuraDb db, String profileId) {
        List<String> list = listAllMemories(db, profileId);
        if (list.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append("; ");
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    private static JSONArray loadArray(AuraDb db, String profileId) {
        return parseArray(db.getSetting(keyFor(profileId), "[]"));
    }

    private static JSONArray parseArray(String raw) {
        try { return new JSONArray(raw); } catch (Exception e) { return new JSONArray(); }
    }

    private static void persistList(AuraDb db, String profileId, List<String> list) {
        JSONArray arr = new JSONArray();
        for (String s : list) arr.put(s);
        db.setSetting(keyFor(profileId), arr.toString());
    }

    private static boolean containsCaseInsensitive(List<String> list, String target) {
        for (String item : list) {
            if (item.equalsIgnoreCase(target)) return true;
        }
        return false;
    }

    private static String keyFor(String profileId) {
        String prof = (profileId != null && !profileId.isEmpty()) ? profileId : AuraDb.DEFAULT_PROFILE;
        return "user_memories_" + prof;
    }
}
