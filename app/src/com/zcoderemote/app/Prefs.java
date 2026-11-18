package com.zcoderemote.app;

import android.content.Context;
import android.content.SharedPreferences;

public class Prefs {
    public static final String DEFAULT_URL = Local.URL;

    /** Set by SettingsActivity so MainActivity reloads the (possibly new) URL on resume. */
    public static volatile boolean dirty = false;

    static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("app", Context.MODE_PRIVATE);
    }

    public static String url(Context c) {
        return sp(c).getString("url", DEFAULT_URL);
    }

    public static void setUrl(Context c, String u) {
        sp(c).edit().putString("url", u).apply();
    }

    public static boolean enabled(Context c) {
        return sp(c).getBoolean("enabled", true);
    }

    public static void setEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean("enabled", v).apply();
    }

    public static boolean wakelock(Context c) {
        return sp(c).getBoolean("wakelock", true);
    }

    public static void setWakelock(Context c, boolean v) {
        sp(c).edit().putBoolean("wakelock", v).apply();
    }

    public static boolean notifications(Context c) {
        return sp(c).getBoolean("notifications", true);
    }

    public static void setNotifications(Context c, boolean v) {
        sp(c).edit().putBoolean("notifications", v).apply();
    }
}
