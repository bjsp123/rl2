package com.bjsp123.rl2.screen;

import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.bjsp123.rl2.Rl2Game;
import com.bjsp123.rl2.logic.MobFactory;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;

import java.util.ArrayList;
import java.util.List;

/**
 * Modal team-picker for arena mode. Two columns, each picks the team's
 * species (any non-PLAYER {@link Mob.MobType} OR one of the three player
 * classes), level, and count. Start Fight launches an {@link ArenaScreen}.
 *
 * <p>Rendered via the same {@link MenuScreen} chrome the settings screen
 * uses — fixed-size centred panel reads as a "popup over the title".
 */
public class ArenaSetupScreen extends MenuScreen {

    /** Tagged team-type. Either a species ({@link #charClass} == null,
     *  {@link #mobType} set to a registry key) or a player class
     *  ({@link #charClass} set, {@link #mobType} ignored). */
    public static final class TeamType {
        public final String mobType;
        public final Mob.CharacterClass charClass;
        public final String label;

        public TeamType(String mobType, Mob.CharacterClass charClass, String label) {
            this.mobType = mobType;
            this.charClass = charClass;
            this.label = label;
        }
    }

    public static final class TeamSpec {
        public final TeamType type;
        public final int level;
        public final int count;

        public TeamSpec(TeamType type, int level, int count) {
            this.type = type;
            this.level = level;
            this.count = count;
        }
    }

    private static final int[] LEVEL_CHOICES = { 1, 5, 10, 15 };
    private static final int[] COUNT_CHOICES = { 1, 3, 5, 8 };
    private static final float PANEL_W = 540;
    private static final float PANEL_H = 660;

    /** Lazily initialised — population reads from {@link com.bjsp123.rl2.logic.MobRegistry}
     *  which isn't loaded yet at static-init time. */
    private static List<TeamType> typeChoices;

    private static List<TeamType> typeChoices() {
        if (typeChoices == null) {
            List<TeamType> out = new ArrayList<>();
            // Player classes first. mobType is unused for these entries — the
            // spawn path routes through MobFactory.player(charClass).
            out.add(new TeamType(null, Mob.CharacterClass.WARRIOR, "warrior"));
            out.add(new TeamType(null, Mob.CharacterClass.ROGUE,   "rogue"));
            out.add(new TeamType(null, Mob.CharacterClass.MAGE,    "mage"));
            // Every species in the registry. Names lifted from a freshly-spawned
            // template so the label matches what the look panel shows.
            Point dummy = new Point(0, 0);
            for (String t : com.bjsp123.rl2.logic.MobRegistry.knownTypes()) {
                Mob template = MobFactory.spawn(t, dummy);
                String label = template != null && template.name != null
                        ? template.name : t.toLowerCase();
                out.add(new TeamType(t, null, label));
            }
            typeChoices = out;
        }
        return typeChoices;
    }

    private final Rl2Game game;

    // Persisted across rebuilds so prev/next don't reset on click.
    private static int teamAIdx   = 0;            // warrior
    private static int teamBIdx   = 3;            // first non-player species (spider)
    private static int teamALevel = 5;
    private static int teamBLevel = 5;
    private static int teamACount = 3;
    private static int teamBCount = 3;

    public ArenaSetupScreen(Rl2Game game) {
        this.game = game;
    }

    @Override protected float minVirtualWidth()  { return PANEL_W + 40; }
    @Override protected float minVirtualHeight() { return PANEL_H + 40; }

    @Override
    protected void build(Table root) {
        Table panel = new Table();
        panel.pad(16).defaults().left();
        panel.add(label("Arena setup", "title", 1.4f)).colspan(3).padBottom(10).row();

        // Two team columns side-by-side, separated by a "vs" label.
        Table teamA = buildTeamColumn(true);
        Table teamB = buildTeamColumn(false);
        Table vs    = new Table();
        vs.add(label("vs", "title", 1.6f)).pad(6);

        panel.add(teamA).top().pad(4);
        panel.add(vs).center().pad(4);
        panel.add(teamB).top().pad(4).row();

        // Bottom column — Start Fight + Hall of Fame as full-width chunky
        // buttons stacked vertically. Avoids the side-by-side overflow on
        // narrow viewports and matches the "vertical column of large
        // buttons" pattern used by the title and settings screens.
        Table bottom = new Table();
        bottom.defaults().pad(6).fillX();
        bottom.add(chunkyButton("Start Fight", () -> {
            TeamSpec a = currentSpec(true);
            TeamSpec b = currentSpec(false);
            game.setScreen(new ArenaScreen(game, a, b));
        })).width(360).height(64).row();
        bottom.add(chunkyButton("Hall of Fame",
                        () -> game.setScreen(new ArenaHallOfFameScreen(game))))
                .width(360).height(64);
        panel.add(bottom).colspan(3).padTop(14).center().row();

        // Back button overlaid by framedWithBack — 12 px from the BR corner.
        com.badlogic.gdx.scenes.scene2d.ui.Stack framed =
                framedWithBack(panel, PANEL_W, PANEL_H,
                        () -> game.setScreen(new TitleScreen(game)));
        root.center().add(framed);
    }

    private Table buildTeamColumn(boolean isA) {
        Table col = new Table();
        col.defaults().left().pad(2);
        col.add(label(isA ? "Team A" : "Team B", "title", 1.1f)).padBottom(4).row();

        // Type selector — prev / current-label / next.
        TeamType currentType = typeChoices().get(isA ? teamAIdx : teamBIdx);
        Table typeRow = new Table();
        typeRow.add(button("<", () -> shiftType(isA, -1))).width(48).height(40).pad(2);
        typeRow.add(label(currentType.label, "default", 1.2f)).width(150).center().pad(4);
        typeRow.add(button(">", () -> shiftType(isA, +1))).width(48).height(40).pad(2);
        col.add(label("Type", "dim", 1f)).left().padTop(4).row();
        col.add(typeRow).left().row();

        col.add(label("Level", "dim", 1f)).left().padTop(6).row();
        col.add(buildIntChooser(LEVEL_CHOICES,
                isA ? teamALevel : teamBLevel,
                v -> { if (isA) teamALevel = v; else teamBLevel = v;
                       game.setScreen(new ArenaSetupScreen(game)); })).left().row();

        col.add(label("Count", "dim", 1f)).left().padTop(6).row();
        col.add(buildIntChooser(COUNT_CHOICES,
                isA ? teamACount : teamBCount,
                v -> { if (isA) teamACount = v; else teamBCount = v;
                       game.setScreen(new ArenaSetupScreen(game)); })).left().row();
        return col;
    }

    private Table buildIntChooser(int[] choices, int currentValue,
                                  java.util.function.IntConsumer onPick) {
        Table row = new Table();
        for (int v : choices) {
            final int chosen = v;
            TextButton b = button(Integer.toString(v), () -> onPick.accept(chosen));
            if (v == currentValue) b.setChecked(true);
            row.add(b).width(60).height(40).pad(2);
        }
        return row;
    }

    private void shiftType(boolean isA, int delta) {
        int n = typeChoices().size();
        if (isA) teamAIdx = ((teamAIdx + delta) % n + n) % n;
        else     teamBIdx = ((teamBIdx + delta) % n + n) % n;
        game.setScreen(new ArenaSetupScreen(game));
    }

    private TeamSpec currentSpec(boolean isA) {
        return new TeamSpec(
                typeChoices().get(isA ? teamAIdx : teamBIdx),
                isA ? teamALevel : teamBLevel,
                isA ? teamACount : teamBCount);
    }
}
