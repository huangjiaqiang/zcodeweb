package com.zcoderemote.app;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

public class MainActivity extends Activity {
    static final String TAG = "ZCRemote";

    WebView web;
    String ua = "Mozilla/5.0";
    volatile boolean foreground = true;
    volatile String currentUrl = "";
    String lastLoadedUrl;
    final Handler handler = new Handler();

    ValueCallback<Uri[]> fileChooser;
    static final int REQ_FILE_CHOOSER = 4001;

    /** Assembles chunked blob downloads coming from the JS bridge. */
    static class BlobPull {
        final String mime;
        final int total;
        int received;
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();

        BlobPull(String mime, int total) {
            this.mime = mime;
            this.total = total;
        }
    }

    final Object blobLock = new Object();
    final Map<String, BlobPull> blobPulls = new HashMap<>();

    int reloadCount = 0;
    long reloadWindowStart = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Notifications.init(this);
        if (Prefs.enabled(this)) {
            startService(new Intent(this, KeepAliveService.class));
        }
        buildUi();
        setupWebView();

        String debugUrl = getIntent() != null ? getIntent().getStringExtra("url") : null;
        String url = (debugUrl != null && debugUrl.length() > 0) ? debugUrl : Prefs.url(this);
        load(url);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.hasExtra("openDownloadId")) {
            // Test hook: runs the same VIEW intent the completion
            // notification would fire, under this app's own uid.
            openDownloadedFile(intent.getLongExtra("openDownloadId", -1));
            return;
        }
        String debugUrl = intent.getStringExtra("url");
        if (debugUrl != null && debugUrl.length() > 0) {
            load(debugUrl);
        } else if (web != null) {
            web.reload();
        }
    }

    void openDownloadedFile(long id) {
        try {
            DownloadManager dm = getSystemService(DownloadManager.class);
            Uri uri = dm.getUriForDownloadedFile(id);
            String mime = dm.getMimeTypeForDownloadedFile(id);
            Log.i(TAG, "open downloaded id=" + id + " uri=" + uri + " mime=" + mime);
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(uri, mime == null || mime.length() == 0 ? "*/*" : mime);
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(view);
        } catch (Throwable t) {
            Log.w(TAG, "open downloaded failed " + t);
        }
    }

    void buildUi() {
        FrameLayout root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Tiny settings affordance; the app is otherwise a full-screen page.
        TextView gear = new TextView(this);
        gear.setText("\u2699");
        gear.setTextSize(18);
        gear.setTextColor(0xCCFFFFFF);
        gear.setAlpha(0.35f);
        gear.setGravity(Gravity.CENTER);
        gear.setClickable(true);
        gear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(44), dp(44),
                Gravity.TOP | Gravity.END);
        lp.topMargin = dp(6);
        lp.rightMargin = dp(6);
        root.addView(gear, lp);

        setContentView(root);
    }

    int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        ua = s.getUserAgentString();

        CookieManager.getInstance().setAcceptCookie(true);

        web.addJavascriptInterface(new NotifyBridge(), "ZCNotify");
        web.addJavascriptInterface(new ReportBridge(), "ZCReport");

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileChooser != null) fileChooser.onReceiveValue(null);
                fileChooser = callback;
                try {
                    Intent intent;
                    try {
                        intent = params.createIntent();
                    } catch (Throwable t) {
                        intent = new Intent(Intent.ACTION_GET_CONTENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("*/*");
                    }
                    if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    }
                    startActivityForResult(intent, REQ_FILE_CHOOSER);
                    return true;
                } catch (Throwable t) {
                    fileChooser = null;
                    return false;
                }
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                          android.os.Message resultMsg) {
                // Route OAuth popups into the main WebView: only one relay
                // connection may exist per session.
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                final WebView tmp = new WebView(MainActivity.this);
                tmp.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                        if (web != null) web.loadUrl(request.getUrl().toString());
                        return true;
                    }
                });
                transport.setWebView(tmp);
                resultMsg.sendToTarget();
                return true;
            }
        });

        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimetype, long contentLength) {
                startDownload(url, contentDisposition, mimetype);
            }
        });

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                try {
                    if (request.isForMainFrame()) {
                        String url = request.getUrl().toString();
                        if (shouldInject(url)) {
                            WebResourceResponse r = intercept(url);
                            if (r != null) return r;
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "intercept error " + t);
                }
                return null;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                currentUrl = url == null ? "" : url;
                Log.i(TAG, "page start " + url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                Log.i(TAG, "page finish " + url);
                pushForeground();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        android.webkit.WebResourceError error) {
                if (request.isForMainFrame()) {
                    Log.w(TAG, "load error " + error.getErrorCode() + " " + request.getUrl());
                }
            }
        });
    }

    boolean shouldInject(String url) {
        if (url == null) return false;
        return url.startsWith("https://zcode.z.ai/remote/")
                || url.startsWith("http://127.0.0.1:")
                || url.startsWith("http://localhost:");
    }

    void load(String url) {
        if (url == null || url.length() == 0) url = Prefs.url(this);
        lastLoadedUrl = url;
        Log.i(TAG, "load " + url);
        web.loadUrl(url);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Prefs.dirty) {
            Prefs.dirty = false;
            load(Prefs.url(this));
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        foreground = true;
        pushForeground();
    }

    @Override
    protected void onStop() {
        super.onStop();
        foreground = false;
        pushForeground();
    }

    void pushForeground() {
        if (web == null) return;
        final String js = "window.__zcForeground=" + (foreground ? "true" : "false") + ";";
        web.evaluateJavascript(js, null);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            moveTaskToBack(true);
        }
    }

    @Override
    protected void onDestroy() {
        if (isFinishing() && web != null) {
            try {
                web.loadUrl("about:blank");
                web.destroy();
            } catch (Throwable ignored) {
            }
            web = null;
        }
        super.onDestroy();
    }

    // ---------- injection ----------

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
            Log.w(TAG, "asset " + name + " " + t);
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

            String cookie = CookieManager.getInstance().getCookie(urlStr);
            if (cookie != null && cookie.length() > 0) {
                c.setRequestProperty("Cookie", cookie);
            }

            int code = c.getResponseCode();

            Map<String, List<String>> headers = c.getHeaderFields();
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                String k = e.getKey();
                if (k != null && "Set-Cookie".equalsIgnoreCase(k)) {
                    for (String v : e.getValue()) {
                        try {
                            CookieManager.getInstance().setCookie(urlStr, v);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }

            String finalHost = c.getURL() != null ? c.getURL().getHost() : u.getHost();
            if (finalHost != null && !finalHost.equalsIgnoreCase(u.getHost())) {
                return null;
            }

            String ct = c.getContentType();
            if (code != 200 || ct == null || !ct.toLowerCase(Locale.US).contains("text/html")) {
                return null;
            }

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
            String inject = "<script>\n" + readAsset("inject.js") + "\n</script>\n";
            Matcher m = Pattern.compile("(?i)<head[^>]*>").matcher(html);
            if (m.find()) {
                html = html.substring(0, m.end()) + inject + html.substring(m.end());
            } else {
                html = inject + html;
            }

            WebResourceResponse r = new WebResourceResponse("text/html", "utf-8",
                    new ByteArrayInputStream(html.getBytes("UTF-8")));
            r.setStatusCodeAndReasonPhrase(200, "OK");
            Log.i(TAG, "injected " + urlStr + " (" + html.length() + " bytes)");
            return r;
        } catch (Throwable t) {
            Log.w(TAG, "intercept fail " + t);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER) {
            ValueCallback<Uri[]> cb = fileChooser;
            fileChooser = null;
            if (cb != null) {
                Uri[] results = null;
                if (resultCode == RESULT_OK && data != null) {
                    if (data.getData() != null) {
                        results = new Uri[]{data.getData()};
                    } else if (data.getClipData() != null) {
                        ClipData clip = data.getClipData();
                        results = new Uri[clip.getItemCount()];
                        for (int i = 0; i < clip.getItemCount(); i++) {
                            results[i] = clip.getItemAt(i).getUri();
                        }
                    }
                }
                cb.onReceiveValue(results);
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    // ---------- download ----------

    /**
     * DownloadManager has no access to the page's localStorage JWT, and many
     * artifact endpoints require it, so read it from the WebView first.
     */
    void startDownload(final String url, final String contentDisposition, final String mimetype) {
        if (web == null) return;
        final String cookie = CookieManager.getInstance().getCookie(url);
        web.evaluateJavascript(
                "(function(){try{return localStorage.getItem('zcodejwttoken')}catch(e){return null}})()",
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        String token = null;
                        if (value != null && !"null".equals(value) && value.length() > 2) {
                            token = value.substring(1, value.length() - 1);
                        }
                        enqueueDownload(url, contentDisposition, mimetype, cookie, token);
                    }
                });
    }

    void enqueueDownload(String url, String contentDisposition, String mimetype,
                         String cookie, String token) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            // blob:/data: downloads are handled by the JS bridge, not here.
            Toast.makeText(this, "不支持的下载地址：" + url, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            DownloadManager dm = getSystemService(DownloadManager.class);
            String name = uniqueDmName(dm, fileName(url, contentDisposition));
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            if (mimetype != null && mimetype.length() > 0) req.setMimeType(mimetype);
            req.addRequestHeader("User-Agent", ua);
            if (cookie != null && cookie.length() > 0) req.addRequestHeader("Cookie", cookie);
            if (token != null && token.length() > 0) {
                req.addRequestHeader("Authorization", "Bearer " + token);
            }
            req.setTitle(name);
            // System notification only while downloading; our own completion
            // notification takes over and opens the file when tapped.
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            dm.enqueue(req);
            Log.i(TAG, "download enqueued as " + name + " <- " + url);
        } catch (Throwable t) {
            Log.w(TAG, "download fail " + t);
            Toast.makeText(this, "下载失败：" + t, Toast.LENGTH_LONG).show();
        }
    }

    static String fileName(String url, String contentDisposition) {
        String name = null;
        if (contentDisposition != null) {
            Matcher m = Pattern.compile("filename\\*=(?:UTF-8|utf-8)''([^;]+)")
                    .matcher(contentDisposition);
            if (m.find()) {
                try {
                    name = URLDecoder.decode(m.group(1).replace("+", "%20"), "UTF-8");
                } catch (Throwable ignored) {
                }
            }
            if (name == null) {
                m = Pattern.compile("filename=\"?([^\";]+)\"?").matcher(contentDisposition);
                if (m.find()) name = m.group(1).trim();
            }
        }
        if (name == null || name.length() == 0) {
            String path = Uri.parse(url).getPath();
            if (path != null) name = path.substring(path.lastIndexOf('/') + 1);
        }
        return sanitizeName(name);
    }

    static String sanitizeName(String n) {
        String s = n == null ? "" : n.replaceAll("[/\\\\\r\n\t]", "_").trim();
        if (s.length() == 0) s = "download-" + System.currentTimeMillis();
        return s.length() > 120 ? s.substring(s.length() - 120) : s;
    }

    /** Inserts a suffix into the name part; the extension stays intact. */
    static String insertBeforeExt(String name, String suffix) {
        int dot = name.lastIndexOf('.');
        if (dot > 0) return name.substring(0, dot) + suffix + name.substring(dot);
        return name + suffix;
    }

    boolean dmHasSuccessfulTitle(DownloadManager dm, String title) {
        Cursor cur = null;
        try {
            cur = dm.query(new DownloadManager.Query()
                    .setFilterByStatus(DownloadManager.STATUS_SUCCESSFUL));
            int col = cur.getColumnIndex(DownloadManager.COLUMN_TITLE);
            while (cur.moveToNext()) {
                if (title.equals(cur.getString(col))) return true;
            }
        } catch (Throwable ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        return false;
    }

    /** name.ext → name-1.ext → name-2.ext … so a duplicate never overwrites. */
    String uniqueDmName(DownloadManager dm, String wanted) {
        String name = wanted;
        for (int i = 1; dmHasSuccessfulTitle(dm, name); i++) {
            if (i > 99) {
                name = insertBeforeExt(wanted, "-" + System.currentTimeMillis());
                break;
            }
            name = insertBeforeExt(wanted, "-" + i);
        }
        return name;
    }

    /** True if we already own a MediaStore download with this display name. */
    boolean blobNameExists(String display) {
        Cursor cur = null;
        try {
            cur = getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Downloads.DISPLAY_NAME},
                    MediaStore.Downloads.DISPLAY_NAME + "=?",
                    new String[]{display}, null);
            return cur != null && cur.moveToFirst();
        } catch (Throwable t) {
            return false;
        } finally {
            if (cur != null) cur.close();
        }
    }

    /** Writes a completed blob download into MediaStore Downloads. */
    void saveBlob(final String name, final String mime, final byte[] data) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final String m =
                            mime == null || mime.length() == 0 ? "application/octet-stream" : mime;
                    // Duplicate names are renamed in the name part
                    // (name-1.ext, name-2.ext …) so the extension — and with it
                    // file-type recognition — stays intact.
                    String wanted = sanitizeName(name);
                    Uri uri = null;
                    String used = null;
                    for (int attempt = 0; attempt < 12 && uri == null; attempt++) {
                        String candidate;
                        if (attempt == 0) {
                            candidate = wanted;
                        } else if (attempt <= 10) {
                            candidate = insertBeforeExt(wanted, "-" + attempt);
                        } else {
                            candidate = insertBeforeExt(wanted, "-" + System.currentTimeMillis());
                        }
                        if (blobNameExists(candidate)) continue;
                        try {
                            uri = insertBlobRow(candidate, m);
                        } catch (Throwable t) {
                            Log.w(TAG, "mediastore insert attempt " + attempt + " " + t);
                        }
                        if (uri != null) used = candidate;
                    }
                    if (uri == null) throw new IllegalStateException("MediaStore insert failed");
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    if (os == null) throw new IllegalStateException("openOutputStream failed");
                    os.write(data);
                    os.close();
                    Log.i(TAG, "blob saved " + name + " as " + used
                            + " (" + data.length + " bytes) uri=" + uri);
                    Notifications.downloaded(MainActivity.this, "已下载", used, uri, m);
                } catch (Throwable t) {
                    Log.w(TAG, "blob save fail " + t);
                }
            }
        }).start();
    }

    Uri insertBlobRow(String display, String mime) {
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Downloads.DISPLAY_NAME, display);
        cv.put(MediaStore.Downloads.MIME_TYPE, mime);
        cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        return getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
    }

    // ---------- JS bridges ----------

    class NotifyBridge {
        @JavascriptInterface
        public void show(String title, String body) {
            if (foreground) return;
            if (!trusted(currentUrl)) return;
            Notifications.page(MainActivity.this, title, body);
        }
    }

    class ReportBridge {
        @JavascriptInterface
        public void log(String msg) {
            Log.i(TAG, "js " + msg);
        }

        @JavascriptInterface
        public void terminal(final String text, final boolean transient_) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    onTerminal(text, transient_);
                }
            });
        }

        @JavascriptInterface
        public void blobChunk(final String name, final String mime, final String b64,
                              final int seq, final int total) {
            byte[] part;
            try {
                part = Base64.decode(b64, Base64.DEFAULT);
            } catch (Throwable t) {
                Log.w(TAG, "blob chunk decode fail seq=" + seq + " " + t);
                synchronized (blobLock) {
                    blobPulls.remove(name);
                }
                return;
            }
            BlobPull pull;
            synchronized (blobLock) {
                pull = blobPulls.get(name);
                if (pull == null) {
                    pull = new BlobPull(mime, total);
                    blobPulls.put(name, pull);
                }
                pull.buf.write(part, 0, part.length);
                pull.received++;
                if (pull.received < pull.total) return;
                blobPulls.remove(name);
            }
            Log.i(TAG, "blob assembled " + name + " chunks=" + pull.received);
            saveBlob(name, pull.mime, pull.buf.toByteArray());
        }
    }

    boolean trusted(String url) {
        if (url == null) return false;
        return url.startsWith("https://zcode.z.ai/")
                || url.startsWith("http://127.0.0.1:")
                || url.startsWith("http://localhost:");
    }

    void onTerminal(String text, boolean transient_) {
        Log.i(TAG, "terminal " + text + " transient=" + transient_);
        Notifications.status(this, "远程会话中断", text);
        if (transient_ && canReload()) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (web != null) {
                        Log.i(TAG, "auto reload after transient failure");
                        web.reload();
                    }
                }
            }, 2000);
        }
    }

    boolean canReload() {
        long now = SystemClock.elapsedRealtime();
        if (reloadWindowStart == 0 || now - reloadWindowStart > 600000) {
            reloadWindowStart = now;
            reloadCount = 0;
        }
        if (reloadCount >= 5) return false;
        reloadCount++;
        return true;
    }
}
