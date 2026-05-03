package com.bjsp123.rl2.ui.skin;
/**
 * Size/position helpers for UI surfaces (dialogs, panels, menu content blocks). Enforces the
 * "maximum size but fills most of the screen when small" rule: a surface's preferred size is
 * clamped to the smaller of {@code maxPreferred} and a configurable fraction (default 90%) of
 * the available viewport dimension.
 */
public final class UiLayout {

    /** Fraction of the viewport a panel is allowed to occupy when its preferred size exceeds it. */
    public static final float FILL_FRACTION = 0.9f;

    private UiLayout() {}

    /** Clamp one axis to min(preferred, viewport * {@link #FILL_FRACTION}). */
    public static float clamp(float preferred, float viewport) {
        return Math.min(preferred, viewport * FILL_FRACTION);
    }

    /** Centred offset for a surface of size {@code size} inside {@code viewport}. */
    public static float centered(float size, float viewport) {
        return (viewport - size) / 2f;
    }
}
