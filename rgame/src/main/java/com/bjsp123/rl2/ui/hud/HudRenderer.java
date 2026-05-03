package com.bjsp123.rl2.ui.hud;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.bjsp123.rl2.input.LookMode;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.ui.skin.StoneUi;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntConsumer;
import com.bjsp123.rl2.world.render.ItemSprites;
import com.bjsp123.rl2.world.render.BuffIcons;
import com.bjsp123.rl2.ui.popup.InventoryRenderer;
import com.bjsp123.rl2.ui.overlay.LookRenderer;
import com.bjsp123.rl2.logic.TurnSystem;

/**
 * In-game HUD laid out into the four screen corners. All clusters are flush to the
 * screen edge — there is no margin between the buttons and the viewport border, so
 * the chrome reads as if it extends off the visible area.
 * <ul>
 *   <li><b>Top-left</b> — character portrait, HP / XP / Satiety bars, and a status
 *       line carrying the level depth + monotonic turn counter.</li>
 *   <li><b>Top-right</b> — single burger button; tap opens a dropdown with Settings,
 *       Level Info, Encyclopaedia, Return to Title.</li>
 *   <li><b>Bottom-left</b> — Wait, Look, and Info (map) buttons.</li>
 *   <li><b>Bottom-right</b> — six action buttons + an inventory button, all flush
 *       against the screen's bottom-right corner.</li>
 * </ul>
 */
public class HudRenderer extends Group {

    private static final int ACTION_BTN  = 32;   // bottom-right action + inventory cells
    private static final int BOTTOM_BTN  = 32;   // wait / look / info squares
    private static final int INFO_BTN    = 32;   // burger
    private static final int BAR_W       = 140;
    private static final int BAR_H       = 12;
    /** Inset for the top-left portrait + bars cluster. Bigger than 0 so the bars
     *  don't bleed into the world; the buttons themselves still sit flush to the
     *  edge via {@link #EDGE_MARGIN}. */
    private static final int MARGIN      = 8;
    /** Distance from the screen edge to the corner button clusters. Zero = the
     *  buttons sit flush against the viewport edge, looking like they extend off. */
    private static final int EDGE_MARGIN = 0;

    private final Skin    skin;
    private final StoneUi stoneUi;

    // Top-left — portrait is now a framed character sprite: a stone 9-patch background
    // with the actual player sprite composited on top. The inner image's drawable is
    // swapped per frame by update() based on the current character's class.
    //private final Image     portrait;             // drawable swapped per player class
    //private final Container<Image> portraitFramed;
    //private final Texture   portraitAtlasTex;     // myaprites.png (warrior, mage, rogue slots)
    //private final TextureRegionDrawable portraitWarrior;
    //private final TextureRegionDrawable portraitMage;
    //private final TextureRegionDrawable portraitRogue;
    private final BarWidget hpBar, xpBar, satietyBar;
    private final Table     topLeft;
    /** Multi-line list of active player buffs anchored under the portrait. Used when
     *  {@link com.bjsp123.rl2.ui.skin.UseBuffIcons#enabled()} is false (text mode). */
    private final Label     playerBuffsLabel;
    /** Horizontal strip of buff-icon images, rebuilt each frame from the player's
     *  active buffs. Used when {@link com.bjsp123.rl2.ui.skin.UseBuffIcons#enabled()}
     *  is true (icon mode). Sits in the same screen position as
     *  {@link #playerBuffsLabel}; only one of the two is non-empty at a time. */
    private final Table     playerBuffsIcons;

    // Top-right
    private final Button     burgerBtn;
    private final Table      menuPanel;
    private boolean menuOpen;

    // Bottom-left — Wait, Look, Info
    private final TextButton waitBtn;
    private final Button     lookBtn;
    private final Button     infoBtn;
    private final Table      bottomLeft;

