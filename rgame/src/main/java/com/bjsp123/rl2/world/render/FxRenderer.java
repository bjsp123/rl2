package com.bjsp123.rl2.world.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.world.render.Effect.EffectTint;
import com.bjsp123.rl2.world.render.Effect.EffectType;

/**
 * Animations, FX, and timing — every {@link Effect}-driven visual the renderer paints.
 * Held by {@link DefaultLevelRenderer} (one instance per renderer); reads its
 * {@link SpriteBatch}, {@link BitmapFont}, white-pixel {@link TextureRegion}, and the
 * two optional painted-fire sheets through constructor injection. The whole class is
 * read-only against game state — every draw method takes whatever it needs in args.
 *
 * <p>Owns the rendering for every {@link EffectType}: floating text, thrown items,
 * particle burst, explosion, magic missile, fire particle, sleep Z, teleport streaks,
 * light mote, attack flash, ray, blast, buff icon. Plus the per-tile painted-fire
 * machinery (used both for FIRE vegetation tiles and for the burning-mob overlay) and
 * the burning-mob overlay itself.
 */
final class FxRenderer {

    private static final int CELL = 16;
    private static final int ENTITY_Y_OFFSET = 2;

    /** Wall-clock duration of one painted-fire frame, in milliseconds. */
    private static final int  FIRE_FRAME_MS      = 90;
    private static final int  FIRE_SHEET_FRAME_W = 32;
    private static final int  FIRE_SHEET_FRAME_H = 48;
    private static final int  FIRE_SHEET_FRAMES  = 8;
    /** On-screen draw size of one painted fire — smaller than the cell so a tile can
     *  carry a PAIR of flames at random per-tile positions. Preserves 32:48 source
     *  aspect at 12×18. */
    private static final int  FIRE_DISPLAY_W     = 12;
    private static final int  FIRE_DISPLAY_H     = 18;

    /** Constant downward acceleration applied to PARTICLE_BURST particles, in pixels/frame². */
    private static final float PARTICLE_GRAVITY = 0.32f;
    /** Pixel size of each particle (square). */
    private static final float PARTICLE_SIZE    = 1.5f;

    /** Cached tint colours so the inner draw loop doesn't allocate. */
    private static final Color TINT_BLUE   = new Color(0.4f, 0.6f, 1f, 1f);
    private static final Color TINT_BROWN  = new Color(0.55f, 0.34f, 0.16f, 1f);
    private static final Color TINT_ORANGE = new Color(1f, 0.55f, 0.1f, 1f);

    private final SpriteBatch batch;
    private final BitmapFont font;
    private final TextureRegion whiteRegion;
    /** Two painted 8-frame fire animation sheets — each 256×48 (8 frames of 32×48). Each
     *  FIRE tile picks one of the two by hash for visual variety. Optional: if a file is
     *  missing, {@link #drawFireAt} draws nothing — there is no procedural fallback. */
    private final Texture fire1Tex;
    private final Texture fire2Tex;

    FxRenderer(SpriteBatch batch, BitmapFont font, TextureRegion whiteRegion,
               Texture fire1Tex, Texture fire2Tex) {
        this.batch = batch;
        this.font = font;
        this.whiteRegion = whiteRegion;
        this.fire1Tex = fire1Tex;
        this.fire2Tex = fire2Tex;
    }

