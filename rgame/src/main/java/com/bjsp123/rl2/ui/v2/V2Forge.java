package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.bjsp123.rl2.Rl2Game;
import com.bjsp123.rl2.ui.ItemLore;
import com.bjsp123.rl2.ui.v2.stage.V2PopupActor;
import com.bjsp123.rl2.logic.EventLog;
import com.bjsp123.rl2.logic.GemRecipe;
import com.bjsp123.rl2.logic.ItemFactory;
import com.bjsp123.rl2.logic.ItemNames;
import com.bjsp123.rl2.logic.RecipeSystem;
import com.bjsp123.rl2.logic.Registries;
import com.bjsp123.rl2.logic.TurnSystem;
import com.bjsp123.rl2.model.GemSpecies;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.LogEvent;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.world.render.GemSprites;
import com.bjsp123.rl2.world.render.ItemSprites;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Gem hearth / forge screen (RL-52). Lists every gem recipe as a wordless
 * equation row <b>X + Y + Z &rarr; Q</b>: the inputs are either a specific gem's
 * picture (a {@code NAMED} keystone) or a tinted silhouette for the
 * {@code any} / {@code metal-or-exotic} / {@code exotic} constraints, and the
 * result Q is the crafted item's picture plus its name.
 *
 * <p>Tapping a gem chip in the top strip filters the list to recipes that gem
 * can contribute to (RL-52 "placing a gem filters the list"). Rows the player
 * can't currently afford render greyed-but-legible; tapping an affordable row
 * consumes the gems and builds the item into the bag.
 *
 */
public final class V2Forge extends V2Screen {

    private final Rl2Game game;
    private final Runnable onBack;

    private final Rect window = new Rect();
    /** Shared scroll component (clip + scrollbar + input) for the recipe list. */
    private final ScrollBand band = new ScrollBand();

    private static final float ROW_H = 46f;
    private static final float ROW_GAP = 4f;

    /** One built sample output per recipe (for icon + name), recipe order. */
    private final List<Row> allRows = new ArrayList<>();
    /** Filter chips - one per distinct raw-gem species in the bag. */
    private final List<Chip> chips = new ArrayList<>();
    /** Species the player has "placed" (toggled) to filter the list. */
    private final Set<GemSpecies> placed = new LinkedHashSet<>();

    /** Per-frame visible rows (filtered) with live rects + affordability. */
    private final List<Row> visible = new ArrayList<>();

    private int pressedRow = -1;
    private int pressedChip = -1;

    /** Pinned "any item -> gems" recipe at the top of the list. Tapping it opens
     *  the inventory as a picker (wired via {@link #onRecycle}) to choose the
     *  item to break down into gems. */
    private final Rect recycleRow = new Rect();
    private boolean recyclePressed;
    /** Opens the inventory item-picker for recycling; set by the caller. */
    private final Runnable onRecycle;
    /** Representative gems drawn on the recycle row's output side. */
    private static final GemSpecies[] RECYCLE_GEMS = {
            GemSpecies.LETTUSTONE, GemSpecies.GOLD, GemSpecies.BLOODHIVE };

    /** Preview/confirm popup shown before a recipe is actually forged, so the
     *  player can read what the scroll does first. */
    private final ConfirmPopup preview;
    private final V2PopupActor previewActor;

    public V2Forge(Rl2Game game, UiCtx ctx, Runnable onBack, Runnable onRecycle) {
        super(ctx);
        this.game = game;
        this.onBack = onBack;
        this.onRecycle = onRecycle;
        this.preview = new ConfirmPopup(ctx);
        this.previewActor = new V2PopupActor(preview);
    }

    @Override
    public void show() {
        super.show();
        ctx.v2Stage.remove(previewActor);
        ctx.v2Stage.addToSubPopup(previewActor);
        Gdx.input.setInputProcessor(new InputMultiplexer(preview.input(), baseInput()));
    }

    @Override
    public void hide() {
        super.hide();
        ctx.v2Stage.remove(previewActor);
    }

    private Mob player() {
        if (game.currentPlay == null) return null;
        return TurnSystem.findPlayer(game.currentPlay.getWorld().currentLevel());
    }

    @Override
    protected Rect modalWindow() { return preview.isOpen() ? preview.window() : window; }

