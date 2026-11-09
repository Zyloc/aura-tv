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
 * Compact 3-dash (☰) Navigation Button that smoothly expands into a full
 * Frosted Dark-Glass Navigation Drawer on click/left-nav.
 * Strictly traps D-pad Up/Down/Left inside the drawer while open so focus
 * never leaks to the right-side rails.
 */
public final class NavPanelBuilder {

    private static final String TAG_DRAWER = "nav_drawer";
    private static final String TAG_SCROLL = "nav_scroll";
    private static final String TAG_HAMBURGER = "nav_hamburger";
    private static final String TAG_PROFILES = "nav_profiles";
    private static final String TAG_STATUS = "nav_status";
    private static final String TAG_FIRST_ITEM = "nav_first_item";

    private static final int DRAWER_WIDTH_DP = 246;

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

        LinearLayout drawer = buildExpandedDrawer(wrapper, ctx, callback);
        wrapper.addView(drawer);
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

        if (expanded) {
            expandDrawerView(st, drawer, hiddenX, ctx);
        } else {
            collapseDrawerView(st, drawer, hiddenX, notifyCollapse);
        }
    }

    private static void expandDrawerView(PanelState st, LinearLayout drawer, float hiddenX, Context ctx) {
        drawer.animate().cancel();
        if (drawer.getVisibility() != View.VISIBLE) {
            drawer.setTranslationX(hiddenX);
            drawer.setAlpha(0f);
            drawer.setVisibility(View.VISIBLE);
        }
        drawer.animate().translationX(0f).alpha(1f).setDuration(420).setInterpolator(SLIDE_EASE).start();
        animateMainContent(st.mainContentToAnimate, dp(ctx, DRAWER_WIDTH_DP + 14), 0.955f);
        View first = findByTag(drawer, TAG_FIRST_ITEM);
        if (first != null) first.requestFocus();
        if (st.mainContentToAnimate instanceof ViewGroup) {
            ((ViewGroup) st.mainContentToAnimate).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
    }

    private static void collapseDrawerView(PanelState st, LinearLayout drawer, float hiddenX, boolean notifyCollapse) {
        if (st.mainContentToAnimate instanceof ViewGroup) {
            ((ViewGroup) st.mainContentToAnimate).setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        }
        drawer.animate().cancel();
        animateMainContent(st.mainContentToAnimate, 0, 1.0f);
        drawer.animate().translationX(hiddenX).alpha(0f).setDuration(380).setInterpolator(SLIDE_EASE)
            .withEndAction(() -> drawer.setVisibility(View.INVISIBLE)).start();
        if (notifyCollapse && st.callback != null) {
            st.callback.onDrawerCollapsed();
        }
    }

    private static void animateMainContent(View main, float targetTransX, float targetScale) {
        if (main == null) return;
        main.animate().cancel();
        main.animate()
            .translationX(targetTransX)
            .scaleX(targetScale)
            .scaleY(targetScale)
            .setDuration(420)
            .setInterpolator(SLIDE_EASE)
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
        TextView btn = GlassTheme.makeText(ctx, "\u2630", 16f,
            GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1);
        btn.setGravity(Gravity.CENTER);
        btn.setTag(TAG_HAMBURGER);
        float r = dp(ctx, 18);
        btn.setBackground(GlassTheme.focusRounded(
            GlassTheme.SURFACE_ELEV, GlassTheme.BORDER_STRONG, dp(ctx, 1), r));
        btn.setElevation(dp(ctx, 6));
        btn.setFocusable(true);
        btn.setClickable(true);
        btn.setOnClickListener(v -> setExpanded(wrapper, true, ctx));
        btn.setOnFocusChangeListener((v, focused) -> {
            if (focused) {
                btn.setBackground(GlassTheme.focusRounded(
                    Color.WHITE, Color.WHITE, dp(ctx, 2), r));
                btn.setTextColor(GlassTheme.TEXT_ON_LIGHT);
                v.animate().scaleX(1.06f).scaleY(1.06f).setDuration(150).start();
            } else {
                btn.setBackground(GlassTheme.focusRounded(
                    GlassTheme.SURFACE_ELEV, GlassTheme.BORDER_STRONG, dp(ctx, 1), r));
                btn.setTextColor(GlassTheme.TEXT_PRIMARY);
                v.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
            }
        });
        return btn;
    }

    private static LinearLayout buildExpandedDrawer(FrameLayout wrapper, Context ctx, NavCallback cb) {
        LinearLayout drawer = new LinearLayout(ctx);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setTag(TAG_DRAWER);
        drawer.setVisibility(View.INVISIBLE);
        drawer.setTranslationX(-dp(ctx, DRAWER_WIDTH_DP + 28));
        drawer.setAlpha(0f);
        GradientDrawable bg = GlassTheme.solidRounded(GlassTheme.GLASS_PANEL, dp(ctx, 18));
        bg.setStroke(dp(ctx, 1), GlassTheme.BORDER_STRONG);
        drawer.setBackground(bg);
        drawer.setElevation(dp(ctx, 28));
        int w = dp(ctx, DRAWER_WIDTH_DP);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.setMargins(dp(ctx, 14), dp(ctx, 12), 0, dp(ctx, 12));
        drawer.setLayoutParams(lp);

        ScrollView sv = new ScrollView(ctx);
        sv.setTag(TAG_SCROLL);
        sv.setVerticalScrollBarEnabled(false);
        sv.setFillViewport(true);
        sv.setFocusable(false);
        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12));
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
        addNavItem(wrapper, content, ctx, "+", "Add New Profile", false, v -> {
            setExpanded(wrapper, false, ctx, false);
            cb.onAddProfile();
        });

        addDivider(content, ctx);
        addSectionLabel(content, ctx, "NAVIGATION & AI");
        addNavItem(wrapper, content, ctx, "\u2302", "Home", true, v -> {
            setExpanded(wrapper, false, ctx, true);
            cb.onNavigate("home");
        });
        addNavItem(wrapper, content, ctx, "\u25F7", "Watch History", false, v -> {
            setExpanded(wrapper, false, ctx, false);
            cb.onWatchHistoryOpen();
        });
        addNavItem(wrapper, content, ctx, "\u2726", "Guide AI Taste", false, v -> {
            setExpanded(wrapper, false, ctx, false);
            cb.onNavigate("guide_ai");
        });
        addNavItem(wrapper, content, ctx, "\u2699", "Aura Settings", false, v -> {
            setExpanded(wrapper, false, ctx, false);
            cb.onSettingsOpen();
        });
        addNavItem(wrapper, content, ctx, "\u229E", "Phone Remote (QR)", false, v -> {
            setExpanded(wrapper, false, ctx, false);
            cb.onQrOpen();
        });

        addDivider(content, ctx);
        addSectionLabel(content, ctx, "ANDROID TV & INPUTS");
        addNavItem(wrapper, content, ctx, "\u2637", "Android TV Settings", false, v -> {
            setExpanded(wrapper, false, ctx, true);
            cb.onNavigate("sys_tv_settings");
        });
        addNavItem(wrapper, content, ctx, "H1", "HDMI 1 Input", false, v -> {
            setExpanded(wrapper, false, ctx, true);
            cb.onHdmiSelect(1);
        });
        addNavItem(wrapper, content, ctx, "H2", "HDMI 2 Input", false, v -> {
            setExpanded(wrapper, false, ctx, true);
            cb.onHdmiSelect(2);
        });
        addNavItem(wrapper, content, ctx, "\u224B", "Wi-Fi & Network", false, v -> {
            setExpanded(wrapper, false, ctx, true);
            cb.onNavigate("sys_wi-fi");
        });

        LinearLayout statusBox = new LinearLayout(ctx);
        statusBox.setOrientation(LinearLayout.VERTICAL);
        statusBox.setPadding(dp(ctx, 6), dp(ctx, 12), dp(ctx, 6), dp(ctx, 4));
        statusBox.setTag(TAG_STATUS);
        content.addView(statusBox, GlassTheme.matchWrapParams());
    }

    private static void addDrawerHeader(FrameLayout wrapper, LinearLayout content, Context ctx) {
        LinearLayout hdr = new LinearLayout(ctx);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER_VERTICAL);
        hdr.setPadding(dp(ctx, 6), 0, dp(ctx, 6), dp(ctx, 6));

        TextView badge = GlassTheme.makeText(ctx, "\u2630  AURA TV", 12.5f,
            GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1);
        hdr.addView(badge, GlassTheme.weightParams(1f));

        TextView closeHint = GlassTheme.makeText(ctx, "\u2192 Close", 9.5f,
            GlassTheme.TEXT_MUTED, GlassTheme.FONT_MEDIUM, 1);
        hdr.addView(closeHint, GlassTheme.wrapParams());
        content.addView(hdr, GlassTheme.matchWrapParams());
    }

    private static void addSectionLabel(LinearLayout content, Context ctx, String text) {
        TextView lbl = GlassTheme.makeText(ctx, text, 8.5f,
            GlassTheme.TEXT_MUTED, GlassTheme.FONT_BOLD, 1);
        lbl.setLetterSpacing(0.08f);
        lbl.setPadding(dp(ctx, 8), dp(ctx, 4), 0, dp(ctx, 2));
        content.addView(lbl, GlassTheme.wrapParams());
    }

    private static void addNavItem(FrameLayout wrapper, LinearLayout parent, Context ctx,
                                    String badgeText, String label, boolean isFirst,
                                    View.OnClickListener click) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, 10), dp(ctx, 5), dp(ctx, 12), dp(ctx, 5));
        if (isFirst) row.setTag(TAG_FIRST_ITEM);

        TextView iconPill = createIconPill(ctx, badgeText);
        row.addView(iconPill);

        TextView labelTv = GlassTheme.makeText(ctx, label, 11f,
            GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1);
        labelTv.setPadding(dp(ctx, 10), 0, 0, 0);
        row.addView(labelTv, GlassTheme.wrapParams());

        bindRowFocusAndClick(wrapper, row, iconPill, labelTv, false, click, ctx);
        parent.addView(row, itemParams(ctx));
    }

    private static TextView createIconPill(Context ctx, String badgeText) {
        TextView tv = GlassTheme.makeText(ctx, badgeText, 10.5f,
            GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1);
        tv.setGravity(Gravity.CENTER);
        int s = dp(ctx, 24);
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
        row.setPadding(dp(ctx, 10), dp(ctx, 5), dp(ctx, 12), dp(ctx, 5));

        TextView avatar = createAvatar(ctx, name, active);
        row.addView(avatar);

        String display = active ? (name + "  \u2022 Active") : name;
        TextView label = GlassTheme.makeText(ctx, display, 11f,
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
        TextView avatar = GlassTheme.makeText(ctx, initial, 10.5f, fg, GlassTheme.FONT_BOLD, 1);
        avatar.setGravity(Gravity.CENTER);
        int s = dp(ctx, 24);
        avatar.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        avatar.setBackground(GlassTheme.solidRounded(bg, dp(ctx, 12)));
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
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_BACK) {
                setExpanded(wrapper, false, ctx);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                return true; // Stay strictly inside the left navigation drawer
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                LinearLayout drawer = findByTag(wrapper, TAG_DRAWER);
                if (drawer == null) return true;
                List<View> rows = new ArrayList<>();
                collectFocusableRows(drawer, rows);
                int idx = rows.indexOf(v);
                if (idx >= 0) {
                    int nextIdx = (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) ? idx + 1 : idx - 1;
                    if (nextIdx >= 0 && nextIdx < rows.size()) {
                        View target = rows.get(nextIdx);
                        target.requestFocus();
                        scrollDrawerToRow(wrapper, target, ctx);
                    }
                }
                return true; // Always consume so focus never leaks to the right side!
            }
            return false;
        });
        row.setOnFocusChangeListener((v, focused) -> {
            if (focused) {
                row.setBackground(GlassTheme.focusRounded(
                    Color.WHITE, Color.WHITE, dp(ctx, 2), dp(ctx, 10)));
                labelView.setTextColor(GlassTheme.TEXT_ON_LIGHT);
                row.animate().scaleX(1.02f).scaleY(1.02f).setDuration(140).start();
            } else {
                row.setBackground(isActiveProfile
                    ? GlassTheme.solidRounded(GlassTheme.SURFACE_ELEV, dp(ctx, 10)) : null);
                labelView.setTextColor(isActiveProfile
                    ? GlassTheme.GOLD_ACCENT : GlassTheme.TEXT_PRIMARY);
                row.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
            }
        });
    }

    private static void collectFocusableRows(View root, List<View> out) {
        if (root == null || root.getVisibility() != View.VISIBLE) return;
        if (root.isFocusable() && root instanceof LinearLayout) {
            out.add(root);
            return;
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectFocusableRows(g.getChildAt(i), out);
            }
        }
    }

    private static void scrollDrawerToRow(FrameLayout wrapper, View target, Context ctx) {
        ScrollView sv = findByTag(wrapper, TAG_SCROLL);
        if (sv == null) return;
        int y = 0;
        View cur = target;
        while (cur != null && cur != sv) {
            y += cur.getTop();
            cur = cur.getParent() instanceof View ? (View) cur.getParent() : null;
        }
        int desiredScrollY = Math.max(0, y - dp(ctx, 140));
        ObjectAnimator anim = ObjectAnimator.ofInt(sv, "scrollY", sv.getScrollY(), desiredScrollY);
        anim.setDuration(200);
        anim.setInterpolator(GlassTheme.EASE);
        anim.start();
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