    /**
     * Render one effect. FLOATING_TEXT and PARTICLE_BURST check the visibility of their
     * source cell (dark-room effects stay hidden); THROWN_ITEM and MAGIC_MISSILE intentionally
     * ignore FOV so the player can see their own projectiles crossing dark tiles.
     */
    void drawEffect(Level level, Effect effect) {
        int ex = effect.location.tileX(), ey = effect.location.tileY();
        if (ex < 0 || ey < 0 || ex >= level.width || ey >= level.height) return;
        if (effect.type == EffectType.FLOATING_TEXT) {
            if (!level.visible[ex][ey]) return;
            float yOffset = effect.frame / 5f;
            font.setColor(tintToColor(effect.tint, Color.YELLOW));
            font.draw(batch, effect.text, ex * CELL, ey * CELL + CELL * 2 + yOffset);
        } else if (effect.type == EffectType.THROWN_ITEM) {
            drawThrownItem(effect);
        } else if (effect.type == EffectType.PARTICLE_BURST) {
            if (!level.visible[ex][ey]) return;
            drawParticleBurst(effect);
        } else if (effect.type == EffectType.EXPLOSION) {
            if (!level.visible[ex][ey]) return;
            drawExplosion(effect);
        } else if (effect.type == EffectType.MAGIC_MISSILE) {
            drawMagicMissile(effect);
        } else if (effect.type == EffectType.FIRE_PARTICLE) {
            if (!level.visible[ex][ey]) return;
            drawFireParticle(effect);
        } else if (effect.type == EffectType.SLEEP_Z) {
            if (!level.visible[ex][ey]) return;
            drawSleepZ(effect);
        } else if (effect.type == EffectType.TELEPORT_STREAKS) {
            if (!level.visible[ex][ey]) return;
            drawTeleportStreaks(effect);
        } else if (effect.type == EffectType.LIGHT_MOTE) {
            if (!level.visible[ex][ey]) return;
            drawLightMote(effect);
        } else if (effect.type == EffectType.ATTACK_FLASH) {
            if (!level.visible[ex][ey]) return;
            drawAttackFlash(effect);
        } else if (effect.type == EffectType.RAY) {
            // Rays draw across the whole length regardless of FOV — the player should
            // always see their own beam clear out a ghost even if the destination tile
            // is at the edge of vision.
            drawRay(effect);
        } else if (effect.type == EffectType.BLAST) {
            if (!level.visible[ex][ey]) return;
            drawBlast(effect);
        } else if (effect.type == EffectType.BUFF_ICON) {
            if (!level.visible[ex][ey]) return;
            drawBuffIcon(effect);
        }
    }

    /**
     * Draw the flame at tile {@code (x, y)}. Each tile picks one of the painted fire
     * sheets by hash for variety and animates through the 8 frames at
     * {@link #FIRE_FRAME_MS} per frame, with a per-tile phase offset so adjacent fires
     * don't flicker in lockstep. If neither sheet loaded, draws nothing.
     */
    void drawFireAt(int x, int y) {
        if (fire1Tex == null && fire2Tex == null) return;
        int seedA = (x * 73856093) ^ (y * 19349663);
        int seedB = seedA ^ 0x55555555;
        drawPaintedFireFlame(x, y, seedA, /*centerX*/ CELL / 4);
        drawPaintedFireFlame(x, y, seedB, /*centerX*/ 3 * CELL / 4);
    }

    private void drawPaintedFireFlame(int x, int y, int seed, int centerX) {
        drawPaintedFireFlamePx(seed, x * (float) CELL + centerX, y * (float) CELL);
    }

    private void drawPaintedFireFlamePx(int seed, float centerXpx, float baseYpx) {
        Texture sheet = pickPaintedFireSheetFromSeed(seed);
        if (sheet == null) return;
        int phase = Math.floorMod(seed >>> 4, FIRE_SHEET_FRAMES);
        int frame = (int) Math.floorMod(System.currentTimeMillis() / FIRE_FRAME_MS + phase,
                                        FIRE_SHEET_FRAMES);
        int jitterX = Math.floorMod(seed >>> 8, 3) - 1;
        int spanY   = Math.max(1, CELL / 4);
        int dy = Math.floorMod(seed >>> 16, spanY);
        TextureRegion region = new TextureRegion(
                sheet, frame * FIRE_SHEET_FRAME_W, 0,
                FIRE_SHEET_FRAME_W, FIRE_SHEET_FRAME_H);
        float drawX = centerXpx - FIRE_DISPLAY_W / 2f + jitterX;
        float drawY = baseYpx + dy;
        batch.setColor(Color.WHITE);
        batch.draw(region, drawX, drawY, FIRE_DISPLAY_W, FIRE_DISPLAY_H);
    }

