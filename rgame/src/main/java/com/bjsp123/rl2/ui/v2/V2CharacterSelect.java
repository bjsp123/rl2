package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.bjsp123.rl2.Rl2Game;
import com.bjsp123.rl2.logic.ItemDefinition;
import com.bjsp123.rl2.logic.Registries;
import com.bjsp123.rl2.logic.MobDefinition;
import com.bjsp123.rl2.logic.MobFactory;
import com.bjsp123.rl2.logic.TextCatalog;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Mob.CharacterClass;
import com.bjsp123.rl2.model.Perk;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.screen.PlayScreen;
import com.bjsp123.rl2.util.SeedCode;
import com.bjsp123.rl2.world.render.MobSprites;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * V2 character-select screen - three big class buttons (Warrior / Rogue /
 * Mage) plus a back affordance. Tapping a class opens a character-detail
 * popup showing the class sprite (encyclopaedia-style framed), its
 * description, starting gear / stats / perks (all read live from the
 * mobs CSV via {@link MobRegistry}), and Options + PLAY buttons. The
 * Options button stacks a pre-game-options popup on top with God Mode
 * and seed entry; PLAY launches a fresh run with the current settings.
 */
public final class V2CharacterSelect extends V2Screen {

    private static final CharacterClass[] CLASSES = {
            CharacterClass.WARRIOR, CharacterClass.ROGUE, CharacterClass.MAGE
    };

    private final Rl2Game game;
    private final int slot;

    /** Selected class, or {@code null} when only the class buttons are up. */
    private CharacterClass selected;
    /** {@code true} when the pre-game options popup is stacked on top of
     *  the character-detail popup. */
    private boolean optionsOpen;

    /** Pre-game options. {@link #customSeed} == null means "random". */
    private boolean godMode;
    private Long customSeed;
    /** Starting character level - also doubles as the starting dungeon
     *  depth when {@code > 1}. Clamped at build time to
     *  {@code [1, DUNGEON_DEPTH]} since the world only has that many
     *  levels. */
    private int startingLevel = 1;
    /** Seed the player with one of every non-unique item in the registry. */
    private boolean allItems;
    /** Seed the player with one of every GEM-category crafted scroll. */
    private boolean allScrolls;
    /** Grant +10 perk points on top of the character's normal allowance. */
    private boolean tenPerkPoints;
    /** Reveal-whole-world flag: every level pre-explored, every beacon
     *  pre-activated, +10 teleport orbs in the starting inventory. */
    private boolean revealWholeWorld;
    /** Drop the player on the endgame Landing floor (1 below the last
     *  regular dungeon depth) instead of depth 1. Defaults TRUE while we
     *  iterate on the endgame floors - flip back to FALSE once they're
     *  feature-complete and the regular dungeon start matters again. */
    private boolean startOnLanding = false;

    private final Rect window       = new Rect();
    private final Rect charPopup    = new Rect();
    private final Rect optionsPopup = new Rect();
    private final Rect spriteFrame  = new Rect();

    /** Cached per-class sample Mob - lets us pull live sprite + stats
     *  without re-rolling each frame. Built lazily; never added to a
     *  level. */
    private final java.util.EnumMap<CharacterClass, Mob> sampleMob
            = new java.util.EnumMap<>(CharacterClass.class);

    /** The detail popup is split into icon-labelled tabs so each view is
     *  one focused thing (per the "list OR info" principle) instead of one
     *  crammed, overflowing panel. */
    private enum DetailTab { SUMMARY, STATS, ITEMS, PERKS }
    private static final DetailTab[] DETAIL_TABS = DetailTab.values();
    private DetailTab detailTab = DetailTab.SUMMARY;
    private final Rect[] detailTabRects =
            { new Rect(), new Rect(), new Rect(), new Rect() };

