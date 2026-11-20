package com.aura.tvprobe;

import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runtime Subtitle (.srt) & Episode/Cast Graph Store.
 * Proactively caches episode transcripts and TVMaze Cast/Episode guides into SQLite
 * and provides zero-LLM-token local slicing, scene-beat compression, grep, and X-Ray tools.
 */
public final class SubtitleAndGuideStore {

    private static final ExecutorService PREFETCH_EXEC = AuraExecutors.serial();

    private SubtitleAndGuideStore() {}

    public static void proactivelyCacheSeriesAsync(
            final AuraDb db, final String title, final int season, final int episode) {
        if (db == null || title == null || title.trim().isEmpty()) return;
        PREFETCH_EXEC.submit(() -> {
            try {
                JSONObject guide = getOrFetchShowGuide(db, title, season > 0);
                int s = Math.max(1, season);
                int e = Math.max(1, episode);
                getOrFetchEpisodeSrtCues(db, title, guide, s, e, season > 0);
                if (season > 0) getOrFetchEpisodeSrtCues(db, title, guide, s, e + 1, true);
            } catch (Exception ignored) {}
        });
    }

    public static JSONObject getOrFetchShowGuide(AuraDb db, String rawTitle, boolean isSeries) {
        String clean = MediaDeepLinkLauncher.extractCleanTitle(new JSONObject());
        String title = (rawTitle != null && !rawTitle.isEmpty()) ? rawTitle.split("\u00B7")[0].trim() : clean;
        String key = "guide:" + AuraDb.normalizeTitleKey(title);
        String cached = DatabaseJanitor.getSubtitleCacheRow(db, key);
        if (cached != null) {
            try { return new JSONObject(cached); } catch (Exception ignored) {}
        }
        JSONObject fetched = fetchShowGuideFromNetwork(title, isSeries);
        if (fetched.length() > 0) {
            DatabaseJanitor.saveSubtitleCacheRow(db, key, AuraDb.normalizeTitleKey(title), 0, 0, fetched.toString());
        }
        return fetched;
    }

    private static JSONObject fetchShowGuideFromNetwork(String title, boolean preferSeries) {
        JSONObject guide = new JSONObject();
        try {
            String imdbId = resolveImdbId(title, preferSeries);
            guide.put("title", title).put("imdb_id", imdbId);
            populateTvMazeGuide(title, guide);
        } catch (Exception ignored) {}
        return guide;
    }

    public static String resolveImdbId(String title, boolean preferSeries) {
        if (title == null || title.trim().isEmpty()) return "";
        String clean = title.trim();
        char first = Character.toLowerCase(clean.charAt(0));
        if (!Character.isLetterOrDigit(first)) first = 'a';
        String url = "https://v3.sg.media-imdb.com/suggestion/" + first + "/" + Uri.encode(clean) + ".json";
        return parseBestImdbId(httpGetText(url, 4500), clean, preferSeries);
    }

    private static String parseBestImdbId(String jsonStr, String title, boolean preferSeries) {
        try {
            JSONArray d = new JSONObject(jsonStr).optJSONArray("d");
            if (d == null || d.length() == 0) return "";
            String normTarget = AuraDb.normalizeTitleKey(title);
            for (int i = 0; i < d.length(); i++) {
                JSONObject item = d.optJSONObject(i);
                if (matchesImdbCandidate(item, normTarget, preferSeries)) return item.optString("id", "");
            }
            return d.getJSONObject(0).optString("id", "");
        } catch (Exception e) { return ""; }
    }

    private static boolean matchesImdbCandidate(JSONObject item, String normTarget, boolean preferSeries) {
        if (item == null || !item.optString("id", "").startsWith("tt")) return false;
        String qid = item.optString("qid", "").toLowerCase(Locale.US);
        boolean isTv = qid.contains("series") || qid.contains("tv");
        String normLabel = AuraDb.normalizeTitleKey(item.optString("l", ""));
        return normLabel.equals(normTarget) && (!preferSeries || isTv);
    }

    private static void populateTvMazeGuide(String title, JSONObject guide) {
        try {
            String url = "https://api.tvmaze.com/singlesearch/shows?q=" + Uri.encode(title) + "&embed[]=episodes&embed[]=cast";
            String raw = httpGetText(url, 5500);
            if (raw.isEmpty()) return;
            JSONObject root = new JSONObject(raw);
            guide.put("show_summary", stripHtml(root.optString("summary", "")));
            JSONObject emb = root.optJSONObject("_embedded");
            if (emb != null) extractEmbeddedEpisodesAndCast(emb, guide);
        } catch (Exception ignored) {}
    }

