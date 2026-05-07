package com.bjsp123.rl2.screen;

import com.badlogic.gdx.scenes.scene2d.ui.Stack;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.bjsp123.rl2.Rl2Game;

public class CreditsScreen extends MenuScreen {

    private final Rl2Game game;

    public CreditsScreen(Rl2Game game) { this.game = game; }

    @Override protected float minVirtualWidth()  { return 420; }
    @Override protected float minVirtualHeight() { return 660; }

    @Override
    protected void build(Table root) {
        Table panel = new Table();
        panel.pad(28).defaults().pad(4);

        panel.add(label("Credits", "title", 2.5f)).padBottom(28).row();
        String[] lines = {
            "rl2",
            "",
            "Built with libgdx",
            "Code by hwacha",
            "",
            "Thanks for playing.",
        };
        for (String s : lines) {
            panel.add(label(s, "dim", 1.6f)).row();
        }

        Stack framed = framedWithBack(panel, 380, 600,
                () -> game.setScreen(new TitleScreen(game)));
        root.center().add(framed);
    }

    @Override
    protected void onEscape() { game.setScreen(new TitleScreen(game)); }
}