    /** Icon for each detail tab - tabs are labelled with icons, not text. */
    private static com.bjsp123.rl2.world.render.IconSprites.Icon tabIcon(DetailTab t) {
        return switch (t) {
            case SUMMARY -> com.bjsp123.rl2.world.render.IconSprites.Icon.CHARACTER;
            case STATS   -> com.bjsp123.rl2.world.render.IconSprites.Icon.INFO;
            case ITEMS   -> com.bjsp123.rl2.world.render.IconSprites.Icon.EQUIPMENT;
            case PERKS   -> com.bjsp123.rl2.world.render.IconSprites.Icon.PERKS;
        };
    }

    public V2CharacterSelect(Rl2Game game, int slot) {
        super(game.ui);
        this.game = game;
        this.slot = slot;
    }

    @Override
    protected Rect modalWindow() {
        if (optionsOpen)       return optionsPopup;
        if (selected != null)  return charPopup;
        return window;
    }

    @Override
    protected void buildLayout() {
        float vw = ctx.worldW();
        float vh = ctx.worldH();
        float winW = Math.min(360f, vw - UIVars.PAD_MODAL);
        float winH = Math.min(560f, vh - 144f);
        window.set((vw - winW) * 0.5f, (vh - winH) * 0.5f, winW, winH);

        back   = new BackBtn(ctx, game::popScreen);
        burger = makeBurger();
        addStandardBurgerItems(game);

        if (optionsOpen) {
            buildOptionsPopup();
        } else if (selected != null) {
            buildCharacterPopup();
        } else {
            buildClassButtons();
        }
    }

    // -- Layer 1: class buttons ------------------------------------------
    private void buildClassButtons() {
        float btnW = window.w - 32f;
        float btnH = 64f;
        float gap  = 12f;
        float colH       = CLASSES.length * btnH + (CLASSES.length - 1) * gap;
        float contentTop = window.top() - headerBandH();
        float contentCy  = (window.y + contentTop) * 0.5f;
        float yTop       = contentCy + colH * 0.5f - btnH;
        for (int i = 0; i < CLASSES.length; i++) {
            final CharacterClass c = CLASSES[i];
            float y = yTop - i * (btnH + gap);
            buttons.add(new Btn(c.displayName(),
                    window.x + 16f, y, btnW, btnH,
                    () -> { selected = c; detailTab = DetailTab.SUMMARY; show(); }).header());
        }
    }

    // -- Layer 2: character detail popup ---------------------------------
    private void buildCharacterPopup() {
        float vw = ctx.worldW();
        float vh = ctx.worldH();
        float pw = Math.min(360f, vw - UIVars.PAD_MODAL);
        float ph = Math.min(560f, vh - 144f);
        charPopup.set((vw - pw) * 0.5f, (vh - ph) * 0.5f, pw, ph);

        // Tab bar - four icon tabs (Summary / Stats / Items / Perks) just
        // below the title band. Each tab swaps the body content so no single
        // view crams everything. The active tab gets the header (accent) look.
        float tabH   = 40f;
        float tabGap = 6f;
        float tabX   = charPopup.x + 14f;
        float tabW   = (charPopup.w - 28f - tabGap * 3f) / 4f;
        float tabY   = charPopup.top() - headerBandH() - tabH;
        for (int i = 0; i < DETAIL_TABS.length; i++) {
            final DetailTab t = DETAIL_TABS[i];
            float tx = tabX + i * (tabW + tabGap);
            detailTabRects[i].set(tx, tabY, tabW, tabH);
            Btn tab = new Btn("", tx, tabY, tabW, tabH,
                    () -> { detailTab = t; show(); });
            tab.icon = com.bjsp123.rl2.world.render.IconSprites.regionFor(tabIcon(t));
            if (t == detailTab) tab.header();
            buttons.add(tab);
        }

        // Sprite frame - Summary tab only; sized 2x source (64-px floor),
        // centred just below the tab bar.
        if (detailTab == DetailTab.SUMMARY) {
            Mob sample = sampleMob(selected);
            TextureRegion region = sample != null ? MobSprites.regionFor(sample) : null;
            int srcMax = 32;
            if (region != null) {
                srcMax = Math.max(region.getRegionWidth(), region.getRegionHeight());
            }
            float frameSz = Math.max(64f, srcMax * 2f);
            spriteFrame.set(charPopup.cx() - frameSz * 0.5f,
                    tabY - 8f - frameSz, frameSz, frameSz);
        } else {
            spriteFrame.set(0, 0, 0, 0);
        }

        // Bottom-row buttons: a square SETTINGS-icon Options button on
        // the left, and a wide PLAY button filling the rest. The square
        // options button gives PLAY visual primacy while keeping the
        // settings affordance discoverable via the standard UI icon.
        float btnH = 48f;
        float btnGap = 12f;
        float optionsW = btnH;                              // square
        float playW = pw - 2 * 14f - optionsW - btnGap;
        float btnY = charPopup.y + UIVars.BACK_SIZE + 2 * BackBtn.INSET;
        Btn optionsBtn = new Btn("",
                charPopup.x + 14f, btnY, optionsW, btnH,
                () -> { optionsOpen = true; show(); });
        optionsBtn.icon = com.bjsp123.rl2.world.render.IconSprites
                .regionFor(com.bjsp123.rl2.world.render.IconSprites.Icon.SETTINGS);
        buttons.add(optionsBtn);
        buttons.add(new Btn(TextCatalog.get("ui.common.play"),
                charPopup.x + 14f + optionsW + btnGap, btnY, playW, btnH,
                this::launchGame).header());
    }

