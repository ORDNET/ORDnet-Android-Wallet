/* =========================================================================
   ORDplug BRC-100 provider-shim (v3.2, Android) — runs INSIDE the web page.

   Deliberately key-free (architecture rule): this shim only relays calls to
   the native wallet over the Android addJavascriptInterface bridge
   (OrdplugBrc100Android). All key material and all crypto live in the
   separate, network-isolated engine WebView; the page can never reach them.
   window.CWI is the FIRST substrate @bsv/sdk's WalletClient('auto') probes,
   so any BRC-100 app detects this wallet by calling getVersion({}).

   Error contract (BRC-100 / @bsv/sdk WindowCWISubstrate): methods RETURN a
   promise that REJECTS with an Error carrying name (WERR_*), message, code
   and isError=true. Never a resolved {status:'error'} object — an app must
   never mistake a refusal for success.
   ========================================================================= */
(function () {
  'use strict';
  if (window.CWI) return;                       // never clobber another wallet
  if (!(window.OrdplugBrc100Android && window.OrdplugBrc100Android.postMessage)) return;

  var pending = {};
  var nextId = 1;

  function callWallet(method, args, originator) {
    return new Promise(function (resolve, reject) {
      var id = 'cwi' + (nextId++);
      pending[id] = { resolve: resolve, reject: reject };
      try {
        window.OrdplugBrc100Android.postMessage(JSON.stringify({
          id: id,
          method: method,
          args: JSON.stringify(args === undefined ? {} : args),
          originator: String(originator || window.location.origin || '')
        }));
      } catch (e) {
        delete pending[id];
        var err = new Error('The wallet bridge is unavailable.');
        err.name = 'WERR_UNKNOWN'; err.code = 1; err.isError = true;
        reject(err);
      }
    });
  }

  /* native side answers via this — ok:true resolves with the result object,
     ok:false REJECTS with a standards-shaped WalletError */
  window.__brc100Deliver = function (payload) {
    var p = pending[payload.id];
    if (!p) return;
    delete pending[payload.id];
    if (payload.ok) {
      p.resolve(payload.result);
    } else {
      var info = payload.error || {};
      var err = new Error(info.message || 'Unknown wallet error.');
      err.name = info.name || 'WERR_UNKNOWN';
      err.code = typeof info.code === 'number' ? info.code : 1;
      err.isError = true;
      p.reject(err);
    }
  };

  /* the full 28-method BRC-100 surface (source: @bsv/sdk WindowCWISubstrate).
     Every method exists; the native side decides support level and rejects
     unimplemented ones explicitly. */
  var METHODS = [
    'createAction', 'signAction', 'abortAction', 'listActions',
    'internalizeAction', 'listOutputs', 'relinquishOutput',
    'getPublicKey', 'revealCounterpartyKeyLinkage', 'revealSpecificKeyLinkage',
    'encrypt', 'decrypt', 'createHmac', 'verifyHmac',
    'createSignature', 'verifySignature',
    'acquireCertificate', 'listCertificates', 'proveCertificate',
    'relinquishCertificate', 'discoverByIdentityKey', 'discoverByAttributes',
    'isAuthenticated', 'waitForAuthentication',
    'getHeight', 'getHeaderForHeight', 'getNetwork', 'getVersion'
  ];

  var CWI = {};
  METHODS.forEach(function (m) {
    CWI[m] = function (args, originator) { return callWallet(m, args, originator); };
  });
  window.CWI = CWI;
})();
