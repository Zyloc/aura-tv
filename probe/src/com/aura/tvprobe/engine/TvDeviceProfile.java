package com.aura.tvprobe;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import org.json.JSONObject;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Multi-Brand OEM Hardware & Regional OTT Profile Abstraction.
 * Adapts Aura TV automatically across Sony Bravia, TCL, Hisense, Xiaomi, Philips,
 * Nvidia Shield, Chromecast with Google TV, Fire OS, and Amlogic/Realtek/MediaTek TVs.
 */
public final class TvDeviceProfile {

    private static final String[] PROTECTED_OEM_PREFIXES = {
        "com.aura.tvprobe", "katniss", "google.android.tts", "com.google.android.gms",
        "inputmethod", "tv.remote", "systemui", "providers.tv", "mediatek", "xiaomi",
        "mstar", "com.sony.dtv", "com.sony.bravia", "com.sonymobile", "com.tcl.",
        "com.ffalcon", "com.hisense", "com.jamdeo", "org.droidtv", "com.tpvision",
        "com.nvidia.", "com.amlogic", "com.realtek", "com.skyworth", "com.haier",
        "com.amazon.tv", "com.amazon.vizzini", "com.amazon.imp"
    };

    private static final String[] GLOBAL_OTT_KEYWORDS = {
        "netflix", "hotstar", "amazonvideo", "disney", "youtube.tv", "smarttube",
        "graymatrix", "sonyliv", "apple.atve", "xstream", "mxtech", "stremio",
        "wbd.stream", "hbo", "hulu", "peacock", "cbs.ott", "paramount", "crunchyroll",
        "iplayer", "itvx", "unext", "abema", "stan.and", "globo", "canal", "dazn", "fubo"
    };

    private TvDeviceProfile() {}

    public static String detectRuntimeOemFamily(AuraDb db) {
        String override = db != null ? db.getSetting("tv_brand_override", "").trim() : "";
        if (!override.isEmpty()) return detectOemFamily(override, override, "");
        return detectOemFamily(Build.MANUFACTURER, Build.BRAND, Build.HARDWARE);
    }

    public static String detectOemFamily(String manufacturer, String brand, String hardware) {
        String combined = ((manufacturer != null ? manufacturer : "") + " "
            + (brand != null ? brand : "") + " " + (hardware != null ? hardware : "")).toLowerCase(Locale.US);
        String tierA = matchMajorTvBrands(combined);
        if (!tierA.isEmpty()) return tierA;
        return matchPlatformBrands(combined);
    }

    private static String matchMajorTvBrands(String c) {
        if (c.contains("sony") || c.contains("bravia")) return "SONY";
        if (c.contains("tcl") || c.contains("ffalcon")) return "TCL";
        if (c.contains("hisense") || c.contains("jamdeo")) return "HISENSE";
        if (c.contains("xiaomi") || c.contains("mitv")) return "XIAOMI";
        if (c.contains("philips") || c.contains("tpvision") || c.contains("droidtv")) return "PHILIPS";
        if (c.contains("samsung")) return "SAMSUNG";
        return c.contains("lge") || c.contains("lg") ? "LG" : "";
    }

    private static String matchPlatformBrands(String c) {
        if (c.contains("nvidia") || c.contains("shield") || c.contains("tegra")) return "NVIDIA";
        if (c.contains("amazon") || c.contains("aft")) return "AMAZON";
        if (c.contains("google") || c.contains("chromecast") || c.contains("sabrina")) return "GOOGLE";
        if (c.contains("skyworth") || c.contains("coocaa")) return "SKYWORTH";
        return "GENERIC_ATV";
    }

    public static boolean isProtectedSystemOrOemPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;
        String p = pkg.toLowerCase(Locale.US);
        if ("android".equals(p)) return true;
        for (String token : PROTECTED_OEM_PREFIXES) {
            if (p.contains(token)) return true;
        }
        return false;
    }

    public static boolean isKnownHeavyOttPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        String p = pkg.toLowerCase(Locale.US);
        for (String kw : GLOBAL_OTT_KEYWORDS) {
            if (p.contains(kw)) return true;
        }
        return false;
    }

    public static String resolveFallbackHdmiInputId(String oemFamily, int port) {
        int safePort = Math.max(1, Math.min(4, port));
        String fam = oemFamily != null ? oemFamily.toUpperCase(Locale.US) : "";
        if ("SONY".equals(fam)) return "com.sony.dtv.tvinput/.hdmi.HdmiInputService/HW" + safePort;
        if ("TCL".equals(fam)) return "com.tcl.tvinput/.hdmi.HDMIInputService/HW" + safePort;
        if ("HISENSE".equals(fam)) return "com.hisense.tvinput/.hdmi.HdmiInputService/HW" + safePort;
        if ("PHILIPS".equals(fam)) return "org.droidtv.tvinput/.hdmi.HdmiInputService/HW" + safePort;
        return "com.mediatek.tvinput/.hdmi.HDMIInputService/HW" + (safePort + 1);
    }

    public static String inferCountryFromInstalledApps(Set<String> installedPkgs) {
        if (installedPkgs == null || installedPkgs.isEmpty()) return "";
        if (installedPkgs.contains("bbc.iplayer.android") || installedPkgs.contains("air.ITVMobilePlayer")) return "GB";
        if (installedPkgs.contains("jp.unext.mediaplayer") || installedPkgs.contains("tv.abema")) return "JP";
        if (installedPkgs.contains("in.startv.hotstar") || installedPkgs.contains("com.sonyliv")) return "IN";
        if (installedPkgs.contains("au.com.stan.and") || installedPkgs.contains("com.seven.plus")) return "AU";
        if (installedPkgs.contains("com.globo.globotv")) return "BR";
        if (installedPkgs.contains("com.hulu.livingroomplus") || installedPkgs.contains("com.peacocktv.peacockandroid")) return "US";
        return "";
    }

    public static Set<String> discoverInstalledOttPackages(Context ctx) {
        if (ctx == null) return Collections.emptySet();
        Set<String> out = new LinkedHashSet<>();
        try {
            PackageManager pm = ctx.getPackageManager();
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            collectVideoAppsFromResolveList(pm.queryIntentActivities(query, 0), out);
        } catch (Throwable ignored) {}
        return out;
    }

    private static void collectVideoAppsFromResolveList(List<ResolveInfo> list, Set<String> out) {
        if (list == null) return;
        for (ResolveInfo ri : list) {
            if (ri == null || ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            ApplicationInfo ai = ri.activityInfo.applicationInfo;
            if (!isProtectedSystemOrOemPackage(pkg) && isVideoOrOttCandidate(ai, pkg)) {
                out.add(pkg);
            }
        }
    }

    private static boolean isVideoOrOttCandidate(ApplicationInfo ai, String pkg) {
        if (isKnownHeavyOttPackage(pkg)) return true;
        return ai != null && Build.VERSION.SDK_INT >= 26 && ai.category == ApplicationInfo.CATEGORY_VIDEO;
    }

    public static JSONObject buildDeviceProfileJson(Context ctx, AuraDb db) {
        JSONObject o = new JSONObject();
        try {
            o.put("oem_family", detectRuntimeOemFamily(db));
            o.put("manufacturer", Build.MANUFACTURER);
            o.put("brand", Build.BRAND);
            o.put("model", Build.MODEL);
            o.put("sdk_int", Build.VERSION.SDK_INT);
            o.put("region_country", RegionProviderRegistry.getCountryCode(db));
            o.put("region_language", RegionProviderRegistry.getLanguageCode(db));
        } catch (Exception ignored) {}
        return o;
    }
}
