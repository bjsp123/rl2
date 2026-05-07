package com.bjsp123.rl2.screen;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.bjsp123.rl2.Rl2Game;

public class TitleScreen extends MenuScreen {

    private final Rl2Game game;

    public TitleScreen(Rl2Game game) { this.game = game; }

    @Override protected float minVirtualWidth()  { return 420; }
    @Override protected float minVirtualHeight() { return 760; }

    @Override
    protected void build(Table root) {
        Table panel = new Table();
        // Each row fills ~95% of the panel's inner width and stands ~72 px
        // tall — matches the screenshot reference where the menu fills the
        // bulk of the screen and every entry is comfortably thumb-sized.
        panel.pad(20).defaults().pad(8).width(340).height(72).fillX();
        panel.add(chunkyButton("Saved Games", () -> game.setScreen(new SavesScreen(game)))).row();
        panel.add(chunkyButton("Hall of Fame", () -> game.setScreen(new HallOfFameScreen(game)))).row();
        panel.add(chunkyButton("Arena",        () -> game.setScreen(new ArenaSetupScreen(game)))).row();
        panel.add(chunkyButton("Settings",     () -> game.setScreen(new SettingsScreen(game)))).row();
        panel.add(chunkyButton("Credits",      () -> game.setScreen(new CreditsScreen(game)))).row();
        panel.add(chunkyButton("Quit",         Gdx.app::exit));

        Container<Table> framed = fixedPanel(panel, 400, 640);

        root.center();
        root.add(label("rl2", "title", 5f)).padBottom(8).row();
        root.add(label("a roguelike", "dim", 1.6f)).padBottom(28).row();
        root.add(framed);
    }
}
