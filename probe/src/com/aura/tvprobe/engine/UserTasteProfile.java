package com.aura.tvprobe;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Dynamic, data-driven user taste model built from live watch_history and rated library.
 * Tracks genre vector weights, origin country affinities, and opt-in format flags.
 */
public final class UserTasteProfile {

    private final List<String> anchors;
    private final String topAnchor;
    private final String regionCountry;
    private final String preferredLangs;
    private final String rankMode;
    private final boolean animeEnabled;
    private final boolean kidsMode;
    private final int currentEpoch;
    private final Map<String, Double> genreWeights = Collections.synchronizedMap(new HashMap<>());
    private final Map<String, Double> countryWeights = Collections.synchronizedMap(new HashMap<>());

    public UserTasteProfile(AuraDb db, String profileId, int epoch) {
        this(
            db.getRotatingTasteAnchors(profileId, epoch),
            RegionProviderRegistry.getCountryCode(db),
            buildProfileTasteContext(db, profileId),
            db.getSetting("ranking_mode", "prestige"),
            epoch
        );
        seedWeightsFromWatchedLibrary(db.listManuallyWatched());
    }

    private void seedWeightsFromWatchedLibrary(JSONArray watched) {
        if (watched == null) return;
        for (int i = 0; i < Math.min(80, watched.length()); i++) {
            applyWatchedItemTaste(watched.optJSONObject(i));
        }
    }

    private void applyWatchedItemTaste(JSONObject item) {
        if (item == null || (item.optInt("user_rating", 0) > 0 && item.optInt("user_rating", 0) <= 5)) return;
        int r = item.optInt("user_rating", 0);
        double w = r >= 8 ? 0.55 : (r >= 7 ? 0.40 : 0.25);
        String codes = item.optString("genre_codes", "");
        recordSeedMetadata(item.optString("country", ""), codes, w);
        if (codes.isEmpty()) addKeywordWeights(item.optString("genres", "").toLowerCase(Locale.US), w);
    }

    private static String buildProfileTasteContext(AuraDb db, String profileId) {
        return db.getSetting("preferred_langs", "English") + " " + db.getProfileTaste(profileId)
            + " " + db.getActiveFilters(profileId).toString()
            + ("kids".equalsIgnoreCase(profileId) ? " kids" : "");
    }

    public UserTasteProfile(List<String> anchors, String regionCountry,
                            String preferredLangs, String rankMode, int epoch) {
        this.currentEpoch = epoch;
        this.anchors = anchors != null ? new java.util.ArrayList<>(anchors) : new java.util.ArrayList<>();
        this.topAnchor = !this.anchors.isEmpty() ? this.anchors.get(0) : "";
        this.regionCountry = regionCountry != null && !regionCountry.isEmpty()
            ? regionCountry
            : RegionProviderRegistry.getCountryCode(null);
        this.preferredLangs = preferredLangs != null ? preferredLangs.toLowerCase(Locale.US) : "english";
        this.rankMode = rankMode != null ? rankMode : "prestige";
        this.animeEnabled = this.preferredLangs.contains("anime");
        this.kidsMode = this.preferredLangs.contains("kids") || this.preferredLangs.contains("children");
        applyExplicitInterestKeywords(this.preferredLangs);
    }

    private static final String[][] INTEREST_KEYWORD_MAP = {
        {"sci-fi", "scf"}, {"scifi", "scf"}, {"science fiction", "scf"}, {"thriller", "trl"},
        {"suspense", "trl"}, {"espionage", "trl"}, {"crime", "crm"}, {"mystery", "crm"},
        {"comedy", "cmy"}, {"action", "act"}, {"drama", "drm"}, {"horror", "hrr"},
        {"fantasy", "fnt"}, {"romance", "rma"}, {"sport", "spt"}, {"documentary", "doc"},
        {"history", "hst"}, {"war", "war"}
    };

    private void applyExplicitInterestKeywords(String text) {
        addKeywordWeights(text, 1.6);
    }

    private void addKeywordWeights(String text, double weight) {
        if (text == null || text.isEmpty()) return;
        for (String[] pair : INTEREST_KEYWORD_MAP) {
            if (text.contains(pair[0])) {
                genreWeights.put(pair[1], genreWeights.getOrDefault(pair[1], 0.0) + weight);
            }
        }
    }

