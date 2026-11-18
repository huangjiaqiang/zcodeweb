package com.zcoderemote.app;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class SettingsActivity extends Activity {
    EditText url;
    CheckBox keep;
    CheckBox wake;
    CheckBox notif;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Notifications.init(this);

        int p = dp(16);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p, p, p, p);
        scroll.addView(root);
        setContentView(scroll);

        root.addView(section("会话"));
        root.addView(label("地址固定，可手动替换"));
        url = new EditText(this);
        url.setText(Prefs.url(this));
        url.setTextSize(12);
        url.setSingleLine(false);
        url.setMinLines(2);
        url.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_URI
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        root.addView(url);

        keep = new CheckBox(this);
        keep.setText("后台保活（前台服务 + 常驻通知）");
        keep.setChecked(Prefs.enabled(this));
        root.addView(keep);

        wake = new CheckBox(this);
        wake.setText("熄屏时保持唤醒（会增加耗电）");
        wake.setChecked(Prefs.wakelock(this));
        root.addView(wake);

        notif = new CheckBox(this);
        notif.setText("转发页面的任务通知");
        notif.setChecked(Prefs.notifications(this));
        root.addView(notif);

        Button save = new Button(this);
        save.setText("保存并重新加载");
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveAndClose();
            }
        });
        root.addView(save);

        root.addView(section("华为 EMUI 需要手动设置"));
        Button battery = new Button(this);
        battery.setText("电池优化白名单");
        battery.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openBattery();
            }
        });
        root.addView(battery);

        Button startup = new Button(this);
        startup.setText("后台运行设置说明");
        startup.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openStartup();
            }
        });
        root.addView(startup);

        Button appSettings = new Button(this);
        appSettings.setText("应用设置");
        appSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAppSettings();
            }
        });
        root.addView(appSettings);

        root.addView(section("说明"));
        root.addView(label(
                "1. 设置 → 应用 → 应用启动管理：本应用改为「手动管理」，三个开关全部打开。\n" +
                "2. 电池优化选择「不允许」。\n" +
                "3. 通知设置里把「需要你处理」调为重要并允许横幅。\n" +
                "4. 最近任务里下拉锁定本应用，避免一键清理。\n" +
                "5. 登录态保存在 WebView 本地，卸载重装需重新登录。"));
    }

    void saveAndClose() {
        String u = url.getText().toString().trim();
        if (u.length() == 0) u = Prefs.DEFAULT_URL;
        Prefs.setUrl(this, u);
        Prefs.setEnabled(this, keep.isChecked());
        Prefs.setWakelock(this, wake.isChecked());
        Prefs.setNotifications(this, notif.isChecked());

        if (keep.isChecked()) {
            startService(new Intent(this, KeepAliveService.class));
        } else {
            stopService(new Intent(this, KeepAliveService.class));
        }

        Prefs.dirty = true;
        finish();
    }

    void openBattery() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
            return;
        } catch (Throwable ignored) {
        }
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Throwable ignored) {
            toast("请手动在系统设置中允许忽略电池优化");
        }
    }

    void openStartup() {
        String[][] targets = new String[][]{
                {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"},
        };
        for (String[] t : targets) {
            try {
                Intent i = new Intent();
                i.setComponent(new ComponentName(t[0], t[1]));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return;
            } catch (Throwable ignored) {
            }
        }
        toast("华为限制了直达入口，请到 设置 → 应用 → 应用启动管理 手动配置");
        openAppSettings();
    }

    void openAppSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            toast("无法打开应用设置");
        }
    }

    void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    TextView label(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(13);
        v.setPadding(0, dp(4), 0, dp(8));
        return v;
    }

    TextView section(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(15);
        v.setPadding(0, dp(20), 0, dp(8));
        v.setTextColor(0xFF4F8CFF);
        return v;
    }

    int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }
}