    // Bottom-right — 6 action buttons + inventory button
    private final ActionButton[] actionBtns = new ActionButton[6];
    private final Image[]      actionIcons = new Image[6];
    private final Item[]       actionIconItem = new Item[6];
    private final Button       inventoryBtn;
    private final Button       combineBtn;
    private final Table        bottomRight;
    /** Top-left portrait — tap (or {@code c}) opens the character screen. The button
     *  body is a class-aware Image actor whose drawable is swapped per frame in
     *  {@link #update}. Cached drawables (one per {@link com.bjsp123.rl2.model.Mob.CharacterClass}
     *  region from {@link com.bjsp123.rl2.world.render.PortraitSprites}) live in
     *  {@link #portraitDrawables} so the swap doesn't allocate. */
    private final Button       portraitBtn;
    private final Image        portraitImage;
    private final java.util.EnumMap<com.bjsp123.rl2.model.Mob.CharacterClass, TextureRegionDrawable>
            portraitDrawables = new java.util.EnumMap<>(com.bjsp123.rl2.model.Mob.CharacterClass.class);
    /** Optional combine-screen toggle. Set via {@link #setCombineToggle}; the HUD button
     *  fires it when clicked. Lets the player pop the crafting modal without going
     *  through the inventory. */
    private Runnable onCombineToggle;
    private Runnable           onWait, onOpenInfo, onPortraitTap;
    /** Source of the live player mob — re-queried per frame so the HUD always renders
     *  the current player without re-wiring on level change. */
    private java.util.function.Supplier<Mob> playerSupplier;
    private final LogView      logView;
    private final TextButton   logToggleBtn, lowToggleBtn, npcToggleBtn, expandToggleBtn;
    private final Table        logToggleRow;
    private IntConsumer        onActionUse;
    private com.bjsp123.rl2.ui.hud.ActionBar  actionBar;

    // TPS counter (overlay on top-left status)
    private final Label statusLine;

    private long  windowStart   = System.currentTimeMillis();
    private int   ticksInWindow = 0;
    private float tps           = 0;

    private InventoryRenderer inventoryRenderer;
    private LookMode          lookMode;
    private Runnable onReturnToTitle, onOpenSettings, onOpenEncyclopedia, onOpenLevelInfo;