    // -- Layer 3: pre-game options popup --------------------------------
    private void buildOptionsPopup() {
        float vw = ctx.worldW();
        float vh = ctx.worldH();
        float pw = Math.min(300f, vw - 32f);
        // Compressed to fit eight toggles plus the Done button: shorter
        // rows + tighter gaps, taller cap. headerBandH + 9*btnH + 7*gap +
        // pad is the worst-case stack height (see buildOptionsPopup loop).
        float ph = Math.min(440f, vh - 120f);
        optionsPopup.set((vw - pw) * 0.5f, (vh - ph) * 0.5f, pw, ph);

        float pad   = 16f;
        float btnH  = 32f;
        float gap   = 6f;
        float btnW  = pw - 2 * pad;
        float yTop  = optionsPopup.top() - headerBandH() - btnH;

        int maxStartLevel = Math.max(1,
                com.bjsp123.rl2.logic.GameBalance.DUNGEON_DEPTH);
        for (OptionRow row : List.of(
                new OptionRow(() -> TextCatalog.format("ui.characterSelect.godMode",
                        TextCatalog.vars("state", onOff(godMode))),
                        () -> { godMode = !godMode; show(); }),
                new OptionRow(() -> customSeed != null
                        ? TextCatalog.format("ui.characterSelect.seed",
                                TextCatalog.vars("seed", SeedCode.encode(customSeed)))
                        : TextCatalog.get("ui.characterSelect.seedRandom"),
                        this::promptForSeed),
                new OptionRow(() -> TextCatalog.format("ui.characterSelect.startingLevel",
                        TextCatalog.vars("level", startingLevel)),
                        () -> { startingLevel = (startingLevel % maxStartLevel) + 1; show(); }),
                new OptionRow(() -> TextCatalog.format("ui.characterSelect.allItems",
                        TextCatalog.vars("state", onOff(allItems))),
                        () -> { allItems = !allItems; show(); }),
                new OptionRow(() -> TextCatalog.format("ui.characterSelect.allScrolls",
                        TextCatalog.vars("state", onOff(allScrolls))),
                        () -> { allScrolls = !allScrolls; show(); }),
                new OptionRow(() -> TextCatalog.format("ui.characterSelect.tenPerkPoints",
                        TextCatalog.vars("state", onOff(tenPerkPoints))),
                        () -> { tenPerkPoints = !tenPerkPoints; show(); }),
                new OptionRow(() -> TextCatalog.format("ui.characterSelect.revealWholeWorld",
                        TextCatalog.vars("state", onOff(revealWholeWorld))),
                        () -> { revealWholeWorld = !revealWholeWorld; show(); }),
                new OptionRow(() -> TextCatalog.format("ui.characterSelect.startOnLanding",
                        TextCatalog.vars("state", onOff(startOnLanding))),
                        () -> { startOnLanding = !startOnLanding; show(); })
        )) {
            buttons.add(new Btn(row.label.get(), optionsPopup.x + pad, yTop,
                    btnW, btnH, row.onClick));
            yTop -= btnH + gap;
        }

        buttons.add(new Btn(TextCatalog.get("ui.common.done"), optionsPopup.x + pad,
                optionsPopup.y + pad, btnW, btnH,
                () -> { optionsOpen = false; show(); }).header());
    }

