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
        "  credits { name role } posterUrl backdrops { backdropUrl } " +
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
        "  popularTitles(country: $country, filter: { searchQuery: $searchQuery }, first: 4) {" +
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

    private static final String DYNAMIC_GRAPH_QUERY =
        "query DynamicGraph($country: Country!, $language: Language!, $filter: TitleFilter!, $first: Int!, $includeSimilar: Boolean!) {" +
        "  popularTitles(country: $country, filter: $filter, first: $first) {" +
        "    edges { node { " +
        "      " + NODE_FIELDS +
        "      ... on MovieOrShow {" +
        "        similarTitles(country: $country) @include(if: $includeSimilar) { " + NODE_FIELDS + " }" +
        "      }" +
        "    }}" +
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

    public static JSONObject lookupTitle(String title) {
        return lookupTitle(title, RegionProviderRegistry.getCountryCode(null), RegionProviderRegistry.getLanguageCode(null));
    }

    public static JSONObject lookupTitleInIndia(String title) {
        return lookupTitle(title);
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

    public static String lookupDirectProviderUri(String androidPkg, String query) {
        JSONArray codes = resolveProviderCodesForPkg(androidPkg);
        if (codes.length() == 0 || query == null || query.trim().isEmpty()) return "";
        try {
            String country = RegionProviderRegistry.getCountryCode(null);
            String lang = RegionProviderRegistry.getLanguageCode(null);
            JSONObject vars = new JSONObject().put("searchQuery", query.trim())
                .put("country", country).put("language", lang).put("packages", codes);
            JSONObject match = selectBestLookupMatch(extractEdges(postGraphQl(SEARCH_QUERY, vars)),
                AuraDb.normalizeTitleKey(query), lang);
            return match.optString("launch_uri", "").trim();
        } catch (Exception e) { return ""; }
    }

    private static JSONArray resolveProviderCodesForPkg(String pkg) {
        if ("in.startv.hotstar".equals(pkg)) return new JSONArray().put("jhs").put("dnp").put("hst");
        if ("com.disney.disneyplus".equals(pkg)) return new JSONArray().put("dnp");
        if ("com.apple.atve.androidtv.appletv".equals(pkg)) return new JSONArray().put("atp");
        if ("com.graymatrix.did".equals(pkg)) return new JSONArray().put("zee");
        return new JSONArray();
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
            if (!isLookupEligible(parsed)) continue;
            if (matchesTargetTitleOrSlug(parsed, normTarget)) return parsed;
            if (best == null && isCloseLookupMatch(parsed, normTarget)) best = parsed;
        }
        return best != null ? best : new JSONObject();
    }

    private static boolean isCloseLookupMatch(JSONObject parsed, String normTarget) {
        String titleKey = AuraDb.normalizeTitleKey(parsed.optString("title"));
        String slugKey = parsed.optString("slug_key", "");
        return isCloseSeedMatch(titleKey, slugKey, normTarget)
            && CandidateValidator.isValidCoWatchCandidate(parsed, 1950, 5.5);
    }

    private static boolean isLookupEligible(JSONObject parsed) {
        return CandidateValidator.hasRequiredBaseFields(parsed, 1950, 5.2);
    }

    public static JSONArray fetchCastSeedCandidates(JSONArray castSeedTitles, String format) {
        if (castSeedTitles == null || castSeedTitles.length() == 0) return new JSONArray();
        JSONArray actorSpecs = buildActorSpecsFromSeedTitles(castSeedTitles, format);
        return executeScatterGatherSpecs(actorSpecs);
    }

    private static JSONArray buildActorSpecsFromSeedTitles(JSONArray castSeedTitles, String format) {
        JSONArray specs = new JSONArray();
        for (int i = 0; i < castSeedTitles.length() && i < 2; i++) {
            List<String> actors = fetchLeadCastForTitle(castSeedTitles.optString(i, "").trim());
            appendActorSearchSpecs(actors, format, specs);
        }
        return specs;
    }

    private static void appendActorSearchSpecs(List<String> actors, String format, JSONArray specs) {
        for (int i = 0; i < actors.size() && specs.length() < 6; i++) {
            try {
                JSONObject s = new JSONObject().put("searchQuery", actors.get(i)).put("includeSimilar", false);
                if ("MOVIE".equalsIgnoreCase(format) || "SHOW".equalsIgnoreCase(format)) s.put("objectTypes", new JSONArray().put(format.toUpperCase(Locale.US)));
                specs.put(s);
            } catch (Exception ignored) {}
        }
    }

    public static List<String> fetchLeadCastForTitle(String seedTitle) {
        if (seedTitle == null || seedTitle.isEmpty()) return new ArrayList<>();
        try {
            String lang = RegionProviderRegistry.getLanguageCode(null);
            JSONObject vars = new JSONObject().put("searchQuery", seedTitle).put("country", RegionProviderRegistry.getCountryCode(null)).put("language", lang);
            return extractCastFromMatchingNode(extractEdges(postGraphQl(SIMILAR_QUERY, vars)), AuraDb.normalizeTitleKey(seedTitle), lang);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static List<String> extractCastFromMatchingNode(JSONArray edges, String normSeed, String lang) {
        if (edges == null) return new ArrayList<>();
        for (int i = 0; i < edges.length() && i < 4; i++) {
            JSONObject p = parseNode(edges.optJSONObject(i).optJSONObject("node"), lang);
            if (isCloseSeedMatch(AuraDb.normalizeTitleKey(p.optString("title", "")), p.optString("slug_key", ""), normSeed)) {
                return splitFirstActors(p.optString("cast", ""), 4);
            }
        }
        return new ArrayList<>();
    }

    private static List<String> splitFirstActors(String castCsv, int max) {
        List<String> out = new ArrayList<>();
        if (castCsv == null || castCsv.isEmpty()) return out;
        for (String part : castCsv.split(",")) {
            if (!part.trim().isEmpty() && out.size() < max) out.add(part.trim());
        }
        return out;
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

    public static JSONArray executeScatterGatherSpecs(JSONArray specs) {
        if (specs == null || specs.length() == 0) return new JSONArray();
        final List<JSONObject> gathered = java.util.Collections.synchronizedList(new ArrayList<>());
        final Set<String> seenKeys = java.util.Collections.synchronizedSet(new HashSet<>());
        runScatterGatherPool(specs, seenKeys, gathered);
        JSONArray out = new JSONArray();
        for (JSONObject o : gathered) out.put(o);
        return out;
    }

    private static void runScatterGatherPool(JSONArray specs, Set<String> seenKeys, List<JSONObject> gathered) {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(Math.min(6, specs.length()));
        for (int i = 0; i < specs.length() && i < 6; i++) {
            final JSONObject spec = specs.optJSONObject(i);
            if (spec != null) pool.submit(() -> collectSpecCandidates(spec, seenKeys, gathered));
        }
        pool.shutdown();
        try { pool.awaitTermination(8, TimeUnit.SECONDS); } catch (Exception ignored) {}
    }

    private static void collectSpecCandidates(JSONObject spec, Set<String> seenKeys, List<JSONObject> gathered) {
        JSONArray hits = executeDynamicQuerySpec(spec);
        for (int i = 0; i < hits.length(); i++) {
            JSONObject item = hits.optJSONObject(i);
            String key = item != null ? AuraDb.normalizeTitleKey(item.optString("title", "")) : "";
            if (!key.isEmpty() && seenKeys.add(key)) gathered.add(item);
        }
    }

    public static JSONArray executeDynamicQuerySpec(JSONObject spec) {
        JSONArray out = new JSONArray();
        if (spec == null || isUnfilteredEmptySpec(spec)) return out;
        try {
            String lang = RegionProviderRegistry.getLanguageCode(null);
            JSONObject vars = buildDynamicSpecVars(spec, RegionProviderRegistry.getCountryCode(null), lang);
            JSONArray edges = extractEdges(postGraphQl(DYNAMIC_GRAPH_QUERY, vars));
            extractDynamicSpecNodes(edges, spec, lang, out);
        } catch (Exception ignored) {}
        return out;
    }

    private static boolean isUnfilteredEmptySpec(JSONObject spec) {
        String sq = spec.optString("searchQuery", "").trim();
        return sq.isEmpty() && extractSpecGenres(spec.optJSONArray("genres")).length() == 0;
    }

    private static JSONObject buildDynamicSpecVars(JSONObject spec, String country, String lang) throws Exception {
        boolean hasSq = !spec.optString("searchQuery", "").trim().isEmpty();
        boolean incSim = spec.optBoolean("includeSimilar", hasSq);
        int first = hasSq ? (incSim ? 4 : 25) : 18;
        return new JSONObject().put("country", country).put("language", lang)
            .put("first", first).put("includeSimilar", incSim)
            .put("filter", buildDynamicTitleFilter(spec));
    }

    private static JSONObject buildDynamicTitleFilter(JSONObject spec) throws Exception {
        JSONObject filter = new JSONObject();
        String sq = spec.optString("searchQuery", "").trim();
        if (!sq.isEmpty()) {
            filter.put("searchQuery", sq);
            JSONArray objTypes = extractSpecObjectTypes(spec.optJSONArray("objectTypes"));
            if (objTypes.length() > 0) filter.put("objectTypes", objTypes);
            return filter;
        }
        return populatePureDiscoveryFilter(spec, filter);
    }

    private static JSONObject populatePureDiscoveryFilter(JSONObject spec, JSONObject filter) throws Exception {
        filter.put("packages", extractSpecPackages(spec.optJSONArray("packages")));
        filter.put("monetizationTypes", new JSONArray().put("FLATRATE").put("FREE").put("ADS"));
        int minYr = Math.max(1995, spec.optInt("minYear", getDefaultMinModernYear() - 2));
        int maxYr = Math.min(getCurrentYear(), spec.optInt("maxYear", getCurrentYear()));
        filter.put("releaseYear", new JSONObject().put("min", Math.min(minYr, maxYr)).put("max", maxYr));
        applyOptionalSpecFilterFields(spec, filter);
        return filter;
    }

    private static void applyOptionalSpecFilterFields(JSONObject spec, JSONObject filter) throws Exception {
        String sq = spec.optString("searchQuery", "").trim();
        if (!sq.isEmpty()) filter.put("searchQuery", sq);
        JSONArray objTypes = extractSpecObjectTypes(spec.optJSONArray("objectTypes"));
        if (objTypes.length() > 0) filter.put("objectTypes", objTypes);
        JSONArray genres = extractSpecGenres(spec.optJSONArray("genres"));
        if (genres.length() > 0 && sq.isEmpty()) filter.put("genres", genres);
        JSONArray countries = extractSpecCountries(spec.optJSONArray("productionCountries"));
        if (countries.length() > 0 && sq.isEmpty()) filter.put("productionCountries", countries);
    }

    private static JSONArray extractSpecPackages(JSONArray raw) {
        if (raw == null || raw.length() == 0) return getDefaultPackagesJson();
        JSONArray out = new JSONArray();
        for (int i = 0; i < raw.length(); i++) {
            String p = raw.optString(i, "").trim().toLowerCase(Locale.US);
            if (RegionProviderRegistry.isAllowedProvider(p)) out.put(p);
        }
        return out.length() > 0 ? out : getDefaultPackagesJson();
    }

    private static JSONArray extractSpecObjectTypes(JSONArray raw) {
        JSONArray out = new JSONArray();
        if (raw == null) return out;
        for (int i = 0; i < raw.length(); i++) {
            String t = raw.optString(i, "").trim().toUpperCase(Locale.US);
            if ("MOVIE".equals(t) || "SHOW".equals(t)) out.put(t);
        }
        return out;
    }

    private static JSONArray extractSpecGenres(JSONArray raw) {
        JSONArray out = new JSONArray();
        if (raw == null) return out;
        for (int i = 0; i < raw.length(); i++) {
            String code = normalizeSpecGenreCode(raw.optString(i, ""));
            if (!code.isEmpty()) out.put(code);
        }
        return out;
    }

    private static String normalizeSpecGenreCode(String rawGenre) {
        String g = rawGenre == null ? "" : rawGenre.trim().toLowerCase(Locale.US);
        if (isValidGenreCode(g)) return g;
        List<String> inferred = inferGenreCodesFromText(g);
        return !inferred.isEmpty() ? inferred.get(0) : "";
    }

    private static JSONArray extractSpecCountries(JSONArray raw) {
        JSONArray out = new JSONArray();
        if (raw == null) return out;
        for (int i = 0; i < raw.length(); i++) {
            String c = raw.optString(i, "").trim().toUpperCase(Locale.US);
            if (c.length() == 2) out.put(c);
        }
        return out;
    }

    private static void extractDynamicSpecNodes(JSONArray edges, JSONObject spec, String lang, JSONArray out) {
        if (edges == null) return;
        String sq = spec.optString("searchQuery", "").trim();
        int specMin = spec.optInt("minYear", 2008);
        int minYr = (!sq.isEmpty() && specMin < getCurrentYear() - 1) ? 1950 : Math.max(1950, specMin);
        String seedAnchor = !sq.isEmpty() ? spec.optString("seedAnchor", sq).trim() : "";
        String specGenres = joinGenreArray(extractSpecGenres(spec.optJSONArray("genres")));
        JSONArray objTypes = extractSpecObjectTypes(spec.optJSONArray("objectTypes"));
        for (int i = 0; i < edges.length(); i++) {
            JSONObject node = edges.optJSONObject(i) != null ? edges.optJSONObject(i).optJSONObject("node") : null;
            if (node != null) collectNodeAndSimilar(node, i, sq, specGenres, minYr, seedAnchor, objTypes, lang, out);
        }
    }

    private static String joinGenreArray(JSONArray arr) {
        if (arr == null || arr.length() == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            if (i > 0) sb.append(",");
            sb.append(arr.optString(i, ""));
        }
        return sb.toString();
    }

    private static void collectNodeAndSimilar(
            JSONObject node, int edgeIdx, String sq, String specGenres,
            int minYr, String seedAnchor, JSONArray objTypes, String lang, JSONArray out) {
        JSONObject primary = parseNode(node, lang);
        boolean castMatch = !sq.isEmpty() && primary.optString("cast", "").toLowerCase(Locale.US).contains(sq.toLowerCase(Locale.US));
        if (!sq.isEmpty() && !castMatch && !matchesQueriedSeedNode(primary, edgeIdx, sq, specGenres)) return;
        boolean directMatch = castMatch || isDirectTitleSeedMatch(primary, sq);
        String refGenres = !specGenres.isEmpty() ? specGenres : primary.optString("genre_codes", "");
        if (isCompatibleDynamicCandidate(primary, directMatch ? "" : refGenres, objTypes, minYr)) {
            tagDynamicHit(primary, seedAnchor, false);
            out.put(primary);
        }
        if (!castMatch) collectSimilarNodesForSpec(node.optJSONArray("similarTitles"), primary, refGenres, minYr, seedAnchor, objTypes, lang, out);
    }

    private static boolean isDirectTitleSeedMatch(JSONObject primary, String sq) {
        if (sq == null || sq.isEmpty()) return false;
        String key = AuraDb.normalizeTitleKey(primary.optString("title", ""));
        return isCloseSeedMatch(key, primary.optString("slug_key", ""), AuraDb.normalizeTitleKey(sq));
    }

    private static boolean matchesQueriedSeedNode(JSONObject primary, int edgeIdx, String sq, String specGenres) {
        if (isDirectTitleSeedMatch(primary, sq)) return true;
        return edgeIdx == 0 && (specGenres.isEmpty() || CandidateValidator.computeGenreJaccard(primary.optString("genre_codes", ""), specGenres) >= 0.15);
    }

    private static void collectSimilarNodesForSpec(
            JSONArray sim, JSONObject primary, String refGenres, int minYr, String seedAnchor,
            JSONArray objTypes, String lang, JSONArray out) {
        if (sim == null) return;
        String anchor = !seedAnchor.isEmpty() ? seedAnchor : primary.optString("title", "");
        for (int j = 0; j < sim.length(); j++) {
            JSONObject sNode = parseNode(sim.optJSONObject(j), lang);
            if (isCompatibleDynamicCandidate(sNode, refGenres, objTypes, Math.min(minYr, 1995))) {
                tagDynamicHit(sNode, anchor, true);
                out.put(sNode);
            }
        }
    }

    private static boolean isCompatibleDynamicCandidate(
            JSONObject cand, String refGenres, JSONArray objTypes, int minYr) {
        if (!matchesSpecObjectTypes(cand, objTypes)) return false;
        String candGenres = cand.optString("genre_codes", "");
        if (!CandidateValidator.isCompatibleFormatForSeed(candGenres, refGenres)) return false;
        if (!refGenres.isEmpty() && CandidateValidator.computeGenreJaccard(candGenres, refGenres) < 0.15) return false;
        double minImdb = refGenres.isEmpty() ? 5.0 : ((refGenres.contains("cmy") || refGenres.contains("hrr") || candGenres.contains("cmy")) ? 5.8 : 6.4);
        return CandidateValidator.isValidCoWatchCandidate(cand, minYr, minImdb);
    }

    private static boolean matchesSpecObjectTypes(JSONObject parsed, JSONArray objTypes) {
        if (objTypes == null || objTypes.length() == 0) return true;
        String want = "Movie".equalsIgnoreCase(parsed.optString("type", "")) ? "MOVIE" : "SHOW";
        for (int i = 0; i < objTypes.length(); i++) {
            if (want.equalsIgnoreCase(objTypes.optString(i, ""))) return true;
        }
        return false;
    }

    private static void tagDynamicHit(JSONObject item, String seedAnchor, boolean isSim) {
        try {
            if (!seedAnchor.isEmpty()) item.put("seed_anchor", seedAnchor);
            item.put("is_direct_sim", isSim);
            item.put("source_channel", isSim ? "co_watch" : "llm_planned");
        } catch (Exception ignored) {}
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
            JSONArray structured = fetchTypedGenreCandidates(q, gList, pkgs, minYear);
            if (structured.length() > 0) return structured;
        }
        return executeDirectKeywordSearch(q, minYear);
    }

    private static JSONArray fetchTypedGenreCandidates(String q, List<String> gList, List<String> pkgs, int minYear) {
        if (!VoiceConcierge.wantsMoviesOnly(q) && !VoiceConcierge.wantsSeriesOnly(q)) {
            return fetchStructuredGenreCandidates(gList, pkgs, minYear, getCurrentYear());
        }
        try {
            return executeDynamicQuerySpec(buildTypedGenreSpec(q, gList, pkgs, minYear));
        } catch (Exception e) {
            return fetchStructuredGenreCandidates(gList, pkgs, minYear, getCurrentYear());
        }
    }

    private static JSONObject buildTypedGenreSpec(String q, List<String> gList, List<String> pkgs, int minYear) throws Exception {
        JSONObject spec = new JSONObject().put("genres", new JSONArray(gList)).put("minYear", minYear).put("maxYear", getCurrentYear());
        if (!pkgs.isEmpty()) spec.put("packages", new JSONArray(pkgs));
        return spec.put("objectTypes", new JSONArray().put(VoiceConcierge.wantsMoviesOnly(q) ? "MOVIE" : "SHOW"));
    }

    private static JSONArray executeDirectKeywordSearch(String query, int minYear) {
        JSONArray out = new JSONArray();
        try {
            String lang = RegionProviderRegistry.getLanguageCode(null);
            JSONObject vars = buildKeywordDiscoveryVars(query, RegionProviderRegistry.getCountryCode(null), lang);
            collectValidCandidates(extractEdges(postGraphQl(STRUCTURED_DISCOVERY_QUERY, vars)), out, minYear, lang, false);
        } catch (Exception ignored) {}
        return out;
    }

    private static JSONObject buildKeywordDiscoveryVars(String query, String country, String lang) throws Exception {
        JSONObject filter = new JSONObject()
            .put("searchQuery", query)
            .put("packages", getDefaultPackagesJson())
            .put("monetizationTypes", new JSONArray().put("FLATRATE").put("FREE").put("ADS"));
        return new JSONObject().put("country", country).put("language", lang).put("filter", filter);
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
        if (seedMeta != null && candidates.size() < 6) expandSparseCoWatchGraph(cleanSeed, seedMeta, seedGenreCodes, minYear, seenKeys, candidates);
        return sortAndPackSimilarCandidates(candidates, seedMeta, seedGenreCodes);
    }

    private static JSONObject extractDirectCoWatchNeighbors(
            String cleanSeed, String normSeed, int minYear, Set<String> seenKeys,
            List<String> seedGenreCodes, List<JSONObject> candidates) {
        try {
            String lang = RegionProviderRegistry.getLanguageCode(null);
            JSONObject vars = new JSONObject().put("searchQuery", cleanSeed)
                .put("country", RegionProviderRegistry.getCountryCode(null)).put("language", lang);
            return iterateSeedEdges(extractEdges(postGraphQl(SIMILAR_QUERY, vars)), normSeed, minYear, lang, seenKeys, seedGenreCodes, candidates);
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONObject iterateSeedEdges(
            JSONArray edges, String normSeed, int minYear, String lang,
            Set<String> seenKeys, List<String> seedGenreCodes, List<JSONObject> candidates) throws Exception {
        if (edges == null) return null;
        JSONObject seedMeta = null;
        for (int eIdx = 0; eIdx < Math.min(4, edges.length()); eIdx++) {
            JSONObject node = edges.getJSONObject(eIdx).optJSONObject("node");
            seedMeta = processSeedEdgeNode(node, normSeed, minYear, lang, seedMeta, seenKeys, seedGenreCodes, candidates);
        }
        return seedMeta;
    }

    private static JSONObject processSeedEdgeNode(
            JSONObject node, String normSeed, int minYear, String lang, JSONObject currentSeedMeta,
            Set<String> seenKeys, List<String> seedGenreCodes, List<JSONObject> candidates) throws Exception {
        if (node == null) return currentSeedMeta;
        JSONObject nodeMeta = parseNode(node, lang);
        String resolvedKey = AuraDb.normalizeTitleKey(nodeMeta.optString("title", ""));
        String slugKey = nodeMeta.optString("slug_key", "");
        if (!isCloseSeedMatch(resolvedKey, slugKey, normSeed)) return currentSeedMeta;
        if (currentSeedMeta == null || resolvedKey.equals(normSeed)) recordSeenSeedKeys(resolvedKey, slugKey, seenKeys);
        String coreGenres = collectCoreSeedGenres(nodeMeta.optString("genre_codes", ""), seedGenreCodes);
        extractSimilarFromNode(node.optJSONArray("similarTitles"), coreGenres, minYear, lang, seenKeys, candidates);
        return (currentSeedMeta == null || slugKey.equals(normSeed)) ? nodeMeta : currentSeedMeta;
    }

    private static void recordSeenSeedKeys(String resolvedKey, String slugKey, Set<String> seenKeys) {
        if (!resolvedKey.isEmpty()) seenKeys.add(resolvedKey);
        if (!slugKey.isEmpty()) seenKeys.add(slugKey);
    }

    private static boolean isCloseSeedMatch(String resolvedKey, String slugKey, String normSeed) {
        if (normSeed.isEmpty()) return false;
        if (resolvedKey.equals(normSeed) || slugKey.equals(normSeed)) return true;
        if (normSeed.length() >= 5 && (resolvedKey.startsWith(normSeed) || slugKey.startsWith(normSeed))) return true;
        int diff = Math.abs(resolvedKey.length() - normSeed.length());
        return diff <= 6 && (resolvedKey.contains(normSeed) || normSeed.contains(resolvedKey));
    }

    private static String collectCoreSeedGenres(String rawCodes, List<String> seedGenreCodes) {
        List<String> specific = new ArrayList<>();
        if (rawCodes != null && !rawCodes.isEmpty()) {
            for (String c : rawCodes.split(",")) addCoreGenreCode(c.trim(), seedGenreCodes, specific);
        }
        return !specific.isEmpty() ? String.join(",", specific) : String.join(",", seedGenreCodes);
    }

    private static void addCoreGenreCode(String t, List<String> seedGenreCodes, List<String> specific) {
        if (t.isEmpty() || "fml".equals(t) || "msc".equals(t)) return;
        if (!seedGenreCodes.contains(t)) seedGenreCodes.add(t);
        if (!"drm".equals(t)) specific.add(t);
    }

    private static void extractSimilarFromNode(
            JSONArray sim, String seedCoreGenres, int minYear, String lang,
            Set<String> seenKeys, List<JSONObject> candidates) throws Exception {
        if (sim == null) return;
        for (int i = 0; i < sim.length(); i++) {
            JSONObject parsed = parseNode(sim.optJSONObject(i), lang);
            if (isEligibleDirectNeighbor(parsed, seedCoreGenres, minYear)) {
                tryAddNeighborCandidate(parsed, seenKeys, candidates);
            }
        }
    }

    private static boolean isEligibleDirectNeighbor(JSONObject parsed, String seedCoreGenres, int minYear) {
        double minImdb = (seedCoreGenres.contains("cmy") || seedCoreGenres.contains("hrr")) ? 5.8 : 6.4;
        if (!CandidateValidator.isValidCoWatchCandidate(parsed, Math.min(minYear, 1995), minImdb)) return false;
        String candGenres = parsed.optString("genre_codes", "");
        if (!CandidateValidator.isCompatibleFormatForSeed(candGenres, seedCoreGenres)) return false;
        return CandidateValidator.computeGenreJaccard(candGenres, seedCoreGenres) >= 0.15;
    }

    private static void tryAddNeighborCandidate(JSONObject parsed, Set<String> seenKeys, List<JSONObject> candidates) {
        String k = AuraDb.normalizeTitleKey(parsed.optString("title"));
        if (!k.isEmpty() && seenKeys.add(k)) {
            try { parsed.put("is_direct_sim", true); } catch (Exception ignored) {}
            candidates.add(parsed);
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
        appendStructuredExpansion(structured, seedMeta, seedCore, seenKeys, candidates);
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
            JSONArray structured, JSONObject seedMeta, String seedCoreGenres,
            Set<String> seenKeys, List<JSONObject> candidates) {
        for (int i = 0; i < structured.length(); i++) {
            JSONObject p = structured.optJSONObject(i);
            if (p != null && matchesStructuredExpansion(p, seedMeta, seedCoreGenres)) {
                tryAddNeighborCandidate(p, seenKeys, candidates);
            }
        }
    }

    private static boolean matchesStructuredExpansion(JSONObject p, JSONObject seedMeta, String seedCoreGenres) {
        String seedType = seedMeta != null ? seedMeta.optString("type", "") : "";
        if (!seedType.isEmpty() && !seedType.equalsIgnoreCase(p.optString("type", ""))) return false;
        String candGenres = p.optString("genre_codes", "");
        if (!CandidateValidator.isCompatibleFormatForSeed(candGenres, seedCoreGenres)) return false;
        return CandidateValidator.computeGenreJaccard(candGenres, seedCoreGenres) >= 0.28;
    }

    private static JSONArray sortAndPackSimilarCandidates(
            List<JSONObject> candidates, JSONObject seedMeta, List<String> seedGenreCodes) {
        int curYear = getCurrentYear();
        candidates.sort((a, b) -> Double.compare(
            HybridScorer.computeItemToItemScore(b, seedMeta, curYear),
            HybridScorer.computeItemToItemScore(a, seedMeta, curYear)));
        String seedCtry = seedMeta != null ? seedMeta.optString("country", "") : "";
        String seedGs = String.join(",", seedGenreCodes);
        return packTopSimilarWithSeedInfo(candidates, seedCtry, seedGs);
    }

    private static JSONArray packTopSimilarWithSeedInfo(List<JSONObject> candidates, String seedCtry, String seedGs) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < candidates.size() && i < 16; i++) {
            JSONObject c = candidates.get(i);
            try { c.put("seed_country", seedCtry).put("seed_genres", seedGs); } catch (Exception ignored) {}
            out.put(c);
        }
        return out;
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
        if (node == null || node.optJSONObject("content") == null) return res;
        try {
            populateNodeMetadata(node, node.optJSONObject("content"), lang, res);
        } catch (Exception ignored) {}
        return res;
    }

    private static void populateNodeMetadata(JSONObject node, JSONObject content, String lang, JSONObject res) throws Exception {
        populateBasicContent(content, node.optString("objectType", "SHOW"), lang, res);
        populateScoring(content.optJSONObject("scoring"), res);
        populateArtwork(content, res);
        JSONArray gArr = content.optJSONArray("genres");
        res.put("genres", mapGenres(gArr));
        res.put("genre_codes", extractGenreCodesCsv(gArr));
        res.put("cast", extractTopCreditsCsv(content.optJSONArray("credits")));
        extractBestOffer(node.optJSONArray("offers"), res);
    }

    private static String extractTopCreditsCsv(JSONArray credits) {
        if (credits == null || credits.length() == 0) return "";
        List<String> names = new ArrayList<>();
        for (int i = 0; i < credits.length() && names.size() < 10; i++) {
            appendCreditIfActorOrDirector(credits.optJSONObject(i), names);
        }
        return String.join(", ", names);
    }

    private static void appendCreditIfActorOrDirector(JSONObject c, List<String> names) {
        if (c == null) return;
        String role = c.optString("role", "").toUpperCase(Locale.US);
        if (!role.isEmpty() && !"ACTOR".equals(role) && !"DIRECTOR".equals(role)) return;
        String n = c.optString("name", "").trim();
        if (!n.isEmpty() && !names.contains(n)) names.add(n);
    }

    private static void populateBasicContent(JSONObject content, String objType, String lang, JSONObject res) throws Exception {
        String country = extractFirstCountry(content.optJSONArray("productionCountries"));
        res.put("country", country);
        populateResolvedTitles(content, country, lang, res);
        res.put("year", content.optInt("originalReleaseYear", getCurrentYear()));
        res.put("type", "MOVIE".equalsIgnoreCase(objType) ? "Movie" : "Series");
        int rt = content.optInt("runtime", "MOVIE".equalsIgnoreCase(objType) ? 115 : 48);
        res.put("runtime_min", rt > 0 ? rt : 48);
        res.put("synopsis", content.optString("shortDescription"));
    }

    private static String extractFirstCountry(JSONArray countries) {
        return (countries != null && countries.length() > 0) ? countries.optString(0, "") : "";
    }

    private static void populateResolvedTitles(JSONObject content, String country, String lang, JSONObject res) throws Exception {
        String rawTitle = content.optString("title", "");
        String origTitle = content.optString("originalTitle", "");
        String fullPath = content.optString("fullPath", "");
        String slugTitle = RegionProviderRegistry.humanizeFullPathSlug(fullPath);
        res.put("slug_key", AuraDb.normalizeTitleKey(slugTitle));
        res.put("title", RegionProviderRegistry.resolveTitleFromSlugIfRomanized(rawTitle, origTitle, fullPath, country, lang));
    }

    private static void populateScoring(JSONObject scoring, JSONObject res) throws Exception {
        double imdb = scoring != null ? scoring.optDouble("imdbScore", 0.0) : 0.0;
        int votes = scoring != null ? scoring.optInt("imdbVotes", 0) : 0;
        double pop = scoring != null ? scoring.optDouble("tmdbPopularity", 0.0) : 0.0;
        if (Double.isNaN(imdb)) imdb = 0.0;
        if (Double.isNaN(pop)) pop = 0.0;
        res.put("imdb_raw", imdb).put("imdb_score", imdb).put("imdb_votes", votes);
        res.put("tmdb_popularity", pop).put("rating", imdb > 0 ? String.format(Locale.US, "\u2605 %.1f", imdb) : "");
    }

    private static void populateArtwork(JSONObject content, JSONObject res) throws Exception {
        String posterPath = content.optString("posterUrl");
        if (!posterPath.isEmpty()) {
            res.put("poster_url", "https://images.justwatch.com" + posterPath.replace("{profile}", "s718").replace("{format}", "jpg"));
        }
        String backdropUrl = resolveFirstBackdropUrl(content.optJSONArray("backdrops"));
        if (!backdropUrl.isEmpty()) res.put("backdrop_url", backdropUrl);
    }

    private static String resolveFirstBackdropUrl(JSONArray backdrops) {
        if (backdrops == null) return "";
        for (int b = 0; b < backdrops.length(); b++) {
            JSONObject bObj = backdrops.optJSONObject(b);
            String bPath = bObj != null ? bObj.optString("backdropUrl", "") : "";
            if (!bPath.isEmpty()) {
                return "https://images.justwatch.com" + bPath.replace("{profile}", "s1920").replace("{format}", "jpg");
            }
        }
        return "";
    }

    private static void extractBestOffer(JSONArray offers, JSONObject res) throws Exception {
        JSONObject chosen = selectAllowedOffer(offers);
        if (chosen == null) {
            res.put("has_flatrate_offer", false);
            return;
        }
        applyChosenOffer(chosen, chosen.optJSONObject("package"), res);
    }

    private static void applyChosenOffer(JSONObject chosen, JSONObject pkg, JSONObject res) throws Exception {
        String shortName = pkg != null ? pkg.optString("shortName", "").toLowerCase(Locale.US) : "";
        String displayApp = RegionProviderRegistry.normalizeProviderName(shortName, pkg != null ? pkg.optString("clearName", "") : "");
        String deeplink = chosen.optString("deeplinkURL");
        res.put("has_flatrate_offer", true).put("pkg_code", shortName);
        res.put("app", displayApp).put("badge", displayApp);
        res.put("launch_uri", !deeplink.isEmpty() ? deeplink : chosen.optString("standardWebURL"));
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
            appendDelimitedIfNotEmpty(sb, g != null ? g.optString("shortName", "").trim() : "", ",");
        }
        return sb.toString();
    }

    private static String mapGenres(JSONArray gArr) {
        if (gArr == null || gArr.length() == 0) return "Drama";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < gArr.length() && i < 3; i++) {
            JSONObject g = gArr.optJSONObject(i);
            appendDelimitedIfNotEmpty(sb, g != null ? genreLabel(g.optString("shortName")) : "", ", ");
        }
        return sb.length() > 0 ? sb.toString() : "Drama";
    }

    private static void appendDelimitedIfNotEmpty(StringBuilder sb, String val, String sep) {
        if (val != null && !val.isEmpty()) {
            if (sb.length() > 0) sb.append(sep);
            sb.append(val);
        }
    }

    private static final java.util.Map<String, String> GENRE_LABELS = createGenreLabelMap();

    private static java.util.Map<String, String> createGenreLabelMap() {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        putGenrePairs(m, "scf", "Sci-Fi", "trl", "Thriller", "drm", "Drama", "cmy", "Comedy", "act", "Action", "hrr", "Horror");
        putGenrePairs(m, "hor", "Horror", "crm", "Crime", "rma", "Romance", "fnt", "Fantasy", "ani", "Animation", "doc", "Documentary");
        putGenrePairs(m, "spt", "Sport", "hst", "History", "war", "War", "wsn", "Western", "fml", "Family");
        return java.util.Collections.unmodifiableMap(m);
    }

    private static void putGenrePairs(java.util.Map<String, String> m, String... kv) {
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
    }

    public static String genreLabel(String code) {
        return code != null ? GENRE_LABELS.getOrDefault(code, "") : "";
    }

    public static boolean isValidGenreCode(String code) {
        return !genreLabel(code).isEmpty();
    }

    public static List<String> inferGenreCodesFromText(String text) {
        List<String> codes = new ArrayList<>();
        if (text == null || text.isEmpty()) return codes;
        String lower = text.toLowerCase(Locale.US);
        inferPrimaryGenres(lower, codes);
        inferSecondaryGenres(lower, codes);
        return codes;
    }

    private static void inferPrimaryGenres(String lower, List<String> codes) {
        if (containsAny(lower, "sci-fi", "scifi", "science", "cyber", "space", "dystopia", "time travel", "time loop", "multiverse", "alien", "future")) codes.add("scf");
        if (containsAny(lower, "thriller", "suspense", "mystery", "dark", "gritt", "mind", "psych")) codes.add("trl");
        if (containsAny(lower, "crime", "heist", "mafia", "gang", "detective", "murder", "noir")) codes.add("crm");
        if (containsAny(lower, "action", "octane", "combat", "spy", "espionage", "adventure")) codes.add("act");
        if (containsAny(lower, "horror", "scary", "supernatural", "zombie", "ghost")) codes.add("hrr");
    }

    private static void inferSecondaryGenres(String lower, List<String> codes) {
        if (containsAny(lower, "comedy", "funny", "sitcom", "laugh", "light")) codes.add("cmy");
        if (containsAny(lower, "romance", "romantic", "love")) codes.add("rma");
        if (containsAny(lower, "fantasy", "magic")) codes.add("fnt");
        if (containsAny(lower, "anime", "animation", "animated")) codes.add("ani");
        if (containsAny(lower, "drama", "emotional", "acclaimed", "prestige")) codes.add("drm");
    }

    public static List<String> inferPackageCodesFromText(String text) {
        return RegionProviderRegistry.inferPackageCodesFromText(text);
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
        HttpURLConnection conn = openGraphQlConnection(payload);
        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return new JSONObject(sb.toString());
        }
    }

    private static HttpURLConnection openGraphQlConnection(JSONObject payload) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(GRAPHQL_URL).openConnection();
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3500);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        try (OutputStream os = conn.getOutputStream()) { os.write(payload.toString().getBytes(StandardCharsets.UTF_8)); }
        return conn;
    }
}
