package com.aura.tvprobe;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Uncluttered Frosted Dark-Glass Left Navigation Drawer for Aura TV.
 * Uses a compact horizontal profile avatar strip at the top and a 2-column
 * HDMI row at the bottom so every AI, navigation, and TV option fits cleanly
 * on a 1080p TV viewport with strict D-pad focus containment.
 */
public final class NavPanelBuilder {

    private static final String TAG_DRAWER = "nav_drawer";
    private static final String TAG_SCROLL = "nav_scroll";
    private static final String TAG_HAMBURGER = "nav_hamburger";
    private static final String TAG_PROFILES = "nav_profiles";
    private static final String TAG_PROFILE_SUB = "nav_profile_sub";
    private static final String TAG_STATUS = "nav_status";
    private static final String TAG_FIRST_ITEM = "nav_first_item";

    private static final int DRAWER_WIDTH_DP = 276;

    private static final class PanelState {
        boolean expanded = false;
        NavCallback callback;
        View mainContentToAnimate;
        String activeProfileName = "";
    }

    public interface NavCallback {
        void onProfileSelected(String profileId);
        void onAddProfile();
        void onNavigate(String destination);
        void onHdmiSelect(int port);
        void onSettingsOpen();
        void onQrOpen();
        void onWatchHistoryOpen();
        default void onDrawerCollapsed() {}
    }

    public static FrameLayout build(Context ctx, NavCallback callback) {
        FrameLayout wrapper = new FrameLayout(ctx);
        wrapper.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        wrapper.setClipChildren(false);
        wrapper.setClipToPadding(false);
        PanelState st = new PanelState();
        st.callback = callback;
        wrapper.setTag(st);
        wrapper.addView(buildExpandedDrawer(wrapper, ctx, callback));
        return wrapper;
    }

    public static TextView createTopBarHamburger(FrameLayout wrapper, Context ctx) {
        TextView btn = buildHamburgerButton(wrapper, ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(ctx, 38), dp(ctx, 36));
        lp.rightMargin = dp(ctx, 10);
        btn.setLayoutParams(lp);
        return btn;
    }

    public static void bindMainContentForAnimation(FrameLayout wrapper, View mainContent) {
        getState(wrapper).mainContentToAnimate = mainContent;
    }

    private static final android.view.animation.Interpolator SLIDE_EASE =
        new android.view.animation.PathInterpolator(0.22f, 1f, 0.36f, 1f);

    public static void setExpanded(FrameLayout wrapper, boolean expanded, Context ctx) {
        setExpanded(wrapper, expanded, ctx, true);
    }

    public static void setExpanded(FrameLayout wrapper, boolean expanded, Context ctx, boolean notifyCollapse) {
        PanelState st = getState(wrapper);
        if (st.expanded == expanded) return;
        st.expanded = expanded;
        LinearLayout drawer = findByTag(wrapper, TAG_DRAWER);
        if (drawer == null) return;
        float hiddenX = -dp(ctx, DRAWER_WIDTH_DP + 28);
        if (expanded) expandDrawerView(st, drawer, hiddenX, ctx);
        else collapseDrawerView(st, drawer, hiddenX, notifyCollapse);
    }

