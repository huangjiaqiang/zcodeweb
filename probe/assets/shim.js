(function () {
  if (window.__zcShim) return;
  window.__zcShim = 1;

  function L(m) {
    try { ZCProbe.log('SHIM ' + m); } catch (e) { }
  }

  try {
    Object.defineProperty(Document.prototype, 'visibilityState', {
      configurable: true,
      get: function () { return 'visible'; }
    });
    Object.defineProperty(Document.prototype, 'hidden', {
      configurable: true,
      get: function () { return false; }
    });
    Object.defineProperty(Document.prototype, 'webkitVisibilityState', {
      configurable: true,
      get: function () { return 'visible'; }
    });
    Object.defineProperty(Document.prototype, 'webkitHidden', {
      configurable: true,
      get: function () { return false; }
    });
    L('visibility faked');
  } catch (e) {
    L('fake visibility fail ' + e);
  }

  var swallow = function (e) { e.stopImmediatePropagation(); };
  window.addEventListener('visibilitychange', swallow, true);
  document.addEventListener('visibilitychange', swallow, true);
  L('visibilitychange swallowed');

  try {
    Document.prototype.hasFocus = function () { return false; };
    L('hasFocus faked=false');
  } catch (e) {
    L('fake hasFocus fail ' + e);
  }
})();
