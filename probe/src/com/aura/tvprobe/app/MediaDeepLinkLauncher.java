package com.aura.tvprobe;

import android.app.SearchManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;
import org.json.JSONObject;
import java.util.List;

/**
 * Single-responsibility launcher for deep-linking into OTT streaming apps,
 * executing in-app search fallbacks, launching Leanback packages, and opening
 * Android TV system/HDMI settings.
 */
public final class MediaDeepLinkLauncher {

    private static final String NETFLIX_PKG = "com.netflix.ninja";
    private static final String PLAY_STORE_PKG = "com.android.vending";

    private MediaDeepLinkLauncher() {}

    public static void launchMediaItem(Context ctx, AuraDb db, JSONObject item) {
        if (item == null) return;
        String cleanTitle = extractCleanTitle(item);
        String pkg = resolvePackage(item);
        String uri = resolveLaunchUri(db, item, cleanTitle);
        if (tryDirectDeepLink(ctx, uri, pkg, item)) return;
        if (tryInAppSearch(ctx, cleanTitle, pkg)) return;
        launchPackage(ctx, pkg);
    }

    public static String extractCleanTitle(JSONObject item) {
        if (item == null) return "";
        String series = item.optString("series_title", "");
        String rawTitle = item.optString("title", "");
        String base = !series.isEmpty() ? series : rawTitle;
        return base.split("\u00B7")[0].trim();
    }

    public static String resolvePackage(JSONObject item) {
        if (item == null) return "";
        String rawApp = item.optString("app", item.optString("app_name", ""));
        return item.optString("launch_pkg", AuraDb.resolveAppPackage(rawApp)).trim();
    }

    private static String resolveLaunchUri(AuraDb db, JSONObject item, String cleanTitle) {
        String uri = item.optString("launch_uri", "").trim();
        if (!uri.isEmpty() || cleanTitle.isEmpty() || db == null) return uri;
        uri = db.findNativeLaunchUri(cleanTitle);
        if (!uri.isEmpty()) return uri;
        return lookupCachedGtiUri(db, cleanTitle, item);
    }

    private static String lookupCachedGtiUri(AuraDb db, String cleanTitle, JSONObject item) {
        String cached = getCachedMetadataJson(db, cleanTitle.toLowerCase());
        if (cached == null) return "";
        try {
            JSONObject cm = new JSONObject(cached);
            if (item.optString("launch_pkg", "").isEmpty()) {
                item.put("launch_pkg", cm.optString("launch_pkg", "").trim());
            }
            return cm.optString("launch_uri", "").trim();
        } catch (Exception ignored) { return ""; }
    }

    private static String getCachedMetadataJson(AuraDb db, String keyLower) {
        String cached = db.getCachedGti("jw_imdb_v6:" + keyLower);
        return cached != null ? cached : db.getCachedGti("jw_imdb_v5:" + keyLower);
    }

    private static boolean tryDirectDeepLink(Context ctx, String uri, String pkg, JSONObject item) {
        if (uri == null || uri.isEmpty()) return false;
        String finalPkg = pkg.isEmpty() ? item.optString("launch_pkg", "").trim() : pkg;
        try {
            Intent intent = buildDeepLinkIntent(uri, finalPkg);
            ctx.startActivity(intent);
            return true;
        } catch (Exception e) {
            return tryUnpackagedWebUri(ctx, uri);
        }
    }

    public static Intent buildDeepLinkIntent(String uri, String pkg) throws Exception {
        Intent deepIntent = parseOrCreateViewIntent(uri, pkg);
        if (NETFLIX_PKG.equals(pkg) || NETFLIX_PKG.equals(deepIntent.getPackage())) {
            deepIntent.putExtra("source", "30");
        }
        deepIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return deepIntent;
    }

    private static Intent parseOrCreateViewIntent(String uri, String pkg) throws Exception {
        if (uri.startsWith("intent:") || uri.contains("#Intent;")) {
            return Intent.parseUri(uri, Intent.URI_INTENT_SCHEME);
        }
        Intent deepIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
        if (pkg != null && !pkg.isEmpty()) deepIntent.setPackage(pkg);
        return deepIntent;
    }

