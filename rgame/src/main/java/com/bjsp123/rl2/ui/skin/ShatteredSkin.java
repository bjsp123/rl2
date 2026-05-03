package com.bjsp123.rl2.ui.skin;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.NinePatch;
import com.badlogic.gdx.utils.Disposable;

import java.util.ArrayList;
import java.util.List;

/**
 * Programmatic theme generator for the SHATTERED-style UI. No PNG assets — every
 * drawable is built from a {@link Pixmap} so the palette can be tuned in one place.
 * Matches the visual vocabulary of Shattered Pixel Dungeon: flat dark slate panels,
 * thin lighter borders, gold accents on focus.
 *
 * <p>Five conceptual primitives the renderer code uses, each backed by a single
 * 9-patch:
 * <ul>
 *   <li><b>slot</b> — square cell where an item is stored (inventory, gem holder).
 *       Focused / pressed variants share the same shape with different palette.</li>
 *   <li><b>action</b> — square cell where a tap fires an action (HUD quickslot,
 *       Wait / Look / Info / map). Same drawable as {@link #slot} for visual
 *       consistency; the keyboard accelerator label distinguishes the two at a
 *       glance.</li>
 *   <li><b>combination</b> — slot + slot + button arrangement (crafting). Built
 *       from {@link #slot} cells inside a {@link #panel} backdrop; no dedicated
 *       drawable.</li>
 *   <li><b>panel</b> — bordered region containing other elements (modal dialog
 *       bodies).</li>
 *   <li><b>dashboard</b> — a panel showing facts and instruments (HUD strip,
 *       character stats). Same shape as {@link #panel} with a slightly brighter
 *       border so it reads as authoritative chrome.</li>
 * </ul>
 *
 * <p>All textures are nearest-filter and live for the lifetime of this generator.
 * Held by a {@link StoneUi} and disposed alongside it.
 */
final class ShatteredSkin implements Disposable {

    // ─── Palette ────────────────────────────────────────────────────────────
    static final Color BG_DARK     = rgb(0x0c0e12);  // slot fill (very dark)
    static final Color BG_PANEL    = rgb(0x1a1c20);  // panel / dashboard fill
    static final Color BG_PANEL_HI = rgb(0x22252a);  // pressed / focus highlight
    static final Color BORDER_DIM  = rgb(0x2a2f37);  // slot border, calm
    static final Color BORDER_MID  = rgb(0x3d434c);  // panel border
    static final Color BORDER_BRT  = rgb(0x5a606a);  // dashboard border
    static final Color ACCENT      = rgb(0xd8a040);  // gold — focus, level badges
    static final Color ACCENT_DIM  = rgb(0x8a6020);  // dim gold — pressed border
    static final Color TEXT_WHITE  = rgb(0xe8e8e8);
    static final Color TEXT_DIM    = rgb(0x989ca4);
    static final Color TEXT_WARN   = rgb(0xd44848);
    static final Color TEXT_TITLE  = rgb(0xd8b070);

    // ─── Drawables ──────────────────────────────────────────────────────────
    NinePatch slot;          // dim border, dark fill, 1-px chamfer
    NinePatch slotFocus;     // accent border, panel-hi fill (selected / checked)
    NinePatch slotPressed;   // accent-dim border, panel-hi fill (held)
    NinePatch equipSlot;     // dim border, panel-hi fill — equipment cells (visually
                             //   distinct from bag cells which use plain {@link #slot})
    NinePatch tab;           // dim border, dark fill — inactive tab (recessed look)
    NinePatch tabActive;     // accent border, panel fill — active tab (merges with the
                             //   body panel below, reads as "currently selected")
    NinePatch panel;         // mid border, panel fill, slightly larger corner
    NinePatch panelOpenTop;  // panel without a top border — used when something else
                             //   (e.g. a tab strip) provides the visual top edge so
                             //   the active tab's interior flows seamlessly into the
                             //   panel below it
    NinePatch dashboard;     // bright border, panel fill
    NinePatch hudStrip;      // dashboard but with no left/right caps (stretchy x)
    Texture   whitePixel;    // 1×1 white — for tinted fills (bars, dimming)

    private final List<Texture> textures = new ArrayList<>();