    public HudRenderer(Skin skin, StoneUi stoneUi) {
        this.skin    = skin;
        this.stoneUi = stoneUi;

        

        TextureRegionDrawable whiteFill =
                new TextureRegionDrawable(new com.badlogic.gdx.graphics.g2d.TextureRegion(
                        stoneUi.whitePixel()));
        com.badlogic.gdx.graphics.g2d.BitmapFont font = skin.getFont("default-font");
        hpBar      = new BarWidget(whiteFill, font, new Color(0.85f, 0.18f, 0.18f, 1f));
        xpBar      = new BarWidget(whiteFill, font, new Color(0.95f, 0.78f, 0.20f, 1f));
        satietyBar = new BarWidget(whiteFill, font, new Color(0.55f, 0.75f, 0.30f, 1f));

        Table bars = new Table();
        bars.defaults().pad(1).left();
        bars.add(hpBar     ).size(BAR_W, BAR_H).row();
        bars.add(xpBar     ).size(BAR_W, BAR_H).row();
        bars.add(satietyBar).size(BAR_W, BAR_H);

        // Portrait — clickable tile at top-left holding the player class's head sprite.
        // Drawable swapped per frame by update() based on Mob.characterClass; the "C"
        // hotkey badge is overlaid in the top-left corner via the shared accel-badge
        // helper. 'c' (GameInput / PlayScreen) and a click here both fire onPortraitTap.
        portraitImage = new Image();
        portraitImage.setScaling(com.badlogic.gdx.utils.Scaling.fit);
        portraitBtn = new Button(skin, "action-icon") {
            @Override public void draw(com.badlogic.gdx.graphics.g2d.Batch batch, float parentAlpha) {
                super.draw(batch, parentAlpha);
                ActionButton.drawAccelBadge(batch, skin.getFont("default-font"), "C",
                        getX(), getY(), getHeight(), parentAlpha);
            }
        };
        portraitBtn.add(portraitImage).size(32, 32);
        portraitBtn.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                if (onPortraitTap != null) onPortraitTap.run();
            }
        });

        topLeft = new Table();
        topLeft.add(portraitBtn).size(40, 40).padRight(6);
        topLeft.add(bars).top();
        addActor(topLeft);

        // Small dim label that lists every active buff on the player, one per line.
        // Anchored a few pixels below the portrait by HudRenderer.layout(). Stays
        // empty when the player has no buffs, so it doesn't take visual space when
        // there's nothing to show. Populated only when the user-pref toggle for
        // icon-style buffs is OFF; otherwise the icon strip below renders the buffs.
        playerBuffsLabel = new Label("", skin, "dim");
        playerBuffsLabel.setFontScale(0.85f);
        addActor(playerBuffsLabel);

        playerBuffsIcons = new Table();
        playerBuffsIcons.left();
        playerBuffsIcons.defaults().padRight(2);
        addActor(playerBuffsIcons);

        statusLine = new Label("", skin, "title");
        addActor(statusLine);

        // ── Top-right: single burger button + dropdown ──────────────────
        // The dropdown subsumes the previous row of icon buttons (cog, compass, question mark,
        // marker). Look has its own button bottom-left now; the rest live here as text items.
        menuPanel = new Table();
        menuPanel.setBackground(skin.getDrawable("simple-panel"));
        menuPanel.add(makeMenuItem("Settings",        () -> {
            closeMenu();
            if (onOpenSettings != null) onOpenSettings.run();
        })).width(180).height(30).pad(3).row();
        menuPanel.add(makeMenuItem("Level Info",      () -> {
            closeMenu();
            if (onOpenLevelInfo != null) onOpenLevelInfo.run();
        })).width(180).height(30).pad(3).row();
        menuPanel.add(makeMenuItem("Encyclopaedia",   () -> {
            closeMenu();
            if (onOpenEncyclopedia != null) onOpenEncyclopedia.run();
        })).width(180).height(30).pad(3).row();
        menuPanel.add(makeMenuItem("Return to Title", () -> {
            closeMenu();
            if (onReturnToTitle != null) onReturnToTitle.run();
        })).width(180).height(30).pad(3);
        menuPanel.setVisible(false);

        burgerBtn = makeIconButton(stoneUi.iconCogTex, this::toggleMenu);
        addActor(burgerBtn);
        addActor(menuPanel);

        // ── Bottom-left: Wait / Look / Info ─────────────────────────────
        // Wait fires the same handler as SPACE / tapping the player tile (pickup or
        // wait one move-cost). Look toggles the world look-mode cursor. Info opens
        // the map dialog (overall map + current-level info tabs).
        waitBtn = makeTextButton("Wait",
                () -> { if (onWait != null) onWait.run(); });
        lookBtn = makeIconButton(stoneUi.iconMarkerTex, "L",
                () -> { if (lookMode != null) lookMode.toggle(); });
        infoBtn = makeIconButton(stoneUi.iconCompassTex,
                () -> { if (onOpenInfo != null) onOpenInfo.run(); });
        bottomLeft = new Table();
        bottomLeft.defaults().size(BOTTOM_BTN, BOTTOM_BTN);
        bottomLeft.add(waitBtn);
        bottomLeft.add(lookBtn);
        bottomLeft.add(infoBtn);
        addActor(bottomLeft);

        // ── Bottom-right: 6 action buttons (smaller, tighter, edge-hugging) ──


        // Six action buttons + an inventory button to the right of them. The whole
        // strip butts up against the bottom-right corner of the screen so it reads as
        // continuing off the visible area.
        bottomRight = new Table();
        bottomRight.defaults().size(ACTION_BTN, ACTION_BTN);
        for (int i = 0; i < actionBtns.length; i++) {
            final int slotIdx = i;
            actionBtns[i] = new ActionButton(
                    Integer.toString(i + 1),
                    skin,
                    () -> { if (onActionUse != null) onActionUse.accept(slotIdx); });
            bottomRight.add(actionBtns[i]);
        }
        // Inventory icon — distinct visual from the action slots so it doesn't look
        // like a 7th quickslot. Uses the chest icon; "I" hotkey shown in the corner.
        inventoryBtn = makeIconButton(stoneUi.iconChestTex, "I", () -> {
            if (inventoryRenderer != null) inventoryRenderer.toggle();
        });
        bottomRight.add(inventoryBtn).size(ACTION_BTN, ACTION_BTN);
        // Combine — sibling button next to the inventory icon. Falls back to a text label
        // if no dedicated icon is loaded.
        combineBtn = makeTextButton("❂", () -> {
            if (onCombineToggle != null) onCombineToggle.run();
        });
        bottomRight.add(combineBtn).size(ACTION_BTN, ACTION_BTN);
        addActor(bottomRight);

        // ── Event log (above action buttons) ───────────────────────────
        logView = new LogView();
        addActor(logView);

        // ── Log toggles: on, show-low-priority, show-non-player, expand ───────
        logToggleBtn = makeTextButton("L", () -> logView.logOn          = !logView.logOn);
        lowToggleBtn = makeTextButton("!", () -> logView.showLowPriority = !logView.showLowPriority);
        npcToggleBtn = makeTextButton("M", () -> logView.showNonPlayer   = !logView.showNonPlayer);
        // Up-caret when collapsed (tap to expand to 10 lines), down-caret when expanded
        // (tap to collapse back to 2). Glyph is updated each frame in update(). ASCII so
        // it renders correctly in the default bitmap font without needing a Unicode glyph.
        expandToggleBtn = makeTextButton("^",
                () -> logView.expanded = !logView.expanded);
        int TOGGLE = 16;
        logToggleRow = new Table();
        logToggleRow.defaults().size(TOGGLE, TOGGLE).pad(1);
        logToggleRow.add(logToggleBtn);
        logToggleRow.add(lowToggleBtn);
        logToggleRow.add(npcToggleBtn);
        logToggleRow.add(expandToggleBtn);
        addActor(logToggleRow);
    }

    public LogView logView() { return logView; }

    /** Square stone-tile button holding a centred 16×16 icon. Uses the {@code action-icon}
     *  style. {@code accel} (optional) is the keyboard hotkey label rendered as a tiny
     *  badge in the top-left corner — pass null/empty for buttons with no hotkey. */
    private Button makeIconButton(com.badlogic.gdx.graphics.Texture iconTex, String accel, Runnable onClick) {
        Button b = new Button(skin, "action-icon") {
            @Override public void draw(com.badlogic.gdx.graphics.g2d.Batch batch, float parentAlpha) {
                super.draw(batch, parentAlpha);
                ActionButton.drawAccelBadge(batch, skin.getFont("default-font"), accel,
                        getX(), getY(), getHeight(), parentAlpha);
            }
        };
        if (iconTex != null) {
            Image icon = new Image(iconTex);
            icon.setScaling(com.badlogic.gdx.utils.Scaling.fit);
            b.add(icon).size(18, 18);
        }
        b.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { onClick.run(); }
        });
        return b;
    }

    /** Backward-compatible overload — no keyboard accelerator badge. */
    private Button makeIconButton(com.badlogic.gdx.graphics.Texture iconTex, Runnable onClick) {
        return makeIconButton(iconTex, null, onClick);
    }

    /** Square stone-tile button with a text label. Uses the {@code action-text} style.
     *  {@code accel} (optional) is the keyboard hotkey label rendered in the top-left
     *  corner alongside the centred {@code label}. */
    private TextButton makeTextButton(String label, String accel, Runnable onClick) {
        TextButton b = new TextButton(label, skin, "action-text") {
            @Override public void draw(com.badlogic.gdx.graphics.g2d.Batch batch, float parentAlpha) {
                super.draw(batch, parentAlpha);
                ActionButton.drawAccelBadge(batch, getStyle().font, accel,
                        getX(), getY(), getHeight(), parentAlpha);
            }
        };
        b.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { onClick.run(); }
        });
        return b;
    }

    /** Backward-compatible overload — no keyboard accelerator badge. */
    private TextButton makeTextButton(String label, Runnable onClick) {
        return makeTextButton(label, null, onClick);
    }

    private TextButton makeMenuItem(String label, Runnable onClick) {
        TextButton b = new TextButton(label, skin);
        b.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { onClick.run(); }
        });
        return b;
    }

    public void setOnActionUse(IntConsumer fn)  { this.onActionUse  = fn; }
    public void setOnWait(Runnable fn)          { this.onWait        = fn; }
    public void setOnOpenInfo(Runnable fn)      { this.onOpenInfo    = fn; }
    /** Live player accessor for HUD widgets that need the current player without
     *  re-wiring on level change. Set by PlayScreen to {@code TurnSystem.findPlayer}. */
    public void setPlayerSupplier(java.util.function.Supplier<Mob> s) { this.playerSupplier = s; }
    /** Wired to the portrait button at top-left — opens the character screen. */
    public void setOnPortraitTap(Runnable fn)   { this.onPortraitTap = fn; }
    public void setActionBar(com.bjsp123.rl2.ui.hud.ActionBar bar) { this.actionBar = bar; }

    public void setOverlays(InventoryRenderer inv, LookRenderer look) {
        this.inventoryRenderer = inv;
    }

    public void setLookMode(LookMode lookMode) { this.lookMode = lookMode; }

    public void setOnReturnToTitle    (Runnable r) { this.onReturnToTitle    = r; }
    public void setOnOpenSettings     (Runnable r) { this.onOpenSettings     = r; }
    public void setOnOpenEncyclopedia (Runnable r) { this.onOpenEncyclopedia = r; }
    public void setOnOpenLevelInfo    (Runnable r) { this.onOpenLevelInfo    = r; }
    public void setCombineToggle      (Runnable r) { this.onCombineToggle    = r; }

    public boolean isMenuOpen() { return menuOpen; }

    private void toggleMenu() {
        menuOpen = !menuOpen;
        menuPanel.setVisible(menuOpen);
        burgerBtn.setChecked(menuOpen);
    }

    private void closeMenu() {
        menuOpen = false;
        menuPanel.setVisible(false);
        burgerBtn.setChecked(false);
    }

    public void recordTick() {
        ticksInWindow++;
        long now = System.currentTimeMillis();
        long elapsed = now - windowStart;
        if (elapsed >= 1000) {
            tps = ticksInWindow * 1000f / elapsed;
            ticksInWindow = 0;
            windowStart = now;
        }
    }

    /** Rebuild either the buff icon strip or the buff text label from the player's
     *  current buffs, depending on the {@link com.bjsp123.rl2.ui.skin.UseBuffIcons}
     *  preference. Called once per frame so duration tick-down stays current. */
    private void refreshPlayerBuffs(Mob player) {
        if (player.buffs == null || player.buffs.isEmpty()) {
            playerBuffsLabel.setText("");
            playerBuffsLabel.pack();
            playerBuffsIcons.clearChildren();
            playerBuffsIcons.pack();
            return;
        }
        boolean iconsOn = com.bjsp123.rl2.ui.skin.UseBuffIcons.enabled()
                && BuffIcons.isLoaded();
        if (iconsOn) {
            playerBuffsLabel.setText("");
            playerBuffsLabel.pack();
            playerBuffsIcons.clearChildren();
            // Each buff = a small vertical column: icon on top, "+N Tt" label below.
            // Levels and durations are part of the game state the player needs to read
            // at a glance, not just the bare buff identity.
            for (com.bjsp123.rl2.model.Buff b : player.buffs) {
                com.badlogic.gdx.graphics.g2d.TextureRegion r = BuffIcons.regionFor(b.type);
                com.badlogic.gdx.scenes.scene2d.ui.Table cell =
                        new com.badlogic.gdx.scenes.scene2d.ui.Table();
                cell.defaults().center();
                if (r != null) {
                    com.badlogic.gdx.scenes.scene2d.ui.Image im =
                            new com.badlogic.gdx.scenes.scene2d.ui.Image(r);
                    cell.add(im).size(10, 10).row();
                }
                String levelStr = b.level > 1 ? "+" + (b.level - 1) + " " : "";
                Label info = new Label(levelStr + b.durationTurns + "t", skin, "dim");
                info.setFontScale(0.7f);
                cell.add(info);
                playerBuffsIcons.add(cell).padRight(2);
            }
            playerBuffsIcons.pack();
        } else {
            playerBuffsIcons.clearChildren();
            playerBuffsIcons.pack();
            playerBuffsLabel.setText(com.bjsp123.rl2.logic.BuffSystem.summary(player));
            playerBuffsLabel.pack();
        }
    }

    /** Refresh state shown by the HUD — call once per frame from PlayScreen. */
    public void update(Mob player, int depth, int turn, int tick) {
        // Prefer the live supplier when available so we never render a stale player
        // reference after a level transition.
        if (playerSupplier != null) {
            Mob live = playerSupplier.get();
            if (live != null) player = live;
        }
        if (player != null) {
            double maxHp = player.effectiveStats().maxHp;
            float hpFrac = maxHp > 0 ? (float) (player.hp / maxHp) : 0f;
            hpBar.setFraction(hpFrac);
            hpBar.setLabel((int) Math.round(player.hp) + "/" + (int) Math.round(maxHp));
            int satMax = com.bjsp123.rl2.logic.GameBalance.STARTING_SATIETY;
            float satFrac = satMax > 0 ? (float) player.satiety / satMax : 0f;
            satietyBar.setFraction(satFrac);
            satietyBar.setLabel(player.satiety + "/" + satMax);
            refreshPlayerBuffs(player);
            // Portrait sprite — head + shoulders region for the player's class, pulled
            // from sprites/mobs.png via PortraitSprites. Drawables are cached per class
            // in portraitDrawables so the per-frame swap doesn't allocate.
            if (player.characterClass != null) {
                TextureRegionDrawable d = portraitDrawables.computeIfAbsent(
                        player.characterClass,
                        c -> {
                            com.badlogic.gdx.graphics.g2d.TextureRegion r =
                                    com.bjsp123.rl2.world.render.PortraitSprites.regionFor(c);
                            return r != null ? new TextureRegionDrawable(r) : null;
                        });
                if (d != null && portraitImage.getDrawable() != d) {
                    portraitImage.setDrawable(d);
                }
            }
        } else {
            hpBar.setFraction(0); hpBar.setLabel("");
            satietyBar.setFraction(0); satietyBar.setLabel("");
            playerBuffsLabel.setText("");
            playerBuffsLabel.pack();
            playerBuffsIcons.clearChildren();
            playerBuffsIcons.pack();
        }
        // XP has no model yet — show an empty bar so the layout stays honest.
        xpBar.setFraction(0);
        xpBar.setLabel("XP");

        // Status line: depth + monotonic turn + game-tick counters ("Lvl 3  Turn 152
        // Tick 18403"). The running TPS estimate hangs off the end so it stays available
        // for tuning without crowding the level/turn display.
        statusLine.setText(com.bjsp123.rl2.util.Fmt.of(
                "Lvl %d  Turn %d  Tick %d   TPS %.1f", depth, turn, tick, tps));

        lookBtn.setChecked(lookMode != null && lookMode.isActive());
        inventoryBtn.setChecked(inventoryRenderer != null && inventoryRenderer.isOpen());

        logToggleBtn.setChecked(logView.logOn);
        lowToggleBtn.setChecked(logView.showLowPriority);
        npcToggleBtn.setChecked(logView.showNonPlayer);
        expandToggleBtn.setChecked(logView.expanded);
        expandToggleBtn.setText(logView.expanded ? "v" : "^");

        refreshActionIcons(player);
    }

    /** Sync each action button's child icon with the bound item in the HUD ActionBar. */
    private void refreshActionIcons(Mob player) {
        for (int i = 0; i < actionBtns.length; i++) {
            Item bound = actionBar != null ? actionBar.get(i) : null;
            if (bound == actionIconItem[i]) continue;   // no change since last frame
            actionIconItem[i] = bound;
            TextButton btn = actionBtns[i];
            btn.clearChildren();
            if (bound == null) {
                btn.setText(Integer.toString(i + 1));
            } else {
                btn.setText("");
                // Source the icon from the shared ItemSprites loader so the action
                // bar matches the inventory popup and the on-floor sprite. Falls back
                // to the legacy glyph-keyed map when ItemSprites can't resolve.
                TextureRegion region = ItemSprites.regionFor(bound);
                if (region != null) {
                    Image icon = new Image(region);
                    icon.setScaling(com.badlogic.gdx.utils.Scaling.fit);
                    btn.add(icon).size(18, 18);
                    actionIcons[i] = icon;
                }
            }
        }
    }

    /** Position the four corner clusters against the current stage size. All clusters
     *  sit flush against their edge ({@link #EDGE_MARGIN} = 0) so the buttons read as
     *  if they extend off the visible area. The top-left bars cluster keeps a small
     *  inset so the bar text doesn't bleed into the world. */
    public void layoutForStage(Stage stage) {
        float w = stage.getViewport().getWorldWidth();
        float h = stage.getViewport().getWorldHeight();

        topLeft.pack();
        topLeft.setPosition(EDGE_MARGIN, h - EDGE_MARGIN - topLeft.getHeight());

        // Player buffs sit just under the portrait+bars cluster. Only one of the two
        // sibling actors (text label vs icon strip) carries content at any given time
        // — the empty one packs to (0, 0) and is invisible.
        playerBuffsLabel.pack();
        playerBuffsLabel.setPosition(EDGE_MARGIN + 4f,
                topLeft.getY() - playerBuffsLabel.getHeight() - 2f);
        playerBuffsIcons.pack();
        playerBuffsIcons.setPosition(EDGE_MARGIN + 4f,
                topLeft.getY() - playerBuffsIcons.getHeight() - 2f);

        float buffsBottom = Math.min(playerBuffsLabel.getY(), playerBuffsIcons.getY());
        statusLine.pack();
        statusLine.setPosition(MARGIN, buffsBottom - statusLine.getHeight() - 2);

        burgerBtn.setSize(INFO_BTN, INFO_BTN);
        burgerBtn.setPosition(w - EDGE_MARGIN - INFO_BTN, h - EDGE_MARGIN - INFO_BTN);

        menuPanel.pack();
        menuPanel.setPosition(w - EDGE_MARGIN - menuPanel.getWidth(),
                              burgerBtn.getY() - menuPanel.getHeight() - 4);

        bottomLeft.pack();
        bottomLeft.setPosition(EDGE_MARGIN, EDGE_MARGIN);

        bottomRight.pack();
        bottomRight.setPosition(w - EDGE_MARGIN - bottomRight.getWidth(), EDGE_MARGIN);

        logToggleRow.pack();
        logToggleRow.setPosition(w - EDGE_MARGIN - logToggleRow.getWidth(),
                                 bottomRight.getY() + bottomRight.getHeight() + 4);

        float logW = logView.preferredWidth();
        float logH = logView.preferredHeight();
        logView.setSize(logW, logH);
        logView.setPosition(w - EDGE_MARGIN - logW,
                            logToggleRow.getY() + logToggleRow.getHeight() + 4);
    }

    @Override
    public void act(float delta) {
        super.act(delta);
        if (getStage() != null) layoutForStage(getStage());
    }


    /** Source-compat shims with the old API. */
    public void create()  { }
    public void resize(int w, int h) { }
    public void render(Mob player, int depth, int turn, int tick) { update(player, depth, turn, tick); }
    public void dispose() {
        if (logView != null) logView.dispose();
    }
}
