package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.bjsp123.rl2.GameStartTipsRegistry;
import com.bjsp123.rl2.GameStartTipsRegistry.GameStartTip;
import com.bjsp123.rl2.logic.ItemFactory;
import com.bjsp123.rl2.logic.MobFactory;
import com.bjsp123.rl2.logic.Registries;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.world.render.BuffIcons;
import com.bjsp123.rl2.world.render.ItemSprites;
import com.bjsp123.rl2.world.render.MobSprites;
import com.bjsp123.rl2.world.render.TileSprites;

import java.util.ArrayList;
import java.util.List;

/**
 * Modal "Game Start Tip" window shown between the world-graph fly-in and the
 * level zoom-in on a new game. Loaded from {@code assets/data/tip.csv} via
 * {@link GameStartTipsRegistry}. A random tip is picked on {@link #show};
 * the player can browse with next/prev arrows. Any click outside the arrow
 * hit-rects dismisses (delegated to {@link Runnable} supplied to {@link #show}).
 *
 * <p>Body text supports {@code \n} (line break) and {@code *emphasis*}
 * (rendered in accent colour).
 */
public final class V2GameStartTipPopup {

    private static final float PANEL_W_RATIO     = 0.78f;
    private static final float PANEL_W_MAX       = 560f;
    private static final float PANEL_PAD         = 16f;
    private static final float TITLE_GAP         = 10f;
    private static final float SPRITE_ROW_GAP    = 12f;
    private static final float SPRITE_CELL       = 56f;
    private static final float SPRITE_GAP        = 8f;
    private static final float ARROW_SIZE        = 40f;
    private static final float ARROW_PAD         = 8f;
    private static final int   BODY_MAX_LINES    = 24;
    /** Caption (item-name) row metrics under each sprite. */
    private static final float CAPTION_GAP       = 4f;
    private static final float CAPTION_SCALE     = 0.8f;
    /** Width of an inline {@link #ARROW_RIGHT} connector cell. */
    private static final float ARROW_CELL_W      = 44f;
    /** Special spritelist token: render a rightward arrow instead of an item. */
    private static final String ARROW_RIGHT      = "ARROWRIGHT";
    /** Special spritelist token: render the beacon ornament (terrain, not an item). */
    private static final String BEACON           = "BEACON";
    private static final Point  DUMMY_POS        = new Point(0, 0);

    private final UiCtx ctx;
    private final List<GameStartTip> tips = new ArrayList<>();
    private int index;
    /** Wall-clock accumulator driving the gentle powerup-glow pulse. */
    private float glowTime;
    private boolean visible;
    private Runnable onDismiss;

    // Hit rects refreshed each renderSelf() pass.
    private final Rect panel = new Rect();
    private final Rect prevBtn = new Rect();
    private final Rect nextBtn = new Rect();

    public V2GameStartTipPopup(UiCtx ctx) {
        this.ctx = ctx;
    }

    public boolean isVisible() { return visible; }

    /** Show a random tip from the registry. No-op when the registry is empty. */
    public boolean show(java.util.Random rng, Runnable onDismiss) {
        tips.clear();
        tips.addAll(GameStartTipsRegistry.tips());
        if (tips.isEmpty()) return false;
        this.onDismiss = onDismiss;
        this.index = rng == null ? 0 : rng.nextInt(tips.size());
        this.visible = true;
        return true;
    }

    public void hide() {
        visible = false;
        tips.clear();
        onDismiss = null;
    }

    /** Process a click in viewport coords. Returns true if the popup
     *  consumed the click (always true while visible). Arrow taps cycle;
     *  any other tap dismisses. */
    public boolean handleClick(float vx, float vy) {
        if (!visible) return false;
        if (tips.size() > 1 && prevBtn.contains(vx, vy)) {
            index = (index - 1 + tips.size()) % tips.size();
            return true;
        }
        if (tips.size() > 1 && nextBtn.contains(vx, vy)) {
            index = (index + 1) % tips.size();
            return true;
        }
        Runnable cb = onDismiss;
        hide();
        if (cb != null) cb.run();
        return true;
    }

