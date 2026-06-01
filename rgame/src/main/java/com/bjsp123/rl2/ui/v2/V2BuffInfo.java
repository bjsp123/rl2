package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.bjsp123.rl2.logic.BuffSystem;
import com.bjsp123.rl2.logic.TextCatalog;
import com.bjsp123.rl2.model.Buff;
import com.bjsp123.rl2.world.render.BuffIcons;

import java.util.ArrayList;
import java.util.List;

/**
 * Small modal popup showing the details of a single active buff: a large
 * icon, the buff name with level, its flavour description, and how many
 * turns remain.  Opened by tapping a buff icon in the HUD or any info
 * screen; closed by tapping outside or pressing Back/Escape.
 */
public final class V2BuffInfo extends BasePopup {

    private Buff buff;

    private final List<String> descLines = new ArrayList<>();

    public V2BuffInfo(UiCtx ctx) { super(ctx); }

    public void open(Buff b) {
        this.buff = b;
        open();
    }

    @Override
    protected boolean canRender() { return buff != null; }

    @Override
    protected void layoutRects() {
        float vw = ctx.worldW();
        float vh = ctx.worldH();
        float winW = Math.min(240f, vw - 32f);

        descLines.clear();
        String desc = BuffSystem.description(buff.type);
        if (desc != null && !desc.isEmpty()) {
            TextDraw.wrap(ctx.fontRegular, desc, winW - 28f, 3, descLines);
        }

        float winH = Math.min(180f + descLines.size() * 18f, vh - 80f);
        window.set((vw - winW) * 0.5f, (vh - winH) * 0.5f, winW, winH);
    }

    @Override
    protected void renderShapesPass() {
        beginModalShapes();
        drawScrim();
        drawWindow();
        // Extra fully-opaque fill under the body region so the description /
        // effect / turns text stays legible even when the world backdrop is
        // busy under the window. Window chrome alone uses PANEL_FILL_ALPHA
        // (~0.85) which can let texture bleed through on bright tiles.
        com.badlogic.gdx.graphics.glutils.ShapeRenderer s = ctx.shapes;
        s.setColor(UIVars.WIN_BG.r, UIVars.WIN_BG.g, UIVars.WIN_BG.b, 1f);
        float inset = UIVars.WIN_BORDER + 4f;
        s.rect(window.x + inset, window.y + inset,
                window.w - 2 * inset, window.h - 2 * inset);
        endModalShapes();
    }

    @Override
    protected void renderTextPass() {
        ctx.batch.begin();

        float iconSz = 48f;
        float top = window.top() - 16f;
        float iconY = top - iconSz;

        TextureRegion region = BuffIcons.regionFor(buff.type);
        if (region != null) {
            ctx.batch.draw(region, window.cx() - iconSz * 0.5f, iconY, iconSz, iconSz);
        }

        top = iconY - ctx.lineH();
        TextDraw.centreFit(ctx, ctx.fontHeader, UIVars.ACCENT,
                BuffSystem.displayName(buff.type), window.cx(), top,
                window.w - 28f);

        top -= ctx.headerLineH();
        TextDraw.centre(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                TextCatalog.format("buff.info.level",
                        TextCatalog.vars("level", buff.level)), window.cx(), top);

        // Level-resolved "Effect: ..." line - shows what the buff is doing
        // numerically at the current level (e.g. HASTED 3 -> "moves 49%
        // faster"). Empty for buffs whose effect is purely qualitative.
        String effect = BuffSystem.describeEffectAtLevel(buff.type, buff.level);
        if (effect != null && !effect.isEmpty()) {
            top -= ctx.lineH();
            TextDraw.centre(ctx, ctx.fontRegular, UIVars.ACCENT,
                    TextCatalog.format("buff.info.effect",
                            TextCatalog.vars("effect", effect)),
                    window.cx(), top);
        }

        top -= ctx.lineH() * 1.5f;
        for (String line : descLines) {
            TextDraw.centre(ctx, ctx.fontRegular, UIVars.TEXT_DIM, line, window.cx(), top);
            top -= ctx.lineH();
        }

        top -= 6f;
        int displayTurns = com.bjsp123.rl2.logic.BuffSystem.displayTurns(buff.durationTicks);
        String durStr = displayTurns > 0
                ? TextCatalog.format("buff.info.turns",
                        TextCatalog.vars("turns", displayTurns))
                : TextCatalog.get("buff.info.permanent");
        TextDraw.centre(ctx, ctx.fontRegular, UIVars.TEXT_BODY, durStr, window.cx(), top);

        ctx.batch.end();
    }

    public InputProcessor input() {
        return simpleDismissInput();
    }
}
