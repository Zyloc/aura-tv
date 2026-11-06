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
 * Compact 3-dash (☰) Navigation Button that smoothly expands into a full
 * Frosted Dark-Glass Navigation Drawer on click/left-nav.
 * Frees 100% of vertical screen space when collapsed.
 */
public final class NavPanelBuilder {

    private static final String TAG_DRAWER = "nav_drawer";
    private static final String TAG_HAMBURGER = "nav_hamburger";
    private static final String TAG_PROFILES = "nav_profiles";
    private static final String TAG_STATUS = "nav_status";
    private static final String TAG_FIRST_ITEM = "nav_first_item";

    private static final class PanelState {
        boolean expanded = false;
        NavCallback callback;
        View mainContentToAnimate;
    }

    public interface NavCallback {
        void onProfileSelected(String profileId);
        void onAddProfile();
        void onNavigate(String destination);
        void onHdmiSelect(int port);
        void onSettingsOpen();
        void onQrOpen();
        void onWatchHistoryOpen();
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

        LinearLayout drawer = buildExpandedDrawer(wrapper, ctx, callback);
        wrapper.addView(drawer);
        return wrapper;
    }

    public static TextView createTopBarHamburger(FrameLayout wrapper, Context ctx) {
        TextView btn = buildHamburgerButton(wrapper, ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(ctx, 44), dp(ctx, 38));
        lp.rightMargin = dp(ctx, 10);
        btn.setLayoutParams(lp);
        return btn;
    }

    public static void bindMainContentForAnimation(FrameLayout wrapper, View mainContent) {
        getState(wrapper).mainContentToAnimate = mainContent;
    }

    public static void setExpanded(FrameLayout wrapper, boolean expanded, Context ctx) {
        PanelState st = getState(wrapper);
        if (st.expanded == expanded) return;
        st.expanded = expanded;

        LinearLayout drawer = findByTag(wrapper, TAG_DRAWER);
        if (drawer == null) return;

        if (expanded) {
            drawer.setVisibility(View.VISIBLE);
            drawer.setTranslationX(-dp(ctx, GlassTheme.NAV_EXPANDED_W));
            drawer.setAlpha(0.2f);
            drawer.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(220)
                .setInterpolator(GlassTheme.EASE)
                .start();
            animateMainContent(st.mainContentToAnimate, dp(ctx, GlassTheme.NAV_EXPANDED_W + 6), 0.965f);
            View first = findByTag(drawer, TAG_FIRST_ITEM);
            if (first != null) first.post(first::requestFocus);
        } else {
            animateMainContent(st.mainContentToAnimate, 0, 1.0f);
            drawer.animate()
                .translationX(-dp(ctx, GlassTheme.NAV_EXPANDED_W))
                .alpha(0f)
                .setDuration(190)
                .setInterpolator(GlassTheme.EASE)
                .withEndAction(() -> drawer.setVisibility(View.GONE))
                .start();
        }
    }

    private static void animateMainContent(View main, float targetTransX, float targetScale) {
        if (main == null) return;
        main.animate()
            .translationX(targetTransX)
            .scaleX(targetScale)
            .scaleY(targetScale)
            .setDuration(220)
            .setInterpolator(GlassTheme.EASE)
            .start();
    }

    public static boolean isExpanded(FrameLayout wrapper) {
        return getState(wrapper).expanded;
    }

    public static void focusHamburgerOrExpand(FrameLayout wrapper, Context ctx) {
        setExpanded(wrapper, true, ctx);
    }

    public static void updateProfiles(FrameLayout wrapper, List<JSONObject> profiles,
                                       String activeProfileId, Context ctx) {
        LinearLayout container = findByTag(wrapper, TAG_PROFILES);
        if (container == null) return;
        container.removeAllViews();
        NavCallback cb = getState(wrapper).callback;
        for (JSONObject p : profiles) {
            addProfileRow(wrapper, container, p, activeProfileId, ctx, cb);
        }
    }

