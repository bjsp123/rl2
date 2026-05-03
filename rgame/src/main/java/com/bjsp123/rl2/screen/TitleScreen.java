package com.bjsp123.rl2.screen;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.bjsp123.rl2.Rl2Game;

public class TitleScreen extends MenuScreen {

    private final Rl2Game game;

    public TitleScreen(Rl2Game game) { this.game = game; }

    @Override protected float minVirtualWidth()  { return 400; }
    @Override protected float minVirtualHeight() { return 620; }

    @Override
    protected void build(Table root) {
        Table panel = new Table();
        panel.pad(28).defaults().pad(6).width(220).height(44).fillX();
        panel.add(button("Saved Games",  () -> game.setScreen(new SavesScreen(game)))).row();
        panel.add(button("Hall of Fame", () -> game.setScreen(new HallOfFameScreen(game)))).row();
        panel.add(button("Settings",     () -> game.setScreen(new SettingsScreen(game)))).row();
        panel.add(button("Credits",      () -> game.setScreen(new CreditsScreen(game)))).row();
        panel.add(button("Quit",         Gdx.app::exit));

        Container<Table> framed = fixedPanel(panel, 360, 360);

        root.center();
        root.add(label("rl2", "title", 4f)).padBottom(8).row();
        root.add(label("a roguelike", "dim", 1.2f)).padBottom(28).row();
        root.add(framed);
    }
}