    public void renderSelf() {
        if (!visible || tips.isEmpty()) return;
        GameStartTip tip = tips.get(index);
        glowTime += com.badlogic.gdx.Gdx.graphics.getDeltaTime();

        float vw = ctx.worldW();
        float vh = ctx.worldH();
        float panelW = Math.min(vw * PANEL_W_RATIO, PANEL_W_MAX);
        float bodyW = panelW - 2 * PANEL_PAD;

        // Resolve the sprite row into cells: item sprites (with captions) plus
        // any ARROWRIGHT connector glyphs. Unknown items are skipped.
        List<TipCell> cells = new ArrayList<>();
        for (String ref : tip.spriteRefs) {
            TipCell c = resolveCell(ref);
            if (c != null) cells.add(c);
        }

        // Per-cell widths sized to fit the (smaller) caption, and caption metrics.
        boolean anyCaption = false;
        float prevSX = ctx.fontRegular.getData().scaleX;
        float prevSY = ctx.fontRegular.getData().scaleY;
        ctx.fontRegular.getData().setScale(CAPTION_SCALE);
        float capLineH = ctx.fontRegular.getLineHeight();
        for (TipCell c : cells) {
            if (c.arrow) continue;
            anyCaption = true;
            ctx.layout.setText(ctx.fontRegular, c.caption);
            c.width = Math.max(SPRITE_CELL, ctx.layout.width);
        }
        ctx.fontRegular.getData().setScale(prevSX, prevSY);

        // Lay out content top-down to compute the panel height.
        float titleH = ctx.fontHeader.getLineHeight();
        float captionH = anyCaption ? (CAPTION_GAP + capLineH) : 0f;
        float spriteH = cells.isEmpty() ? 0f : SPRITE_CELL + captionH + SPRITE_ROW_GAP;
        List<String> bodyLines = wrappedBody(tip.text, bodyW);
        float lineH = ctx.fontRegular.getLineHeight() + 1f;
        float bodyH = lineH * Math.max(1, bodyLines.size());
        boolean showArrows = tips.size() > 1;
        float arrowsH = showArrows ? ARROW_SIZE + ARROW_PAD : 0f;
        float contentH = titleH + TITLE_GAP + spriteH + bodyH + arrowsH;
        float panelH = contentH + 2 * PANEL_PAD;

        float panelX = (vw - panelW) * 0.5f;
        float panelY = (vh - panelH) * 0.5f;
        panel.set(panelX, panelY, panelW, panelH);

        // Lay out the cell row (centred) and stamp each cell's sprite rect, so the
        // shape pass (arrows) and batch pass (sprites/glows) share one geometry.
        float rowW = 0f;
        for (int i = 0; i < cells.size(); i++) {
            rowW += cells.get(i).width;
            if (i > 0) rowW += SPRITE_GAP;
        }
        float spriteTop = panelY + panelH - PANEL_PAD - titleH - TITLE_GAP;
        float spriteSy  = spriteTop - SPRITE_CELL;
        float cxRow = panelX + (panelW - rowW) * 0.5f;
        for (TipCell c : cells) {
            c.rect.set(cxRow, spriteSy, c.width, SPRITE_CELL);
            cxRow += c.width + SPRITE_GAP;
        }

        // Shape pass: drop-shadow, fill, border, nav arrows, ARROWRIGHT glyphs.
        ctx.applyProjection();
        com.badlogic.gdx.Gdx.gl.glEnable(GL20.GL_BLEND);
        com.badlogic.gdx.Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        ctx.shapes.begin(ShapeRenderer.ShapeType.Filled);
        ctx.shapes.setColor(0f, 0f, 0f, 0.5f);
        ctx.shapes.rect(panelX + UIVars.SHADOW_OFFSET,
                panelY - UIVars.SHADOW_OFFSET, panelW, panelH);
        // Semi-transparent fill so the swirling intro backdrop shows through the
        // game-start tip (this popup floats over the swirl, not an opaque screen).
        Color bg = UIVars.INFO_WIN_BG;
        ctx.shapes.setColor(bg.r, bg.g, bg.b, 0.82f);
        ctx.shapes.rect(panelX, panelY, panelW, panelH);
        Color border = UIVars.INFO_RULE;
        ctx.shapes.setColor(border.r, border.g, border.b, 1f);
        ctx.shapes.rect(panelX,            panelY,            panelW, 1f);
        ctx.shapes.rect(panelX,            panelY + panelH-1, panelW, 1f);
        ctx.shapes.rect(panelX,            panelY,            1f,     panelH);
        ctx.shapes.rect(panelX + panelW-1, panelY,            1f,     panelH);

        // Nav arrows (drawn as small chevron rects). Pure shape so they live
        // inside the same Filled batch.
        if (showArrows) {
            float ay = panelY + PANEL_PAD;
            float lx = panelX + PANEL_PAD;
            float rx = panelX + panelW - PANEL_PAD - ARROW_SIZE;
            prevBtn.set(lx, ay, ARROW_SIZE, ARROW_SIZE);
            nextBtn.set(rx, ay, ARROW_SIZE, ARROW_SIZE);
            drawArrow(prevBtn, /*pointsLeft=*/true);
            drawArrow(nextBtn, /*pointsLeft=*/false);
        }
        // Inline ARROWRIGHT connectors in the sprite row.
        for (TipCell c : cells) {
            if (c.arrow) drawRightArrow(c.rect);
        }

        ctx.shapes.end();
        com.badlogic.gdx.Gdx.gl.glDisable(GL20.GL_BLEND);

        // Text + sprite pass.
        ctx.batch.begin();

        // Title with "Tip:" prefix in accent colour.
        Color accent = UIVars.ACCENT;
        Color body   = UIVars.TEXT_BODY;
        float yCursor = panelY + panelH - PANEL_PAD;
        String fullTitle = "Tip: " + tip.title;
        ctx.fontHeader.setColor(accent.r, accent.g, accent.b, 1f);
        ctx.fontHeader.draw(ctx.batch, fullTitle, panelX + PANEL_PAD, yCursor);
        ctx.fontHeader.setColor(Color.WHITE);
        yCursor -= titleH + TITLE_GAP;

        // Sprite cells: glowing halo behind powerups (as in-world), then the
        // static atlas sprite centred in its cell.
        for (TipCell c : cells) {
            if (c.arrow) continue;
            if (c.item != null && c.item.useBehavior == Item.UseBehavior.POWERUP) {
                drawPowerupGlow(c);
            }
            drawCellSprite(c);
        }
        // Captions (small font) centred under each sprite.
        if (anyCaption) {
            ctx.fontRegular.getData().setScale(CAPTION_SCALE);
            for (TipCell c : cells) {
                if (c.arrow || c.caption == null) continue;
                // yTop = just below the sprite; the caption draws downward from here.
                float yTop = c.rect.y - CAPTION_GAP;
                TextDraw.centre(ctx, ctx.fontRegular, body, c.caption, c.rect.cx(), yTop);
            }
            ctx.fontRegular.getData().setScale(prevSX, prevSY);
            ctx.fontRegular.setColor(Color.WHITE);
        }
        yCursor -= spriteH;

        // Body text - each wrapped line, emphasis runs in accent colour.
        for (String line : bodyLines) {
            drawEmphasizedLine(line, panelX + PANEL_PAD, yCursor, body, accent);
            yCursor -= lineH;
        }
        ctx.fontRegular.setColor(Color.WHITE);

        ctx.batch.end();
    }

