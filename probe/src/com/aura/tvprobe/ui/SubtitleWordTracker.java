package com.aura.tvprobe;

import java.util.ArrayList;
import java.util.List;

/**
 * Word-to-word subtitle counter and page slicer.
 * Tokenizes each spoken sentence into indexed words (0..N-1), maps TTS character
 * ranges to exact word indices, and builds progressive pages with active-word bounds.
 */
public final class SubtitleWordTracker {

    public static final int WORDS_PER_PAGE = 10;

    public static final class WordToken {
        public final int index;
        public final int charStart;
        public final int charEnd;
        public final String text;

        public WordToken(int index, int charStart, int charEnd, String text) {
            this.index = index;
            this.charStart = charStart;
            this.charEnd = charEnd;
            this.text = text;
        }
    }

    public static final class PageSlice {
        public final String text;
        public final int activeStart;
        public final int activeEnd;
        public final int wordIndex;
        public final int totalWords;

        public PageSlice(String text, int activeStart, int activeEnd, int wordIndex, int totalWords) {
            this.text = text;
            this.activeStart = activeStart;
            this.activeEnd = activeEnd;
            this.wordIndex = wordIndex;
            this.totalWords = totalWords;
        }
    }

    private SubtitleWordTracker() {}

    public static List<WordToken> tokenize(String sentence) {
        List<WordToken> tokens = new ArrayList<>();
        if (sentence == null || sentence.isEmpty()) return tokens;
        for (int i = skipWhitespace(sentence, 0); i < sentence.length(); i = skipWhitespace(sentence, i)) {
            int end = findTokenEnd(sentence, i);
            tokens.add(new WordToken(tokens.size(), i, end, sentence.substring(i, end)));
            i = end;
        }
        return tokens;
    }

    private static int skipWhitespace(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    private static int findTokenEnd(String s, int i) {
        while (i < s.length() && !Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    public static int resolveWordIndex(List<WordToken> tokens, int charStart, int charEnd) {
        if (tokens == null || tokens.isEmpty()) return -1;
        int bestIdx = 0;
        int bestDist = Integer.MAX_VALUE;
        for (WordToken t : tokens) {
            if (overlaps(t, charStart, charEnd)) return t.index;
            int dist = Math.abs(t.charStart - charStart);
            if (dist < bestDist) { bestDist = dist; bestIdx = t.index; }
        }
        return bestIdx;
    }

    private static boolean overlaps(WordToken t, int start, int end) {
        int safeEnd = Math.max(start + 1, end);
        return start < t.charEnd && safeEnd > t.charStart;
    }

    public static PageSlice buildPageAtWord(List<WordToken> tokens, int rawWordIndex) {
        if (tokens == null || tokens.isEmpty()) return new PageSlice("", 0, 0, 0, 0);
        int clamped = Math.max(0, Math.min(rawWordIndex, tokens.size() - 1));
        int pageStart = (clamped / WORDS_PER_PAGE) * WORDS_PER_PAGE;
        return assemblePageSlice(tokens, pageStart, clamped);
    }

    public static List<String> splitIntoReadableCaptions(String script, int maxWords) {
        List<String> captions = new ArrayList<>();
        if (script == null || script.trim().isEmpty()) return captions;
        int limit = Math.max(8, maxWords);
        for (String sentence : script.trim().split("(?<=[.!?])\\s+")) {
            appendSentenceAsCalmCaptions(sentence.trim(), limit, captions);
        }
        return captions;
    }

    private static void appendSentenceAsCalmCaptions(String sentence, int limit, List<String> out) {
        if (sentence.isEmpty()) return;
        String[] words = sentence.split("\\s+");
        if (words.length <= limit) { out.add(sentence); return; }
        splitLongSentenceByClauses(sentence, limit, out);
    }

    private static void splitLongSentenceByClauses(String sentence, int limit, List<String> out) {
        StringBuilder cur = new StringBuilder();
        int count = 0;
        for (String clause : sentence.split("(?<=[,;:\u2014])\\s+")) {
            int cw = clause.trim().split("\\s+").length;
            if (count > 0 && (count + cw) > limit) { out.add(cur.toString().trim()); cur.setLength(0); count = 0; }
            if (cur.length() > 0) cur.append(' ');
            cur.append(clause.trim());
            count += cw;
        }
        if (cur.length() > 0) out.add(cur.toString().trim());
    }

    private static PageSlice assemblePageSlice(List<WordToken> tokens, int pageStart, int activeIdx) {
        StringBuilder sb = new StringBuilder();
        int hiStart = 0;
        int hiEnd = 0;
        for (int i = pageStart; i <= activeIdx; i++) {
            if (sb.length() > 0) sb.append(' ');
            int wStart = sb.length();
            sb.append(tokens.get(i).text);
            if (i == activeIdx) { hiStart = wStart; hiEnd = sb.length(); }
        }
        return new PageSlice(sb.toString(), hiStart, hiEnd, activeIdx, tokens.size());
    }
}
