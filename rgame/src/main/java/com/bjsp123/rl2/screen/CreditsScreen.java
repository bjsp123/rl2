package com.bjsp123.rl2.screen;

import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.bjsp123.rl2.Rl2Game;

public class CreditsScreen extends MenuScreen {

    private final Rl2Game game;

    public CreditsScreen(Rl2Game game) { this.game = game; }

    @Override protected float minVirtualWidth()  { return 560; }
    @Override protected float minVirtualHeight() { return 500; }

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
            panel.add(label(s, "dim", 1.1f)).row();
        }
        panel.add(button("Back", () -> game.setScreen(new TitleScreen(game))))
             .width(220).height(40).padTop(28);

        Container<Table> framed = fixedPanel(panel, 520, 460);
        root.center().add(framed);
    }

    @Override
    protected void onEscape() { game.setScreen(new TitleScreen(game)); }
}
