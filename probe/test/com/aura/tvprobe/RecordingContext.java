package com.aura.tvprobe;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageManager;
import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight pure-JVM {@link ContextWrapper} that records outbound intents without native JNI.
 */
public class RecordingContext extends ContextWrapper {

    public final List<Intent> startedActivities = new ArrayList<>();
    public final List<Intent> startedServices = new ArrayList<>();
    public final List<Intent> sentBroadcasts = new ArrayList<>();
    public boolean throwOnFirstActivity = false;

    public RecordingContext() {
        super(null);
    }

    @Override
    public Context getApplicationContext() {
        return this;
    }

    @Override
    public String getPackageName() {
        return "com.aura.tvprobe";
    }

    @Override
    public void startActivity(Intent intent) {
        if (throwOnFirstActivity) {
            throwOnFirstActivity = false;
            throw new RuntimeException("Simulated missing activity");
        }
        startedActivities.add(intent);
    }

    @Override
    public ComponentName startService(Intent service) {
        startedServices.add(service);
        return new ComponentName("com.aura.tvprobe", "com.aura.tvprobe.MediaProbeService");
    }

    @Override
    public void sendBroadcast(Intent intent) {
        sentBroadcasts.add(intent);
    }

    @Override
    public Object getSystemService(String name) {
        return null;
    }

    @Override
    public ContentResolver getContentResolver() {
        return null;
    }

    @Override
    public PackageManager getPackageManager() {
        return null;
    }
}