    private static void expandDrawerView(PanelState st, LinearLayout drawer, float hiddenX, Context ctx) {
        prepareDrawerForExpand(drawer, hiddenX);
        refreshLiveTelemetryBadge(drawer, ctx);
        setMainDescendantFocusability(st.mainContentToAnimate, ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        drawer.animate().translationX(0f).alpha(1f).setDuration(360).setInterpolator(SLIDE_EASE).start();
        animateMainContent(st.mainContentToAnimate, dp(ctx, DRAWER_WIDTH_DP + 14), 0.96f);
        View first = findByTag(drawer, TAG_FIRST_ITEM);
        if (first != null) first.requestFocus();
    }

    private static void prepareDrawerForExpand(LinearLayout drawer, float hiddenX) {
        drawer.animate().cancel();
        if (drawer.getVisibility() != View.VISIBLE) {
            drawer.setTranslationX(hiddenX);
            drawer.setAlpha(0f);
            drawer.setVisibility(View.VISIBLE);
        }
    }

    private static void setMainDescendantFocusability(View main, int focusability) {
        if (main instanceof ViewGroup) ((ViewGroup) main).setDescendantFocusability(focusability);
    }

    private static void collapseDrawerView(PanelState st, LinearLayout drawer, float hiddenX, boolean notifyCollapse) {
        setMainDescendantFocusability(st.mainContentToAnimate, ViewGroup.FOCUS_AFTER_DESCENDANTS);
        drawer.animate().cancel();
        animateMainContent(st.mainContentToAnimate, 0, 1.0f);
        drawer.animate().translationX(hiddenX).alpha(0f).setDuration(320).setInterpolator(SLIDE_EASE)
            .withEndAction(() -> drawer.setVisibility(View.GONE)).start();
        if (notifyCollapse && st.callback != null) st.callback.onDrawerCollapsed();
    }

    private static void animateMainContent(View main, float targetTransX, float targetScale) {
        if (main == null) return;
        main.animate().cancel();
        main.animate().translationX(targetTransX).scaleX(targetScale).scaleY(targetScale)
            .setDuration(360).setInterpolator(SLIDE_EASE).start();
    }

    public static boolean isExpanded(FrameLayout wrapper) {
        return getState(wrapper).expanded;
    }

    public static void focusHamburgerOrExpand(FrameLayout wrapper, Context ctx) {
        setExpanded(wrapper, true, ctx);
    }

    public static void updateProfiles(FrameLayout wrapper, List<JSONObject> profiles,
                                      String activeProfileId, Context ctx) {
        LinearLayout strip = findByTag(wrapper, TAG_PROFILES);
        if (strip == null) return;
        strip.removeAllViews();
        NavCallback cb = getState(wrapper).callback;
        String activeName = populateProfileStrip(wrapper, strip, profiles, activeProfileId, ctx, cb);
        getState(wrapper).activeProfileName = activeName;
        setProfileSubtext(wrapper, activeName.isEmpty() ? "Select Profile" : (activeName + " \u00B7 Active"));
    }

    private static String populateProfileStrip(
            FrameLayout wrapper, LinearLayout strip, List<JSONObject> profiles,
            String activeId, Context ctx, NavCallback cb) {
        String activeName = "";
        for (JSONObject p : profiles) {
            String id = p.optString("id", "");
            String name = p.optString("name", "User");
            boolean active = id.equals(activeId);
            if (active) activeName = name;
            strip.addView(buildProfileChip(wrapper, ctx, id, name, active, cb));
        }
        strip.addView(buildAddProfileChip(wrapper, ctx, cb));
        return activeName;
    }

    public static void updateStatus(FrameLayout wrapper, String serverStatus, String ram, String remotePort) {
        TextView badge = findByTag(wrapper, TAG_STATUS);
        if (badge != null) refreshLiveTelemetryBadge(wrapper, badge.getContext());
    }

    private static void refreshLiveTelemetryBadge(View root, Context ctx) {
        TextView badge = findByTag(root, TAG_STATUS);
        if (badge == null) return;
        JSONObject snap = SystemResourceMonitor.collectLightweightTelemetry(ctx, null);
        badge.setText(snap.optInt("avail_ram_mb", 400) + "M \u00B7 " + snap.optInt("cpu_load_pct", 8) + "%");
    }

    private static PanelState getState(FrameLayout wrapper) {
        if (wrapper.getTag() instanceof PanelState) return (PanelState) wrapper.getTag();
        PanelState st = new PanelState();
        wrapper.setTag(st);
        return st;
    }

    private static TextView buildHamburgerButton(FrameLayout wrapper, Context ctx) {
        TextView btn = GlassTheme.makeText(ctx, "\u2630", 16f, GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1);
        btn.setGravity(Gravity.CENTER);
        btn.setTag(TAG_HAMBURGER);
        float r = dp(ctx, 18);
        btn.setBackground(GlassTheme.focusRounded(GlassTheme.SURFACE_ELEV, GlassTheme.BORDER_STRONG, dp(ctx, 1), r));
        btn.setElevation(dp(ctx, 6));
        bindHamburgerInteractions(wrapper, btn, ctx, r);
        return btn;
    }

    private static void bindHamburgerInteractions(FrameLayout wrapper, TextView btn, Context ctx, float r) {
        btn.setFocusable(true);
        btn.setClickable(true);
        btn.setOnClickListener(v -> setExpanded(wrapper, true, ctx));
        btn.setOnFocusChangeListener((v, focused) -> updateHamburgerFocus(ctx, btn, v, focused, r));
    }

    private static void updateHamburgerFocus(Context ctx, TextView btn, View v, boolean focused, float r) {
        btn.setBackground(focused
            ? GlassTheme.focusRounded(Color.WHITE, Color.WHITE, dp(ctx, 2), r)
            : GlassTheme.focusRounded(GlassTheme.SURFACE_ELEV, GlassTheme.BORDER_STRONG, dp(ctx, 1), r));
        btn.setTextColor(focused ? GlassTheme.TEXT_ON_LIGHT : GlassTheme.TEXT_PRIMARY);
        float scale = focused ? 1.06f : 1f;
        v.animate().scaleX(scale).scaleY(scale).setDuration(150).start();
    }

    private static LinearLayout buildExpandedDrawer(FrameLayout wrapper, Context ctx, NavCallback cb) {
        LinearLayout drawer = createDrawerContainer(ctx);
        ScrollView sv = createDrawerScrollView(ctx);
        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10));
        populateDrawerItems(wrapper, content, ctx, cb);
        sv.addView(content, GlassTheme.matchWrapParams());
        drawer.addView(sv, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return drawer;
    }