    private static void extractEmbeddedEpisodesAndCast(JSONObject emb, JSONObject guide) throws Exception {
        guide.put("episodes", compressTvMazeEpisodes(emb.optJSONArray("episodes")));
        guide.put("cast", compressTvMazeCast(emb.optJSONArray("cast")));
    }

    private static JSONArray compressTvMazeEpisodes(JSONArray rawEps) throws Exception {
        JSONArray out = new JSONArray();
        if (rawEps == null) return out;
        for (int i = 0; i < rawEps.length(); i++) {
            JSONObject e = rawEps.optJSONObject(i);
            if (e == null) continue;
            out.put(new JSONObject().put("s", e.optInt("season", 1)).put("e", e.optInt("number", i + 1))
                .put("name", e.optString("name", "")).put("summary", stripHtml(e.optString("summary", ""))));
        }
        return out;
    }

    private static JSONArray compressTvMazeCast(JSONArray rawCast) throws Exception {
        JSONArray out = new JSONArray();
        if (rawCast == null) return out;
        for (int i = 0; i < rawCast.length() && i < 18; i++) {
            JSONObject c = rawCast.optJSONObject(i);
            if (c == null) continue;
            String actor = c.optJSONObject("person") != null ? c.optJSONObject("person").optString("name", "") : "";
            String ch = c.optJSONObject("character") != null ? c.optJSONObject("character").optString("name", "") : "";
            if (!actor.isEmpty()) out.put(new JSONObject().put("actor", actor).put("character", ch));
        }
        return out;
    }

    public static JSONArray getOrFetchEpisodeSrtCues(
            AuraDb db, String rawTitle, JSONObject guide, int season, int episode, boolean isSeries) {
        String seriesKey = AuraDb.normalizeTitleKey(rawTitle);
        int s = isSeries ? Math.max(1, season) : 0;
        int e = isSeries ? Math.max(1, episode) : 0;
        String cacheKey = "srt:" + seriesKey + ":s" + s + "e" + e;
        String cached = DatabaseJanitor.getSubtitleCacheRow(db, cacheKey);
        if (cached != null) {
            try { return new JSONArray(cached); } catch (Exception ignored) {}
        }
        JSONArray cues = fetchAndParseSrtCues(guide, rawTitle, s, e, isSeries);
        if (cues.length() > 0) {
            DatabaseJanitor.saveSubtitleCacheRow(db, cacheKey, seriesKey, s, e, cues.toString());
        }
        return cues;
    }

    private static JSONArray fetchAndParseSrtCues(
            JSONObject guide, String rawTitle, int season, int episode, boolean isSeries) {
        String imdbId = guide != null ? guide.optString("imdb_id", "") : "";
        if (imdbId.isEmpty()) imdbId = resolveImdbId(rawTitle, isSeries);
        if (imdbId.isEmpty()) return new JSONArray();
        String subMetaUrl = isSeries
            ? ("https://opensubtitles-v3.strem.io/subtitles/series/" + imdbId + ":" + season + ":" + episode + ".json")
            : ("https://opensubtitles-v3.strem.io/subtitles/movie/" + imdbId + ".json");
        String srtUrl = pickBestEnglishSrtUrl(httpGetText(subMetaUrl, 6000));
        return srtUrl.isEmpty() ? new JSONArray() : parseRawSrtToCues(httpGetText(srtUrl, 8000));
    }

