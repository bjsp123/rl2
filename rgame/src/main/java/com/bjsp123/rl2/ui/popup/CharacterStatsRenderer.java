package com.bjsp123.rl2.ui.popup;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.bjsp123.rl2.logic.GameBalance;
import com.bjsp123.rl2.logic.MobProgression;
import com.bjsp123.rl2.model.HistoricalRecord;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Perk;

/**
 * Character stats frame — opens when the HUD portrait is tapped. Three tabs:
 * <ul>
 *   <li><b>Character</b> — HP / XP / level / attack / defense / melee damage / armor /
 *       perk points.</li>
 *   <li><b>Perks</b> — stub; the perk-selection flow isn't implemented yet.</li>
 *   <li><b>History</b> — scrollable lifetime log of slain creatures, level-ups, and
 *       item finds (see {@link HistoricalRecord}).</li>
 * </ul>
 *
 * <p>Built as a scene2d {@link Group} living inside the shared in-game stage, same pattern
 * as {@link InventoryRenderer}. Input swallowing when open is handled by the parent's
 * modal gating in PlayScreen.
 */
public class CharacterStatsRenderer extends Group {

    private final Skin skin;
    private final Container<Table> framed;
    private final TextButton tabCharacter, tabPerks, tabHistory;
    private final Table contentCharacter, contentPerks, contentHistory;
    private final Label characterLabel, historyLabel;

    private Mob player;
    private boolean open;
    private int activeTab;   // 0 = Character, 1 = Perks, 2 = History
    /** Set whenever the perks tab needs a rebuild (tab activated or a perk just spent).
     *  We CAN'T rebuild every frame: ClickListener fires `clicked` only when the same
     *  actor receives both touchDown and touchUp, and act()→update() runs between
     *  frames. Rebuilding `contentPerks` each frame replaces the button between the
     *  touch events, so the listener never fires. */
    private boolean perksDirty = true;

