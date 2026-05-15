package com.bjsp123.rl2.screen;

import com.badlogic.gdx.Gdx;
import com.bjsp123.rl2.logic.GameBalance;
import com.bjsp123.rl2.model.Level;

/** Lightweight spike logger for the libGDX render frame. */
final class FrameProfiler {
    private static final String TAG = "RL2Profile";
    private static final int MAX_SPANS = 64;

    private final String[] names = new String[MAX_SPANS];
    private final String[] values = new String[MAX_SPANS];

    private long frameStart;
    private long lastLogNs;
    private int count;
    private float deltaSec;
    private float freezeFrames;
    private int depth;
    private boolean active;

    void begin(float delta, Level level, float freezeFrames) {
        active = GameBalance.PROFILING_ENABLED;
        if (!active) return;
        frameStart = System.nanoTime();
        count = 0;
        deltaSec = delta;
        this.freezeFrames = freezeFrames;
        depth = level == null ? -1 : level.depth;
    }

    long start() {
        return active ? System.nanoTime() : 0L;
    }

    void add(String name, long startNs) {
        if (!active || startNs == 0L || count >= MAX_SPANS) return;
        names[count] = name;
        values[count] = fmt((System.nanoTime() - startNs) / 1_000_000.0) + "ms";
        count++;
    }

    void addMetric(String name, String value) {
        if (!active || count >= MAX_SPANS) return;
        names[count] = name;
        values[count] = value;
        count++;
    }

    void finish(boolean ticked, boolean overlayOpen) {
        if (!active || frameStart == 0L) return;
        long total = System.nanoTime() - frameStart;
        double totalMs = total / 1_000_000.0;
        if (totalMs < GameBalance.PROFILING_SLOW_FRAME_MS) return;

        long now = System.nanoTime();
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

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