    @Override
    protected void onEscape() {
        if (preview.isOpen()) { preview.cancel(); return; }
        onBack.run();
    }

    @Override
    protected void buildLayout() {
        back   = new BackBtn(ctx, onBack);
        burger = makeBurger();
        addStandardBurgerItems(game);

        float vw = ctx.worldW();
        float vh = ctx.worldH();
        float winW = Math.min(380f, vw - UIVars.PAD_MODAL);
        float winH = Math.min(600f, vh - 96f);
        window.set((vw - winW) * 0.5f, (vh - winH) * 0.5f, winW, winH);

        // Build one sample output Item per recipe (icon + name source). Skip
        // recipes whose output has no items.csv row yet.
        allRows.clear();
        for (GemRecipe r : Registries.recipes()) {
            if (Registries.item(r.output) == null) continue;
            Item sample;
            try {
                sample = ItemFactory.build(r.output);
            } catch (RuntimeException ex) {
                continue;
            }
            allRows.add(new Row(r, sample));
        }
        band.scroller.resetTop();
    }

    /** Distinct raw-gem species currently in the bag, in encounter order. */
    private List<GemSpecies> bagGemSpecies(Mob p) {
        List<GemSpecies> out = new ArrayList<>();
        if (p == null || p.inventory == null) return out;
        for (Item it : p.inventory.bag) {
            if (it != null && it.isGem() && !out.contains(it.gemSpecies)) {
                out.add(it.gemSpecies);
            }
        }
        return out;
    }

    /** Recompute the chip strip + visible row rects each frame. */
    private void layout(Mob p) {
        float pad = 14f;
        float contentX = window.x + pad;
        float contentW = window.w - 2 * pad;

        // Chip strip just under the header band.
        chips.clear();
        List<GemSpecies> species = bagGemSpecies(p);
        float chipSz = 34f;
        float chipGap = 6f;
        float chipY = window.top() - headerBandH() - chipSz;
        for (int i = 0; i < species.size(); i++) {
            float cx = contentX + i * (chipSz + chipGap);
            if (cx + chipSz > contentX + contentW) break; // single row of chips
            Chip c = new Chip();
            c.species = species.get(i);
            c.rect.set(cx, chipY, chipSz, chipSz);
            chips.add(c);
        }

        // Visible (filtered) rows.
        List<Item> placedGems = new ArrayList<>();
        for (GemSpecies s : placed) placedGems.add(com.bjsp123.rl2.logic.GemSystem.createGem(s));
        List<GemRecipe> candidates = RecipeSystem.candidates(placedGems);

        visible.clear();
        for (Row row : allRows) {
            if (candidates.contains(row.recipe)) visible.add(row);
        }

        // List area below the chip strip, down to the bottom padding. The
        // "any item -> gems" recipe is pinned at the very top; the scrollable
        // band of normal recipes fills the rest.
        float listTop = chipY - 10f;
        float listBottom = window.y + pad;
        recycleRow.set(contentX, listTop - ROW_H, contentW, ROW_H);
        float bandTop = recycleRow.y - ROW_GAP;
        band.set(contentX, listBottom, contentW, Math.max(0f, bandTop - listBottom));

        band.update(listContentH());
        for (int i = 0; i < visible.size(); i++) {
            float cellTop = band.top() - i * (ROW_H + ROW_GAP) + band.scroller.scrollY();
            float cellY = cellTop - ROW_H;
            Row row = visible.get(i);
            row.rect.set(contentX, cellY, contentW, ROW_H);
            row.affordable = (p != null)
                    && RecipeSystem.canAfford(row.recipe, p.inventory);
            // Only fully-inside-the-band rows draw, so the list never spills past
            // the window onto the HUD below; the scrollbar reveals the rest.
            row.onScreen = cellTop <= band.top() && cellY >= band.bottom();
        }
    }

    /** Total height of all recipe rows stacked. */
    private float listContentH() {
        return visible.isEmpty() ? 0f : visible.size() * (ROW_H + ROW_GAP) - ROW_GAP;
    }

