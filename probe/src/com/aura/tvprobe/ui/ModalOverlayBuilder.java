package com.aura.tvprobe;

import android.content.Context;
import android.graphics.Color;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Native Frosted Smoked-Obsidian Modals for Aura TV (Zero Android AlertDialogs).
 * Provides:
 * 1. Visual Watch History Screen (sorted by Date, with posters, progress bars, and platform filters including Netflix).
 * 2. Aura Settings Screen (Picture Quality control, Ranking Algorithm mode, Languages, AI Engine).
 * 3. Guide AI Taste Studio (Interactive vibe pills + custom prompt).
 * 4. Add Profile Glass Modal.
 */
public final class ModalOverlayBuilder {

    private ModalOverlayBuilder() { }

    public interface ModalActions {
        void onRefreshRecommendations(boolean forceRebuild);
        void onLaunchMedia(JSONObject item);
        default void onHistoryItemSelected(JSONObject item) { onLaunchMedia(item); }
        void onOpenSystemTvSettings();
        void onProfileCreated(String newProfileId);
        void onModalClosed();
        default void onStartScreensaver() {}
    }

    // ── 1. Visual Watch History Studio (Sorted by Date + Platform Filter) ─

    public static void showWatchHistoryModal(
            FrameLayout hostContainer, Context ctx, AuraDb db,
            String activeProfile, ModalActions actions) {
        LinearLayout panel = prepareModalPanel(hostContainer, ctx);
        LinearLayout gridRows = new LinearLayout(ctx);
        gridRows.setOrientation(LinearLayout.VERTICAL);
        final String[] currentFilter = {"ALL"};
        final List<JSONObject> allHistory = collectUnifiedDateSortedHistory(db, activeProfile);
        Runnable renderList = () -> populateHistoryGrid(gridRows, allHistory, currentFilter[0], ctx, hostContainer, actions);
        LinearLayout filterRow = addHistoryHeaderAndFilters(panel, ctx, db, allHistory, currentFilter, renderList, hostContainer, actions);
        panel.addView(wrapInVerticalScroll(ctx, gridRows), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        finishWatchHistoryModal(hostContainer, panel, filterRow, renderList);
    }

    private static LinearLayout addHistoryHeaderAndFilters(LinearLayout panel, Context ctx, AuraDb db, List<JSONObject> allHistory,
                                                           String[] currentFilter, Runnable renderList, FrameLayout hostContainer, ModalActions actions) {
        panel.addView(buildHistoryHeader(ctx, db, allHistory.size(), hostContainer, actions));
        LinearLayout filterRow = buildHistoryFilterRow(ctx, db, allHistory, currentFilter, renderList, hostContainer, actions);
        panel.addView(filterRow);
        return filterRow;
    }

    private static LinearLayout prepareModalPanel(FrameLayout hostContainer, Context ctx) {
        hostContainer.removeAllViews();
        hostContainer.setVisibility(View.VISIBLE);
        return createFullGlassPanel(ctx);
    }

    private static void finishWatchHistoryModal(FrameLayout hostContainer, LinearLayout panel,
                                                LinearLayout filterRow, Runnable renderList) {
        renderList.run();
        hostContainer.addView(panel);
        if (filterRow.getChildCount() > 0) {
            View first = filterRow.getChildAt(0);
            first.post(first::requestFocus);
        }
    }

    // Header Row
    private static LinearLayout buildHistoryHeader(Context ctx, AuraDb db, int totalCount,
                                                   FrameLayout hostContainer, ModalActions actions) {
        LinearLayout hdr = createModalHeaderRow(ctx, 10);
        hdr.addView(buildHistoryTitleColumn(ctx, db, totalCount), GlassTheme.weightParams(1f));
        TextView syncBtn = createHeaderActionBtn(ctx, "\u21BB Sync & Re-Rank", v -> {
            closeModal(hostContainer, actions);
            if (actions != null) actions.onRefreshRecommendations(true);
        }, hostContainer, actions);
        hdr.addView(syncBtn, createRightMarginParams(ctx, 8));
        hdr.addView(createCloseHeaderBtn(ctx, hostContainer, actions), GlassTheme.wrapParams());
        return hdr;
    }

    private static LinearLayout buildHistoryTitleColumn(Context ctx, AuraDb db, int totalCount) {
        LinearLayout titleCol = new LinearLayout(ctx);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.addView(GlassTheme.makeText(ctx, "Watch History", 18f,
            GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1));
        String providers = RegionProviderRegistry.getProviderDisplayListForRegion(db);
        titleCol.addView(GlassTheme.makeText(ctx,
            "Sorted by recent activity across " + providers + " (" + totalCount + " titles)",
            10.5f, GlassTheme.TEXT_MUTED, GlassTheme.FONT_MEDIUM, 1));
        return titleCol;
    }

    // Platform Filter Bar
    private static LinearLayout buildHistoryFilterRow(Context ctx, AuraDb db, List<JSONObject> allHistory,
                                                      String[] currentFilter, Runnable renderList,
                                                      FrameLayout hostContainer, ModalActions actions) {
        LinearLayout filterRow = new LinearLayout(ctx);
        filterRow.setOrientation(LinearLayout.HORIZONTAL);
        filterRow.setGravity(Gravity.CENTER_VERTICAL);
        filterRow.setPadding(0, 0, 0, dp(ctx, 12));
        for (String f : resolveDynamicHistoryFilterPills(db, allHistory)) {
            filterRow.addView(createFilterPill(ctx, f, currentFilter, renderList, hostContainer, actions));
        }
        return filterRow;
    }

    private static List<String> resolveDynamicHistoryFilterPills(AuraDb db, List<JSONObject> allHistory) {
        List<String> pills = new ArrayList<>();
        pills.add("All Platforms");
        pills.add("Watched \u2713");
        pills.add("Continue Watching");
        for (JSONObject item : allHistory) {
            String p = ContinueWatchingSelector.cleanPlatformName(item.optString("app", item.optString("app_name", "")));
            if (!p.isEmpty() && !"Streaming".equals(p) && !"TV".equals(p) && !pills.contains(p) && pills.size() < 7) {
                pills.add(p);
            }
        }
        appendRegionalFallbackPills(db, pills);
        return pills;
    }

    private static void appendRegionalFallbackPills(AuraDb db, List<String> pills) {
        for (String code : RegionProviderRegistry.getProvidersForCountry(RegionProviderRegistry.getCountryCode(db))) {
            if (pills.size() >= 6) break;
            String name = RegionProviderRegistry.normalizeProviderName(code, code);
            if (!pills.contains(name)) pills.add(name);
        }
    }

    private static TextView createFilterPill(Context ctx, String f, String[] currentFilter,
                                             Runnable renderList, FrameLayout hostContainer, ModalActions actions) {
        TextView pill = GlassTheme.makeGlassButton(ctx, f);
        pill.setTextSize(10.5f);
        pill.setPadding(dp(ctx, 12), dp(ctx, 5), dp(ctx, 12), dp(ctx, 5));
        pill.setLayoutParams(createRightMarginParams(ctx, 8));
        pill.setOnClickListener(v -> {
            currentFilter[0] = "All Platforms".equals(f) ? "ALL" : f;
            renderList.run();
        });
        bindBackKey(pill, hostContainer, actions);
        return pill;
    }

    private static ScrollView wrapInVerticalScroll(Context ctx, View child) {
        ScrollView sv = new ScrollView(ctx);
        sv.setVerticalScrollBarEnabled(false);
        sv.setSmoothScrollingEnabled(true);
        sv.setFocusable(false);
        sv.addView(child, GlassTheme.matchWrapParams());
        return sv;
    }

    static List<JSONObject> collectUnifiedDateSortedHistory(AuraDb db, String profileId) {
        List<JSONObject> combined = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        appendOttHistoryItems(db, profileId, combined, seenKeys);
        appendManuallyWatchedItems(db, combined, seenKeys);
        combined.sort(ModalOverlayBuilder::compareHistoryEntries);
        return combined;
    }

    private static int compareHistoryEntries(JSONObject a, JSONObject b) {
        return Long.compare(b.optLong("updated_at", 0L), a.optLong("updated_at", 0L));
    }

    private static void appendOttHistoryItems(AuraDb db, String profileId,
                                              List<JSONObject> combined, Set<String> seenKeys) {
        JSONArray ottHist = db.listHistory(profileId);
        for (int i = 0; i < ottHist.length(); i++) {
            JSONObject copy = buildOttHistoryEntry(db, ottHist.optJSONObject(i), seenKeys);
            if (copy != null) combined.add(copy);
        }
    }

    private static JSONObject buildOttHistoryEntry(AuraDb db, JSONObject o, Set<String> seenKeys) {
        if (o == null) return null;
        String series = o.optString("series_title", "");
        String rawTitle = o.optString("title", "");
        String mainTitle = !series.isEmpty() ? series : rawTitle;
        String app = ContinueWatchingSelector.cleanPlatformName(o.optString("app_name", o.optString("app", "Streaming")));
        if (isExcludedHistoryRow(mainTitle, app, o.optString("app_pkg", "").toLowerCase(), seenKeys)) return null;
        String disp = (!series.isEmpty() && !series.equalsIgnoreCase(rawTitle) && !rawTitle.isEmpty())
            ? (series + " \u00B7 " + rawTitle) : mainTitle;
        boolean watched = o.optBoolean("watched", false) || !ContinueWatchingSelector.isUnfinishedProgress(o);
        return enrichHistoryJson(db, o, disp, mainTitle, app, watched ? "watched" : "continue", -1L);
    }

    private static boolean isExcludedHistoryRow(String mainTitle, String app, String pkg, Set<String> seenKeys) {
        if (mainTitle.isEmpty() || mainTitle.equalsIgnoreCase(app)
                || pkg.contains("youtube") || pkg.contains("smarttube") || pkg.contains("stremio")) {
            return true;
        }
        String norm = AuraDb.normalizeTitleKey(mainTitle);
        return norm.isEmpty() || !seenKeys.add(norm);
    }

    private static void appendManuallyWatchedItems(AuraDb db, List<JSONObject> combined, Set<String> seenKeys) {
        JSONArray watched = db.listManuallyWatched();
        long fallbackTs = (System.currentTimeMillis() / 1000L) - (35L * 86400L);
        for (int i = 0; i < watched.length(); i++) {
            JSONObject copy = buildWatchedHistoryEntry(db, watched.optJSONObject(i), fallbackTs - (i * 86400L), seenKeys);
            if (copy != null) combined.add(copy);
        }
    }

    private static JSONObject buildWatchedHistoryEntry(AuraDb db, JSONObject o, long ts, Set<String> seenKeys) {
        if (o == null) return null;
        String series = o.optString("series_title", "");
        String mainTitle = !series.isEmpty() ? series : o.optString("title", "");
        String app = ContinueWatchingSelector.cleanPlatformName(o.optString("app", o.optString("app_name", "Netflix")));
        if ("TV".equalsIgnoreCase(app) || "Streaming".equalsIgnoreCase(app)) app = "Netflix";
        String norm = AuraDb.normalizeTitleKey(mainTitle);
        if (norm.isEmpty() || !seenKeys.add(norm)) return null;
        return enrichHistoryJson(db, o, mainTitle, mainTitle, app, "watched", ts);
    }

    private static JSONObject enrichHistoryJson(AuraDb db, JSONObject src, String dispTitle,
                                                String mainTitle, String app, String kind, long overrideTs) {
        try {
            JSONObject copy = new JSONObject(src.toString());
            populateEnrichedHistoryFields(copy, dispTitle, app, kind, overrideTs);
            backfillCachedPosterUrl(db, copy, mainTitle);
            return copy;
        } catch (Exception e) {
            return null;
        }
    }

    private static void populateEnrichedHistoryFields(JSONObject copy, String dispTitle, String app,
                                                      String kind, long overrideTs) throws Exception {
        copy.put("display_title", dispTitle);
        copy.put("clean_app", app);
        copy.put("history_kind", kind);
        if (overrideTs > 0 && copy.optLong("updated_at", 0L) <= 0L) copy.put("updated_at", overrideTs);
    }

    private static void backfillCachedPosterUrl(AuraDb db, JSONObject copy, String mainTitle) throws Exception {
        if (!copy.optString("poster_url").isEmpty()) return;
        String cached = db.getCachedGti("jw_imdb_v6:" + mainTitle.toLowerCase());
        if (cached == null) cached = db.getCachedGti("jw_imdb_v5:" + mainTitle.toLowerCase());
        if (cached != null) {
            copy.put("poster_url", new JSONObject(cached).optString("poster_url"));
        }
    }

    private static void populateHistoryGrid(
            LinearLayout gridRows, List<JSONObject> all, String platformFilter,
            Context ctx, FrameLayout hostContainer, ModalActions actions) {
        gridRows.removeAllViews();
        List<JSONObject> filtered = filterHistoryByPlatform(all, platformFilter);
        int rows = (filtered.size() + 1) / 2;
        for (int r = 0; r < rows; r++) {
            gridRows.addView(buildHistoryPairRow(filtered, r, ctx, hostContainer, actions));
        }
    }

    static List<JSONObject> filterHistoryByPlatform(List<JSONObject> all, String platformFilter) {
        List<JSONObject> filtered = new ArrayList<>();
        for (JSONObject item : all) {
            if (matchesHistoryFilter(item, platformFilter)) filtered.add(item);
        }
        return filtered;
    }

    private static boolean matchesHistoryFilter(JSONObject item, String filter) {
        if ("ALL".equalsIgnoreCase(filter)) return true;
        if ("Watched \u2713".equals(filter)) return "watched".equals(item.optString("history_kind"));
        if ("Continue Watching".equals(filter)) return "continue".equals(item.optString("history_kind"));
        return item.optString("clean_app", "").equalsIgnoreCase(filter);
    }

    private static LinearLayout buildHistoryPairRow(List<JSONObject> filtered, int r, Context ctx,
                                                    FrameLayout hostContainer, ModalActions actions) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rLp.bottomMargin = dp(ctx, 8);
        row.setLayoutParams(rLp);
        addHistoryCellToPairRow(row, filtered, r * 2, true, ctx, hostContainer, actions);
        addHistoryCellToPairRow(row, filtered, r * 2 + 1, false, ctx, hostContainer, actions);
        return row;
    }