    /** Additive glow halo behind a POWERUP cell, matching the in-world look
     *  ({@link BuffIcons#powerupGlowRegion}). Sized to the tip's sprite cell. */
    private void drawPowerupGlow(TipCell c) {
        TextureRegion glow = BuffIcons.powerupGlowRegion(c.item);
        if (glow == null) return;
        float[] rgb = BuffIcons.powerupGlowColor(c.item);
        float pulse = 0.45f + 0.15f * (float) Math.sin(glowTime * 2f);
        float gx = c.rect.cx();
        float gy = c.rect.y + SPRITE_CELL * 0.5f;
        ctx.batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
        float outer = SPRITE_CELL * 1.8f;
        ctx.batch.setColor(rgb[0], rgb[1], rgb[2], 0.55f * pulse);
        ctx.batch.draw(glow, gx - outer * 0.5f, gy - outer * 0.5f, outer, outer);
        float inner = SPRITE_CELL * 1.2f;
        ctx.batch.setColor(rgb[0], rgb[1], rgb[2], 0.85f * pulse);
        ctx.batch.draw(glow, gx - inner * 0.5f, gy - inner * 0.5f, inner, inner);
        ctx.batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        ctx.batch.setColor(Color.WHITE);
    }

    /** Filled rightward arrow (shaft + head) centred in {@code r}, accent colour.
     *  Drawn in the shape pass for the {@link #ARROW_RIGHT} connector token. */
    private void drawRightArrow(Rect r) {
        Color a = UIVars.ACCENT;
        ctx.shapes.setColor(a.r, a.g, a.b, 0.95f);
        float cx = r.cx(), cy = r.cy();
        float aw = r.w * 0.32f;
        float st = r.h * 0.14f;
        float hw = r.w * 0.22f;
        float hh = r.h * 0.26f;
        ctx.shapes.rect(cx - aw, cy - st * 0.5f, 2f * aw - hw, st);
        ctx.shapes.triangle(cx + aw, cy, cx + aw - hw, cy - hh, cx + aw - hw, cy + hh);
    }