    public static void updateStatus(FrameLayout wrapper, String serverStatus,
                                     String ram, String remotePort) {
        LinearLayout statusBox = findByTag(wrapper, TAG_STATUS);
        if (statusBox == null) return;
        statusBox.removeAllViews();
        Context ctx = statusBox.getContext();
        TextView tv = GlassTheme.makeText(ctx,
            serverStatus + " \u00B7 " + ram + " \u00B7 :" + remotePort,
            9.5f, GlassTheme.TEXT_MUTED, GlassTheme.FONT_MEDIUM, 1);
        statusBox.addView(tv, GlassTheme.wrapParams());
    }

    private static PanelState getState(FrameLayout wrapper) {
        if (wrapper.getTag() instanceof PanelState) return (PanelState) wrapper.getTag();
        PanelState st = new PanelState();
        wrapper.setTag(st);
        return st;
    }

    private static TextView buildHamburgerButton(FrameLayout wrapper, Context ctx) {
        TextView btn = GlassTheme.makeText(ctx, "\u2630", 18f,
            GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1);
        btn.setGravity(Gravity.CENTER);
        btn.setTag(TAG_HAMBURGER);
        float r = dp(ctx, 12);
        btn.setBackground(GlassTheme.glassCard(r));
        btn.setElevation(dp(ctx, 8));
        btn.setFocusable(true);
        btn.setClickable(true);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(ctx, 44), dp(ctx, 40));
        lp.setMargins(dp(ctx, 14), dp(ctx, 10), 0, 0);
        btn.setLayoutParams(lp);
        btn.setOnClickListener(v -> setExpanded(wrapper, true, ctx));
        btn.setOnFocusChangeListener((v, focused) -> {
            if (focused) {
                btn.setBackground(GlassTheme.focusRounded(
                    Color.WHITE, Color.WHITE, dp(ctx, 2), r));
                btn.setTextColor(GlassTheme.TEXT_ON_LIGHT);
                v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(140).start();
            } else {
                btn.setBackground(GlassTheme.glassCard(r));
                btn.setTextColor(GlassTheme.TEXT_PRIMARY);
                v.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
            }
        });
        return btn;
    }

    private static LinearLayout buildExpandedDrawer(FrameLayout wrapper, Context ctx, NavCallback cb) {
        LinearLayout drawer = new LinearLayout(ctx);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setTag(TAG_DRAWER);
        drawer.setVisibility(View.GONE);
        GradientDrawable bg = GlassTheme.solidRounded(GlassTheme.GLASS_PANEL, dp(ctx, 18));
        bg.setStroke(dp(ctx, 1), GlassTheme.BORDER_STRONG);
        drawer.setBackground(bg);
        drawer.setElevation(dp(ctx, 24));
        int w = dp(ctx, GlassTheme.NAV_EXPANDED_W);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.setMargins(dp(ctx, 8), dp(ctx, 8), 0, dp(ctx, 8));
        drawer.setLayoutParams(lp);

        ScrollView sv = new ScrollView(ctx);
        sv.setVerticalScrollBarEnabled(false);
        sv.setFillViewport(true);
        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12));
        populateDrawerItems(wrapper, content, ctx, cb);
        sv.addView(content, GlassTheme.matchWrapParams());
        drawer.addView(sv, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return drawer;
    }

    private static void populateDrawerItems(FrameLayout wrapper, LinearLayout content,
                                             Context ctx, NavCallback cb) {
        addDrawerHeader(wrapper, content, ctx);
        addSectionLabel(content, ctx, "PROFILES");
        LinearLayout profiles = new LinearLayout(ctx);
        profiles.setOrientation(LinearLayout.VERTICAL);
        profiles.setTag(TAG_PROFILES);
        content.addView(profiles, GlassTheme.matchWrapParams());
        addNavItem(wrapper, content, ctx, "+", "Add New Profile", false, v -> cb.onAddProfile());

        addDivider(content, ctx);
        addSectionLabel(content, ctx, "NAVIGATION & AI");
        addNavItem(wrapper, content, ctx, "\u2302", "Home", true, v -> {
            setExpanded(wrapper, false, ctx);
            cb.onNavigate("home");
        });
        addNavItem(wrapper, content, ctx, "\u25F7", "Watch History", false, v -> cb.onWatchHistoryOpen());
        addNavItem(wrapper, content, ctx, "\u2726", "Guide AI Taste", false, v -> cb.onNavigate("guide_ai"));
        addNavItem(wrapper, content, ctx, "\u2699", "Aura & TV Settings", false, v -> cb.onSettingsOpen());
        addNavItem(wrapper, content, ctx, "\u229E", "Phone Remote (QR)", false, v -> cb.onQrOpen());

        addDivider(content, ctx);
        addSectionLabel(content, ctx, "TV INPUTS & NETWORK");
        addNavItem(wrapper, content, ctx, "H1", "HDMI 1 Input", false, v -> cb.onHdmiSelect(1));
        addNavItem(wrapper, content, ctx, "H2", "HDMI 2 Input", false, v -> cb.onHdmiSelect(2));
        addNavItem(wrapper, content, ctx, "\u224B", "Wi-Fi & Network", false, v -> cb.onNavigate("sys_wi-fi"));

        LinearLayout statusBox = new LinearLayout(ctx);
        statusBox.setOrientation(LinearLayout.VERTICAL);
        statusBox.setPadding(dp(ctx, 4), dp(ctx, 10), dp(ctx, 4), dp(ctx, 2));
        statusBox.setTag(TAG_STATUS);
        content.addView(statusBox, GlassTheme.matchWrapParams());
    }

    private static void addDrawerHeader(FrameLayout wrapper, LinearLayout content, Context ctx) {
        LinearLayout hdr = new LinearLayout(ctx);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER_VERTICAL);
        hdr.setPadding(dp(ctx, 4), 0, dp(ctx, 4), dp(ctx, 8));

        TextView badge = GlassTheme.makeText(ctx, "\u2630  AURA TV", 13f,
            GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1);
        hdr.addView(badge, GlassTheme.weightParams(1f));

        TextView closeHint = GlassTheme.makeText(ctx, "\u2190 Back", 10f,
            GlassTheme.TEXT_MUTED, GlassTheme.FONT_MEDIUM, 1);
        hdr.addView(closeHint, GlassTheme.wrapParams());
        content.addView(hdr, GlassTheme.matchWrapParams());
    }

    private static void addSectionLabel(LinearLayout content, Context ctx, String text) {
        TextView lbl = GlassTheme.makeText(ctx, text, 9f,
            GlassTheme.TEXT_MUTED, GlassTheme.FONT_BOLD, 1);
        lbl.setLetterSpacing(0.08f);
        lbl.setPadding(dp(ctx, 6), dp(ctx, 4), 0, dp(ctx, 2));
        content.addView(lbl, GlassTheme.wrapParams());
    }

    private static void addNavItem(FrameLayout wrapper, LinearLayout parent, Context ctx,
                                    String badgeText, String label, boolean isFirst,
                                    View.OnClickListener click) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6));
        if (isFirst) row.setTag(TAG_FIRST_ITEM);

        TextView iconPill = createIconPill(ctx, badgeText);
        row.addView(iconPill);

        TextView labelTv = GlassTheme.makeText(ctx, label, 12f,
            GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1);
        labelTv.setPadding(dp(ctx, 10), 0, 0, 0);
        row.addView(labelTv, GlassTheme.wrapParams());

        bindRowFocusAndClick(wrapper, row, iconPill, labelTv, false, click, ctx);
        parent.addView(row, itemParams(ctx));
    }

    private static TextView createIconPill(Context ctx, String badgeText) {
        TextView tv = GlassTheme.makeText(ctx, badgeText, 11f,
            GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1);
        tv.setGravity(Gravity.CENTER);
        int s = dp(ctx, 26);
        tv.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        tv.setBackground(GlassTheme.solidRounded(GlassTheme.SURFACE_ELEV, dp(ctx, 7)));
        return tv;
    }

    private static void addProfileRow(FrameLayout wrapper, LinearLayout container,
                                       JSONObject profile, String activeId,
                                       Context ctx, NavCallback cb) {
        String id = profile.optString("id", "");
        String name = profile.optString("name", "User");
        boolean active = id.equals(activeId);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, 8), dp(ctx, 5), dp(ctx, 10), dp(ctx, 5));

        TextView avatar = createAvatar(ctx, name, active);
        row.addView(avatar);

        String display = active ? (name + "  \u2022 Active") : name;
        TextView label = GlassTheme.makeText(ctx, display, 12f,
            active ? GlassTheme.GOLD_ACCENT : GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1);
        label.setPadding(dp(ctx, 10), 0, 0, 0);
        row.addView(label, GlassTheme.wrapParams());

        if (active) row.setBackground(GlassTheme.solidRounded(GlassTheme.SURFACE_ELEV, dp(ctx, 9)));
        bindRowFocusAndClick(wrapper, row, avatar, label, active,
            cb != null ? v -> {
                setExpanded(wrapper, false, ctx);
                cb.onProfileSelected(id);
            } : null, ctx);
        container.addView(row, itemParams(ctx));
    }

    private static TextView createAvatar(Context ctx, String name, boolean active) {
        String initial = name.isEmpty() ? "U" : name.substring(0, 1).toUpperCase();
        int bg = active ? GlassTheme.GOLD_ACCENT : GlassTheme.SURFACE_ELEV;
        int fg = active ? GlassTheme.TEXT_ON_LIGHT : GlassTheme.TEXT_PRIMARY;
        TextView avatar = GlassTheme.makeText(ctx, initial, 11f, fg, GlassTheme.FONT_BOLD, 1);
        avatar.setGravity(Gravity.CENTER);
        int s = dp(ctx, 26);
        avatar.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        avatar.setBackground(GlassTheme.solidRounded(bg, dp(ctx, 13)));
        return avatar;
    }

    private static void bindRowFocusAndClick(FrameLayout wrapper, LinearLayout row,
                                              TextView iconView, TextView labelView,
                                              boolean isActiveProfile,
                                              View.OnClickListener click, Context ctx) {
        row.setFocusable(true);
        row.setClickable(true);
        if (click != null) row.setOnClickListener(click);
        row.setOnKeyListener((v, keyCode, ev) -> {
            if (ev.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                setExpanded(wrapper, false, ctx);
                return false;
            }
            return false;
        });
        row.setOnFocusChangeListener((v, focused) -> {
            if (focused) {
                row.setBackground(GlassTheme.focusRounded(
                    Color.WHITE, Color.WHITE, dp(ctx, 2), dp(ctx, 9)));
                labelView.setTextColor(GlassTheme.TEXT_ON_LIGHT);
            } else {
                row.setBackground(isActiveProfile
                    ? GlassTheme.solidRounded(GlassTheme.SURFACE_ELEV, dp(ctx, 9)) : null);
                labelView.setTextColor(isActiveProfile
                    ? GlassTheme.GOLD_ACCENT : GlassTheme.TEXT_PRIMARY);
            }
        });
    }

    private static void addDivider(LinearLayout parent, Context ctx) {
        View divider = new View(ctx);
        divider.setBackgroundColor(GlassTheme.DIVIDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1));
        lp.setMargins(dp(ctx, 4), dp(ctx, 5), dp(ctx, 4), dp(ctx, 5));
        parent.addView(divider, lp);
    }

    private static LinearLayout.LayoutParams itemParams(Context ctx) {
        LinearLayout.LayoutParams lp = GlassTheme.matchWrapParams();
        lp.setMargins(0, dp(ctx, 1), 0, dp(ctx, 1));
        return lp;
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