    @Override
    protected void drawBodyShape(UiCtx ctx) {
        Mob p = player();
        layout(p);
        ShapeRenderer s = ctx.shapes;

        // Modal dim.
        s.setColor(0f, 0f, 0f, UIVars.DIM_ALPHA);
        s.rect(0, 0, ctx.worldW(), ctx.worldH());

        Window.drawShape(ctx, window.x, window.y, window.w, window.h);

        // Chip slots - selected chips get the accent border.
        for (int i = 0; i < chips.size(); i++) {
            Rect r = chips.get(i).rect;
            boolean sel = placed.contains(chips.get(i).species);
            if (sel || i == pressedChip) {
                Edges.drawTriLine(s, r.x, r.y, r.w, r.h, UIVars.HUD_LINE_W,
                        UIVars.ACCENT, UIVars.BORDER_MID, UIVars.BORDER_INNER);
            } else {
                Edges.drawTriLine(s, r.x, r.y, r.w, r.h, UIVars.HUD_LINE_W);
            }
            s.setColor(UIVars.SLOT_BG);
            s.rect(r.x + UIVars.HUD_BORDER, r.y + UIVars.HUD_BORDER,
                    r.w - 2 * UIVars.HUD_BORDER, r.h - 2 * UIVars.HUD_BORDER);
        }

        // Pinned "any item -> gems" recycle row (accent border to mark it
        // special / always available).
        {
            Rect r = recycleRow;
            Edges.drawTriLine(s, r.x, r.y, r.w, r.h, UIVars.HUD_LINE_W,
                    UIVars.ACCENT, UIVars.BORDER_MID, UIVars.BORDER_INNER);
            s.setColor(recyclePressed ? UIVars.BTN_PRESSED_BG : UIVars.BTN_BG);
            s.rect(r.x + UIVars.HUD_BORDER, r.y + UIVars.HUD_BORDER,
                    r.w - 2 * UIVars.HUD_BORDER, r.h - 2 * UIVars.HUD_BORDER);
        }

        // Recipe rows.
        for (int i = 0; i < visible.size(); i++) {
            Row row = visible.get(i);
            if (!row.onScreen) continue;
            Rect r = row.rect;
            Edges.drawTriLine(s, r.x, r.y, r.w, r.h, UIVars.HUD_LINE_W);
            boolean pressed = i == pressedRow && row.affordable;
            // Shared vocabulary: affordable = darker active fill (pressed lighter);
            // unaffordable = flat grey so it clearly reads as disabled.
            Color bg = pressed ? UIVars.BTN_PRESSED_BG
                    : (row.affordable ? UIVars.BTN_BG : UIVars.BTN_DISABLED_BG);
            s.setColor(bg);
            s.rect(r.x + UIVars.HUD_BORDER, r.y + UIVars.HUD_BORDER,
                    r.w - 2 * UIVars.HUD_BORDER, r.h - 2 * UIVars.HUD_BORDER);
        }

        // Shared scrollbar affordance on the right edge of the list.
        band.drawScrollbar(s, listContentH());
    }

    @Override
    protected void drawBodyText(UiCtx ctx) {
        TextDraw.centre(ctx, ctx.fontHeader, UIVars.ACCENT,
                "Gem Hearth", window.cx(), window.top() - ctx.headerLineH() * 0.5f);

        drawRecycleRow(ctx);

        // Chip gem icons.
        for (Chip c : chips) {
            TextureRegion reg = GemSprites.regionFor(c.species);
            if (reg == null) continue;
            float sz = c.rect.w * 0.7f;
            ctx.batch.setColor(1f, 1f, 1f, 1f);
            ctx.batch.draw(reg, c.rect.cx() - sz * 0.5f, c.rect.cy() - sz * 0.5f, sz, sz);
        }

        for (Row row : visible) {
            if (!row.onScreen) continue;
            drawRecipeRow(ctx, row);
        }
        ctx.batch.setColor(Color.WHITE);
    }

