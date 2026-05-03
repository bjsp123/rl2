package com.bjsp123.rl2.screen;

import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.bjsp123.rl2.Rl2Game;
import com.bjsp123.rl2.model.HallOfFameEntry;

public class GameOverScreen extends MenuScreen {

    private final Rl2Game game;
    private final HallOfFameEntry record;

    public GameOverScreen(Rl2Game game, HallOfFameEntry record) {
        this.game   = game;
        this.record = record;
    }

    @Override protected float minVirtualWidth()  { return 660; }
    @Override protected float minVirtualHeight() { return 580; }

    @Override
    protected void build(Table root) {
        Table panel = new Table();
        panel.pad(28).defaults().pad(4);

        panel.add(label("You died.", "warn", 3f)).padBottom(12).row();
        panel.add(label(record.charClass + " — score " + record.score + ", depth " + record.depth,
                "default", 1.4f)).padBottom(28).row();

        Table eq = new Table();
        eq.defaults().left();
        eq.add(label("Equipment:", "dim", 1f)).row();
        if (record.equipment.isEmpty()) {
            eq.add(label("  (none)", "dim", 1f)).row();
        } else {
            for (String s : record.equipment) {
                eq.add(label("  " + s, "default", 1f)).row();
            }
        }
        ScrollPane scroll = new ScrollPane(eq);
        scroll.setFadeScrollBars(false);
        scroll.setScrollingDisabled(true, false);
        panel.add(scroll).expand().fill().row();

        panel.add(button("Return to Title", () -> game.setScreen(new TitleScreen(game))))
             .width(240).height(44).padTop(28);

        // Fixed panel — equipment count varies but the panel size doesn't; the
        // ScrollPane handles overflow when there's a lot of equipment.
        Container<Table> framed = fixedPanel(panel, 620, 540);
        root.center().add(framed).expand().fill().pad(20);
    }

    @Override
    protected void onEscape() { game.setScreen(new TitleScreen(game)); }
}
