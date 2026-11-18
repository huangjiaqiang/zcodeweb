(function () {
  if (window.__zcProbe) return;
  window.__zcProbe = 1;

  var socks = [];
  function L(m) {
    try { ZCProbe.log(String(m)); } catch (e) { }
  }
  var origVis = Object.getOwnPropertyDescriptor(Document.prototype, 'visibilityState');
  var origHidden = Object.getOwnPropertyDescriptor(Document.prototype, 'hidden');
  function real() {
    try {
      return 'realVis=' + (origVis && origVis.get ? origVis.get.call(document) : '?') +
             ' realHidden=' + (origHidden && origHidden.get ? origHidden.get.call(document) : '?');
    } catch (e) { return 'real=?'; }
  }
  function vis() {
    return 'vis=' + document.visibilityState + ' hidden=' + document.hidden +
           ' focus=' + document.hasFocus() + ' ' + real();
  }

  L('probe installed ' + vis());
  L('ua=' + navigator.userAgent);

  window.addEventListener('visibilitychange', function () { L('EVT visibilitychange ' + vis()); }, true);
  window.addEventListener('focus', function () { L('EVT focus ' + vis()); }, true);
  window.addEventListener('blur', function () { L('EVT blur ' + vis()); }, true);
  window.addEventListener('pagehide', function () { L('EVT pagehide ' + vis()); }, true);
  window.addEventListener('pageshow', function () { L('EVT pageshow ' + vis()); }, true);
  window.addEventListener('freeze', function () { L('EVT freeze'); }, true);
  window.addEventListener('resume', function () { L('EVT resume'); }, true);
  window.addEventListener('online', function () { L('EVT online'); }, true);
  window.addEventListener('offline', function () { L('EVT offline'); }, true);

  try {
    var OW = window.WebSocket;
    if (OW) {
      window.WebSocket = new Proxy(OW, {
        construct: function (t, args) {
          var ws = Reflect.construct(t, args);
          socks.push(ws);
          L('WS new ' + args[0]);
          ws.addEventListener('open', function () { L('WS open'); });
          ws.addEventListener('close', function (e) {
            L('WS close code=' + e.code + ' clean=' + e.wasClean + ' reason=' + (e.reason || ''));
          });
          ws.addEventListener('error', function () { L('WS error'); });
          return ws;
        }
      });
      L('WS proxied');
    } else {
      L('WS API missing');
    }
  } catch (e) {
    L('WS proxy fail ' + e);
  }

  function state() {
    var n = document.querySelector('[data-web-remote-control-reconnect-notice]');
    var st = document.querySelector('[role=status]');
    var h1 = document.querySelector('h1');
    var arr = [];
    for (var i = 0; i < socks.length; i++) arr.push(socks[i].readyState);
    return 'sockets=[' + arr.join(',') + ']' +
           ' notice=' + (n ? '"' + (n.textContent || '').trim().slice(0, 40) + '"' : '-') +
           ' status=' + (st ? '"' + (st.textContent || '').trim().slice(0, 40) + '"' : '-') +
           ' h1=' + (h1 ? '"' + (h1.textContent || '').trim().slice(0, 40) + '"' : '-') +
           ' url=' + location.href;
  }

  setInterval(function () { L('TICK ' + vis() + ' ' + state()); }, 10000);
})();
