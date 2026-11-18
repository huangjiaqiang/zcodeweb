(function () {
  'use strict';
  if (window.__zcWrap) return;
  window.__zcWrap = 1;

  function log(m) { try { ZCReport.log(String(m)); } catch (e) { } }

  // 1) Lifecycle shim: the page must always believe it is visible, otherwise
  //    it suspends the relay transport and stops reconnecting.
  try {
    var d = Document.prototype;
    Object.defineProperty(d, 'visibilityState', { configurable: true, get: function () { return 'visible'; } });
    Object.defineProperty(d, 'hidden', { configurable: true, get: function () { return false; } });
    Object.defineProperty(d, 'webkitVisibilityState', { configurable: true, get: function () { return 'visible'; } });
    Object.defineProperty(d, 'webkitHidden', { configurable: true, get: function () { return false; } });
  } catch (e) { log('vis shim fail ' + e); }

  var swallow = function (e) { e.stopImmediatePropagation(); };
  window.addEventListener('visibilitychange', swallow, true);
  document.addEventListener('visibilitychange', swallow, true);
  document.addEventListener('freeze', swallow, true);
  document.addEventListener('resume', swallow, true);

  // 2) Focus: native code sets window.__zcForeground on start/stop. The page's
  //    only hasFocus() consumer is the notification gate.
  window.__zcForeground = true;
  try {
    Document.prototype.hasFocus = function () { return window.__zcForeground !== false; };
  } catch (e) { log('focus shim fail ' + e); }

  // 3) Notification bridge: WebView has no Notification API, so the page's own
  //    task notifications are dead without this shim.
  function FakeNotification(title, opts) {
    var body = '';
    try { body = (opts && opts.body) || ''; } catch (e) { }
    try { ZCNotify.show(String(title == null ? '' : title), String(body)); } catch (e) { }
  }
  FakeNotification.permission = 'granted';
  FakeNotification.maxActions = 0;
  FakeNotification.requestPermission = function (cb) {
    if (typeof cb === 'function') { try { cb('granted'); } catch (e) { } }
    return Promise.resolve('granted');
  };
  try { window.Notification = FakeNotification; } catch (e) { log('notif shim fail ' + e); }

  // 4) Terminal-state observer: report page-level failures so native code can
  //    notify and (for transient ones) reload.
  var TRANSIENT = ['响应超时', '连接超时', '无法连接中转'];
  var TERMINAL = ['会话结束', '电脑端离线', '设备接管', '工作区关闭', '链接不可用', '响应超时', '连接超时', '无法连接中转'];
  var last = '';
  function scan() {
    try {
      var h1 = document.querySelector('h1');
      if (!h1) return;
      var t = (h1.textContent || '').replace(/\s+/g, ' ').trim();
      if (!t || t === last) return;
      for (var i = 0; i < TERMINAL.length; i++) {
        if (t.indexOf(TERMINAL[i]) >= 0) {
          var transient_ = false;
          for (var j = 0; j < TRANSIENT.length; j++) {
            if (t.indexOf(TRANSIENT[j]) >= 0) transient_ = true;
          }
          last = t;
          try { ZCReport.terminal(t, transient_); } catch (e) { }
          return;
        }
      }
    } catch (e) { }
  }
  function startObserver() {
    try {
      new MutationObserver(scan).observe(document.body, { childList: true, subtree: true, characterData: true });
    } catch (e) { }
    setInterval(scan, 5000);
    scan();
  }
  if (document.body) startObserver();
  else document.addEventListener('DOMContentLoaded', startObserver);

  // 5) blob:/data: downloads: DownloadManager cannot handle these schemes, so
  //    read the content in the page and stream it to native in base64 chunks.
  document.addEventListener('click', function (e) {
    var el = e.target;
    var a = el && el.closest ? el.closest('a[download]') : null;
    if (!a) return;
    var href = a.getAttribute('href') || '';
    if (href.indexOf('blob:') !== 0 && href.indexOf('data:') !== 0) return;
    e.preventDefault();
    e.stopPropagation();
    var name = a.getAttribute('download') || 'download';
    fetch(href).then(function (r) { return r.blob(); }).then(function (b) {
      var fr = new FileReader();
      fr.onload = function () {
        var du = String(fr.result);
        var comma = du.indexOf(',');
        var mime = du.slice(5, comma).split(';')[0] || 'application/octet-stream';
        var b64 = du.slice(comma + 1);
        var CH = 400000; // multiple of 4: each chunk decodes standalone
        var total = Math.ceil(b64.length / CH);
        for (var i = 0; i < total; i++) {
          try {
            ZCReport.blobChunk(String(name), mime, b64.substr(i * CH, CH), i, total);
          } catch (err) {
            log('blob chunk fail ' + err);
            return;
          }
        }
        log('blob sent ' + name + ' ~' + (b64.length * 3 / 4 | 0) + ' bytes in ' + total + ' chunks');
      };
      fr.readAsDataURL(b);
    }).catch(function (err) { log('blob fetch fail ' + err); });
  }, true);

  log('wrapper injected');
})();
