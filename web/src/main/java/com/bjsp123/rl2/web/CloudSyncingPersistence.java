package com.bjsp123.rl2.web;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.bjsp123.rl2.persistence.Persistence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Local-first cloud sync. Decorates {@link WebPersistence} so the game's
 * synchronous {@link Persistence} contract is served entirely from
 * localStorage - the game never blocks on the network - while a background
 * pump mirrors a whitelist of keys to the signed-in user's Supabase {@code kv}
 * table (via {@link JsBridge}).
 *
 * <p><b>Model.</b> Every synced write stamps a per-key client timestamp and
 * marks the key dirty (both persisted in localStorage, so a tab closed
 * mid-sync resumes cleanly). A {@code setInterval} pump drains dirty keys
 * upward; an auth-change reconcile pass compares per-key timestamps both ways
 * and lets the newer side win (last-write-wins). Deletes upload an empty-string
 * tombstone. Pre-sign-in local data has no timestamp, so on first sign-in it
 * uploads wherever the cloud has nothing newer - which IS the anonymous-to-
 * signed-in migration.
 *
 * <p>Offline behavior: everything works from localStorage; failed uploads stay
 * dirty and retry on the next pump tick.
 */
public final class CloudSyncingPersistence implements Persistence {

    /** Keys mirrored to the cloud. Everything else stays local-only (window
     *  size, the sync bookkeeping itself, any future device-local keys).
     *  Keep in sync with Settings' key constants + the save/meta slots. */
    private static final Set<String> SYNC_KEYS = new LinkedHashSet<>();
    static {
        for (int i = 0; i < 4; i++) {           // SaveSystem.SLOTS
            SYNC_KEYS.add("rl2-save-" + i);
            SYNC_KEYS.add("rl2-meta-" + i);
        }
        SYNC_KEYS.add("rl2-hall-of-fame");
        SYNC_KEYS.add("rl2-arena-hall-of-fame");
        SYNC_KEYS.add("rl2-achievements");
        // Preferences (ui/skin/Settings.java key constants).
        SYNC_KEYS.add("rl2-ui-scale-v3");
        SYNC_KEYS.add("rl2-ui-pixel-scale");
        SYNC_KEYS.add("rl2-quickslot-count");
        SYNC_KEYS.add("rl2-use-buff-icons");
        SYNC_KEYS.add("rl2-animation-speed");
        SYNC_KEYS.add("rl2-anim-queue-accel");
        SYNC_KEYS.add("rl2-mob-outline-width");
        SYNC_KEYS.add("rl2-mob-outline-darkness");
        SYNC_KEYS.add("rl2-mob-outline-smooth");
        SYNC_KEYS.add("rl2-log-font-scale");
        SYNC_KEYS.add("rl2-log-on");
        SYNC_KEYS.add("rl2-log-low-priority");
        SYNC_KEYS.add("rl2-log-non-player");
        SYNC_KEYS.add("rl2-log-expanded");
        SYNC_KEYS.add("rl2-instant-actions");
        SYNC_KEYS.add("rl2-low-res-render");
        SYNC_KEYS.add("rl2-perf-overlay");
        SYNC_KEYS.add("rl2-sfx-enabled");
        SYNC_KEYS.add("rl2-sfx-volume");
        SYNC_KEYS.add("rl2-music-enabled");
        SYNC_KEYS.add("rl2-music-volume");
        SYNC_KEYS.add("rl2-tips-enabled");
        SYNC_KEYS.add("rl2-melee-preview");
        SYNC_KEYS.add("rl2-colorblind-preset");
    }

    /** localStorage keys for the sync bookkeeping (never themselves synced). */
    private static final String DIRTY_KEY = "rl2-cloud-dirty";
    private static final String TS_KEY    = "rl2-cloud-ts";

    private static final int PUMP_INTERVAL_MS = 3000;

    private final WebPersistence local;
    private final Set<String> dirty = new LinkedHashSet<>();
    private final Map<String, Long> lastWriteTs = new LinkedHashMap<>();
    private boolean uploadInFlight = false;
    private boolean reconcileInFlight = false;

    public CloudSyncingPersistence(WebPersistence local) {
        this.local = local;
        loadBookkeeping();
    }

    /** Wire the background sync. Call once after the auth service exists;
     *  harmless when the bridge is unconfigured. */
    public void start(WebAuthService auth) {
        if (!JsBridge.available()) return;
        auth.addListener(this::reconcile);
        JsBridge.every(PUMP_INTERVAL_MS, this::pump);
    }

    // ---- synchronous Persistence contract (localStorage only) -------------

    @Override public String load(String key) { return local.load(key); }

    @Override public boolean exists(String key) { return local.exists(key); }

