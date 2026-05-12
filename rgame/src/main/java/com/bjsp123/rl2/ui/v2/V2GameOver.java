package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.bjsp123.rl2.Rl2Game;
import com.bjsp123.rl2.model.HallOfFameEntry;
import com.bjsp123.rl2.model.Mob.CharacterClass;
import com.bjsp123.rl2.world.render.IconSprites;
import com.bjsp123.rl2.world.render.PortraitSprites;

import java.util.ArrayList;
import java.util.List;

/** V2 game-over screen — shown when the player dies. */
public final class V2GameOver extends V2Screen {

    private static final Color DIM_WARN = new Color(0.9f, 0.3f, 0.3f, 1f);

    private final Rl2Game        game;
    private final HallOfFameEntry record;
    private final V2Log           log;

    // Layout rects — computed once in buildLayout().
    private final Rect window   = new Rect();
    private final Rect portrait = new Rect();
    private float nameY, statsY, deathY;

    public V2GameOver(Rl2Game game, HallOfFameEntry record) {
        super(game.ui);
        this.game   = game;
        this.record = record;
        this.log    = new V2Log(game.ui);
    }

    // ── V2Screen lifecycle ────────────────────────────────────────────────────

    @Override
    public void show() {
        super.show();
        Gdx.input.setInputProcessor(new InputMultiplexer(log.input(), baseInput()));
    }

    @Override
    protected void buildLayout() {
        float vw = ctx.worldW();
        float vh = ctx.worldH();
        float winW = Math.min(340f, vw - 24f);
        float winH = Math.min(460f, vh - 80f);
        float winX = (vw - winW) * 0.5f;
        float winY = (vh - winH) * 0.5f;
        window.set(winX, winY, winW, winH);

        // Portrait box — centred, below "YOU DIED" title.
        float portSz  = 72f;
        float titleH  = 36f;   // space for "YOU DIED" at window top
        float portX   = winX + (winW - portSz) * 0.5f;
        float portTop = winY + winH - titleH - 16f;
        portrait.set(portX, portTop - portSz, portSz, portSz);

        // Text anchor Ys.
        nameY  = portrait.y - 20f;
        statsY = nameY  - 18f;
        deathY = statsY - 18f;

        // Buttons — bottom of window.
        float btnH    = 52f;
        float btnGap  = 8f;
        float iconSz  = 52f;
        float btnY    = winY + 16f;
        float mainW   = winW - iconSz - btnGap - 32f;
        float mainX   = winX + 16f;
        float iconX   = mainX + mainW + btnGap;

        Btn mainMenu = new Btn("Main Menu", mainX, btnY, mainW, btnH,
                () -> game.setRootScreen(new V2Title(game, ctx))).header();
        buttons.add(mainMenu);

        Btn logBtn = new Btn("", iconX, btnY, iconSz, iconSz,
                () -> { log.open(); });
        logBtn.icon = IconSprites.regionFor(IconSprites.Icon.BOOK);
        buttons.add(logBtn);
    }

    @Override
    protected void drawBodyShape(UiCtx ctx) {
        Window.drawShape(ctx, window.x, window.y, window.w, window.h);

        // Portrait frame — a simple recessed rect slightly larger than the portrait.
        float pad = 4f;
        ctx.shapes.setColor(UiColors.SLOT_RECESS);
        ctx.shapes.rect(portrait.x - pad, portrait.y - pad,
                portrait.w + 2f * pad, portrait.h + 2f * pad);
    }

    @Override
    protected void drawBodyText(UiCtx ctx) {
        float cx = window.cx();

        // "YOU DIED" title.
        float titleY = window.top() - 8f;
        TextDraw.centre(ctx, ctx.fontHeader, DIM_WARN, "YOU DIED", cx, titleY);

        // Portrait.
        CharacterClass cls = parseClass(record.charClass);
        if (cls != null) {
            TextureRegion p = PortraitSprites.regionFor(cls);
            if (p != null) {
                ctx.batch.draw(p, portrait.x, portrait.y, portrait.w, portrait.h);
            }
        }

        // Class name.
        TextDraw.centre(ctx, ctx.fontHeader, Pal.WHITE, record.charClass, cx, nameY);

        // Score + depth.
        String stats = "Score: " + record.score + "   Depth: " + record.depth;
        TextDraw.centre(ctx, ctx.fontRegular, UiColors.TEXT_DIM, stats, cx, statsY);

        // Death message — word-wrapped up to 2 lines.
        if (record.deathMessage != null && !record.deathMessage.isEmpty()) {
            float maxW = window.w - 24f;
            List<String> lines = new ArrayList<>();
            TextDraw.wrap(ctx.fontRegular, record.deathMessage, maxW, 2, lines);
            float lineH = ctx.fontRegular.getLineHeight() + 2f;
            float y = deathY;
            for (String line : lines) {
                TextDraw.centre(ctx, ctx.fontRegular, UiColors.TEXT_DIM, line, cx, y);
                y -= lineH;
            }
        }
    }

    @Override
    public void render(float delta) {
        super.render(delta);
        if (log.isOpen()) log.render();
    }

    @Override
    protected void onEscape() {
        game.setRootScreen(new V2Title(game, ctx));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static CharacterClass parseClass(String name) {
        if (name == null) return null;
        for (CharacterClass c : CharacterClass.values()) {
            if (c.displayName.equalsIgnoreCase(name) || c.name().equalsIgnoreCase(name))
                return c;
        }
        return null;
    }
}
