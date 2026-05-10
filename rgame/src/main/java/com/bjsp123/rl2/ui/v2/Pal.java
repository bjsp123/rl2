package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.graphics.Color;

/**
 * V2 UI sizing constants + thin colour aliases. Colours have been migrated
 * to {@link UiColors} so a future palette swap doesn't reach into the
 * sizing maths; the aliases here keep older call sites compiling without
 * a full sweep through the codebase.
 */
public final class Pal {
    private Pal() {}

    // ── Colour aliases (delegate to UiColors) ───────────────────────────────
    public static final Color PANEL         = UiColors.WIN_BG;
    public static final Color PANEL_HI      = UiColors.BTN_PRESSED_BG;
    public static final Color DARK          = UiColors.SLOT_RECESS;
    public static final Color BORDER        = UiColors.BORDER_MID;
    public static final Color BORDER_BRIGHT = UiColors.BORDER_OUTER;
    public static final Color BORDER_HL     = UiColors.BORDER_OUTER;
    public static final Color BORDER_SHADE  = UiColors.BORDER_INNER;
    public static final Color WARN_HL       = UiColors.WARN_HL;
    public static final Color WARN_SHADE    = UiColors.WARN_SHADE;
    public static final Color SHADOW        = UiColors.SHADOW;
    public static final Color ACCENT        = UiColors.ACCENT;
    public static final Color ACCENT_DIM    = UiColors.ACCENT_DIM;
    public static final Color WHITE         = UiColors.TEXT_BODY;
    public static final Color DIM           = UiColors.TEXT_DIM;
    public static final Color WARN          = UiColors.TEXT_WARN;

    /** Modal dim overlay alpha (0..1). Drawn full-stage behind every popup. */
    public static final float DIM_ALPHA     = 0.55f;
    /** Alpha applied to a window's interior fill. */
    public static final float PANEL_FILL_ALPHA = 0.85f;
    /** Drop-shadow offset (px) applied to every window. */
    public static final float SHADOW_OFFSET    = 5f;

    // ── Sizing (virtual pixels) ─────────────────────────────────────────────
    /** Per-line thickness for a window's tri-line border (3 lines × this).
     *  Total window border width = {@code 3 × WIN_LINE_W}. */
    public static final float WIN_LINE_W    = 3f;
    /** Total tri-line border thickness for a window. */
    public static final float WIN_BORDER    = 3f * WIN_LINE_W;     // 9
    /** Per-line thickness for HUD elements + buttons — thinner than the
     *  window's border so HUD chrome reads as a lighter visual tier. */
    public static final float HUD_LINE_W    = 1f;
    /** Total tri-line border thickness for HUD elements + buttons. */
    public static final float HUD_BORDER    = 3f * HUD_LINE_W;     // 3
    /** Alias for callers that historically asked for "button border" — same
     *  as {@link #HUD_BORDER}. */
    public static final float BTN_BORDER    = HUD_BORDER;

    public static final float BTN_W         = 320f;
    public static final float BTN_H         = 64f;
    public static final float BACK_SIZE     = 40f;
    public static final float BURGER_SIZE   = 48f;

    public static final float VIRTUAL_W     = 400f;
    public static final float VIRTUAL_H     = 720f;

    public static final int   FONT_REGULAR_PX = 16;
    public static final int   FONT_HEADER_PX  = 32;
}