    private static final class OptionRow {
        final Supplier<String> label;
        final Runnable onClick;

        OptionRow(Supplier<String> label, Runnable onClick) {
            this.label = label;
            this.onClick = onClick;
        }
    }

    @Override
    protected void onEscape() {
        if (optionsOpen)       { optionsOpen = false; show(); return; }
        if (selected != null)  { selected = null;     show(); return; }
        super.onEscape();
    }

    // -- Game launch -----------------------------------------------------
    private void launchGame() {
        if (selected == null) return;
        if (game.currentPlay != null) {
            game.currentPlay.dispose();
            game.currentPlay = null;
        }
        game.saveSystem.clear(slot);
        game.setRootScreen(new PlayScreen(game, slot, selected,
                customSeed, godMode, startingLevel, allItems, tenPerkPoints,
                revealWholeWorld, startOnLanding, allScrolls));
    }

    // -- Seed entry ------------------------------------------------------
    /** Always pops the platform text dialog (Android keyboard, desktop
     *  modal field) so the player can type, edit, or clear the seed in
     *  one place. Pre-fills with the current seed code when one is set;
     *  empty / whitespace-only input means "random". Invalid input
     *  leaves the existing setting untouched. */
    private void promptForSeed() {
        String prefill = customSeed != null ? SeedCode.encode(customSeed) : "";
        Gdx.input.getTextInput(new Input.TextInputListener() {
            @Override public void input(String text) {
                String s = text == null ? "" : text.trim();
                if (s.isEmpty()) {
                    // Blank -> random.
                    customSeed = null;
                    show();
                } else if (SeedCode.isValid(s)) {
                    customSeed = SeedCode.decode(s);
                    show();
                }
                // Invalid non-empty input: silently leave current value.
            }
            @Override public void canceled() { /* leave current */ }
        }, TextCatalog.get("ui.characterSelect.seedPrompt"), prefill,
                TextCatalog.get("ui.characterSelect.seedExample"));
    }

    // -- Render passes ---------------------------------------------------
    @Override
    protected void drawBodyShape(UiCtx ctx) {
        Window.drawShape(ctx, window.x, window.y, window.w, window.h);

        if (selected != null) {
            // Dim + opaque-backed character popup.
            ShapeRenderer s = ctx.shapes;
            s.setColor(0f, 0f, 0f, UIVars.DIM_ALPHA);
            s.rect(0, 0, ctx.worldW(), ctx.worldH());
            s.setColor(0f, 0f, 0f, 1f);
            s.rect(charPopup.x, charPopup.y, charPopup.w, charPopup.h);
            Window.drawShape(ctx,
                    charPopup.x, charPopup.y, charPopup.w, charPopup.h);

            // Sprite frame chrome - Summary tab only.
            if (detailTab == DetailTab.SUMMARY) {
                Edges.drawTriLine(s, spriteFrame.x, spriteFrame.y,
                        spriteFrame.w, spriteFrame.h, UIVars.HUD_LINE_W);
                s.setColor(UIVars.ICON_FRAME_BG);
                s.rect(spriteFrame.x + UIVars.HUD_BORDER,
                        spriteFrame.y + UIVars.HUD_BORDER,
                        spriteFrame.w - 2 * UIVars.HUD_BORDER,
                        spriteFrame.h - 2 * UIVars.HUD_BORDER);
            }
        }

        if (optionsOpen) {
            ShapeRenderer s = ctx.shapes;
            s.setColor(0f, 0f, 0f, UIVars.DIM_ALPHA);
            s.rect(0, 0, ctx.worldW(), ctx.worldH());
            s.setColor(0f, 0f, 0f, 1f);
            s.rect(optionsPopup.x, optionsPopup.y,
                    optionsPopup.w, optionsPopup.h);
            Window.drawShape(ctx, optionsPopup.x, optionsPopup.y,
                    optionsPopup.w, optionsPopup.h);
        }
    }