    private static LinearLayout createDrawerContainer(Context ctx) {
        LinearLayout drawer = new LinearLayout(ctx);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setTag(TAG_DRAWER);
        drawer.setVisibility(View.GONE);
        drawer.setTranslationX(-dp(ctx, DRAWER_WIDTH_DP + 28));
        drawer.setAlpha(0f);
        styleDrawerSurface(ctx, drawer);
        return drawer;
    }

    private static void styleDrawerSurface(Context ctx, LinearLayout drawer) {
        GradientDrawable bg = GlassTheme.solidRounded(GlassTheme.GLASS_PANEL, dp(ctx, 18));
        bg.setStroke(dp(ctx, 1), GlassTheme.BORDER_STRONG);
        drawer.setBackground(bg);
        drawer.setElevation(dp(ctx, 28));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            dp(ctx, DRAWER_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT);
        lp.setMargins(dp(ctx, 12), dp(ctx, 10), 0, dp(ctx, 10));
        drawer.setLayoutParams(lp);
    }

    private static ScrollView createDrawerScrollView(Context ctx) {
        ScrollView sv = new ScrollView(ctx);
        sv.setTag(TAG_SCROLL);
        sv.setVerticalScrollBarEnabled(true);
        sv.setScrollbarFadingEnabled(false);
        sv.setFillViewport(true);
        sv.setFocusable(false);
        return sv;
    }

    private static void populateDrawerItems(FrameLayout wrapper, LinearLayout content,
                                            Context ctx, NavCallback cb) {
        addDrawerHeader(content, ctx);
        addProfilesStripSection(content, ctx);
        addDivider(content, ctx);
        addAiDiscoverySection(wrapper, content, ctx, cb);
        addDivider(content, ctx);
        addSystemAndInputsSection(wrapper, content, ctx, cb);
    }

