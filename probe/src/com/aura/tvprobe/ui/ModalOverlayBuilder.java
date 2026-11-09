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
        void onOpenSystemTvSettings();
        void onProfileCreated(String newProfileId);
        void onModalClosed();
        default void onStartScreensaver() {}
    }

    // ── 1. Visual Watch History Studio (Sorted by Date + Platform Filter) ─

    public static void showWatchHistoryModal(
            FrameLayout hostContainer, Context ctx, AuraDb db,
            String activeProfile, ModalActions actions) {
        hostContainer.removeAllViews();
        hostContainer.setVisibility(View.VISIBLE);

        LinearLayout panel = createFullGlassPanel(ctx);
        LinearLayout gridRows = new LinearLayout(ctx);
        gridRows.setOrientation(LinearLayout.VERTICAL);

        final String[] currentFilter = {"ALL"};
        final List<JSONObject> allHistory = collectUnifiedDateSortedHistory(db, activeProfile);

        // Header Row
        LinearLayout hdr = new LinearLayout(ctx);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER_VERTICAL);
        hdr.setPadding(0, 0, 0, dp(ctx, 10));

        LinearLayout titleCol = new LinearLayout(ctx);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        TextView title = GlassTheme.makeText(ctx, "Watch History", 18f,
            GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1);
        TextView sub = GlassTheme.makeText(ctx,
            "Sorted by recent activity across Netflix, Prime Video, Disney+ Hotstar & Apple TV+ (" + allHistory.size() + " titles)",
            10.5f, GlassTheme.TEXT_MUTED, GlassTheme.FONT_MEDIUM, 1);
        titleCol.addView(title);
        titleCol.addView(sub);
        hdr.addView(titleCol, GlassTheme.weightParams(1f));

        TextView syncBtn = GlassTheme.makeGlassButton(ctx, "\u21BB Sync & Re-Rank");
        syncBtn.setTextSize(10.5f);
        syncBtn.setPadding(dp(ctx, 12), dp(ctx, 6), dp(ctx, 12), dp(ctx, 6));
        syncBtn.setOnClickListener(v -> {
            closeModal(hostContainer, actions);
            if (actions != null) actions.onRefreshRecommendations(true);
        });
        LinearLayout.LayoutParams sLp = GlassTheme.wrapParams();
        sLp.rightMargin = dp(ctx, 8);
        hdr.addView(syncBtn, sLp);

        TextView closeBtn = GlassTheme.makeGlassButton(ctx, "\u2715 Close");
        closeBtn.setTextSize(10.5f);
        closeBtn.setPadding(dp(ctx, 12), dp(ctx, 6), dp(ctx, 12), dp(ctx, 6));
        closeBtn.setOnClickListener(v -> closeModal(hostContainer, actions));
        bindBackKey(closeBtn, hostContainer, actions);
        bindBackKey(syncBtn, hostContainer, actions);
        hdr.addView(closeBtn, GlassTheme.wrapParams());
        panel.addView(hdr);

        // Platform Filter Bar
        LinearLayout filterRow = new LinearLayout(ctx);
        filterRow.setOrientation(LinearLayout.HORIZONTAL);
        filterRow.setGravity(Gravity.CENTER_VERTICAL);
        filterRow.setPadding(0, 0, 0, dp(ctx, 12));

        String[] filters = {"All Platforms", "Netflix", "Prime Video", "Disney+ Hotstar", "Apple TV+"};
        Runnable renderList = () -> populateHistoryGrid(
            gridRows, allHistory, currentFilter[0], ctx, hostContainer, actions);

        for (String f : filters) {
            TextView pill = GlassTheme.makeGlassButton(ctx, f);
            pill.setTextSize(10.5f);
            pill.setPadding(dp(ctx, 12), dp(ctx, 5), dp(ctx, 12), dp(ctx, 5));
            LinearLayout.LayoutParams pLp = GlassTheme.wrapParams();
            pLp.rightMargin = dp(ctx, 8);
            pill.setLayoutParams(pLp);
            pill.setOnClickListener(v -> {
                currentFilter[0] = "All Platforms".equals(f) ? "ALL" : f;
                renderList.run();
            });
            bindBackKey(pill, hostContainer, actions);
            filterRow.addView(pill);
        }
        panel.addView(filterRow);

        ScrollView sv = new ScrollView(ctx);
        sv.setVerticalScrollBarEnabled(false);
        sv.setSmoothScrollingEnabled(true);
        sv.setFocusable(false);
        sv.addView(gridRows, GlassTheme.matchWrapParams());
        panel.addView(sv, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        renderList.run();
        hostContainer.addView(panel);
        if (filterRow.getChildCount() > 0) {
            View first = filterRow.getChildAt(0);
            first.post(first::requestFocus);
        }
    }

    private static List<JSONObject> collectUnifiedDateSortedHistory(AuraDb db, String profileId) {
        List<JSONObject> combined = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();

        JSONArray ottHist = db.listHistory(profileId);
        for (int i = 0; i < ottHist.length(); i++) {
            JSONObject o = ottHist.optJSONObject(i);
            if (o == null) continue;
            String series = o.optString("series_title", "");
            String rawTitle = o.optString("title", "");
            String mainTitle = !series.isEmpty() ? series : rawTitle;
            String app = HeroSliderBuilder.cleanPlatformName(
                o.optString("app_name", o.optString("app", "Streaming")));
            String pkg = o.optString("app_pkg", "").toLowerCase();
            if (mainTitle.isEmpty() || mainTitle.equalsIgnoreCase(app)
                    || pkg.contains("youtube") || pkg.contains("smarttube") || pkg.contains("stremio")) {
                continue;
            }
            String norm = AuraDb.normalizeTitleKey(mainTitle);
            if (norm.isEmpty() || !seenKeys.add(norm)) continue;
            try {
                JSONObject copy = new JSONObject(o.toString());
                copy.put("display_title", (!series.isEmpty() && !series.equalsIgnoreCase(rawTitle) && !rawTitle.isEmpty())
                    ? (series + " \u00B7 " + rawTitle) : mainTitle);
                copy.put("clean_app", app);
                copy.put("history_kind", "continue");
                if (copy.optString("poster_url").isEmpty()) {
                    String cached = db.getCachedGti("jw_imdb_v6:" + mainTitle.toLowerCase());
                    if (cached == null) cached = db.getCachedGti("jw_imdb_v5:" + mainTitle.toLowerCase());
                    if (cached != null) {
                        JSONObject cm = new JSONObject(cached);
                        copy.put("poster_url", cm.optString("poster_url"));
                    }
                }
                combined.add(copy);
            } catch (Exception ignored) {}
        }

        JSONArray watched = db.listManuallyWatched();
        long fallbackTs = (System.currentTimeMillis() / 1000L) - (35L * 86400L);
        for (int i = 0; i < watched.length(); i++) {
            JSONObject o = watched.optJSONObject(i);
            if (o == null) continue;
            String series = o.optString("series_title", "");
            String rawTitle = o.optString("title", "");
            String mainTitle = !series.isEmpty() ? series : rawTitle;
            String app = HeroSliderBuilder.cleanPlatformName(
                o.optString("app", o.optString("app_name", "Netflix")));
            if ("TV".equalsIgnoreCase(app) || "Streaming".equalsIgnoreCase(app)) app = "Netflix";
            String norm = AuraDb.normalizeTitleKey(mainTitle);
            if (norm.isEmpty() || !seenKeys.add(norm)) continue;
            try {
                JSONObject copy = new JSONObject(o.toString());
                copy.put("display_title", mainTitle);
                copy.put("clean_app", app);
                copy.put("history_kind", "watched");
                long ts = fallbackTs - (i * 86400L);
                copy.put("updated_at", ts);
                if (copy.optString("poster_url").isEmpty()) {
                    String cached = db.getCachedGti("jw_imdb_v6:" + mainTitle.toLowerCase());
                    if (cached == null) cached = db.getCachedGti("jw_imdb_v5:" + mainTitle.toLowerCase());
                    if (cached != null) {
                        JSONObject cm = new JSONObject(cached);
                        copy.put("poster_url", cm.optString("poster_url"));
                    }
                }
                combined.add(copy);
            } catch (Exception ignored) {}
        }

        combined.sort((a, b) -> {
            boolean aCont = "continue".equals(a.optString("history_kind"));
            boolean bCont = "continue".equals(b.optString("history_kind"));
            if (aCont != bCont) return aCont ? -1 : 1;
            return Long.compare(b.optLong("updated_at", 0L), a.optLong("updated_at", 0L));
        });
        return combined;
    }

    private static void populateHistoryGrid(
            LinearLayout gridRows, List<JSONObject> all, String platformFilter,
            Context ctx, FrameLayout hostContainer, ModalActions actions) {
        gridRows.removeAllViews();
        List<JSONObject> filtered = new ArrayList<>();
        for (JSONObject item : all) {
            String app = item.optString("clean_app", "");
            if ("ALL".equalsIgnoreCase(platformFilter) || app.equalsIgnoreCase(platformFilter)) {
                filtered.add(item);
            }
        }
        int n = filtered.size();
        int rows = (n + 1) / 2;
        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rLp.bottomMargin = dp(ctx, 8);
            row.setLayoutParams(rLp);

            for (int c = 0; c < 2; c++) {
                int idx = r * 2 + c;
                if (idx < n) {
                    View card = buildHistoryVisualCard(filtered.get(idx), ctx, hostContainer, actions);
                    LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    if (c == 0) cLp.rightMargin = dp(ctx, 10);
                    card.setLayoutParams(cLp);
                    row.addView(card);
                } else {
                    row.addView(new View(ctx), new LinearLayout.LayoutParams(0, 1, 1f));
                }
            }
            gridRows.addView(row);
        }
    }

    private static View buildHistoryVisualCard(
            JSONObject item, Context ctx, FrameLayout hostContainer, ModalActions actions) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        float rad = dp(ctx, 12);
        card.setBackground(GlassTheme.focusRounded(
            GlassTheme.GLASS_CARD, GlassTheme.BORDER_LIGHT, dp(ctx, 1), rad));
        card.setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8));
        card.setFocusable(true);
        card.setClickable(true);

        String dispTitle = item.optString("display_title", "Program");
        String app = item.optString("clean_app", "Netflix");

        FrameLayout thumbBox = new FrameLayout(ctx);
        LinearLayout.LayoutParams tbLp = new LinearLayout.LayoutParams(dp(ctx, 56), dp(ctx, 78));
        tbLp.rightMargin = dp(ctx, 12);
        thumbBox.setLayoutParams(tbLp);
        thumbBox.setBackground(GlassTheme.solidRounded(GlassTheme.SURFACE_ELEV, dp(ctx, 7)));

        String mono = dispTitle.isEmpty() ? "\u25B6" : dispTitle.substring(0, 1).toUpperCase();
        TextView monoTv = GlassTheme.makeText(ctx, mono, 18f,
            GlassTheme.TEXT_MUTED, GlassTheme.FONT_BOLD, 1);
        monoTv.setGravity(Gravity.CENTER);
        thumbBox.addView(monoTv, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ImageView iv = new ImageView(ctx);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ImageLoader.applyRoundedCorners(iv, dp(ctx, 7));
        String url = item.optString("poster_url", "");
        if (!url.isEmpty()) {
            ImageLoader.load(ctx, url, iv);
        }
        thumbBox.addView(iv, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        card.addView(thumbBox);

        LinearLayout info = new LinearLayout(ctx);
        info.setOrientation(LinearLayout.VERTICAL);

        // Top badge row: Platform + Formatted Date
        LinearLayout metaRow = new LinearLayout(ctx);
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView pill = GlassTheme.makeText(ctx, app.toUpperCase(), 7.5f,
            Color.WHITE, GlassTheme.FONT_BOLD, 1);
        pill.setBackground(GlassTheme.solidRounded(HeroSliderBuilder.getPlatformColor(app), dp(ctx, 4)));
        pill.setPadding(dp(ctx, 6), dp(ctx, 2), dp(ctx, 6), dp(ctx, 2));
        metaRow.addView(pill, GlassTheme.wrapParams());

        long tsSec = item.optLong("updated_at", 0L);
        String dateStr = formatTimestampDate(tsSec);
        TextView dateTv = GlassTheme.makeText(ctx, "   \u00B7   " + dateStr, 9.5f,
            GlassTheme.TEXT_MUTED, GlassTheme.FONT_MEDIUM, 1);
        metaRow.addView(dateTv, GlassTheme.wrapParams());
        info.addView(metaRow);

        // Title
        TextView title = GlassTheme.makeText(ctx, dispTitle,
            13f, GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(0, dp(ctx, 4), 0, dp(ctx, 3));
        info.addView(title);

        // Status / Progress bar
        int pos = item.optInt("position_sec", 0);
        int dur = item.optInt("duration_sec", 0);
        int rating = item.optInt("user_rating", 0);
        String statusText;
        int pct = 100;
        if (pos > 0 && dur > 0) {
            pct = Math.min(95, Math.max(10, (pos * 100) / dur));
            statusText = "\u25B6 Resume \u00B7 " + pct + "% watched";
        } else if (rating > 0) {
            statusText = "Watched \u2713  \u00B7  Rated \u2605 " + rating + "/10";
        } else {
            statusText = "Watched \u2713  \u00B7  Used by AI Taste Graph";
        }
        TextView stTv = GlassTheme.makeText(ctx, statusText, 10f,
            GlassTheme.GOLD_ACCENT, GlassTheme.FONT_MEDIUM, 1);
        info.addView(stTv);

        if (pos > 0 && dur > 0) {
            LinearLayout track = new LinearLayout(ctx);
            track.setOrientation(LinearLayout.HORIZONTAL);
            track.setBackground(GlassTheme.solidRounded(0x33FFFFFF, dp(ctx, 2)));
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 3.5f));
            tLp.topMargin = dp(ctx, 5);
            track.setLayoutParams(tLp);
            View fill = new View(ctx);
            fill.setBackground(GlassTheme.solidRounded(GlassTheme.GOLD_ACCENT, dp(ctx, 2)));
            track.addView(fill, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, pct));
            track.addView(new View(ctx), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 100 - pct));
            info.addView(track);
        }

        card.addView(info, GlassTheme.weightParams(1f));

        card.setOnClickListener(v -> {
            closeModal(hostContainer, actions);
            if (actions != null) actions.onLaunchMedia(item);
        });
        card.setOnFocusChangeListener((v, focused) -> {
            int bg = focused ? GlassTheme.SURFACE_ELEV : GlassTheme.GLASS_CARD;
            int stroke = focused ? Color.WHITE : GlassTheme.BORDER_LIGHT;
            int sw = focused ? dp(ctx, 2.5f) : dp(ctx, 1);
            v.setBackground(GlassTheme.focusRounded(bg, stroke, sw, rad));
        });
        bindBackKey(card, hostContainer, actions);
        return card;
    }

    private static String formatTimestampDate(long tsSec) {
        if (tsSec <= 0L) return "Recent";
        long nowSec = System.currentTimeMillis() / 1000L;
        long diffHours = (nowSec - tsSec) / 3600L;
        if (diffHours >= 0 && diffHours < 20) {
            return "Today \u00B7 " + new SimpleDateFormat("MMM d", Locale.US).format(new Date(tsSec * 1000L));
        }
        if (diffHours >= 20 && diffHours < 44) {
            return "Yesterday \u00B7 " + new SimpleDateFormat("MMM d", Locale.US).format(new Date(tsSec * 1000L));
        }
        return new SimpleDateFormat("MMM d, yyyy", Locale.US).format(new Date(tsSec * 1000L));
    }

    // ── 2. Aura Settings Studio (Separated from Android TV Settings!) ────

    public static void showAuraSettingsModal(
            FrameLayout hostContainer, Context ctx, AuraDb db,
            String activeProfile, int remotePort, ModalActions actions) {
        hostContainer.removeAllViews();
        hostContainer.setVisibility(View.VISIBLE);

        LinearLayout panel = createFullGlassPanel(ctx);

        // Header
        LinearLayout hdr = new LinearLayout(ctx);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER_VERTICAL);
        hdr.setPadding(0, 0, 0, dp(ctx, 12));

        TextView title = GlassTheme.makeText(ctx, "Aura Settings", 18f,
            GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1);
        hdr.addView(title, GlassTheme.weightParams(1f));

        TextView tvSettingsBtn = GlassTheme.makeGlassButton(ctx, "Open Android TV System Settings \u2197");
        tvSettingsBtn.setTextSize(10.5f);
        tvSettingsBtn.setPadding(dp(ctx, 12), dp(ctx, 6), dp(ctx, 12), dp(ctx, 6));
        tvSettingsBtn.setOnClickListener(v -> {
            if (actions != null) actions.onOpenSystemTvSettings();
        });
        LinearLayout.LayoutParams tLp = GlassTheme.wrapParams();
        tLp.rightMargin = dp(ctx, 8);
        hdr.addView(tvSettingsBtn, tLp);

        TextView closeBtn = GlassTheme.makeGlassButton(ctx, "\u2715 Close");
        closeBtn.setTextSize(10.5f);
        closeBtn.setPadding(dp(ctx, 12), dp(ctx, 6), dp(ctx, 12), dp(ctx, 6));
        closeBtn.setOnClickListener(v -> closeModal(hostContainer, actions));
        bindBackKey(closeBtn, hostContainer, actions);
        bindBackKey(tvSettingsBtn, hostContainer, actions);
        hdr.addView(closeBtn, GlassTheme.wrapParams());
        panel.addView(hdr);

        ScrollView sv = new ScrollView(ctx);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);

        // Section 1: Picture & Poster Resolution Quality
        addSettingSectionHeader(body, ctx, "1. PICTURE & POSTER QUALITY",
            "Controls poster & Hero artwork decoding resolution and color depth (32-bit ARGB_8888 vs 16-bit).");
        String curQuality = db.getSetting("image_quality", "ultra");
        String[][] qualityOpts = {
            {"ultra", "Ultra HD (s592 \u00B7 32-bit Crisp)"},
            {"balanced", "Balanced HD"},
            {"saver", "Data & Memory Saver"}
        };
        View firstQualityBtn = addOptionSelectorRow(body, ctx, qualityOpts, curQuality, selectedKey -> {
            db.setSetting("image_quality", selectedKey);
            ImageLoader.setQualityMode(selectedKey);
            showAuraSettingsModal(hostContainer, ctx, db, activeProfile, remotePort, actions);
        }, hostContainer, actions);

        // Section 2: Recommendation Ranking Algorithm
        addSettingSectionHeader(body, ctx, "2. AI RANKING ALGORITHM",
            "Adjust how Aura weighs IMDb prestige scores, 2024\u20132026 release recency, and platform affinity.");
        String curRank = db.getSetting("ranking_mode", "prestige");
        String[][] rankOpts = {
            {"prestige", "Prestige & High IMDb (7.5+)"},
            {"fresh", "Fresh 2024\u20132026 Releases First"},
            {"balanced", "Balanced Discovery"}
        };
        addOptionSelectorRow(body, ctx, rankOpts, curRank, selectedKey -> {
            db.setSetting("ranking_mode", selectedKey);
            showAuraSettingsModal(hostContainer, ctx, db, activeProfile, remotePort, actions);
        }, hostContainer, actions);

        // Section 3: Preferred Content & Search Languages
        addSettingSectionHeader(body, ctx, "3. PREFERRED LANGUAGES (SEARCH IN ANY LANGUAGE)",
            "Select languages to prioritize across recommendations and multilingual voice/text search.");
        String curLangs = db.getSetting("preferred_langs", "English");
        String[] langs = {"English", "Hindi", "Tamil", "Telugu", "Malayalam", "Korean", "Japanese"};
        LinearLayout langRow = new LinearLayout(ctx);
        langRow.setOrientation(LinearLayout.HORIZONTAL);
        langRow.setPadding(0, dp(ctx, 4), 0, dp(ctx, 12));
        for (String lang : langs) {
            boolean active = curLangs.toLowerCase().contains(lang.toLowerCase());
            String label = (active ? "\u2713 " : "") + lang;
            TextView pill = active
                ? GlassTheme.makePrimaryButton(ctx, label)
                : GlassTheme.makeGlassButton(ctx, label);
            pill.setTextSize(10.5f);
            pill.setPadding(dp(ctx, 12), dp(ctx, 6), dp(ctx, 12), dp(ctx, 6));
            LinearLayout.LayoutParams lp = GlassTheme.wrapParams();
            lp.rightMargin = dp(ctx, 8);
            pill.setLayoutParams(lp);
            pill.setOnClickListener(v -> {
                String updated = toggleCsvItem(curLangs, lang);
                db.setSetting("preferred_langs", updated);
                db.setProfileTaste(activeProfile, db.getProfileTaste(activeProfile));
                showAuraSettingsModal(hostContainer, ctx, db, activeProfile, remotePort, actions);
            });
            bindBackKey(pill, hostContainer, actions);
            langRow.addView(pill);
        }
        body.addView(langRow);

        // Section 4: AI Engine & Phone Web Config
        String keyVal = db.getSetting("gemini_api_key", "");
        String keyStatus = keyVal.isEmpty() ? "Offline JustWatch Graph Mode" : "Aura Agentic Engine + JustWatch Graph Active \u2713";
        addSettingSectionHeader(body, ctx, "4. AI ENGINE & MOBILE REMOTE",
            "Status: " + keyStatus + "   \u00B7   Phone Remote URL: http://"
            + QrRemoteBuilder.getTvIpAddress(ctx) + ":" + remotePort);

        LinearLayout actionRow = new LinearLayout(ctx);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        actionRow.setPadding(0, dp(ctx, 4), 0, dp(ctx, 8));

        TextView rebuildBtn = GlassTheme.makePrimaryButton(ctx, "\u2726 Save & Rebuild Recommendations Now");
        rebuildBtn.setTextSize(11f);
        rebuildBtn.setPadding(dp(ctx, 14), dp(ctx, 7), dp(ctx, 14), dp(ctx, 7));
        rebuildBtn.setOnClickListener(v -> {
            closeModal(hostContainer, actions);
            if (actions != null) actions.onRefreshRecommendations(true);
        });
        bindBackKey(rebuildBtn, hostContainer, actions);
        LinearLayout.LayoutParams rbLp = GlassTheme.wrapParams();
        rbLp.rightMargin = dp(ctx, 10);
        actionRow.addView(rebuildBtn, rbLp);

        TextView clearCacheBtn = GlassTheme.makeGlassButton(ctx, "Clear Image Cache & Reload Ultra HD");
        clearCacheBtn.setTextSize(10.5f);
        clearCacheBtn.setPadding(dp(ctx, 12), dp(ctx, 7), dp(ctx, 12), dp(ctx, 7));
        clearCacheBtn.setOnClickListener(v -> {
            ImageLoader.trimMemory();
            closeModal(hostContainer, actions);
            if (actions != null) actions.onRefreshRecommendations(false);
        });
        bindBackKey(clearCacheBtn, hostContainer, actions);
        LinearLayout.LayoutParams ccLp = GlassTheme.wrapParams();
        ccLp.rightMargin = dp(ctx, 10);
        actionRow.addView(clearCacheBtn, ccLp);

        TextView saverBtn = GlassTheme.makeGlassButton(ctx, "\u2726 Start Ambient Screensaver");
        saverBtn.setTextSize(10.5f);
        saverBtn.setPadding(dp(ctx, 12), dp(ctx, 7), dp(ctx, 12), dp(ctx, 7));
        saverBtn.setOnClickListener(v -> {
            closeModal(hostContainer, actions);
            if (actions != null) actions.onStartScreensaver();
        });
        bindBackKey(saverBtn, hostContainer, actions);
        actionRow.addView(saverBtn, GlassTheme.wrapParams());

        body.addView(actionRow);
        sv.addView(body, GlassTheme.matchWrapParams());
        panel.addView(sv, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        hostContainer.addView(panel);
        if (firstQualityBtn != null) {
            firstQualityBtn.post(firstQualityBtn::requestFocus);
        }
    }

    // ── 3. Guide AI Taste Studio (No AlertDialog!) ───────────────────────

    public static void showGuideAiModal(
            FrameLayout hostContainer, Context ctx, AuraDb db,
            String activeProfile, ModalActions actions) {
        hostContainer.removeAllViews();
        hostContainer.setVisibility(View.VISIBLE);

        LinearLayout panel = createFullGlassPanel(ctx);

        LinearLayout hdr = new LinearLayout(ctx);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER_VERTICAL);
        hdr.setPadding(0, 0, 0, dp(ctx, 12));

        String profName = db.getProfileLabel(activeProfile);
        TextView title = GlassTheme.makeText(ctx, "Guide AI Taste  \u00B7  " + profName,
            18f, GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1);
        hdr.addView(title, GlassTheme.weightParams(1f));

        TextView closeBtn = GlassTheme.makeGlassButton(ctx, "\u2715 Close");
        closeBtn.setTextSize(10.5f);
        closeBtn.setPadding(dp(ctx, 12), dp(ctx, 6), dp(ctx, 12), dp(ctx, 6));
        closeBtn.setOnClickListener(v -> closeModal(hostContainer, actions));
        bindBackKey(closeBtn, hostContainer, actions);
        hdr.addView(closeBtn, GlassTheme.wrapParams());
        panel.addView(hdr);

        final String curTaste = db.getProfileTaste(activeProfile);
        addSettingSectionHeader(panel, ctx, "SELECT YOUR VIBE PILLS (1-CLICK TOGGLE)",
            "Click any vibe below or type custom instructions in any language to steer Aura's ranking engine.");

        String[] vibes = {
            "Mind-Bending Sci-Fi",
            "Gripping Crime Thrillers",
            "Dark Psychological",
            "High-Octane Action",
            "Indian Prestige Series",
            "Slow-Burn Mystery",
            "IMDb 8.0+ Masterpieces",
            "Feel-Good & Witty"
        };

        LinearLayout row1 = new LinearLayout(ctx);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setPadding(0, dp(ctx, 4), 0, dp(ctx, 8));
        LinearLayout row2 = new LinearLayout(ctx);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, 0, 0, dp(ctx, 14));

        View firstPill = null;
        for (int i = 0; i < vibes.length; i++) {
            final String vibe = vibes[i];
            boolean active = curTaste.toLowerCase().contains(vibe.toLowerCase());
            String lbl = (active ? "\u2713 " : "+ ") + vibe;
            TextView pill = active
                ? GlassTheme.makePrimaryButton(ctx, lbl)
                : GlassTheme.makeGlassButton(ctx, lbl);
            pill.setTextSize(10.5f);
            pill.setPadding(dp(ctx, 12), dp(ctx, 6), dp(ctx, 12), dp(ctx, 6));
            LinearLayout.LayoutParams lp = GlassTheme.wrapParams();
            lp.rightMargin = dp(ctx, 8);
            pill.setLayoutParams(lp);
            pill.setOnClickListener(v -> {
                String updated = toggleCsvItem(curTaste, vibe);
                db.setProfileTaste(activeProfile, updated);
                showGuideAiModal(hostContainer, ctx, db, activeProfile, actions);
            });
            bindBackKey(pill, hostContainer, actions);
            if (i < 4) row1.addView(pill);
            else row2.addView(pill);
            if (firstPill == null) firstPill = pill;
        }
        panel.addView(row1);
        panel.addView(row2);

        addSettingSectionHeader(panel, ctx, "CUSTOM TASTE INSTRUCTIONS (OPTIONAL)",
            "Current profile taste: " + (curTaste.isEmpty() ? "Default Modern Prestige" : curTaste));

        LinearLayout inputBox = buildClickToEditBox(ctx, curTaste,
            "Press OK to type custom taste (e.g. 'Thrillers like Drishyam, no horror')\u2026",
            newText -> {
                db.setProfileTaste(activeProfile, newText);
                showGuideAiModal(hostContainer, ctx, db, activeProfile, actions);
            }, hostContainer, actions);
        panel.addView(inputBox);

        LinearLayout bottomRow = new LinearLayout(ctx);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        bottomRow.setPadding(0, dp(ctx, 16), 0, 0);

        TextView saveBtn = GlassTheme.makePrimaryButton(ctx, "\u2726 Apply Taste & Re-Rank Home");
        saveBtn.setTextSize(11f);
        saveBtn.setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 16), dp(ctx, 8));
        saveBtn.setOnClickListener(v -> {
            closeModal(hostContainer, actions);
            if (actions != null) actions.onRefreshRecommendations(true);
        });
        bindBackKey(saveBtn, hostContainer, actions);
        LinearLayout.LayoutParams sLp = GlassTheme.wrapParams();
        sLp.rightMargin = dp(ctx, 10);
        bottomRow.addView(saveBtn, sLp);

        TextView resetBtn = GlassTheme.makeGlassButton(ctx, "Reset to Default");
        resetBtn.setTextSize(10.5f);
        resetBtn.setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8));
        resetBtn.setOnClickListener(v -> {
            db.setProfileTaste(activeProfile, "");
            showGuideAiModal(hostContainer, ctx, db, activeProfile, actions);
        });
        bindBackKey(resetBtn, hostContainer, actions);
        bottomRow.addView(resetBtn, GlassTheme.wrapParams());

        panel.addView(bottomRow);
        hostContainer.addView(panel);
        if (firstPill != null) firstPill.post(firstPill::requestFocus);
    }

    // ── 4. Add Profile Glass Modal (No AlertDialog!) ─────────────────────

    public static void showAddProfileModal(
            FrameLayout hostContainer, Context ctx, AuraDb db, ModalActions actions) {
        hostContainer.removeAllViews();
        hostContainer.setVisibility(View.VISIBLE);

        LinearLayout panel = createFullGlassPanel(ctx);

        LinearLayout hdr = new LinearLayout(ctx);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER_VERTICAL);
        hdr.setPadding(0, 0, 0, dp(ctx, 14));

        TextView title = GlassTheme.makeText(ctx, "Add New Profile", 18f,
            GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1);
        hdr.addView(title, GlassTheme.weightParams(1f));

        TextView closeBtn = GlassTheme.makeGlassButton(ctx, "\u2715 Close");
        closeBtn.setTextSize(10.5f);
        closeBtn.setPadding(dp(ctx, 12), dp(ctx, 6), dp(ctx, 12), dp(ctx, 6));
        closeBtn.setOnClickListener(v -> closeModal(hostContainer, actions));
        bindBackKey(closeBtn, hostContainer, actions);
        hdr.addView(closeBtn, GlassTheme.wrapParams());
        panel.addView(hdr);

        addSettingSectionHeader(panel, ctx, "1-CLICK PROFILE PRESETS",
            "Create a profile with one click or type a custom name below.");

        String[] presets = {"Family", "Kids", "Partner", "Parents", "Guest"};
        LinearLayout presetRow = new LinearLayout(ctx);
        presetRow.setOrientation(LinearLayout.HORIZONTAL);
        presetRow.setPadding(0, dp(ctx, 6), 0, dp(ctx, 16));
        View firstBtn = null;
        for (String p : presets) {
            TextView pill = GlassTheme.makeGlassButton(ctx, "+ " + p);
            pill.setTextSize(11f);
            pill.setPadding(dp(ctx, 14), dp(ctx, 7), dp(ctx, 14), dp(ctx, 7));
            LinearLayout.LayoutParams lp = GlassTheme.wrapParams();
            lp.rightMargin = dp(ctx, 10);
            pill.setLayoutParams(lp);
            pill.setOnClickListener(v -> {
                String id = db.addProfile(p, "");
                closeModal(hostContainer, actions);
                if (actions != null) actions.onProfileCreated(id);
            });
            bindBackKey(pill, hostContainer, actions);
            presetRow.addView(pill);
            if (firstBtn == null) firstBtn = pill;
        }
        panel.addView(presetRow);

        addSettingSectionHeader(panel, ctx, "OR TYPE CUSTOM PROFILE NAME", "");
        LinearLayout customBox = buildClickToEditBox(ctx, "",
            "Press OK to type a custom profile name\u2026",
            name -> {
                if (!name.trim().isEmpty()) {
                    String id = db.addProfile(name.trim(), "");
                    closeModal(hostContainer, actions);
                    if (actions != null) actions.onProfileCreated(id);
                }
            }, hostContainer, actions);
        panel.addView(customBox);

        hostContainer.addView(panel);
        if (firstBtn != null) firstBtn.post(firstBtn::requestFocus);
    }

    // ── 5. Phone QR Remote Glass Modal (No AlertDialog!) ─────────────────

    public static void showQrRemoteModal(
            FrameLayout hostContainer, Context ctx, String ip, int port, ModalActions actions) {
        hostContainer.removeAllViews();
        hostContainer.setVisibility(View.VISIBLE);

        FrameLayout backdrop = new FrameLayout(ctx);
        backdrop.setBackgroundColor(GlassTheme.BG_AMBIENT);
        backdrop.setClickable(true);
        backdrop.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout wrapper = new LinearLayout(ctx);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams wLp = new FrameLayout.LayoutParams(
            dp(ctx, 420), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        wrapper.setLayoutParams(wLp);

        View qrCard = QrRemoteBuilder.buildQrCard(ctx, ip, port);
        qrCard.setFocusable(false);
        wrapper.addView(qrCard, GlassTheme.matchWrapParams());

        TextView closeBtn = GlassTheme.makePrimaryButton(ctx, "\u2713 Done");
        closeBtn.setTextSize(11.5f);
        closeBtn.setPadding(dp(ctx, 24), dp(ctx, 8), dp(ctx, 24), dp(ctx, 8));
        LinearLayout.LayoutParams cLp = GlassTheme.wrapParams();
        cLp.topMargin = dp(ctx, 12);
        closeBtn.setLayoutParams(cLp);
        closeBtn.setOnClickListener(v -> closeModal(hostContainer, actions));
        bindBackKey(closeBtn, hostContainer, actions);
        wrapper.addView(closeBtn);

        backdrop.addView(wrapper);
        hostContainer.addView(backdrop);
        closeBtn.post(closeBtn::requestFocus);
    }

    // ── Shared Glass UI Helpers ──────────────────────────────────────────

    private interface OptionSelectCallback {
        void onSelect(String key);
    }

    private interface TextSubmitCallback {
        void onSubmit(String text);
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
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(ctx, 4), 0, dp(ctx, 12));
        View first = null;
        for (String[] opt : options) {
            final String key = opt[0];
            boolean isSel = key.equalsIgnoreCase(activeKey);
            String label = (isSel ? "\u25CF " : "\u25CB ") + opt[1];
            TextView pill = isSel
                ? GlassTheme.makePrimaryButton(ctx, label)
                : GlassTheme.makeGlassButton(ctx, label);
            pill.setTextSize(10.5f);
            pill.setPadding(dp(ctx, 13), dp(ctx, 6), dp(ctx, 13), dp(ctx, 6));
            LinearLayout.LayoutParams lp = GlassTheme.wrapParams();
            lp.rightMargin = dp(ctx, 8);
            pill.setLayoutParams(lp);
            pill.setOnClickListener(v -> cb.onSelect(key));
            bindBackKey(pill, hostContainer, actions);
            row.addView(pill);
            if (first == null) first = pill;
        }
        parent.addView(row);
        return first;
    }

    private static LinearLayout buildClickToEditBox(
            Context ctx, String initialText, String hint,
            TextSubmitCallback onSubmit, FrameLayout hostContainer, ModalActions actions) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        float r = dp(ctx, 14);
        box.setBackground(GlassTheme.focusRounded(
            GlassTheme.SURFACE_ELEV, GlassTheme.BORDER_STRONG, dp(ctx, 1), r));
        box.setPadding(dp(ctx, 14), dp(ctx, 8), dp(ctx, 14), dp(ctx, 8));
        box.setFocusable(true);
        box.setClickable(true);

        EditText et = new EditText(ctx);
        et.setText(initialText);
        et.setHint(hint);
        et.setHintTextColor(GlassTheme.TEXT_MUTED);
        et.setTextColor(GlassTheme.TEXT_PRIMARY);
        et.setTextSize(12f);
        et.setSingleLine(true);
        et.setBackground(null);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        et.setFocusable(false);
        et.setFocusableInTouchMode(false);
        box.addView(et, GlassTheme.weightParams(1f));

        box.setOnClickListener(v -> {
            et.setFocusableInTouchMode(true);
            et.setFocusable(true);
            et.requestFocus();
            et.setSelection(et.getText().length());
            try {
                InputMethodManager imm = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT);
            } catch (Exception ignored) {}
        });

        et.setOnEditorActionListener((v, actionId, ev) -> {
            try {
                InputMethodManager imm = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(et.getWindowToken(), 0);
            } catch (Exception ignored) {}
            et.setFocusable(false);
            et.setFocusableInTouchMode(false);
            onSubmit.onSubmit(et.getText().toString());
            return true;
        });

        box.setOnFocusChangeListener((v, focused) -> {
            int stroke = focused ? Color.WHITE : GlassTheme.BORDER_STRONG;
            int sw = focused ? dp(ctx, 2.5f) : dp(ctx, 1);
            v.setBackground(GlassTheme.focusRounded(GlassTheme.SURFACE_ELEV, stroke, sw, r));
        });
        bindBackKey(box, hostContainer, actions);
        return box;
    }

    private static String toggleCsvItem(String csv, String item) {
        List<String> parts = new ArrayList<>();
        if (csv != null && !csv.trim().isEmpty()) {
            for (String s : csv.split(",")) {
                String clean = s.trim();
                if (!clean.isEmpty()) parts.add(clean);
            }
        }
        boolean removed = parts.removeIf(p -> p.equalsIgnoreCase(item));
        if (!removed) parts.add(item);
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
