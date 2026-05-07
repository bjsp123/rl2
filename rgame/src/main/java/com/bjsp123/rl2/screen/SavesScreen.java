package com.bjsp123.rl2.screen;

import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Stack;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.bjsp123.rl2.Rl2Game;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.World;
import com.bjsp123.rl2.save.SaveMetadata;
import com.bjsp123.rl2.save.SaveSystem;
import com.bjsp123.rl2.world.render.PortraitSprites;

/**
 * Saved-game picker. Each slot is a chunky panel — empty slots show "empty"
 * plus a "New Game" button; filled slots show a class portrait, level, and
 * depth, plus a Continue button and a smaller Delete button. The outer modal
 * panel is fixed-size, so the screen doesn't resize as saves are added or
 * deleted.
 */
public class SavesScreen extends MenuScreen {

    /** Fixed slot card dimensions. Empty + filled cards always render at this size. */
    private static final float SLOT_CARD_W = 340;
    private static final float SLOT_CARD_H = 140;
    /** On-screen size of the class portrait drawn on the left of a filled card. */
    private static final float PORTRAIT_SIZE = 64;

    private final Rl2Game game;

    public SavesScreen(Rl2Game game) { this.game = game; }

    @Override protected float minVirtualWidth()  { return 420; }
    @Override protected float minVirtualHeight() { return 760; }

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

        // Outer panel embeds the back-icon button automatically at a fixed
        // 12 px from the bottom-right corner via {@link #framedWithBack}.
        Stack framed = framedWithBack(panel, 400, 700,
                () -> game.setScreen(new TitleScreen(game)));
        root.center().add(framed);
    }

    /**
     * One slot rendered as a chunky card. Empty: a single big "New Game"
     * button beneath the "empty" caption. Filled: class portrait + class /
     * level / depth lines, then Continue (large) + Delete (smaller) on a
     * bottom row.
     */
    private Table buildSlotCard(int slot) {
        SaveMetadata m  = game.saveSystem.metadata(slot);
        boolean filled  = game.saveSystem.exists(slot);

        Table card = new Table();
        card.setBackground(skin.getDrawable("simple-panel"));
        card.pad(8).defaults().left().pad(2);
        card.top().left();

        if (filled) buildFilledCard(card, slot, m);
        else        buildEmptyCard(card, slot);

        return card;
    }

    private void buildFilledCard(Table card, int slot, SaveMetadata m) {
        // Top row: portrait on the left, class / level / depth on the right.
        Table topRow = new Table();
        topRow.left().top().defaults().left();

        Image portrait = new Image();
        portrait.setScaling(com.badlogic.gdx.utils.Scaling.fit);
        Mob.CharacterClass cls = parseCharacterClass(m.charClass);
        if (cls != null) {
            com.badlogic.gdx.graphics.g2d.TextureRegion region =
                    PortraitSprites.regionFor(cls);
            if (region != null) portrait.setDrawable(new TextureRegionDrawable(region));
        }
        // Light slot-bezel behind the portrait so the sprite reads against the
        // card chrome — same treatment items get in the inventory.
        Table portraitCell = new Table();
        portraitCell.setBackground(skin.getDrawable("item-slot"));
        portraitCell.add(portrait).size(PORTRAIT_SIZE, PORTRAIT_SIZE).pad(4);
        topRow.add(portraitCell).size(PORTRAIT_SIZE + 8, PORTRAIT_SIZE + 8).padRight(12);

        Table textCol = new Table();
        textCol.left().top().defaults().left();
        textCol.add(label(m.charClass + "   Lvl " + m.characterLevel,
                          "title", 1.3f)).left().row();
        textCol.add(label("Depth " + m.depth, "default", 1.2f)).left().row();
        textCol.add(label("Score " + m.score, "dim", 1.0f)).left();
        topRow.add(textCol).top().left().expandX().fillX();

        card.add(topRow).left().fillX().expandX().row();

        // Bottom row: chunky Continue button on the left, smaller Delete on
        // the right. Continue gets the bulk of the width so the primary
        // action is visually obvious.
        Table btns = new Table();
        btns.defaults().pad(4);
        TextButton resumeBtn = chunkyButton("Continue", 1.2f, () -> resume(slot));
        TextButton deleteBtn = button("Delete", () -> deleteSlot(slot));
        btns.add(resumeBtn).expandX().fillX().height(40);
        btns.add(deleteBtn).width(80).height(40);
        card.add(btns).fillX().expandX().padTop(8);
    }

    private void buildEmptyCard(Table card, int slot) {
        // "empty" caption, then a chunky New Game button filling the bottom.
        card.add(label("empty", "dim", 1.4f)).left().expandX().padTop(8).row();
        card.add().expand().fill().row();   // spacer pushes button to the bottom
        TextButton newBtn = chunkyButton("New Game", 1.2f, () -> newGame(slot));
        card.add(newBtn).fillX().expandX().height(40).padTop(4);
    }

    /** Resolve a {@link SaveMetadata#charClass} string (the displayName the
     *  save was written with) back to its enum value. Returns {@code null}
     *  when the string doesn't match any known class — falls through to the
     *  card's "no portrait" branch. */
    private static Mob.CharacterClass parseCharacterClass(String name) {
        if (name == null) return null;
        for (Mob.CharacterClass c : Mob.CharacterClass.values()) {
            if (c.displayName.equalsIgnoreCase(name) || c.name().equalsIgnoreCase(name)) {
                return c;
            }
        }
        return null;
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
