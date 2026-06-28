package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.bjsp123.rl2.logic.TextCatalog;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.Tile;
import com.bjsp123.rl2.Rl2Game;
import com.bjsp123.rl2.model.World;

import java.util.ArrayList;

/**
 * V2 map screen - schematic of the world's level graph. Each level renders
 * as a small trapezoidal mini-map (slight perspective tilt) coloured by
 * tile type for the explored area, with arrows between depths showing the
 * staircase graph. Tap a visited level to bring up an info panel at the
 * bottom of the window with the level's theme, unique themed rooms, and
 * unique mob species.
 */
public final class V2Map extends V2Screen {

    private final Rl2Game game;
    private final Runnable onBack;
    private final World world;
    private final Rect window = new Rect();

    /** Height of the bottom info pane, drawn under the map graph when a
     *  visited level is selected. */
    private static final float INFO_PANE_H = 96f;

    /** Shared world-graph renderer (swirl backdrop, arrows, trapezoid
     *  mini-maps, beacons). Configured + drawn each frame; its hit-rect lists
     *  feed {@link #onTouchDownInBody} and its unvisited centres feed the "?"
     *  glyphs in {@link #drawBodyText}. */
    private final WorldGraphView graph = new WorldGraphView();

    /** World index of the currently-selected level, or -1 for none. */
    private int selected = -1;

    /** Wall-clock accumulator for the pulsing-plus-sign animation. Driven
     *  by {@code Gdx.graphics.getDeltaTime()} in {@link #drawBodyShape}. */
    private float beaconPulseT;
    /** Wall-clock accumulator for the swirling backdrop blobs. Same dt as
     *  {@link #beaconPulseT}; a separate field keeps the swirl phase
     *  independent of the plus-sign pulse. */
    private float bgSwirlT;

    /** Open / closed flag for the teleport-confirmation modal. While
     *  {@code true} the rest of the map screen ignores input - the only
     *  active controls are the Yes / No buttons inside the popup. */
    private boolean confirmOpen;
    /** Destination level index + beacon position that the player is about
     *  to teleport to once they confirm. Set when an active beacon is
     *  clicked; consumed (or discarded) by the Yes / No handler. */
    private int   pendingDestLevel = -1;
    private Point pendingBeaconPos;
    /** Hit rects for the confirmation Yes / No buttons. Populated each
     *  frame in {@link #drawBodyShape} when {@link #confirmOpen} is set. */
    private final Rect confirmYes = new Rect();
    private final Rect confirmNo  = new Rect();
    /** Confirmation popup geometry. Recomputed each frame from the map
     *  window so it tracks resizes. */
    private final Rect confirmPanel = new Rect();

    /** Pan offset applied to the level graph (post-zoom). Drag-in-body
     *  shifts these. Zero by default - graph centred on the window. */
    private float panX, panY;
    /** Set when the map is (re)shown so the next frame pans the graph to
     *  centre the player's current level in the viewport. Cleared once done. */
    private boolean needsCenter = true;
    /** Current zoom factor for the level graph. Eases smoothly toward
     *  {@link #targetZoom} each frame. */
    private float zoom = 1.0f;
    /** Zoom target the wheel sets; {@link #zoom} eases toward it so zooming is
     *  smooth and continuous rather than stepped. */
    private float targetZoom = 1.0f;
    private static final float MIN_ZOOM = 0.4f, MAX_ZOOM = 3.0f;
    /** Multiplicative zoom change per wheel notch. */
    private static final float ZOOM_STEP_FACTOR = 1.2f;
    /** Exponential ease rate (per second) of zoom toward target. */
    private static final float ZOOM_LERP_RATE = 14f;
    /** Last known cursor / finger position; updated on touchDown so
     *  drag-pan can compute deltas without losing the anchor across
     *  release-then-redrag pairs. */
    private float dragLastX, dragLastY;
    private boolean dragging;

    /** When false, beacons are shown but cannot be used to teleport - the map
     *  was opened for viewing (burger / HUD), not via a beacon interaction. */
    private final boolean teleportEnabled;

    public V2Map(Rl2Game game, UiCtx ctx, Runnable onBack, World world) {
        this(game, ctx, onBack, world, false);
    }

