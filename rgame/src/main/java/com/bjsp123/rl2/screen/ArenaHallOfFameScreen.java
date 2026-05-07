package com.bjsp123.rl2.screen;

import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Stack;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.bjsp123.rl2.Rl2Game;
import com.bjsp123.rl2.save.ArenaHallOfFameEntry;
import com.bjsp123.rl2.util.Fmt;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Read-only viewer for the arena hall-of-fame ledger. Mirrors
 * {@link HallOfFameScreen}'s layout — fixed-size centred panel, ScrollPane
 * for overflow, Back button to the title.
 */
public class ArenaHallOfFameScreen extends MenuScreen {

    private static final SimpleDateFormat TS_FMT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT);

    private final Rl2Game game;

    public ArenaHallOfFameScreen(Rl2Game game) { this.game = game; }

    @Override protected float minVirtualWidth()  { return 420; }
    @Override protected float minVirtualHeight() { return 720; }

    @Override
    protected void build(Table root) {
        Table panel = new Table();
        panel.pad(24);

        panel.add(label("Arena Hall of Fame", "title", 2.0f)).padBottom(20).row();

        Table list = new Table();
        list.defaults().left().pad(2);
        List<ArenaHallOfFameEntry> entries = game.arenaHallOfFame.entries;
        if (entries.isEmpty()) {
            list.add(label("No matchups recorded yet.", "dim", 1.6f));
        } else {
            list.add(label(Fmt.of("%-16s  %-22s  %-22s  %s",
                    "When", "Team A", "Team B", "Survivors"), "dim", 1.6f)).row();
            for (ArenaHallOfFameEntry e : entries) {
                String when = TS_FMT.format(new Date(e.timestampMillis));
                String result = "A: " + e.teamASurvivors + "  B: " + e.teamBSurvivors;
                String winnerStyle = e.winner == 1 ? "default"
                                   : e.winner == 2 ? "default"
                                   : "dim";
                list.add(label(Fmt.of("%-16s  %-22s  %-22s  %s",
                        when, e.teamADescription, e.teamBDescription, result),
                        winnerStyle, 1.6f)).row();
            }
        }
        ScrollPane scroll = new ScrollPane(list);
        scroll.setFadeScrollBars(false);
        scroll.setScrollingDisabled(true, false);
        panel.add(new com.bjsp123.rl2.ui.skin.ScrollHinted(scroll, skin))
                .expand().fill().row();

        Stack framed = framedWithBack(panel, 380, 660,
                () -> game.setScreen(new ArenaSetupScreen(game)));
        root.center().add(framed).expand().fill().pad(20);
    }

    @Override
    protected void onEscape() { game.setScreen(new ArenaSetupScreen(game)); }
}