    @Override
    protected void drawBodyText(UiCtx ctx) {
        if (optionsOpen) {
            drawOptionsText();
        } else if (selected != null) {
            drawCharacterPopupText();
        } else {
            drawClassListText();
        }
    }

    private void drawClassListText() {
        TextDraw.centre(ctx, ctx.fontHeader, UIVars.ACCENT, TextCatalog.get("ui.characterSelect.title"),
                window.cx(), window.top() - ctx.fontHeader.getCapHeight() - UIVars.HUD_BORDER - 2f);
    }

    private void drawCharacterPopupText() {
        MobDefinition def = Registries.mob("PLAYER_" + selected.name());
        Mob sample = sampleMob(selected);

        String title = (def != null && def.name != null)
                ? def.name : selected.displayName();
        TextDraw.centreFit(ctx, ctx.fontHeader, UIVars.ACCENT, title,
                charPopup.cx(), charPopup.top() - ctx.fontHeader.getCapHeight() - UIVars.HUD_BORDER - 2f,
                charPopup.w - 28f);

        float lh       = ctx.lineH();
        float left     = charPopup.x + 14f;
        float right    = charPopup.right() - 14f;
        float maxLineW = right - left;
        float guard    = charPopup.y + UIVars.BACK_SIZE + 2 * BackBtn.INSET + 48f + lh;
        // Content starts below the sprite on Summary, below the tab bar on
        // the other tabs (no sprite there, so each list gets the full column
        // - no more truncation).
        float top = (detailTab == DetailTab.SUMMARY)
                ? spriteFrame.y - lh
                : detailTabRects[0].y - 10f - lh;

        switch (detailTab) {
            case SUMMARY -> {
                if (sample != null) {
                    TextureRegion region = MobSprites.regionFor(sample);
                    if (region != null) {
                        drawAspectFit(region, spriteFrame.x, spriteFrame.y,
                                spriteFrame.w, spriteFrame.h);
                    }
                }
                if (def != null && def.description != null && !def.description.isEmpty()) {
                    TextDraw.TextBlock desc = TextDraw.block(ctx.fontRegular,
                            def.description, maxLineW, 8, lh);
                    TextDraw.wrapped(ctx, ctx.fontRegular, UIVars.TEXT_DIM, desc, left, top);
                }
            }
            case STATS -> {
                if (sample != null) {
                    com.bjsp123.rl2.model.StatBlock st = sample.effectiveStats();
                    top = statRow(left, top, TextCatalog.get("ui.characterSelect.hp"),
                            Integer.toString((int) Math.round(st.maxHp)));
                    top = statRow(left, top, TextCatalog.get("ui.characterSelect.acc"),
                            Integer.toString(st.accuracy));
                    top = statRow(left, top, TextCatalog.get("ui.characterSelect.eva"),
                            Integer.toString(st.evasion));
                    top = statRow(left, top, TextCatalog.get("ui.characterSelect.atk"),
                            st.damage.min() + "-" + st.damage.max());
                    top = statRow(left, top, TextCatalog.get("ui.characterSelect.armor"),
                            st.armor.min() + "-" + st.armor.max());
                }
            }
            case ITEMS -> {
                if (def != null && def.startingInventory != null) {
                    for (MobDefinition.StartItem si : def.startingInventory) {
                        if (top < guard) break;
                        String iname = lookupItemName(si.type);
                        String line = (si.count > 1) ? iname + " x" + si.count : iname;
                        TextDraw.TextBlock gear = TextDraw.block(ctx.fontRegular,
                                line, maxLineW, 2, lh);
                        TextDraw.wrapped(ctx, ctx.fontRegular, UIVars.TEXT_BODY, gear, left, top);
                        top -= gear.height();
                    }
                }
            }
            case PERKS -> {
                if (def != null && def.startingPerks != null) {
                    for (com.bjsp123.rl2.logic.MobDefinition.StartPerk sp : def.startingPerks) {
                        if (top < guard) break;
                        String label = sp.level > 1
                                ? sp.perk.displayName() + " +" + (sp.level - 1)
                                : sp.perk.displayName();
                        TextDraw.TextBlock perk = TextDraw.block(ctx.fontRegular,
                                label, maxLineW, 2, lh);
                        TextDraw.wrapped(ctx, ctx.fontRegular, UIVars.TEXT_BODY, perk, left, top);
                        top -= perk.height();
                    }
                }
            }
        }
    }