    /** Draw the pinned recycle recipe: "any item -> [gem gem gem] ?  recycle item". */
    private void drawRecycleRow(UiCtx ctx) {
        Rect r = recycleRow;
        float icon = r.h - 14f;
        float x = r.x + 8f;
        float cy = r.cy();
        // Input: the literal text "any item".
        TextDraw.centre(ctx, ctx.fontHeader, UIVars.TEXT_BODY, "any item", x + icon * 0.5f, cy + 6f);
        x += icon;
        TextDraw.centre(ctx, ctx.fontHeader, UIVars.ACCENT, "->", x + 12f, cy + 6f);
        x += 26f;
        // Output: a small row of gems + a trailing "?".
        for (GemSpecies g : RECYCLE_GEMS) {
            TextureRegion reg = GemSprites.regionFor(g);
            if (reg != null) {
                ctx.batch.setColor(1f, 1f, 1f, 1f);
                ctx.batch.draw(reg, x, cy - icon * 0.5f, icon, icon);
            }
            x += icon + 1f;
        }
        TextDraw.centre(ctx, ctx.fontHeader, UIVars.ACCENT, "?", x + 8f, cy + 6f);
        x += 22f;
        TextDraw.left(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                TextDraw.ellipsize(ctx.fontRegular, "recycle an item", r.right() - x - 8f),
                x, cy + 6f);
    }

    /** Draw one X+Y+Z -> Q equation across the row. */
    private void drawRecipeRow(UiCtx ctx, Row row) {
        Rect r = row.rect;
        float alpha = row.affordable ? 1f : 0.45f;
        float icon = r.h - 14f;
        float x = r.x + 8f;
        float cy = r.cy();

        List<GemRecipe.Slot> slots = row.recipe.slots;
        for (int i = 0; i < slots.size(); i++) {
            drawSlotIcon(ctx, slots.get(i), x, cy - icon * 0.5f, icon, alpha);
            x += icon;
            if (i < slots.size() - 1) {
                TextDraw.centre(ctx, ctx.fontHeader, tint(UIVars.TEXT_DIM, alpha),
                        "+", x + 6f, cy + 6f);
                x += 16f;
            }
        }
        // Arrow.
        TextDraw.centre(ctx, ctx.fontHeader, tint(UIVars.ACCENT, alpha),
                "->", x + 12f, cy + 6f);
        x += 26f;

        // Output icon + name.
        TextureRegion out = ItemSprites.regionFor(row.sample);
        if (out != null) {
            ctx.batch.setColor(1f, 1f, 1f, alpha);
            ctx.batch.draw(out, x, cy - icon * 0.5f, icon, icon);
        }
        x += icon + 8f;
        String name = ItemNames.displayName(row.sample, null);
        if (name == null || name.isEmpty()) name = row.recipe.output;
        TextDraw.left(ctx, ctx.fontRegular, tint(UIVars.TEXT_BODY, alpha),
                TextDraw.ellipsize(ctx.fontRegular, name, r.right() - x - 8f),
                x, cy + 6f);
    }

    /** Draw an ingredient slot: a named gem's sprite, or a tinted silhouette
     *  for the {@code any} / {@code metal-or-exotic} / {@code exotic} kinds. */
    private void drawSlotIcon(UiCtx ctx, GemRecipe.Slot slot, float x, float y,
                              float sz, float alpha) {
        if (slot.kind == GemRecipe.SlotKind.NAMED) {
            TextureRegion reg = GemSprites.regionFor(slot.species);
            if (reg != null) {
                ctx.batch.setColor(1f, 1f, 1f, alpha);
                ctx.batch.draw(reg, x, y, sz, sz);
            }
            return;
        }
        // Silhouette - a dimmed representative gem sprite with the constraint
        // word ("any" / "metal" / "exotic") overlaid, so the player reads
        // "any gem of this kind" rather than mistaking it for a specific gem.
        GemSpecies rep;
        Color tintC;
        String label;
        switch (slot.kind) {
            case EXOTIC          -> { rep = GemSpecies.BLOODHIVE; tintC = UIVars.ACCENT; label = "exotic"; }
            case METAL_OR_EXOTIC -> { rep = GemSpecies.GOLD;      tintC = new Color(0.85f, 0.7f, 0.3f, 1f); label = "metal"; }
            default              -> { rep = GemSpecies.LETTUSTONE; tintC = UIVars.TEXT_DIM; label = "any"; }
        }
        TextureRegion reg = GemSprites.regionFor(rep);
        if (reg == null) return;
        ctx.batch.setColor(tintC.r, tintC.g, tintC.b, alpha * 0.55f);   // dimmed
        ctx.batch.draw(reg, x, y, sz, sz);
        ctx.batch.setColor(1f, 1f, 1f, 1f);
        // Word centred over the dimmed gem.
        TextDraw.centre(ctx, ctx.fontRegular, tint(UIVars.TEXT_BODY, alpha),
                label, x + sz * 0.5f, y + sz * 0.5f + 4f);
    }

