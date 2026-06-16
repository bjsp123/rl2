package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.bjsp123.rl2.Rl2Game;
import com.bjsp123.rl2.logic.EventLog;
import com.bjsp123.rl2.logic.GemRecipe;
import com.bjsp123.rl2.logic.InventorySystem;
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
 * <p>This screen is reached via a stub burger entry for now; RL-51 will place
 * an actual hearth room the player steps into.
 */
public final class V2Forge extends V2Screen {

    private final Rl2Game game;
    private final Runnable onBack;

    private final Rect window = new Rect();
    private final Rect listClip = new Rect();
    private final Scroller scroller = new Scroller();

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

    public V2Forge(Rl2Game game, UiCtx ctx, Runnable onBack) {
        super(ctx);
        this.game = game;
        this.onBack = onBack;
    }

    private Mob player() {
        if (game.currentPlay == null) return null;
        return TurnSystem.findPlayer(game.currentPlay.getWorld().currentLevel());
    }

    @Override
    protected Rect modalWindow() { return window; }

    @Override
    protected void onEscape() { onBack.run(); }

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
        scroller.resetTop();
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

        // List area below the chip strip, down to the bottom padding.
        float rowH = 46f;
        float rowGap = 4f;
        float listTop = chipY - 10f;
        float listBottom = window.y + pad;
        listClip.set(contentX, listBottom, contentW, listTop - listBottom);

        float total = visible.size() * (rowH + rowGap) - rowGap;
        scroller.setMaxScroll(total - listClip.h);

        for (int i = 0; i < visible.size(); i++) {
            float cellTop = listTop - i * (rowH + rowGap) + scroller.scrollY();
            float cellY = cellTop - rowH;
            Row row = visible.get(i);
            row.rect.set(contentX, cellY, contentW, rowH);
            row.affordable = (p != null)
                    && RecipeSystem.canAfford(row.recipe, p.inventory);
            // Only fully-inside-the-clip rows draw, so the list never spills past
            // the window onto the HUD below; the scrollbar reveals the rest.
            row.onScreen = cellTop <= listTop && cellY >= listBottom;
        }
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

        // Recipe rows.
        for (int i = 0; i < visible.size(); i++) {
            Row row = visible.get(i);
            if (!row.onScreen) continue;
            Rect r = row.rect;
            Edges.drawTriLine(s, r.x, r.y, r.w, r.h, UIVars.HUD_LINE_W);
            boolean pressed = i == pressedRow && row.affordable;
            Color bg = pressed ? UIVars.BTN_PRESSED_BG
                    : (row.affordable ? UIVars.BTN_BG : UIVars.SLOT_BG);
            s.setColor(bg);
            s.rect(r.x + UIVars.HUD_BORDER, r.y + UIVars.HUD_BORDER,
                    r.w - 2 * UIVars.HUD_BORDER, r.h - 2 * UIVars.HUD_BORDER);
        }

        // Shared scrollbar affordance on the right edge of the list.
        scroller.drawScrollbar(s, listClip);
    }

    @Override
    protected void drawBodyText(UiCtx ctx) {
        TextDraw.centre(ctx, ctx.fontHeader, UIVars.ACCENT,
                "Gem Hearth", window.cx(), window.top() - ctx.headerLineH() * 0.5f);

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
        scroller.onTouchDown(vy);
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
        if (scroller.onTouchDragged(vy)) {
            pressedRow = -1;
            pressedChip = -1;
            return true;
        }
        return false;
    }

    @Override
    protected boolean onScrolled(float amountY) {
        scroller.onScrolled(amountY);
        return true;
    }

    @Override
    protected boolean onTouchUpInBody(float vx, float vy) {
        if (pressedChip >= 0) {
            int idx = pressedChip;
            pressedChip = -1;
            if (idx < chips.size() && chips.get(idx).rect.contains(vx, vy)) {
                GemSpecies sp = chips.get(idx).species;
                if (!placed.remove(sp)) placed.add(sp);
                scroller.resetTop();
            }
            return true;
        }
        if (pressedRow >= 0) {
            int idx = pressedRow;
            pressedRow = -1;
            if (idx < visible.size()) {
                Row row = visible.get(idx);
                if (row.onScreen && row.rect.contains(vx, vy) && row.affordable) {
                    craft(row.recipe);
                }
            }
            return true;
        }
        return false;
    }

    /** Consume the recipe's gems and add the built item to the bag. */
    private void craft(GemRecipe recipe) {
        Mob p = player();
        if (p == null || p.inventory == null) return;
        if (!RecipeSystem.consume(recipe, p.inventory)) return;
        Item out = ItemFactory.build(recipe.output);
        InventorySystem.addToBag(p.inventory, out);
        String name = ItemNames.displayName(out, p);
        if (name == null || name.isEmpty()) name = recipe.output;
        EventLog.add(new LogEvent("You forge the " + name + " at the gem hearth.",
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