    private static boolean tryUnpackagedWebUri(Context ctx, String uri) {
        if (uri.startsWith("intent:") || uri.contains("#Intent;")) return false;
        try {
            Intent unpkg = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
            unpkg.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(unpkg);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean tryInAppSearch(Context ctx, String cleanTitle, String pkg) {
        if (cleanTitle.isEmpty() || pkg.isEmpty()) return false;
        if (NETFLIX_PKG.equals(pkg) && tryNetflixSearch(ctx, cleanTitle)) return true;
        if (tryActionIntent(ctx, Intent.ACTION_SEARCH, pkg, cleanTitle)) return true;
        return tryActionIntent(ctx, "android.media.action.MEDIA_PLAY_FROM_SEARCH", pkg, cleanTitle);
    }

    private static boolean tryNetflixSearch(Context ctx, String cleanTitle) {
        if (tryActionIntent(ctx, Intent.ACTION_SEARCH, NETFLIX_PKG, cleanTitle)) return true;
        try {
            Uri uri = Uri.parse("http://www.netflix.com/search/" + Uri.encode(cleanTitle));
            Intent nfView = new Intent(Intent.ACTION_VIEW, uri).setPackage(NETFLIX_PKG);
            nfView.putExtra("source", "30");
            nfView.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(nfView);
            return true;
        } catch (Exception ignored) { return false; }
    }

    private static boolean tryActionIntent(Context ctx, String action, String pkg, String query) {
        try {
            Intent intent = new Intent(action).setPackage(pkg);
            intent.putExtra(SearchManager.QUERY, query);
            intent.putExtra("query", query);
            if (NETFLIX_PKG.equals(pkg)) intent.putExtra("source", "30");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
            return true;
        } catch (Exception ignored) { return false; }
    }

    public static void launchPackage(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        PackageManager pm = ctx.getPackageManager();
        if (tryStandardLaunchIntent(ctx, pm, pkg)) return;
        if (PLAY_STORE_PKG.equals(pkg) && tryMarketIntent(ctx)) return;
        if (tryResolvedMainActivity(ctx, pm, pkg)) return;
        Toast.makeText(ctx, "App not installed: " + pkg, Toast.LENGTH_SHORT).show();
    }

    private static boolean tryStandardLaunchIntent(Context ctx, PackageManager pm, String pkg) {
        try {
            Intent li = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (li == null) li = pm.getLaunchIntentForPackage(pkg);
            if (li == null) return false;
            li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(li);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean tryMarketIntent(Context ctx) {
        try {
            Intent market = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MARKET);
            market.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(market);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean tryResolvedMainActivity(Context ctx, PackageManager pm, String pkg) {
        try {
            List<ResolveInfo> ris = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).setPackage(pkg), 0);
            if (ris == null || ris.isEmpty() || ris.get(0).activityInfo == null) return false;
            Intent comp = new Intent(Intent.ACTION_MAIN).setClassName(pkg, ris.get(0).activityInfo.name);
            comp.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(comp);
            return true;
        } catch (Exception e) { return false; }
    }

    public static void openHdmiInput(Context ctx, int port) {
        String inputId = resolveHdmiInputId(ctx, port);
        try {
            Uri uri = android.media.tv.TvContract.buildChannelUriForPassthroughInput(inputId);
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
        } catch (Exception e) {
            openSystemSetting(ctx, "sys_display");
        }
    }

    public static String resolveHdmiInputId(Context ctx, int port) {
        int safePort = Math.max(1, Math.min(3, port));
        String suffix = "HW" + (safePort + 1);
        String queried = querySystemHdmiInputId(ctx, suffix);
        return queried != null ? queried : "com.mediatek.tvinput/.hdmi.HDMIInputService/" + suffix;
    }

    private static String querySystemHdmiInputId(Context ctx, String suffix) {
        if (ctx == null) return null;
        try {
            android.media.tv.TvInputManager tim =
                (android.media.tv.TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
            return tim != null ? matchHdmiInputFromList(tim.getTvInputList(), suffix) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String matchHdmiInputFromList(List<android.media.tv.TvInputInfo> list, String suffix) {
        if (list == null) return null;
        String fallback = null;
        for (android.media.tv.TvInputInfo info : list) {
            String id = info.getId();
            if (id != null && id.toLowerCase().contains("hdmi")) {
                if (id.endsWith(suffix)) return id;
                if (fallback == null) fallback = id;
            }
        }
        return fallback;
    }

    public static void openSystemSetting(Context ctx, String dest) {
        if (dest != null && (dest.contains("tv_settings") || dest.contains("all"))) {
            if (tryTvMainSettings(ctx)) return;
        }
        String action = resolveSettingsAction(dest);
        if (!tryStartAction(ctx, action)) {
            tryStartAction(ctx, Settings.ACTION_SETTINGS);
        }
    }

    public static String resolveSettingsAction(String dest) {
        if (dest == null) return Settings.ACTION_SETTINGS;
        if (dest.contains("wi-fi") || dest.contains("network")) return Settings.ACTION_WIFI_SETTINGS;
        if (dest.contains("bluetooth")) return Settings.ACTION_BLUETOOTH_SETTINGS;
        if (dest.contains("display")) return Settings.ACTION_DISPLAY_SETTINGS;
        if (dest.contains("audio") || dest.contains("sound")) return Settings.ACTION_SOUND_SETTINGS;
        return Settings.ACTION_SETTINGS;
    }

    private static boolean tryTvMainSettings(Context ctx) {
        try {
            Intent tvIntent = new Intent(Settings.ACTION_SETTINGS);
            tvIntent.setComponent(new ComponentName(
                "com.android.tv.settings", "com.android.tv.settings.MainSettings"));
            tvIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(tvIntent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean tryStartAction(Context ctx, String action) {
        try {
            Intent intent = new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
