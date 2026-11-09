package com.aura.tvprobe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Starts MediaProbeService on device boot so the launcher is always available.
 */
public final class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        try {
            Intent svc = new Intent(context, MediaProbeService.class);
            context.startService(svc);
        } catch (Throwable ignored) {}
    }
}