    public CharacterStatsRenderer(Skin skin) {
        this.skin = skin;

        // Root frame — Shattered's thin chrome (2-px border + 2-px chamfer) only eats
        // ~4 px of frame, so the inner inset can be modest. The "ornate panel" comment
        // is legacy from the stonebase look.
        Table frame = new Table();
        frame.pad(8);
        frame.defaults().pad(2);

        // Tab row — uses the dedicated "tab" TextButtonStyle so the active tab visually
        // merges with the body panel below it, and adjacent tabs sit flush (no gap)
        // forming a continuous tab strip rather than a row of standalone buttons.
        tabCharacter = new TextButton("Character", skin, "tab");
        tabPerks     = new TextButton("Perks",     skin, "tab");
        tabHistory   = new TextButton("History",   skin, "tab");
        wireTab(tabCharacter, 0);
        wireTab(tabPerks,     1);
        wireTab(tabHistory,   2);

        Table tabs = new Table();
        // .left() so the tab strip itself packs to the left when given more width
        // than its tabs need — without this the row centres inside the cell, which
        // looks wrong with a manila-folder tab pattern.
        tabs.left();
        tabs.defaults().pad(0);
        tabs.add(tabCharacter).minWidth(72).height(26);
        tabs.add(tabPerks)    .minWidth(72).height(26);
        tabs.add(tabHistory)  .minWidth(72).height(26);
        // tabs row added BELOW inside the bordered tab section.

        // Tab content bodies — exactly one is visible at a time. Character + Perks use plain
        // Labels; History wraps a Label in a ScrollPane so thousands of entries stay usable.
        characterLabel = new Label("", skin);
        characterLabel.setWrap(true);
        contentCharacter = new Table();
        // top().left() so the cell anchors at the top-left of the table — without
        // this, a narrow panel (where the table is smaller than the cell's preferred
        // width) would centre the cell, spilling text equally past both panel edges.
        contentCharacter.top().left();
        // Cell expands + fills horizontally so the wrapped label uses whatever width
        // the panel allocates, instead of forcing a fixed 260-px column that
        // overflows on small viewports.
        contentCharacter.add(characterLabel).expandX().fillX().top().left();

        // Perks tab — populated each frame in update() so plus-button clicks reflect
        // immediately. The body table is held by reference so we can clearChildren()
        // and rebuild without losing the parent layout.
        contentPerks = new Table();
        contentPerks.top().left();

        historyLabel = new Label("", skin);
        historyLabel.setWrap(true);
        historyLabel.setAlignment(com.badlogic.gdx.utils.Align.topLeft);
        // Plain ScrollPane with no skin-backed style — the stone UI skin doesn't register a
        // ScrollPaneStyle, so passing `skin` here would crash at construction. The default
        // no-arg style draws no scrollbar chrome, which is fine for a text scroller.
        ScrollPane historyScroll = new ScrollPane(historyLabel);
        historyScroll.setFadeScrollBars(false);
        historyScroll.setScrollingDisabled(true, false);
        contentHistory = new Table();
        // No minSize on the cell — letting the scroll pane expand/fill is enough,
        // and a hard minSize forces the body Stack at least 280 wide which makes the
        // popup overflow its panel chrome on narrow viewports.
        contentHistory.add(historyScroll).expand().fill();

        // Body — exactly one of the three content tables is visible at any time (driven
        // by setActiveTab), so we stack them via a Stack and let scene2d hide/show them.
        com.badlogic.gdx.scenes.scene2d.ui.Stack body =
                new com.badlogic.gdx.scenes.scene2d.ui.Stack();
        body.add(contentCharacter);
        body.add(contentPerks);
        body.add(contentHistory);

        // Tab section — Stack with a bordered content panel BELOW the tab strip;
        // tabs overlap only the panel's 2-px top border (manila-folder pattern,
        // matching the inventory backpack section).
        final int TAB_H = 26;
        com.badlogic.gdx.scenes.scene2d.ui.Stack tabSection =
                new com.badlogic.gdx.scenes.scene2d.ui.Stack();

        Image tabSectionBg = new Image(skin.getDrawable("panel"));
        tabSectionBg.setScaling(com.badlogic.gdx.utils.Scaling.stretch);
        Table tabSectionBgLayer = new Table();
        tabSectionBgLayer.top();
        tabSectionBgLayer.add().height(TAB_H - 2).row();
        tabSectionBgLayer.add(tabSectionBg).expand().fill();
        tabSection.add(tabSectionBgLayer);

        Table tabSectionContent = new Table();
        tabSectionContent.top();
        tabSectionContent.add(tabs).left().fillX().height(TAB_H).row();
        tabSectionContent.add(body).expand().fill().pad(8);
        tabSection.add(tabSectionContent);

        frame.add(tabSection).expand().fill().row();

        // Close button.
        TextButton closeBtn = new TextButton("Close", skin);
        closeBtn.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { close(); }
        });
        frame.add(closeBtn).padTop(6);

        framed = new Container<>(frame);
        framed.setBackground(skin.getDrawable("panel"));
        // The container fills its child (the {@code frame} Table) to whatever explicit
        // size we set in {@link #layoutForStage} — that's what makes the panel reactive
        // to viewport size. Without {@code fill()}, setSize on the container would
        // leave the inner Table at its packed preferred size and waste the new space.
        framed.fill();
        framed.pack();
        addActor(framed);

        // Dim the rest of the screen while open — tapping outside the frame closes.
        setVisible(false);
        setTouchable(Touchable.enabled);
        addListener(new InputListener() {
            @Override public boolean touchDown(InputEvent e, float x, float y, int pointer, int btn) {
                // Outside the framed panel counts as "dismiss".
                if (!open) return false;
                if (!withinFrame(x, y)) { close(); return true; }
                return false;
            }
            @Override public boolean keyDown(InputEvent event, int keycode) {
                if (!open) return false;
                if (keycode == Input.Keys.ESCAPE) { close(); return true; }
                return false;
            }
        });

        setActiveTab(0);
    }

    private boolean withinFrame(float x, float y) {
        return x >= framed.getX() && x <= framed.getX() + framed.getWidth()
            && y >= framed.getY() && y <= framed.getY() + framed.getHeight();
    }

    private void wireTab(TextButton btn, int idx) {
        btn.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { setActiveTab(idx); }
        });
    }

    public void setPlayer(Mob p) { this.player = p; }
    public boolean isOpen() { return open; }

    public void toggle() { if (open) close(); else open(); }

    public void open() {
        open = true;
        setVisible(true);
        setActiveTab(activeTab);
        if (getStage() != null) getStage().setKeyboardFocus(this);
    }

    public void close() {
        open = false;
        setVisible(false);
    }

    private void setActiveTab(int idx) {
        activeTab = idx;
        tabCharacter.setChecked(idx == 0);
        tabPerks    .setChecked(idx == 1);
        tabHistory  .setChecked(idx == 2);
        contentCharacter.setVisible(idx == 0);
        contentPerks    .setVisible(idx == 1);
        contentHistory  .setVisible(idx == 2);
        if (idx == 1) perksDirty = true;
    }

    /** Called once per frame while open. Re-render tab bodies from the live player state. */
    public void update() {
        if (!open || player == null) return;

        // Character tab — plain key/value block.
        StringBuilder sb = new StringBuilder();
        sb.append("Name:  ").append(player.name != null ? player.name : "Adventurer").append('\n');
        sb.append("Class: ").append(player.characterClass != null ? player.characterClass.displayName : "?")
          .append('\n');
        sb.append("Level: ").append(player.characterLevel);
        if (player.characterLevel < GameBalance.MAX_CHARACTER_LEVEL) {
            int nextTotal = MobProgression.xpToReach(player.characterLevel + 1);
            sb.append("    (").append(player.xp).append(" / ").append(nextTotal).append(" xp)");
        } else {
            sb.append("    (MAX)");
        }
        sb.append('\n');
        com.bjsp123.rl2.model.StatBlock ps = player.effectiveStats();
        sb.append("HP:    ").append((int) Math.round(player.hp)).append(" / ")
          .append((int) Math.round(ps.maxHp)).append('\n');
        sb.append("Satiety: ").append(player.satiety).append(" / ")
          .append(GameBalance.STARTING_SATIETY).append('\n');
        sb.append("Score: ").append(player.score).append('\n');
        sb.append('\n');
        sb.append("Attack:  ").append(ps.accuracy).append('\n');
        sb.append("Defense: ").append(ps.evasion).append('\n');
        sb.append("Damage:  ").append(meleeDamageLine(player)).append('\n');
        sb.append("Armor:   ").append(armorLine(player)).append('\n');
        sb.append('\n');
        sb.append("Perk points: ").append(player.perkPoints);
        characterLabel.setText(sb.toString());

        // Perks tab — only rebuilt when the perk state changes (tab activated, or a
        // perk point just spent). See the perksDirty field's javadoc for why we
        // can't rebuild every frame.
        if (activeTab == 1 && perksDirty) {
            contentPerks.clearChildren();
            // Cell defaults: every row anchors top-left so wrapped descriptions align
            // with the perk name on the row above, not centred against it.
            contentPerks.defaults().left().top().padBottom(4);
            Label header = new Label("Perk points: " + player.perkPoints, skin);
            contentPerks.add(header).colspan(3).left().padBottom(6).row();
            for (Perk perk : Perk.values()) {
                int lvl = player.perks == null ? 0
                        : player.perks.getOrDefault(perk, 0);
                String name = perk.displayName() + (lvl > 0 ? "  Lv " + lvl : "");
                Label nameLbl = new Label(name, skin);
                Label desc = new Label(perk.description(), skin, "dim");
                desc.setWrap(true);
                // Row layout: name (min 96, no upper bound) | description (grows
                // into whatever's left, wraps) | + button (fixed 28). Flex widths
                // mean the row never forces the panel wider than its container.
                contentPerks.add(nameLbl).minWidth(96).left().top();
                contentPerks.add(desc).growX().prefWidth(0).left().top()
                        .padLeft(8).padRight(4);
                if (player.perkPoints > 0) {
                    final Perk p = perk;
                    TextButton plus = new TextButton("+", skin);
                    plus.addListener(new ClickListener() {
                        @Override public void clicked(InputEvent e, float x, float y) {
                            int cur = player.perks.getOrDefault(p, 0);
                            player.perks.put(p, cur + 1);
                            player.perkPoints--;
                            perksDirty = true;
                        }
                    });
                    contentPerks.add(plus).width(28).top();
                } else {
                    contentPerks.add().width(28);
                }
                contentPerks.row().padBottom(4);
            }
            perksDirty = false;
        }

        // History tab — render latest-first so the most recent event shows at the top.
        if (activeTab == 2) {
            StringBuilder h = new StringBuilder();
            if (player.history != null && !player.history.isEmpty()) {
                for (int i = player.history.size() - 1; i >= 0; i--) {
                    h.append(player.history.get(i).describe()).append('\n');
                }
            } else {
                h.append("No history yet.");
            }
            historyLabel.setText(h.toString());
        }
    }

    /** Current melee damage range formatted as "min-max", pulled directly from the
     *  StatBlock pipeline so item level scaling and any future buff contributions show. */
    private static String meleeDamageLine(Mob m) {
        com.bjsp123.rl2.model.MinMax d = m.effectiveStats().damage;
        return d.min() + "-" + d.max();
    }

    /** Current armor range, same StatBlock-sourced shape as {@link #meleeDamageLine}. */
    private static String armorLine(Mob m) {
        com.bjsp123.rl2.model.MinMax a = m.effectiveStats().armor;
        return a.min() + "-" + a.max();
    }

    /** Position the panel centred against the stage at a deterministic preferred size
     *  ({@link com.bjsp123.rl2.ui.skin.PanelSize.Kind#MEDIUM}). The size is independent
     *  of which tab is currently active — the body is a {@link com.badlogic.gdx.scenes.scene2d.ui.Stack}
     *  whose dimensions come entirely from this layout call, not from the visible
     *  child's preferred size, so switching tabs never resizes the frame. */
    public void layoutForStage(Stage stage) {
        float w = stage.getViewport().getWorldWidth();
        float h = stage.getViewport().getWorldHeight();
        setBounds(0, 0, w, h);
        // LARGE — matches the inventory window's "primary modal" sizing so the
        // character screen has the same reactive resize behaviour the player
        // has come to expect from the inventory.
        float panelW = com.bjsp123.rl2.ui.skin.PanelSize.widthFor(
                com.bjsp123.rl2.ui.skin.PanelSize.Kind.LARGE, w);
        float panelH = com.bjsp123.rl2.ui.skin.PanelSize.heightFor(
                com.bjsp123.rl2.ui.skin.PanelSize.Kind.LARGE, h);
        framed.setSize(panelW, panelH);
        framed.setPosition((w - panelW) / 2f, (h - panelH) / 2f);
    }

    @Override
    public void act(float delta) {
        super.act(delta);
        if (getStage() != null) layoutForStage(getStage());
        update();
    }
}
