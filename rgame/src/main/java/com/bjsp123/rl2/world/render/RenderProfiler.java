package com.bjsp123.rl2.world.render;

/**
 * Opt-in aggregate render profiler. Where {@code FrameProfiler} logs individual
 * slow frames, this accumulates the renderer's per-pass timings
 * ({@link DefaultLevelRenderer.RenderStats}) across ALL frames and prints a
 * sorted per-frame-average summary every 5 seconds - the number you want when
 * ranking passes by cost rather than chasing spikes.
 *
 * <p>Enabled by the {@code RL2_PROFILE=1} environment variable (desktop runs:
 * {@code $env:RL2_PROFILE='1'; ./gradlew :desktop:run}) or the
 * {@code rl2.profile} system property. Both reads are wrapped for TeaVM, where
 * env access may be unsupported - the web build ships with this permanently
 * off and the whole path costs one static boolean check per frame.
 *
 * <p>Output goes to {@code System.out} (terminal on desktop, console on web):
 * <pre>[rl2-profile] 300f pass3=1.42ms surface=0.31ms ... flushes=12.3/f</pre>
 */
public final class RenderProfiler {

    public static final boolean ENABLED = detect();

    private static boolean detect() {
        try {
            if ("1".equals(System.getenv("RL2_PROFILE"))) return true;
        } catch (Throwable ignored) { /* TeaVM may not support getenv */ }
        try {
            return Boolean.getBoolean("rl2.profile");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static long nsPass1, nsSurface, nsPass3, nsPass4, nsFog;
    private static long flushes;
    private static int frames;
    private static long lastPrintNs;

    private RenderProfiler() {}

    /** Fold one frame's render stats into the running aggregate; prints and
     *  resets every 5 seconds. Call once per {@code render()} when enabled. */
    public static void frame(DefaultLevelRenderer.RenderStats s) {
        if (!ENABLED || s == null) return;
        nsPass1   += s.nsPass1;
        nsSurface += s.nsSurface;
        nsPass3   += s.nsPass3;
        nsPass4   += s.nsPass4;
        nsFog     += s.nsFog;
        flushes   += s.totalFlushes;
        frames++;

        long now = System.nanoTime();
        if (lastPrintNs == 0L) { lastPrintNs = now; return; }
        if (now - lastPrintNs < 5_000_000_000L) return;
        lastPrintNs = now;

        StringBuilder sb = new StringBuilder(160);
        sb.append("[rl2-profile] ").append(frames).append("f avg/frame:");
        appendMs(sb, " pass3-content=", nsPass3);
        appendMs(sb, " pass1-floors=", nsPass1);
        appendMs(sb, " surfaces=", nsSurface);
        appendMs(sb, " pass4-tops=", nsPass4);
        appendMs(sb, " fog+overlays=", nsFog);
        sb.append(" flushes=").append(com.bjsp123.rl2.util.Fmt.of("%.1f",
                flushes / (double) Math.max(1, frames))).append("/f");
        System.out.println(sb);

        nsPass1 = nsSurface = nsPass3 = nsPass4 = nsFog = flushes = 0L;
        frames = 0;
    }

    private static void appendMs(StringBuilder sb, String label, long totalNs) {
        sb.append(label)
          .append(com.bjsp123.rl2.util.Fmt.of("%.2f",
                  totalNs / 1_000_000.0 / Math.max(1, frames)))
          .append("ms");
    }
}
