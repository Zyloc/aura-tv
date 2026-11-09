package com.aura.tvprobe;

import org.json.JSONArray;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Global Region, Locale & Streaming Provider Abstraction.
 * Resolves user country/language dynamically and maps international OTT providers
 * without hardcoding any single region or country.
 */
public final class RegionProviderRegistry {

    private static final String[] GLOBAL_PROVIDER_CODES = {
        "nfx", "prv", "dnp", "jhs", "atp", "mxx", "hlu", "pmp", "pck", "cru", "snl", "zee"
    };

    private static final Set<String> ALLOWED_CODES = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList(GLOBAL_PROVIDER_CODES))
    );

    private static final Set<String> ENGLISH_PRIMARY_COUNTRIES = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList("US", "GB", "CA", "AU", "NZ", "IE"))
    );

    private static final Map<String, String> CODE_TO_DISPLAY = createDisplayMap();
    private static final Map<String, String> KEYWORD_TO_PACKAGE = createPackageMap();

    private RegionProviderRegistry() {}

    private static Map<String, String> createDisplayMap() {
        Map<String, String> m = new HashMap<>();
        putPairs(m, "nfx", "Netflix", "prv", "Prime Video", "dnp", "Disney+", "jhs", "Disney+ Hotstar");
        putPairs(m, "atp", "Apple TV+", "mxx", "Max", "hlu", "Hulu", "pmp", "Paramount+");
        putPairs(m, "pck", "Peacock", "cru", "Crunchyroll", "snl", "SonyLIV", "zee", "ZEE5");
        return Collections.unmodifiableMap(m);
    }

    private static Map<String, String> createPackageMap() {
        Map<String, String> m = new HashMap<>();
        putPairs(m, "netflix", "com.netflix.ninja", "prime", "com.amazon.amazonvideo.livingroom",
            "hotstar", "in.startv.hotstar", "disney", "com.disney.disneyplus");
        putPairs(m, "apple", "com.apple.atve.androidtv.appletv", "max", "com.wbd.stream",
            "hulu", "com.hulu.livingroomplus", "crunchyroll", "com.crunchyroll.crunchyroid");
        putPairs(m, "sony", "com.sonyliv", "zee", "com.graymatrix.did", "xstream", "tv.airtel.xstream.tvapp",
            "airtel", "tv.airtel.xstream.tvapp", "youtube", "com.google.android.youtube.tv", "smarttube", "org.smarttube.stable");
        return Collections.unmodifiableMap(m);
    }

    private static void putPairs(Map<String, String> map, String... kv) {
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
    }

    public static String getCountryCode(AuraDb db) {
        String configured = db != null ? db.getSetting("region_country", "").trim().toUpperCase(Locale.US) : "";
        if (configured.length() == 2) return configured;
        String tzCountry = inferCountryFromTimezone(java.util.TimeZone.getDefault().getID());
        if (!tzCountry.isEmpty()) return tzCountry;
        String sys = Locale.getDefault().getCountry().toUpperCase(Locale.US);
        return sys.length() == 2 ? sys : "US";
    }

    private static String inferCountryFromTimezone(String tzId) {
        if (tzId == null) return "";
        if (tzId.contains("Kolkata") || tzId.contains("Calcutta")) return "IN";
        if (tzId.contains("Tokyo")) return "JP";
        if (tzId.contains("Seoul")) return "KR";
        if (tzId.contains("London")) return "GB";
        if (tzId.contains("Berlin")) return "DE";
        if (tzId.contains("Paris")) return "FR";
        if (tzId.contains("Sydney") || tzId.contains("Melbourne")) return "AU";
        if (tzId.contains("Sao_Paulo")) return "BR";
        return "";
    }

    public static String getLanguageCode(AuraDb db) {
        String configured = db != null ? db.getSetting("region_language", "").trim().toLowerCase(Locale.US) : "";
        if (configured.length() >= 2) return configured.substring(0, 2);
        String sys = Locale.getDefault().getLanguage().toLowerCase(Locale.US);
        return sys.length() >= 2 ? sys.substring(0, 2) : "en";
    }

    public static boolean isAllowedProvider(String shortName) {
        return shortName != null && ALLOWED_CODES.contains(shortName.trim());
    }

    public static boolean isPrimaryTierProvider(String shortName) {
        return "nfx".equals(shortName) || "prv".equals(shortName) || "dnp".equals(shortName)
            || "jhs".equals(shortName) || "atp".equals(shortName) || "mxx".equals(shortName) || "hlu".equals(shortName);
    }

    public static String normalizeProviderName(String shortName, String clearName) {
        if (shortName != null && CODE_TO_DISPLAY.containsKey(shortName)) {
            return CODE_TO_DISPLAY.get(shortName);
        }
        return clearName != null && !clearName.isEmpty() ? clearName : "Streaming";
    }

    public static JSONArray getDefaultPackagesJson() {
        JSONArray arr = new JSONArray();
        for (String code : GLOBAL_PROVIDER_CODES) arr.put(code);
        return arr;
    }

    public static List<String> getDefaultPackageList() {
        return Arrays.asList(GLOBAL_PROVIDER_CODES);
    }

    public static boolean isEnglishPrimaryCountry(String countryCode) {
        return countryCode != null && ENGLISH_PRIMARY_COUNTRIES.contains(countryCode.toUpperCase(Locale.US));
    }

    public static String resolveAndroidPackage(String appName) {
        if (appName == null) return "";
        String lower = appName.toLowerCase(Locale.US);
        if (lower.contains("hotstar") || lower.contains("jio")) return KEYWORD_TO_PACKAGE.get("hotstar");
        for (Map.Entry<String, String> entry : KEYWORD_TO_PACKAGE.entrySet()) {
            if (lower.contains(entry.getKey())) return entry.getValue();
        }
        return "";
    }

    public static String resolveTitleFromSlugIfRomanized(
            String rawTitle, String originalTitle, String fullPath, String country, String lang) {
        if (rawTitle == null || rawTitle.isEmpty() || !"en".equalsIgnoreCase(lang)) return rawTitle;
        if (isEnglishPrimaryCountry(country) || fullPath == null || !fullPath.contains("/")) return rawTitle;
        String slugTitle = humanizeFullPathSlug(fullPath);
        if (slugTitle.isEmpty()) return rawTitle;
        boolean sameAsOrig = originalTitle != null && rawTitle.equalsIgnoreCase(originalTitle);
        String rawNorm = AuraDb.normalizeTitleKey(rawTitle);
        String slugNorm = AuraDb.normalizeTitleKey(slugTitle);
        if (rawNorm.equals(slugNorm) || rawNorm.startsWith(slugNorm)) return rawTitle;
        return sameAsOrig ? slugTitle : rawTitle;
    }

    public static String humanizeFullPathSlug(String fullPath) {
        if (fullPath == null || !fullPath.contains("/")) return "";
        String slug = stripCollisionSuffix(fullPath.substring(fullPath.lastIndexOf('/') + 1));
        StringBuilder sb = new StringBuilder();
        for (String p : slug.split("-")) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" ");
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    private static String stripCollisionSuffix(String slug) {
        String lower = slug.toLowerCase(Locale.US);
        if (lower.matches(".*-(chapter|part|vol|volume|season)-\\d{1,2}$")) return slug;
        return slug.replaceAll("-\\d{1,2}$", "");
    }
}