    private Texture pickPaintedFireSheetFromSeed(int seed) {
        if (fire1Tex == null && fire2Tex == null) return null;
        if (fire1Tex == null) return fire2Tex;
        if (fire2Tex == null) return fire1Tex;
        return ((seed >>> 12) & 1) == 0 ? fire1Tex : fire2Tex;
    }

    /** Overlay two painted-fire flames at the mob's pixel position so a burning mob is
     *  visually on fire. Uses the same flame sheets and per-frame animation cadence as
     *  fire-tile rendering — but the centre x is the mob's tile centre (with anim/lift
     *  offsets applied) so the flame follows the mob's step interpolation. */
    void drawFireOnMob(Mob mob, int mx, int my, float ox, float oy) {
        long frameId = Gdx.graphics.getFrameId();
        int seedA = (mx * 73856093) ^ (my * 19349663) ^ ((int) (frameId / 6));
        int seedB = seedA ^ 0x55555555;
        float centerXpx = mx * CELL + CELL / 2f + ox;
        float baseYpx   = my * CELL + ENTITY_Y_OFFSET + oy;
        drawPaintedFireFlamePx(seedA, centerXpx - CELL / 4f, baseYpx);
        drawPaintedFireFlamePx(seedB, centerXpx + CELL / 4f, baseYpx);
    }

    /** Render a FIRE_PARTICLE effect — a single rising ember spawned by
     *  {@link com.bjsp123.rl2.logic.FireSystem} from each burning tile on a regular cadence. */
    private void drawFireParticle(Effect e) {
        if (e.particleX0 == null || e.particleY0 == null || e.particleVX == null) return;
        if (e.particleX0.length < 1) return;
        int total = e.totalFrames();
        float t = e.frame;
        float lifeT = total > 0 ? t / (float) total : 1f;
        if (lifeT > 1f) lifeT = 1f;
        float baseX = e.location.tileX() * (float) CELL;
        float baseY = e.location.tileY() * (float) CELL;
        float ipPhase = e.particleX0[0];
        float ipAmp   = e.particleY0[0];
        float ipDrift = e.particleVX[0];
        float px = baseX + CELL / 2f
                + ipDrift * lifeT * 9f
                + (float) Math.sin(ipPhase + t * 0.3f) * ipAmp * 1.4f;
        float py = baseY + 2f + t * 0.6f;
        float alpha = lifeT < 0.33f ? 1f : Math.max(0f, 1f - (lifeT - 0.33f) / 0.67f);
        Color c;
        if (lifeT < 0.3f) {
            float k = lifeT / 0.3f;
            c = new Color(1f, 1f, 1f - 0.6f * k, alpha);
        } else if (lifeT < 0.65f) {
            float k = (lifeT - 0.3f) / 0.35f;
            c = new Color(1f - 0.05f * k, 1f - 0.7f * k, 0.4f - 0.3f * k, alpha);
        } else {
            float k = (lifeT - 0.65f) / 0.35f;
            c = new Color(0.95f - 0.5f * k, 0.30f - 0.22f * k, 0.10f - 0.05f * k, alpha);
        }
        batch.setColor(c);
        batch.draw(whiteRegion, px - 1.5f, py - 1.5f, 3f, 3f);
        batch.setColor(1f, 1f, 1f, alpha);
        batch.draw(whiteRegion, px - 0.5f, py - 0.5f, 1f, 1f);
        batch.setColor(Color.WHITE);
    }

