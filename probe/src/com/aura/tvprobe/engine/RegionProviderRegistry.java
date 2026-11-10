package com.aura.tvprobe;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Global Region, Locale & Streaming Provider Registry (Single Source of Truth).
 * Dynamically resolves country/language, installed OTT package variants, brand colors,
 * spoken platform filters, and PackageManager application labels without hardcoding.
 */
public final class RegionProviderRegistry {

    private static final String[] GLOBAL_PROVIDER_CODES = {
        "nfx", "prv", "dnp", "jhs", "atp", "mxx", "hlu", "pmp", "pck",
        "cru", "snl", "zee", "bbc", "itv", "unx", "abm", "stn", "glb", "cnl"
    };

    private static final Set<String> ALLOWED_CODES = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList(GLOBAL_PROVIDER_CODES)));

    private static final Set<String> ENGLISH_PRIMARY_COUNTRIES = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList("US", "GB", "CA", "AU", "NZ", "IE")));

    private static final String[][] CORE_STREAMING_CATALOG = {
        {"Netflix", "com.netflix.ninja", "#E50914", "nfx"},
        {"Prime Video", "com.amazon.amazonvideo.livingroom", "#00A8E1", "prv"},
        {"Disney+", "com.disney.disneyplus", "#113CCF", "dnp"},
        {"Disney+ Hotstar", "in.startv.hotstar", "#2563EB", "jhs"},
        {"Apple TV+", "com.apple.atve.androidtv.appletv", "#334155", "atp"},
        {"Max", "com.wbd.stream", "#002BE7", "mxx"},
        {"Hulu", "com.hulu.livingroomplus", "#1CE783", "hlu"},
        {"Peacock", "com.peacocktv.peacockandroid", "#0F172A", "pck"},
        {"Paramount+", "com.cbs.ott", "#0064FF", "pmp"},
        {"Crunchyroll", "com.crunchyroll.crunchyroid", "#F47521", "cru"},
        {"BBC iPlayer", "bbc.iplayer.android", "#EC4899", "bbc"},
        {"ITVX", "air.ITVMobilePlayer", "#10B981", "itv"},
        {"U-NEXT", "jp.unext.mediaplayer", "#0EA5E9", "unx"},
        {"ABEMA", "tv.abema", "#22C55E", "abm"},
        {"Stan", "au.com.stan.and", "#0284C7", "stn"},
        {"Globoplay", "com.globo.globotv", "#EF4444", "glb"},
        {"Canal+", "com.canal.android.canal", "#1E293B", "cnl"},
        {"SonyLIV", "com.sonyliv", "#4F46E5", "snl"},
        {"ZEE5", "com.graymatrix.did", "#9333EA", "zee"},
        {"Airtel Xstream", "tv.airtel.xstream.tvapp", "#E11D48", ""},
        {"YouTube", "com.google.android.youtube.tv", "#DC2626", ""},
        {"SmartTube", "org.smarttube.stable", "#B91C1C", ""},
        {"Plex", "com.plexapp.android", "#E5A00D", ""},
        {"Kodi", "org.xbmc.kodi", "#0284C7", ""},
        {"Stremio", "com.stremio.one", "#7C3AED", ""},
        {"MX Player", "com.mxtech.videoplayer.television", "#2563EB", ""},
        {"VLC", "org.videolan.vlc", "#EA580C", ""},
        {"Play Store", "com.android.vending", "#0F9D58", ""}
    };

    private static final Map<String, String> CODE_TO_DISPLAY = createDisplayMap();
    private static final Map<String, String> KEYWORD_TO_PACKAGE = createPackageMap();
    private static final Map<String, String> KEYWORD_TO_CODE = createKeywordToCodeMap();

    private RegionProviderRegistry() {}

    public static String[][] getCoreStreamingAppsCatalog() {
        return CORE_STREAMING_CATALOG;
    }

    private static Map<String, String> createDisplayMap() {
        Map<String, String> m = new HashMap<>();
        for (String[] row : CORE_STREAMING_CATALOG) {
            if (!row[3].isEmpty()) m.put(row[3], row[0]);
        }
        return Collections.unmodifiableMap(m);
    }

    private static Map<String, String> createPackageMap() {
        Map<String, String> m = new LinkedHashMap<>();
        putPairs(m, "netflix", "com.netflix.ninja", "prime", "com.amazon.amazonvideo.livingroom",
            "amazon", "com.amazon.amazonvideo.livingroom", "hotstar", "in.startv.hotstar",
            "jio", "in.startv.hotstar", "disney", "com.disney.disneyplus");
        putPairs(m, "apple", "com.apple.atve.androidtv.appletv", "max", "com.wbd.stream",
            "hbo", "com.wbd.stream", "hulu", "com.hulu.livingroomplus", "crunchyroll", "com.crunchyroll.crunchyroid");
        putPairs(m, "sony", "com.sonyliv", "zee", "com.graymatrix.did", "xstream", "tv.airtel.xstream.tvapp",
            "airtel", "tv.airtel.xstream.tvapp", "youtube", "com.google.android.youtube.tv", "smarttube", "org.smarttube.stable");
        putPairs(m, "iplayer", "bbc.iplayer.android", "bbc", "bbc.iplayer.android", "itvx", "air.ITVMobilePlayer",
            "u-next", "jp.unext.mediaplayer", "unext", "jp.unext.mediaplayer", "abema", "tv.abema",
            "peacock", "com.peacocktv.peacockandroid", "paramount", "com.cbs.ott", "stan", "au.com.stan.and",
            "globo", "com.globo.globotv", "canal", "com.canal.android.canal", "plex", "com.plexapp.android",
            "kodi", "org.xbmc.kodi", "stremio", "com.stremio.one", "mxtech", "com.mxtech.videoplayer.television");
        return Collections.unmodifiableMap(m);
    }

    private static Map<String, String> createKeywordToCodeMap() {
        Map<String, String> m = new LinkedHashMap<>();
        putPairs(m, "netflix", "nfx", "prime", "prv", "amazon", "prv", "hotstar", "jhs", "jio", "jhs",
            "disney", "dnp", "apple", "atp", "max", "mxx", "hbo", "mxx", "hulu", "hlu");
        putPairs(m, "paramount", "pmp", "peacock", "pck", "crunchyroll", "cru", "sony", "snl",
            "zee", "zee", "iplayer", "bbc", "bbc", "bbc", "itvx", "itv", "u-next", "unx",
            "unext", "unx", "abema", "abm", "stan", "stn", "globo", "glb", "canal", "cnl");
        return Collections.unmodifiableMap(m);
    }

    private static void putPairs(Map<String, String> map, String... kv) {
        for (int i = 0; i + 1 < kv.length; i += 2) map.put(kv[i], kv[i + 1]);
    }

    public static String getCountryCode(AuraDb db) {
        AuraDb activeDb = db != null ? db : AuraDb.peekInstance();
        String configured = activeDb != null ? activeDb.getSetting("region_country", "").trim().toUpperCase(Locale.US) : "";
        if (configured.length() == 2) return configured;
        String tzCountry = inferCountryFromTimezone(java.util.TimeZone.getDefault().getID());
        if (!tzCountry.isEmpty()) return tzCountry;
        String sys = Locale.getDefault().getCountry().toUpperCase(Locale.US);
        return sys.length() == 2 ? sys : "US";
    }

    private static String inferCountryFromTimezone(String tzId) {
        if (tzId == null) return "";
        String asia = inferAsiaOceaniaFromTz(tzId);
        if (!asia.isEmpty()) return asia;
        String euro = inferEuropeFromTz(tzId);
        return !euro.isEmpty() ? euro : inferAmericasFromTz(tzId);
    }

    private static String inferAsiaOceaniaFromTz(String tzId) {
        if (tzId.contains("Kolkata") || tzId.contains("Calcutta")) return "IN";
        if (tzId.contains("Tokyo")) return "JP";
        if (tzId.contains("Seoul")) return "KR";
        if (tzId.contains("Singapore")) return "SG";
        if (tzId.contains("Dubai")) return "AE";
        if (tzId.contains("Sydney") || tzId.contains("Melbourne")) return "AU";
        return tzId.contains("Auckland") ? "NZ" : "";
    }

    private static String inferEuropeFromTz(String tzId) {
        if (tzId.contains("London")) return "GB";
        if (tzId.contains("Berlin")) return "DE";
        if (tzId.contains("Paris")) return "FR";
        if (tzId.contains("Madrid")) return "ES";
        if (tzId.contains("Rome")) return "IT";
        return tzId.contains("Amsterdam") ? "NL" : "";
    }

    private static String inferAmericasFromTz(String tzId) {
        if (tzId.contains("New_York") || tzId.contains("Los_Angeles") || tzId.contains("Chicago") || tzId.contains("Denver")) return "US";
        if (tzId.contains("Toronto") || tzId.contains("Vancouver")) return "CA";
        if (tzId.contains("Sao_Paulo")) return "BR";
        return tzId.contains("Mexico_City") ? "MX" : "";
    }

    public static String getRegionDisplayName(AuraDb db) {
        String code = getCountryCode(db);
        String name = new Locale("", code).getDisplayCountry(Locale.ENGLISH);
        return (name != null && !name.isEmpty()) ? name : code;
    }

    public static String getProviderCodesForRegion(AuraDb db) {
        return new JSONArray(getProvidersForCountry(getCountryCode(db))).toString();
    }

    public static String getProviderDisplayListForRegion(AuraDb db) {
        List<String> codes = getProvidersForCountry(getCountryCode(db));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < codes.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(normalizeProviderName(codes.get(i), codes.get(i)));
        }
        return sb.toString();
    }

    public static String getLanguageCode(AuraDb db) {
        AuraDb activeDb = db != null ? db : AuraDb.peekInstance();
        String configured = activeDb != null ? activeDb.getSetting("region_language", "").trim().toLowerCase(Locale.US) : "";
        if (configured.length() >= 2) return configured.substring(0, 2);
        String sys = Locale.getDefault().getLanguage().toLowerCase(Locale.US);
        return sys.length() >= 2 ? sys.substring(0, 2) : "en";
    }

    public static boolean isAllowedProvider(String shortName) {
        return shortName != null && ALLOWED_CODES.contains(shortName.trim());
    }

    public static boolean isPrimaryTierProvider(String shortName) {
        return "nfx".equals(shortName) || "prv".equals(shortName) || "dnp".equals(shortName)
            || "jhs".equals(shortName) || "atp".equals(shortName) || "mxx".equals(shortName)
            || "hlu".equals(shortName) || "pmp".equals(shortName) || "pck".equals(shortName) || "bbc".equals(shortName);
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

    public static List<String> getProvidersForCountry(String countryCode) {
        String c = countryCode != null ? countryCode.trim().toUpperCase(Locale.US) : "US";
        if ("IN".equals(c)) return Arrays.asList("nfx", "prv", "jhs", "atp", "snl", "zee", "cru");
        if ("GB".equals(c)) return Arrays.asList("nfx", "prv", "dnp", "atp", "bbc", "itv", "pmp", "cru");
        if ("JP".equals(c)) return Arrays.asList("nfx", "prv", "dnp", "atp", "unx", "abm", "hlu", "cru");
        if ("AU".equals(c)) return Arrays.asList("nfx", "prv", "dnp", "atp", "stn", "pmp", "cru");
        if ("BR".equals(c)) return Arrays.asList("nfx", "prv", "dnp", "atp", "mxx", "glb", "pmp", "cru");
        if ("FR".equals(c)) return Arrays.asList("nfx", "prv", "dnp", "atp", "mxx", "cnl", "pmp", "cru");
        return Arrays.asList("nfx", "prv", "dnp", "atp", "mxx", "hlu", "pmp", "pck", "cru");
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

    public static String resolveFriendlyAppName(Context ctx, String rawPkgOrName) {
        if (rawPkgOrName == null || rawPkgOrName.isEmpty()) return "Streaming";
        String base = rawPkgOrName.split("\u00B7")[0].trim();
        String lower = base.toLowerCase(Locale.US);
        for (String[] row : CORE_STREAMING_CATALOG) {
            if (lower.equals(row[1].toLowerCase(Locale.US)) || lower.equals(row[0].toLowerCase(Locale.US))) return row[0];
        }
        String matched = matchFriendlyAppByKeyword(lower);
        if (!matched.isEmpty()) return matched;
        return queryPackageManagerAppLabel(ctx, base);
    }

    private static String matchFriendlyAppByKeyword(String lower) {
        String code = matchProviderCodeByKeyword(lower);
        if (!code.isEmpty() && CODE_TO_DISPLAY.containsKey(code)) return CODE_TO_DISPLAY.get(code);
        for (String[] row : CORE_STREAMING_CATALOG) {
            String key = row[0].toLowerCase(Locale.US).split(" ")[0];
            if (key.length() >= 3 && lower.contains(key)) return row[0];
        }
        if (lower.contains("graymatrix")) return "ZEE5";
        if (lower.contains("xstream")) return "Airtel Xstream";
        return lower.contains("mxtech") ? "MX Player" : "";
    }

    private static String queryPackageManagerAppLabel(Context ctx, String pkg) {
        if (ctx == null || !pkg.contains(".")) return pkg;
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence lbl = pm.getApplicationLabel(ai);
            if (lbl != null && lbl.length() > 0) return lbl.toString().trim();
        } catch (Throwable ignored) {}
        return pkg;
    }

    public static String matchProviderCodeByKeyword(String lowerText) {
        if (lowerText == null || lowerText.isEmpty()) return "";
        for (Map.Entry<String, String> e : KEYWORD_TO_CODE.entrySet()) {
            if (lowerText.contains(e.getKey())) return e.getValue();
        }
        return "";
    }

    public static List<String> inferPackageCodesFromText(String text) {
        List<String> pkgs = new ArrayList<>();
        if (text == null || text.isEmpty()) return pkgs;
        String lower = text.toLowerCase(Locale.US);
        for (Map.Entry<String, String> e : KEYWORD_TO_CODE.entrySet()) {
            if (lower.contains(e.getKey()) && !pkgs.contains(e.getValue())) pkgs.add(e.getValue());
        }
        return pkgs;
    }

    public static boolean matchesSpokenPlatformFilter(String itemApp, String combinedQuery) {
        List<String> requestedCodes = inferPackageCodesFromText(combinedQuery);
        if (requestedCodes.isEmpty()) return true;
        String itemCode = matchProviderCodeByKeyword(itemApp != null ? itemApp.toLowerCase(Locale.US) : "");
        return !itemCode.isEmpty() && requestedCodes.contains(itemCode);
    }

    public static int getPlatformColorInt(String app) {
        if (app == null || app.isEmpty()) return 0xFF334155;
        String lower = app.toLowerCase(Locale.US);
        for (String[] row : CORE_STREAMING_CATALOG) {
            String key = row[0].toLowerCase(Locale.US).split(" ")[0];
            if (lower.contains(key) || lower.contains(row[1].toLowerCase(Locale.US))) {
                try { return android.graphics.Color.parseColor(row[2]); } catch (Throwable ignored) {}
            }
        }
        return 0xFF334155;
    }

    public static void adaptSeedItemForRegion(JSONObject item, String countryCode) {
        if (item == null || "IN".equalsIgnoreCase(countryCode)) return;
        if (!"jhs".equals(item.optString("provider_code"))) return;
        try {
            boolean isJackal = item.optString("title").toLowerCase(Locale.US).contains("jackal");
            String newCode = isJackal && "US".equalsIgnoreCase(countryCode) ? "pck" : "pmp";
            String newApp = normalizeProviderName(newCode, "Paramount+");
            item.put("provider_code", newCode).put("app", newApp);
            item.put("launch_pkg", resolveAndroidPackage(newApp)).put("launch_uri", "");
        } catch (Exception ignored) {}
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
        return slug.replaceAll("(-(19|20)\\d{2})?(-\\d{1,2})?$", "");
    }
}
