package com.aura.tvprobe;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.service.notification.NotificationListenerService;

/**
 * Starts MediaProbeService on device boot so the launcher is always available.
 */
public final class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String act = intent != null ? intent.getAction() : "";
        if (!Intent.ACTION_BOOT_COMPLETED.equals(act) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(act)) return;
        try {
            NotificationListenerService.requestRebind(new ComponentName(context, MediaProbeService.class));
            context.startService(new Intent(context, MediaProbeService.class));
        } catch (Throwable ignored) {}
    }
}
