package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.graphics.Color;
import com.bjsp123.rl2.util.CsvTable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Single source of truth for every constant the V2 UI and HUD use.
 * Replaces {@code Pal} and {@code UiColors}.
 *
 * <p>All fields are {@code public static} (non-final) so {@link #load}
 * can override them from {@code assets/data/config.csv} at startup
 * without recompilation. Java-side defaults reproduce the original palette
 * and layout exactly when the properties file is absent.
 *
 * <p>Colors are stored as live {@link Color} instances - the loader mutates
 * them in-place so any code that cached a field reference before load() still
 * sees the updated value. Call {@link #load} once, before any rendering.
 */
public final class UIVars {

    private UIVars() {}

    // -- Border lines (blue-green-tinted greys, 3-line chrome) ----------------
    public static Color BORDER_OUTER    = hex(0xb0bab4);
    public static Color BORDER_MID      = hex(0x6c7672);
    public static Color BORDER_INNER    = hex(0x2c3632);

    // -- Background tiers (warm greys, darkest -> lightest) --------------------
    public static Color WIN_BG          = hex(0x443333);
    public static Color BTN_BG          = hex(0x5a4040);
    public static Color BTN_PRESSED_BG  = hex(0x6e5050);
    public static Color SLOT_BG         = hex(0x806060);
    public static Color ICON_FRAME_BG   = hex(0xa48080);
    public static Color HUD_BG          = hex(0x665544);
    public static Color SLOT_RECESS     = hex(0x2a1f1f);

    // -- Drop shadow -----------------------------------------------------------
    public static Color SHADOW          = new Color(0f, 0f, 0f, 0.45f);

    // -- Text + accent ---------------------------------------------------------
    public static Color TEXT_BODY       = hex(0xffffff);
    public static Color TEXT_DIM        = hex(0xc8c4b8);
    public static Color TEXT_WARN       = hex(0xe05050);
    public static Color ACCENT          = hex(0xffe848);
    public static Color ACCENT_DIM      = hex(0xb09020);
    public static Color WARN_HL         = hex(0xff7878);
    public static Color WARN_SHADE      = hex(0x802020);

    // -- Special item-level badge color ----------------------------------------
    /** Bright green - level badge when a perk/buff/equipment boosts effective level. */
    public static Color BOOST           = hex(0x33d94d);

    // -- Bar fills -------------------------------------------------------------
    public static Color BAR_HP          = hex(0xc04040);
    public static Color BAR_XP          = hex(0xffe848);
    public static Color BAR_SATIETY     = hex(0xa08858);

    // -- Damage floater tints (per DamageElement) -----------------------------
    public static Color DAMAGE_PHYSICAL = hex(0xc04040);  // red
    public static Color DAMAGE_SHOCK    = hex(0x66e0ff);  // cyan
    public static Color DAMAGE_POISON   = hex(0xa8e070);  // pale green
    public static Color DAMAGE_FIRE     = hex(0xff9040);  // orange
    public static Color DAMAGE_MAGIC    = hex(0x99c8ff);  // pale blue

    /** Colorblind-aware palette swap for the {@code DAMAGE_*} colors.
     *  Mutates the existing {@link Color} instances in-place so cached
     *  field references stay valid. Wong-style palettes for the red-green
     *  variants; a blue-yellow recolor for tritan. */
    public static void applyColorblindPalette(com.bjsp123.rl2.ui.skin.Settings.ColorblindPreset p) {
        switch (p) {
            case DEUTER, PROTAN -> {
                setRgb(DAMAGE_PHYSICAL, 0xe69f00); // orange (red is hard to see)
                setRgb(DAMAGE_SHOCK,    0x56b4e9); // sky blue
                setRgb(DAMAGE_POISON,   0xf0e442); // yellow (green is hard to see)
                setRgb(DAMAGE_FIRE,     0xd55e00); // vermillion
                setRgb(DAMAGE_MAGIC,    0xcc79a7); // reddish purple
            }
            case TRITAN -> {
                setRgb(DAMAGE_PHYSICAL, 0xd62728); // red stays
                setRgb(DAMAGE_SHOCK,    0xff7b00); // orange (was cyan - blue is hard)
                setRgb(DAMAGE_POISON,   0x2ca02c); // deeper green
                setRgb(DAMAGE_FIRE,     0xe377c2); // pink (avoid confusion w/ SHOCK orange)
                setRgb(DAMAGE_MAGIC,    0x9467bd); // purple
            }
            case NONE -> {
                setRgb(DAMAGE_PHYSICAL, 0xc04040);
                setRgb(DAMAGE_SHOCK,    0x66e0ff);
                setRgb(DAMAGE_POISON,   0xa8e070);
                setRgb(DAMAGE_FIRE,     0xff9040);
                setRgb(DAMAGE_MAGIC,    0x99c8ff);
            }
        }
    }

    private static void setRgb(Color c, int rgb) {
        c.r = ((rgb >> 16) & 0xFF) / 255f;
        c.g = ((rgb >>  8) & 0xFF) / 255f;
        c.b = ( rgb        & 0xFF) / 255f;
        c.a = 1f;
    }

    // -- Charge bar (item cells) -----------------------------------------------
    /** Semi-transparent black backdrop behind the charge bar. */
    public static Color BAR_CHARGE_BACKDROP = new Color(0f, 0f, 0f, 0.85f);
    /** Empty slot background. */
    public static Color BAR_CHARGE_EMPTY    = hex(0x404040);
    /** Fully-charged slot fill. */
    public static Color BAR_CHARGE_FULL     = hex(0x33d94d);
    /** Partially-charged slot fill. */
    public static Color BAR_CHARGE_PARTIAL  = hex(0x1a8026);

    // -- Alpha scalars ---------------------------------------------------------
    /** Alpha of the dim overlay drawn behind modal popups (0..1). */
    public static float DIM_ALPHA       = 0.55f;
    /** Alpha of a window's interior fill rectangle (0..1). */
    public static float PANEL_FILL_ALPHA = 0.85f;

    // -- Chrome geometry (virtual pixels) -------------------------------------
    public static float SHADOW_OFFSET   = 5f;
    /** Per-line thickness of a window's 3-line border. */
    public static float WIN_LINE_W      = 3f;
    /** Per-line thickness of HUD elements and button borders. */
    public static float HUD_LINE_W      = 1f;
    /** Standard large navigation button width. */
    public static float BTN_W           = 320f;
    /** Standard large navigation button height. */
    public static float BTN_H           = 64f;
    /** Square size of the back-button affordance. */
    public static float BACK_SIZE       = 40f;
    /** Square size of the burger-menu button. */
    public static float BURGER_SIZE     = 48f;
    /** Virtual canvas width - all layout calcs use this. */
    public static float VIRTUAL_W       = 400f;
    /** Virtual canvas height - all layout calcs use this. */
    public static float VIRTUAL_H       = 720f;
    /** Minimum horizontal/vertical margin when sizing a modal window. */
    public static float PAD_MODAL       = 24f;
    /** Standard inset from a window edge to its body content. */
    public static float PAD_CONTENT     = 18f;

    // -- Typography ------------------------------------------------------------
    public static int FONT_REGULAR_PX   = 16;
    public static int FONT_HEADER_PX    = 32;

    // -- Derived (recomputed by applyDerived after every load) ----------------
    /** Total border width of a window's 3-line chrome: 3 x WIN_LINE_W. */
    public static float WIN_BORDER;
    /** Total border width of a HUD / button 3-line chrome: 3 x HUD_LINE_W. */
    public static float HUD_BORDER;
    /** Synonym for HUD_BORDER - kept for button-sizing call sites. */
    public static float BTN_BORDER;

    static { applyDerived(); }

    // -- Loading ---------------------------------------------------------------

    /**
     * Parse {@code text} as {@code assets/data/config.csv} and override every
     * matching {@code public static} field on this class. Unknown keys and
     * malformed values are silently ignored. Call once at startup before any
     * rendering code runs.
     *
     * <p>Color values: 6-digit hex {@code RRGGBB} (alpha=1) or 8-digit
     * {@code RRGGBBAA}, with or without a leading {@code #} or {@code 0x}.
     * Float/int fields accept standard decimal notation.
     */
    public static void load(String text) {
        if (text == null || text.isEmpty()) return;
        CsvTable table = CsvTable.parse(text);
        for (java.util.Map<String, String> row : table.rows) {
            if (!"ui".equals(CsvTable.str(row, "kind", ""))) continue;
            String key = CsvTable.str(row, "key", "");
            String value = CsvTable.str(row, "value", "").trim();
            if (key.isEmpty() || value.isEmpty()) continue;
            try {
                Field f = UIVars.class.getDeclaredField(key);
                int mods = f.getModifiers();
                if (!Modifier.isStatic(mods) || Modifier.isFinal(mods)) continue;
                Class<?> t = f.getType();
                if      (t == int.class)   f.setInt(null,   Integer.parseInt(value));
                else if (t == float.class) f.setFloat(null, Float.parseFloat(value));
                else if (t == double.class) f.setDouble(null, Double.parseDouble(value));
                else if (t == Color.class) applyHex((Color) f.get(null), value);
            } catch (NoSuchFieldException | IllegalAccessException | NumberFormatException ignored) {}
        }
        applyDerived();
    }

    private static void applyDerived() {
        WIN_BORDER = 3f * WIN_LINE_W;
        HUD_BORDER = 3f * HUD_LINE_W;
        BTN_BORDER = HUD_BORDER;
    }

    /** Mutate {@code c} in-place from a hex string (RRGGBB or RRGGBBAA). */
    private static void applyHex(Color c, String hex) {
        String h = hex.startsWith("0x") ? hex.substring(2)
                 : hex.startsWith("#")  ? hex.substring(1)
                 : hex;
        long val = Long.parseLong(h.length() <= 6 ? h + "ff" : h, 16);
        c.r = ((val >> 24) & 0xFF) / 255f;
        c.g = ((val >> 16) & 0xFF) / 255f;
        c.b = ((val >>  8) & 0xFF) / 255f;
        c.a = ( val        & 0xFF) / 255f;
    }

    private static Color hex(int rgb) {
        return new Color(
            ((rgb >> 16) & 0xFF) / 255f,
            ((rgb >>  8) & 0xFF) / 255f,
            ( rgb        & 0xFF) / 255f,
            1f);
    }
}
