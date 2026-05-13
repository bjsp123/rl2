package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.bjsp123.rl2.logic.EventLog;
import com.bjsp123.rl2.logic.InventorySystem;
import com.bjsp123.rl2.logic.ItemSystem;
import com.bjsp123.rl2.logic.RecipeSystem;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.LogEvent;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.world.render.ItemSprites;

import java.util.ArrayList;
import java.util.List;

/**
 * V2 crafting popup — three input slots + a result preview, with a picker
 * overlay for filling each slot from the player's inventory. Tap Confirm
 * to consume one unit from each filled slot and add the matched recipe
 * result to the bag.
 *
 * <p>Recipe matching is delegated to {@link RecipeSystem#tryMatch} — same
 * logic the V1 crafting screen uses, so any recipe registered in
 * {@link RecipeSystem#ALL} works here.
 */
public final class V2Crafting implements com.bjsp123.rl2.ui.v2.stage.V2Popup {

    private static final int N_INPUTS = 3;

    private final UiCtx ctx;
    private boolean open;
    private Mob player;

    /** Items bound to each input slot. Items remain in the player's bag
     *  until {@link #confirm()} runs — the slot just holds a reference. */
    private final Item[] slots = new Item[N_INPUTS];

    private final Rect window         = new Rect();
    private final Rect[] slotRects    = new Rect[N_INPUTS];
    private final Rect resultRect     = new Rect();
    private final Rect confirmBtn     = new Rect();
    private final Rect cancelBtn      = new Rect();

    private final boolean[] slotPressed = new boolean[N_INPUTS];
    private boolean confirmPressed, cancelPressed;
    private boolean resultPressed;

    /** When >= 0, the picker overlay is open and a tap on any inventory
     *  cell will assign that item to the indicated input slot. */
    private int pickerSlot = -1;
    private final List<Rect> pickerCells = new ArrayList<>();
    private final List<Item> pickerItems = new ArrayList<>();
    private int pickerPressed = -1;

    /** Optional callback fired with the freshly-crafted item right after
     *  it lands in the player's bag. PlayScreen uses this to feed the
     *  AchievementSystem; null when no observer is interested. */
    private java.util.function.Consumer<Item> onCrafted;

    public void setOnCrafted(java.util.function.Consumer<Item> cb) {
        this.onCrafted = cb;
    }

    public V2Crafting(UiCtx ctx) {
        this.ctx = ctx;
        for (int i = 0; i < slotRects.length; i++) slotRects[i] = new Rect();
    }

    public void setPlayer(Mob p) { this.player = p; }
    public boolean isOpen() { return open; }

    public void toggle() { if (open) close(); else openInv(); }
    private void openInv() {
        open = true;
        for (int i = 0; i < slots.length; i++) slots[i] = null;
    }
    public void close() {
        open = false;
        pickerSlot = -1;
        for (int i = 0; i < slots.length; i++) slots[i] = null;
    }

