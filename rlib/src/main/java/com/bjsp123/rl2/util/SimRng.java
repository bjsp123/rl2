package com.bjsp123.rl2.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * Central registry of the process-wide gameplay RNGs, so an entire run can be
 * made deterministic / reproducible.
 *
 * <p>Each logic system that owns a static {@link Random} registers it here at
 * class-load time under a stable name. {@link #reseedAll(long)} then reseeds
 * every registered RNG from a single master seed - each RNG getting its own
 * distinct, stable stream (keyed by name, so the result is independent of
 * class-load order and survives lazy loading mid-run).
 *
 * <p><b>Opt-in.</b> Until a master seed is set, registered RNGs keep their
 * default {@code new Random()} seeding - so normal play is wall-clock random as
 * before. Only headless replays (autoplay / the regression harness) call
 * {@link #reseedAll} to pin the whole sim to a seed; the same seed then
 * reproduces the same run.
 */
public final class SimRng {

    private static final Map<String, Random> REGISTERED = new LinkedHashMap<>();
    private static long master;
    private static boolean seeded;

    private SimRng() {}

    /**
     * Register a process-wide gameplay RNG under a stable {@code name}. Returns
     * the same instance so it can be used directly in a field initializer:
     * <pre>{@code private static final Random RANDOM = SimRng.register("MobSystem", new Random());}</pre>
     * If a master seed has already been set (a run is in progress), the RNG is
     * reseeded immediately so a lazily-loaded system joins the deterministic
     * stream rather than starting wall-clock random.
     */
    public static synchronized Random register(String name, Random r) {
        REGISTERED.put(name, r);
        if (seeded) r.setSeed(streamSeed(name));
        return r;
    }

    /**
     * Reseed every registered RNG deterministically from {@code masterSeed}.
     * Call once at the start of a run to make the whole sim reproducible: the
     * same {@code masterSeed} yields the same combat / AI / environment rolls.
     */
    public static synchronized void reseedAll(long masterSeed) {
        master = masterSeed;
        seeded = true;
        for (Map.Entry<String, Random> e : REGISTERED.entrySet()) {
            e.getValue().setSeed(streamSeed(e.getKey()));
        }
    }

    /** Distinct, stable per-RNG seed from the master + the RNG's name.
     *  {@code String.hashCode} is JDK-specified, so this is reproducible across
     *  JVMs and independent of registration order. */
    private static long streamSeed(String name) {
        return master * 0x9E3779B97F4A7C15L + name.hashCode();
    }
}
