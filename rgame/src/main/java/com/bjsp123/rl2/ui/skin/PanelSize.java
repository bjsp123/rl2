package com.bjsp123.rl2.ui.skin;

import com.badlogic.gdx.scenes.scene2d.Stage;

/**
 * Single source of truth for modal-panel preferred sizes. Each {@link Kind} maps a
 * panel category to a (percentage of viewport, absolute max in stage units) pair.
 * The actual size is the smallest of: {@code viewportFraction × stageDim}, the cap,
 * and {@code stageDim − 16} (a small margin so panels never bleed off the viewport
 * edge on small screens).
 *
 * <p>Panels query this on every {@code layoutForStage} call to size themselves; the
 * result is the panel's preferred size and is independent of which tab is active or
 * what the current content prefers. Tab-switching never resizes the frame.
 *
 * <h3>Categories</h3>
 * <ul>
 *   <li>{@link Kind#LARGE} — inventory grid, encyclopaedia. ~85 % × 90 % of viewport,
 *       capped at 720 × 600 stage units.</li>
 *   <li>{@link Kind#MEDIUM} — character-stats, inventory item-detail popup. ~65 % ×
 *       75 %, capped at 540 × 460.</li>
 *   <li>{@link Kind#SMALL} — crafting, gem picker, look. ~50 % × 60 %, capped at
 *       400 × 340.</li>
 * </ul>
 */
public final class PanelSize {

    public enum Kind { LARGE, MEDIUM, SMALL }

    /** Margin from the viewport edge — a panel is never wider than {@code stageW − EDGE_MARGIN_PX}
     *  or taller than {@code stageH − EDGE_MARGIN_PX}, so panels never bleed off-screen. */
    public static final float EDGE_MARGIN_PX = 16f;

    private PanelSize() {}

    public static float widthFor(Kind k, float stageW) {
        return switch (k) {
            case LARGE  -> minOf(stageW * 0.75f, 640f, stageW - EDGE_MARGIN_PX);
            case MEDIUM -> minOf(stageW * 0.65f, 560f, stageW - EDGE_MARGIN_PX);
            case SMALL  -> minOf(stageW * 0.50f, 400f, stageW - EDGE_MARGIN_PX);
        };
    }

    public static float heightFor(Kind k, float stageH) {
        return switch (k) {
            case LARGE  -> minOf(stageH * 0.75f, 540f, stageH - EDGE_MARGIN_PX);
            case MEDIUM -> minOf(stageH * 0.65f, 420f, stageH - EDGE_MARGIN_PX);
            case SMALL  -> minOf(stageH * 0.55f, 320f, stageH - EDGE_MARGIN_PX);
        };
    }

    public static float widthFor(Kind k, Stage stage) {
        return widthFor(k, stage.getViewport().getWorldWidth());
    }

    public static float heightFor(Kind k, Stage stage) {
        return heightFor(k, stage.getViewport().getWorldHeight());
    }

    private static float minOf(float a, float b, float c) {
        return Math.min(Math.min(a, b), c);
    }
}