    void create() {
        // Slots: tight 1-px chamfered corner, 2-px border. Reads as a square cell with
        // just-perceptibly rounded corners.
        slot        = roundedRect(BORDER_DIM,  BG_DARK,     3, 1, 2);
        slotFocus   = roundedRect(ACCENT,      BG_PANEL_HI, 3, 1, 2);
        slotPressed = roundedRect(ACCENT_DIM,  BG_PANEL_HI, 3, 1, 2);
        // Equipment slots reuse the slot shape but with the panel-highlight fill, so
        // the "what you're wearing" row reads as elevated relative to the bag grid.
        equipSlot   = roundedRect(BORDER_DIM,  BG_PANEL_HI, 3, 1, 2);
        // Tabs are square-cornered so they read distinctly from the rounded slot /
        // panel / dashboard primitives. The active tab has NO bottom border and uses
        // the body-panel fill colour, so when it sits at the top of a panel its open
        // bottom + matching fill let the panel's interior bleed up into the tab —
        // the manila-folder pattern. Inactive tabs are fully bordered (closed shape)
        // and recessed (dark fill); they sit on the panel's top border like buttons
        // resting on a shelf. Border colour matches the panel's so the tab strip and
        // panel chrome read as one continuous frame.
        tab         = tabRect(BORDER_MID,  BG_DARK,  /*withBottomBorder*/ true);
        tabActive   = tabRect(BORDER_MID,  BG_PANEL, /*withBottomBorder*/ false);
        // Panels + dashboards: 2-px chamfered corner, 2-px border. Visibly rounder than
        // a slot so containers and contained cells read as different visual tiers.
        panel       = roundedRect(BORDER_MID,  BG_PANEL,    4, 2, 2);
        dashboard   = roundedRect(BORDER_BRT,  BG_PANEL,    4, 2, 2);
        // Panel with no top border + square TL/TR corners. The tab strip above
        // provides the visual top edge: inactive tabs' bottom borders bound the
        // top, while the active tab's open bottom + matching fill flow seamlessly
        // into the panel's interior. BL/BR corners stay rounded.
        panelOpenTop = roundedRect(BORDER_MID, BG_PANEL, 4,
                                   /*chamfer TL TR BL BR */ 0, 0, 2, 2,
                                   /*border  T  R  B  L  */ 0, 2, 2, 2);
        hudStrip    = horizontalStrip(BORDER_BRT, BG_PANEL,  3, 2);
        whitePixel  = solid(Color.WHITE);
    }

    /**
     * Build a rounded-corner border on a flat fill — convenience overload that uses
     * uniform chamfer + uniform border thickness on all four corners / sides.
     */
    private NinePatch roundedRect(Color border, Color fill,
                                  int corner, int chamfer, int borderThickness) {
        return roundedRect(border, fill, corner,
                chamfer, chamfer, chamfer, chamfer,
                borderThickness, borderThickness, borderThickness, borderThickness);
    }