    public V2Map(Rl2Game game, UiCtx ctx, Runnable onBack, World world,
                 boolean teleportEnabled) {
        super(ctx);
        this.game   = game;
        this.onBack = onBack;
        this.world  = world;
        this.teleportEnabled = teleportEnabled;
    }

    @Override
    protected Rect modalWindow() { return window; }

    @Override protected String screenId() { return "map"; }

    @Override
    protected void buildLayout() {
        float vw = ctx.worldW();
        float vh = ctx.worldH();
        float winW = Math.min(420f, vw - UIVars.PAD_MODAL);
        float winH = Math.min(UIVars.VIRTUAL_H - 120f, vh - 120f);
        float winY = (vh - winH) * 0.5f;
        window.set((vw - winW) * 0.5f, winY, winW, winH);

        back   = new BackBtn(ctx, onBack);
        burger = makeBurger();
        addStandardBurgerItems(game);
        needsCenter = true;   // centre on the current level next frame
    }

    @Override
    protected boolean onTouchDownInBody(float vx, float vy) {
        // Reset drag tracking - onTouchDragged will detect motion past the
        // ~2-pixel threshold and start panning then. A clean touch release
        // (no drag) leaves dragging=false, so the box-tap below still fires.
        dragLastX = vx;
        dragLastY = vy;
        dragging  = false;
        // While the confirmation popup is open, ALL input goes through its
        // Yes / No buttons - no panning, no beacon clicks, no level
        // selection. Touch outside the popup is swallowed so the player
        // can't accidentally close it by tapping the map.
        if (confirmOpen) {
            if (confirmYes.contains(vx, vy)) {
                confirmTeleport();
            } else if (confirmNo.contains(vx, vy)) {
                cancelTeleportConfirm();
            }
            return true;
        }
        // Beacon plus-signs take priority over the level box behind them -
        // a tap on an active beacon opens the teleport confirmation, on
        // an inactive one does nothing (eaten so it doesn't fall through
        // to box selection).
        for (int i = 0; i < graph.beaconRects.size(); i++) {
            if (!graph.beaconRects.get(i).contains(vx, vy)) continue;
            // Teleport is only offered when the map was opened via a beacon
            // interaction (walked into / waited beside one). Otherwise the
            // beacon is informational - swallow the tap with no teleport.
            if (!teleportEnabled) return true;
            if (!Boolean.TRUE.equals(graph.beaconActive.get(i))) return true;
            int[] ref = graph.beaconRefs.get(i);
            // Out-of-orbs: silently swallow the click. The orb counter at
            // the top of the screen tells the player why nothing happened.
            if (teleportOrbCount() <= 0) return true;
            pendingDestLevel = ref[0];
            pendingBeaconPos = new Point(ref[1], ref[2]);
            confirmOpen      = true;
            return true;
        }
        for (int i = 0; i < graph.boxRects.size(); i++) {
            if (graph.boxRects.get(i).contains(vx, vy)) {
                int worldIdx = graph.boxIndex.get(i);
                Level lvl = world.levels[worldIdx];
                // Only visited levels open the info pane - unexplored
                // levels have nothing to report.
                if (lvl != null && lvl.visited) {
                    selected = (selected == worldIdx) ? -1 : worldIdx;
                }
                return true;
            }
        }
        // Tap inside the window but outside any box clears the selection
        // and arms the pan-drag tracker so a subsequent drag pans the
        // graph.
        if (graphViewport().contains(vx, vy)) {
            selected = -1;
            return true;   // claim the touch so onTouchDragged routes here
        }
        return false;
    }

    @Override
    protected boolean onTouchDragged(float vx, float vy) {
        if (!graphViewport().contains(vx, vy)
                && !graphViewport().contains(dragLastX, dragLastY)) {
            return false;
        }
        float dx = vx - dragLastX;
        float dy = vy - dragLastY;
        if (!dragging && Math.hypot(dx, dy) < 2f) return true;
        dragging = true;
        panX += dx;
        panY += dy;
        dragLastX = vx;
        dragLastY = vy;
        clampPan();
        return true;
    }

