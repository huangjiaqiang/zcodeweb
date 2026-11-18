package com.zcprobe;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

public class MainActivity extends Activity {
    static final String TAG = "ZCProbe";
    static final String DEFAULT_URL = "https://zcode.z.ai/remote/v4";

    WebView web;
    EditText urlEdit;
    TextView logView;
    volatile boolean shimMode = false;
    volatile String ua = "";
    File logFile;
    final Object logLock = new Object();
    final ArrayDeque<String> recent = new ArrayDeque<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        logFile = new File(getExternalFilesDir(null), "probe.log");
        startService(new Intent(this, ProbeService.class));
        buildUi();
        log("=== ZCProbe start ===");
        log("webview: " + webViewInfo());
        String url = getIntent() != null ? getIntent().getStringExtra("url") : null;
        if (url == null || url.trim().isEmpty()) url = DEFAULT_URL;
        shimMode = getIntent() != null && getIntent().getBooleanExtra("shim", false);
        urlEdit.setText(url);
        log("mode=" + (shimMode ? "SHIM" : "OBSERVE"));
        log("url=" + url);
        web.loadUrl(url);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String url = intent.getStringExtra("url");
        if (url == null || url.trim().isEmpty()) url = urlEdit.getText().toString();
        shimMode = intent.getBooleanExtra("shim", false);
        urlEdit.setText(url);
        log("newIntent mode=" + (shimMode ? "SHIM" : "OBSERVE"));
        web.loadUrl(url);
    }

    String webViewInfo() {
        try {
            android.content.pm.PackageInfo p = WebView.getCurrentWebViewPackage();
            if (p != null) return p.packageName + " " + p.versionName;
        } catch (Throwable t) {
            return "err:" + t;
        }
        return "unknown";
    }

    void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        urlEdit = new EditText(this);
        urlEdit.setSingleLine(true);
        urlEdit.setTextSize(9);
        top.addView(urlEdit, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button obsBtn = new Button(this);
        obsBtn.setText("观察");
        Button shimBtn = new Button(this);
        shimBtn.setText("垫片");
        top.addView(obsBtn);
        top.addView(shimBtn);
        root.addView(top);

        web = new WebView(this);
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        logView = new TextView(this);
        logView.setTextSize(8);
        logView.setTextColor(0xFF7CFC00);
        logView.setBackgroundColor(0xCC101010);
        logView.setPadding(8, 4, 8, 4);
        root.addView(logView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 220));
        setContentView(root);

        obsBtn.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                shimMode = false;
                log("reload OBSERVE");
                web.loadUrl(urlEdit.getText().toString());
            }
        });
        shimBtn.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                shimMode = true;
                log("reload SHIM");
                web.loadUrl(urlEdit.getText().toString());
            }
        });

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        ua = s.getUserAgentString();

        web.addJavascriptInterface(new Bridge(), "ZCProbe");
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
                return false;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                try {
                    if (request.isForMainFrame()) {
                        String u = request.getUrl().toString();
                        if (u.startsWith("https://zcode.z.ai/remote/")) {
                            WebResourceResponse r = intercept(u);
                            if (r != null) return r;
                        }
                    }
                } catch (Throwable t) {
                    log("intercept err " + t);
                }
                return null;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                log("onPageStarted " + url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                log("onPageFinished " + url);
            }
        });
    }

    class Bridge {
        @JavascriptInterface
        public void log(String msg) {
            MainActivity.this.log("JS " + msg);
        }
    }

    String readAsset(String name) {
        InputStream in = null;
        try {
            in = getAssets().open(name);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        } catch (Throwable t) {
            log("asset err " + name + " " + t);
            return "";
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) { }
        }
    }

    WebResourceResponse intercept(String urlStr) {
        HttpURLConnection c = null;
        try {
            URL u = new URL(urlStr);
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(25000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Accept-Encoding", "identity");
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            c.setRequestProperty("User-Agent", ua);
            int code = c.getResponseCode();
            String ct = c.getContentType();
            log("intercept code=" + code + " ct=" + ct + " shim=" + shimMode);
            if (code != 200 || ct == null || !ct.contains("text/html")) return null;

            InputStream in = c.getInputStream();
            String enc = c.getContentEncoding();
            if (enc != null && enc.toLowerCase(Locale.US).contains("gzip")) {
                in = new GZIPInputStream(in);
            }
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();

            String html = bo.toString("UTF-8");
            StringBuilder inject = new StringBuilder();
            inject.append("<script>\n").append(readAsset("probe.js")).append("\n</script>\n");
            if (shimMode) {
                inject.append("<script>\n").append(readAsset("shim.js")).append("\n</script>\n");
            }

            Matcher m = Pattern.compile("(?i)<head[^>]*>").matcher(html);
            if (m.find()) {
                html = html.substring(0, m.end()) + inject + html.substring(m.end());
            } else {
                html = inject + html;
            }
            log("injected html bytes=" + html.length());

            WebResourceResponse r = new WebResourceResponse("text/html", "utf-8",
                    new ByteArrayInputStream(html.getBytes("UTF-8")));
            r.setStatusCodeAndReasonPhrase(200, "OK");
            return r;
        } catch (Throwable t) {
            log("intercept fail " + t);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    void log(String msg) {
        final String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date()) + " " + msg;
        Log.i(TAG, line);
        synchronized (logLock) {
            try (FileOutputStream fo = new FileOutputStream(logFile, true)) {
                fo.write((line + "\n").getBytes("UTF-8"));
            } catch (Throwable ignored) {
            }
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                recent.addLast(line);
                while (recent.size() > 50) recent.removeFirst();
                StringBuilder sb = new StringBuilder();
                for (String l : recent) sb.append(l).append('\n');
                logView.setText(sb.toString());
            }
        });
    }
}
