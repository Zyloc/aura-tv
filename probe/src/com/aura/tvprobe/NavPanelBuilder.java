package com.aura.tvprobe;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;
import java.util.List;

/**
 * Clean, native-feeling collapsible left navigation bar for Aura TV.
 */
public final class NavPanelBuilder {

    private static final String TAG_CONTENT = "nav_content";
    private static final String TAG_PROFILES = "nav_profiles";
    private static final String TAG_STATUS = "nav_status";
    private static final String TAG_BRAND = "nav_brand";

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
        populateItems(panel, content, ctx, callback);
        return panel;
    }

    public static void setExpanded(LinearLayout panel, boolean expanded, Context ctx) {
        if (isExpanded(panel) == expanded) return;
        getState(panel).expanded = expanded;
        int targetW = dp(ctx, expanded ? GlassTheme.NAV_EXPANDED_W : GlassTheme.NAV_COLLAPSED_W);
        ViewGroup.LayoutParams lp = panel.getLayoutParams();
        lp.width = targetW;
        panel.setLayoutParams(lp);
        updateContentVisibility(panel, expanded, ctx);
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
        statusBox.removeAllViews();
        Context ctx = statusBox.getContext();
        TextView tv = GlassTheme.makeText(ctx,
            serverStatus + " \u00B7 " + ram + " \u00B7 :" + remotePort,
            9f, GlassTheme.TEXT_MUTED, GlassTheme.FONT_MEDIUM, 1);
        statusBox.addView(tv, GlassTheme.wrapParams());
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
        GradientDrawable bg = GlassTheme.solidRounded(Color.argb(242, 255, 255, 255), dp(ctx, 18));
        bg.setStroke(dp(ctx, 1), GlassTheme.BORDER_STRONG);
        panel.setBackground(bg);
        panel.setElevation(dp(ctx, 14));
        int w = dp(ctx, GlassTheme.NAV_COLLAPSED_W);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.setMargins(dp(ctx, 6), dp(ctx, 8), 0, dp(ctx, 8));
        panel.setLayoutParams(lp);
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

    private static void populateItems(LinearLayout panel, LinearLayout content,
                                       Context ctx, NavCallback cb) {
        addBrandLogo(content, ctx);
        addProfilesSection(panel, content, ctx, cb);
        addDivider(content, ctx);
        addNavItem(panel, content, ctx, "\uD83C\uDFE0", "Home", v -> cb.onNavigate("home"));
        addNavItem(panel, content, ctx, "\u2728", "Guide AI", v -> cb.onNavigate("guide_ai"));
        addNavItem(panel, content, ctx, "\uD83C\uDFAC", "Watched", v -> cb.onNavigate("watched"));
        addDivider(content, ctx);
        addNavItem(panel, content, ctx, "H1", "HDMI 1", v -> cb.onHdmiSelect(1));
        addNavItem(panel, content, ctx, "H2", "HDMI 2", v -> cb.onHdmiSelect(2));
        addNavItem(panel, content, ctx, "\uD83D\uDCF6", "Wi-Fi & Net", v -> cb.onNavigate("sys_wi-fi"));
        addDivider(content, ctx);
        addNavItem(panel, content, ctx, "\uD83D\uDCF1", "Phone Remote", v -> cb.onQrOpen());
        addNavItem(panel, content, ctx, "\u2699", "Settings", v -> cb.onSettingsOpen());
        addStatusSection(content, ctx);
    }

    private static void addBrandLogo(LinearLayout content, Context ctx) {
        TextView logo = GlassTheme.makeText(ctx, "A", 18f, Color.WHITE, GlassTheme.FONT_BOLD, 1);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(GlassTheme.solidRounded(GlassTheme.ACCENT, dp(ctx, 10)));
        logo.setTag(TAG_BRAND);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(ctx, 34), dp(ctx, 34));
        lp.bottomMargin = dp(ctx, 10);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        content.addView(logo, lp);
    }

    private static void addProfilesSection(LinearLayout panel, LinearLayout content,
                                            Context ctx, NavCallback cb) {
        LinearLayout profiles = new LinearLayout(ctx);
        profiles.setOrientation(LinearLayout.VERTICAL);
        profiles.setGravity(Gravity.CENTER_HORIZONTAL);
        profiles.setTag(TAG_PROFILES);
        content.addView(profiles, GlassTheme.matchWrapParams());
        addNavItem(panel, content, ctx, "+", "New Profile", v -> cb.onAddProfile());
    }

    private static void addStatusSection(LinearLayout content, Context ctx) {
        LinearLayout statusBox = new LinearLayout(ctx);
        statusBox.setOrientation(LinearLayout.VERTICAL);
        statusBox.setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 2));
        statusBox.setTag(TAG_STATUS);
        statusBox.setVisibility(View.GONE);
        content.addView(statusBox, GlassTheme.matchWrapParams());
    }

    private static void addNavItem(LinearLayout panel, LinearLayout parent, Context ctx,
                                    String badgeText, String label, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(dp(ctx, 6), dp(ctx, 6), dp(ctx, 6), dp(ctx, 6));
        TextView iconPill = createIconPill(ctx, badgeText);
        row.addView(iconPill);
        TextView labelTv = GlassTheme.makeText(ctx, label, 12f,
            GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1);
        labelTv.setPadding(dp(ctx, 10), 0, 0, 0);
        labelTv.setVisibility(View.GONE);
        row.addView(labelTv, GlassTheme.wrapParams());
        bindRowFocusAndClick(panel, row, iconPill, labelTv, false, click, ctx);
        parent.addView(row, itemParams(ctx));
    }

    private static TextView createIconPill(Context ctx, String badgeText) {
        TextView tv = GlassTheme.makeText(ctx, badgeText, 12f,
            GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1);
        tv.setGravity(Gravity.CENTER);
        int s = dp(ctx, 32);
        tv.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        tv.setBackground(GlassTheme.solidRounded(GlassTheme.BG_AMBIENT, dp(ctx, 9)));
        return tv;
    }

    private static void addProfileItem(LinearLayout panel, LinearLayout container,
                                        JSONObject profile, String activeId, boolean expanded,
                                        Context ctx, NavCallback cb) {
        String id = profile.optString("id", "");
        String name = profile.optString("name", "User");
        boolean active = id.equals(activeId);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(expanded ? (Gravity.START | Gravity.CENTER_VERTICAL) : Gravity.CENTER);
        row.setPadding(dp(ctx, 6), dp(ctx, 5), dp(ctx, 6), dp(ctx, 5));
        TextView avatar = createAvatar(ctx, name, active);
        row.addView(avatar);
        TextView label = GlassTheme.makeText(ctx, name, 12f,
            GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1);
        label.setPadding(dp(ctx, 10), 0, 0, 0);
        label.setVisibility(expanded ? View.VISIBLE : View.GONE);
        row.addView(label, GlassTheme.wrapParams());
        if (active) row.setBackground(GlassTheme.solidRounded(GlassTheme.ACCENT_LIGHT, dp(ctx, 10)));
        bindRowFocusAndClick(panel, row, avatar, label, active,
            cb != null ? v -> cb.onProfileSelected(id) : null, ctx);
        container.addView(row, itemParams(ctx));
    }

    private static TextView createAvatar(Context ctx, String name, boolean active) {
        String initial = name.isEmpty() ? "U" : name.substring(0, 1).toUpperCase();
        int bg = active ? GlassTheme.ACCENT : GlassTheme.TEXT_SECONDARY;
        TextView avatar = GlassTheme.makeText(ctx, initial, 12f, Color.WHITE, GlassTheme.FONT_BOLD, 1);
        avatar.setGravity(Gravity.CENTER);
        int s = dp(ctx, 32);
        avatar.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        avatar.setBackground(GlassTheme.solidRounded(bg, dp(ctx, 16)));
        return avatar;
    }

    private static void bindRowFocusAndClick(LinearLayout panel, LinearLayout row,
                                              TextView iconView, TextView labelView,
                                              boolean isActiveProfile,
                                              View.OnClickListener click, Context ctx) {
        row.setFocusable(true);
        row.setClickable(true);
        if (click != null) row.setOnClickListener(click);
        row.setOnFocusChangeListener((v, focused) -> {
            if (focused) {
                setExpanded(panel, true, ctx);
                row.setBackground(GlassTheme.focusRounded(
                    GlassTheme.ACCENT, Color.WHITE, dp(ctx, 2), dp(ctx, 10)));
                labelView.setTextColor(Color.WHITE);
            } else {
                row.setBackground(isActiveProfile
                    ? GlassTheme.solidRounded(GlassTheme.ACCENT_LIGHT, dp(ctx, 10)) : null);
                labelView.setTextColor(GlassTheme.TEXT_PRIMARY);
            }
        });
    }

    private static void addDivider(LinearLayout parent, Context ctx) {
        View divider = new View(ctx);
        divider.setBackgroundColor(GlassTheme.DIVIDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1));
        lp.setMargins(dp(ctx, 6), dp(ctx, 4), dp(ctx, 6), dp(ctx, 4));
        parent.addView(divider, lp);
    }

    private static LinearLayout.LayoutParams itemParams(Context ctx) {
        LinearLayout.LayoutParams lp = GlassTheme.matchWrapParams();
        lp.setMargins(0, dp(ctx, 2), 0, dp(ctx, 2));
        return lp;
    }

    private static void updateContentVisibility(LinearLayout panel, boolean expanded, Context ctx) {
        LinearLayout content = findByTag(panel, TAG_CONTENT);
        if (content == null) return;
        for (int i = 0; i < content.getChildCount(); i++) {
            toggleRowMode(content.getChildAt(i), expanded);
        }
        LinearLayout profiles = findByTag(content, TAG_PROFILES);
        if (profiles != null) {
            for (int i = 0; i < profiles.getChildCount(); i++) {
                toggleRowMode(profiles.getChildAt(i), expanded);
            }
        }
        TextView brand = findByTag(content, TAG_BRAND);
        if (brand != null) brand.setText(expanded ? "AURA AI" : "A");
        if (brand != null) {
            brand.getLayoutParams().width = dp(ctx, expanded ? 120 : 34);
            brand.requestLayout();
        }
        LinearLayout statusBox = findByTag(content, TAG_STATUS);
        if (statusBox != null) statusBox.setVisibility(expanded ? View.VISIBLE : View.GONE);
    }

    private static void toggleRowMode(View child, boolean expanded) {
        if (!(child instanceof LinearLayout)) return;
        if (TAG_PROFILES.equals(child.getTag()) || TAG_STATUS.equals(child.getTag())) return;
        LinearLayout row = (LinearLayout) child;
        if (row.getChildCount() >= 2 && row.getChildAt(1) instanceof TextView) {
            row.getChildAt(1).setVisibility(expanded ? View.VISIBLE : View.GONE);
        }
        row.setGravity(expanded ? (Gravity.START | Gravity.CENTER_VERTICAL) : Gravity.CENTER);
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