    /** Open with {@code item} pre-loaded into the first empty slot — same
     *  contract as the V1 crafting screen, used by inventory's Combine
     *  button to chain into here with a chosen item. */
    public void openWith(Item item) {
        open = true;
        if (item == null) return;
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == null) { slots[i] = item; return; }
        }
    }

    @Override
    public void renderSelf() {
        if (!open) return;
        layoutRects();
        renderShapesPass();
        renderTextPass();
    }

    private void layoutRects() {
        float vw = ctx.worldW();
        float vh = ctx.worldH();
        float winW = Math.min(340f, vw - Pal.PAD_MODAL);
        float winH = Math.min(440f, vh - 100f);
        window.set((vw - winW) * 0.5f, (vh - winH) * 0.5f, winW, winH);

        // Inputs row + result cell, just below the header.
        float cellSz = 56f;
        float gap = 12f;
        float rowW = 3 * cellSz + 2 * gap + 28f + cellSz;        // 3 inputs + arrow + result
        float rowX = window.cx() - rowW * 0.5f;
        float rowY = window.top() - 80f - cellSz;
        for (int i = 0; i < slotRects.length; i++) {
            slotRects[i].set(rowX + i * (cellSz + gap), rowY, cellSz, cellSz);
        }
        resultRect.set(rowX + 3 * cellSz + 2 * gap + 28f, rowY, cellSz, cellSz);

        // Confirm + cancel.
        float btnW = 110f;
        float btnH = 40f;
        float btnY = window.y + 16f;
        confirmBtn.set(window.cx() - btnW - 6f, btnY, btnW, btnH);
        cancelBtn .set(window.cx() + 6f,        btnY, btnW, btnH);

        // Picker overlay rects.
        if (pickerSlot >= 0) {
            pickerCells.clear();
            pickerItems.clear();
            if (player != null && player.inventory != null) {
                for (Item it : player.inventory.bag) {
                    if (it == null) continue;
                    pickerItems.add(it);
                }
            }
            // 5-col grid centred in the picker window.
            int cols = 5;
            float pCellSz = 44f;
            float pGap = 6f;
            float pickW = Math.min(280f, vw - 32f);
            int rows = Math.max(1,
                    (int) Math.ceil(pickerItems.size() / (float) cols));
            float gridH = rows * pCellSz + (rows - 1) * pGap;
            float pickH = gridH + 80f;
            pickH = Math.min(pickH, vh - 100f);
            float pX = (vw - pickW) * 0.5f;
            float pY = (vh - pickH) * 0.5f;
            float gridW = cols * pCellSz + (cols - 1) * pGap;
            float gridX = pX + (pickW - gridW) * 0.5f;
            float gridTop = pY + pickH - 56f;
            for (int i = 0; i < pickerItems.size(); i++) {
                int r = i / cols, c = i % cols;
                pickerCells.add(new Rect(
                        gridX + c * (pCellSz + pGap),
                        gridTop - pCellSz - r * (pCellSz + pGap),
                        pCellSz, pCellSz));
            }
            // Stash the picker window rect at the end so render() can reach it.
            // We rebuild this every frame — cheap.
            pickerWindow.set(pX, pY, pickW, pickH);
        }
    }

    private final Rect pickerWindow = new Rect();

    private void renderShapesPass() {
        ctx.applyProjection();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        ShapeRenderer s = ctx.shapes;
        s.begin(ShapeRenderer.ShapeType.Filled);

        // Modal dim.
        s.setColor(0f, 0f, 0f, Pal.DIM_ALPHA);
        s.rect(0, 0, ctx.worldW(), ctx.worldH());
        Window.drawShape(ctx, window.x, window.y, window.w, window.h);

        // Input slot cells.
        for (int i = 0; i < slotRects.length; i++) drawSlot(s, slotRects[i]);
        // Result cell.
        drawSlot(s, resultRect);

        // Confirm + cancel buttons.
        Item result = RecipeSystem.tryMatch(slots[0], slots[1], slots[2]);
        boolean confirmEnabled = result != null;
        drawBtn(s, confirmBtn, confirmPressed, confirmEnabled);
        drawBtn(s, cancelBtn,  cancelPressed,  true);

        // Picker overlay.
        if (pickerSlot >= 0) {
            s.setColor(0f, 0f, 0f, 0.4f);
            s.rect(0, 0, ctx.worldW(), ctx.worldH());
            Window.drawShape(ctx,
                    pickerWindow.x, pickerWindow.y, pickerWindow.w, pickerWindow.h);
            for (int i = 0; i < pickerCells.size(); i++) {
                drawSlot(s, pickerCells.get(i));
                if (i == pickerPressed) {
                    s.setColor(Pal.PANEL_HI);
                    Rect r = pickerCells.get(i);
                    s.rect(r.x + 2, r.y + 2, r.w - 4, r.h - 4);
                }
            }
        }

        s.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    private void drawSlot(ShapeRenderer s, Rect r) {
        Edges.drawTriLine(s, r.x, r.y, r.w, r.h, Pal.HUD_LINE_W);
        s.setColor(UiColors.SLOT_BG);
        s.rect(r.x + Pal.HUD_BORDER, r.y + Pal.HUD_BORDER,
                r.w - 2 * Pal.HUD_BORDER, r.h - 2 * Pal.HUD_BORDER);
    }

    private void drawBtn(ShapeRenderer s, Rect r, boolean pressed, boolean enabled) {
        if (!enabled) {
            // Disabled: all three border lines collapse to the inner shade.
            Edges.drawTriLine(s, r.x, r.y, r.w, r.h, Pal.HUD_LINE_W,
                    UiColors.BORDER_INNER, UiColors.BORDER_INNER, UiColors.BORDER_INNER);
        } else {
            Edges.drawTriLine(s, r.x, r.y, r.w, r.h, Pal.HUD_LINE_W);
        }
        s.setColor(pressed ? UiColors.BTN_PRESSED_BG : UiColors.BTN_BG);
        s.rect(r.x + Pal.HUD_BORDER, r.y + Pal.HUD_BORDER,
                r.w - 2 * Pal.HUD_BORDER, r.h - 2 * Pal.HUD_BORDER);
    }

    private void renderTextPass() {
        ctx.batch.begin();
        TextDraw.centre(ctx, ctx.fontHeader, Pal.ACCENT, "Crafting",
                window.cx(), window.top() - ctx.headerLineH());

        // Slot icons.
        for (int i = 0; i < slotRects.length; i++) {
            drawCellIcon(slotRects[i], slots[i]);
        }
        // Result preview icon.
        Item result = RecipeSystem.tryMatch(slots[0], slots[1], slots[2]);
        drawCellIcon(resultRect, result);

        // Arrow glyph between last input and result — drawn as text since
        // we don't have a vector arrow primitive yet.
        TextDraw.centre(ctx, ctx.fontHeader,
                result != null ? Pal.ACCENT : Pal.DIM,
                "→",
                slotRects[2].right() + 14f, slotRects[2].cy() + 4f);

        // Button labels.
        TextDraw.centre(ctx, ctx.fontRegular,
                result == null ? Pal.DIM : Pal.WHITE,
                "Combine", confirmBtn.cx(), confirmBtn.cy() + 6f);
        TextDraw.centre(ctx, ctx.fontRegular, Pal.WHITE,
                "Cancel", cancelBtn.cx(), cancelBtn.cy() + 6f);

        // Help line.
        if (result == null) {
            TextDraw.centre(ctx, ctx.fontRegular, Pal.DIM,
                    "Tap a slot to choose an ingredient.",
                    window.cx(), confirmBtn.top() + 28f);
        } else {
            String name = result.name != null ? result.name : result.type;
            TextDraw.centre(ctx, ctx.fontRegular, Pal.ACCENT,
                    "Yields: " + name,
                    window.cx(), confirmBtn.top() + 28f);
        }

        // Recipe list under the slots — read-only summary.
        float top = resultRect.y - 20f;
        TextDraw.left(ctx, ctx.fontRegular, Pal.DIM, "Recipes:",
                window.x + 16f, top);
        top -= 18f;
        for (RecipeSystem.Recipe r : RecipeSystem.ALL) {
            if (top < confirmBtn.top() + 50f) break;
            String desc = r.describe();
            if (desc.length() > 42) desc = desc.substring(0, 40) + "…";
            TextDraw.left(ctx, ctx.fontRegular, Pal.WHITE,
                    desc, window.x + Pal.PAD_CONTENT, top);
            top -= 16f;
        }

        // Picker overlay — item icons.
        if (pickerSlot >= 0) {
            TextDraw.centre(ctx, ctx.fontHeader, Pal.ACCENT,
                    "Choose item",
                    pickerWindow.cx(),
                    pickerWindow.top() - ctx.headerLineH());
            for (int i = 0; i < pickerCells.size(); i++) {
                drawCellIcon(pickerCells.get(i), pickerItems.get(i));
            }
        }

        ctx.batch.end();
    }

    private void drawCellIcon(Rect cell, Item item) {
        if (item == null) return;
        TextureRegion region = ItemSprites.regionFor(item);
        if (region == null) return;
        float pad = 4f;
        ctx.batch.draw(region,
                cell.x + pad, cell.y + pad,
                cell.w - 2 * pad, cell.h - 2 * pad);
    }

    /** Called by Confirm. Consumes one unit from each filled slot and adds
     *  the matched recipe result to the player's bag. */
    private void confirm() {
        if (player == null) return;
        Item result = RecipeSystem.tryMatch(slots[0], slots[1], slots[2]);
        if (result == null) return;
        for (int i = 0; i < N_INPUTS; i++) {
            if (slots[i] != null) {
                InventorySystem.removeOneFromBag(player.inventory, slots[i]);
            }
            slots[i] = null;
        }
        InventorySystem.addToBag(player.inventory, result);
        EventLog.add(new LogEvent(
                "Combined: " + ItemSystem.displayName(result),
                LogEvent.EventPriority.HIGH, true));
        if (onCrafted != null) onCrafted.accept(result);
    }

    public InputProcessor input() {
        return new InputAdapter() {
            @Override
            public boolean touchDown(int sx, int sy, int p, int b) {
                if (!open) return false;
                float vx = ctx.unprojectX(sx, sy);
                float vy = ctx.unprojectY(sx, sy);

                // Picker overlay intercepts everything when up.
                if (pickerSlot >= 0) {
                    for (int i = 0; i < pickerCells.size(); i++) {
                        if (pickerCells.get(i).contains(vx, vy)) {
                            pickerPressed = i;
                            return true;
                        }
                    }
                    if (!pickerWindow.contains(vx, vy)) pickerSlot = -1;
                    return true;
                }

                if (confirmBtn.contains(vx, vy)) { confirmPressed = true; return true; }
                if (cancelBtn .contains(vx, vy)) { cancelPressed  = true; return true; }
                for (int i = 0; i < slotRects.length; i++) {
                    if (slotRects[i].contains(vx, vy)) {
                        slotPressed[i] = true;
                        return true;
                    }
                }
                if (resultRect.contains(vx, vy)) { resultPressed = true; return true; }
                if (!window.contains(vx, vy)) close();
                return true;
            }

            @Override
            public boolean touchUp(int sx, int sy, int p, int b) {
                if (!open) return false;
                float vx = ctx.unprojectX(sx, sy);
                float vy = ctx.unprojectY(sx, sy);

                // Picker selection.
                if (pickerPressed >= 0) {
                    int idx = pickerPressed;
                    pickerPressed = -1;
                    if (idx < pickerCells.size()
                            && pickerCells.get(idx).contains(vx, vy)) {
                        if (pickerSlot >= 0 && pickerSlot < slots.length
                                && idx < pickerItems.size()) {
                            slots[pickerSlot] = pickerItems.get(idx);
                        }
                        pickerSlot = -1;
                    }
                    return true;
                }

                if (confirmPressed) {
                    confirmPressed = false;
                    if (confirmBtn.contains(vx, vy)) confirm();
                    return true;
                }
                if (cancelPressed) {
                    cancelPressed = false;
                    if (cancelBtn.contains(vx, vy)) close();
                    return true;
                }
                for (int i = 0; i < slotRects.length; i++) {
                    if (slotPressed[i]) {
                        slotPressed[i] = false;
                        if (slotRects[i].contains(vx, vy)) pickerSlot = i;
                        return true;
                    }
                }
                if (resultPressed) {
                    resultPressed = false;
                    return true;
                }
                return true;
            }

            @Override
            public boolean keyDown(int keycode) {
                if (!open) return false;
                if (keycode == Input.Keys.ESCAPE || keycode == Input.Keys.BACK) {
                    if (pickerSlot >= 0) pickerSlot = -1;
                    else                  close();
                    return true;
                }
                return false;
            }
        };
    }
}
