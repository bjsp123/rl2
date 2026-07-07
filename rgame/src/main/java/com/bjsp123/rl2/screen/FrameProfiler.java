package com.bjsp123.rl2.screen;

import com.badlogic.gdx.Gdx;
import com.bjsp123.rl2.logic.GameBalance;
import com.bjsp123.rl2.model.Level;

/** Lightweight spike logger for the libGDX render frame. */
final class FrameProfiler {
    private static final String TAG = "RL2Profile";
    private static final int MAX_SPANS = 64;

    private final String[] names  = new String[MAX_SPANS];
    private final String[] values = new String[MAX_SPANS];

    private long  frameStart;
    private long  lastLogNs;
    private int   count;
    private float deltaSec;
    private float freezeFrames;
    private int   depth;
    private boolean active;

    // Per-frame captures for the perf overlay (reset each begin())
    private long frameClearNs;
    private long frameLogicNs;
    private long frameRenderNs;

    // Rolling 1-second accumulators
    private long  accumClearNs, accumLogicNs, accumRenderNs;
    private float accumDeltaSec;
    private int   accumFrames;

    // Last published 1-second averages (read by the overlay)
    private float snapFps, snapClearMs, snapRenderMs, snapLogicMs;

    void begin(float delta, Level level, float freezeFrames) {
        frameClearNs = frameLogicNs = frameRenderNs = 0;
        deltaSec  = delta;
        frameStart = 0L;
        count      = 0;

        active = GameBalance.PROFILING_ENABLED;
        if (!active) return;
        frameStart        = System.nanoTime();
        this.freezeFrames = freezeFrames;
        depth = level == null ? -1 : level.depth;
    }

    long start() {
        return System.nanoTime();
    }

    void add(String name, long startNs) {
        long durNs = System.nanoTime() - startNs;
        captureForOverlay(name, durNs);
        if (!active || count >= MAX_SPANS) return;
        names[count]  = name;
        values[count] = fmt(durNs / 1_000_000.0) + "ms";
        count++;
    }

    void addMetric(String name, String value) {
        if (!active || count >= MAX_SPANS) return;
        names[count]  = name;
        values[count] = value;
        count++;
    }

    void finish(boolean ticked, boolean overlayOpen) {
        // Always update rolling stats regardless of profiling flag
        accumClearNs  += frameClearNs;
        accumLogicNs  += frameLogicNs;
        accumRenderNs += frameRenderNs;
        accumDeltaSec += deltaSec;
        accumFrames++;
        if (accumDeltaSec >= 1f) {
            if (accumFrames > 0) {
                snapFps      = accumFrames / accumDeltaSec;
                snapClearMs  = (accumClearNs  / 1_000_000f) / accumFrames;
                snapRenderMs = (accumRenderNs / 1_000_000f) / accumFrames;
                snapLogicMs  = (accumLogicNs  / 1_000_000f) / accumFrames;
            }
            accumClearNs = accumLogicNs = accumRenderNs = 0;
            accumDeltaSec = 0f;
            accumFrames   = 0;
        }

        if (!active || frameStart == 0L) return;
        long total    = System.nanoTime() - frameStart;
        double totalMs = total / 1_000_000.0;
        if (totalMs < GameBalance.PROFILING_SLOW_FRAME_MS) return;

        long now      = System.nanoTime();
        long minGapNs = Math.max(0L, GameBalance.PROFILING_MIN_LOG_GAP_MS) * 1_000_000L;
        if (minGapNs > 0L && now - lastLogNs < minGapNs) return;
        lastLogNs = now;

        StringBuilder sb = new StringBuilder(320);
        sb.append("slow frame ")
          .append(fmt(totalMs)).append("ms")
          .append(" delta=").append(fmt(deltaSec * 1000.0)).append("ms")
          .append(" depth=").append(depth)
          .append(" ticked=").append(ticked)
          .append(" overlay=").append(overlayOpen)
          .append(" freeze=").append(freezeFrames)
          .append(" :: ");
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(' ');
            sb.append(names[i]).append('=').append(values[i]);
        }
        if (Gdx.app != null) Gdx.app.log(TAG, sb.toString());
        else System.out.println(TAG + ": " + sb);
    }

    float snapFps()      { return snapFps; }
    float snapClearMs()  { return snapClearMs; }
    float snapRenderMs() { return snapRenderMs; }
    float snapLogicMs()  { return snapLogicMs; }

    private void captureForOverlay(String name, long durNs) {
        switch (name) {
            case "clear"          -> frameClearNs  = durNs;
            case "controllerTick" -> frameLogicNs  = durNs;
            case "levelRender",
                 "hudRender",
                 "stageDraw"      -> frameRenderNs += durNs;
        }
    }

    private static String fmt(double value) {
        return com.bjsp123.rl2.util.Fmt.of("%.1f", value);
    }
}