    private static void addHistoryCellToPairRow(LinearLayout row, List<JSONObject> filtered, int idx,
                                                boolean isLeftCol, Context ctx,
                                                FrameLayout hostContainer, ModalActions actions) {
        if (idx >= filtered.size()) {
            row.addView(new View(ctx), new LinearLayout.LayoutParams(0, 1, 1f));
            return;
        }
        View card = buildHistoryVisualCard(filtered.get(idx), ctx, hostContainer, actions);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (isLeftCol) cLp.rightMargin = dp(ctx, 10);
        row.addView(card, cLp);
    }

    private static View buildHistoryVisualCard(
            JSONObject item, Context ctx, FrameLayout hostContainer, ModalActions actions) {
        float rad = dp(ctx, 12);
        LinearLayout card = createHistoryCardContainer(ctx, rad);
        String dispTitle = item.optString("display_title", "Program");
        String app = item.optString("clean_app", "Netflix");
        card.addView(buildHistoryThumbBox(ctx, dispTitle, item.optString("poster_url", "")));
        card.addView(buildHistoryCardInfo(ctx, item, dispTitle, app), GlassTheme.weightParams(1f));
        bindHistoryCardEvents(card, item, rad, ctx, hostContainer, actions);
        return card;
    }

    private static LinearLayout createHistoryCardContainer(Context ctx, float rad) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(GlassTheme.focusRounded(GlassTheme.GLASS_CARD, GlassTheme.BORDER_LIGHT, dp(ctx, 1), rad));
        card.setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8));
        card.setFocusable(true);
        card.setClickable(true);
        return card;
    }

    private static FrameLayout buildHistoryThumbBox(Context ctx, String dispTitle, String url) {
        FrameLayout thumbBox = new FrameLayout(ctx);
        LinearLayout.LayoutParams tbLp = new LinearLayout.LayoutParams(dp(ctx, 56), dp(ctx, 78));
        tbLp.rightMargin = dp(ctx, 12);
        thumbBox.setLayoutParams(tbLp);
        thumbBox.setBackground(GlassTheme.solidRounded(GlassTheme.SURFACE_ELEV, dp(ctx, 7)));
        addThumbMonogramAndImage(ctx, thumbBox, dispTitle, url);
        return thumbBox;
    }

    private static void addThumbMonogramAndImage(Context ctx, FrameLayout thumbBox, String dispTitle, String url) {
        String mono = dispTitle.isEmpty() ? "\u25B6" : dispTitle.substring(0, 1).toUpperCase();
        TextView monoTv = GlassTheme.makeText(ctx, mono, 18f, GlassTheme.TEXT_MUTED, GlassTheme.FONT_BOLD, 1);
        monoTv.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams match = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        thumbBox.addView(monoTv, match);
        thumbBox.addView(createThumbImageView(ctx, url), match);
    }

    private static ImageView createThumbImageView(Context ctx, String url) {
        ImageView iv = new ImageView(ctx);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ImageLoader.applyRoundedCorners(iv, dp(ctx, 7));
        if (!url.isEmpty()) ImageLoader.load(ctx, url, iv);
        return iv;
    }

    private static LinearLayout buildHistoryCardInfo(Context ctx, JSONObject item, String dispTitle, String app) {
        LinearLayout info = new LinearLayout(ctx);
        info.setOrientation(LinearLayout.VERTICAL);
        info.addView(buildHistoryBadgeDateRow(ctx, app, item.optLong("updated_at", 0L)));
        TextView title = GlassTheme.makeText(ctx, dispTitle, 13f, GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(0, dp(ctx, 4), 0, dp(ctx, 3));
        info.addView(title);
        addHistoryProgressSection(ctx, info, item);
        return info;
    }

    // Top badge row: Platform + Formatted Date
    private static LinearLayout buildHistoryBadgeDateRow(Context ctx, String app, long tsSec) {
        LinearLayout metaRow = new LinearLayout(ctx);
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView pill = GlassTheme.makeText(ctx, app.toUpperCase(), 7.5f, Color.WHITE, GlassTheme.FONT_BOLD, 1);
        pill.setBackground(GlassTheme.solidRounded(HeroSliderBuilder.getPlatformColor(app), dp(ctx, 4)));
        pill.setPadding(dp(ctx, 6), dp(ctx, 2), dp(ctx, 6), dp(ctx, 2));
        metaRow.addView(pill, GlassTheme.wrapParams());
        metaRow.addView(GlassTheme.makeText(ctx, "   \u00B7   " + formatTimestampDate(tsSec), 9.5f,
            GlassTheme.TEXT_MUTED, GlassTheme.FONT_MEDIUM, 1), GlassTheme.wrapParams());
        return metaRow;
    }

    // Status / Progress bar
    private static void addHistoryProgressSection(Context ctx, LinearLayout info, JSONObject item) {
        boolean isWatched = "watched".equals(item.optString("history_kind")) || item.optBoolean("watched", false);
        int pos = item.optInt("position_sec", 0);
        int dur = item.optInt("duration_sec", 0);
        int pct = (pos > 0 && dur > 0) ? Math.min(95, Math.max(10, (pos * 100) / dur)) : 100;
        String statusText = resolveHistoryStatusText(isWatched, pos, dur, pct, item.optInt("user_rating", 0));
        info.addView(GlassTheme.makeText(ctx, statusText, 10f, GlassTheme.GOLD_ACCENT, GlassTheme.FONT_MEDIUM, 1));
        if (!isWatched && pos > 0 && dur > 0) info.addView(buildHistoryProgressTrack(ctx, pct));
    }

    private static String resolveHistoryStatusText(boolean isWatched, int pos, int dur, int pct, int rating) {
        if (isWatched) return rating > 0 ? ("Watched \u2713  \u00B7  Rated \u2605 " + rating + "/10") : "Watched \u2713  \u00B7  Used by AI Taste Graph";
        if (pos > 0 && dur > 0) return "\u25B6 Resume \u00B7 " + pct + "% watched";
        return "Watched \u2713  \u00B7  Used by AI Taste Graph";
    }

    private static LinearLayout buildHistoryProgressTrack(Context ctx, int pct) {
        LinearLayout track = createProgressTrackContainer(ctx);
        View fill = new View(ctx);
        fill.setBackground(GlassTheme.solidRounded(GlassTheme.GOLD_ACCENT, dp(ctx, 2)));
        track.addView(fill, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, pct));
        track.addView(new View(ctx), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 100 - pct));
        return track;
    }

    private static LinearLayout createProgressTrackContainer(Context ctx) {
        LinearLayout track = new LinearLayout(ctx);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setBackground(GlassTheme.solidRounded(0x33FFFFFF, dp(ctx, 2)));
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 3.5f));
        tLp.topMargin = dp(ctx, 5);
        track.setLayoutParams(tLp);
        return track;
    }

    private static void bindHistoryCardEvents(LinearLayout card, JSONObject item, float rad,
                                              Context ctx, FrameLayout hostContainer, ModalActions actions) {
        card.setOnClickListener(v -> {
            closeModal(hostContainer, actions);
            if (actions != null) actions.onHistoryItemSelected(item);
        });
        card.setOnFocusChangeListener((v, focused) -> v.setBackground(GlassTheme.focusRounded(
            focused ? GlassTheme.SURFACE_ELEV : GlassTheme.GLASS_CARD,
            focused ? Color.WHITE : GlassTheme.BORDER_LIGHT, dp(ctx, focused ? 2.5f : 1), rad)));
        bindBackKey(card, hostContainer, actions);
    }

    private static String formatTimestampDate(long tsSec) {
        if (tsSec <= 0L) return "Recent";
        long diffHours = (System.currentTimeMillis() / 1000L - tsSec) / 3600L;
        Date dt = new Date(tsSec * 1000L);
        if (diffHours >= 0 && diffHours < 20) {
            return "Today \u00B7 " + new SimpleDateFormat("MMM d", Locale.US).format(dt);
        }
        if (diffHours >= 20 && diffHours < 44) {
            return "Yesterday \u00B7 " + new SimpleDateFormat("MMM d", Locale.US).format(dt);
        }
        return new SimpleDateFormat("MMM d, yyyy", Locale.US).format(dt);
    }

    // ── 2. Aura Settings Studio (Separated from Android TV Settings!) ────

    public static void showAuraSettingsModal(
            FrameLayout hostContainer, Context ctx, AuraDb db,
            String activeProfile, int remotePort, ModalActions actions) {
        LinearLayout panel = prepareModalPanel(hostContainer, ctx);
        panel.addView(buildSettingsHeader(ctx, hostContainer, actions));
        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        View firstQualityBtn = populateSettingsBody(body, ctx, db, activeProfile, remotePort, hostContainer, actions);
        panel.addView(wrapInVerticalScroll(ctx, body), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        hostContainer.addView(panel);
        if (firstQualityBtn != null) firstQualityBtn.post(firstQualityBtn::requestFocus);
    }

    private static View populateSettingsBody(LinearLayout body, Context ctx, AuraDb db, String activeProfile,
                                             int remotePort, FrameLayout hostContainer, ModalActions actions) {
        View firstQualityBtn = addQualityAndRankingSections(body, ctx, db, activeProfile, remotePort, hostContainer, actions);
        addPreferredLanguagesSection(body, ctx, db, activeProfile, remotePort, hostContainer, actions);
        addCompanionVoiceAndSpeedSection(body, ctx, db, activeProfile, remotePort, hostContainer, actions);
        addAiEngineAndActionsSection(body, ctx, db, activeProfile, remotePort, hostContainer, actions);
        return firstQualityBtn;
    }

    private static void addCompanionVoiceAndSpeedSection(
            LinearLayout body, Context ctx, AuraDb db, String activeProfile,
            int remotePort, FrameLayout hostContainer, ModalActions actions) {
        addSettingSectionHeader(body, ctx, "4. COMPANION VOICE & SPEED (FREE GOOGLE SPEECH)",
            "Choose Catch Me Up narration accent and speaking speed.");
        String[][] accents = {{"US", "US English"}, {"IN", "Indian English"}, {"GB", "UK English"}, {"HI", "Hindi"}};
        String[][] speeds = {{"0.90", "0.9x Calm"}, {"0.97", "1.0x Natural"}, {"1.15", "1.15x Brisk"}};
        addOptionSelectorRow(body, ctx, accents, db.getSetting("tts_accent", "US"),
            k -> applyVoicePrefAndReopen(hostContainer, ctx, db, activeProfile, remotePort, actions, k, null), hostContainer, actions);
        addOptionSelectorRow(body, ctx, speeds, db.getSetting("tts_speed", "0.97"),
            k -> applyVoicePrefAndReopen(hostContainer, ctx, db, activeProfile, remotePort, actions, null, k), hostContainer, actions);
    }

    private static void applyVoicePrefAndReopen(
            FrameLayout hostContainer, Context ctx, AuraDb db, String activeProfile,
            int remotePort, ModalActions actions, String newAccent, String newSpeed) {
        if (newAccent != null) db.setSetting("tts_accent", newAccent);
        if (newSpeed != null) db.setSetting("tts_speed", newSpeed);
        float rate = Float.parseFloat(db.getSetting("tts_speed", "0.97"));
        AuraTtsNarrator.applyVoicePreferences(ctx, db.getSetting("tts_accent", "US"), rate);
        showAuraSettingsModal(hostContainer, ctx, db, activeProfile, remotePort, actions);
    }

    private static LinearLayout buildSettingsHeader(Context ctx, FrameLayout hostContainer, ModalActions actions) {
        LinearLayout hdr = createModalHeaderRow(ctx, 12);
        hdr.addView(GlassTheme.makeText(ctx, "Aura Settings", 18f,
            GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1), GlassTheme.weightParams(1f));
        TextView tvSettingsBtn = createHeaderActionBtn(ctx, "Open Android TV System Settings \u2197",
            v -> { if (actions != null) actions.onOpenSystemTvSettings(); }, hostContainer, actions);
        hdr.addView(tvSettingsBtn, createRightMarginParams(ctx, 8));
        hdr.addView(createCloseHeaderBtn(ctx, hostContainer, actions), GlassTheme.wrapParams());
        return hdr;
    }

    // Section 1: Picture & Poster Resolution Quality & Section 2: Recommendation Ranking Algorithm
    private static View addQualityAndRankingSections(LinearLayout body, Context ctx, AuraDb db,
                                                     String activeProfile, int remotePort,
                                                     FrameLayout hostContainer, ModalActions actions) {
        addSettingSectionHeader(body, ctx, "1. PICTURE & POSTER QUALITY",
            "Controls poster & Hero artwork decoding resolution and color depth (32-bit ARGB_8888 vs 16-bit).");
        String[][] qualityOpts = {
            {"ultra", "Ultra HD (s592 \u00B7 32-bit Crisp)"}, {"balanced", "Balanced HD"}, {"saver", "Data & Memory Saver"}
        };
        View firstBtn = addOptionSelectorRow(body, ctx, qualityOpts, db.getSetting("image_quality", "ultra"),
            key -> applyImageQualitySetting(hostContainer, ctx, db, activeProfile, remotePort, actions, key),
            hostContainer, actions);
        addRankingAlgorithmSection(body, ctx, db, activeProfile, remotePort, hostContainer, actions);
        return firstBtn;
    }

    private static void applyImageQualitySetting(FrameLayout hostContainer, Context ctx, AuraDb db,
                                                 String activeProfile, int remotePort,
                                                 ModalActions actions, String selectedKey) {
        db.setSetting("image_quality", selectedKey);
        ImageLoader.setQualityMode(selectedKey);
        showAuraSettingsModal(hostContainer, ctx, db, activeProfile, remotePort, actions);
    }

    private static final String[][] RANK_OPTS = {
        {"prestige", "Prestige & High IMDb (7.5+)"},
        {"fresh", "Fresh 2024\u20132026 Releases First"},
        {"balanced", "Balanced Discovery"}
    };

    private static final String[] PREFERRED_LANGS = {
        "English", "Spanish", "French", "German", "Hindi", "Korean", "Japanese", "Tamil", "Telugu"
    };

    private static void addRankingAlgorithmSection(LinearLayout body, Context ctx, AuraDb db,
                                                   String activeProfile, int remotePort,
                                                   FrameLayout hostContainer, ModalActions actions) {
        addSettingSectionHeader(body, ctx, "2. AI RANKING ALGORITHM",
            "Adjust how Aura weighs IMDb prestige scores, 2024\u20132026 release recency, and platform affinity.");
        addOptionSelectorRow(body, ctx, RANK_OPTS, db.getSetting("ranking_mode", "prestige"), selectedKey -> {
            db.setSetting("ranking_mode", selectedKey);
            showAuraSettingsModal(hostContainer, ctx, db, activeProfile, remotePort, actions);
        }, hostContainer, actions);
    }

    // Section 3: Preferred Content & Search Languages
    private static void addPreferredLanguagesSection(LinearLayout body, Context ctx, AuraDb db,
                                                     String activeProfile, int remotePort,
                                                     FrameLayout hostContainer, ModalActions actions) {
        addSettingSectionHeader(body, ctx, "3. PREFERRED LANGUAGES (SEARCH IN ANY LANGUAGE)",
            "Select languages to prioritize across recommendations and multilingual voice/text search.");
        String curLangs = db.getSetting("preferred_langs", "English");
        LinearLayout langRow = createPaddedHorizontalRow(ctx, 4, 12);
        for (String lang : PREFERRED_LANGS) {
            langRow.addView(createLanguagePill(ctx, db, curLangs, lang, activeProfile, remotePort, hostContainer, actions));
        }
        body.addView(langRow);
    }

    private static TextView createLanguagePill(Context ctx, AuraDb db, String curLangs, String lang,
                                               String activeProfile, int remotePort,
                                               FrameLayout hostContainer, ModalActions actions) {
        boolean active = curLangs.toLowerCase().contains(lang.toLowerCase());
        String label = (active ? "\u2713 " : "") + lang;
        TextView pill = active ? GlassTheme.makePrimaryButton(ctx, label) : GlassTheme.makeGlassButton(ctx, label);
        pill.setTextSize(10.5f);
        pill.setPadding(dp(ctx, 12), dp(ctx, 6), dp(ctx, 12), dp(ctx, 6));
        pill.setLayoutParams(createRightMarginParams(ctx, 8));
        pill.setOnClickListener(v -> toggleLanguageSetting(hostContainer, ctx, db, curLangs, lang, activeProfile, remotePort, actions));
        bindBackKey(pill, hostContainer, actions);
        return pill;
    }

    private static void toggleLanguageSetting(FrameLayout hostContainer, Context ctx, AuraDb db,
                                              String curLangs, String lang, String activeProfile,
                                              int remotePort, ModalActions actions) {
        db.setSetting("preferred_langs", toggleCsvItem(curLangs, lang));
        db.setProfileTaste(activeProfile, db.getProfileTaste(activeProfile));
        showAuraSettingsModal(hostContainer, ctx, db, activeProfile, remotePort, actions);
    }

    // Section 5: AI Provider Switcher & Phone Web Config
    private static void addAiEngineAndActionsSection(LinearLayout body, Context ctx, AuraDb db,
                                                     String activeProfile, int remotePort,
                                                     FrameLayout hostContainer, ModalActions actions) {
        String mode = db.getSetting("llm_provider", "auto").trim().toLowerCase();
        String[][] providers = {{"gemini", "Gemini Cloud (Recommended)"}, {"auto", "Auto (Local \u2192 Cloud)"}, {"local", "Local LLM Only (Offline)"}};
        addSettingSectionHeader(body, ctx, "5. AI PROVIDER & MOBILE REMOTE",
            buildAiEngineStatusLine(ctx, db, mode, remotePort));
        addOptionSelectorRow(body, ctx, providers, mode,
            k -> applyLlmProviderAndReopen(hostContainer, ctx, db, activeProfile, remotePort, actions, k), hostContainer, actions);
        body.addView(buildSettingsActionRow(ctx, hostContainer, actions));
    }

    private static String buildAiEngineStatusLine(Context ctx, AuraDb db, String mode, int remotePort) {
        String key = db.getSetting("gemini_api_key", "").trim();
        String localUrl = db.getSetting("local_llm_url", "").trim();
        String status = "local".equals(mode)
            ? (localUrl.isEmpty() ? "Local LLM Only (Set URL in Phone Setup)" : "Local LLM Active (" + localUrl + ")")
            : ("gemini".equals(mode) ? (key.isEmpty() ? "Gemini Cloud (Set Key in Phone Setup)" : "Gemini Cloud Active \u2713")
                : (localUrl.isEmpty() ? (key.isEmpty() ? "Offline JustWatch Graph" : "Auto \u2192 Gemini Cloud \u2713") : "Auto \u2192 Local + Cloud Fallback \u2713"));
        return "Status: " + status + "   \u00B7   Phone Companion: http://" + QrRemoteBuilder.getTvIpAddress(ctx) + ":" + remotePort;
    }

    private static void applyLlmProviderAndReopen(
            FrameLayout hostContainer, Context ctx, AuraDb db, String activeProfile,
            int remotePort, ModalActions actions, String newMode) {
        db.setSetting("llm_provider", newMode);
        showAuraSettingsModal(hostContainer, ctx, db, activeProfile, remotePort, actions);
    }

    private static LinearLayout buildSettingsActionRow(Context ctx, FrameLayout hostContainer, ModalActions actions) {
        LinearLayout actionRow = new LinearLayout(ctx);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        actionRow.setPadding(0, dp(ctx, 4), 0, dp(ctx, 8));
        actionRow.addView(createRebuildSettingsBtn(ctx, hostContainer, actions), createRightMarginParams(ctx, 10));
        actionRow.addView(createClearCacheSettingsBtn(ctx, hostContainer, actions), createRightMarginParams(ctx, 10));
        actionRow.addView(createScreensaverSettingsBtn(ctx, hostContainer, actions), GlassTheme.wrapParams());
        return actionRow;
    }

    private static TextView createRebuildSettingsBtn(Context ctx, FrameLayout hostContainer, ModalActions actions) {
        TextView rebuildBtn = GlassTheme.makePrimaryButton(ctx, "\u2726 Save & Rebuild Recommendations Now");
        rebuildBtn.setTextSize(11f);
        rebuildBtn.setPadding(dp(ctx, 14), dp(ctx, 7), dp(ctx, 14), dp(ctx, 7));
        rebuildBtn.setOnClickListener(v -> {
            closeModal(hostContainer, actions);
            if (actions != null) actions.onRefreshRecommendations(true);
        });
        bindBackKey(rebuildBtn, hostContainer, actions);
        return rebuildBtn;
    }

    private static TextView createClearCacheSettingsBtn(Context ctx, FrameLayout hostContainer, ModalActions actions) {
        TextView clearCacheBtn = GlassTheme.makeGlassButton(ctx, "Clear Image Cache & Reload Ultra HD");
        clearCacheBtn.setTextSize(10.5f);
        clearCacheBtn.setPadding(dp(ctx, 12), dp(ctx, 7), dp(ctx, 12), dp(ctx, 7));
        clearCacheBtn.setOnClickListener(v -> {
            ImageLoader.trimMemory();
            closeModal(hostContainer, actions);
            if (actions != null) actions.onRefreshRecommendations(false);
        });
        bindBackKey(clearCacheBtn, hostContainer, actions);
        return clearCacheBtn;
    }

    private static TextView createScreensaverSettingsBtn(Context ctx, FrameLayout hostContainer, ModalActions actions) {
        TextView saverBtn = GlassTheme.makeGlassButton(ctx, "\u2726 Start Ambient Screensaver");
        saverBtn.setTextSize(10.5f);
        saverBtn.setPadding(dp(ctx, 12), dp(ctx, 7), dp(ctx, 12), dp(ctx, 7));
        saverBtn.setOnClickListener(v -> {
            closeModal(hostContainer, actions);
            if (actions != null) actions.onStartScreensaver();
        });
        bindBackKey(saverBtn, hostContainer, actions);
        return saverBtn;
    }

    // ── 3. Guide AI Taste Studio (No AlertDialog!) ───────────────────────

    private static final String[] VIBE_PRESETS = {
        "Mind-Bending Sci-Fi", "Gripping Crime Thrillers", "Dark Psychological", "High-Octane Action",
        "Global Prestige Series", "Slow-Burn Mystery", "IMDb 8.0+ Masterpieces", "Feel-Good & Witty"
    };

    public static void showGuideAiModal(
            FrameLayout hostContainer, Context ctx, AuraDb db,
            String activeProfile, ModalActions actions) {
        LinearLayout panel = prepareModalPanel(hostContainer, ctx);
        panel.addView(buildSimpleModalHeader(ctx, "Guide AI Taste  \u00B7  " + db.getProfileLabel(activeProfile),
            12, hostContainer, actions));
        final String curTaste = db.getProfileTaste(activeProfile);
        View firstPill = addVibePillRows(panel, ctx, db, activeProfile, curTaste, hostContainer, actions);
        addCustomTasteSection(panel, ctx, db, activeProfile, curTaste, hostContainer, actions);
        panel.addView(buildGuideAiBottomRow(ctx, db, activeProfile, hostContainer, actions));
        hostContainer.addView(panel);
        if (firstPill != null) firstPill.post(firstPill::requestFocus);
    }

    private static View addVibePillRows(LinearLayout panel, Context ctx, AuraDb db, String activeProfile,
                                        String curTaste, FrameLayout hostContainer, ModalActions actions) {
        addSettingSectionHeader(panel, ctx, "SELECT YOUR VIBE PILLS (1-CLICK TOGGLE)",
            "Click any vibe below or type custom instructions in any language to steer Aura's ranking engine.");
        LinearLayout row1 = createPaddedHorizontalRow(ctx, 4, 8);
        LinearLayout row2 = createPaddedHorizontalRow(ctx, 0, 14);
        View firstPill = populateVibeRows(row1, row2, ctx, db, activeProfile, curTaste, hostContainer, actions);
        panel.addView(row1);
        panel.addView(row2);
        return firstPill;
    }

    private static View populateVibeRows(LinearLayout row1, LinearLayout row2, Context ctx, AuraDb db,
                                         String activeProfile, String curTaste,
                                         FrameLayout hostContainer, ModalActions actions) {
        View firstPill = null;
        for (int i = 0; i < VIBE_PRESETS.length; i++) {
            TextView pill = createVibePill(ctx, db, activeProfile, curTaste, VIBE_PRESETS[i], hostContainer, actions);
            (i < 4 ? row1 : row2).addView(pill);
            if (firstPill == null) firstPill = pill;
        }
        return firstPill;
    }

    private static TextView createVibePill(Context ctx, AuraDb db, String activeProfile, String curTaste,
                                           String vibe, FrameLayout hostContainer, ModalActions actions) {
        boolean active = curTaste.toLowerCase().contains(vibe.toLowerCase());
        String lbl = (active ? "\u2713 " : "+ ") + vibe;
        TextView pill = styleSelectablePill(ctx, active, lbl, 12, 6, 8);
        pill.setOnClickListener(v -> {
            db.setProfileTaste(activeProfile, toggleCsvItem(curTaste, vibe));
            showGuideAiModal(hostContainer, ctx, db, activeProfile, actions);
        });
        bindBackKey(pill, hostContainer, actions);
        return pill;
    }

    private static TextView styleSelectablePill(Context ctx, boolean active, String lbl, int padH, int padV, int rightMargin) {
        TextView pill = active ? GlassTheme.makePrimaryButton(ctx, lbl) : GlassTheme.makeGlassButton(ctx, lbl);
        pill.setTextSize(10.5f);
        pill.setPadding(dp(ctx, padH), dp(ctx, padV), dp(ctx, padH), dp(ctx, padV));
        pill.setLayoutParams(createRightMarginParams(ctx, rightMargin));
        return pill;
    }

    private static void addCustomTasteSection(LinearLayout panel, Context ctx, AuraDb db, String activeProfile,
                                              String curTaste, FrameLayout hostContainer, ModalActions actions) {
        addSettingSectionHeader(panel, ctx, "CUSTOM TASTE INSTRUCTIONS (OPTIONAL)",
            "Current profile taste: " + (curTaste.isEmpty() ? "Default Modern Prestige" : curTaste));
        LinearLayout inputBox = buildClickToEditBox(ctx, curTaste,
            "Press OK to type custom taste (e.g. 'Thrillers like Drishyam, no horror')\u2026",
            newText -> {
                db.setProfileTaste(activeProfile, newText);
                showGuideAiModal(hostContainer, ctx, db, activeProfile, actions);
            }, hostContainer, actions);
        panel.addView(inputBox);
    }

    private static LinearLayout buildGuideAiBottomRow(Context ctx, AuraDb db, String activeProfile,
                                                      FrameLayout hostContainer, ModalActions actions) {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        String dna = TasteIntelligenceEngine.getTasteDnaPromptSummary(db, activeProfile);
        col.addView(GlassTheme.makeText(ctx, "Learned Taste DNA: " + dna, 10f,
            GlassTheme.TEXT_MUTED, GlassTheme.FONT_REGULAR, 2), createTopMarginParams(ctx, 10));
        LinearLayout bottomRow = createPaddedHorizontalRow(ctx, 10, 0);
        bottomRow.addView(createApplyTasteBtn(ctx, hostContainer, actions), createRightMarginParams(ctx, 10));
        bottomRow.addView(createClearDislikesBtn(ctx, db, activeProfile, hostContainer, actions), createRightMarginParams(ctx, 10));
        bottomRow.addView(createResetTasteBtn(ctx, db, activeProfile, hostContainer, actions), GlassTheme.wrapParams());
        col.addView(bottomRow);
        return col;
    }

    private static TextView createClearDislikesBtn(Context ctx, AuraDb db, String activeProfile,
                                                   FrameLayout hostContainer, ModalActions actions) {
        TextView btn = GlassTheme.makeGlassButton(ctx, "Clear Hidden & Dislikes");
        btn.setTextSize(10.5f);
        btn.setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8));
        btn.setOnClickListener(v -> {
            TasteIntelligenceEngine.clearDismissedItems(db, activeProfile);
            showGuideAiModal(hostContainer, ctx, db, activeProfile, actions);
        });
        bindBackKey(btn, hostContainer, actions);
        return btn;
    }

    private static TextView createApplyTasteBtn(Context ctx, FrameLayout hostContainer, ModalActions actions) {
        TextView saveBtn = GlassTheme.makePrimaryButton(ctx, "\u2726 Apply Taste & Re-Rank Home");
        saveBtn.setTextSize(11f);
        saveBtn.setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 16), dp(ctx, 8));
        saveBtn.setOnClickListener(v -> {
            closeModal(hostContainer, actions);
            if (actions != null) actions.onRefreshRecommendations(true);
        });
        bindBackKey(saveBtn, hostContainer, actions);
        return saveBtn;
    }

    private static TextView createResetTasteBtn(Context ctx, AuraDb db, String activeProfile,
                                                FrameLayout hostContainer, ModalActions actions) {
        TextView resetBtn = GlassTheme.makeGlassButton(ctx, "Reset to Default");
        resetBtn.setTextSize(10.5f);
        resetBtn.setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8));
        resetBtn.setOnClickListener(v -> {
            db.setProfileTaste(activeProfile, "");
            showGuideAiModal(hostContainer, ctx, db, activeProfile, actions);
        });
        bindBackKey(resetBtn, hostContainer, actions);
        return resetBtn;
    }

    // ── 4. Add Profile Glass Modal (No AlertDialog!) ─────────────────────

    public static void showAddProfileModal(
            FrameLayout hostContainer, Context ctx, AuraDb db, ModalActions actions) {
        LinearLayout panel = prepareModalPanel(hostContainer, ctx);
        panel.addView(buildSimpleModalHeader(ctx, "Add New Profile", 14, hostContainer, actions));
        addSettingSectionHeader(panel, ctx, "1-CLICK PROFILE PRESETS",
            "Create a profile with one click or type a custom name below.");
        View firstBtn = addProfilePresetRow(panel, ctx, db, hostContainer, actions);
        addCustomProfileNameBox(panel, ctx, db, hostContainer, actions);
        hostContainer.addView(panel);
        if (firstBtn != null) firstBtn.post(firstBtn::requestFocus);
    }

    private static View addProfilePresetRow(LinearLayout panel, Context ctx, AuraDb db,
                                            FrameLayout hostContainer, ModalActions actions) {
        String[] presets = {"Family", "Kids", "Partner", "Parents", "Guest"};
        LinearLayout presetRow = createPaddedHorizontalRow(ctx, 6, 16);
        View firstBtn = null;
        for (String p : presets) {
            TextView pill = createProfilePresetPill(ctx, db, p, hostContainer, actions);
            presetRow.addView(pill);
            if (firstBtn == null) firstBtn = pill;
        }
        panel.addView(presetRow);
        return firstBtn;
    }

    private static TextView createProfilePresetPill(Context ctx, AuraDb db, String p,
                                                    FrameLayout hostContainer, ModalActions actions) {
        TextView pill = GlassTheme.makeGlassButton(ctx, "+ " + p);
        pill.setTextSize(11f);
        pill.setPadding(dp(ctx, 14), dp(ctx, 7), dp(ctx, 14), dp(ctx, 7));
        pill.setLayoutParams(createRightMarginParams(ctx, 10));
        pill.setOnClickListener(v -> createAndActivateProfile(db, p, hostContainer, actions));
        bindBackKey(pill, hostContainer, actions);
        return pill;
    }

    private static void addCustomProfileNameBox(LinearLayout panel, Context ctx, AuraDb db,
                                                FrameLayout hostContainer, ModalActions actions) {
        addSettingSectionHeader(panel, ctx, "OR TYPE CUSTOM PROFILE NAME", "");
        LinearLayout customBox = buildClickToEditBox(ctx, "",
            "Press OK to type a custom profile name\u2026",
            name -> {
                if (!name.trim().isEmpty()) createAndActivateProfile(db, name.trim(), hostContainer, actions);
            }, hostContainer, actions);
        panel.addView(customBox);
    }

    private static void createAndActivateProfile(AuraDb db, String name,
                                                 FrameLayout hostContainer, ModalActions actions) {
        String id = db.addProfile(name, "");
        closeModal(hostContainer, actions);
        if (actions != null) actions.onProfileCreated(id);
    }

    // ── 5. Phone QR Remote Glass Modal (No AlertDialog!) ─────────────────

    public static void showQrRemoteModal(
            FrameLayout hostContainer, Context ctx, String ip, int port, ModalActions actions) {
        showQrRemoteModal(hostContainer, ctx, ip, port, "", actions);
    }

    public static void showQrRemoteModal(
            FrameLayout hostContainer, Context ctx, String ip, int port,
            String publicUrl, ModalActions actions) {
        hostContainer.removeAllViews();
        hostContainer.setVisibility(View.VISIBLE);
        FrameLayout backdrop = createQrBackdrop(ctx);
        LinearLayout wrapper = createQrModalWrapper(ctx, ip, port, publicUrl);
        TextView closeBtn = createQrDoneButton(ctx, hostContainer, actions);
        wrapper.addView(closeBtn);
        backdrop.addView(wrapper);
        hostContainer.addView(backdrop);
        closeBtn.post(closeBtn::requestFocus);
    }

    private static LinearLayout createQrModalWrapper(Context ctx, String ip, int port, String publicUrl) {
        LinearLayout wrapper = new LinearLayout(ctx);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setGravity(Gravity.CENTER_HORIZONTAL);
        wrapper.setLayoutParams(new FrameLayout.LayoutParams(
            dp(ctx, 420), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        View qrCard = QrRemoteBuilder.buildQrCard(ctx, ip, port, publicUrl);
        qrCard.setFocusable(false);
        wrapper.addView(qrCard, GlassTheme.matchWrapParams());
        return wrapper;
    }

    private static FrameLayout createQrBackdrop(Context ctx) {
        FrameLayout backdrop = new FrameLayout(ctx);
        backdrop.setBackgroundColor(GlassTheme.BG_AMBIENT);
        backdrop.setClickable(true);
        backdrop.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return backdrop;
    }

    private static TextView createQrDoneButton(Context ctx, FrameLayout hostContainer, ModalActions actions) {
        TextView closeBtn = GlassTheme.makePrimaryButton(ctx, "\u2713 Done");
        closeBtn.setTextSize(11.5f);
        closeBtn.setPadding(dp(ctx, 24), dp(ctx, 8), dp(ctx, 24), dp(ctx, 8));
        LinearLayout.LayoutParams cLp = GlassTheme.wrapParams();
        cLp.topMargin = dp(ctx, 12);
        closeBtn.setLayoutParams(cLp);
        closeBtn.setOnClickListener(v -> closeModal(hostContainer, actions));
        bindBackKey(closeBtn, hostContainer, actions);
        return closeBtn;
    }

    // ── Shared Glass UI Helpers ──────────────────────────────────────────

    private interface OptionSelectCallback {
        void onSelect(String key);
    }

    private interface TextSubmitCallback {
        void onSubmit(String text);
    }

    private static LinearLayout createModalHeaderRow(Context ctx, int bottomPadDp) {
        LinearLayout hdr = new LinearLayout(ctx);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER_VERTICAL);
        hdr.setPadding(0, 0, 0, dp(ctx, bottomPadDp));
        return hdr;
    }

    private static LinearLayout buildSimpleModalHeader(Context ctx, String titleText, int bottomPadDp,
                                                       FrameLayout hostContainer, ModalActions actions) {
        LinearLayout hdr = createModalHeaderRow(ctx, bottomPadDp);
        TextView title = GlassTheme.makeText(ctx, titleText, 18f,
            GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1);
        hdr.addView(title, GlassTheme.weightParams(1f));
        hdr.addView(createCloseHeaderBtn(ctx, hostContainer, actions), GlassTheme.wrapParams());
        return hdr;
    }

    private static TextView createHeaderActionBtn(Context ctx, String label, View.OnClickListener click,
                                                  FrameLayout hostContainer, ModalActions actions) {
        TextView btn = GlassTheme.makeGlassButton(ctx, label);
        btn.setTextSize(10.5f);
        btn.setPadding(dp(ctx, 12), dp(ctx, 6), dp(ctx, 12), dp(ctx, 6));
        btn.setOnClickListener(click);
        bindBackKey(btn, hostContainer, actions);
        return btn;
    }

    private static TextView createCloseHeaderBtn(Context ctx, FrameLayout hostContainer, ModalActions actions) {
        return createHeaderActionBtn(ctx, "\u2715 Close", v -> closeModal(hostContainer, actions), hostContainer, actions);
    }

    private static LinearLayout createPaddedHorizontalRow(Context ctx, int topDp, int bottomDp) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(ctx, topDp), 0, dp(ctx, bottomDp));
        return row;
    }

    private static LinearLayout.LayoutParams createRightMarginParams(Context ctx, int rightDp) {
        LinearLayout.LayoutParams lp = GlassTheme.wrapParams();
        lp.rightMargin = dp(ctx, rightDp);
        return lp;
    }

    private static LinearLayout.LayoutParams createTopMarginParams(Context ctx, int topDp) {
        LinearLayout.LayoutParams lp = GlassTheme.matchWrapParams();
        lp.topMargin = dp(ctx, topDp);
        return lp;
    }

    private static LinearLayout createFullGlassPanel(Context ctx) {
        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(GlassTheme.BG_AMBIENT);
        panel.setPadding(dp(ctx, 28), dp(ctx, 22), dp(ctx, 28), dp(ctx, 20));
        panel.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        panel.setClickable(true);
        return panel;
    }

    private static void addSettingSectionHeader(LinearLayout parent, Context ctx,
                                                String title, String subtitle) {
        TextView tv = GlassTheme.makeText(ctx, title, 10.5f,
            GlassTheme.GOLD_ACCENT, GlassTheme.FONT_BOLD, 1);
        tv.setPadding(0, dp(ctx, 6), 0, dp(ctx, 2));
        parent.addView(tv);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = GlassTheme.makeText(ctx, subtitle, 10.5f,
                GlassTheme.TEXT_MUTED, GlassTheme.FONT_REGULAR, 2);
            sub.setPadding(0, 0, 0, dp(ctx, 4));
            parent.addView(sub);
        }
    }

    private static View addOptionSelectorRow(
            LinearLayout parent, Context ctx, String[][] options,
            String activeKey, OptionSelectCallback cb,
            FrameLayout hostContainer, ModalActions actions) {
        LinearLayout row = createPaddedHorizontalRow(ctx, 4, 12);
        View first = null;
        for (String[] opt : options) {
            TextView pill = createOptionSelectorPill(ctx, opt[0], opt[1], activeKey, cb, hostContainer, actions);
            row.addView(pill);
            if (first == null) first = pill;
        }
        parent.addView(row);
        return first;
    }

    private static TextView createOptionSelectorPill(Context ctx, String key, String text, String activeKey,
                                                     OptionSelectCallback cb, FrameLayout hostContainer, ModalActions actions) {
        boolean isSel = key.equalsIgnoreCase(activeKey);
        String label = (isSel ? "\u25CF " : "\u25CB ") + text;
        TextView pill = isSel ? GlassTheme.makePrimaryButton(ctx, label) : GlassTheme.makeGlassButton(ctx, label);
        pill.setTextSize(10.5f);
        pill.setPadding(dp(ctx, 13), dp(ctx, 6), dp(ctx, 13), dp(ctx, 6));
        pill.setLayoutParams(createRightMarginParams(ctx, 8));
        pill.setOnClickListener(v -> cb.onSelect(key));
        bindBackKey(pill, hostContainer, actions);
        return pill;
    }

    private static LinearLayout buildClickToEditBox(
            Context ctx, String initialText, String hint,
            TextSubmitCallback onSubmit, FrameLayout hostContainer, ModalActions actions) {
        float r = dp(ctx, 14);
        LinearLayout box = createClickToEditContainer(ctx, r);
        EditText et = createClickToEditInput(ctx, initialText, hint);
        box.addView(et, GlassTheme.weightParams(1f));
        bindClickToEditEvents(ctx, box, et, r, onSubmit, hostContainer, actions);
        return box;
    }

    private static LinearLayout createClickToEditContainer(Context ctx, float r) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackground(GlassTheme.focusRounded(
            GlassTheme.SURFACE_ELEV, GlassTheme.BORDER_STRONG, dp(ctx, 1), r));
        box.setPadding(dp(ctx, 14), dp(ctx, 8), dp(ctx, 14), dp(ctx, 8));
        box.setFocusable(true);
        box.setClickable(true);
        return box;
    }

    private static EditText createClickToEditInput(Context ctx, String initialText, String hint) {
        EditText et = new EditText(ctx);
        et.setText(initialText);
        et.setHint(hint);
        et.setHintTextColor(GlassTheme.TEXT_MUTED);
        et.setTextColor(GlassTheme.TEXT_PRIMARY);
        et.setTextSize(12f);
        configureClickToEditInput(et);
        return et;
    }

    private static void configureClickToEditInput(EditText et) {
        et.setSingleLine(true);
        et.setBackground(null);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        et.setFocusable(false);
        et.setFocusableInTouchMode(false);
    }

    private static void bindClickToEditEvents(Context ctx, LinearLayout box, EditText et, float r,
                                              TextSubmitCallback onSubmit, FrameLayout hostContainer, ModalActions actions) {
        box.setOnClickListener(v -> activateModalEditText(ctx, et));
        et.setOnEditorActionListener((v, actionId, ev) -> submitModalEditText(ctx, et, onSubmit));
        box.setOnFocusChangeListener((v, focused) -> v.setBackground(GlassTheme.focusRounded(
            GlassTheme.SURFACE_ELEV, focused ? Color.WHITE : GlassTheme.BORDER_STRONG,
            dp(ctx, focused ? 2.5f : 1), r)));
        bindBackKey(box, hostContainer, actions);
    }

    private static void activateModalEditText(Context ctx, EditText et) {
        et.setFocusableInTouchMode(true);
        et.setFocusable(true);
        et.requestFocus();
        et.setSelection(et.getText().length());
        try {
            InputMethodManager imm = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT);
        } catch (Exception ignored) {}
    }

    private static boolean submitModalEditText(Context ctx, EditText et, TextSubmitCallback onSubmit) {
        try {
            InputMethodManager imm = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(et.getWindowToken(), 0);
        } catch (Exception ignored) {}
        et.setFocusable(false);
        et.setFocusableInTouchMode(false);
        onSubmit.onSubmit(et.getText().toString());
        return true;
    }

    private static String toggleCsvItem(String csv, String item) {
        List<String> parts = new ArrayList<>();
        if (csv != null && !csv.trim().isEmpty()) {
            for (String s : csv.split(",")) {
                if (!s.trim().isEmpty()) parts.add(s.trim());
            }
        }
        if (!parts.removeIf(p -> p.equalsIgnoreCase(item))) parts.add(item);
        return String.join(", ", parts);
    }

    public static void closeModal(FrameLayout hostContainer, ModalActions actions) {
        hostContainer.removeAllViews();
        hostContainer.setVisibility(View.GONE);
        if (actions != null) actions.onModalClosed();
    }

    private static void bindBackKey(View v, FrameLayout hostContainer, ModalActions actions) {
        v.setOnKeyListener((view, code, ev) -> {
            if (ev.getAction() == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_BACK) {
                closeModal(hostContainer, actions);
                return true;
            }
            return false;
        });
    }

    private static int dp(Context ctx, float v) {
        return GlassTheme.dp(ctx, v);
    }
}