    /** Render a single SLEEP_Z effect — a "Z" rising out of a sleeping mob's tile. */
    private void drawSleepZ(Effect e) {
        if (e == null || e.location == null || font == null) return;
        int total = e.totalFrames();
        if (total <= 0) return;
        float t = e.frame / (float) total;
        if (t < 0f) t = 0f; else if (t > 1f) t = 1f;
        float baseX = e.location.tileX() * (float) CELL;
        float baseY = e.location.tileY() * (float) CELL;
        float jitterX = (e.particleX0 != null && e.particleX0.length > 0) ? e.particleX0[0] : 0f;
        float phase   = (e.particleY0 != null && e.particleY0.length > 0) ? e.particleY0[0] : 0f;
        float swayX   = (float) Math.sin(phase + t * (float) (Math.PI * 2)) * 1.5f;
        float wx = baseX + (CELL - 4f) / 2f + jitterX + swayX;
        float wy = baseY + CELL + 2f + t * CELL;
        float alpha = 1f - t;
        if (alpha <= 0f) return;
        float scale = 0.5f + 1.7f * t;
        font.getData().setScale(scale);
        font.setColor(1f, 1f, 1f, alpha);
        font.draw(batch, "Z", wx, wy);
        font.getData().setScale(1f);
        font.setColor(Color.WHITE);
    }

    /** Concussive single-tile flash for the blast bomb. */
    private void drawBlast(Effect e) {
        int total = e.totalFrames();
        if (total <= 0) return;
        float lifeT = Math.min(1f, e.frame / (float) total);
        float baseX = e.location.tileX() * (float) CELL;
        float baseY = e.location.tileY() * (float) CELL;
        float reach = CELL * 0.5f * (0.4f + 1.4f * lifeT);
        float alpha = lifeT < 0.5f ? 1f : Math.max(0f, 1f - (lifeT - 0.5f) / 0.5f);
        if (alpha <= 0f) return;
        float cx = baseX + CELL * 0.5f;
        float cy = baseY + CELL * 0.5f;
        batch.setColor(1f, 0.95f, 0.55f, alpha);
        batch.draw(whiteRegion, cx - reach, cy + reach - 1f, reach * 2f, 1f);
        batch.draw(whiteRegion, cx - reach, cy - reach,      reach * 2f, 1f);
        batch.draw(whiteRegion, cx - reach,      cy - reach, 1f, reach * 2f);
        batch.draw(whiteRegion, cx + reach - 1f, cy - reach, 1f, reach * 2f);
        if (lifeT < 0.5f) {
            batch.setColor(1f, 1f, 1f, alpha);
            batch.draw(whiteRegion, cx - 1f, cy - 1f, 2f, 2f);
        }
        batch.setColor(Color.WHITE);
    }

    /** Floating buff-icon — same rising motion as floating text, but renders the buff's
     *  sprite from {@link BuffIcons}. Falls back to the carried {@link Effect#text}
     *  when the sheet didn't load. */
    private void drawBuffIcon(Effect e) {
        int total = e.totalFrames();
        if (total <= 0) return;
        float lifeT = Math.min(1f, e.frame / (float) total);
        float alpha = lifeT < 0.65f ? 1f : Math.max(0f, 1f - (lifeT - 0.65f) / 0.35f);
        if (alpha <= 0f) return;
        com.bjsp123.rl2.model.Buff.BuffType type = e.buffType;
        if (type == null) return;
        TextureRegion icon = BuffIcons.regionFor(type);
        float baseX = e.location.tileX() * (float) CELL;
        float baseY = e.location.tileY() * (float) CELL;
        float yOffset = e.frame / 5f;
        if (icon == null) {
            font.setColor(1f, 0.9f, 0.5f, alpha);
            font.draw(batch, e.text == null ? "+" : e.text, baseX, baseY + CELL * 2f + yOffset);
            font.setColor(Color.WHITE);
            return;
        }
        float iconSize = 14f;
        float ix = baseX + CELL * 0.5f - iconSize * 0.5f;
        float iy = baseY + CELL * 1.6f + yOffset;
        batch.setColor(1f, 1f, 1f, alpha);
        batch.draw(icon, ix, iy, iconSize, iconSize);
        batch.setColor(Color.WHITE);
    }

