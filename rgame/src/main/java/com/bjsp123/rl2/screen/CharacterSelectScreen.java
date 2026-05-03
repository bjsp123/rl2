package com.bjsp123.rl2.screen;

import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.bjsp123.rl2.Rl2Game;
import com.bjsp123.rl2.model.Mob.CharacterClass;

public class CharacterSelectScreen extends MenuScreen {

    private final Rl2Game game;
    private final int slot;
    private CharacterClass selected = CharacterClass.WARRIOR;
    private Label blurb;
    private TextButton[] classButtons;

    public CharacterSelectScreen(Rl2Game game, int slot) {
        this.game = game;
        this.slot = slot;
    }

    @Override protected float minVirtualWidth()  { return 460; }
    @Override protected float minVirtualHeight() { return 760; }

    @Override
    protected void build(Table root) {
        Table panel = new Table();
        panel.pad(28).defaults().pad(6);

        panel.add(label("Choose your class", "title", 2f)).padBottom(20).row();

        CharacterClass[] classes = CharacterClass.values();
        classButtons = new TextButton[classes.length];
        for (int i = 0; i < classes.length; i++) {
            CharacterClass c = classes[i];
            TextButton b = button(c.displayName, () -> {
                selected = c;
                refreshSelection();
                blurb.setText(c.blurb);
            });
            if (c == selected) b.setChecked(true);
            classButtons[i] = b;
            panel.add(b).width(220).height(44).row();
        }

        // Blurb text varies with the selected class. setWrap + an explicit cell width
        // means the blurb area is reserved at a fixed visual size — switching classes
        // updates the text, never the layout.
        blurb = label(selected.blurb, "dim", 1f);
        blurb.setWrap(true);
        panel.add(blurb).width(340).height(80).padTop(16).padBottom(16).row();

        panel.add(button("Begin", () -> {
            if (game.currentPlay != null) {
                game.currentPlay.dispose();
                game.currentPlay = null;
            }
            game.saveSystem.clear(slot);
            game.setScreen(new PlayScreen(game, slot, selected));
        })).width(220).height(44).row();
        panel.add(button("Back", () -> game.setScreen(new SavesScreen(game))))
             .width(220).height(44);

        // Fixed-size panel — the blurb text changes with the selected class, but the
        // window dimensions stay the same so the layout doesn't jump on selection.
        Container<Table> framed = fixedPanel(panel, 420, 520);
        root.center().add(framed);
    }

    private void refreshSelection() {
        CharacterClass[] classes = CharacterClass.values();
        for (int i = 0; i < classes.length && i < classButtons.length; i++) {
            classButtons[i].setChecked(classes[i] == selected);
        }
    }

    @Override
    protected void onEscape() { game.setScreen(new SavesScreen(game)); }
}
