package com.zcoderemote.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.Uri;

public class Notifications {
    static final String CH_SERVICE = "service";
    static final String CH_INTERACTION = "interaction";
    static final String CH_STATUS = "status";

    static void init(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;

        NotificationChannel svc = new NotificationChannel(CH_SERVICE, "后台保活",
                NotificationManager.IMPORTANCE_LOW);
        svc.setShowBadge(false);
        nm.createNotificationChannel(svc);

        nm.createNotificationChannel(new NotificationChannel(CH_INTERACTION, "需要你处理",
                NotificationManager.IMPORTANCE_HIGH));
        nm.createNotificationChannel(new NotificationChannel(CH_STATUS, "任务状态",
                NotificationManager.IMPORTANCE_DEFAULT));
    }

    static PendingIntent openApp(Context c) {
        Intent i = new Intent(c, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static PendingIntent openSettings(Context c) {
        Intent i = new Intent(c, SettingsActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, 1, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static Notification ongoing(Context c) {
        Notification.Action settings = new Notification.Action.Builder(
                Icon.createWithResource(c, android.R.drawable.ic_menu_preferences),
                "设置", openSettings(c)).build();
        return new Notification.Builder(c, CH_SERVICE)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("ZCode 远程")
                .setContentText("后台保活中")
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(openApp(c))
                .addAction(settings)
                .build();
    }

    static boolean isInteraction(String title) {
        String t = title == null ? "" : title;
        String l = t.toLowerCase();
        return t.contains("需要你的确认") || t.contains("需要你的回复") || t.contains("计划等待确认")
                || t.contains("等待确认")
                || l.contains("needs your") || l.contains("confirmation")
                || l.contains("approval") || l.contains("needs confirmation")
                || l.contains("needs reply") || l.contains("awaiting");
    }

    /** Notification produced by the page itself. */
    static void page(Context c, String title, String body) {
        if (!Prefs.notifications(c)) return;
        post(c, isInteraction(title) ? CH_INTERACTION : CH_STATUS, title, body, true);
    }

    /** Notification produced by native code (terminal errors, recovery, ...). */
    static void status(Context c, String title, String body) {
        if (!Prefs.notifications(c)) return;
        post(c, CH_STATUS, title, body, true);
    }

    /** Notification for a finished download; tapping it opens the file. */
    static void downloaded(Context c, String title, String body, Uri uri, String mime) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        PendingIntent pi = openApp(c);
        if (uri != null && !"file".equals(uri.getScheme())) {
            try {
                Intent view = new Intent(Intent.ACTION_VIEW);
                view.setDataAndType(uri, mime == null || mime.length() == 0 ? "*/*" : mime);
                view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                if (view.resolveActivity(c.getPackageManager()) != null) {
                    pi = PendingIntent.getActivity(c, 2, view,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                }
            } catch (Throwable ignored) {
            }
        }
        String b = body == null ? "" : body;
        Notification n = new Notification.Builder(c, CH_STATUS)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title == null || title.length() == 0 ? "已下载" : title)
                .setContentText(b)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .build();
        nm.notify(2003, n);
    }

    static void post(Context c, String ch, String title, String body, boolean autoCancel) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        // One live notification per kind: new events replace the previous one
        // instead of piling up in the shade.
        int id = CH_INTERACTION.equals(ch) ? 2001 : 2002;        String t = (title == null || title.length() == 0) ? "ZCode 远程" : title;
        String b = body == null ? "" : body;
        Notification n = new Notification.Builder(c, ch)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(t)
                .setContentText(b)
                .setStyle(new Notification.BigTextStyle().bigText(b))
                .setAutoCancel(autoCancel)
                .setContentIntent(openApp(c))
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .build();
        nm.notify(id, n);
    }
}
