package com.bjsp123.rl2.screen;

import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Stack;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.bjsp123.rl2.Rl2Game;
import com.bjsp123.rl2.ui.skin.AnimationSpeed;
import com.bjsp123.rl2.ui.skin.MobOutline;
import com.bjsp123.rl2.ui.skin.UiPixelScale;
import com.bjsp123.rl2.ui.skin.UiScale;
import com.bjsp123.rl2.ui.skin.UiStyleChoice;

/**
 * Settings screen — fixed-size modal with a tab strip whose active tab visually
 * joins the content panel below it (manila-folder pattern, matching the inventory
 * backpack section). Switching tabs never resizes the modal: the content area is
 * a Stack with a fixed area allocated by the outer fixed-panel layout.
 */
public class SettingsScreen extends MenuScreen {

    private enum Tab { GAMEPLAY, SOUND, GRAPHICS }

    private static Tab currentTab = Tab.GRAPHICS;

    /** Tab strip height — same as the inventory tabs so the visual rhythm matches. */
    private static final int TAB_H = 26;
    /** Outer panel size. Fixed so adding/removing settings options doesn't resize the
     *  modal. Bounded by {@link MenuScreen#effectiveScale} which scales the viewport
     *  down on small screens so this never bleeds off-edge. */
    private static final float PANEL_W = 380;
    private static final float PANEL_H = 460;

    private final Rl2Game game;
    private final Runnable onBack;

    public SettingsScreen(Rl2Game game) {
        this(game, () -> game.setScreen(new TitleScreen(game)));
    }

    public SettingsScreen(Rl2Game game, Runnable onBack) {
        this.game = game;
        this.onBack = onBack;
    }

    @Override protected float minVirtualWidth()  { return 380; }
    @Override protected float minVirtualHeight() { return 480; }

    @Override
    protected void build(Table root) {
        Table panel = new Table();
        panel.pad(12).defaults().left();

        panel.add(label("Settings", "title", 1.6f)).left().padBottom(8).row();

        // Tab section — Stack with the content panel chrome behind, the tab strip
        // overlapping its top border (manila-folder pattern). Tabs are square-bottomed
        // and use the "tab" style; the active tab fill matches the panel interior so
        // the selected tab visually flows into the content area.
        Stack tabSection = new Stack();

        Image tabSectionBg = new Image(skin.getDrawable("panel"));
        tabSectionBg.setScaling(com.badlogic.gdx.utils.Scaling.stretch);
        Table bgLayer = new Table();
        bgLayer.top();
        bgLayer.add().height(TAB_H - 2).row();
        bgLayer.add(tabSectionBg).expand().fill();
        tabSection.add(bgLayer);

        Table contentLayer = new Table();
        contentLayer.top();

        Table tabBar = new Table();
        tabBar.defaults().pad(0);
        for (Tab t : Tab.values()) {
            final Tab tab = t;
            TextButton b = new TextButton(label(t), skin, "tab");
            b.addListener(new ClickListener() {
                @Override public void clicked(InputEvent event, float x, float y) {
                    currentTab = tab;
                    rebuild();
                }
            });
            if (t == currentTab) b.setChecked(true);
            tabBar.add(b).width(96).height(TAB_H);
        }
        contentLayer.add(tabBar).left().fillX().height(TAB_H).row();

        // Tab content area — fills whatever's left of the Stack after the tab strip.
        // Uses expand+fill so an empty "(no settings yet)" tab doesn't shrink the
        // section — the surrounding fixed panel keeps the size deterministic.
        Table tabContent = new Table();
        tabContent.pad(10).defaults().left();
        switch (currentTab) {
            case GRAPHICS -> graphicsContent(tabContent);
            case GAMEPLAY -> gameplayContent(tabContent);
            case SOUND ->
                tabContent.add(label("(no settings yet)", "dim", 1f))
                          .left().padTop(24).padBottom(24).row();
        }
        contentLayer.add(tabContent).expand().fill();
        tabSection.add(contentLayer);

        panel.add(tabSection).fillX().expand().fill().padBottom(10).row();

        panel.add(button("Back", onBack)).left().width(120).height(34);

        // Fixed-size outer panel — switching tabs does not resize.
        Container<Table> framed = fixedPanel(panel, PANEL_W, PANEL_H);
        root.center().add(framed);
    }

    private void graphicsContent(Table panel) {
        addLabeledRow(panel, "UI Scale", buildUiScaleRow());
        addLabeledRow(panel, "UI Pixel Scale", buildUiPixelScaleRow());
        addLabeledRow(panel, "UI Style", buildUiStyleRow());
        addLabeledRow(panel, "Mob Outline Width", buildOutlineWidthRow());
        addLabeledRow(panel, "Mob Outline Darkness", buildOutlineDarknessRow());
        addLabeledRow(panel, "Mob Outline Smoothing", buildOutlineSmoothRow());
    }