    /** Render a banishment-style ray as a thick bright line. */
    private void drawRay(Effect e) {
        if (e.endLocation == null) return;
        float t     = e.frame;
        int   total = e.totalFrames();
        if (total <= 0) return;
        float lifeT = Math.min(1f, t / (float) total);
        float alpha = lifeT < 0.4f ? 1f : Math.max(0f, 1f - (lifeT - 0.4f) / 0.6f);
        if (alpha <= 0f) return;
        float x1 = e.location.tileX()    * (float) CELL + CELL * 0.5f;
        float y1 = e.location.tileY()    * (float) CELL + CELL * 0.5f;
        float x2 = e.endLocation.tileX() * (float) CELL + CELL * 0.5f;
        float y2 = e.endLocation.tileY() * (float) CELL + CELL * 0.5f;
        float dx = x2 - x1, dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-3f) return;
        float angle = (float) Math.toDegrees(Math.atan2(dy, dx));
        Color tint = tintToColor(e.tint, Color.WHITE);
        batch.setColor(tint.r, tint.g, tint.b, alpha * 0.35f);
        batch.draw(whiteRegion,
                x1, y1 - 3f, 0f, 3f,
                len, 6f, 1f, 1f, angle);
        batch.setColor(1f, 1f, 1f, alpha);
        batch.draw(whiteRegion,
                x1, y1 - 1f, 0f, 1f,
                len, 2f, 1f, 1f, angle);
        batch.setColor(Color.WHITE);
    }

    /** Render one LIGHT_MOTE — a soft pale spark drifting up from a light source. */
    private void drawLightMote(Effect e) {
        if (e.particleX0 == null || e.particleX0.length == 0) return;
        int total = e.totalFrames();
        if (total <= 0) return;
        float lifeT = Math.min(1f, e.frame / (float) total);
        float phase    = e.particleX0[0];
        float jitterX  = e.particleY0[0];
        float speed    = e.particleVX != null && e.particleVX.length > 0 ? e.particleVX[0] : 1f;
        float baseX = e.location.tileX() * (float) CELL;
        float baseY = e.location.tileY() * (float) CELL;
        float swayX = (float) Math.sin(phase + lifeT * 6f) * 1.2f;
        float px = baseX + CELL / 2f + jitterX + swayX;
        float py = baseY + CELL * 0.5f + speed * lifeT * 12f;
        float alpha = lifeT < 0.3f
                ? 0.55f * (lifeT / 0.3f)
                : 0.55f * (1f - (lifeT - 0.3f) / 0.7f);
        if (alpha <= 0f) return;
        batch.setColor(1f, 0.95f, 0.7f, alpha);
        batch.draw(whiteRegion, px - 1f, py - 1f, 2f, 2f);
        batch.setColor(1f, 1f, 0.9f, alpha);
        batch.draw(whiteRegion, px - 0.5f, py - 0.5f, 1f, 1f);
        batch.setColor(Color.WHITE);
    }

    /** Render a teleport streak burst — green vertical streaks moving purely along y. */
    private void drawTeleportStreaks(Effect e) {
        if (e.particleX0 == null) return;
        float t     = e.frame;
        int   total = e.totalFrames();
        float alpha = t < total / 2f
                ? 1f
                : Math.max(0f, 1f - (t - total / 2f) / (total / 2f));
        float baseX = e.location.tileX() * (float) CELL;
        float baseY = e.location.tileY() * (float) CELL;
        Color c = tintToColor(e.tint, Color.GREEN);
        batch.setColor(c.r, c.g, c.b, alpha);
        float streakW = 1f;
        float streakH = 4f;
        for (int i = 0; i < e.particleX0.length; i++) {
            float dy = e.particleVY[i] * t;
            float px = baseX + e.particleX0[i];
            float py = baseY + e.particleY0[i] + dy;
            batch.draw(whiteRegion, px, py, streakW, streakH);
        }
        batch.setColor(Color.WHITE);
    }

    /** Render an EXPLOSION effect — particles flying outward at constant velocity. */
    private void drawExplosion(Effect e) {
        if (e.particleX0 == null) return;
        float t = e.frame;
        int total = e.totalFrames();
        if (total <= 0) return;
        float lifeT = Math.min(1f, t / (float) total);
        float alpha = lifeT < 0.4f ? 1f : Math.max(0f, 1f - (lifeT - 0.4f) / 0.6f);
        if (alpha <= 0f) return;
        float rChan = 1f;
        float gChan = Math.max(0.1f, 0.85f - lifeT * 0.7f);
        float bChan = Math.max(0f,   0.45f * (1f - lifeT * 1.4f));
        batch.setColor(rChan, gChan, bChan, alpha);
        float baseX = e.location.tileX() * (float) CELL;
        float baseY = e.location.tileY() * (float) CELL;
        for (int i = 0; i < e.particleX0.length; i++) {
            float dx = e.particleVX[i] * t;
            float dy = e.particleVY[i] * t;
            float px = baseX + e.particleX0[i] + dx;
            float py = baseY + e.particleY0[i] + dy;
            float size = 2.5f * (1f - 0.5f * lifeT);
            batch.draw(whiteRegion, px - size * 0.5f, py - size * 0.5f, size, size);
        }
        batch.setColor(Color.WHITE);
    }

    /** Integrate each particle from its stored initial state and draw with gravity. */
    private void drawParticleBurst(Effect e) {
        if (e.particleX0 == null) return;
        float t = e.frame;
        int total = EffectType.PARTICLE_BURST.frameCount;
        float lifeFrac = 1f - Math.max(0f, t - total / 2f) / (total / 2f);
        float alpha = Math.max(0f, Math.min(1f, lifeFrac));
        float baseX = e.location.tileX() * (float) CELL;
        float baseY = e.location.tileY() * (float) CELL;
        Color c = tintToColor(e.tint, Color.WHITE);
        batch.setColor(c.r, c.g, c.b, alpha);
        for (int i = 0; i < e.particleX0.length; i++) {
            float dx = e.particleVX[i] * t;
            float dy = e.particleVY[i] * t - 0.5f * PARTICLE_GRAVITY * t * t;
            float px = baseX + e.particleX0[i] + dx;
            float py = baseY + e.particleY0[i] + dy;
            batch.draw(whiteRegion, px, py, PARTICLE_SIZE, PARTICLE_SIZE);
        }
        batch.setColor(Color.WHITE);
    }

    /** Head + trail rendering for a magic missile in flight. */
    private void drawMagicMissile(Effect e) {
        if (e.endLocation == null || e.particleX0 == null) return;
        int total = e.totalFrames();
        int flightEnd = Math.max(1, (int) Math.round(total * 0.7));
        float headT = Math.min(1f, e.frame / (float) flightEnd);
        float sx = (e.location.tileX()    + 0.5f) * CELL;
        float sy = (e.location.tileY()    + 0.5f) * CELL;
        float dx = (e.endLocation.tileX() + 0.5f) * CELL;
        float dy = (e.endLocation.tileY() + 0.5f) * CELL;
        float hx = sx + (dx - sx) * headT;
        float hy = sy + (dy - sy) * headT;

        Color headColor = tintToColor(e.headTint, Color.WHITE);
        if (e.frame <= flightEnd) {
            batch.setColor(headColor.r, headColor.g, headColor.b, 1f);
            batch.draw(whiteRegion, hx - 1.5f, hy - 1.5f, 3f, 3f);
        }

        int trailLife = 14;
        float gravity = e.particleGravity;
        float size    = e.particleSize > 0 ? e.particleSize : 1.5f;
        EffectTint[] palette = e.particleTints;
        for (int i = 0; i < e.particleX0.length; i++) {
            int spawn = e.particleSpawnFrame[i];
            int age = e.frame - spawn;
            if (age < 0 || age >= trailLife) continue;
            float spawnT = Math.min(1f, spawn / (float) flightEnd);
            float bx = sx + (dx - sx) * spawnT + e.particleX0[i];
            float by = sy + (dy - sy) * spawnT + e.particleY0[i];
            float xPhase = (i * 1.737f) + (spawn * 0.193f);
            float yPhase = (i * 2.341f) + (spawn * 0.271f);
            float driftX = (float) Math.sin(age * 0.55f + xPhase) * 1.6f;
            float driftY = (float) Math.cos(age * 0.61f + yPhase) * 1.6f;
            float px = bx + e.particleVX[i] * age + driftX;
            float py = by + e.particleVY[i] * age - 0.5f * gravity * age * age + driftY;
            float alpha = 1f - age / (float) trailLife;
            EffectTint pt = (palette != null && palette.length > 0)
                    ? palette[i % palette.length]
                    : e.tint;
            Color c = tintToColor(pt, Color.WHITE);
            if (e.particleBright) {
                batch.setColor(c.r, c.g, c.b, alpha * 0.5f);
                batch.draw(whiteRegion, px - size / 2f, py - size / 2f, size * 2f, size * 2f);
                batch.setColor(c.r, c.g, c.b, alpha);
                batch.draw(whiteRegion, px, py, size, size);
            } else {
                batch.setColor(c.r, c.g, c.b, alpha);
                batch.draw(whiteRegion, px, py, size, size);
            }
        }
        batch.setColor(Color.WHITE);
    }

    /** Brief attack-flash sprite next to a swinging mob. */
    private void drawAttackFlash(Effect e) {
        TextureRegion region = BuffIcons.attackFlashRegion(e.spriteCol);
        if (region == null) return;
        int f = e.frame;
        float alpha;
        if (f <= 1) alpha = 1f;
        else if (f == 2) alpha = 0f;
        else if (f <= 4) alpha = 1f;
        else if (f <= 16) alpha = 1f - (f - 5) / 12f;
        else return;
        if (alpha <= 0f) return;
        float drawSize = CELL;
        float yBase = e.location.tileY() * CELL + CELL * 0.25f;
        float xLeft  = e.location.tileX() * CELL - CELL * 0.5f;
        float xRight = e.location.tileX() * CELL + CELL * 0.75f;
        float drawX = e.facesRight ? xRight : xLeft;
        batch.setColor(1f, 1f, 1f, alpha);
        if (e.facesRight) {
            batch.draw(region, drawX + drawSize, yBase, -drawSize, drawSize);
        } else {
            batch.draw(region, drawX, yBase, drawSize, drawSize);
        }
        batch.setColor(Color.WHITE);
    }

    /** Interpolate the flying item along its tile path and draw it spinning. */
    private void drawThrownItem(Effect e) {
        if (e.endLocation == null) return;
        if (e.thrownItem == null) return;
        TextureRegion region = ItemSprites.regionFor(e.thrownItem);
        if (region == null) return;
        float drawW = CELL, drawH = CELL;
        int frames = e.totalFrames();
        float t = frames <= 1 ? 1f : e.frame / (float) (frames - 1);
        float sx = (e.location.tileX()    + 0.5f) * CELL;
        float sy = (e.location.tileY()    + 0.5f) * CELL;
        float dx = (e.endLocation.tileX() + 0.5f) * CELL;
        float dy = (e.endLocation.tileY() + 0.5f) * CELL;
        float px = sx + (dx - sx) * t;
        float py = sy + (dy - sy) * t + (float) Math.sin(Math.PI * t) * (CELL * 0.5f);
        float rotation = t * 360f;
        batch.setColor(Color.WHITE);
        batch.draw(region,
                px - drawW / 2f, py - drawH / 2f,
                drawW / 2f, drawH / 2f,
                drawW, drawH,
                1f, 1f,
                rotation);
    }

    /** Map an {@link EffectTint} to a libGDX {@link Color}. */
    private static Color tintToColor(EffectTint t, Color fallback) {
        if (t == null) return fallback;
        return switch (t) {
            case RED    -> Color.RED;
            case YELLOW -> Color.YELLOW;
            case WHITE  -> Color.WHITE;
            case GREEN  -> Color.GREEN;
            case BLUE   -> TINT_BLUE;
            case BROWN  -> TINT_BROWN;
            case ORANGE -> TINT_ORANGE;
        };
    }
}
