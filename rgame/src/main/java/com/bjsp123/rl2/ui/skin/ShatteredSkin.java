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
    // Dark text-area backgrounds with cream borders + bright corner accents,
    // bright yellow titles + highlights, white body text. Kept in sync with
    // {@link UiTheme}.
    static final Color BG_DARK     = rgb(0x0c0a08);  // slot recess
    static final Color BG_PANEL    = rgb(0x1f1d1a);  // panel / dashboard / slot fill (text-area dark)
    static final Color BG_PANEL_HI = rgb(0x2d2a26);  // pressed / focus highlight
    static final Color BG_SLOT_LT  = rgb(0x1f1d1a);  // matches panel — text-on-dark surface
    static final Color BORDER_DIM  = rgb(0x3d3a35);  // slot grid border (visible against panel)
    static final Color BORDER_MID  = rgb(0xdcd6c4);  // panel border (cream)
    static final Color BORDER_BRT  = rgb(0xf0eadc);  // bright cream — dashboard + corner accents
    static final Color ACCENT      = rgb(0xf4ee2b);  // bright yellow — focus, level badges, titles
    static final Color ACCENT_DIM  = rgb(0xb09020);  // dim yellow — pressed border
    static final Color TEXT_WHITE  = rgb(0xffffff);
    static final Color TEXT_DIM    = rgb(0xa8a4a0);  // muted gray (legible on dark panel)
    static final Color TEXT_WARN   = rgb(0xd44848);
    static final Color TEXT_TITLE  = rgb(0xf4ee2b);  // bright yellow (matches ACCENT)

    // ─── Drawables ──────────────────────────────────────────────────────────
    NinePatch slot;          // dim border, dark fill, 1-px chamfer — used for default
                             //   button backgrounds (menu items etc.)
    NinePatch slotLight;     // dim border, LIGHT fill — used for inventory cells and
                             //   HUD action-bar buttons so the 32-px item sprites read
                             //   against a high-contrast background. Combined with
                             //   {@link OutlinedImage} on the icons.
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
        // Light slot — same shape, opaque pale fill. Bound to the actionBox / itemSlot /
        // equipSlot drawables in StoneUi so HUD action buttons and inventory cells share
        // the same high-contrast surface for item sprites to land on.
        slotLight   = roundedRect(BORDER_DIM,  BG_SLOT_LT,  3, 1, 2);
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
        tab         = tabRect(BORDER_MID, BG_DARK,  BORDER_BRT, /*withBottomBorder*/ true);
        tabActive   = tabRect(BORDER_MID, BG_PANEL, BORDER_BRT, /*withBottomBorder*/ false);
        // Panels + dashboards: 1-px cream border with 2×2 bright-cream corner
        // accents at all four corners. Matches the SPD reference inventory and
        // class-description panels — flat warm-gray fill, thin lighter frame,
        // small bright squares marking each corner.
        panel       = panelWithCornerAccents(BORDER_MID, BG_PANEL, BORDER_BRT,
                                             /*borderTop*/ 1, /*right*/ 1, /*bottom*/ 1, /*left*/ 1);
        dashboard   = panelWithCornerAccents(BORDER_BRT, BG_PANEL, BORDER_BRT,
                                             1, 1, 1, 1);
        // Panel with no top border — used when something else (e.g. a tab strip)
        // provides the visual top edge so the active tab's interior flows
        // seamlessly into the panel below it. Bottom + sides keep their cream
        // border + corner accents; the top-corner accents are dropped so the
        // open-top edge reads cleanly.
        panelOpenTop = panelWithCornerAccents(BORDER_MID, BG_PANEL, BORDER_BRT,
                                              0, 1, 1, 1);
        hudStrip    = horizontalStrip(BORDER_MID, BG_PANEL,  3, 1);
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
     * Build a panel 9-patch with optional cream corner-accent squares at each of
     * the four outer corners — a distinctive Shattered Pixel Dungeon mannerism.
     * The accent squares are a 2×2 block in source pixels, anchored in the corner
     * regions of the 9-patch so they survive any stretch (the corner regions are
     * never stretched, only the centre 1×1 is).
     *
     * <p>Per-edge {@code border*} thicknesses let callers drop one or more borders
     * (used by {@link #panelOpenTop} which omits its top border so a tab strip can
     * sit flush above it). When a border is dropped, that edge's two corner accents
     * are also dropped so the open edge reads clean.
     *
     * @param border         border ink colour
     * @param fill           interior fill colour
     * @param cornerAccent   bright accent colour painted as a 2×2 square at each
     *                       corner (overrides the border on those pixels)
     * @param borderTop      pixels of border drawn inward from the top edge (0 = no top border)
     * @param borderRight    pixels of border drawn inward from the right edge
     * @param borderBottom   pixels of border drawn inward from the bottom edge
     * @param borderLeft     pixels of border drawn inward from the left edge
     */
    private NinePatch panelWithCornerAccents(Color border, Color fill, Color cornerAccent,
                                             int borderTop, int borderRight,
                                             int borderBottom, int borderLeft) {
        final int corner     = 4;
        final int s          = 2 * corner + 1;   // 9
        final int accentSize = 2;

        Pixmap p = new Pixmap(s, s, Pixmap.Format.RGBA8888);
        p.setBlending(Pixmap.Blending.None);

        // Pass 1 — flood fill with panel colour.
        p.setColor(fill);
        p.fill();

        // Pass 2 — paint border bands.
        p.setColor(border);
        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {
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

        // Pass 3 — overpaint corner accents. A corner accent only appears at a
        // corner whose two adjacent borders are both present (so an "open top"
        // panel has no top-corner accents).
        p.setColor(cornerAccent);
        boolean tl = borderTop    > 0 && borderLeft  > 0;
        boolean tr = borderTop    > 0 && borderRight > 0;
        boolean bl = borderBottom > 0 && borderLeft  > 0;
        boolean br = borderBottom > 0 && borderRight > 0;
        for (int dy = 0; dy < accentSize; dy++) {
            for (int dx = 0; dx < accentSize; dx++) {
                if (tl) p.drawPixel(dx,             dy);
                if (tr) p.drawPixel(s - 1 - dx,     dy);
                if (bl) p.drawPixel(dx,             s - 1 - dy);
                if (br) p.drawPixel(s - 1 - dx,     s - 1 - dy);
            }
        }
        return new NinePatch(mkTex(p), corner, corner, corner, corner);
    }

    /**
     * Build a tab-shaped 9-patch — distinctly tab-shaped, not just a button-sized
     * box. Source is 7×7 with corner=3 so the visible chrome on every rendered
     * tab is at least 3 source-pixels wide on every side; the top corners are
     * chamfered into a 2-px diagonal so the rounded shape reads clearly even at
     * small sizes; the bottom corners stay square so the tab sits flush on the
     * panel below. Top corners get bright-cream accent dots matching the panel
     * chrome.
     *
     * <p>An "active" tab passes {@code withBottomBorder = false} so its bottom
     * is open: when the tab sits flush above a panel and the active tab's fill
     * colour matches the panel fill, the panel's interior bleeds up into the
     * tab's open bottom — the manila-folder pattern that distinguishes a tab
     * from a button.
     *
     * @param border           border ink (top + sides; bottom too when {@code withBottomBorder})
     * @param fill             interior fill colour
     * @param cornerAccent     bright-cream dot painted at the topmost border pixel
     *                         on each side of the chamfer; matches the panel's
     *                         corner-accent treatment so tab + panel read as a family
     * @param withBottomBorder draw the bottom border (closed/inactive tab) or skip it
     *                         (open/active tab whose interior merges with the panel below)
     */
    private NinePatch tabRect(Color border, Color fill, Color cornerAccent,
                              boolean withBottomBorder) {
        final int corner = 3;
        final int s = 2 * corner + 1;          // 7
        final int chamfer = 2;                 // 2-px diagonal at TL / TR

        Pixmap p = new Pixmap(s, s, Pixmap.Format.RGBA8888);
        p.setBlending(Pixmap.Blending.None);
        p.setColor(0, 0, 0, 0); p.fill();

        // Pass 1 — fill every non-chamfer cell with the body colour.
        p.setColor(fill);
        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {
                if (isTabTopChamfer(x, y, s, chamfer)) continue;
                p.drawPixel(x, y);
            }
        }

        // Pass 2 — paint border. Top + sides always; bottom only on closed tabs.
        // Chamfered cells stay transparent (the rounded corner reads as the
        // surrounding background bleeding in).
        p.setColor(border);
        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {
                if (isTabTopChamfer(x, y, s, chamfer)) continue;
                boolean onTop    = y == 0;
                boolean onLeft   = x == 0;
                boolean onRight  = x == s - 1;
                boolean onBottom = withBottomBorder && y == s - 1;
                if (onTop || onLeft || onRight || onBottom) p.drawPixel(x, y);
            }
        }

        // Pass 3 — bright corner accents at the topmost edge just past each chamfer.
        // Mirrors the panelWithCornerAccents treatment so tabs visually belong to
        // the same family as the panels they sit above.
        p.setColor(cornerAccent);
        p.drawPixel(chamfer,         0);
        p.drawPixel(s - 1 - chamfer, 0);

        return new NinePatch(mkTex(p), corner, corner, corner, corner);
    }

    /** True if (x, y) is one of the chamfered diagonal cells at the top-left or
     *  top-right corner. Manhattan-distance test: a cell is chamfered when the
     *  sum of its distances from the top edge and one side edge is less than
     *  {@code chamfer}. Bottom corners stay square so a tab sits flush on the
     *  panel below it. */
    private static boolean isTabTopChamfer(int x, int y, int s, int chamfer) {
        if (chamfer <= 0) return false;
        int distFromTop   = y;
        int distFromLeft  = x;
        int distFromRight = s - 1 - x;
        if (distFromLeft  + distFromTop < chamfer) return true;   // TL diagonal
        if (distFromRight + distFromTop < chamfer) return true;   // TR diagonal
        return false;
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
