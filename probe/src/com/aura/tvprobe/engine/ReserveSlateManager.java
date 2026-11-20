package com.aura.tvprobe;

import org.json.JSONArray;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Instant 0-Second Recommendation Refresh Manager.
 * Keeps a pre-computed next-epoch reserve slate warm in AuraDb so pressing
 * "Refresh Picks" swaps in fresh recommendations in <200ms while silently
 * pre-warming the following epoch in the background.
 */
public final class ReserveSlateManager {

    private static final ExecutorService WARM_EXEC = AuraExecutors.serial();
    private static final AtomicBoolean WARMING = new AtomicBoolean(false);
    private static final int MIN_RESERVE_SIZE = 10;

    private ReserveSlateManager() {}

    public static boolean hasReadyReserve(AuraDb db, String profileId) {
        return readCleanReserve(db, profileId).length() >= MIN_RESERVE_SIZE;
    }

    public static JSONArray tryPopFreshReserveSlate(AuraDb db, String profileId) {
        JSONArray reserve = readCleanReserve(db, profileId);
        if (reserve.length() < MIN_RESERVE_SIZE) return null;
        db.setSetting(reserveKey(profileId), "");
        db.saveProfileRecommendations(profileId, reserve);
        return reserve;
    }

    public static void saveReserveSlate(AuraDb db, String profileId, JSONArray slate) {
        if (db == null || slate == null || slate.length() < MIN_RESERVE_SIZE) return;
        db.setSetting(reserveKey(profileId), slate.toString());
    }

    public static void pruneTitleFromReserve(AuraDb db, String profileId) {
        if (db == null) return;
        JSONArray clean = readCleanReserve(db, profileId);
        db.setSetting(reserveKey(profileId), clean.length() >= MIN_RESERVE_SIZE ? clean.toString() : "");
    }

    public static void warmNextReserveAsync(AuraDb db, AiRecommender ai, String profileId, JSONArray history) {
        if (db == null || ai == null || hasReadyReserve(db, profileId)) return;
        if (!WARMING.compareAndSet(false, true)) return;
        WARM_EXEC.submit(() -> executeWarmReserveTask(db, ai, profileId, history));
    }

    private static void executeWarmReserveTask(AuraDb db, AiRecommender ai, String profileId, JSONArray history) {
        try {
            JSONArray next = ai.generateNextEpochSlateWithoutSaving(profileId, history);
            saveReserveSlate(db, profileId, next);
        } catch (Throwable ignored) {
        } finally {
            WARMING.set(false);
        }
    }

    private static JSONArray readCleanReserve(AuraDb db, String profileId) {
        if (db == null) return new JSONArray();
        String raw = db.getSetting(reserveKey(profileId), "");
        if (raw == null || raw.isEmpty()) return new JSONArray();
        try {
            JSONArray unwatched = db.filterOutWatched(new JSONArray(raw));
            return TasteIntelligenceEngine.filterOutDismissed(db, profileId, unwatched);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static String reserveKey(String profileId) {
        return "reserve_recos_" + (profileId != null ? profileId : AuraDb.DEFAULT_PROFILE);
    }
}
