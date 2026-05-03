package com.bjsp123.rl2.screen;

import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.bjsp123.rl2.Rl2Game;
import com.bjsp123.rl2.save.SaveMetadata;
import com.bjsp123.rl2.save.SaveSystem;
import com.bjsp123.rl2.model.World;

/**
 * Saved-game picker. Each slot is rendered as a fixed-size card so that empty slots
 * and filled slots line up identically — switching between save states never causes
 * the layout to jitter. The outer modal panel is also fixed-size, so the screen
 * doesn't resize as slots are added or deleted.
 */
public class SavesScreen extends MenuScreen {

    /** Fixed slot card dimensions. Empty + filled cards always render at this size. */
    private static final float SLOT_CARD_W = 340;
    private static final float SLOT_CARD_H = 96;

    private final Rl2Game game;

    public SavesScreen(Rl2Game game) { this.game = game; }

    @Override protected float minVirtualWidth()  { return 380; }
    @Override protected float minVirtualHeight() { return 700; }

    @Override
    protected void build(Table root) {
        Table panel = new Table();
        panel.pad(16).defaults().pad(4);

        panel.add(label("Saved Games", "title", 1.6f)).padBottom(12).row();

        for (int i = 0; i < SaveSystem.SLOTS; i++) {
            panel.add(buildSlotCard(i))
                 .size(SLOT_CARD_W, SLOT_CARD_H)
                 .padBottom(8).row();
        }

        panel.add(button("Back", () -> game.setScreen(new TitleScreen(game))))
             .width(220).height(38).padTop(12);

        // Fixed outer panel — does not resize when saves are added or deleted.
        Container<Table> framed = fixedPanel(panel, 380, 660);
        root.center().add(framed);
    }

    /**
     * One slot rendered as a vertical card with a fixed footprint. The card always
     * has the same structure — header line, two metadata lines (placeholders for
     * empty slots), and an action row — so the visible card height is identical
     * whether the slot has a save in it or not.
     */
    private Table buildSlotCard(int slot) {
        SaveMetadata m  = game.saveSystem.metadata(slot);
        boolean filled  = game.saveSystem.exists(slot);

        Table card = new Table();
        card.setBackground(skin.getDrawable("simple-panel"));
        card.pad(8).defaults().left().pad(2);

        card.add(label("Slot " + (slot + 1), "title", 1.0f))
            .left().padBottom(2).row();

        // Always render two metadata lines so empty + filled slots have identical
        // height. Empty slots use a placeholder line so the structure stays the same.
        Table meta = new Table();
        meta.defaults().left();
        if (m == null) {
            meta.add(label("(empty)", "dim", 1f)).left().row();
            meta.add(label(" ", "dim", 1f)).left();
        } else {
            meta.add(label(com.bjsp123.rl2.util.Fmt.of("%s   Lvl %d   Depth %d",
                            m.charClass, m.characterLevel, m.depth),
                          "default", 1f)).left().row();
            meta.add(label(com.bjsp123.rl2.util.Fmt.of("HP %d/%d   Score %d",
                            m.hp, m.maxHp, m.score),
                          "default", 1f)).left();
        }
        card.add(meta).left().expandX().padBottom(4).row();

        // Action row — button shapes differ between filled vs empty, but the row
        // height is constant. Cell sizes pinned so layout doesn't reflow on swap.
        Table btns = new Table();
        btns.defaults().pad(2);
        if (filled) {
            btns.add(button("Resume", () -> resume(slot))).width(100).height(28);
            btns.add(button("Delete", () -> deleteSlot(slot))).width(100).height(28);
        } else {
            btns.add(button("New Game", () -> newGame(slot))).width(140).height(28);
        }
        card.add(btns).left();
        return card;
    }

    private void resume(int slot) {
        if (game.currentPlay != null && game.currentPlay.saveSlot == slot) {
            game.setScreen(game.currentPlay);
            return;
        }
        if (game.currentPlay != null) { game.currentPlay.dispose(); game.currentPlay = null; }
        World loaded = game.saveSystem.load(slot);
        if (loaded == null) return;
        game.setScreen(new PlayScreen(game, slot, loaded));
    }

    private void newGame(int slot) {
        discardCurrentPlayIfSlot(slot);
        game.saveSystem.clear(slot);
        game.setScreen(new CharacterSelectScreen(game, slot));
    }

    private void deleteSlot(int slot) {
        discardCurrentPlayIfSlot(slot);
        game.saveSystem.clear(slot);
        rebuild();
    }

    private void discardCurrentPlayIfSlot(int slot) {
        if (game.currentPlay != null && game.currentPlay.saveSlot == slot) {
            game.currentPlay.dispose();
            game.currentPlay = null;
        }
    }

    @Override
    protected void onEscape() { game.setScreen(new TitleScreen(game)); }
}