    private void gameplayContent(Table panel) {
        addLabeledRow(panel, "Animation Speed", buildAnimationSpeedRow());
    }

    private Table buildAnimationSpeedRow() {
        Table row = new Table();
        for (int n : AnimationSpeed.CHOICES) {
            final int chosen = n;
            TextButton b = button(n + "x", () -> {
                AnimationSpeed.setFramesPerRender(chosen);
                game.setScreen(new SettingsScreen(game, onBack));
            });
            if (AnimationSpeed.framesPerRender() == n) b.setChecked(true);
            row.add(b).width(54).height(28).pad(2);
        }
        return row;
    }

    /** Stack a section label above its button row so each setting reads top-to-bottom
     *  rather than label-on-the-left. Lets the panel collapse to a narrow column. */
    private void addLabeledRow(Table panel, String labelText, Table row) {
        panel.add(label(labelText, "dim", 1f)).left().padTop(6).row();
        panel.add(row).left().padBottom(2).row();
    }

    private Table buildUiScaleRow() {
        Table row = new Table();
        for (float s : UiScale.CHOICES) {
            final float chosen = s;
            TextButton b = button(s + "x", () -> {
                UiScale.set(chosen);
                game.setScreen(new SettingsScreen(game, onBack));
            });
            if (Math.abs(UiScale.scale() - s) < 0.001f) b.setChecked(true);
            row.add(b).width(54).height(28).pad(2);
        }
        return row;
    }

    private Table buildUiPixelScaleRow() {
        Table row = new Table();
        for (int s : UiPixelScale.CHOICES) {
            final int chosen = s;
            TextButton b = button(s + "x", () -> {
                UiPixelScale.set(chosen);
                game.setScreen(new SettingsScreen(game, onBack));
            });
            if (UiPixelScale.scale() == s) b.setChecked(true);
            row.add(b).width(54).height(28).pad(2);
        }
        return row;
    }

    private Table buildUiStyleRow() {
        Table row = new Table();
        for (UiStyleChoice.Mode m : UiStyleChoice.Mode.values()) {
            TextButton b = button(m.displayName, () -> {
                UiStyleChoice.set(m);
                game.setScreen(new SettingsScreen(game, onBack));
            });
            if (UiStyleChoice.mode() == m) b.setChecked(true);
            row.add(b).width(110).height(28).pad(2);
        }
        return row;
    }

    private Table buildOutlineWidthRow() {
        Table row = new Table();
        for (float w : MobOutline.WIDTH_CHOICES) {
            final float chosen = w;
            TextButton b = button(formatNumber(w), () -> {
                MobOutline.setWidth(chosen);
                game.setScreen(new SettingsScreen(game, onBack));
            });
            if (Math.abs(MobOutline.width() - w) < 0.0001f) b.setChecked(true);
            row.add(b).width(44).height(28).pad(2);
        }
        return row;
    }

    private Table buildOutlineDarknessRow() {
        Table row = new Table();
        for (float a : MobOutline.DARKNESS_CHOICES) {
            final float chosen = a;
            TextButton b = button(formatNumber(a), () -> {
                MobOutline.setDarkness(chosen);
                game.setScreen(new SettingsScreen(game, onBack));
            });
            if (Math.abs(MobOutline.darkness() - a) < 0.0001f) b.setChecked(true);
            row.add(b).width(44).height(28).pad(2);
        }
        return row;
    }

    private Table buildOutlineSmoothRow() {
        Table row = new Table();
        // Two-button toggle: "Smooth" (Linear-filter outline taps) vs "Pixel"
        // (Nearest filter, the original pixel-aligned look).
        TextButton smooth = button("Smooth", () -> {
            MobOutline.setSmooth(true);
            game.setScreen(new SettingsScreen(game, onBack));
        });
        TextButton aliased = button("Pixel", () -> {
            MobOutline.setSmooth(false);
            game.setScreen(new SettingsScreen(game, onBack));
        });
        if (MobOutline.smooth())  smooth.setChecked(true);
        else                      aliased.setChecked(true);
        row.add(smooth).width(80).height(28).pad(2);
        row.add(aliased).width(80).height(28).pad(2);
        return row;
    }

    /** Compact label for a float setting choice — drops trailing zeros so 1.0 reads
     *  as "1" and 0.55 stays as "0.55". */
    private static String formatNumber(float v) {
        if (v == (int) v) return Integer.toString((int) v);
        // Strip trailing zeros from the default %f formatting.
        String s = String.format(java.util.Locale.ROOT, "%.2f", v);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String label(Tab t) {
        return switch (t) {
            case GAMEPLAY -> "Gameplay";
            case SOUND    -> "Sound";
            case GRAPHICS -> "Graphics";
        };
    }

    @Override
    protected void onEscape() { onBack.run(); }
}
