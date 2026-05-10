package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.graphics.Color;

/**
 * Single source of truth for every colour the V2 UI paints. Held separately
 * from {@link Pal} (which keeps the sizing constants) so a future palette
 * swap doesn't drag all callers' layout maths along with it.
 *
 * <p>Three colour families:
 * <ul>
 *   <li><b>Borders</b> — slightly blue-green-tinted greys forming a 3-line
 *       (outer / mid / inner) frame on every chromed element. Outer is the
 *       lightest, inner is the darkest, mid is between.</li>
 *   <li><b>Backgrounds</b> — warm dark greys. Windows are darkest, button
 *       fills are a shade lighter, HUD chrome is mid warm grey, and image-
 *       bearing slots (inventory cells, action quickslots) use the palest
 *       warm grey so item sprites pop against them.</li>
 *   <li><b>Text + accents</b> — bright yellow for titles / highlights, white
 *       for body, muted grey for dim, red for warnings / back affordance.</li>
 * </ul>
 */
public final class UiColors {

    private UiColors() {}

    // ── Border lines (blue-green-tinted greys) ─────────────────────────────
    /** Outermost line — lightest. */
    public static final Color BORDER_OUTER  = rgb(0xb0bab4);
    /** Middle line — mid value. */
    public static final Color BORDER_MID    = rgb(0x6c7672);
    /** Innermost line — darkest. */
    public static final Color BORDER_INNER  = rgb(0x2c3632);

    // ── Background tiers (warm greys) ──────────────────────────────────────
    /** Window interior fill. */
    public static final Color WIN_BG          = rgb(0x443333);
    /** Slightly brighter warm grey — used for button fills inside windows. */
    public static final Color BTN_BG          = rgb(0x5a4040);
    /** Pressed-button fill — brighter still so the press registers visually. */
    public static final Color BTN_PRESSED_BG  = rgb(0x6e5050);
    /** Image-bearing slot fill (inventory cells, action quickslots,
     *  encyclopaedia detail icon backdrop) — paler warm grey so the
     *  item / mob / glyph sprite stays legible without an outline. */
    public static final Color SLOT_BG         = rgb(0x806060);
    /** Light warm grey used as the backdrop of encyclopaedia row icons
     *  and the detail-page sprite frame — brighter than {@link #SLOT_BG}
     *  so dark-on-warm sprites read at a glance against it. */
    public static final Color ICON_FRAME_BG   = rgb(0xa48080);
    /** HUD chrome fill (action bar, status bars' backdrop) — mid warm grey,
     *  one tier lighter than {@link #WIN_BG}. */
    public static final Color HUD_BG          = rgb(0x665544);
    /** Deepest recess — used as the empty-slot interior so an empty cell
     *  reads inset against {@link #SLOT_BG}. */
    public static final Color SLOT_RECESS     = rgb(0x2a1f1f);

    // ── Drop shadow ────────────────────────────────────────────────────────
    /** Faint black drop shadow drawn behind every window. */
    public static final Color SHADOW          = new Color(0f, 0f, 0f, 0.45f);

    // ── Text + accent ──────────────────────────────────────────────────────
    public static final Color TEXT_BODY       = rgb(0xffffff);
    public static final Color TEXT_DIM        = rgb(0xc8c4b8);
    public static final Color TEXT_WARN       = rgb(0xe05050);
    public static final Color ACCENT          = rgb(0xffe848);  // bright yellow — titles, focus
    public static final Color ACCENT_DIM      = rgb(0xb09020);  // muted yellow — pressed accent
    public static final Color WARN_HL         = rgb(0xff7878);  // bright red — back-button highlight
    public static final Color WARN_SHADE      = rgb(0x802020);  // dark red — back-button shadow

    // ── HP / XP / Satiety bar fill colours ─────────────────────────────────
    public static final Color BAR_HP          = rgb(0xc04040);
    public static final Color BAR_XP          = rgb(0xffe848);
    public static final Color BAR_SATIETY     = rgb(0xa08858);

    private static Color rgb(int hex) {
        float r = ((hex >> 16) & 0xFF) / 255f;
        float g = ((hex >>  8) & 0xFF) / 255f;
        float b = ( hex        & 0xFF) / 255f;
        return new Color(r, g, b, 1f);
    }
}
