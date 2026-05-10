package com.bjsp123.rl2.ui.v2;

import com.bjsp123.rl2.Rl2Game;
import com.bjsp123.rl2.model.HallOfFameEntry;

/** V2 game-over screen — full-screen modal shown when the player dies. */
public final class V2GameOver extends V2Screen {

    private final Rl2Game game;
    private final HallOfFameEntry record;
    private final Rect window = new Rect();

    public V2GameOver(Rl2Game game, HallOfFameEntry record) {
        super(game.ui);
        this.game = game;
        this.record = record;
    }

    @Override
    protected void buildLayout() {
        float vw = ctx.worldW();
        float vh = ctx.worldH();
        float winW = Math.min(360f, vw - 24f);
        float winH = Math.min(440f, vh - 100f);
        window.set((vw - winW) * 0.5f, (vh - winH) * 0.5f, winW, winH);

        // Single chunky button at the bottom of the window.
        float btnW = winW - 32f;
        float btnH = 56f;
        Btn home = new Btn("Return to Title",
                window.x + (window.w - btnW) * 0.5f, window.y + 16f,
                btnW, btnH,
                () -> game.setRootScreen(new V2Title(game, ctx))).header();
        buttons.add(home);
    }

    @Override
    protected void drawBodyShape(UiCtx ctx) {
        Window.drawShape(ctx, window.x, window.y, window.w, window.h);
    }

    @Override
    protected void drawBodyText(UiCtx ctx) {
        float cx = window.cx();
        float top = window.top() - 24f;
        TextDraw.centre(ctx, ctx.fontHeader, Pal.WARN, "You died", cx, top);
        top -= 16f;

        // Portrait of the dead character — same sprite the HUD shows. Pulled
        // by parsing the saved class name (record.charClass is the V1 enum's
        // displayName / name string).
        com.bjsp123.rl2.model.Mob.CharacterClass cls = parseClass(record.charClass);
        if (cls != null) {
            var portrait = com.bjsp123.rl2.world.render.PortraitSprites.regionFor(cls);
            if (portrait != null) {
                float pSize = 56f;
                ctx.batch.draw(portrait,
                        cx - pSize * 0.5f, top - pSize, pSize, pSize);
                top -= pSize + 8f;
            }
        } else {
            top -= 32f;
        }

        String summary = record.charClass + "   score " + record.score
                + "   depth " + record.depth;
        TextDraw.centre(ctx, ctx.fontRegular, Pal.WHITE, summary, cx, top);

        top -= 32f;
        TextDraw.centre(ctx, ctx.fontRegular, Pal.DIM, "Equipment", cx, top);
        top -= 22f;
        if (record.equipment.isEmpty()) {
            TextDraw.centre(ctx, ctx.fontRegular, Pal.DIM, "(none)", cx, top);
        } else {
            for (String s : record.equipment) {
                if (top < window.y + 100f) break;
                TextDraw.centre(ctx, ctx.fontRegular, Pal.WHITE, s, cx, top);
                top -= 18f;
            }
        }
    }

    @Override
    protected void onEscape() {
        game.setRootScreen(new V2Title(game, ctx));
    }

    /** Map the saved class string (either the enum's {@code name()} or its
     *  {@code displayName}) back to a {@link com.bjsp123.rl2.model.Mob.CharacterClass}.
     *  Returns {@code null} when the string doesn't match any known class —
     *  the portrait is then suppressed and the layout falls through to the
     *  text-only summary. */
    private static com.bjsp123.rl2.model.Mob.CharacterClass parseClass(String name) {
        if (name == null) return null;
        for (var c : com.bjsp123.rl2.model.Mob.CharacterClass.values()) {
            if (c.displayName.equalsIgnoreCase(name)
                    || c.name().equalsIgnoreCase(name)) {
                return c;
            }
        }
        return null;
    }
}
