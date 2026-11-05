package com.aura.tvprobe;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class JustWatchGraph {
    private static final String GRAPHQL_URL = "https://apis.justwatch.com/graphql";

    private static final String SEARCH_QUERY =
        "query GetTitle($searchQuery: String!, $country: Country!, $language: Language!) {" +
        "  popularTitles(country: $country, filter: { searchQuery: $searchQuery }, first: 1) {" +
        "    edges { node {" +
        "      objectType" +
        "      content(country: $country, language: $language) {" +
        "        title originalReleaseYear runtime shortDescription" +
        "        genres { shortName } scoring { imdbScore } posterUrl" +
        "      }" +
        "      offers(country: $country, platform: ANDROID_TV) {" +
        "        monetizationType standardWebURL deeplinkURL(platform: ANDROID_TV)" +
        "        package { clearName shortName }" +
        "      }" +
        "    }}" +
        "  }" +
        "}";

    private static final String SIMILAR_QUERY =
        "query GetSimilar($searchQuery: String!, $country: Country!, $language: Language!) {" +
        "  popularTitles(country: $country, filter: { searchQuery: $searchQuery }, first: 1) {" +
        "    edges { node { ... on MovieOrShow {" +
        "      similarTitles(country: $country) {" +
        "        objectType" +
        "        content(country: $country, language: $language) {" +
        "          title originalReleaseYear runtime shortDescription" +
        "          genres { shortName } scoring { imdbScore } posterUrl" +
        "        }" +
        "        offers(country: $country, platform: ANDROID_TV) {" +
        "          monetizationType standardWebURL deeplinkURL(platform: ANDROID_TV)" +
        "          package { clearName shortName }" +
        "        }" +
        "      }" +
        "    }}}" +
        "  }" +
        "}";

    public static JSONObject lookupTitleInIndia(String title) {
        try {
            JSONObject vars = new JSONObject()
                .put("searchQuery", title)
                .put("country", "IN")
                .put("language", "en");
            JSONObject resp = postGraphQl(SEARCH_QUERY, vars);
            JSONArray edges = resp.optJSONObject("data")
                .optJSONObject("popularTitles")
                .optJSONArray("edges");
            if (edges == null || edges.length() == 0) return new JSONObject();
            JSONObject node = edges.getJSONObject(0).optJSONObject("node");
            return parseNode(node);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static JSONArray fetchCollaborativeSimilar(String seedTitle) {
        JSONArray out = new JSONArray();
        try {
            JSONObject vars = new JSONObject()
                .put("searchQuery", seedTitle)
                .put("country", "IN")
                .put("language", "en");
            JSONObject resp = postGraphQl(SIMILAR_QUERY, vars);
            JSONArray edges = resp.optJSONObject("data")
                .optJSONObject("popularTitles")
                .optJSONArray("edges");
            if (edges == null || edges.length() == 0) return out;
            JSONArray sim = edges.getJSONObject(0).optJSONObject("node").optJSONArray("similarTitles");
            if (sim == null) return out;
            for (int i = 0; i < sim.length(); i++) {
                JSONObject parsed = parseNode(sim.optJSONObject(i));
                if (!parsed.optString("title").isEmpty() && parsed.optDouble("imdb_raw", 7.5) >= 7.2) {
                    out.put(parsed);
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static JSONObject parseNode(JSONObject node) {
        JSONObject res = new JSONObject();
        if (node == null) return res;
        try {
            String objType = node.optString("objectType", "SHOW");
            JSONObject content = node.optJSONObject("content");
            if (content == null) return res;
            res.put("title", content.optString("title"));
            res.put("year", content.optInt("originalReleaseYear", 2024));
            res.put("type", "MOVIE".equalsIgnoreCase(objType) ? "Movie" : "Series");
            int rt = content.optInt("runtime", "MOVIE".equalsIgnoreCase(objType) ? 115 : 48);
            res.put("runtime_min", rt > 0 ? rt : 48);
            res.put("synopsis", content.optString("shortDescription"));

            JSONObject scoring = content.optJSONObject("scoring");
            double imdb = scoring != null ? scoring.optDouble("imdbScore", 0.0) : 0.0;
            if (imdb > 0) {
                res.put("imdb_raw", imdb);
                res.put("rating", String.format("★ %.1f", imdb));
            } else {
                res.put("rating", "★ 8.1");
            }

            String posterPath = content.optString("posterUrl");
            if (!posterPath.isEmpty()) {
                String fullPoster = "https://images.justwatch.com" +
                    posterPath.replace("{profile}", "s332").replace("{format}", "jpg");
                res.put("poster_url", fullPoster);
            }

            res.put("genres", mapGenres(content.optJSONArray("genres"), objType));
            extractBestOffer(node.optJSONArray("offers"), res);
        } catch (Exception ignored) {}
        return res;
    }

    private static void extractBestOffer(JSONArray offers, JSONObject res) throws Exception {
        if (offers == null || offers.length() == 0) return;
        JSONObject chosen = offers.optJSONObject(0);
        for (int i = 0; i < offers.length(); i++) {
            JSONObject off = offers.optJSONObject(i);
            if (off != null && "FLATRATE".equalsIgnoreCase(off.optString("monetizationType"))) {
                chosen = off;
                break;
            }
        }
        if (chosen == null) return;
        JSONObject pkg = chosen.optJSONObject("package");
        String clearName = pkg != null ? pkg.optString("clearName", "Streaming") : "Streaming";
        if (clearName.contains("JioHotstar") || clearName.contains("Hotstar")) clearName = "Disney+ Hotstar";
        if (clearName.contains("Amazon Prime")) clearName = "Prime Video";
        if (clearName.equals("Apple TV")) clearName = "Apple TV+";

        String deeplink = chosen.optString("deeplinkURL");
        String webUrl = chosen.optString("standardWebURL");
        res.put("app", clearName);
        res.put("badge", clearName);
        res.put("launch_uri", !deeplink.isEmpty() ? deeplink : webUrl);
        res.put("launch_pkg", AuraDb.resolveAppPackage(clearName));
    }

    private static String mapGenres(JSONArray gArr, String objType) {
        if (gArr == null || gArr.length() == 0) return "MOVIE".equals(objType) ? "Movie, Drama" : "Series, Drama";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < gArr.length() && i < 3; i++) {
            JSONObject g = gArr.optJSONObject(i);
            if (g == null) continue;
            String label = genreLabel(g.optString("shortName"));
            if (!label.isEmpty()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(label);
            }
        }
        return sb.length() > 0 ? sb.toString() : "Drama";
    }

    private static String genreLabel(String code) {
        switch (code) {
            case "scf": return "Sci-Fi";
            case "trl": return "Thriller";
            case "drm": return "Drama";
            case "cmy": return "Comedy";
            case "act": return "Action";
            case "hor": return "Horror";
            case "crm": return "Crime";
            case "mys": return "Mystery";
            case "rma": return "Romance";
            case "fnt": return "Fantasy";
            case "ani": return "Animation";
            case "doc": return "Documentary";
            default: return "";
        }
    }

    private static JSONObject postGraphQl(String query, JSONObject variables) throws Exception {
        JSONObject payload = new JSONObject().put("query", query).put("variables", variables);
        HttpURLConnection conn = (HttpURLConnection) new URL(GRAPHQL_URL).openConnection();
        conn.setConnectTimeout(3500);
        conn.setReadTimeout(4500);
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
