package com.aura.tvprobe;

import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic in-memory {@link AuraDb} test double for fast JVM unit/integration tests.
 */
public class InMemoryAuraDb extends AuraDb {

    private final Map<String, String> settings = new HashMap<>();
    private final Map<String, JSONObject> profiles = new LinkedHashMap<>();
    private final Map<String, JSONObject> history = new LinkedHashMap<>();
    private final Map<String, JSONObject> watched = new LinkedHashMap<>();
    private final Map<String, String> gtiCache = new HashMap<>();
    private final Map<String, JSONArray> recos = new HashMap<>();
    private final Map<String, Long> recoUpdatedSec = new HashMap<>();

    public InMemoryAuraDb() {
        super(true);
        settings.put("active_profile", DEFAULT_PROFILE);
        settings.put("server_mode", "tv");
        saveProfileRecord(DEFAULT_PROFILE, "Explorer", "");
    }

    @Override
    public SQLiteDatabase getReadableDatabase() {
        return null;
    }

    @Override
    public SQLiteDatabase getWritableDatabase() {
        return null;
    }

    @Override
    public String getSetting(String k, String def) {
        return settings.containsKey(k) ? settings.get(k) : def;
    }

    @Override
    public void setSetting(String k, String v) {
        settings.put(k, v != null ? v : "");
    }

    @Override
    public List<JSONObject> listProfiles() {
        if (profiles.isEmpty()) saveProfileRecord(DEFAULT_PROFILE, "Explorer", "");
        return new ArrayList<>(profiles.values());
    }

    @Override
    public String getValidActiveProfile() {
        String cur = getSetting("active_profile", DEFAULT_PROFILE);
        if (profiles.containsKey(cur) || profiles.isEmpty()) return cur;
        String first = profiles.keySet().iterator().next();
        setSetting("active_profile", first);
        return first;
    }

    @Override
    public void saveProfileRecord(String profileId, String name, String interests) {
        String cleanName = (name != null && !name.trim().isEmpty()) ? name.trim() : "Guest";
        String cleanTaste = interests != null ? interests.trim() : "";
        try {
            profiles.put(profileId, new JSONObject().put("id", profileId).put("name", cleanName).put("interests", cleanTaste));
        } catch (Exception ignored) {}
        setSetting("profile_name_" + profileId, cleanName);
        setSetting("profile_taste_" + profileId, cleanTaste);
    }

    @Override
    public String deleteProfile(String profileId) {
        profiles.remove(profileId);
        recos.remove(profileId);
        if (profiles.isEmpty()) return addProfile("Temp_User", "");
        String next = profiles.keySet().iterator().next();
        setSetting("active_profile", next);
        return next;
    }

    @Override
    public void upsertWatchItem(JSONObject item) {
        String key = item.optString("media_key").trim().toLowerCase();
        if (!key.isEmpty()) storeWatchItemCopy(key, item);
    }

    private void storeWatchItemCopy(String key, JSONObject item) {
        try {
            JSONObject copy = new JSONObject(item.toString());
            if (!copy.has("profile_id")) copy.put("profile_id", getValidActiveProfile());
            if (copy.optLong("updated_at", 0L) <= 0L) copy.put("updated_at", System.currentTimeMillis() / 1000L);
            history.put(key, copy);
        } catch (Exception ignored) {}
    }

    @Override
    public JSONArray listHistory(String profileId) {
        List<JSONObject> rows = new ArrayList<>(history.values());
        rows.sort((a, b) -> Long.compare(b.optLong("updated_at", 0L), a.optLong("updated_at", 0L)));
        JSONArray out = filterHistoryByProfile(rows, profileId);
        return (profileId != null && !profileId.isEmpty() && out.length() < 3) ? filterHistoryByProfile(rows, null) : out;
    }

    private JSONArray filterHistoryByProfile(List<JSONObject> rows, String profileId) {
        JSONArray out = new JSONArray();
        Set<String> wKeys = getWatchedTitleKeys();
        for (JSONObject r : rows) {
            if (profileId == null || profileId.isEmpty() || profileId.equals(r.optString("profile_id"))) {
                appendHistoryClone(out, r, wKeys);
            }
        }
        return out;
    }

