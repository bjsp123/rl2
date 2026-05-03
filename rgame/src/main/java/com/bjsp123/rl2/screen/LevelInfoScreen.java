package com.bjsp123.rl2.screen;

import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Level.LevelFlag;
import com.bjsp123.rl2.model.Tile;

/**
 * Full-screen fact sheet for the level the player is currently on. Shows depth,
 * layout/theme, generation flags, and a tile / mob / item census so players curious
 * about why a level "feels" a certain way can see exactly what was rolled for it.
 *
 * <p>Wired to the burger-menu "Level Info" item in {@link com.bjsp123.rl2.ui.hud.HudRenderer}.
 * The encyclopaedia (Items / Creatures / Buffs / Gems / Perks / Terrain) is now a
 * separate in-game popup; see {@code EncyclopediaRenderer}.
 */
public class LevelInfoScreen extends MenuScreen {

    private final Runnable onBack;
    private final Level currentLevel;

    public LevelInfoScreen(Runnable onBack, Level currentLevel) {
        this.onBack = onBack;
        this.currentLevel = currentLevel;
    }

    @Override protected float minVirtualWidth()  { return 480; }
    @Override protected float minVirtualHeight() { return 640; }

    @Override
    protected void build(Table root) {
        Table panel = new Table();
        panel.pad(24);

        panel.add(label("Level Info", "title", 1.8f)).padBottom(16).row();

        Table list = new Table();
        list.defaults().left().pad(2);

        if (currentLevel != null) {
            appendCurrentLevelSection(list);
        } else {
            list.add(label("(no level loaded)", "dim", 1f)).row();
        }

        ScrollPane scroll = new ScrollPane(list);
        scroll.setFadeScrollBars(false);
        scroll.setScrollingDisabled(true, false);
        panel.add(scroll).expand().fill().row();

        panel.add(button("Back", onBack)).width(180).height(36).padTop(20);

        Container<Table> framed = fixedPanel(panel, 460, 620);
        root.center().add(framed).expand().fill().pad(20);
    }

    private void appendCurrentLevelSection(Table list) {
        Level lvl = currentLevel;
        list.add(label("Current Level", "title", 1.1f)).padTop(4).padBottom(4).row();
        list.add(label("  Depth     " + lvl.depth, "default", 1f)).row();
        list.add(label("  Size      " + lvl.width + "×" + lvl.height, "default", 1f)).row();
        list.add(label("  Layout    " + lvl.layout, "default", 1f)).row();
        list.add(label("  Theme     " + lvl.theme,  "default", 1f)).row();

        StringBuilder flags = new StringBuilder();
        if (lvl.flags != null) {
            for (LevelFlag f : lvl.flags) {
                if (flags.length() > 0) flags.append(", ");
                flags.append(f.name().toLowerCase().replace('_', ' '));
            }
        }
        list.add(label("  Flags     " + (flags.length() == 0 ? "none" : flags),
                "default", 1f)).row();

        int floor = 0, wall = 0, chasm = 0, door = 0, lamp = 0, woodFloor = 0;
        for (int x = 0; x < lvl.width; x++) {
            for (int y = 0; y < lvl.height; y++) {
                Tile t = lvl.tiles[x][y];
                if (t == null) continue;
                switch (t) {
                    case FLOOR       -> floor++;
                    case FLOOR_WOOD  -> woodFloor++;
                    case WALL        -> wall++;
                    case CHASM       -> chasm++;
                    case DOOR, DOOR_OPEN -> door++;
                    case LAMP        -> lamp++;
                    default -> {}
                }
            }
        }
        list.add(label(com.bjsp123.rl2.util.Fmt.of(
                "  Tiles     floor %d  wood %d  wall %d  chasm %d  door %d  lamp %d",
                floor, woodFloor, wall, chasm, door, lamp), "dim", 1f)).row();

        int mobCount  = lvl.mobs  == null ? 0 : lvl.mobs.size();
        int itemCount = lvl.items == null ? 0 : lvl.items.size();
        list.add(label("  Mobs      " + mobCount,  "default", 1f)).row();
        list.add(label("  Items     " + itemCount, "default", 1f)).row();
    }

    @Override
    protected void onEscape() { onBack.run(); }
}