    private static String pickBestEnglishSrtUrl(String metaJson) {
        try {
            JSONArray subs = new JSONObject(metaJson).optJSONArray("subtitles");
            if (subs == null) return "";
            for (int i = 0; i < subs.length(); i++) {
                JSONObject s = subs.optJSONObject(i);
                if (s != null && "eng".equalsIgnoreCase(s.optString("lang")) && !s.optString("url").isEmpty()) {
                    return s.optString("url");
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    public static JSONArray parseRawSrtToCues(String rawSrt) {
        JSONArray cues = new JSONArray();
        if (rawSrt == null || rawSrt.isEmpty()) return cues;
        String[] blocks = rawSrt.replace("\r\n", "\n").split("\n\n+");
        for (String block : blocks) {
            appendParsedSrtBlock(block.trim(), cues);
        }
        return cues;
    }

    private static void appendParsedSrtBlock(String block, JSONArray cues) {
        String[] lines = block.split("\n");
        if (lines.length < 2) return;
        int timeIdx = lines[0].contains("-->") ? 0 : (lines.length > 1 && lines[1].contains("-->") ? 1 : -1);
        if (timeIdx < 0) return;
        int startSec = parseSrtTimestampSec(lines[timeIdx]);
        String text = cleanSrtDialogueLines(lines, timeIdx + 1);
        if (startSec >= 0 && !isLowSignalFillerLine(text)) {
            try { cues.put(new JSONObject().put("s", startSec).put("t", text)); } catch (Exception ignored) {}
        }
    }

    public static int parseSrtTimestampSec(String arrowLine) {
        try {
            String start = arrowLine.split("-->")[0].trim().replace(',', '.');
            String[] hms = start.split(":");
            return Integer.parseInt(hms[0].trim()) * 3600
                + Integer.parseInt(hms[1].trim()) * 60
                + (int) Double.parseDouble(hms[2].trim());
        } catch (Exception e) { return -1; }
    }

    private static String cleanSrtDialogueLines(String[] lines, int fromIdx) {
        StringBuilder sb = new StringBuilder();
        for (int i = fromIdx; i < lines.length; i++) {
            String cl = stripHtml(lines[i]).replaceAll("\\[(music|sighs|groans|breathing|grunts|laughs|chuckles|footsteps|indistinct)[^\\]]*\\]", "").trim();
            if (!cl.isEmpty()) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(cl);
            }
        }
        return sb.toString().trim();
    }

    private static boolean isLowSignalFillerLine(String text) {
        if (text == null || text.length() < 4) return true;
        String low = text.toLowerCase(Locale.US).replaceAll("[^a-z ]", "").trim();
        return low.equals("yeah") || low.equals("okay") || low.equals("yes") || low.equals("no")
            || low.equals("what") || low.equals("hello") || low.equals("thank you") || low.equals("thanks");
    }

    /**
     * Local 5-minute Scene-Beat Compressor (0 LLM tokens):
     * Slices cues from 00:00 up to maxSec and picks top high-density lines per 5-minute bucket.
     */
    public static String sliceCompressedSceneBeats(JSONArray cues, int maxSec) {
        if (cues == null || cues.length() == 0) return "";
        int effectiveMax = maxSec > 60 ? maxSec : 1800;
        int numBuckets = Math.max(1, (effectiveMax / 300) + 1);
        int wordsPerBucket = Math.max(45, 720 / numBuckets);
        StringBuilder out = new StringBuilder();
        for (int b = 0; b < numBuckets; b++) {
            appendCompressedBucket(cues, b * 300, Math.min(effectiveMax, (b + 1) * 300), wordsPerBucket, out);
        }
        return out.toString().trim();
    }

    private static void appendCompressedBucket(
            JSONArray cues, int startSec, int endSec, int maxWords, StringBuilder out) {
        if (startSec >= endSec) return;
        StringBuilder bucketText = new StringBuilder();
        int words = 0;
        for (int i = 0; i < cues.length() && words < maxWords; i++) {
            JSONObject c = cues.optJSONObject(i);
            int s = c != null ? c.optInt("s", -1) : -1;
            String t = c != null ? c.optString("t", "") : "";
            if (s >= startSec && s <= endSec && isHighSignalBeat(t)) {
                bucketText.append(t).append(" ");
                words += t.split("\\s+").length;
            }
        }
        if (bucketText.length() > 0) {
            out.append("[").append(startSec / 60).append("m-").append(endSec / 60).append("m] ")
               .append(bucketText.toString().trim()).append("\n");
        }
    }

    private static boolean isHighSignalBeat(String t) {
        if (t == null || t.length() < 16) return false;
        return t.length() >= 28 || t.contains("?") || Character.isUpperCase(t.charAt(0));
    }

    public static String sliceRawWindow(JSONArray cues, int minSec, int maxSec) {
        if (cues == null || cues.length() == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cues.length() && sb.length() < 2600; i++) {
            JSONObject c = cues.optJSONObject(i);
            int s = c != null ? c.optInt("s", -1) : -1;
            if (s >= Math.max(0, minSec) && s <= maxSec) {
                sb.append("[").append(formatMmSs(s)).append("] ").append(c.optString("t")).append("\n");
            }
        }
        return sb.toString().trim();
    }

    public static String grepCharacterOrTopic(JSONArray cues, int maxSec, List<String> terms) {
        if (cues == null || cues.length() == 0 || terms == null || terms.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int lastIncludedIdx = -5;
        for (int i = 0; i < cues.length() && sb.length() < 2800; i++) {
            JSONObject c = cues.optJSONObject(i);
            if (c == null || c.optInt("s", 0) > maxSec) continue;
            if (lineMatchesAnyTerm(c.optString("t", ""), terms) && (i - lastIncludedIdx) > 2) {
                appendCueContextWindow(cues, i, maxSec, sb);
                lastIncludedIdx = i;
            }
        }
        return sb.toString().trim();
    }

    private static boolean lineMatchesAnyTerm(String text, List<String> terms) {
        String low = text.toLowerCase(Locale.US);
        for (String term : terms) {
            if (term != null && term.length() >= 3 && low.contains(term.toLowerCase(Locale.US))) return true;
        }
        return false;
    }

    private static void appendCueContextWindow(JSONArray cues, int centerIdx, int maxSec, StringBuilder sb) {
        int from = Math.max(0, centerIdx - 2);
        int to = Math.min(cues.length() - 1, centerIdx + 3);
        int s = cues.optJSONObject(from).optInt("s", 0);
        sb.append("[").append(formatMmSs(s)).append("] ");
        for (int j = from; j <= to; j++) {
            JSONObject cj = cues.optJSONObject(j);
            if (cj != null && cj.optInt("s", 0) <= maxSec) sb.append(cj.optString("t")).append(" ");
        }
        sb.append("\n");
    }

    public static JSONArray inferActiveSceneActors(JSONObject guide, JSONArray cues, int posSec) {
        JSONArray cast = guide != null ? guide.optJSONArray("cast") : null;
        if (cast == null || cast.length() == 0) return new JSONArray();
        String windowText = sliceRawWindow(cues, Math.max(0, posSec - 150), posSec + 20).toLowerCase(Locale.US);
        JSONArray out = matchCastInDialogueWindow(cast, windowText);
        appendRemainingTopCast(cast, out, 10);
        return out;
    }

    private static void appendRemainingTopCast(JSONArray cast, JSONArray out, int maxTotal) {
        for (int i = 0; i < cast.length() && out.length() < maxTotal; i++) {
            JSONObject c = cast.optJSONObject(i);
            if (c != null && !containsActor(out, c.optString("actor", ""))) out.put(c);
        }
    }

    private static boolean containsActor(JSONArray arr, String actor) {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && actor.equalsIgnoreCase(o.optString("actor", ""))) return true;
        }
        return false;
    }

    private static JSONArray matchCastInDialogueWindow(JSONArray cast, String windowText) {
        JSONArray out = new JSONArray();
        if (windowText.isEmpty()) return out;
        for (int i = 0; i < cast.length(); i++) {
            JSONObject c = cast.optJSONObject(i);
            if (c != null && isCharacterMentioned(c.optString("character", ""), windowText)) {
                out.put(c);
            }
        }
        return out;
    }

    private static boolean isCharacterMentioned(String charName, String windowText) {
        for (String token : charName.toLowerCase(Locale.US).split("\\s+")) {
            if (token.length() >= 3 && windowText.contains(token)) return true;
        }
        return false;
    }

    public static String buildHierarchicalEpisodeStitch(JSONObject guide, int targetSeason, int targetEp) {
        if (guide == null) return "";
        StringBuilder sb = new StringBuilder();
        String showSum = guide.optString("show_summary", "");
        if (!showSum.isEmpty()) sb.append("Premise: ").append(showSum).append("\n");
        JSONArray eps = guide.optJSONArray("episodes");
        if (eps == null) return sb.toString();
        appendPriorEpisodeSummaries(eps, Math.max(1, targetSeason), Math.max(1, targetEp), sb);
        return sb.toString().trim();
    }

    private static void appendPriorEpisodeSummaries(JSONArray eps, int targetS, int targetE, StringBuilder sb) {
        for (int i = 0; i < eps.length(); i++) {
            JSONObject ep = eps.optJSONObject(i);
            if (ep == null) continue;
            int s = ep.optInt("s", 1);
            int e = ep.optInt("e", 1);
            if (s < targetS || (s == targetS && e <= targetE)) {
                appendSingleEpisodeSummary(ep, s, e, targetS, targetE, sb);
            }
        }
    }

    private static void appendSingleEpisodeSummary(
            JSONObject ep, int s, int e, int targetS, int targetE, StringBuilder sb) {
        String sum = ep.optString("summary", "");
        if (sum.isEmpty()) return;
        boolean isRecentOrCurrent = (s == targetS && e >= targetE - 4);
        String text = (!isRecentOrCurrent && sum.length() > 120) ? sum.substring(0, 120) + "..." : sum;
        String tag = (s == targetS && e == targetE) ? " [CURRENT EPISODE OVERVIEW]" : "";
        sb.append("S").append(s).append("E").append(e).append(" (").append(ep.optString("name")).append(")")
          .append(tag).append(": ").append(text).append("\n");
    }

    public static String formatMmSs(int totalSec) {
        int m = Math.max(0, totalSec) / 60;
        int s = Math.max(0, totalSec) % 60;
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    private static String stripHtml(String raw) {
        return raw == null ? "" : raw.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }

    private static String httpGetText(String urlStr, int timeoutMs) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64)");
            return readStreamText(conn);
        } catch (Exception e) { return ""; }
    }

    private static String readStreamText(HttpURLConnection conn) throws Exception {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
            return sb.toString();
        }
    }
}