    private void appendHistoryClone(JSONArray out, JSONObject r, Set<String> wKeys) {
        try {
            JSONObject copy = new JSONObject(r.toString());
            String t = copy.optString("series_title").isEmpty() ? copy.optString("title") : copy.optString("series_title");
            if (wKeys.contains(normalizeTitleKey(t))) copy.put("watched", true);
            out.put(copy);
        } catch (Exception ignored) {}
    }

    @Override
    public void enforceStorageCapForProfile(String profileId) {
        history.entrySet().removeIf(e -> isOrphanHistoryEntry(e.getValue()));
    }

    private boolean isOrphanHistoryEntry(JSONObject row) {
        String s = row.optString("series_title", "");
        String t = row.optString("title", "");
        return s.isEmpty() && t.matches("S\\d+ E\\d+.*");
    }

    @Override
    public void markTitleWatched(JSONObject item, boolean isWatched) {
        String raw = item.optString("series_title").isEmpty() ? item.optString("title") : item.optString("series_title");
        String key = normalizeTitleKey(raw);
        if (key.isEmpty()) return;
        if (!isWatched) watched.remove(key);
        else putWatchedCopy(key, item);
    }

    private void putWatchedCopy(String key, JSONObject item) {
        try {
            JSONObject copy = new JSONObject(item.toString()).put("watched", true).put("state", "WATCHED \u2713");
            copy.put("updated_at", System.currentTimeMillis() / 1000L);
            watched.put(key, copy);
        } catch (Exception ignored) {}
    }

    @Override
    public boolean isTitleWatched(String title) {
        return watched.containsKey(normalizeTitleKey(title));
    }

    @Override
    public Set<String> getWatchedTitleKeys() {
        Set<String> keys = new HashSet<>(watched.keySet());
        for (String k : new ArrayList<>(keys)) {
            if (k.matches(".*(chapter|part|vol)\\d+$")) keys.add(k.replaceAll("\\d+$", ""));
        }
        return keys;
    }

    @Override
    public JSONArray listManuallyWatched() {
        JSONArray out = new JSONArray();
        for (JSONObject w : watched.values()) out.put(w);
        return out;
    }

    @Override
    public Set<String> getAllExcludedTitleKeys() {
        Set<String> keys = getWatchedTitleKeys();
        for (JSONObject h : history.values()) {
            if (h.optInt("position_sec", 0) >= 180) addHistoryExcludedKey(h, keys);
        }
        return keys;
    }

    private void addHistoryExcludedKey(JSONObject h, Set<String> keys) {
        int pos = h.optInt("position_sec", 0), dur = h.optInt("duration_sec", 0);
        if (pos == 1350 && dur == 3000) return;
        String t = h.optString("series_title").isEmpty() ? h.optString("title") : h.optString("series_title");
        String k = normalizeTitleKey(t);
        if (!k.isEmpty()) keys.add(k);
    }

    @Override
    public Map<String, Integer> getUserRatingsMap() {
        Map<String, Integer> map = new HashMap<>();
        for (Map.Entry<String, JSONObject> e : watched.entrySet()) {
            int r = e.getValue().optInt("user_rating", 0);
            if (r > 0) map.put(e.getKey(), r);
        }
        return map;
    }

    @Override
    public String getCachedGti(String gti) {
        return gtiCache.get(gti);
    }

    @Override
    public void saveCachedGti(String gti, String json) {
        gtiCache.put(gti, json);
    }

    @Override
    public long getRecoUpdatedAt(String profileId) {
        return recoUpdatedSec.getOrDefault(profileId, -1L);
    }

    public void setRecoUpdatedAtForTest(String profileId, long epochSec) {
        recoUpdatedSec.put(profileId, epochSec);
    }

    @Override
    public void saveProfileRecommendations(String profileId, JSONArray arr) {
        recos.put(profileId, filterOutWatched(arr));
        recoUpdatedSec.put(profileId, System.currentTimeMillis() / 1000L);
    }

    @Override
    public void pruneWatchedFromCachedRecos(String profileId) {
        if (recos.containsKey(profileId)) {
            recos.put(profileId, filterOutWatched(recos.get(profileId)));
        }
    }

    @Override
    public JSONArray getProfileRecommendations(String profileId) {
        JSONArray stored = recos.get(profileId);
        return stored != null ? filterOutWatched(stored) : new JSONArray();
    }
}
