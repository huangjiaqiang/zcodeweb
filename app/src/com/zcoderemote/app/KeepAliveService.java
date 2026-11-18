package com.zcoderemote.app;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

public class KeepAliveService extends Service {
    static final String TAG = "ZCRemote";

    private PowerManager.WakeLock lock;
    private BroadcastReceiver screenReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        Notifications.init(this);
        startForeground(1, Notifications.ongoing(this));

        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    acquireLock();
                } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                    releaseLock();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenReceiver, filter);

        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null && !pm.isInteractive()) acquireLock();
    }

    private void acquireLock() {
        if (!Prefs.wakelock(this) || lock != null) return;
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm == null) return;
        lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zcode:remote");
        lock.setReferenceCounted(false);
        try {
            lock.acquire();
            Log.i(TAG, "wakelock acquired");
        } catch (Throwable t) {
            Log.w(TAG, "wakelock acquire failed " + t);
            lock = null;
        }
    }

    private void releaseLock() {
        if (lock == null) return;
        try {
            lock.release();
            Log.i(TAG, "wakelock released");
        } catch (Throwable ignored) {
        }
        lock = null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1, Notifications.ongoing(this));
        if (intent == null) {
            // System restarted the service after the process was killed.
            Notifications.status(this, "远程会话已恢复", "后台服务已被系统重启，点击打开");
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        releaseLock();
        if (screenReceiver != null) {
            try {
                unregisterReceiver(screenReceiver);
            } catch (Throwable ignored) {
            }
            screenReceiver = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
