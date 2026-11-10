package com.aura.tvprobe;

import org.json.JSONObject;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;

/**
 * Pure, region-agnostic candidate validation, Bayesian IMDb quality scoring,
 * and genre set similarity math.
 */
public final class CandidateValidator {

    private static final double BAYES_MIN_VOTES = 3000.0;
    private static final double BAYES_PRIOR_MEAN = 7.0;

    private CandidateValidator() {}

    public static int getCurrentYear() {
        return Calendar.getInstance().get(Calendar.YEAR);
    }

    public static int getDefaultMinModernYear() {
        return getCurrentYear() - 6;
    }

    public static double computeBayesianImdb(JSONObject item) {
        if (item == null) return 0.0;
        double rating = item.optDouble("imdb_raw", item.optDouble("imdb_score", 0.0));
        if (rating <= 0.0) return 0.0;
        int votes = item.optInt("imdb_votes", 0);
        double pop = item.optDouble("tmdb_popularity", 0.0);
        double bayes = (votes / (votes + BAYES_MIN_VOTES)) * rating
            + (BAYES_MIN_VOTES / (votes + BAYES_MIN_VOTES)) * BAYES_PRIOR_MEAN;
        double popBonus = Math.min(0.65, Math.log10(Math.max(1.0, pop)) * 0.25);
        return bayes + popBonus;
    }

    public static double computeGenreJaccard(String codesA, String codesB) {
        Set<String> setA = toGenreSet(codesA);
        Set<String> setB = toGenreSet(codesB);
        if (setA.isEmpty() || setB.isEmpty()) return 0.25;
        int inter = 0;
        for (String s : setA) {
            if (setB.contains(s)) inter++;
        }
        int union = setA.size() + setB.size() - inter;
        return union > 0 ? (double) inter / (double) union : 0.0;
    }

    private static Set<String> toGenreSet(String codes) {
        Set<String> set = new HashSet<>();
        if (codes == null || codes.isEmpty()) return set;
        set.addAll(Arrays.asList(codes.split(",")));
        set.remove("");
        return set;
    }

    public static boolean hasRequiredBaseFields(JSONObject parsed, int minYear, double minImdb) {
        if (parsed == null || parsed.optString("title").isEmpty()) return false;
        int year = parsed.optInt("year", 9999);
        int curYear = getCurrentYear();
        if (year > curYear || year < minYear) return false;
        if (!parsed.optBoolean("has_flatrate_offer", false) || parsed.optString("poster_url").isEmpty()) return false;
        int votes = parsed.optInt("imdb_votes", 0);
        if (year == curYear && votes < 800) return false;
        return votes >= 400 && parsed.optDouble("imdb_raw", 0.0) >= minImdb;
    }

    public static boolean isValidCatalogCandidate(JSONObject parsed, int minYear, double minImdb) {
        if (!hasRequiredBaseFields(parsed, minYear, minImdb)) return false;
        int votes = parsed.optInt("imdb_votes", 0);
        double pop = parsed.optDouble("tmdb_popularity", 0.0);
        String gCodes = parsed.optString("genre_codes", "");
        if (gCodes.contains("spt") || gCodes.contains("doc")) {
            return votes >= 600 && (pop >= 5.0 || votes >= 1800);
        }
        return votes >= 900 && (pop >= 10.5 || votes >= 8500);
    }

    public static boolean isValidCoWatchCandidate(JSONObject parsed, int minYear, double minImdb) {
        if (!hasRequiredBaseFields(parsed, minYear, minImdb)) return false;
        int votes = parsed.optInt("imdb_votes", 0);
        double pop = parsed.optDouble("tmdb_popularity", 0.0);
        return votes >= 650 && (pop >= 5.5 || votes >= 1800);
    }

    public static boolean isUnwantedKidsAnimation(String genreCodes) {
        if (genreCodes == null) return false;
        return genreCodes.contains("fml") || genreCodes.contains("ani");
    }

    public static boolean isUnwantedAnimation(String genreCodes, boolean animeEnabled) {
        if (genreCodes == null) return false;
        if (genreCodes.contains("fml")) return true;
        return !animeEnabled && genreCodes.contains("ani");
    }

    public static boolean isCompatibleFormatForSeed(String candGenres, String seedCoreGenres) {
        if (candGenres == null || seedCoreGenres == null || candGenres.contains("fml")) return false;
        boolean seedIsAnime = seedCoreGenres.contains("ani");
        boolean candIsAnime = candGenres.contains("ani");
        if (seedIsAnime != candIsAnime) return false;
        boolean seedIsDark = (seedCoreGenres.contains("hrr") || seedCoreGenres.contains("trl"))
            && !seedCoreGenres.contains("rma") && !seedCoreGenres.contains("cmy");
        return !(seedIsDark && candGenres.contains("rma"));
    }
}