    public List<String> getAnchors() {
        return anchors;
    }

    public String getTopAnchor() {
        return topAnchor;
    }

    public String getRankMode() {
        return rankMode;
    }

    public boolean isAnimeEnabled() {
        return animeEnabled;
    }

    public boolean isKidsMode() {
        return kidsMode;
    }

    public boolean isBlockedByKidsSafety(String genreCodes) {
        if (!kidsMode || genreCodes == null) return false;
        return genreCodes.contains("hrr") || genreCodes.contains("crm") || genreCodes.contains("trl");
    }

    public int getCurrentEpoch() {
        return currentEpoch;
    }

    public synchronized void recordSeedMetadata(String country, String genreCodes, double weight) {
        if (country != null && !country.isEmpty()) {
            String c = country.toUpperCase(Locale.US);
            countryWeights.put(c, countryWeights.getOrDefault(c, 0.0) + weight);
        }
        addGenreWeights(genreCodes, weight);
    }

    private void addGenreWeights(String genreCodes, double weight) {
        if (genreCodes == null || genreCodes.isEmpty()) return;
        for (String g : genreCodes.split(",")) {
            String code = g.trim();
            if (!code.isEmpty() && !"fml".equals(code) && !"msc".equals(code)) {
                genreWeights.put(code, genreWeights.getOrDefault(code, 0.0) + weight);
            }
        }
    }

    public synchronized void ensureDefaultGenreWeightsIfEmpty() {
        if (!genreWeights.isEmpty()) return;
        genreWeights.put("scf", 1.4);
        genreWeights.put("crm", 1.4);
        genreWeights.put("trl", 1.3);
        genreWeights.put("drm", 1.1);
        genreWeights.put("act", 0.9);
    }

    public synchronized double computeGenreAffinity(String candGenreCodes) {
        if (candGenreCodes == null || candGenreCodes.isEmpty() || genreWeights.isEmpty()) return 0.0;
        double totalWeight = 0.0;
        for (double w : genreWeights.values()) totalWeight += w;
        if (totalWeight <= 0.0) return 0.0;
        double score = 0.0;
        for (String g : candGenreCodes.split(",")) {
            score += (genreWeights.getOrDefault(g.trim(), 0.0) / totalWeight) * 2.4;
        }
        return score;
    }

    public synchronized double computeOriginAffinity(String country, int votes, double pop, boolean hasSeed) {
        if (country == null || country.isEmpty()) return 0.0;
        String c = country.toUpperCase(Locale.US);
        if (c.equals(regionCountry) && !RegionProviderRegistry.isEnglishPrimaryCountry(c)) {
            return (votes >= 22000 || (votes >= 8500 && pop >= 22.0)) ? 0.60 : -1.80;
        }
        return computeInternationalOriginAffinity(c, votes, pop, hasSeed);
    }

    private double computeInternationalOriginAffinity(String c, int votes, double pop, boolean hasSeed) {
        if (RegionProviderRegistry.isEnglishPrimaryCountry(c) && preferredLangs.contains("english")) return 0.65;
        if (countryWeights.containsKey(c) || hasSeed) {
            return (pop >= 8.0 || votes >= 3500) ? 0.50 : -0.55;
        }
        return (votes >= 35000 && pop >= 25.0) ? 0.25 : -2.10;
    }

    public boolean passesLocalShovelwareGate(String country, int votes) {
        return passesLocalShovelwareGate(country, votes, 0.0, 0, 0);
    }

    public boolean passesLocalShovelwareGate(String country, int votes, double pop, int releaseYear, int currentYear) {
        if (country == null || country.isEmpty()) return true;
        String c = country.toUpperCase(Locale.US);
        if (c.equals(regionCountry) && !RegionProviderRegistry.isEnglishPrimaryCountry(c)) {
            boolean freshBreakout = currentYear > 0 && releaseYear >= currentYear - 1 && votes >= 5000 && pop >= 15.0;
            return votes >= 25000 || freshBreakout;
        }
        return true;
    }
}
