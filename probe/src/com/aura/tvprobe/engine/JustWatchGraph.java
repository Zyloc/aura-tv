package com.aura.tvprobe;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Modular, region-agnostic GraphQL Retrieval & Verification Engine for JustWatch (ANDROID_TV).
 * Dynamically resolves country, language, and streaming providers via RegionProviderRegistry,
 * and delegates quality/similarity math to CandidateValidator and HybridScorer.
 */
public class JustWatchGraph {

    private static final String GRAPHQL_URL = "https://apis.justwatch.com/graphql";
    private static final ExecutorService NET_EXEC = Executors.newCachedThreadPool();

    private static final String NODE_FIELDS =
        "objectType " +
        "content(country: $country, language: $language) { " +
        "  title originalTitle fullPath originalReleaseYear runtime shortDescription productionCountries " +
        "  genres { shortName } scoring { imdbScore imdbVotes tmdbPopularity } " +
        "  posterUrl backdrops { backdropUrl } " +
        "} " +
        "offers(country: $country, platform: ANDROID_TV) { " +
        "  monetizationType standardWebURL deeplinkURL(platform: ANDROID_TV) " +
        "  package { clearName shortName } " +
        "}";

    private static final String SEARCH_QUERY =
        "query GetTitle($searchQuery: String!, $country: Country!, $language: Language!, $packages: [String!]) {" +
        "  popularTitles(country: $country, filter: { searchQuery: $searchQuery, packages: $packages, monetizationTypes: [FLATRATE, FREE, ADS] }, first: 8) {" +
        "    edges { node { " + NODE_FIELDS + " } }" +
        "  }" +
        "}";

    private static final String SIMILAR_QUERY =
        "query GetSimilar($searchQuery: String!, $country: Country!, $language: Language!) {" +
        "  popularTitles(country: $country, filter: { searchQuery: $searchQuery }, first: 3) {" +
        "    edges { node { " +
        "      " + NODE_FIELDS +
        "      ... on MovieOrShow {" +
        "        similarTitles(country: $country) { " + NODE_FIELDS + " }" +
        "      }" +
        "    }}" +
        "  }" +
        "}";

    private static final String STRUCTURED_DISCOVERY_QUERY =
        "query DiscoverByFilter($country: Country!, $language: Language!, $filter: TitleFilter) {" +
        "  popularTitles(country: $country, filter: $filter, first: 28) {" +
        "    edges { node { " + NODE_FIELDS + " } }" +
        "  }" +
        "}";

    public static int getCurrentYear() {
        return CandidateValidator.getCurrentYear();
    }