    /** Resolve one spritelist token into a row cell, or {@code null} to skip it.
     *  Order: ARROWRIGHT connector, BEACON ornament (terrain), item type, mob
     *  type. Items/mobs caption with their display name; powerups also glow. */
    private TipCell resolveCell(String ref) {
        if (ARROW_RIGHT.equals(ref)) {
            TipCell c = new TipCell();
            c.arrow = true;
            c.width = ARROW_CELL_W;
            return c;
        }
        if (BEACON.equals(ref)) {
            TextureRegion r = TileSprites.beacon(Level.VisualTheme.CRYSTAL);
            if (r == null) return null;
            TipCell c = new TipCell();
            c.region = r;
            c.caption = "beacon";
            return c;
        }
        if (Registries.itemTypes().contains(ref)) {
            Item it = ItemFactory.build(ref);
            TextureRegion r = it == null ? null : ItemSprites.regionFor(it);
            if (r != null) {
                TipCell c = new TipCell();
                c.region = r;
                c.item = it;
                c.caption = it.name != null ? it.name : ref;
                return c;
            }
        }
        if (Registries.mobTypes().contains(ref)) {
            Mob m = MobFactory.spawn(ref, DUMMY_POS);
            TextureRegion r = m == null ? null : MobSprites.regionFor(m);
            if (r != null) {
                TipCell c = new TipCell();
                c.region = r;
                c.caption = (m.name != null && !m.name.isEmpty()) ? m.name : ref;
                return c;
            }
        }
        return null;
    }

    /** Draw a cell's sprite aspect-fit into the square sprite cell (centred), so
     *  square item/mob art and the tall 1x2 beacon ornament both render cleanly. */
    private void drawCellSprite(TipCell c) {
        float rw = c.region.getRegionWidth();
        float rh = c.region.getRegionHeight();
        float scale = SPRITE_CELL / Math.max(rw, rh);
        float dw = rw * scale, dh = rh * scale;
        float dx = c.rect.cx() - dw * 0.5f;
        float dy = c.rect.y + (SPRITE_CELL - dh) * 0.5f;
        ctx.batch.draw(c.region, dx, dy, dw, dh);
    }

    /** One entry in a tip's sprite row: an item/mob/beacon sprite (with caption)
     *  or an {@link #ARROW_RIGHT} connector. */
    private static final class TipCell {
        boolean arrow;
        TextureRegion region;
        Item item;
        String caption;
        float width;
        final Rect rect = new Rect();
    }

    private void drawArrow(Rect r, boolean pointsLeft) {
        Color a = UIVars.ACCENT;
        ctx.shapes.setColor(a.r, a.g, a.b, 0.85f);
        // Background fill
        ctx.shapes.rect(r.x, r.y, r.w, r.h);
        // Inset triangle painted with the panel bg so the arrow reads as a
        // chevron against the accent fill.
        Color bg = UIVars.INFO_WIN_BG;
        ctx.shapes.setColor(bg.r, bg.g, bg.b, 1f);
        float cx = r.x + r.w * 0.5f;
        float cy = r.y + r.h * 0.5f;
        float w  = r.w * 0.35f;
        float h  = r.h * 0.45f;
        if (pointsLeft) {
            ctx.shapes.triangle(cx - w, cy, cx + w*0.4f, cy - h, cx + w*0.4f, cy + h);
        } else {
            ctx.shapes.triangle(cx + w, cy, cx - w*0.4f, cy - h, cx - w*0.4f, cy + h);
        }
    }

    private List<String> wrappedBody(String body, float maxBodyW) {
        List<String> out = new ArrayList<>();
        if (body == null) return out;
        // tip.csv stores \n as the literal two characters "\n" so editors
        // don't need multi-line cells; split on both that escape and a real
        // newline so quoted multi-line cells also work.
        String[] segs = body.split("\\\\n|\\r?\\n");
        for (String seg : segs) {
            List<String> wrapped = new ArrayList<>();
            TextDraw.wrap(ctx.fontRegular, stripEmphasis(seg),
                    maxBodyW, BODY_MAX_LINES, wrapped);
            out.addAll(wrapped);
        }
        return out;
    }

    private static String stripEmphasis(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.replace("*", "");
    }

    private void drawEmphasizedLine(String line, float x, float yBaseline,
                                    Color body, Color accent) {
        if (line == null || line.isEmpty()) return;
        float cursor = x;
        int i = 0;
        boolean emph = false;
        StringBuilder run = new StringBuilder();
        while (i <= line.length()) {
            char c = (i < line.length()) ? line.charAt(i) : '\0';
            if (i == line.length() || c == '*') {
                if (run.length() > 0) {
                    Color col = emph ? accent : body;
                    String s = run.toString();
                    ctx.fontRegular.setColor(col.r, col.g, col.b, 1f);
                    ctx.fontRegular.draw(ctx.batch, s, cursor, yBaseline);
                    ctx.layout.setText(ctx.fontRegular, s);
                    cursor += ctx.layout.width;
                    run.setLength(0);
                }
                if (c == '*') emph = !emph;
            } else {
                run.append(c);
            }
            i++;
        }
        ctx.fontRegular.setColor(Color.WHITE);
    }
}