    private void drawOptionsText() {
        TextDraw.centre(ctx, ctx.fontHeader, UIVars.ACCENT, TextCatalog.get("ui.characterSelect.options"),
                optionsPopup.cx(), optionsPopup.top() - ctx.fontHeader.getCapHeight() - UIVars.HUD_BORDER - 2f);
    }

    private static String lookupItemName(String type) {
        if (type == null) return "?";
        ItemDefinition def = Registries.item(type);
        return (def != null && def.name != null) ? def.name : type;
    }

    private static String onOff(boolean value) {
        return TextCatalog.get(value
                ? "ui.characterSelect.on"
                : "ui.characterSelect.off");
    }

    /** Build (lazily) and cache a sample Mob for the class so we can
     *  read its sprite and effective stats with starter gear applied. */
    private Mob sampleMob(CharacterClass cls) {
        if (cls == null) return null;
        Mob m = sampleMob.get(cls);
        if (m == null) {
            m = MobFactory.player(new Point(0, 0), cls);
            sampleMob.put(cls, m);
        }
        return m;
    }

    private float statRow(float x, float y, String label, String value) {
        float right = charPopup.right() - 14f;
        float gap = 12f;
        float valueW = Math.max(28f, (right - x) * 0.45f);
        TextDraw.leftFit(ctx, ctx.fontRegular, UIVars.TEXT_DIM,
                label, x, y, Math.max(24f, right - x - valueW - gap));
        TextDraw.rightFit(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                value, right, y, valueW);
        return y - ctx.lineH();
    }

    private static final float[] OUTLINE_DX = {
            1f, 0.7071f, 0f, -0.7071f, -1f, -0.7071f, 0f, 0.7071f
    };
    private static final float[] OUTLINE_DY = {
            0f, 0.7071f, 1f, 0.7071f, 0f, -0.7071f, -1f, -0.7071f
    };
    private static final float OUTLINE_W = 1.5f;

    private void drawAspectFit(TextureRegion region,
                               float x, float y, float w, float h) {
        int srcW = region.getRegionWidth();
        int srcH = region.getRegionHeight();
        if (srcW <= 0 || srcH <= 0) return;
        float scale = Math.min(w / srcW, h / srcH);
        float drawW = srcW * scale;
        float drawH = srcH * scale;
        float drawX = x + (w - drawW) * 0.5f;
        float drawY = y + (h - drawH) * 0.5f;
        float oa = com.bjsp123.rl2.ui.skin.Settings.mobOutlineDarkness();
        if (oa > 0f) {
            ctx.batch.setColor(0f, 0f, 0f, oa);
            for (int i = 0; i < OUTLINE_DX.length; i++) {
                ctx.batch.draw(region,
                        drawX + OUTLINE_DX[i] * OUTLINE_W,
                        drawY + OUTLINE_DY[i] * OUTLINE_W,
                        drawW, drawH);
            }
            ctx.batch.setColor(1f, 1f, 1f, 1f);
        }
        ctx.batch.draw(region, drawX, drawY, drawW, drawH);
    }
}
