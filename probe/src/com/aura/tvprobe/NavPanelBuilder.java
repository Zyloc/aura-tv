package com.aura.tvprobe;

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
import java.util.List;

/**
 * Builds the collapsible left navigation panel for Aura TV launcher.
 * Glass-themed, D-pad navigable, expand/collapse with smooth animation.
 */
public final class NavPanelBuilder {

    private static final String TAG_CONTENT = "nav_content";
    private static final String TAG_PROFILES = "nav_profiles";
    private static final String TAG_STATUS = "nav_status";
    private static final String TAG_NAV_ITEM = "nav_item_";
    private static final int ANIM_DURATION = 220;

    private static final class PanelState {
        boolean expanded;
        NavCallback callback;
    }

    public interface NavCallback {
        void onProfileSelected(String profileId);
        void onAddProfile();
        void onNavigate(String destination);
        void onHdmiSelect(int port);
        void onSettingsOpen();
        void onQrOpen();
    }

    public static LinearLayout build(Context ctx, NavCallback callback) {
        LinearLayout panel = createPanel(ctx, callback);
        ScrollView scroll = createScroll(ctx);
        LinearLayout content = createContent(ctx);
        scroll.addView(content, GlassTheme.matchWrapParams());
        panel.addView(scroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        populateCollapsed(panel, content, ctx, callback);
        return panel;
    }

    public static void setExpanded(LinearLayout panel, boolean expanded, Context ctx) {
        if (isExpanded(panel) == expanded) return;
        PanelState st = getState(panel);
        st.expanded = expanded;
        int targetW = dp(ctx, expanded ? GlassTheme.NAV_EXPANDED_W : GlassTheme.NAV_COLLAPSED_W);
        animateWidth(panel, targetW);
        updateContentVisibility(panel, expanded);
    }

    public static boolean isExpanded(LinearLayout panel) {
        return getState(panel).expanded;
    }

    public static void updateProfiles(LinearLayout panel, List<JSONObject> profiles,
                                       String activeProfileId, Context ctx) {
        LinearLayout container = findByTag(panel, TAG_PROFILES);
        if (container == null) return;
        container.removeAllViews();
        NavCallback cb = getState(panel).callback;
        boolean exp = isExpanded(panel);
        for (JSONObject p : profiles) {
            addProfileItem(panel, container, p, activeProfileId, exp, ctx, cb);
        }
    }

    public static void updateStatus(LinearLayout panel, String serverStatus,
                                     String ram, String remotePort) {
        LinearLayout statusBox = findByTag(panel, TAG_STATUS);
        if (statusBox == null) return;
        updateStatusText(statusBox, serverStatus, ram, remotePort);
    }

    private static PanelState getState(LinearLayout panel) {
        if (panel.getTag() instanceof PanelState) return (PanelState) panel.getTag();
        PanelState st = new PanelState();
        panel.setTag(st);
        return st;
    }

    private static LinearLayout createPanel(Context ctx, NavCallback cb) {
        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(GlassTheme.glassPanel(dp(ctx, 20)));
        panel.setElevation(dp(ctx, 8));
        int w = dp(ctx, GlassTheme.NAV_COLLAPSED_W);
        panel.setLayoutParams(new FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT));
        PanelState st = new PanelState();
        st.callback = cb;
        panel.setTag(st);
        return panel;
    }

    private static ScrollView createScroll(Context ctx) {
        ScrollView sv = new ScrollView(ctx);
        sv.setVerticalScrollBarEnabled(false);
        sv.setFillViewport(true);
        return sv;
    }

