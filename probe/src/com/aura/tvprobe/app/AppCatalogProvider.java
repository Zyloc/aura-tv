package com.aura.tvprobe;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Single-responsibility provider that discovers installed Android TV & Leanback
 * applications, includes the Google Play Store, and filters out system bloatware.
 */
public final class AppCatalogProvider {

    private static final String PLAY_STORE_PKG = "com.android.vending";

    private AppCatalogProvider() {}

    public static List<JSONObject> discoverTvApps(Context ctx) {
        List<JSONObject> list = new ArrayList<>();
        Set<String> added = createExcludedSet(ctx.getPackageName());
        PackageManager pm = ctx.getPackageManager();
        addInstalledCoreApps(pm, list, added);
        scanLauncherCategory(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, list, added);
        scanLauncherCategory(pm, Intent.CATEGORY_LAUNCHER, list, added);
        return list;
    }

    static Set<String> createExcludedSet(String selfPkg) {
        Set<String> set = new HashSet<>();
        if (selfPkg != null) set.add(selfPkg);
        set.add("com.spocky.projengmenu");
        set.add("com.android.tv.settings");
        set.add("com.android.settings");
        return set;
    }

    private static void addInstalledCoreApps(PackageManager pm, List<JSONObject> list, Set<String> added) {
        for (String[] app : RegionProviderRegistry.getCoreStreamingAppsCatalog()) {
            if (!added.contains(app[1]) && (isPackageInstalled(pm, app[1]) || PLAY_STORE_PKG.equals(app[1]))) {
                added.add(app[1]);
                addAppJson(list, app[0], app[1], app[2]);
            }
        }
    }

    private static void scanLauncherCategory(PackageManager pm, String category,
                                             List<JSONObject> list, Set<String> added) {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
        List<ResolveInfo> resolved = pm.queryIntentActivities(query, 0);
        if (resolved == null) return;
        for (ResolveInfo ri : resolved) {
            processResolveInfo(pm, ri, list, added);
        }
    }

    private static void processResolveInfo(PackageManager pm, ResolveInfo ri,
                                           List<JSONObject> list, Set<String> added) {
        if (ri == null || ri.activityInfo == null) return;
        String pkg = ri.activityInfo.packageName;
        if (added.contains(pkg) || isUnwantedSystemApp(ri.activityInfo.applicationInfo, pkg)) return;
        CharSequence lbl = ri.loadLabel(pm);
        String name = lbl != null ? lbl.toString().trim() : pkg;
        if (isHiddenUtilityLabel(name, pkg)) return;
        added.add(pkg);
        addAppJson(list, name, pkg, "#1E293B");
    }

    public static boolean isUnwantedSystemApp(ApplicationInfo ai, String pkg) {
        if (ai == null) return false;
        boolean sys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        boolean updated = (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
        return shouldFilterSystemPackage(pkg, sys, updated);
    }

    public static boolean shouldFilterSystemPackage(String pkg, boolean isSystem, boolean isUpdatedSystem) {
        if (!isSystem || isUpdatedSystem) return false;
        if (PLAY_STORE_PKG.equals(pkg)) return false;
        return pkg == null || !pkg.contains("youtube");
    }

    public static boolean isHiddenUtilityLabel(String label, String pkg) {
        String low = label != null ? label.toLowerCase() : "";
        String p = pkg != null ? pkg.toLowerCase() : "";
        if (low.contains("phone media") || low.equals("aura ai") || low.contains("package installer")) return true;
        if (low.contains("password") || low.contains("factory") || low.contains("webview")) return true;
        return low.contains("sink") || (!p.isEmpty() && TvDeviceProfile.isProtectedSystemOrOemPackage(p) && !PLAY_STORE_PKG.equals(p));
    }

    private static boolean isPackageInstalled(PackageManager pm, String pkg) {
        try {
            pm.getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void addAppJson(List<JSONObject> list, String name, String pkg, String color) {
        try {
            list.add(new JSONObject().put("name", name).put("package", pkg).put("color", color));
        } catch (Exception ignored) {}
    }
}