    @Override
    public void save(String key, String value) {
        local.save(key, value);
        markDirty(key);
    }

    @Override
    public void delete(String key) {
        local.delete(key);
        markDirty(key);   // pump uploads an empty tombstone
    }

    private void markDirty(String key) {
        if (!SYNC_KEYS.contains(key)) return;
        lastWriteTs.put(key, System.currentTimeMillis());
        dirty.add(key);
        saveBookkeeping();
    }

    // ---- upward pump: drain dirty keys, one chained upload at a time ------

    private void pump() {
        if (uploadInFlight || dirty.isEmpty() || JsBridge.getUserId() == null) return;
        uploadNext();
    }

    private void uploadNext() {
        if (dirty.isEmpty() || JsBridge.getUserId() == null) {
            uploadInFlight = false;
            return;
        }
        uploadInFlight = true;
        final String key = dirty.iterator().next();
        String value = local.load(key);
        long ts = lastWriteTs.containsKey(key) ? lastWriteTs.get(key) : System.currentTimeMillis();
        JsBridge.kvUpsert(key, value == null ? "" : value, ts, ok -> {
            if (ok) {
                dirty.remove(key);
                saveBookkeeping();
                uploadNext();          // chain until drained
            } else {
                uploadInFlight = false; // offline / error: retry next tick
            }
        });
    }

    // ---- downward reconcile on sign-in / auth change -----------------------
    // Per-key LWW: newer timestamp wins in each direction. Local keys the
    // cloud has never seen (or has older) get marked dirty and upload - that
    // is the anonymous-play -> signed-in migration.

    private void reconcile() {
        if (reconcileInFlight || JsBridge.getUserId() == null) return;
        reconcileInFlight = true;
        JsBridge.kvList(json -> {
            try {
                if (json == null) return;
                Map<String, Long> remoteTs = parseRemoteList(json);
                List<String> toDownload = new ArrayList<>();
                for (Map.Entry<String, Long> e : remoteTs.entrySet()) {
                    String key = e.getKey();
                    if (!SYNC_KEYS.contains(key)) continue;
                    long localTs = lastWriteTs.containsKey(key) ? lastWriteTs.get(key) : 0L;
                    if (e.getValue() > localTs) toDownload.add(key);
                }
                for (String key : SYNC_KEYS) {
                    if (!local.exists(key)) continue;
                    long localTs = lastWriteTs.containsKey(key) ? lastWriteTs.get(key) : 0L;
                    long cloudTs = remoteTs.containsKey(key) ? remoteTs.get(key) : -1L;
                    if (localTs > cloudTs || cloudTs < 0) dirty.add(key);
                }
                saveBookkeeping();
                downloadAll(toDownload, remoteTs);
            } finally {
                reconcileInFlight = false;
            }
        });
    }

    private void downloadAll(List<String> keys, Map<String, Long> remoteTs) {
        if (keys.isEmpty()) return;
        final String key = keys.remove(0);
        JsBridge.kvGet(key, value -> {
            if (value != null) {
                if (value.isEmpty()) local.delete(key);   // tombstone
                else                 local.save(key, value);
                Long ts = remoteTs.get(key);
                lastWriteTs.put(key, ts != null ? ts : System.currentTimeMillis());
                dirty.remove(key);
                saveBookkeeping();
            }
            downloadAll(keys, remoteTs);
        });
    }

    private static Map<String, Long> parseRemoteList(String json) {
        Map<String, Long> out = new LinkedHashMap<>();
        try {
            JsonValue root = new JsonReader().parse(json);
            for (JsonValue row = root.child; row != null; row = row.next) {
                String key = row.getString("key", null);
                if (key != null) out.put(key, row.getLong("client_ts", 0L));
            }
        } catch (Exception ignored) {
            // Malformed list: treat as empty; next reconcile retries.
        }
        return out;
    }

    // ---- bookkeeping persistence (survives tab close mid-sync) ------------
    // Flat string formats (comma list / key=ts;...) - no JSON dependency.

    private void loadBookkeeping() {
        String d = local.load(DIRTY_KEY);
        if (d != null && !d.isEmpty()) {
            for (String k : d.split(",")) {
                if (!k.isEmpty()) dirty.add(k);
            }
        }
        String t = local.load(TS_KEY);
        if (t != null && !t.isEmpty()) {
            for (String pair : t.split(";")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                try {
                    lastWriteTs.put(pair.substring(0, eq), Long.parseLong(pair.substring(eq + 1)));
                } catch (NumberFormatException ignored) {}
            }
        }
    }

    private void saveBookkeeping() {
        local.save(DIRTY_KEY, String.join(",", dirty));
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> e : lastWriteTs.entrySet()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        local.save(TS_KEY, sb.toString());
    }
}