    private static LinearLayout createContent(Context ctx) {
        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(ctx, 6), dp(ctx, 12), dp(ctx, 6), dp(ctx, 12));
        content.setTag(TAG_CONTENT);
        return content;
    }

    private static void populateCollapsed(LinearLayout panel, LinearLayout content,
                                           Context ctx, NavCallback cb) {
        addBrandLogo(content, ctx);
        addProfilesSection(panel, content, ctx, cb);
        addNavSection(panel, content, ctx, cb);
        addDivider(content, ctx);
        addHdmiSection(panel, content, ctx, cb);
        addSystemSection(panel, content, ctx, cb);
        addStatusSection(content, ctx);
        addBottomActions(panel, content, ctx, cb);
    }

    private static void addBrandLogo(LinearLayout content, Context ctx) {
        TextView logo = GlassTheme.makeText(ctx, "A", 20, GlassTheme.ACCENT, GlassTheme.FONT_BOLD, 1);
        logo.setGravity(Gravity.CENTER);
        logo.setPadding(0, 0, 0, dp(ctx, 8));
        logo.setTag(TAG_NAV_ITEM + "brand");
        content.addView(logo, GlassTheme.wrapParams());
    }

    private static void addProfilesSection(LinearLayout panel, LinearLayout content,
                                            Context ctx, NavCallback cb) {
        LinearLayout profiles = new LinearLayout(ctx);
        profiles.setOrientation(LinearLayout.VERTICAL);
        profiles.setGravity(Gravity.CENTER_HORIZONTAL);
        profiles.setTag(TAG_PROFILES);
        content.addView(profiles, GlassTheme.matchWrapParams());
        View item = makeNavItem(panel, ctx, "+", "Add Profile", false, v -> cb.onAddProfile());
        styleDashedBorder(item, ctx);
        content.addView(item, itemParams(ctx));
    }

    private static void addNavSection(LinearLayout panel, LinearLayout content,
                                       Context ctx, NavCallback cb) {
        addSectionLabel(content, ctx, "MENU");
        addNavButton(panel, content, ctx, "\uD83C\uDFE0", "Home", "home", true, cb);
        addNavButton(panel, content, ctx, "\u2728", "Guide AI", "guide_ai", false, cb);
        addNavButton(panel, content, ctx, "\u2705", "Watched", "watched", false, cb);
    }

    private static void addNavButton(LinearLayout panel, LinearLayout parent, Context ctx,
                                      String icon, String label, String dest,
                                      boolean active, NavCallback cb) {
        View item = makeNavItem(panel, ctx, icon, label, active, v -> cb.onNavigate(dest));
        item.setTag(TAG_NAV_ITEM + dest);
        parent.addView(item, itemParams(ctx));
    }

    private static void addHdmiSection(LinearLayout panel, LinearLayout content,
                                        Context ctx, NavCallback cb) {
        addSectionLabel(content, ctx, "INPUTS");
        for (int port = 1; port <= 3; port++) {
            addHdmiItem(panel, content, ctx, port, cb);
        }
    }

    private static void addHdmiItem(LinearLayout panel, LinearLayout parent,
                                     Context ctx, int port, NavCallback cb) {
        String label = "HDMI " + port;
        View item = makeNavItem(panel, ctx, "\uD83D\uDCFA", label, false, v -> cb.onHdmiSelect(port));
        if (port == 1) addBadge((LinearLayout) item, ctx, "Live", GlassTheme.COLOR_SUCCESS);
        parent.addView(item, itemParams(ctx));
    }

    private static void addSystemSection(LinearLayout panel, LinearLayout content,
                                          Context ctx, NavCallback cb) {
        addSectionLabel(content, ctx, "SYSTEM");
        addSystemItem(panel, content, ctx, "\uD83D\uDCF6", "Wi-Fi", "5G", cb);
        addSystemItem(panel, content, ctx, "\uD83D\uDD35", "Bluetooth", null, cb);
        addSystemItem(panel, content, ctx, "\uD83D\uDD06", "Display", null, cb);
        addSystemItem(panel, content, ctx, "\uD83D\uDD0A", "Audio", null, cb);
    }

    private static void addSystemItem(LinearLayout panel, LinearLayout parent, Context ctx,
                                       String icon, String label, String badge, NavCallback cb) {
        LinearLayout item = (LinearLayout) makeNavItem(panel, ctx, icon, label, false,
            v -> cb.onNavigate("sys_" + label.toLowerCase()));
        if (badge != null) addBadge(item, ctx, badge, GlassTheme.ACCENT);
        parent.addView(item, itemParams(ctx));
    }

    private static void addStatusSection(LinearLayout content, Context ctx) {
        LinearLayout statusBox = new LinearLayout(ctx);
        statusBox.setOrientation(LinearLayout.VERTICAL);
        statusBox.setBackground(GlassTheme.glassSubtle(dp(ctx, 10)));
        statusBox.setPadding(dp(ctx, 8), dp(ctx, 5), dp(ctx, 8), dp(ctx, 5));
        statusBox.setTag(TAG_STATUS);
        statusBox.setVisibility(View.GONE);
        content.addView(statusBox, itemParams(ctx));
    }

    private static void updateStatusText(LinearLayout statusBox, String server,
                                          String ram, String port) {
        statusBox.removeAllViews();
        Context ctx = statusBox.getContext();
        addStatusLine(statusBox, ctx, "Server: \u25CF " + server);
        addStatusLine(statusBox, ctx, "RAM: " + ram);
        addStatusLine(statusBox, ctx, "Remote: :" + port);
    }

    private static void addStatusLine(LinearLayout box, Context ctx, String text) {
        TextView tv = GlassTheme.makeText(ctx, text, 9, GlassTheme.TEXT_MUTED, GlassTheme.FONT_REGULAR, 1);
        box.addView(tv, GlassTheme.wrapParams());
    }

    private static void addBottomActions(LinearLayout panel, LinearLayout content,
                                          Context ctx, NavCallback cb) {
        addDivider(content, ctx);
        View qr = makeNavItem(panel, ctx, "\uD83D\uDCF1", "Mobile Remote", false, v -> cb.onQrOpen());
        content.addView(qr, itemParams(ctx));
        View settings = makeNavItem(panel, ctx, "\u2699\uFE0F", "Settings", false, v -> cb.onSettingsOpen());
        content.addView(settings, itemParams(ctx));
    }

    private static View makeNavItem(LinearLayout panel, Context ctx, String icon, String label,
                                     boolean active, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, 6), dp(ctx, 5), dp(ctx, 6), dp(ctx, 5));
        addIconAndLabel(row, ctx, icon, label);
        if (active) row.setBackground(GlassTheme.solidRounded(GlassTheme.ACCENT_BG, dp(ctx, 10)));
        applyFocusAndClick(panel, row, click, ctx);
        return row;
    }

    private static void addIconAndLabel(LinearLayout row, Context ctx,
                                         String icon, String label) {
        TextView iconTv = GlassTheme.makeText(ctx, icon, 14, GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_REGULAR, 1);
        iconTv.setGravity(Gravity.CENTER);
        iconTv.setMinWidth(dp(ctx, 26));
        row.addView(iconTv, GlassTheme.wrapParams());
        TextView labelTv = GlassTheme.makeText(ctx, label, 12, GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_MEDIUM, 1);
        labelTv.setPadding(dp(ctx, 8), 0, 0, 0);
        labelTv.setVisibility(View.GONE);
        row.addView(labelTv, GlassTheme.wrapParams());
    }

    private static void applyFocusAndClick(LinearLayout panel, View item,
                                            View.OnClickListener click, Context ctx) {
        item.setFocusable(true);
        item.setClickable(true);
        if (click != null) item.setOnClickListener(click);
        item.setOnFocusChangeListener((v, hasFocus) -> {
            applyFocusHighlight(v, hasFocus, ctx);
            if (hasFocus) setExpanded(panel, true, ctx);
        });
    }

    private static void applyFocusHighlight(View v, boolean hasFocus, Context ctx) {
        if (hasFocus) {
            v.setBackground(GlassTheme.solidRounded(GlassTheme.ACCENT_BG, dp(ctx, 10)));
        } else {
            v.setBackground(null);
        }
    }

    private static void addProfileItem(LinearLayout panel, LinearLayout container,
                                        JSONObject profile, String activeId, boolean expanded,
                                        Context ctx, NavCallback cb) {
        String id = profile.optString("id", "");
        String name = profile.optString("name", "User");
        boolean active = id.equals(activeId);
        View item = makeProfileRow(panel, ctx, name, active, id, expanded, cb);
        container.addView(item, itemParams(ctx));
    }

    private static View makeProfileRow(LinearLayout panel, Context ctx, String name,
                                        boolean active, String id, boolean expanded, NavCallback cb) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(expanded ? (Gravity.START | Gravity.CENTER_VERTICAL) : Gravity.CENTER);
        row.setPadding(dp(ctx, 6), dp(ctx, 4), dp(ctx, 6), dp(ctx, 4));
        addAvatarCircle(row, ctx, name, active);
        addProfileLabel(row, ctx, name, expanded);
        if (active) row.setBackground(GlassTheme.solidRounded(GlassTheme.ACCENT_BG, dp(ctx, 10)));
        row.setTag(TAG_NAV_ITEM + "profile_" + id);
        applyFocusAndClick(panel, row, cb != null ? v -> cb.onProfileSelected(id) : null, ctx);
        return row;
    }

    private static void addAvatarCircle(LinearLayout row, Context ctx,
                                         String name, boolean active) {
        String initial = name.isEmpty() ? "U" : name.substring(0, 1).toUpperCase();
        int color = active ? GlassTheme.ACCENT : GlassTheme.TEXT_MUTED;
        TextView avatar = GlassTheme.makeText(ctx, initial, 11, Color.WHITE, GlassTheme.FONT_BOLD, 1);
        avatar.setGravity(Gravity.CENTER);
        int size = dp(ctx, 26);
        avatar.setBackground(GlassTheme.solidRounded(color, dp(ctx, 13)));
        row.addView(avatar, new LinearLayout.LayoutParams(size, size));
    }

    private static void addProfileLabel(LinearLayout row, Context ctx, String name, boolean exp) {
        TextView label = GlassTheme.makeText(ctx, name, 12, GlassTheme.TEXT_PRIMARY,
                                              GlassTheme.FONT_MEDIUM, 1);
        label.setPadding(dp(ctx, 8), 0, 0, 0);
        label.setVisibility(exp ? View.VISIBLE : View.GONE);
        row.addView(label, GlassTheme.wrapParams());
    }

    private static void addBadge(LinearLayout row, Context ctx, String text, int color) {
        TextView badge = GlassTheme.makeText(ctx, text, 8, Color.WHITE, GlassTheme.FONT_BOLD, 1);
        badge.setPadding(dp(ctx, 5), dp(ctx, 1), dp(ctx, 5), dp(ctx, 1));
        badge.setBackground(GlassTheme.solidRounded(color, dp(ctx, 6)));
        badge.setGravity(Gravity.CENTER);
        badge.setVisibility(View.GONE);
        LinearLayout.LayoutParams lp = GlassTheme.wrapParams();
        lp.setMargins(dp(ctx, 4), 0, 0, 0);
        row.addView(badge, lp);
    }

    private static void addSectionLabel(LinearLayout parent, Context ctx, String text) {
        TextView label = GlassTheme.makeText(ctx, text, 9, GlassTheme.TEXT_HINT,
                                              GlassTheme.FONT_BOLD, 1);
        label.setPadding(dp(ctx, 8), dp(ctx, 6), 0, dp(ctx, 2));
        label.setVisibility(View.GONE);
        label.setTag(TAG_NAV_ITEM + "section_" + text);
        parent.addView(label, GlassTheme.wrapParams());
    }

    private static void addDivider(LinearLayout parent, Context ctx) {
        View divider = new View(ctx);
        divider.setBackgroundColor(GlassTheme.DIVIDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1));
        lp.setMargins(dp(ctx, 8), dp(ctx, 4), dp(ctx, 8), dp(ctx, 4));
        parent.addView(divider, lp);
    }

    private static void styleDashedBorder(View item, Context ctx) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(ctx, 10));
        d.setStroke(dp(ctx, 1), GlassTheme.TEXT_MUTED, dp(ctx, 4), dp(ctx, 3));
        d.setColor(Color.TRANSPARENT);
        item.setBackground(d);
    }

    private static LinearLayout.LayoutParams itemParams(Context ctx) {
        LinearLayout.LayoutParams lp = GlassTheme.matchWrapParams();
        lp.setMargins(0, dp(ctx, 1), 0, dp(ctx, 1));
        return lp;
    }

    private static void animateWidth(LinearLayout panel, int targetW) {
        panel.animate().cancel();
        ViewGroup.LayoutParams lp = panel.getLayoutParams();
        lp.width = targetW;
        panel.setLayoutParams(lp);
    }

    private static void updateContentVisibility(LinearLayout panel, boolean expanded) {
        LinearLayout content = findByTag(panel, TAG_CONTENT);
        if (content == null) return;
        for (int i = 0; i < content.getChildCount(); i++) {
            updateChildVisibility(content.getChildAt(i), expanded);
        }
        LinearLayout profiles = findByTag(content, TAG_PROFILES);
        if (profiles != null) {
            for (int i = 0; i < profiles.getChildCount(); i++) {
                updateChildVisibility(profiles.getChildAt(i), expanded);
            }
        }
        updatePanelGravity(content, expanded);
    }

    private static void updateChildVisibility(View child, boolean expanded) {
        if (child instanceof LinearLayout && !TAG_PROFILES.equals(child.getTag())
                && !TAG_STATUS.equals(child.getTag())) {
            updateRowChildren((LinearLayout) child, expanded);
        }
        if (child.getTag() != null && child.getTag().toString().startsWith(TAG_NAV_ITEM + "section_")) {
            child.setVisibility(expanded ? View.VISIBLE : View.GONE);
        }
    }

    private static void updateRowChildren(LinearLayout row, boolean expanded) {
        for (int j = 0; j < row.getChildCount(); j++) {
            View v = row.getChildAt(j);
            if (j > 0 && v instanceof TextView) {
                v.setVisibility(expanded ? View.VISIBLE : View.GONE);
            }
        }
        row.setGravity(expanded ? (Gravity.START | Gravity.CENTER_VERTICAL) : Gravity.CENTER);
    }

    private static void updatePanelGravity(LinearLayout content, boolean expanded) {
        content.setGravity(expanded ? Gravity.START : Gravity.CENTER_HORIZONTAL);
        LinearLayout statusBox = findByTag(content, TAG_STATUS);
        if (statusBox != null) statusBox.setVisibility(expanded ? View.VISIBLE : View.GONE);
    }

    @SuppressWarnings("unchecked")
    private static <T extends View> T findByTag(View root, String tag) {
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

    private static int dp(Context ctx, int dpVal) {
        return GlassTheme.dp(ctx, dpVal);
    }

    private NavPanelBuilder() {}
}
