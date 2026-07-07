package com.bjsp123.rl2.web;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

/**
 * TeaVM wrappers over the {@code window.rl2} API defined in
 * {@code webapp-extra/rl2-bridge.js}. All async supabase/gzip work lives in
 * the JS shim; this class only marshals plain values and callbacks, keeping
 * the Java/JS boundary promise-free.
 *
 * <p>Callbacks run on the browser main thread - the same thread the game loop
 * runs on - so they may touch game state directly, but implementations still
 * route through {@code Gdx.app.postRunnable} where ordering with the render
 * loop matters.
 */
public final class JsBridge {

    private JsBridge() {}

    @JSFunctor
    public interface VoidCallback extends JSObject {
        void run();
    }

    @JSFunctor
    public interface StringCallback extends JSObject {
        void accept(String value);
    }

    @JSFunctor
    public interface BoolCallback extends JSObject {
        void accept(boolean ok);
    }

    /** True when rl2-config.js carries Supabase credentials. */
    @JSBody(script = "return !!(window.rl2 && window.rl2.available());")
    public static native boolean available();

    /** Begin loading supabase-js + session restore. Safe to call when
     *  unconfigured (no-op). */
    @JSBody(script = "if (window.rl2) window.rl2.init();")
    public static native void init();

    @JSBody(script = "return window.rl2 ? window.rl2.getUserId() : null;")
    public static native String getUserId();

    @JSBody(script = "return window.rl2 ? window.rl2.getDisplayName() : null;")
    public static native String getDisplayName();

    @JSBody(params = {"cb"}, script = "if (window.rl2) window.rl2.onAuth(cb);")
    public static native void onAuth(VoidCallback cb);

    @JSBody(params = {"provider"}, script = "if (window.rl2) window.rl2.signIn(provider);")
    public static native void signIn(String provider);

    @JSBody(script = "if (window.rl2) window.rl2.signOut();")
    public static native void signOut();

    /** cb receives JSON {@code [{"key":k,"client_ts":t},...]} or null. */
    @JSBody(params = {"cb"}, script = "window.rl2 ? window.rl2.kvList(cb) : cb(null);")
    public static native void kvList(StringCallback cb);

    /** cb receives the value ("" = tombstone) or null on error/missing. */
    @JSBody(params = {"key", "cb"}, script = "window.rl2 ? window.rl2.kvGet(key, cb) : cb(null);")
    public static native void kvGet(String key, StringCallback cb);

    @JSBody(params = {"key", "value", "clientTs", "cb"},
            script = "window.rl2 ? window.rl2.kvUpsert(key, value, clientTs, cb) : cb(false);")
    public static native void kvUpsert(String key, String value, double clientTs, BoolCallback cb);

    /** Run {@code cb} every {@code ms} milliseconds on the browser main thread. */
    @JSBody(params = {"ms", "cb"}, script = "if (window.rl2) window.rl2.every(ms, cb);")
    public static native void every(int ms, VoidCallback cb);
}
