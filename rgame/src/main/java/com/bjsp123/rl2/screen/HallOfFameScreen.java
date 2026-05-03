package com.bjsp123.rl2.screen;

import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.bjsp123.rl2.Rl2Game;
import com.bjsp123.rl2.model.HallOfFameEntry;

import java.util.List;

public class HallOfFameScreen extends MenuScreen {

    private final Rl2Game game;

    public HallOfFameScreen(Rl2Game game) { this.game = game; }

    @Override protected float minVirtualWidth()  { return 820; }
    @Override protected float minVirtualHeight() { return 680; }

    @Override
    protected void build(Table root) {
        Table panel = new Table();
        panel.pad(24);

        panel.add(label("Hall of Fame", "title", 2.2f)).padBottom(20).row();

        Table list = new Table();
        list.defaults().left().pad(2);
        List<HallOfFameEntry> entries = game.hallOfFame.entries();
        if (entries.isEmpty()) {
            list.add(label("No entries yet — die first.", "dim", 1f));
        } else {
            list.add(label(com.bjsp123.rl2.util.Fmt.of("%-4s %-10s %-7s %-7s  %s",
                    "#", "Class", "Score", "Depth", "Equipment"), "dim", 1f)).row();
            for (int i = 0; i < entries.size(); i++) {
                HallOfFameEntry e = entries.get(i);
                String eq = String.join(", ", e.equipment);
                if (eq.length() > 60) eq = eq.substring(0, 57) + "...";
                list.add(label(com.bjsp123.rl2.util.Fmt.of("%-4d %-10s %-7d %-7d  %s",
                        i + 1, e.charClass, e.score, e.depth, eq), "default", 1f)).row();
            }
        }
        ScrollPane scroll = new ScrollPane(list);
        scroll.setFadeScrollBars(false);
        scroll.setScrollingDisabled(true, false);
        panel.add(scroll).expand().fill().row();

        panel.add(button("Back", () -> game.setScreen(new TitleScreen(game))))
             .width(220).height(40).padTop(24);

        // Fixed-size panel — entry count varies, but the visible window does not. The
        // ScrollPane handles overflow when there are more entries than fit.
        Container<Table> framed = fixedPanel(panel, 780, 600);
        root.center().add(framed).expand().fill().pad(20);
    }

    @Override
    protected void onEscape() { game.setScreen(new TitleScreen(game)); }
}
