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
    public static final Color BG_DARK     = rgb(0x0c0e12);  // slot fill (very dark)
    public static final Color BG_PANEL    = rgb(0x1a1c20);  // panel / dashboard fill
    public static final Color BG_PANEL_HI = rgb(0x22252a);  // pressed / focus highlight
    public static final Color BORDER_DIM  = rgb(0x2a2f37);  // slot border, calm
    public static final Color BORDER_MID  = rgb(0x3d434c);  // panel border
    public static final Color BORDER_BRT  = rgb(0x5a606a);  // dashboard border
    public static final Color ACCENT      = rgb(0xd8a040);  // gold — focus, level badges
    public static final Color ACCENT_DIM  = rgb(0x8a6020);  // dim gold — pressed border
    public static final Color TEXT_WHITE  = rgb(0xe8e8e8);
    public static final Color TEXT_DIM    = rgb(0x989ca4);
    public static final Color TEXT_WARN   = rgb(0xd44848);
    public static final Color TEXT_TITLE  = rgb(0xd8b070);

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
