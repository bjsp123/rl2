package com.bjsp123.rl2.ui.skin;

import com.badlogic.gdx.graphics.Color;

/**
 * Single source of truth for the SHATTERED-style UI's design tokens. Colours,
 * border thicknesses, spacing values, popup-specific sizes, and modal dimming
 * alphas all live here so a designer can tweak one file and have every popup
 * pick up the change.
 *
 * <p>Existing skin / size code references these constants:
 * <ul>
 *   <li>{@link ShatteredSkin} — palette + border / chamfer thicknesses</li>
 *   <li>{@link StoneUi#newSkin} — label + button text colours</li>
 *   <li>{@link PanelSize} — preserved as the panel-size policy helper; its
 *       fractions / caps could be moved here if desired but currently stay
 *       in that file for readability</li>
 *   <li>{@link com.bjsp123.rl2.ui.overlay.LookRenderer} — modal popup sizing
 *       and dim-overlay alpha</li>
 * </ul>
 */
public final class UiTheme {

    private UiTheme() {}

    // ── Palette ─────────────────────────────────────────────────────────────
    // Dark text-area backgrounds (slate-on-warm) with cream borders + bright
    // corner accents, bright yellow titles + highlights, white body text. The
    // dark background is the chosen carrier for text — readable against either
    // the dungeon view behind a popup or a screen-filling menu chrome.
    public static final Color BG_DARK     = rgb(0x0c0a08);  // slot recess (deep dark)
    public static final Color BG_PANEL    = rgb(0x1f1d1a);  // panel / dashboard / slot fill (text-area dark)
    public static final Color BG_PANEL_HI = rgb(0x2d2a26);  // pressed / focus highlight (slightly brighter)
    public static final Color BORDER_DIM  = rgb(0x3d3a35);  // slot grid border (visible against panel)
    public static final Color BORDER_MID  = rgb(0xdcd6c4);  // panel border (cream)
    public static final Color BORDER_BRT  = rgb(0xf0eadc);  // dashboard border + corner accents (bright cream)
    public static final Color ACCENT      = rgb(0xf4ee2b);  // bright yellow — focus, level badges, titles
    public static final Color ACCENT_DIM  = rgb(0xb09020);  // dim yellow — pressed border
    public static final Color TEXT_WHITE  = rgb(0xffffff);
    public static final Color TEXT_DIM    = rgb(0xa8a4a0);  // muted gray (legible on dark panel)
    public static final Color TEXT_WARN   = rgb(0xd44848);
    public static final Color TEXT_TITLE  = rgb(0xf4ee2b);  // bright yellow (matches ACCENT)

    // ── Modal dim overlay ───────────────────────────────────────────────────
    /** Alpha applied to the full-stage black overlay drawn behind every modal popup. */
    public static final float DIM_ALPHA = 0.55f;

    // ── Border + chamfer thicknesses (in source-px) ─────────────────────────
    public static final int BORDER_THICK_PANEL = 2;
    public static final int BORDER_THICK_SLOT  = 2;
    public static final int CHAMFER_PANEL = 2;
    public static final int CHAMFER_SLOT  = 1;

    // ── Common spacing values ───────────────────────────────────────────────
    /** Tab strip height — same value used by every tabbed panel. */
    public static final int TAB_HEIGHT       = 26;
    /** Outer pad inside any modal frame's panel chrome. */
    public static final int OUTER_PAD        = 10;
    /** Vertical gap between sub-panels stacked inside a modal. */
    public static final int SECTION_GAP      = 8;
    /** Gap between a group-box label and the panel beneath it. */
    public static final int LABEL_GAP        = 4;
    /** Inner pad of a sub-panel containing slot rows / content. */
    public static final int SUB_PANEL_PAD    = 6;

    // ── Look popup specific ─────────────────────────────────────────────────
    /** Width of the centred look popup. Height grows with content (capped). */
    public static final float LOOK_PANEL_W = 420f;
    /** Maximum height the look popup will grow to before clipping. */
    public static final float LOOK_PANEL_MAX_H = 540f;
    /** Width of the centred mob-info popup (history + inventory). */
    public static final float LOOK_MOB_INFO_W = 380f;
    /** Maximum height of the mob-info popup. */
    public static final float LOOK_MOB_INFO_MAX_H = 460f;
    /** Width of the centred buff-info popup. */
    public static final float LOOK_BUFF_INFO_W = 260f;
    /** Height of the centred buff-info popup. */
    public static final float LOOK_BUFF_INFO_H = 180f;

    // ── Inventory item-detail popup ─────────────────────────────────────────
    /** Width of the centred item-detail popup. */
    public static final float INV_ITEM_POPUP_W = 322f;
    /** Maximum height the item-detail popup will grow to before clipping. */
    public static final float INV_ITEM_POPUP_MAX_H = 260f;
    /** Icon size in the item-detail popup's top-right header. */
    public static final float INV_ITEM_ICON_SIZE = 48f;

    private static Color rgb(int hex) {
        float r = ((hex >> 16) & 0xFF) / 255f;
        float g = ((hex >>  8) & 0xFF) / 255f;
        float b = ( hex        & 0xFF) / 255f;
        return new Color(r, g, b, 1f);
    }
}