    /**
     * Build a rounded-corner border with per-corner chamfers and per-edge border
     * thicknesses. Setting an edge's thickness to {@code 0} skips that edge's border;
     * setting a corner's chamfer to {@code 0} keeps that corner square (no diagonal
     * cut). Used for the {@link #panelOpenTop} drawable, which needs no top border
     * and square top corners so it can sit flush below a tab strip.
     *
     * @param border       border ink colour
     * @param fill         interior fill colour
     * @param corner       9-patch corner size in source pixels (source side = {@code 2*corner + 1})
     * @param chamferTL    chamfer cells knocked out diagonally at the top-left
     * @param chamferTR    chamfer cells knocked out diagonally at the top-right
     * @param chamferBL    chamfer cells knocked out diagonally at the bottom-left
     * @param chamferBR    chamfer cells knocked out diagonally at the bottom-right
     * @param borderTop    pixels of border drawn inward from the top edge
     * @param borderRight  pixels of border drawn inward from the right edge
     * @param borderBottom pixels of border drawn inward from the bottom edge
     * @param borderLeft   pixels of border drawn inward from the left edge
     */
    private NinePatch roundedRect(Color border, Color fill, int corner,
                                  int chamferTL, int chamferTR, int chamferBL, int chamferBR,
                                  int borderTop, int borderRight, int borderBottom, int borderLeft) {
        int s = 2 * corner + 1;
        Pixmap p = new Pixmap(s, s, Pixmap.Format.RGBA8888);
        p.setBlending(Pixmap.Blending.None);
        p.setColor(0, 0, 0, 0); p.fill();

        // Pass 1 — fill every non-chamfer cell.
        p.setColor(fill);
        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {
                if (isChamferedCorner(x, y, s, chamferTL, chamferTR, chamferBL, chamferBR)) continue;
                p.drawPixel(x, y);
            }
        }
        // Pass 2 — paint border per-edge. Top/Bottom/Left/Right thicknesses are
        // applied independently, so a panel can have any combination of borders
        // (e.g. all four for a closed shape, or three sides for a top-open panel).
        p.setColor(border);
        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {
                if (isChamferedCorner(x, y, s, chamferTL, chamferTR, chamferBL, chamferBR)) continue;
                int distFromTop    = y;
                int distFromBottom = s - 1 - y;
                int distFromLeft   = x;
                int distFromRight  = s - 1 - x;
                boolean onTop    = distFromTop    < borderTop;
                boolean onBottom = distFromBottom < borderBottom;
                boolean onLeft   = distFromLeft   < borderLeft;
                boolean onRight  = distFromRight  < borderRight;
                if (onTop || onBottom || onLeft || onRight) p.drawPixel(x, y);
            }
        }
        return new NinePatch(mkTex(p), corner, corner, corner, corner);
    }

    /**
     * Build a tab-shaped 9-patch — square cornered (visually distinct from the rounded
     * slot / panel / dashboard primitives). Border is drawn 1 px on top + sides
     * always; the bottom border is drawn only when {@code withBottomBorder} is true.
     *
     * <p>An "active" tab passes {@code false} so its bottom is open: when the tab sits
     * flush above a panel and the active tab's fill colour matches the panel fill,
     * the panel's interior bleeds up into the tab's open bottom — the manila-folder
     * pattern that distinguishes a tab from a button.
     *
     * @param border           border ink (used on top + sides, plus bottom if requested)
     * @param fill             interior fill colour
     * @param withBottomBorder draw the bottom border (closed/inactive tab) or skip it
     *                         (open/active tab whose interior merges with the panel below)
     */
    private NinePatch tabRect(Color border, Color fill, boolean withBottomBorder) {
        // Source: 5×5 with corner=2 so the centre 1×1 stretches both axes. Top corners
        // are slightly rounded (1-px chamfer at TL and TR) to keep visual consistency
        // with the rest of the chrome ("all elements have slightly rounded corners");
        // bottom corners stay square so the tab sits flush on the panel below.
        int corner = 2;
        int s = 2 * corner + 1;
        Pixmap p = new Pixmap(s, s, Pixmap.Format.RGBA8888);
        p.setBlending(Pixmap.Blending.None);
        p.setColor(0, 0, 0, 0); p.fill();

        // Pass 1 — fill every non-chamfer cell with the body colour. Chamfer just the
        // top-left and top-right outermost cells.
        p.setColor(fill);
        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {
                if (isTabTopChamfer(x, y, s)) continue;
                p.drawPixel(x, y);
            }
        }
        // Pass 2 — paint border. Top + sides always; bottom only on closed tabs.
        // Chamfered cells are skipped so the rounded corner reads as transparent.
        p.setColor(border);
        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {
                if (isTabTopChamfer(x, y, s)) continue;
                boolean onTop    = y == 0;
                boolean onLeft   = x == 0;
                boolean onRight  = x == s - 1;
                boolean onBottom = withBottomBorder && y == s - 1;
                if (onTop || onLeft || onRight || onBottom) p.drawPixel(x, y);
            }
        }
        return new NinePatch(mkTex(p), corner, corner, corner, corner);
    }

    /** True if (x, y) is the 1-px chamfered top-left or top-right corner cell. The
     *  bottom corners stay square so a tab can sit flush on the panel below it. */
    private static boolean isTabTopChamfer(int x, int y, int s) {
        return (x == 0 && y == 0) || (x == s - 1 && y == 0);
    }

    /** Horizontal strip — top + bottom borders, no caps. Used for the bottom HUD bar
     *  where the strip stretches across the full screen width. */
    private NinePatch horizontalStrip(Color border, Color fill,
                                      int verticalCorner, int borderThickness) {
        int s = 2 * verticalCorner + 1;
        Pixmap p = new Pixmap(s, s, Pixmap.Format.RGBA8888);
        p.setBlending(Pixmap.Blending.None);
        p.setColor(fill); p.fill();
        p.setColor(border);
        for (int t = 0; t < borderThickness; t++) {
            for (int x = 0; x < s; x++) {
                p.drawPixel(x, t);
                p.drawPixel(x, s - 1 - t);
            }
        }
        return new NinePatch(mkTex(p), 0, 0, verticalCorner, verticalCorner);
    }

    /** True if (x, y) is a chamfered cell at one of the four outer corners with the
     *  given per-corner chamfer sizes. {@code chamfer == 0} for a corner means that
     *  corner stays square; {@code chamfer == 1} knocks out the single 1×1 outer
     *  corner cell; {@code chamfer == 2} knocks out an L-shape of 3 cells; etc.
     *  Diagonal Manhattan-distance test from each corner. */
    private static boolean isChamferedCorner(int x, int y, int s,
                                              int chamferTL, int chamferTR,
                                              int chamferBL, int chamferBR) {
        int xr = s - 1 - x;
        int yb = s - 1 - y;
        if (chamferTL > 0 && (x  + y  < chamferTL)) return true;
        if (chamferTR > 0 && (xr + y  < chamferTR)) return true;
        if (chamferBL > 0 && (x  + yb < chamferBL)) return true;
        if (chamferBR > 0 && (xr + yb < chamferBR)) return true;
        return false;
    }

    private Texture solid(Color c) {
        Pixmap p = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        p.setBlending(Pixmap.Blending.None);
        p.setColor(c); p.fill();
        return mkTex(p);
    }

    private Texture mkTex(Pixmap p) {
        Texture t = new Texture(p);
        p.dispose();
        t.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        textures.add(t);
        return t;
    }

    /** 0xRRGGBB — opaque. */
    private static Color rgb(int hex) {
        float r = ((hex >> 16) & 0xFF) / 255f;
        float g = ((hex >>  8) & 0xFF) / 255f;
        float b = ( hex        & 0xFF) / 255f;
        return new Color(r, g, b, 1f);
    }

    @Override
    public void dispose() {
        for (Texture t : textures) t.dispose();
        textures.clear();
    }
}