    public static String getCurrentDateString() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().getTime());
    }

    public static int getDefaultMinModernYear() {
        return CandidateValidator.getDefaultMinModernYear();
    }

    public static JSONArray getDefaultPackagesJson() {
        return RegionProviderRegistry.getDefaultPackagesJson();
    }

    public static JSONObject lookupTitleInIndia(String title) {
        return lookupTitle(title, RegionProviderRegistry.getCountryCode(null), RegionProviderRegistry.getLanguageCode(null));
    }

    public static JSONObject lookupTitle(String title, String country, String language) {
        if (title == null || title.trim().isEmpty()) return new JSONObject();
        try {
            JSONObject vars = buildSearchVars(title.trim(), country, language);
            JSONArray edges = extractEdges(postGraphQl(SEARCH_QUERY, vars));
            return selectBestLookupMatch(edges, AuraDb.normalizeTitleKey(title), language);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static JSONObject buildSearchVars(String query, String country, String language) throws Exception {
        return new JSONObject()
            .put("searchQuery", query)
            .put("country", country)
            .put("language", language)
            .put("packages", getDefaultPackagesJson());
    }

    private static JSONObject selectBestLookupMatch(JSONArray edges, String normTarget, String lang) {
        if (edges == null || edges.length() == 0) return new JSONObject();
        JSONObject best = null;
        for (int i = 0; i < edges.length(); i++) {
            JSONObject parsed = parseNode(edges.optJSONObject(i).optJSONObject("node"), lang);
            if (! parsed.optBoolean("has_flatrate_offer", false) || parsed.optInt("year", 9999) > getCurrentYear()) continue;
            if (matchesTargetTitleOrSlug(parsed, normTarget)) return parsed;
            if (best == null && CandidateValidator.isValidCoWatchCandidate(parsed, getDefaultMinModernYear() - 2, 6.8)) {
                best = parsed;
            }
        }
        return best != null ? best : new JSONObject();
    }

    private static boolean matchesTargetTitleOrSlug(JSONObject parsed, String normTarget) {
        String titleKey = AuraDb.normalizeTitleKey(parsed.optString("title"));
        String slugKey = parsed.optString("slug_key", "");
        return !normTarget.isEmpty() && (titleKey.equals(normTarget) || slugKey.equals(normTarget));
    }

    public static JSONArray fetchStructuredGenreCandidates(
            List<String> genreCodes, List<String> packageCodes, int minYear, int maxYear) {
        return fetchStructuredGenreCandidates(genreCodes, packageCodes, null, minYear, maxYear, false);
    }

    public static JSONArray fetchStructuredGenreCandidates(
            List<String> genreCodes, List<String> packageCodes, String originCountry,
            int minYear, int maxYear, boolean isCoWatchExpansion) {
        JSONArray out = new JSONArray();
        try {
            String country = RegionProviderRegistry.getCountryCode(null);
            String lang = RegionProviderRegistry.getLanguageCode(null);
            JSONObject filter = buildDiscoveryFilter(genreCodes, packageCodes, originCountry, minYear, maxYear);
            JSONObject vars = new JSONObject().put("country", country).put("language", lang).put("filter", filter);
            JSONArray edges = extractEdges(postGraphQl(STRUCTURED_DISCOVERY_QUERY, vars));
            collectValidCandidates(edges, out, minYear, lang, isCoWatchExpansion);
        } catch (Exception ignored) {}
        return out;
    }

    private static JSONObject buildDiscoveryFilter(
            List<String> genreCodes, List<String> packageCodes, String originCountry,
            int minYear, int maxYear) throws Exception {
        JSONObject filter = new JSONObject();
        filter.put("packages", toPackageArray(packageCodes));
        filter.put("monetizationTypes", new JSONArray().put("FLATRATE"));
        filter.put("releaseYear", new JSONObject().put("min", minYear).put("max", maxYear));
        JSONArray gArr = toValidGenreArray(genreCodes);
        if (gArr.length() > 0) filter.put("genres", gArr);
        if (originCountry != null && originCountry.length() == 2) {
            filter.put("productionCountries", new JSONArray().put(originCountry.toUpperCase(Locale.US)));
        }
        return filter;
    }

    private static JSONArray toPackageArray(List<String> packageCodes) {
        if (packageCodes == null || packageCodes.isEmpty()) return getDefaultPackagesJson();
        JSONArray pkgs = new JSONArray();
        for (String p : packageCodes) pkgs.put(p);
        return pkgs;
    }

    private static JSONArray toValidGenreArray(List<String> genreCodes) {
        JSONArray gArr = new JSONArray();
        if (genreCodes == null) return gArr;
        for (String g : genreCodes) {
            if (isValidGenreCode(g)) gArr.put(g);
        }
        return gArr;
    }

    private static void collectValidCandidates(
            JSONArray edges, JSONArray out, int minYear, String lang, boolean isCoWatch) {
        if (edges == null) return;
        for (int i = 0; i < edges.length(); i++) {
            JSONObject edge = edges.optJSONObject(i);
            if (edge == null) continue;
            JSONObject parsed = parseNode(edge.optJSONObject("node"), lang);
            boolean valid = isCoWatch
                ? CandidateValidator.isValidCoWatchCandidate(parsed, minYear, 7.1)
                : CandidateValidator.isValidCatalogCandidate(parsed, minYear, 7.1);
            if (valid) out.put(parsed);
        }
    }

    public static JSONArray fetchDeterministicSearchCandidates(String searchQuery) {
        return fetchDeterministicSearchCandidates(searchQuery, getDefaultMinModernYear());
    }

    public static JSONArray fetchDeterministicSearchCandidates(String searchQuery, int minYear) {
        String q = searchQuery == null ? "" : searchQuery.trim();
        List<String> genres = inferGenreCodesFromText(q);
        List<String> pkgs = inferPackageCodesFromText(q);
        if (!genres.isEmpty() || !pkgs.isEmpty() || q.contains("->") || q.length() > 28) {
            List<String> gList = genres.isEmpty() ? Arrays.asList("trl", "crm", "scf", "drm") : genres;
            JSONArray structured = fetchStructuredGenreCandidates(gList, pkgs, minYear, getCurrentYear());
            if (structured.length() > 0) return structured;
        }
        return executeDirectKeywordSearch(q, minYear);
    }

    private static JSONArray executeDirectKeywordSearch(String query, int minYear) {
        JSONArray out = new JSONArray();
        try {
            String country = RegionProviderRegistry.getCountryCode(null);
            String lang = RegionProviderRegistry.getLanguageCode(null);
            JSONObject filter = new JSONObject()
                .put("searchQuery", query)
                .put("packages", getDefaultPackagesJson())
                .put("monetizationTypes", new JSONArray().put("FLATRATE").put("FREE").put("ADS"));
            JSONObject vars = new JSONObject().put("country", country).put("language", lang).put("filter", filter);
            collectValidCandidates(extractEdges(postGraphQl(STRUCTURED_DISCOVERY_QUERY, vars)), out, minYear, lang, false);
        } catch (Exception ignored) {}
        return out;
    }

    public static JSONArray fetchCollaborativeSimilar(String seedTitle) {
        return fetchCollaborativeSimilar(seedTitle, getDefaultMinModernYear());
    }

    public static JSONArray fetchCollaborativeSimilar(String seedTitle, int minYear) {
        List<JSONObject> candidates = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        String cleanSeed = seedTitle == null ? "" : seedTitle.split("\u00B7")[0].trim();
        String normSeed = AuraDb.normalizeTitleKey(cleanSeed);
        seenKeys.add(normSeed);

        List<String> seedGenreCodes = new ArrayList<>();
        JSONObject seedMeta = extractDirectCoWatchNeighbors(cleanSeed, normSeed, minYear, seenKeys, seedGenreCodes, candidates);
        if (seedMeta != null && candidates.size() < 6) {
            expandSparseCoWatchGraph(cleanSeed, seedMeta, seedGenreCodes, minYear, seenKeys, candidates);
        }
        return sortAndPackSimilarCandidates(candidates, seedMeta, seedGenreCodes);
    }

    private static JSONObject extractDirectCoWatchNeighbors(
            String cleanSeed, String normSeed, int minYear, Set<String> seenKeys,
            List<String> seedGenreCodes, List<JSONObject> candidates) {
        JSONObject seedMeta = null;
        try {
            String country = RegionProviderRegistry.getCountryCode(null);
            String lang = RegionProviderRegistry.getLanguageCode(null);
            JSONObject vars = new JSONObject().put("searchQuery", cleanSeed).put("country", country).put("language", lang);
            JSONArray edges = extractEdges(postGraphQl(SIMILAR_QUERY, vars));
            if (edges == null) return null;
            int maxEdges = Math.min(2, edges.length());
            for (int eIdx = 0; eIdx < maxEdges; eIdx++) {
                JSONObject node = edges.getJSONObject(eIdx).optJSONObject("node");
                seedMeta = processSeedEdgeNode(node, normSeed, minYear, lang, seedMeta, seenKeys, seedGenreCodes, candidates);
            }
        } catch (Exception ignored) {}
        return seedMeta;
    }

    private static JSONObject processSeedEdgeNode(
            JSONObject node, String normSeed, int minYear, String lang, JSONObject currentSeedMeta,
            Set<String> seenKeys, List<String> seedGenreCodes, List<JSONObject> candidates) throws Exception {
        if (node == null) return currentSeedMeta;
        JSONObject nodeMeta = parseNode(node, lang);
        String resolvedKey = AuraDb.normalizeTitleKey(nodeMeta.optString("title", ""));
        String slugKey = nodeMeta.optString("slug_key", "");
        if (!isCloseSeedMatch(resolvedKey, slugKey, normSeed)) {
            return currentSeedMeta;
        }
        if (!resolvedKey.isEmpty()) seenKeys.add(resolvedKey);
        if (!slugKey.isEmpty()) seenKeys.add(slugKey);
        String coreGenres = collectCoreSeedGenres(nodeMeta.optString("genre_codes", ""), seedGenreCodes);
        extractSimilarFromNode(node.optJSONArray("similarTitles"), coreGenres, minYear, lang, seenKeys, candidates);
        return (currentSeedMeta == null || slugKey.equals(normSeed)) ? nodeMeta : currentSeedMeta;
    }

    private static boolean isCloseSeedMatch(String resolvedKey, String slugKey, String normSeed) {
        if (normSeed.isEmpty()) return false;
        if (resolvedKey.equals(normSeed) || slugKey.equals(normSeed)) return true;
        int diff = Math.abs(resolvedKey.length() - normSeed.length());
        return diff <= 5 && (resolvedKey.contains(normSeed) || normSeed.contains(resolvedKey));
    }

    private static String collectCoreSeedGenres(String rawCodes, List<String> seedGenreCodes) {
        List<String> specific = new ArrayList<>();
        if (rawCodes != null && !rawCodes.isEmpty()) {
            for (String c : rawCodes.split(",")) {
                String t = c.trim();
                if (t.isEmpty() || "fml".equals(t) || "msc".equals(t)) continue;
                if (!seedGenreCodes.contains(t)) seedGenreCodes.add(t);
                if (!"drm".equals(t)) specific.add(t);
            }
        }
        return !specific.isEmpty() ? String.join(",", specific) : String.join(",", seedGenreCodes);
    }

    private static void extractSimilarFromNode(
            JSONArray sim, String seedCoreGenres, int minYear, String lang,
            Set<String> seenKeys, List<JSONObject> candidates) throws Exception {
        if (sim == null) return;
        for (int i = 0; i < sim.length(); i++) {
            JSONObject parsed = parseNode(sim.optJSONObject(i), lang);
            if (!CandidateValidator.isValidCoWatchCandidate(parsed, Math.min(minYear, 2020), 7.1)) continue;
            String candGenres = parsed.optString("genre_codes", "");
            if (!CandidateValidator.isCompatibleFormatForSeed(candGenres, seedCoreGenres)) continue;
            if (CandidateValidator.computeGenreJaccard(candGenres, seedCoreGenres) < 0.15) continue;
            String k = AuraDb.normalizeTitleKey(parsed.optString("title"));
            if (!k.isEmpty() && seenKeys.add(k)) {
                parsed.put("is_direct_sim", true);
                candidates.add(parsed);
            }
        }
    }

    private static void expandSparseCoWatchGraph(
            String cleanSeed, JSONObject seedMeta, List<String> seedGenreCodes,
            int minYear, Set<String> seenKeys, List<JSONObject> candidates) {
        List<String> queryGenres = selectSpecificExpansionGenres(cleanSeed, seedGenreCodes);
        boolean isAnime = seedGenreCodes.contains("ani");
        String originCountry = isAnime ? "JP" : (seedMeta != null ? seedMeta.optString("country", "") : null);
        JSONArray structured = fetchStructuredGenreCandidates(
            queryGenres, null, originCountry, Math.max(2020, minYear), getCurrentYear(), true);
        String seedCore = String.join(",", seedGenreCodes);
        appendStructuredExpansion(structured, seedCore, seenKeys, candidates);
    }

    private static List<String> selectSpecificExpansionGenres(String cleanSeed, List<String> seedGenreCodes) {
        if (seedGenreCodes.contains("ani")) return Arrays.asList("ani", "act");
        List<String> specific = new ArrayList<>();
        for (String p : new String[]{"hrr", "trl", "crm", "scf", "rma", "cmy", "act", "fnt"}) {
            if (seedGenreCodes.contains(p) && specific.size() < 2) specific.add(p);
        }
        if (!specific.isEmpty()) return specific;
        List<String> inferred = inferGenreCodesFromText(cleanSeed);
        return inferred.isEmpty() ? Arrays.asList("trl", "crm") : inferred;
    }

    private static void appendStructuredExpansion(
            JSONArray structured, String seedCoreGenres, Set<String> seenKeys, List<JSONObject> candidates) {
        for (int i = 0; i < structured.length(); i++) {
            JSONObject p = structured.optJSONObject(i);
            if (p == null) continue;
            String candGenres = p.optString("genre_codes", "");
            if (!CandidateValidator.isCompatibleFormatForSeed(candGenres, seedCoreGenres)) continue;
            if (CandidateValidator.computeGenreJaccard(candGenres, seedCoreGenres) < 0.28) continue;
            String k = AuraDb.normalizeTitleKey(p.optString("title"));
            if (!k.isEmpty() && seenKeys.add(k)) {
                try { p.put("is_direct_sim", true); } catch (Exception ignored) {}
                candidates.add(p);
            }
        }
    }

    private static JSONArray sortAndPackSimilarCandidates(
            List<JSONObject> candidates, JSONObject seedMeta, List<String> seedGenreCodes) {
        int curYear = getCurrentYear();
        candidates.sort((a, b) -> Double.compare(
            HybridScorer.computeItemToItemScore(b, seedMeta, curYear),
            HybridScorer.computeItemToItemScore(a, seedMeta, curYear)));
        String seedCtry = seedMeta != null ? seedMeta.optString("country", "") : "";
        String seedGs = String.join(",", seedGenreCodes);
        JSONArray out = new JSONArray();
        for (int i = 0; i < candidates.size() && i < 16; i++) {
            JSONObject c = candidates.get(i);
            try {
                c.put("seed_country", seedCtry);
                c.put("seed_genres", seedGs);
            } catch (Exception ignored) {}
            out.put(c);
        }
        return out;
    }

    public static double computeItemToItemSimilarityScore(JSONObject cand, JSONObject seed, int currentYear) {
        return HybridScorer.computeItemToItemScore(cand, seed, currentYear);
    }

    public static double computeGenreJaccard(String codesA, String codesB) {
        return CandidateValidator.computeGenreJaccard(codesA, codesB);
    }

    public static double computeBayesianImdb(JSONObject item) {
        return CandidateValidator.computeBayesianImdb(item);
    }

    public static boolean isValidModernStreamingCandidate(JSONObject parsed, int minYear, double minImdb) {
        return CandidateValidator.isValidCatalogCandidate(parsed, minYear, minImdb);
    }

    public static boolean isValidDirectSimilarCandidate(JSONObject parsed, int minYear, double minImdb) {
        return CandidateValidator.isValidCoWatchCandidate(parsed, minYear, minImdb);
    }

    private static JSONArray extractEdges(JSONObject resp) {
        if (resp == null) return null;
        JSONObject data = resp.optJSONObject("data");
        if (data == null) return null;
        JSONObject pop = data.optJSONObject("popularTitles");
        return pop != null ? pop.optJSONArray("edges") : null;
    }

    private static JSONObject parseNode(JSONObject node, String lang) {
        JSONObject res = new JSONObject();
        if (node == null) return res;
        try {
            String objType = node.optString("objectType", "SHOW");
            JSONObject content = node.optJSONObject("content");
            if (content == null) return res;
            populateBasicContent(content, objType, lang, res);
            populateScoring(content.optJSONObject("scoring"), res);
            populateArtwork(content, res);
            JSONArray gArr = content.optJSONArray("genres");
            res.put("genres", mapGenres(gArr));
            res.put("genre_codes", extractGenreCodesCsv(gArr));
            extractBestOffer(node.optJSONArray("offers"), res);
        } catch (Exception ignored) {}
        return res;
    }

    private static void populateBasicContent(JSONObject content, String objType, String lang, JSONObject res) throws Exception {
        JSONArray countries = content.optJSONArray("productionCountries");
        String country = (countries != null && countries.length() > 0) ? countries.optString(0, "") : "";
        res.put("country", country);
        String rawTitle = content.optString("title", "");
        String origTitle = content.optString("originalTitle", "");
        String fullPath = content.optString("fullPath", "");
        String slugTitle = RegionProviderRegistry.humanizeFullPathSlug(fullPath);
        res.put("slug_key", AuraDb.normalizeTitleKey(slugTitle));
        res.put("title", RegionProviderRegistry.resolveTitleFromSlugIfRomanized(rawTitle, origTitle, fullPath, country, lang));
        res.put("year", content.optInt("originalReleaseYear", getCurrentYear()));
        res.put("type", "MOVIE".equalsIgnoreCase(objType) ? "Movie" : "Series");
        int rt = content.optInt("runtime", "MOVIE".equalsIgnoreCase(objType) ? 115 : 48);
        res.put("runtime_min", rt > 0 ? rt : 48);
        res.put("synopsis", content.optString("shortDescription"));
    }

    private static void populateScoring(JSONObject scoring, JSONObject res) throws Exception {
        double imdb = scoring != null ? scoring.optDouble("imdbScore", 0.0) : 0.0;
        int votes = scoring != null ? scoring.optInt("imdbVotes", 0) : 0;
        double pop = scoring != null ? scoring.optDouble("tmdbPopularity", 0.0) : 0.0;
        if (Double.isNaN(imdb)) imdb = 0.0;
        if (Double.isNaN(pop)) pop = 0.0;
        res.put("imdb_raw", imdb);
        res.put("imdb_score", imdb);
        res.put("imdb_votes", votes);
        res.put("tmdb_popularity", pop);
        res.put("rating", imdb > 0 ? String.format(Locale.US, "\u2605 %.1f", imdb) : "");
    }

    private static void populateArtwork(JSONObject content, JSONObject res) throws Exception {
        String posterPath = content.optString("posterUrl");
        if (!posterPath.isEmpty()) {
            res.put("poster_url", "https://images.justwatch.com"
                + posterPath.replace("{profile}", "s718").replace("{format}", "jpg"));
        }
        JSONArray backdrops = content.optJSONArray("backdrops");
        if (backdrops == null) return;
        for (int b = 0; b < backdrops.length(); b++) {
            JSONObject bObj = backdrops.optJSONObject(b);
            String bPath = bObj != null ? bObj.optString("backdropUrl", "") : "";
            if (!bPath.isEmpty()) {
                res.put("backdrop_url", "https://images.justwatch.com"
                    + bPath.replace("{profile}", "s1920").replace("{format}", "jpg"));
                break;
            }
        }
    }

    private static void extractBestOffer(JSONArray offers, JSONObject res) throws Exception {
        JSONObject chosen = selectAllowedOffer(offers);
        if (chosen == null) {
            res.put("has_flatrate_offer", false);
            return;
        }
        JSONObject pkg = chosen.optJSONObject("package");
        String shortName = pkg != null ? pkg.optString("shortName", "").toLowerCase(Locale.US) : "";
        String rawClear = pkg != null ? pkg.optString("clearName", "") : "";
        String displayApp = RegionProviderRegistry.normalizeProviderName(shortName, rawClear);
        String deeplink = chosen.optString("deeplinkURL");
        String webUrl = chosen.optString("standardWebURL");
        res.put("has_flatrate_offer", true);
        res.put("pkg_code", shortName);
        res.put("app", displayApp);
        res.put("badge", displayApp);
        res.put("launch_uri", !deeplink.isEmpty() ? deeplink : webUrl);
        res.put("launch_pkg", RegionProviderRegistry.resolveAndroidPackage(displayApp));
    }

    private static JSONObject selectAllowedOffer(JSONArray offers) {
        if (offers == null) return null;
        for (int i = 0; i < offers.length(); i++) {
            JSONObject off = offers.optJSONObject(i);
            if (off == null || !isSupportedMonetization(off.optString("monetizationType", ""))) continue;
            JSONObject pkg = off.optJSONObject("package");
            String shortName = pkg != null ? pkg.optString("shortName", "").toLowerCase(Locale.US) : "";
            if (RegionProviderRegistry.isAllowedProvider(shortName)) return off;
        }
        return null;
    }

    private static boolean isSupportedMonetization(String mType) {
        return "FLATRATE".equalsIgnoreCase(mType) || "FREE".equalsIgnoreCase(mType) || "ADS".equalsIgnoreCase(mType);
    }

    private static String extractGenreCodesCsv(JSONArray gArr) {
        if (gArr == null || gArr.length() == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < gArr.length(); i++) {
            JSONObject g = gArr.optJSONObject(i);
            String code = g != null ? g.optString("shortName", "").trim() : "";
            if (!code.isEmpty()) {
                if (sb.length() > 0) sb.append(",");
                sb.append(code);
            }
        }
        return sb.toString();
    }

    private static String mapGenres(JSONArray gArr) {
        if (gArr == null || gArr.length() == 0) return "Drama";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < gArr.length() && i < 3; i++) {
            JSONObject g = gArr.optJSONObject(i);
            String label = g != null ? genreLabel(g.optString("shortName")) : "";
            if (!label.isEmpty()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(label);
            }
        }
        return sb.length() > 0 ? sb.toString() : "Drama";
    }

    public static String genreLabel(String code) {
        if (code == null) return "";
        switch (code) {
            case "scf": return "Sci-Fi";
            case "trl": return "Thriller";
            case "drm": return "Drama";
            case "cmy": return "Comedy";
            case "act": return "Action";
            case "hrr": case "hor": return "Horror";
            case "crm": return "Crime";
            case "rma": return "Romance";
            case "fnt": return "Fantasy";
            case "ani": return "Animation";
            case "doc": return "Documentary";
            case "spt": return "Sport";
            case "hst": return "History";
            case "war": return "War";
            case "wsn": return "Western";
            case "fml": return "Family";
            default: return "";
        }
    }

    public static boolean isValidGenreCode(String code) {
        return !genreLabel(code).isEmpty();
    }

    public static List<String> inferGenreCodesFromText(String text) {
        List<String> codes = new ArrayList<>();
        if (text == null || text.isEmpty()) return codes;
        String lower = text.toLowerCase(Locale.US);
        if (containsAny(lower, "sci-fi", "scifi", "science", "cyber", "space", "dystopia")) codes.add("scf");
        if (containsAny(lower, "thriller", "suspense", "mystery", "dark", "gritt", "mind", "psych")) codes.add("trl");
        if (containsAny(lower, "crime", "heist", "mafia", "gang", "detective", "murder", "noir")) codes.add("crm");
        if (containsAny(lower, "action", "octane", "combat", "spy", "espionage", "adventure")) codes.add("act");
        if (containsAny(lower, "horror", "scary", "supernatural", "zombie", "ghost")) codes.add("hrr");
        if (containsAny(lower, "comedy", "funny", "sitcom", "laugh", "light")) codes.add("cmy");
        if (containsAny(lower, "romance", "romantic", "love")) codes.add("rma");
        if (containsAny(lower, "fantasy", "magic")) codes.add("fnt");
        if (containsAny(lower, "anime", "animation", "animated")) codes.add("ani");
        if (containsAny(lower, "drama", "emotional", "acclaimed", "prestige")) codes.add("drm");
        return codes;
    }

    public static List<String> inferPackageCodesFromText(String text) {
        List<String> pkgs = new ArrayList<>();
        if (text == null || text.isEmpty()) return pkgs;
        String lower = text.toLowerCase(Locale.US);
        if (lower.contains("netflix")) pkgs.add("nfx");
        if (containsAny(lower, "prime", "amazon")) pkgs.add("prv");
        if (containsAny(lower, "hotstar", "jio")) pkgs.add("jhs");
        if (lower.contains("disney")) pkgs.add("dnp");
        if (lower.contains("apple")) pkgs.add("atp");
        if (lower.contains("max") || lower.contains("hbo")) pkgs.add("mxx");
        if (lower.contains("hulu")) pkgs.add("hlu");
        if (lower.contains("crunchyroll")) pkgs.add("cru");
        if (lower.contains("sony")) pkgs.add("snl");
        if (lower.contains("zee")) pkgs.add("zee");
        return pkgs;
    }

    private static boolean containsAny(String target, String... keywords) {
        for (String kw : keywords) {
            if (target.contains(kw)) return true;
        }
        return false;
    }

    private static JSONObject postGraphQl(String query, JSONObject variables) throws Exception {
        final JSONObject payload = new JSONObject().put("query", query).put("variables", variables);
        Future<JSONObject> fut = NET_EXEC.submit(() -> executeGraphQlHttp(payload));
        try {
            return fut.get(3800, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            fut.cancel(true);
            throw e;
        }
    }

    private static JSONObject executeGraphQlHttp(JSONObject payload) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(GRAPHQL_URL).openConnection();
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3500);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
        }
        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return new JSONObject(sb.toString());
        }
    }
}
