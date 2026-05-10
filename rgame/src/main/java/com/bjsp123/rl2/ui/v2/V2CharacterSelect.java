package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.bjsp123.rl2.Rl2Game;
import com.bjsp123.rl2.logic.ItemDefinition;
import com.bjsp123.rl2.logic.ItemRegistry;
import com.bjsp123.rl2.logic.MobDefinition;
import com.bjsp123.rl2.logic.MobFactory;
import com.bjsp123.rl2.logic.MobRegistry;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Mob.CharacterClass;
import com.bjsp123.rl2.model.Perk;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.screen.PlayScreen;
import com.bjsp123.rl2.util.SeedCode;
import com.bjsp123.rl2.world.render.MobSprites;

/**
 * V2 character-select screen — three big class buttons (Warrior / Rogue /
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
    /** Starting character level — also doubles as the starting dungeon
     *  depth when {@code > 1}. Clamped at build time to
     *  {@code [1, DUNGEON_DEPTH]} since the world only has that many
     *  levels. */
    private int startingLevel = 1;
    /** Seed the player with one of every non-unique item in the registry. */
    private boolean allItems;
    /** Grant +10 perk points on top of the character's normal allowance. */
    private boolean tenPerkPoints;

    private final Rect window       = new Rect();
    private final Rect charPopup    = new Rect();
    private final Rect optionsPopup = new Rect();
    private final Rect spriteFrame  = new Rect();

    /** Cached per-class sample Mob — lets us pull live sprite + stats
     *  without re-rolling each frame. Built lazily; never added to a
     *  level. */
    private final java.util.EnumMap<CharacterClass, Mob> sampleMob
            = new java.util.EnumMap<>(CharacterClass.class);

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
        float winW = Math.min(360f, vw - 24f);
        float winH = Math.min(560f, vh - 120f);
        window.set((vw - winW) * 0.5f, (vh - winH) * 0.5f, winW, winH);

        back   = new BackBtn(ctx, game::popScreen);
        back.anchorBottomRightOf(window);
        burger = makeBurger();
        addBurgerItem("Title",    () -> game.setRootScreen(new V2Title(game, ctx)));
        addBurgerItem("Settings", () -> game.pushScreen(new V2Settings(game, ctx)));

        if (optionsOpen) {
            buildOptionsPopup();
        } else if (selected != null) {
            buildCharacterPopup();
        } else {
            buildClassButtons();
        }
    }

    // ── Layer 1: class buttons ──────────────────────────────────────────
    private void buildClassButtons() {
        float btnW = window.w - 32f;
        float btnH = 64f;
        float gap  = 12f;
        float colH = CLASSES.length * btnH + (CLASSES.length - 1) * gap;
        float yTop = window.cy() + colH * 0.5f - btnH;
        for (int i = 0; i < CLASSES.length; i++) {
            final CharacterClass c = CLASSES[i];
            float y = yTop - i * (btnH + gap);
            buttons.add(new Btn(c.displayName,
                    window.x + 16f, y, btnW, btnH,
                    () -> { selected = c; show(); }).header());
        }
    }

    // ── Layer 2: character detail popup ─────────────────────────────────
    private void buildCharacterPopup() {
        float vw = ctx.worldW();
        float vh = ctx.worldH();
        float pw = Math.min(360f, vw - 24f);
        float ph = Math.min(560f, vh - 80f);
        charPopup.set((vw - pw) * 0.5f, (vh - ph) * 0.5f, pw, ph);

        // Sprite frame — encyclopaedia-style, sized to 2× source max
        // with a 64-px floor.
        Mob sample = sampleMob(selected);
        TextureRegion region = sample != null ? MobSprites.regionFor(sample) : null;
        int srcMax = 32;
        if (region != null) {
            srcMax = Math.max(region.getRegionWidth(), region.getRegionHeight());
        }
        float frameSz = Math.max(64f, srcMax * 2f);
        spriteFrame.set(charPopup.cx() - frameSz * 0.5f,
                charPopup.top() - 70f - frameSz, frameSz, frameSz);

        // Bottom-row buttons: a square SETTINGS-icon Options button on
        // the left, and a wide PLAY button filling the rest. The square
        // options button gives PLAY visual primacy while keeping the
        // settings affordance discoverable via the standard UI icon.
        float btnH = 48f;
        float btnGap = 12f;
        float optionsW = btnH;                              // square
        float playW = pw - 2 * 14f - optionsW - btnGap;
        float btnY = charPopup.y + 14f;
        Btn optionsBtn = new Btn("",
                charPopup.x + 14f, btnY, optionsW, btnH,
                () -> { optionsOpen = true; show(); });
        optionsBtn.icon = com.bjsp123.rl2.world.render.IconSprites
                .regionFor(com.bjsp123.rl2.world.render.IconSprites.Icon.SETTINGS);
        buttons.add(optionsBtn);
        buttons.add(new Btn("PLAY",
                charPopup.x + 14f + optionsW + btnGap, btnY, playW, btnH,
                this::launchGame).header());
    }

    // ── Layer 3: pre-game options popup ────────────────────────────────
    private void buildOptionsPopup() {
        float vw = ctx.worldW();
        float vh = ctx.worldH();
        float pw = Math.min(300f, vw - 32f);
        float ph = Math.min(420f, vh - 80f);
        optionsPopup.set((vw - pw) * 0.5f, (vh - ph) * 0.5f, pw, ph);

        float pad   = 16f;
        float btnH  = 44f;
        float gap   = 10f;
        float btnW  = pw - 2 * pad;
        float yTop  = optionsPopup.top() - 56f - btnH;

        String godLabel = "God Mode: " + (godMode ? "ON" : "OFF");
        buttons.add(new Btn(godLabel, optionsPopup.x + pad, yTop, btnW, btnH,
                () -> { godMode = !godMode; show(); }));
        yTop -= btnH + gap;

        String seedLabel = customSeed != null
                ? "Seed: " + SeedCode.encode(customSeed)
                : "Seed: random";
        buttons.add(new Btn(seedLabel, optionsPopup.x + pad, yTop, btnW, btnH,
                this::promptForSeed));
        yTop -= btnH + gap;

        int maxStartLevel = Math.max(1,
                com.bjsp123.rl2.logic.GameBalance.DUNGEON_DEPTH);
        String levelLabel = "Starting level: " + startingLevel;
        buttons.add(new Btn(levelLabel, optionsPopup.x + pad, yTop, btnW, btnH,
                () -> {
                    startingLevel = (startingLevel % maxStartLevel) + 1;
                    show();
                }));
        yTop -= btnH + gap;

        String itemsLabel = "All items: " + (allItems ? "ON" : "OFF");
        buttons.add(new Btn(itemsLabel, optionsPopup.x + pad, yTop, btnW, btnH,
                () -> { allItems = !allItems; show(); }));
        yTop -= btnH + gap;

        String perksLabel = "+10 perk points: " + (tenPerkPoints ? "ON" : "OFF");
        buttons.add(new Btn(perksLabel, optionsPopup.x + pad, yTop, btnW, btnH,
                () -> { tenPerkPoints = !tenPerkPoints; show(); }));

        buttons.add(new Btn("Done", optionsPopup.x + pad,
                optionsPopup.y + pad, btnW, btnH,
                () -> { optionsOpen = false; show(); }).header());
    }

    @Override
    protected void onEscape() {
        if (optionsOpen)       { optionsOpen = false; show(); return; }
        if (selected != null)  { selected = null;     show(); return; }
        super.onEscape();
    }

    // ── Game launch ─────────────────────────────────────────────────────
    private void launchGame() {
        if (selected == null) return;
        if (game.currentPlay != null) {
            game.currentPlay.dispose();
            game.currentPlay = null;
        }
        game.saveSystem.clear(slot);
        game.setRootScreen(new PlayScreen(game, slot, selected,
                customSeed, godMode, startingLevel, allItems, tenPerkPoints));
    }

    // ── Seed entry ──────────────────────────────────────────────────────
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
                    // Blank → random.
                    customSeed = null;
                    show();
                } else if (SeedCode.isValid(s)) {
                    customSeed = SeedCode.decode(s);
                    show();
                }
                // Invalid non-empty input: silently leave current value.
            }
            @Override public void canceled() { /* leave current */ }
        }, "Seed (blank for random)", prefill, "e.g. AB12CD");
    }

    // ── Render passes ───────────────────────────────────────────────────
    @Override
    protected void drawBodyShape(UiCtx ctx) {
        Window.drawShape(ctx, window.x, window.y, window.w, window.h);

        if (selected != null) {
            // Dim + opaque-backed character popup.
            ShapeRenderer s = ctx.shapes;
            s.setColor(0f, 0f, 0f, Pal.DIM_ALPHA);
            s.rect(0, 0, ctx.worldW(), ctx.worldH());
            s.setColor(0f, 0f, 0f, 1f);
            s.rect(charPopup.x, charPopup.y, charPopup.w, charPopup.h);
            Window.drawShape(ctx,
                    charPopup.x, charPopup.y, charPopup.w, charPopup.h);

            // Sprite frame chrome.
            Edges.drawTriLine(s, spriteFrame.x, spriteFrame.y,
                    spriteFrame.w, spriteFrame.h, Pal.HUD_LINE_W);
            s.setColor(UiColors.ICON_FRAME_BG);
            s.rect(spriteFrame.x + Pal.HUD_BORDER,
                    spriteFrame.y + Pal.HUD_BORDER,
                    spriteFrame.w - 2 * Pal.HUD_BORDER,
                    spriteFrame.h - 2 * Pal.HUD_BORDER);
        }

        if (optionsOpen) {
            ShapeRenderer s = ctx.shapes;
            s.setColor(0f, 0f, 0f, Pal.DIM_ALPHA);
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
        TextDraw.centre(ctx, ctx.fontHeader, Pal.ACCENT, "Choose Class",
                window.cx(), window.top() - 22f);
    }

    private void drawCharacterPopupText() {
        MobDefinition def = MobRegistry.get("PLAYER_" + selected.name());
        Mob sample = sampleMob(selected);

        String title = (def != null && def.name != null)
                ? def.name : selected.displayName;
        TextDraw.centre(ctx, ctx.fontHeader, Pal.ACCENT, title,
                charPopup.cx(), charPopup.top() - 22f);

        // Aspect-fit the mob sprite inside the frame, with the same
        // 8-radial silhouette outline encyclopaedia entries use.
        if (sample != null) {
            TextureRegion region = MobSprites.regionFor(sample);
            if (region != null) {
                drawAspectFit(region,
                        spriteFrame.x, spriteFrame.y,
                        spriteFrame.w, spriteFrame.h);
            }
        }

        float left  = charPopup.x + 14f;
        float right = charPopup.right() - 14f;
        float top   = spriteFrame.y - 22f;
        float lineH = 14f;
        float maxLineW = right - left;

        if (def != null && def.description != null && !def.description.isEmpty()) {
            top = drawWrapped(def.description, left, top, maxLineW, lineH,
                    /*maxLines=*/3, UiColors.TEXT_DIM);
            top -= 6f;
        }

        if (sample != null) {
            com.bjsp123.rl2.model.StatBlock st = sample.effectiveStats();
            top = statRow(left, top, "HP",    Integer.toString((int) Math.round(st.maxHp)));
            top = statRow(left, top, "Acc",   Integer.toString(st.accuracy));
            top = statRow(left, top, "Eva",   Integer.toString(st.evasion));
            top = statRow(left, top, "Atk",   st.damage.min() + "-" + st.damage.max());
            top = statRow(left, top, "Armor", st.armor.min() + "-" + st.armor.max());
            top -= 6f;
        }

        if (def != null && def.startingInventory != null
                && !def.startingInventory.isEmpty()) {
            TextDraw.left(ctx, ctx.fontRegular, UiColors.TEXT_DIM,
                    "Gear", left, top);
            top -= 14f;
            for (MobDefinition.StartItem si : def.startingInventory) {
                if (top < charPopup.y + 80f) break;
                String iname = lookupItemName(si.type);
                String line = (si.count > 1)
                        ? "  " + iname + " x" + si.count
                        : "  " + iname;
                TextDraw.left(ctx, ctx.fontRegular, Pal.WHITE,
                        line, left, top);
                top -= 14f;
            }
            top -= 4f;
        }

        if (def != null && def.startingPerks != null
                && !def.startingPerks.isEmpty()) {
            TextDraw.left(ctx, ctx.fontRegular, UiColors.TEXT_DIM,
                    "Perks", left, top);
            top -= 14f;
            for (Perk p : def.startingPerks) {
                if (top < charPopup.y + 80f) break;
                TextDraw.left(ctx, ctx.fontRegular, Pal.WHITE,
                        "  " + p.displayName(), left, top);
                top -= 14f;
            }
        }
    }

    private void drawOptionsText() {
        TextDraw.centre(ctx, ctx.fontHeader, Pal.ACCENT, "Options",
                optionsPopup.cx(), optionsPopup.top() - 22f);
    }

    private static String lookupItemName(String type) {
        if (type == null) return "?";
        ItemDefinition def = ItemRegistry.get(type);
        return (def != null && def.name != null) ? def.name : type;
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
        TextDraw.left (ctx, ctx.fontRegular, UiColors.TEXT_DIM,
                label, x, y);
        TextDraw.right(ctx, ctx.fontRegular, UiColors.TEXT_BODY,
                value, charPopup.right() - 14f, y);
        return y - 16f;
    }

    private float drawWrapped(String text, float left, float top,
                              float maxWidth, float lineH, int maxLines,
                              com.badlogic.gdx.graphics.Color colour) {
        if (text == null || text.isEmpty()) return top;
        int maxChars = Math.max(8, (int) (maxWidth / 6f));
        int p = 0;
        int lines = 0;
        while (p < text.length() && lines < maxLines) {
            int end = Math.min(p + maxChars, text.length());
            if (end < text.length()) {
                int sp = text.lastIndexOf(' ', end);
                if (sp > p) end = sp;
            }
            TextDraw.left(ctx, ctx.fontRegular, colour,
                    text.substring(p, end).trim(), left, top - lines * lineH);
            p = end + 1;
            lines++;
        }
        return top - lines * lineH;
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
        float oa = com.bjsp123.rl2.ui.skin.MobOutline.darkness();
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
