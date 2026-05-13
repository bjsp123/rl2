package com.bjsp123.rl2.ui.v2;

import com.bjsp123.rl2.Rl2Game;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Tile;

import java.util.ArrayList;
import java.util.List;

/**
 * V2 level-info screen — fact sheet for the level the player is currently on.
 * Modal full-screen window with a vertical stack of label/value rows.
 */
public final class V2LevelInfo extends V2Screen {

    private final Rl2Game game;
    private final Runnable onBack;
    private final Level level;
    private final Rect window = new Rect();
    private final List<String> rows = new ArrayList<>();

    public V2LevelInfo(Rl2Game game, UiCtx ctx, Runnable onBack, Level level) {
        super(ctx);
        this.game   = game;
        this.onBack = onBack;
        this.level  = level;
    }

    @Override
    protected Rect modalWindow() { return window; }

    @Override
    protected void buildLayout() {
        float vw = ctx.worldW();
        float vh = ctx.worldH();
        float winW = Math.min(360f, vw - UIVars.PAD_MODAL);
        float winH = Math.min(540f, vh - 120f);
        window.set((vw - winW) * 0.5f, (vh - winH) * 0.5f, winW, winH);

        rows.clear();
        if (level != null) {
            rows.add("Depth     " + level.depth);
            rows.add("Size      " + level.width + "x" + level.height);
            rows.add("Layout    " + level.layout);
            rows.add("Theme     " + level.theme);

            int floor = 0, wall = 0, chasm = 0, door = 0;
            for (int x = 0; x < level.width; x++) {
                for (int y = 0; y < level.height; y++) {
                    Tile t = level.tiles[x][y];
                    if (t == null) continue;
                    switch (t) {
                        case FLOOR, FLOOR_WOOD, FLOOR_SPECIAL -> floor++;
                        case WALL                              -> wall++;
                        case CHASM                             -> chasm++;
                        case DOOR, DOOR_OPEN                   -> door++;
                        default -> {}
                    }
                }
            }
            rows.add("Floors    " + floor);
            rows.add("Walls     " + wall);
            rows.add("Chasms    " + chasm);
            rows.add("Doors     " + door);
            rows.add("Mobs      " + (level.mobs == null ? 0 : level.mobs.size()));
            rows.add("Items     " + (level.items == null ? 0 : level.items.size()));
        } else {
            rows.add("(no level loaded)");
        }

        back = new BackBtn(ctx, onBack);
        burger = makeBurger();
        addStandardBurgerItems(game);
    }

    @Override
    protected void drawBodyShape(UiCtx ctx) {
        Window.drawShape(ctx, window.x, window.y, window.w, window.h);
    }

    @Override
    protected void drawBodyText(UiCtx ctx) {
        float top = window.top() - ctx.headerLineH();
        TextDraw.centre(ctx, ctx.fontHeader, UIVars.ACCENT, "Level Info",
                window.cx(), top);
        top -= 50f;
        for (String s : rows) {
            if (top < window.y + 16f) break;
            TextDraw.left(ctx, ctx.fontRegular, UIVars.TEXT_BODY,
                    s, window.x + UIVars.PAD_CONTENT, top);
            top -= ctx.lineH();
        }
    }

    @Override
    protected void onEscape() { onBack.run(); }
}
