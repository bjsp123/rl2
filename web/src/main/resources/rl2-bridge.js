// rl2 <-> Supabase bridge. Loaded by the TeaVM launcher before the game
// starts (alongside freetype.js). Exposes a small, promise-free API on
// window.rl2 that the Java side (JsBridge.java) calls via TeaVM @JSBody -
// all async work (supabase calls, gzip) stays here and reports back through
// plain callbacks, which is far more robust than composing Promises through
// the Java/JS boundary.
//
// The Supabase JS SDK is injected dynamically from the CDN only when
// rl2-config.js provides project credentials; with no credentials the game
// runs fully offline and window.rl2.available() reports false.
(function () {
  "use strict";

  var client = null;          // supabase client, once ready
  var user = null;            // supabase user object, or null
  var authListeners = [];     // Java-registered callbacks
  var initStarted = false;

  var cfg = window.RL2_CONFIG || { supabaseUrl: "", supabaseAnonKey: "" };
  var configured = !!(cfg.supabaseUrl && cfg.supabaseAnonKey);

  function fireAuthChanged() {
    for (var i = 0; i < authListeners.length; i++) {
      try { authListeners[i](); } catch (e) { console.error("rl2 auth listener", e); }
    }
  }

  function init() {
    if (initStarted || !configured) return;
    initStarted = true;
    var s = document.createElement("script");
    s.src = "https://cdn.jsdelivr.net/npm/@supabase/supabase-js@2/dist/umd/supabase.min.js";
    s.onload = function () {
      client = window.supabase.createClient(cfg.supabaseUrl, cfg.supabaseAnonKey);
      // Restores a persisted session (including the return leg of the OAuth
      // redirect) and reports every subsequent change.
      client.auth.onAuthStateChange(function (_event, session) {
        user = session ? session.user : null;
        fireAuthChanged();
      });
      client.auth.getSession().then(function (res) {
        user = (res.data && res.data.session) ? res.data.session.user : null;
        fireAuthChanged();
      });
    };
    s.onerror = function () { console.error("rl2: failed to load supabase-js"); };
    document.head.appendChild(s);
  }

  // ---- gzip helpers (browser CompressionStream), used transparently for
  // large values. Compressed values are marked with a "gz64:" prefix.
  var GZ_PREFIX = "gz64:";
  var GZ_THRESHOLD = 32 * 1024;

  function gzipToB64(str, cb) {
    try {
      var stream = new Blob([str]).stream().pipeThrough(new CompressionStream("gzip"));
      new Response(stream).arrayBuffer().then(function (buf) {
        var bytes = new Uint8Array(buf);
        var bin = "";
        for (var i = 0; i < bytes.length; i += 0x8000) {
          bin += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
        }
        cb(GZ_PREFIX + btoa(bin));
      }).catch(function () { cb(null); });
    } catch (e) { cb(null); }
  }

  function gunzipFromB64(value, cb) {
    try {
      var bin = atob(value.substring(GZ_PREFIX.length));
      var bytes = new Uint8Array(bin.length);
      for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
      var stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream("gzip"));
      new Response(stream).text().then(cb).catch(function () { cb(null); });
    } catch (e) { cb(null); }
  }

  window.rl2 = {
    available: function () { return configured; },
    init: init,

    // ---- auth ----------------------------------------------------------
    getUserId: function () { return user ? user.id : null; },
    getDisplayName: function () {
      if (!user) return null;
      var m = user.user_metadata || {};
      return m.full_name || m.name || user.email || user.id;
    },
    onAuth: function (cb) { authListeners.push(cb); },
    signIn: function (provider) {
      if (!client) return;
      client.auth.signInWithOAuth({
        provider: provider, // "google" | "facebook"
        options: { redirectTo: window.location.origin + window.location.pathname }
      });
    },
    signOut: function () {
      if (client) client.auth.signOut();
    },

    // ---- per-user key/value sync (kv table, RLS-scoped to auth.uid()) ---
    // kvList(cb): cb receives JSON '[{"key":k,"client_ts":t},...]' or null.
    kvList: function (cb) {
      if (!client || !user) { cb(null); return; }
      client.from("kv").select("key,client_ts").then(function (res) {
        cb(res.error ? null : JSON.stringify(res.data));
      });
    },
    // kvGet(key, cb): cb receives the value string, "" for tombstone, or
    // null on error/missing. Transparently gunzips "gz64:" values.
    kvGet: function (key, cb) {
      if (!client || !user) { cb(null); return; }
      client.from("kv").select("value").eq("key", key).maybeSingle().then(function (res) {
        if (res.error || !res.data) { cb(null); return; }
        var v = res.data.value;
        if (v && v.indexOf(GZ_PREFIX) === 0) gunzipFromB64(v, cb);
        else cb(v);
      });
    },
    // kvUpsert(key, value, clientTs, cb): cb receives true/false. Values over
    // the threshold are gzipped. Empty value = tombstone (deletion marker).
    kvUpsert: function (key, value, clientTs, cb) {
      if (!client || !user) { cb(false); return; }
      function put(v) {
        client.from("kv").upsert({
          user_id: user.id, key: key, value: v, client_ts: clientTs
        }).then(function (res) { cb(!res.error); });
      }
      if (value && value.length > GZ_THRESHOLD) {
        gzipToB64(value, function (gz) { put(gz !== null ? gz : value); });
      } else {
        put(value);
      }
    },

    // ---- scheduling ------------------------------------------------------
    // The sync pump: a plain interval on the browser main thread (the same
    // thread the game runs on, so Java callbacks are safe to call directly).
    every: function (ms, cb) { window.setInterval(cb, ms); }
  };
})();