    @Override
    protected boolean onScrolled(float amountY) {
        // Smooth mouse-wheel zoom: nudge the target multiplicatively; the actual
        // zoom eases toward it each frame in drawBodyShape. Wheel up (negative
        // amountY) -> larger; down -> smaller.
        if (amountY > 0) targetZoom /= ZOOM_STEP_FACTOR;
        else             targetZoom *= ZOOM_STEP_FACTOR;
        targetZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, targetZoom));
        return true;
    }

    @Override
    protected void drawBodyShape(UiCtx ctx) {
        Window.drawShape(ctx, window.x, window.y, window.w, window.h);
        float dt = Gdx.graphics.getDeltaTime();
        beaconPulseT += dt;
        bgSwirlT     += dt;

        // Ease zoom toward its target for smooth, continuous zooming.
        if (zoom != targetZoom) {
            zoom += (targetZoom - zoom) * Math.min(1f, dt * ZOOM_LERP_RATE);
            if (Math.abs(targetZoom - zoom) < 0.002f) zoom = targetZoom;
        }

        if (world == null || world.levels == null) return;

        // Configure the shared graph renderer for this frame.
        graph.world        = world;
        graph.layoutArea   = graphLayoutArea();
        graph.viewport     = graphViewport();
        graph.zoom         = zoom;
        graph.selected     = selected;
        graph.currentIndex = world.currentLevelIndex;
        graph.swirlT       = bgSwirlT;
        graph.beaconPulseT = beaconPulseT;

        // One-shot centring: pan so the current level sits in the viewport
        // centre when the map is (re)opened.
        if (needsCenter) {
            float[] p = graph.panToCenter(world.currentLevelIndex);
            panX = p[0];
            panY = p[1];
            needsCenter = false;
        }
        // Re-clamp every frame so the zoom ease can't leave content out of view.
        clampPan();
        graph.panX = panX;
        graph.panY = panY;

        ShapeRenderer s = ctx.shapes;
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        // Viewport frame - drawn BEFORE the graph's own scissor (inside
        // graph.draw) so the 1-px border stays visible even when the graph
        // is empty or panned away.
        Rect viewport = graph.viewport;
        s.setColor(UIVars.BORDER_MID);
        s.rect(viewport.x,                  viewport.y,                  viewport.w, 1f);
        s.rect(viewport.x,                  viewport.y + viewport.h - 1, viewport.w, 1f);
        s.rect(viewport.x,                  viewport.y,                  1f,         viewport.h);
        s.rect(viewport.x + viewport.w - 1, viewport.y,                  1f,         viewport.h);

        graph.draw(ctx);

        // graph.draw disables GL blend on exit; re-enable for the chrome below.
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        // Bottom info pane - drawn as a sub-window inside the map window
        // when a visited level is selected. Pure chrome here; text
        // populates in drawBodyText.
        if (selected >= 0 && selected < world.levels.length) {
            Level lvl = world.levels[selected];
            if (lvl != null && lvl.visited) {
                Rect info = infoPaneRect();
                Window.drawShape(ctx, info.x, info.y, info.w, info.h);
            }
        }

        // Teleport confirmation popup chrome - drawn last so it sits on
        // top of every other shape in this pass.
        drawConfirmShape(s);

        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    /** Rectangle the box grid lays out + centres within - between the title
     *  row and the info-pane / orb-strip reservation. Matches the framing the
     *  legacy boxX/boxY baked in, now consumed by {@link WorldGraphView}. */
    private Rect graphLayoutArea() {
        float topY = window.top() - headerBandH() - 10f;
        float bottomY = window.y + INFO_PANE_H + 20f;
        return new Rect(window.x, bottomY, window.w, topY - bottomY);
    }

    /** Visible band the graph is allowed to occupy - between the title
     *  row and the bottom orb-count strip + info-pane reservation. Used
     *  for scissor clipping and pan clamping. */
    private Rect graphViewport() {
        float top    = window.top() - headerBandH();
        float bottom = window.y + INFO_PANE_H + ORB_STRIP_H + 16f;
        return new Rect(window.x + 8f, bottom,
                window.w - 16f, top - bottom);
    }

    /** Height of the orb-count strip drawn between the graph viewport and
     *  the bottom info pane. Sized for one line of regular text plus
     *  padding. */
    private static final float ORB_STRIP_H = 20f;

    /** Keep the pan within sane bounds - the graph's bounding box must
     *  retain at least a small overlap with the viewport so the user
     *  can't pan it entirely off-screen. */
    private void clampPan() {
        if (graph == null) return;
        float[] cb = graph.contentBoundsZeroPan();   // {x, y, w, h} at pan = 0
        if (cb == null) return;
        Rect vp = graphViewport();
        panX = clampAxis(panX, cb[0], cb[2], vp.x, vp.w);
        panY = clampAxis(panY, cb[1], cb[3], vp.y, vp.h);
    }

    /** Clamp one pan axis so the graph can be scrolled until a far edge reaches
     *  the viewport edge (a small margin of breathing room), but never so far
     *  that the content leaves the viewport. Content smaller than the viewport
     *  is centred and can't be scrolled. */
    private static float clampAxis(float pan, float contentMin, float contentLen,
                                   float vpMin, float vpLen) {
        float margin = 28f;
        float contentMax = contentMin + contentLen;
        if (contentLen + 2f * margin <= vpLen) {
            return vpMin + vpLen * 0.5f - (contentMin + contentMax) * 0.5f;
        }
        float hi = vpMin + margin - contentMin;            // most positive shift
        float lo = vpMin + vpLen - margin - contentMax;    // most negative shift
        return Math.max(lo, Math.min(hi, pan));
    }

    /** Count of TELEPORT_ORBs the player currently carries in their bag.
     *  Used to gate beacon clicks and label the orb counter in the header.
     *  Returns 0 if the play session isn't fully wired (defensive - the
     *  map screen can be opened in attract mode too). */
    private int teleportOrbCount() {
        if (game.currentPlay == null) return 0;
        Mob player = com.bjsp123.rl2.logic.TurnSystem.findPlayer(world.currentLevel());
        if (player == null || player.inventory == null) return 0;
        int n = 0;
        for (com.bjsp123.rl2.model.Item it : player.inventory.bag) {
            if (it == null) continue;
            if ("TELEPORT_ORB".equals(it.type)) n += Math.max(1, it.count);
        }
        return n;
    }

    /** Pop one TELEPORT_ORB from the player's bag. Decrements a stack or
     *  removes the item if the stack was 1. No-op if the player or bag
     *  isn't available. */
    private void consumeOneTeleportOrb() {
        if (game.currentPlay == null) return;
        Mob player = com.bjsp123.rl2.logic.TurnSystem.findPlayer(world.currentLevel());
        if (player == null || player.inventory == null) return;
        for (com.bjsp123.rl2.model.Item it : new ArrayList<>(player.inventory.bag)) {
            if (it == null) continue;
            if (!"TELEPORT_ORB".equals(it.type)) continue;
            com.bjsp123.rl2.logic.InventorySystem.removeOneFromBag(player.inventory, it);
            return;
        }
    }

    /** Confirmation popup "Yes" handler - consume one orb and teleport,
     *  then close the map back to the play screen. */
    private void confirmTeleport() {
        if (pendingBeaconPos == null || pendingDestLevel < 0) {
            cancelTeleportConfirm();
            return;
        }
        if (teleportOrbCount() <= 0) {
            cancelTeleportConfirm();
            return;
        }
        consumeOneTeleportOrb();
        boolean ok = game.currentPlay != null
                && game.currentPlay.teleportToBeacon(pendingDestLevel, pendingBeaconPos);
        confirmOpen      = false;
        pendingDestLevel = -1;
        pendingBeaconPos = null;
        if (ok) onBack.run();
    }

    /** Confirmation popup "No" handler - dismiss without consuming an orb. */
    private void cancelTeleportConfirm() {
        confirmOpen      = false;
        pendingDestLevel = -1;
        pendingBeaconPos = null;
    }

    /** Position + draw the confirmation popup chrome (panel + Yes / No
     *  button bodies). Sized as a small modal centred on the map window.
     *  Hit rects are written into {@link #confirmYes} / {@link #confirmNo}
     *  so {@link #onTouchDownInBody} can dispatch the tap. Text labels
     *  are painted later in {@link #drawConfirmText}. */
    private void drawConfirmShape(ShapeRenderer s) {
        if (!confirmOpen) return;
        float panelW = 240f;
        float panelH = 90f;
        float panelX = window.cx() - panelW * 0.5f;
        float panelY = window.cy() - panelH * 0.5f;
        confirmPanel.set(panelX, panelY, panelW, panelH);
        // Panel background - fully opaque so the swirling backdrop and any
        // label text behind the modal can't bleed through. Single solid
        // fill + a clear 2-px border so the dialog reads as a proper
        // modal, not a translucent overlay.
        s.setColor(0.06f, 0.06f, 0.09f, 1f);
        s.rect(panelX, panelY, panelW, panelH);
        s.setColor(UIVars.ACCENT);
        // 2-px border by drawing 4 thin rects.
        s.rect(panelX,            panelY,            panelW, 2f);
        s.rect(panelX,            panelY + panelH-2, panelW, 2f);
        s.rect(panelX,            panelY,            2f,     panelH);
        s.rect(panelX + panelW-2, panelY,            2f,     panelH);

        // Two big buttons centred along the bottom half of the popup.
        float btnW = 80f;
        float btnH = 26f;
        float btnY = panelY + 10f;
        float gap  = 16f;
        float yesX = window.cx() - btnW - gap * 0.5f;
        float noX  = window.cx() + gap * 0.5f;
        confirmYes.set(yesX, btnY, btnW, btnH);
        confirmNo .set(noX,  btnY, btnW, btnH);
        s.setColor(UIVars.BORDER_MID);
        s.rect(yesX, btnY, btnW, btnH);
        s.rect(noX,  btnY, btnW, btnH);
        s.setColor(0f, 0f, 0f, 1f);
        s.rect(yesX + 1f, btnY + 1f, btnW - 2f, btnH - 2f);
        s.rect(noX  + 1f, btnY + 1f, btnW - 2f, btnH - 2f);
    }

    /** Paint the confirmation popup's text labels: prompt at the top,
     *  "Yes" / "No" on the two buttons. */
    private void drawConfirmText(UiCtx ctx) {
        if (!confirmOpen) return;
        int depth = -1;
        if (pendingDestLevel >= 0 && pendingDestLevel < world.levels.length
                && world.levels[pendingDestLevel] != null) {
            depth = world.levels[pendingDestLevel].depth;
        }
        String prompt = TextCatalog.format("ui.map.teleportConfirm",
                TextCatalog.vars("depth", depth,
                        "orbs", teleportOrbCount()));
        TextDraw.centre(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                prompt, window.cx(), confirmPanel.y + confirmPanel.h - 14f);
        TextDraw.centre(ctx, ctx.fontRegular, UIVars.ACCENT,
                TextCatalog.get("ui.map.teleportYes"),
                confirmYes.cx(), confirmYes.cy() - 4f);
        TextDraw.centre(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                TextCatalog.get("ui.map.teleportNo"),
                confirmNo.cx(), confirmNo.cy() - 4f);
    }

    @Override
    protected void drawBodyText(UiCtx ctx) {
        TextDraw.centre(ctx, ctx.fontHeader, UIVars.ACCENT,
                TextCatalog.get("ui.map.title"),
                window.cx(), window.top() - ctx.headerLineH());
        // "?" glyphs on every unvisited level box - reads as "you haven't
        // been here yet" so the player doesn't mistake an empty fog tile
        // for an explored-but-empty one. Centres come from the shape pass.
        graph.drawUnvisitedGlyphs(ctx);
        // Orb counter sits in its own strip BELOW the scrollable graph and
        // ABOVE the info pane. Painted by drawBodyText since it's text.
        float orbY = window.y + INFO_PANE_H + 16f + ORB_STRIP_H * 0.5f + 4f;
        TextDraw.centre(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                TextCatalog.format("ui.map.orbCount",
                        TextCatalog.vars("count", teleportOrbCount())),
                window.cx(), orbY);
        if (world == null || world.levels == null) return;

        // Per-box depth labels were intentionally removed - each level's
        // box already conveys position via column + row; an additional
        // "L{n}" caption adds clutter without information.

        // Bottom info pane text - only when a visited level is selected.
        if (selected >= 0 && selected < world.levels.length) {
            Level lvl = world.levels[selected];
            if (lvl != null && lvl.visited) {
                drawInfoPaneText(ctx, lvl);
            }
        }
        // Confirmation popup text - drawn last so it sits on top of every
        // other label in this pass.
        drawConfirmText(ctx);
    }

    private Rect infoPaneRect() {
        float pad = 12f;
        return new Rect(window.x + pad, window.y + pad,
                window.w - 2 * pad, INFO_PANE_H);
    }

    private void drawInfoPaneText(UiCtx ctx, Level lvl) {
        Rect info = infoPaneRect();
        float left = info.x + 12f;
        float top  = info.top() - 18f;
        float textW = info.right() - left - 12f;

        // Header: depth + type (special kind if any, else the visual theme).
        String type = prettify(lvl.kind != null && lvl.kind != Level.LevelKind.REGULAR
                ? lvl.kind.name()
                : (lvl.theme == null ? "" : lvl.theme.name()));
        TextDraw.leftFit(ctx, ctx.fontRegular, UIVars.ACCENT,
                TextCatalog.format("ui.map.depthType",
                        TextCatalog.vars("depth", lvl.depth, "type", type)),
                left, top, textW);
        top -= 18f;

        // Percent explored.
        TextDraw.leftFit(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                TextCatalog.format("ui.map.explored",
                        TextCatalog.vars("pct", exploredPercent(lvl))),
                left, top, textW);
        top -= 16f;

        // Beacon status: lit / present-but-unlit / none.
        int beacon = beaconState(lvl);
        String beaconVal = beacon == 2 ? TextCatalog.get("ui.map.beaconLit")
                         : beacon == 1 ? TextCatalog.get("ui.map.beaconUnlit")
                                       : TextCatalog.get("ui.map.beaconNone");
        TextDraw.leftFit(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                TextCatalog.format("ui.map.beacon", TextCatalog.vars("state", beaconVal)),
                left, top, textW);
        top -= 16f;

        // Gemforge presence.
        String forgeVal = hasGemforge(lvl) ? TextCatalog.get("ui.map.yes")
                                           : TextCatalog.get("ui.map.no");
        TextDraw.leftFit(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                TextCatalog.format("ui.map.gemforge", TextCatalog.vars("state", forgeVal)),
                left, top, textW);
    }

    /** "CRYSTAL" -> "Crystal", "MIRRORMATCH" -> "Mirrormatch". */
    private static String prettify(String enumName) {
        if (enumName == null || enumName.isEmpty()) return "";
        return enumName.charAt(0) + enumName.substring(1).toLowerCase().replace('_', ' ');
    }

    /** Percent of {@code lvl}'s floor-like tiles the player has explored. */
    private static int exploredPercent(Level lvl) {
        if (lvl.explored == null || lvl.tiles == null) return 0;
        int total = 0, seen = 0;
        for (int x = 0; x < lvl.width; x++) {
            for (int y = 0; y < lvl.height; y++) {
                if (lvl.tiles[x][y] == null || !lvl.tiles[x][y].isFloorLike()) continue;
                total++;
                if (lvl.explored[x][y]) seen++;
            }
        }
        return total == 0 ? 0 : Math.round(100f * seen / total);
    }

    /** 2 = a lit (active) beacon present, 1 = an unlit beacon present, 0 = none. */
    private static int beaconState(Level lvl) {
        if (lvl.tiles == null) return 0;
        boolean unlit = false;
        for (int x = 0; x < lvl.width; x++) {
            for (int y = 0; y < lvl.height; y++) {
                Tile t = lvl.tiles[x][y];
                if (t == Tile.BEACON_ACTIVE) return 2;
                if (t == Tile.BEACON_INACTIVE) unlit = true;
            }
        }
        return unlit ? 1 : 0;
    }

    /** True if a gem forge (gem hearth) stands anywhere on the level. */
    private static boolean hasGemforge(Level lvl) {
        if (lvl.tiles == null) return false;
        for (int x = 0; x < lvl.width; x++) {
            for (int y = 0; y < lvl.height; y++) {
                if (lvl.tiles[x][y] == Tile.GEM_HEARTH_L) return true;
            }
        }
        return false;
    }

    @Override
    protected void onEscape() { onBack.run(); }
}