    private static Color tint(Color base, float alpha) {
        return new Color(base.r, base.g, base.b, base.a * alpha);
    }

    // -- Input ----------------------------------------------------------------
    @Override
    protected boolean onTouchDownInBody(float vx, float vy) {
        band.touchDown(vx, vy);
        if (recycleRow.contains(vx, vy)) { recyclePressed = true; return true; }
        for (int i = 0; i < chips.size(); i++) {
            if (chips.get(i).rect.contains(vx, vy)) { pressedChip = i; return true; }
        }
        for (int i = 0; i < visible.size(); i++) {
            Row row = visible.get(i);
            if (row.onScreen && row.rect.contains(vx, vy)) { pressedRow = i; return true; }
        }
        return window.contains(vx, vy); // claim taps inside the window
    }

    @Override
    protected boolean onTouchDragged(float vx, float vy) {
        if (band.touchDragged(vy)) {
            pressedRow = -1;
            pressedChip = -1;
            return true;
        }
        return false;
    }

    @Override
    protected boolean onScrolled(float amountY) {
        band.scrolled(amountY);
        return true;
    }

    @Override
    protected boolean onTouchUpInBody(float vx, float vy) {
        if (recyclePressed) {
            recyclePressed = false;
            if (recycleRow.contains(vx, vy) && onRecycle != null) onRecycle.run();
            return true;
        }
        if (pressedChip >= 0) {
            int idx = pressedChip;
            pressedChip = -1;
            if (idx < chips.size() && chips.get(idx).rect.contains(vx, vy)) {
                GemSpecies sp = chips.get(idx).species;
                if (!placed.remove(sp)) placed.add(sp);
                band.scroller.resetTop();
            }
            return true;
        }
        if (pressedRow >= 0) {
            int idx = pressedRow;
            pressedRow = -1;
            if (idx < visible.size()) {
                Row row = visible.get(idx);
                if (row.onScreen && row.rect.contains(vx, vy) && row.affordable) {
                    openPreview(row);
                }
            }
            return true;
        }
        return false;
    }

    /** Show the scroll's description with a Make / Cancel choice before forging,
     *  so the player can see what they're about to create. */
    private void openPreview(Row row) {
        String name = ItemNames.displayName(row.sample, player());
        if (name == null || name.isEmpty()) name = row.recipe.output;
        String desc = ItemLore.describeFlavor(row.sample);
        if (desc == null || desc.isEmpty()) desc = "Forge this item at the gem hearth?";
        preview.configure(name, desc, "Make", null, () -> craft(row.recipe));
        preview.open();
    }

    /** Consume the recipe's gems and add the built item to the bag. */
    private void craft(GemRecipe recipe) {
        Mob p = player();
        if (p == null || p.inventory == null) return;
        if (game.currentPlay == null) return;
        if (!RecipeSystem.consume(recipe, p.inventory)) return;
        Item out = ItemFactory.build(recipe.output);
        // The forged scroll drops on the floor by the hearth, not into the bag.
        com.bjsp123.rl2.logic.ItemSystem.dropItemsNearForge(
                game.currentPlay.getWorld().currentLevel(), p, java.util.List.of(out));
        String name = ItemNames.displayName(out, p);
        if (name == null || name.isEmpty()) name = recipe.output;
        EventLog.add(new LogEvent("You forge the " + name
                + " at the gem hearth; it lands at your feet.",
                LogEvent.EventPriority.HIGH, true));
        if (sounds != null) sounds.play("sfx.ui.click");
    }

    private static final class Row {
        final GemRecipe recipe;
        final Item sample;
        final Rect rect = new Rect();
        boolean affordable;
        boolean onScreen;
        Row(GemRecipe recipe, Item sample) { this.recipe = recipe; this.sample = sample; }
    }

    private static final class Chip {
        GemSpecies species;
        final Rect rect = new Rect();
    }
}
