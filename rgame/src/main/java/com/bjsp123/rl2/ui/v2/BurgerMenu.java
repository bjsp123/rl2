package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.bjsp123.rl2.logic.TextCatalog;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared burger drop-down menu geometry + chrome: a centred modal
 * column-of-buttons window, same shape as every other V2 modal so the user
 * reads it as "a window with a list of choices", not a corner dropdown.
 *
 * <p>Owns the labels, bound actions, panel + per-item rects, open flag, and
 * pressed index. {@link #release} fires the bound action itself (after
 * closing, so a navigation target rebuilds layout cleanly). Scrim/dim
 * drawing stays with the owner since the two hosts layer it differently
 * (V2Screen via the stage {@code Scrim}, V2Hud inline in its shapes pass).
 *
 * <p>{@link #populateStandard} is the single source of truth for burger
 * contents everywhere (see the UI-principles section of CLAUDE.md): always
 * Settings + Encyclopedia; in a run adds Level Info / Map / Log; Credits
 * out of a run; Main Menu last except on the title screen (which IS the
 * main menu). Owners differ only in HOW a destination opens (screens push
 * screens, the HUD opens in-place popups) - that's the {@link Destinations}
 * implementation, not the item list.
 */
public final class BurgerMenu {

    /** Where the standard burger items lead. Screens push V2 screens; the
     *  in-play HUD opens popups / runs callbacks - the menu doesn't care. */
    public interface Destinations {
        void openSettings();
        void openEncyclopedia();
        default void openLevelInfo() {}
        default void openMap()      {}
        default void openLog()      {}
        default void openCredits()  {}
        default void goMainMenu()   {}
    }

    private static final float ITEM_H   = 56f;
    private static final float ITEM_GAP = 8f;
    private static final float PAD_X    = 18f;
    private static final float PAD_TOP  = 18f;
    private static final float PAD_BOT  = 18f;
    private static final float MAX_W    = 320f;

    public final List<String> labels = new ArrayList<>();
    public final Rect panel = new Rect();
    public final List<Rect> itemRects = new ArrayList<>();
    private final List<Runnable> actions = new ArrayList<>();
    public boolean open;
    private int pressed = -1;

    public void clearItems() {
        labels.clear();
        itemRects.clear();
        actions.clear();
        open = false;
        pressed = -1;
    }

    public void add(String label, Runnable action) {
        labels.add(label);
        itemRects.add(new Rect());
        actions.add(action);
    }

    /** The one standard burger item set, in canonical order. {@code title}
     *  marks the title screen (no Main Menu item - it IS the main menu);
     *  {@code inRun} adds the run-context destinations. */
    public void populateStandard(boolean title, boolean inRun, Destinations d) {
        add(TextCatalog.get("ui.menu.settings"),     d::openSettings);
        add(TextCatalog.get("ui.menu.encyclopedia"), d::openEncyclopedia);
        if (inRun) {
            add(TextCatalog.get("ui.menu.levelInfo"), d::openLevelInfo);
            add(TextCatalog.get("ui.menu.map"),       d::openMap);
            add(TextCatalog.get("ui.menu.log"),       d::openLog);
        } else {
            add(TextCatalog.get("ui.menu.credits"),   d::openCredits);
        }
        if (!title) {
            add(TextCatalog.get("ui.menu.main"),      d::goMainMenu);
        }
    }

    public boolean isEmpty() { return labels.isEmpty(); }

    /** Position the panel + per-item rects centred on the viewport. Cheap to
     *  recompute every frame. Top item gets the highest y - libGDX is y-up,
     *  so the first label is the highest one on screen, matching top-down
     *  reading order. */
    public void layout(UiCtx ctx) {
        int   n      = labels.size();
        float panelW = Math.min(MAX_W, ctx.worldW() - 24f);
        float panelH = PAD_TOP + PAD_BOT + n * ITEM_H + (n - 1) * ITEM_GAP;
        float panelX = (ctx.worldW() - panelW) * 0.5f;
        float panelY = (ctx.worldH() - panelH) * 0.5f;
        panel.set(panelX, panelY, panelW, panelH);
        for (int i = 0; i < n; i++) {
            float iy = panelY + panelH - PAD_TOP - (i + 1) * ITEM_H - i * ITEM_GAP;
            itemRects.get(i).set(panelX + PAD_X, iy, panelW - 2 * PAD_X, ITEM_H);
        }
    }

    /** Window chrome + per-item button chrome. Caller has an active
     *  {@link ShapeRenderer} Filled batch (and has already drawn its scrim). */
    public void drawShapes(UiCtx ctx) {
        ShapeRenderer s = ctx.shapes;
        Window.drawShape(ctx, panel.x, panel.y, panel.w, panel.h);
        for (int i = 0; i < itemRects.size(); i++) {
            Rect r = itemRects.get(i);
            Edges.drawTriLine(s, r.x, r.y, r.w, r.h, UIVars.HUD_LINE_W);
            s.setColor(i == pressed ? UIVars.BTN_PRESSED_BG : UIVars.BTN_BG);
            s.rect(r.x + UIVars.HUD_BORDER, r.y + UIVars.HUD_BORDER,
                    r.w - 2 * UIVars.HUD_BORDER, r.h - 2 * UIVars.HUD_BORDER);
        }
    }

    /** Centred header-weight item labels. Caller has an active batch. */
    public void drawLabels(UiCtx ctx) {
        for (int i = 0; i < labels.size(); i++) {
            Rect r = itemRects.get(i);
            TextDraw.centre(ctx, ctx.fontHeader,
                    i == pressed ? UIVars.ACCENT : UIVars.TEXT_BODY,
                    labels.get(i), r.cx(), r.cy() + 8f);
        }
    }

    /** Touch-down while open: arm an item press, or close on a tap outside
     *  the panel that also misses the burger button itself. Always consumes
     *  the touch - the menu is modal. Caller guards on {@link #open}. */
    public boolean touchDown(float vx, float vy, Rect burgerBtnRect) {
        for (int i = 0; i < itemRects.size(); i++) {
            if (itemRects.get(i).contains(vx, vy)) {
                pressed = i;
                return true;
            }
        }
        if (!panel.contains(vx, vy)
                && !(burgerBtnRect != null && burgerBtnRect.contains(vx, vy))) {
            open = false;
        }
        return true;
    }

    /** True while an item press is pending (armed by {@link #touchDown}). */
    public boolean hasPress() { return pressed >= 0; }

    /** Release the pending press. When the release lands on the armed item,
     *  closes the menu FIRST (so a navigation action rebuilds layout
     *  cleanly), then runs the item's bound action. Returns the fired item
     *  index, else -1. */
    public int release(float vx, float vy) {
        int idx = pressed;
        pressed = -1;
        if (idx >= 0 && idx < itemRects.size()
                && itemRects.get(idx).contains(vx, vy)) {
            open = false;
            Runnable a = actions.get(idx);
            if (a != null) a.run();
            return idx;
        }
        return -1;
    }
}