    private static void addDrawerHeader(LinearLayout content, Context ctx) {
        LinearLayout hdr = new LinearLayout(ctx);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER_VERTICAL);
        hdr.setPadding(dp(ctx, 4), 0, dp(ctx, 4), dp(ctx, 4));
        hdr.addView(GlassTheme.makeText(ctx, "AURA TV", 12f,
            GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1), GlassTheme.weightParams(1f));
        hdr.addView(GlassTheme.makeText(ctx, "\u2192 Close", 9f,
            GlassTheme.TEXT_MUTED, GlassTheme.FONT_MEDIUM, 1), GlassTheme.wrapParams());
        content.addView(hdr, GlassTheme.matchWrapParams());
    }

    private static void addProfilesStripSection(LinearLayout content, Context ctx) {
        LinearLayout strip = new LinearLayout(ctx);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        strip.setPadding(dp(ctx, 2), dp(ctx, 2), dp(ctx, 2), dp(ctx, 2));
        strip.setTag(TAG_PROFILES);
        content.addView(strip, GlassTheme.matchWrapParams());
        TextView sub = GlassTheme.makeText(ctx, "Profiles", 9.5f, GlassTheme.GOLD_ACCENT, GlassTheme.FONT_MEDIUM, 1);
        sub.setPadding(dp(ctx, 6), dp(ctx, 2), 0, dp(ctx, 2));
        sub.setTag(TAG_PROFILE_SUB);
        content.addView(sub, GlassTheme.wrapParams());
    }

    private static void addAiDiscoverySection(FrameLayout wrapper, LinearLayout content,
                                              Context ctx, NavCallback cb) {
        addSectionLabel(content, ctx, "AI & DISCOVERY");
        addNavItem(wrapper, content, ctx, "\u2302", "Home", true, null, v -> closeAndRun(wrapper, ctx, true, () -> cb.onNavigate("home")));
        addNavItem(wrapper, content, ctx, "\u2726", "Ask Aura AI", false, null, v -> closeAndRun(wrapper, ctx, false, () -> cb.onNavigate("ask_ai")));
        addNavItem(wrapper, content, ctx, "\u2605", "Movie Night Blend", false, null, v -> closeAndRun(wrapper, ctx, false, () -> cb.onNavigate("movie_blend")));
        addNavItem(wrapper, content, ctx, "\u25B6", "Surprise Me", false, null, v -> closeAndRun(wrapper, ctx, false, () -> cb.onNavigate("surprise_me")));
        addNavItem(wrapper, content, ctx, "\u25F7", "Watch History", false, null, v -> closeAndRun(wrapper, ctx, false, cb::onWatchHistoryOpen));
        addNavItem(wrapper, content, ctx, "\u270E", "Guide AI Taste", false, null, v -> closeAndRun(wrapper, ctx, false, () -> cb.onNavigate("guide_ai")));
        addNavItem(wrapper, content, ctx, "\u21BB", "Refresh Picks", false, null, v -> closeAndRun(wrapper, ctx, true, () -> cb.onNavigate("refresh_recos")));
    }

    private static void addSystemAndInputsSection(FrameLayout wrapper, LinearLayout content,
                                                  Context ctx, NavCallback cb) {
        addSectionLabel(content, ctx, "SYSTEM & INPUTS");
        addNavItem(wrapper, content, ctx, "\u26A1", "RAM & CPU", false, TAG_STATUS, v -> closeAndRun(wrapper, ctx, false, () -> cb.onNavigate("ram_guardian")));
        addNavItem(wrapper, content, ctx, "\u229E", "Phone Remote (QR)", false, null, v -> closeAndRun(wrapper, ctx, false, cb::onQrOpen));
        addNavItem(wrapper, content, ctx, "\u2699", "Aura Settings", false, null, v -> closeAndRun(wrapper, ctx, false, cb::onSettingsOpen));
        addNavItem(wrapper, content, ctx, "\u2637", "Android TV Settings", false, null, v -> closeAndRun(wrapper, ctx, true, () -> cb.onNavigate("sys_tv_settings")));
        addNavItem(wrapper, content, ctx, "\u224B", "Wi-Fi & Network", false, null, v -> closeAndRun(wrapper, ctx, true, () -> cb.onNavigate("sys_wi-fi")));
        addHdmiPairRow(wrapper, content, ctx, cb);
    }

    private static void addHdmiPairRow(FrameLayout wrapper, LinearLayout content, Context ctx, NavCallback cb) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(ctx, 2), dp(ctx, 4), dp(ctx, 2), dp(ctx, 2));
        row.addView(buildCompactPill(wrapper, ctx, "HDMI 1", true, v -> closeAndRun(wrapper, ctx, true, () -> cb.onHdmiSelect(1))), halfRowParams(ctx, true));
        row.addView(buildCompactPill(wrapper, ctx, "HDMI 2", false, v -> closeAndRun(wrapper, ctx, true, () -> cb.onHdmiSelect(2))), halfRowParams(ctx, false));
        content.addView(row, GlassTheme.matchWrapParams());
    }

    private static LinearLayout.LayoutParams halfRowParams(Context ctx, boolean left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(ctx, 30), 1f);
        if (left) lp.rightMargin = dp(ctx, 6);
        return lp;
    }

    private static TextView buildCompactPill(FrameLayout wrapper, Context ctx, String label, boolean isLeft, View.OnClickListener click) {
        TextView btn = GlassTheme.makeText(ctx, label, 10f, GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1);
        btn.setGravity(Gravity.CENTER);
        btn.setBackground(GlassTheme.solidRounded(GlassTheme.SURFACE_ELEV, dp(ctx, 8)));
        btn.setFocusable(true);
        btn.setClickable(true);
        btn.setOnClickListener(click);
        btn.setOnFocusChangeListener((v, f) -> styleCompactPillFocus(btn, f, ctx));
        btn.setOnKeyListener((v, kc, ev) -> handleHorizontalItemKey(wrapper, v, kc, ev, isLeft, !isLeft, ctx));
        return btn;
    }

    private static void styleCompactPillFocus(TextView btn, boolean focused, Context ctx) {
        btn.setBackground(focused ? GlassTheme.focusRounded(Color.WHITE, Color.WHITE, dp(ctx, 2), dp(ctx, 8))
            : GlassTheme.solidRounded(GlassTheme.SURFACE_ELEV, dp(ctx, 8)));
        btn.setTextColor(focused ? GlassTheme.TEXT_ON_LIGHT : GlassTheme.TEXT_PRIMARY);
    }

    private static TextView buildProfileChip(
            FrameLayout wrapper, Context ctx, String id, String name, boolean active, NavCallback cb) {
        String initial = name.isEmpty() ? "U" : name.substring(0, 1).toUpperCase();
        TextView chip = createProfileChipView(ctx, initial, active);
        chip.setOnClickListener(v -> closeAndRun(wrapper, ctx, true, () -> { if (cb != null) cb.onProfileSelected(id); }));
        chip.setOnFocusChangeListener((v, f) -> onProfileChipFocus(wrapper, chip, name, active, f, ctx));
        chip.setOnKeyListener((v, kc, ev) -> handleProfileStripKey(wrapper, v, kc, ev, ctx));
        return chip;
    }

    private static TextView buildAddProfileChip(FrameLayout wrapper, Context ctx, NavCallback cb) {
        TextView chip = createProfileChipView(ctx, "+", false);
        chip.setOnClickListener(v -> closeAndRun(wrapper, ctx, false, () -> { if (cb != null) cb.onAddProfile(); }));
        chip.setOnFocusChangeListener((v, f) -> onProfileChipFocus(wrapper, chip, "Add New Profile", false, f, ctx));
        chip.setOnKeyListener((v, kc, ev) -> handleProfileStripKey(wrapper, v, kc, ev, ctx));
        return chip;
    }

    private static TextView createProfileChipView(Context ctx, String text, boolean active) {
        TextView tv = GlassTheme.makeText(ctx, text, 11f, active ? GlassTheme.TEXT_ON_LIGHT : GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(GlassTheme.solidRounded(active ? GlassTheme.GOLD_ACCENT : GlassTheme.SURFACE_ELEV, dp(ctx, 15)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(ctx, 30), dp(ctx, 30));
        lp.rightMargin = dp(ctx, 7);
        tv.setLayoutParams(lp);
        tv.setFocusable(true);
        tv.setClickable(true);
        return tv;
    }

    private static void onProfileChipFocus(
            FrameLayout wrapper, TextView chip, String name, boolean active, boolean focused, Context ctx) {
        int bg = focused ? Color.WHITE : (active ? GlassTheme.GOLD_ACCENT : GlassTheme.SURFACE_ELEV);
        int fg = (focused || active) ? GlassTheme.TEXT_ON_LIGHT : GlassTheme.TEXT_PRIMARY;
        chip.setBackground(GlassTheme.solidRounded(bg, dp(ctx, 15)));
        chip.setTextColor(fg);
        float s = focused ? 1.12f : 1f;
        chip.animate().scaleX(s).scaleY(s).setDuration(130).start();
        String activeName = getState(wrapper).activeProfileName;
        setProfileSubtext(wrapper, focused ? name : (activeName.isEmpty() ? "Profiles" : (activeName + " \u00B7 Active")));
    }

    private static void setProfileSubtext(FrameLayout wrapper, String text) {
        TextView sub = findByTag(wrapper, TAG_PROFILE_SUB);
        if (sub != null) sub.setText(text);
    }

    private static boolean handleProfileStripKey(FrameLayout wrapper, View v, int keyCode, KeyEvent ev, Context ctx) {
        if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (keyCode == KeyEvent.KEYCODE_BACK) { setExpanded(wrapper, false, ctx); return true; }
        ViewGroup strip = (ViewGroup) v.getParent();
        int idx = strip != null ? strip.indexOfChild(v) : -1;
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { if (idx > 0) strip.getChildAt(idx - 1).requestFocus(); return true; }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) return moveRightOrClose(wrapper, strip, idx, ctx);
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) { View first = findByTag(wrapper, TAG_FIRST_ITEM); if (first != null) first.requestFocus(); return true; }
        return keyCode == KeyEvent.KEYCODE_DPAD_UP;
    }

    private static boolean moveRightOrClose(FrameLayout wrapper, ViewGroup row, int idx, Context ctx) {
        if (row != null && idx >= 0 && idx + 1 < row.getChildCount()) row.getChildAt(idx + 1).requestFocus();
        else setExpanded(wrapper, false, ctx);
        return true;
    }

    private static boolean handleHorizontalItemKey(
            FrameLayout wrapper, View v, int keyCode, KeyEvent ev, boolean isFirstInRow, boolean isLastInRow, Context ctx) {
        if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (keyCode == KeyEvent.KEYCODE_BACK) { setExpanded(wrapper, false, ctx); return true; }
        ViewGroup row = (ViewGroup) v.getParent();
        int idx = row != null ? row.indexOfChild(v) : -1;
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { if (!isFirstInRow && idx > 0) row.getChildAt(idx - 1).requestFocus(); return true; }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) return isLastInRow ? closeDrawerTrue(wrapper, ctx) : moveRightOrClose(wrapper, row, idx, ctx);
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return navigateDrawerVertically(wrapper, row, -1, ctx);
        return keyCode == KeyEvent.KEYCODE_DPAD_DOWN;
    }

    private static boolean closeDrawerTrue(FrameLayout wrapper, Context ctx) {
        setExpanded(wrapper, false, ctx);
        return true;
    }

    private static void closeAndRun(FrameLayout wrapper, Context ctx, boolean notifyCollapse, Runnable action) {
        setExpanded(wrapper, false, ctx, notifyCollapse);
        if (action != null) action.run();
    }

    private static void addSectionLabel(LinearLayout content, Context ctx, String text) {
        TextView lbl = GlassTheme.makeText(ctx, text, 8f, GlassTheme.TEXT_MUTED, GlassTheme.FONT_BOLD, 1);
        lbl.setLetterSpacing(0.10f);
        lbl.setPadding(dp(ctx, 8), dp(ctx, 3), 0, dp(ctx, 2));
        content.addView(lbl, GlassTheme.wrapParams());
    }

    private static void addNavItem(FrameLayout wrapper, LinearLayout parent, Context ctx,
                                   String badgeText, String label, boolean isFirst,
                                   String rightBadgeTag, View.OnClickListener click) {
        LinearLayout row = createNavRowContainer(ctx);
        if (isFirst) row.setTag(TAG_FIRST_ITEM);
        row.addView(createIconPill(ctx, badgeText));
        TextView labelTv = GlassTheme.makeText(ctx, label, 10.8f, GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1);
        labelTv.setPadding(dp(ctx, 9), 0, 0, 0);
        row.addView(labelTv, GlassTheme.weightParams(1f));
        if (rightBadgeTag != null) row.addView(createRightBadge(ctx, rightBadgeTag), GlassTheme.wrapParams());
        bindRowFocusAndClick(wrapper, row, labelTv, click, ctx);
        parent.addView(row, itemParams(ctx));
    }

    private static TextView createRightBadge(Context ctx, String tag) {
        TextView tv = GlassTheme.makeText(ctx, "", 8.5f, GlassTheme.TEXT_MUTED, GlassTheme.FONT_MEDIUM, 1);
        tv.setTag(tag);
        return tv;
    }

    private static LinearLayout createNavRowContainer(Context ctx) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 10), dp(ctx, 4));
        return row;
    }

    private static TextView createIconPill(Context ctx, String badgeText) {
        TextView tv = GlassTheme.makeText(ctx, badgeText, 10f, GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1);
        tv.setGravity(Gravity.CENTER);
        int s = dp(ctx, 22);
        tv.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        tv.setBackground(GlassTheme.solidRounded(GlassTheme.SURFACE_ELEV, dp(ctx, 6)));
        return tv;
    }

    private static void bindRowFocusAndClick(FrameLayout wrapper, LinearLayout row, TextView labelView,
                                             View.OnClickListener click, Context ctx) {
        row.setFocusable(true);
        row.setClickable(true);
        if (click != null) row.setOnClickListener(click);
        row.setOnKeyListener((v, keyCode, ev) -> handleDrawerRowKey(wrapper, v, keyCode, ev, ctx));
        row.setOnFocusChangeListener((v, focused) -> updateDrawerRowFocus(row, labelView, focused, ctx));
    }

    private static boolean handleDrawerRowKey(FrameLayout wrapper, View v, int keyCode, KeyEvent ev, Context ctx) {
        if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_BACK) return closeDrawerTrue(wrapper, ctx);
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return true;
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            return navigateDrawerVertically(wrapper, v, keyCode == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1, ctx);
        }
        return false;
    }

    private static boolean navigateDrawerVertically(FrameLayout wrapper, View current, int delta, Context ctx) {
        LinearLayout drawer = findByTag(wrapper, TAG_DRAWER);
        if (drawer == null) return true;
        List<View> rows = new ArrayList<>();
        collectVerticalStops(drawer, rows);
        int curIdx = rows.indexOf(current);
        int nextIdx = curIdx + delta;
        if (curIdx >= 0 && nextIdx >= 0 && nextIdx < rows.size()) focusStop(wrapper, rows.get(nextIdx), ctx);
        return true;
    }

    private static void focusStop(FrameLayout wrapper, View stop, Context ctx) {
        View target = (stop instanceof LinearLayout && !stop.isFocusable() && ((LinearLayout) stop).getChildCount() > 0)
            ? ((LinearLayout) stop).getChildAt(0) : stop;
        target.requestFocus();
        scrollDrawerToRow(wrapper, target, ctx);
    }

    private static void collectVerticalStops(View root, List<View> out) {
        if (root == null || root.getVisibility() != View.VISIBLE) return;
        if (TAG_PROFILES.equals(root.getTag()) || isHdmiRow(root)) { out.add(root); return; }
        if (root.isFocusable() && root instanceof LinearLayout) { out.add(root); return; }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) collectVerticalStops(g.getChildAt(i), out);
        }
    }

    private static boolean isHdmiRow(View v) {
        if (!(v instanceof LinearLayout) || v.isFocusable()) return false;
        LinearLayout ll = (LinearLayout) v;
        return ll.getOrientation() == LinearLayout.HORIZONTAL && ll.getChildCount() == 2 && ll.getChildAt(0).isFocusable();
    }

    private static void updateDrawerRowFocus(LinearLayout row, TextView labelView, boolean focused, Context ctx) {
        row.setBackground(focused ? GlassTheme.focusRounded(Color.WHITE, Color.WHITE, dp(ctx, 2), dp(ctx, 9)) : null);
        labelView.setTextColor(focused ? GlassTheme.TEXT_ON_LIGHT : GlassTheme.TEXT_PRIMARY);
        float scale = focused ? 1.02f : 1f;
        row.animate().scaleX(scale).scaleY(scale).setDuration(130).start();
    }

    private static void scrollDrawerToRow(FrameLayout wrapper, View target, Context ctx) {
        ScrollView sv = findByTag(wrapper, TAG_SCROLL);
        if (sv == null) return;
        int desiredScrollY = Math.max(0, computeRelativeTop(target, sv) - dp(ctx, 160));
        ObjectAnimator anim = ObjectAnimator.ofInt(sv, "scrollY", sv.getScrollY(), desiredScrollY);
        anim.setDuration(180);
        anim.setInterpolator(GlassTheme.EASE);
        anim.start();
    }

    private static int computeRelativeTop(View target, View ancestor) {
        int y = 0;
        View cur = target;
        while (cur != null && cur != ancestor) {
            y += cur.getTop();
            cur = cur.getParent() instanceof View ? (View) cur.getParent() : null;
        }
        return y;
    }

    private static void addDivider(LinearLayout parent, Context ctx) {
        View divider = new View(ctx);
        divider.setBackgroundColor(GlassTheme.DIVIDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1));
        lp.setMargins(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4));
        parent.addView(divider, lp);
    }

    private static LinearLayout.LayoutParams itemParams(Context ctx) {
        LinearLayout.LayoutParams lp = GlassTheme.matchWrapParams();
        lp.setMargins(0, dp(ctx, 1), 0, dp(ctx, 1));
        return lp;
    }

    @SuppressWarnings("unchecked")
    private static <T extends View> T findByTag(View root, String tag) {
        if (root == null) return null;
        if (tag.equals(root.getTag())) return (T) root;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                T found = findByTag(g.getChildAt(i), tag);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static int dp(Context ctx, int value) {
        return GlassTheme.dp(ctx, value);
    }
}
