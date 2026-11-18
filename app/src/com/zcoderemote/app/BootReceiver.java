package com.zcoderemote.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) && Prefs.enabled(context)) {
            context.startForegroundService(new Intent(context, KeepAliveService.class));
        }
    }
}
