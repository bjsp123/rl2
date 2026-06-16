package com.bjsp123.rl2.world.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.bjsp123.rl2.world.anim.AnimationVars;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Tile;
import com.bjsp123.rl2.world.render.Effect.EffectTint;
// MobSprites is in this same package, no explicit import needed but
// referenced by drawFallingMob.
import com.bjsp123.rl2.world.render.Effect.EffectType;

/**
 * Animations, FX, and timing - every {@link Effect}-driven visual the renderer paints.
 * Held by {@link DefaultLevelRenderer} (one instance per renderer); reads its
 * {@link SpriteBatch}, {@link BitmapFont}, white-pixel {@link TextureRegion}, and the
 * two optional painted-fire sheets through constructor injection. The whole class is
 * read-only against game state - every draw method takes whatever it needs in args.
 *
 * <p>Owns the rendering for every {@link EffectType}: floating text, thrown items,
 * particle burst, explosion, magic missile, fire particle, sleep Z, teleport streaks,
 * light mote, attack flash, ray, blast, buff icon. Plus the per-tile painted-fire
 * machinery (used both for FIRE vegetation tiles and for the burning-mob overlay) and
 * the burning-mob overlay itself.
 */
final class FxRenderer {

    private static final int CELL = 16;
    private static final int ENTITY_Y_OFFSET = 4;

    private static final int  FIRE_SHEET_FRAME_W = 32;
    private static final int  FIRE_SHEET_FRAME_H = 48;
    private static final int  FIRE_SHEET_FRAMES  = 8;
    /** On-screen draw size of one painted fire - smaller than the cell so a tile can
     *  carry a PAIR of flames at random per-tile positions. Preserves 32:48 source
     *  aspect at 12x18. */
    private static final int  FIRE_DISPLAY_W     = 12;
    private static final int  FIRE_DISPLAY_H     = 18;

    /** Cached tint colours so the inner draw loop doesn't allocate. */
    private static final Color TINT_BLUE   = new Color(0.4f, 0.6f, 1f, 1f);
    private static final Color TINT_BROWN  = new Color(0.55f, 0.34f, 0.16f, 1f);
    private static final Color TINT_ORANGE = new Color(1f, 0.55f, 0.1f, 1f);
    private static final Color TINT_PINK   = new Color(1f, 0.35f, 0.65f, 1f);
    private static final Color TINT_MAUVE  = new Color(0.72f, 0.48f, 0.78f, 1f);
    /** POWERUP_FLASH cycle: dim grey -> bright white -> warm gold. */
    private static final Color TINT_GREY   = new Color(0.55f, 0.55f, 0.55f, 1f);
    private static final Color TINT_GOLD   = new Color(1.0f, 0.85f, 0.25f, 1f);

    private final SpriteBatch batch;
    private final BitmapFont font;
    private final TextureRegion whiteRegion;
    /** Two painted 8-frame fire animation sheets - each 256x48 (8 frames of 32x48). Each
     *  FIRE tile picks one of the two by hash for visual variety. Optional: if a file is
     *  missing, {@link #drawFireAt} draws nothing - there is no procedural fallback. */
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
        // Effects with a pending start delay are parked - EffectStage.tick is still
        // counting down the delay; nothing should render yet.
        if (effect.startDelay > 0) return;
        int ex = effect.location.tileX(), ey = effect.location.tileY();
        if (ex < 0 || ey < 0 || ex >= level.width || ey >= level.height) return;
        if (effect.type == EffectType.FLOATING_TEXT) {
            if (!level.visible[ex][ey]) return;
            float yOffset = effect.frame / 5f;
            font.setColor(tintToColor(effect.tint, Color.YELLOW));
            font.draw(batch, effect.text, ex * CELL, ey * CELL + CELL * 2 + yOffset);
        } else if (effect.type == EffectType.THROWN_ITEM
                || effect.type == EffectType.LOOT_TOSS) {
            drawThrownItem(effect);
        } else if (effect.type == EffectType.PICKUP_TOSS) {
            drawPickupToss(effect);
        } else if (effect.type == EffectType.PARTICLE_BURST) {
            if (!effect.ignoresFov && !level.visible[ex][ey]) return;
            drawParticleBurst(effect);
        } else if (effect.type == EffectType.EXPLOSION) {
            if (!effect.ignoresFov && !level.visible[ex][ey]) return;
            drawExplosion(effect);
        } else if (effect.type == EffectType.MAGIC_MISSILE) {
            drawMagicMissile(effect);
        } else if (effect.type == EffectType.PHYSICAL_MISSILE) {
            drawPhysicalMissile(effect);
        } else if (effect.type == EffectType.FIRE_PARTICLE) {
            if (!level.visible[ex][ey]) return;
            drawFireParticle(effect);
        } else if (effect.type == EffectType.SLEEP_Z) {
            if (!level.visible[ex][ey]) return;
            drawSleepZ(effect);
        } else if (effect.type == EffectType.TELEPORT_STREAKS) {
            if (!level.visible[ex][ey]) return;
            drawTeleportStreaks(effect);
        } else if (effect.type == EffectType.ITEM_BIRTH) {
            if (!effect.ignoresFov && !level.visible[ex][ey]) return;
            drawItemBirth(effect);
        } else if (effect.type == EffectType.LIGHT_MOTE) {
            if (!level.visible[ex][ey]) return;
            drawLightMote(effect);
        } else if (effect.type == EffectType.HEARTH_SPARK) {
            if (!level.visible[ex][ey]) return;
            drawHearthSpark(effect);
        } else if (effect.type == EffectType.INWARD_SPIRAL) {
            if (!level.visible[ex][ey]) return;
            drawInwardSpiral(effect);
        } else if (effect.type == EffectType.SCROLL_CAST) {
            drawScrollCast(effect);
        } else if (effect.type == EffectType.LEVEL_FLICKER
                || effect.type == EffectType.ENCHANT_SHOWCASE) {
            // Screen-space; rendered once per frame by drawScreenSpaceEffects.
            return;
        } else if (effect.type == EffectType.ATTACK_FLASH) {
            if (!level.visible[ex][ey]) return;
            drawAttackFlash(effect);
        } else if (effect.type == EffectType.KNOCKBACK_FLASH) {
            if (!level.visible[ex][ey]) return;
            drawKnockbackFlash(effect);
        } else if (effect.type == EffectType.SURPRISE_ICON) {
            if (!level.visible[ex][ey]) return;
            drawSurpriseIcon(effect);
        } else if (effect.type == EffectType.DUST_CLOUD) {
            if (!level.visible[ex][ey]) return;
            drawDustCloud(level, effect);
        } else if (effect.type == EffectType.CLOUD_PUFF) {
            if (!level.visible[ex][ey]) return;
            drawCloudPuff(effect);
        } else if (effect.type == EffectType.RAY) {
            // Rays draw across the whole length regardless of FOV - the player should
            // always see their own beam clear out a ghost even if the destination tile
            // is at the edge of vision.
            drawRay(effect);
        } else if (effect.type == EffectType.GRAPPLE_ROPE) {
            // Same FOV rule as the player's own ray - the rope should
            // always be visible to its caster.
            drawGrappleRope(effect);
        } else if (effect.type == EffectType.BLAST) {
            if (!level.visible[ex][ey]) return;
            drawBlast(effect);
        } else if (effect.type == EffectType.BUFF_ICON) {
            // Drawn ABOVE the fog in drawScreenSpaceEffects so a buff icon rising over
            // an unexplored tile to the north isn't clipped by the fog overlay (RL-44).
            return;
        } else if (effect.type == EffectType.DAMAGE_FLOATER) {
            if (!level.visible[ex][ey]) return;
            drawDamageFloater(effect);
        } else if (effect.type == EffectType.FALLING_ITEM) {
            if (!level.visible[ex][ey]) return;
            drawFallingItem(effect);
        } else if (effect.type == EffectType.FALLING_MOB) {
            if (!level.visible[ex][ey]) return;
            drawFallingMob(effect);
        } else if (effect.type == EffectType.UP_ARROW) {
            if (!level.visible[ex][ey]) return;
            drawUpArrow(effect);
        } else if (effect.type == EffectType.POWERUP_FLASH) {
            if (!level.visible[ex][ey]) return;
            drawPowerupFlash(effect);
        }
    }

    /**
     * Draw the flame at tile {@code (x, y)}. Each tile picks one of the painted fire
     * sheets by hash for variety and animates through the 8 frames at
     * {@link #AnimationVars.FIRE_FRAME_MS} per frame, with a per-tile phase offset so adjacent fires
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
        int frame = (int) Math.floorMod(System.currentTimeMillis() / AnimationVars.FIRE_FRAME_MS + phase,
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
     *  fire-tile rendering - but the centre x is the mob's tile centre (with anim/lift
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

    /** Render a FIRE_PARTICLE effect - a single rising ember spawned by
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

    /** Render a single SLEEP_Z effect - a "Z" rising out of a sleeping mob's tile. */
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
        // Concussive blast flash - additive so the shock wave reads as
        // light pulsing outward, not a flat translucent rectangle leaving
        // a white fringe on lit floor.
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
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
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
    }

    /** Element-aware damage floater - rising icon-then-text pair. Icon comes
     *  from {@link BuffIcons#regionForAtlasIndex(int)} using the per-element
     *  atlas slot; text colour is the live {@link Effect#customColor}. When
     *  {@code iconAtlasIndex < 0} or the sheet failed to load, only the text
     *  draws (mimicking plain {@link EffectType#FLOATING_TEXT}). */
    private void drawDamageFloater(Effect e) {
        int total = e.totalFrames();
        if (total <= 0) return;
        float lifeT = Math.min(1f, e.frame / (float) total);
        float alpha = lifeT < 0.65f ? 1f : Math.max(0f, 1f - (lifeT - 0.65f) / 0.35f);
        if (alpha <= 0f) return;
        float baseX  = e.location.tileX() * (float) CELL;
        float baseY  = e.location.tileY() * (float) CELL;
        float yOff   = e.frame / 5f;
        float textY  = baseY + CELL * 2f + yOff;
        com.badlogic.gdx.graphics.Color c = e.customColor != null
                ? e.customColor
                : tintToColor(e.tint, Color.RED);
        TextureRegion icon = e.iconAtlasIndex >= 0
                ? BuffIcons.regionForAtlasIndex(e.iconAtlasIndex) : null;
        float textX = baseX;
        if (icon != null) {
            float iconSize = 10f;
            float ix = baseX;
            float iy = textY - iconSize + 1f;
            batch.setColor(1f, 1f, 1f, alpha);
            batch.draw(icon, ix, iy, iconSize, iconSize);
            batch.setColor(Color.WHITE);
            textX = baseX + iconSize + 1f;
        }
        // Scale the world font down for the floater - default world scale
        // was sized for big "5" / "miss" pops which competed with the tile
        // art they rose from. Reset after so other renderers that share
        // {@code font} see it at the default scale.
        float prevScale = font.getData().scaleX;
        font.getData().setScale(prevScale * com.bjsp123.rl2.ui.v2.UIVars.DAMAGE_FLOATER_SCALE);
        font.setColor(c.r, c.g, c.b, alpha);
        font.draw(batch, e.text == null ? "" : e.text, textX, textY);
        font.setColor(Color.WHITE);
        font.getData().setScale(prevScale);
    }

    /** Floating buff-icon - same rising motion as floating text, but renders the buff's
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
        // Additive blend - rays are emissive light; straight-alpha on a
        // white-RGB sprite leaves visible white fringes on lit floors.
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
        batch.setColor(tint.r, tint.g, tint.b, alpha * 0.35f);
        batch.draw(whiteRegion,
                x1, y1 - 3f, 0f, 3f,
                len, 6f, 1f, 1f, angle);
        batch.setColor(1f, 1f, 1f, alpha);
        batch.draw(whiteRegion,
                x1, y1 - 1f, 0f, 1f,
                len, 2f, 1f, 1f, angle);
        batch.setColor(Color.WHITE);
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
    }

    /** Render the grappling-rope effect - a thick brown line from caster to
     *  target tile that grows during the extend phase, then either shrinks
     *  back (success) or holds-flashes-fades (failure). The retract pairs
     *  with the engine's {@link com.bjsp123.rl2.event.GameEvent.MobKnockedBack}
     *  slide so the dragged subject's sprite glides home with the rope's
     *  tip - set up at the rlib side, no synchronisation needed here. */
    private void drawGrappleRope(Effect e) {
        if (e.endLocation == null) return;
        int extendFrames = Math.max(1, e.grappleExtendFrames);
        int total        = e.totalFrames();
        int tailFrames   = Math.max(1, total - extendFrames);
        int frame        = e.frame;
        float x1 = e.location.tileX()    * (float) CELL + CELL * 0.5f;
        float y1 = e.location.tileY()    * (float) CELL + CELL * 0.5f;
        float x2 = e.endLocation.tileX() * (float) CELL + CELL * 0.5f;
        float y2 = e.endLocation.tileY() * (float) CELL + CELL * 0.5f;
        float fdx = x2 - x1, fdy = y2 - y1;
        float fullLen = (float) Math.sqrt(fdx * fdx + fdy * fdy);
        if (fullLen < 1e-3f) return;
        float angle = (float) Math.toDegrees(Math.atan2(fdy, fdx));

        float reachT;     // 0..1 fraction of the line currently drawn (rope tip)
        float alpha = 1f;
        if (frame < extendFrames) {
            reachT = (frame + 1) / (float) extendFrames;
        } else if (e.grappleSuccess) {
            // Retract - tip slides back to the caster.
            float t = (frame - extendFrames + 1) / (float) tailFrames;
            reachT = Math.max(0f, 1f - t);
        } else {
            // Failure - full extent, with a brief flash-then-fade.
            reachT = 1f;
            float t = (frame - extendFrames) / (float) tailFrames;
            // First half is a held flash, second half fades to nothing.
            if (t < 0.4f) {
                // Pulse: 1.0 -> 0.6 -> 1.0 to read as "rope yanks taut".
                alpha = 0.6f + 0.4f * (float) Math.cos(t * Math.PI / 0.4f);
            } else {
                alpha = Math.max(0f, 1f - (t - 0.4f) / 0.6f);
            }
        }
        if (alpha <= 0f || reachT <= 0f) return;
        float drawLen = fullLen * reachT;
        // Green vine on success, red-tinged on failure (so the heavy-target
        // outcome reads distinct from a clean pull).
        Color tint = e.grappleSuccess
                ? tintToColor(EffectTint.GREEN, Color.WHITE)
                : tintToColor(EffectTint.RED,   Color.WHITE);
        // Soft outline (1.5x thickness, 35% alpha) under a crisp core line.
        batch.setColor(tint.r * 0.4f, tint.g * 0.3f, tint.b * 0.25f, alpha * 0.5f);
        batch.draw(whiteRegion,
                x1, y1 - 2f, 0f, 2f,
                drawLen, 4f, 1f, 1f, angle);
        batch.setColor(tint.r, tint.g, tint.b, alpha);
        batch.draw(whiteRegion,
                x1, y1 - 1f, 0f, 1f,
                drawLen, 2f, 1f, 1f, angle);
        // Hook tip - small square at the rope's far end so the eye can
        // follow the tip during the retract.
        float tx = x1 + fdx * reachT;
        float ty = y1 + fdy * reachT;
        batch.setColor(tint.r, tint.g, tint.b, alpha);
        batch.draw(whiteRegion, tx - 2f, ty - 2f, 4f, 4f);
        batch.setColor(Color.WHITE);
    }

    /** Resolve the per-particle sprite for {@code e}. Returns the cached
     *  particle region from {@link com.bjsp123.rl2.world.render.BuffIcons}
     *  when the effect carries a concrete {@link Effect.ParticleShape};
     *  falls back to {@link #whiteRegion} otherwise (legacy stamp + a
     *  defensive landing for atlas-missing edge cases). */
    private TextureRegion particleSpriteFor(Effect e) {
        if (e == null || e.particleShape == null) return whiteRegion;
        int idx = e.particleShape.atlasIndex();
        if (idx < 0) return whiteRegion;
        TextureRegion r = com.bjsp123.rl2.world.render.BuffIcons.particleRegion(idx);
        return r != null ? r : whiteRegion;
    }

    /** Render one LIGHT_MOTE - a soft pale spark drifting up from a light source. */
    private void drawLightMote(Effect e) {
        if (e.particleX0 == null || e.particleX0.length == 0) return;
        int total = e.totalFrames();
        if (total <= 0) return;
        float lifeT = Math.min(1f, e.frame / (float) total);
        float phase    = e.particleX0[0];
        float jitterX  = e.particleY0[0];
        float speed    = e.particleVX != null && e.particleVX.length > 0 ? e.particleVX[0] : 1f;
        float baseX = e.location.tileX() * (float) CELL;
        float baseY = e.location.tileY() * (float) CELL + e.pixelOffsetY;
        float swayX = (float) Math.sin(phase + lifeT * 6f) * 1.2f;
        float px = baseX + CELL / 2f + jitterX + swayX;
        float py = baseY + CELL * 0.5f + speed * lifeT * 12f;
        float alpha = lifeT < 0.3f
                ? 0.55f * (lifeT / 0.3f)
                : 0.55f * (1f - (lifeT - 0.3f) / 0.7f);
        if (alpha <= 0f) return;
        TextureRegion sprite = particleSpriteFor(e);
        // Additive blend - motes are floating embers; emissive light on a
        // dark background reads fringe-free under additive.
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
        batch.setColor(1f, 0.95f, 0.7f, alpha);
        batch.draw(sprite, px - 2f, py - 2f, 4f, 4f);
        batch.setColor(1f, 1f, 0.9f, alpha);
        batch.draw(sprite, px - 1f, py - 1f, 2f, 2f);
        batch.setColor(Color.WHITE);
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
    }

    /** Render one HEARTH_SPARK (RL-51): a dot scattered around a point ~16 px
     *  above the hearth's sprite centre that rises while fading and stretching
     *  into a vertical line. Additive, warm ember colour. */
    private void drawHearthSpark(Effect e) {
        if (e.particleX0 == null || e.particleX0.length == 0) return;
        int total = e.totalFrames();
        if (total <= 0) return;
        float lifeT = Math.min(1f, e.frame / (float) total);
        float phase   = e.particleX0[0];
        float scatter = e.particleY0[0];
        float speed   = e.particleVX != null && e.particleVX.length > 0 ? e.particleVX[0] : 1f;
        // Origin: centre of the 2-wide hearth, lifted to its sprite centre plus
        // the builder's pixelOffsetY (the "16 px up from sprite centre").
        float baseX = e.location.tileX() * (float) CELL + CELL;
        float baseY = e.location.tileY() * (float) CELL + CELL + e.pixelOffsetY;
        float sway = (float) Math.sin(phase + lifeT * 5f) * 1.5f;
        float px = baseX + scatter + sway;
        float py = baseY + speed * lifeT * 22f;            // rises
        float alpha = (lifeT < 0.2f ? (lifeT / 0.2f) : (1f - (lifeT - 0.2f) / 0.8f)) * 0.7f;
        if (alpha <= 0f) return;
        float h = 2f + lifeT * lifeT * 14f;                // dot -> elongating line
        float w = Math.max(1f, 2f - lifeT * 1.2f);
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
        batch.setColor(1f, 0.82f, 0.45f, alpha);
        batch.draw(whiteRegion, px - w / 2f, py, w, h);
        batch.setColor(1f, 0.95f, 0.75f, alpha);
        batch.draw(whiteRegion, px - w / 4f, py, Math.max(1f, w / 2f), h);
        batch.setColor(Color.WHITE);
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
    }

    /** Render one INWARD_SPIRAL particle - a single spark that spirals
     *  toward its anchor point (lifted by {@code pixelOffsetY}) with a
     *  dim -> bright -> dim Hann-window alpha envelope. Parameters live
     *  in the 1-element synthetic-particle arrays: angle, start radius,
     *  and rotations-per-lifetime. Tint comes from {@link Effect#tint};
     *  the builder picks a random palette colour per particle so a stream
     *  reads as a multicolour shimmer. */
    private void drawInwardSpiral(Effect e) {
        if (e.particleX0 == null || e.particleX0.length == 0) return;
        int total = e.totalFrames();
        if (total <= 0) return;
        float lifeT = Math.min(1f, e.frame / (float) total);
        float initAngle = e.particleX0[0];
        float startR    = e.particleY0[0];
        float rotations = e.particleVX != null && e.particleVX.length > 0 ? e.particleVX[0] : 1f;

        float angle  = initAngle + rotations * lifeT * (float)(Math.PI * 2);
        float radius = startR * (1f - lifeT);

        float baseX = e.location.tileX() * (float) CELL + CELL * 0.5f;
        float baseY = e.location.tileY() * (float) CELL + CELL * 0.5f + e.pixelOffsetY;
        float px = baseX + (float) Math.cos(angle) * radius;
        float py = baseY + (float) Math.sin(angle) * radius;

        // Hann window: 0 at endpoints, peaks at mid-life.
        float alpha = 0.5f * (1f - (float) Math.cos(2.0 * Math.PI * lifeT));
        if (alpha <= 0f) return;

        Color tint = tintToColor(e.tint, Color.WHITE);
        TextureRegion sprite = particleSpriteFor(e);
        // Three-layer stamp for a bold spark: wide tinted halo, mid-size
        // tinted body, near-white core. Sizes doubled vs the original
        // 2-px draw so the swirl reads boldly against the dark world.
        // Additive blend - sparks are emissive; straight-alpha leaves a
        // white pall on lit floors at low-alpha edges.
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
        batch.setColor(tint.r, tint.g, tint.b, alpha * 0.45f);
        batch.draw(sprite, px - 3f, py - 3f, 6f, 6f);
        batch.setColor(tint.r, tint.g, tint.b, alpha);
        batch.draw(sprite, px - 2f, py - 2f, 4f, 4f);
        batch.setColor(
                Math.min(1f, tint.r + 0.4f),
                Math.min(1f, tint.g + 0.4f),
                Math.min(1f, tint.b + 0.4f),
                alpha);
        batch.draw(sprite, px - 1f, py - 1f, 2f, 2f);
        batch.setColor(Color.WHITE);
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
    }

    /** Paint screen-spanning effects in {@code stage} (currently just
     *  {@link EffectType#LEVEL_FLICKER}). Called once per render frame
     *  by {@link DefaultLevelRenderer} AFTER the per-cell content pass,
     *  so the flicker lays on top of the drawn world. {@code camera} is
     *  used to size the full-viewport quad. */
    void drawScreenSpaceEffects(EffectStage stage, com.badlogic.gdx.graphics.OrthographicCamera camera,
                                Level level) {
        if (stage == null || camera == null) return;
        for (Effect e : stage.active) {
            if (e.startDelay > 0) continue;
            if (e.type == EffectType.LEVEL_FLICKER) {
                drawLevelFlicker(e, camera);
            } else if (e.type == EffectType.ENCHANT_SHOWCASE) {
                drawEnchantShowcase(e, camera);
            } else if (e.type == EffectType.BUFF_ICON) {
                // Buff icons render here (above fog). Still FOV-gated on the source tile so
                // an icon for a mob that walked out of sight doesn't linger over the dark.
                com.bjsp123.rl2.model.Point loc = e.location;
                if (loc == null) continue;
                int ex = loc.tileX(), ey = loc.tileY();
                if (level != null && (ex < 0 || ey < 0 || ex >= level.width || ey >= level.height
                        || !level.visible[ex][ey])) continue;
                drawBuffIcon(e);
            }
        }
    }

    /** Render one LEVEL_FLICKER - a tinted full-viewport quad whose alpha
     *  follows a sine pulse over the effect's lifetime (0 -> peak -> 0). */
    private void drawLevelFlicker(Effect e, com.badlogic.gdx.graphics.OrthographicCamera camera) {
        int total = e.totalFrames();
        if (total <= 0) return;
        float lifeT = Math.min(1f, e.frame / (float) total);
        float alpha = (float) Math.sin(Math.PI * lifeT) * 0.35f;
        if (alpha <= 0f) return;
        Color tint = tintToColor(e.tint, Color.YELLOW);
        float halfW = camera.viewportWidth  * camera.zoom * 0.5f;
        float halfH = camera.viewportHeight * camera.zoom * 0.5f;
        // Full-screen lightning-style flash - additive so a yellow flicker
        // brightens whatever was on screen rather than tinting it through
        // an opaque-ish overlay.
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
        batch.setColor(tint.r, tint.g, tint.b, alpha);
        batch.draw(whiteRegion,
                camera.position.x - halfW, camera.position.y - halfH,
                halfW * 2f, halfH * 2f);
        batch.setColor(Color.WHITE);
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
    }

    /** Render a SCROLL_CAST (RL-50) around the reader's tile: sparks flying
     *  outward, an expanding glow, or an inward whirlpool - all tinted by the
     *  scroll's colour. Additive. */
    private void drawScrollCast(Effect e) {
        int total = e.totalFrames();
        if (total <= 0) return;
        float lifeT = Math.min(1f, e.frame / (float) total);
        Color c = tintToColor(e.tint, Color.WHITE);
        float baseX = e.location.tileX() * (float) CELL + CELL * 0.5f;
        float baseY = e.location.tileY() * (float) CELL + CELL * 0.5f;
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
        if (e.scrollStyle == Effect.SCROLL_GLOW) {
            float r = 5f + lifeT * 42f;
            float a = (1f - lifeT) * 0.55f;
            if (a > 0f) {
                batch.setColor(c.r, c.g, c.b, a);
                batch.draw(whiteRegion, baseX - r, baseY - r, r * 2f, r * 2f);
                float r2 = r * 0.45f;
                batch.setColor(c.r, c.g, c.b, Math.min(1f, a * 1.4f));
                batch.draw(whiteRegion, baseX - r2, baseY - r2, r2 * 2f, r2 * 2f);
            }
        } else if (e.particleX0 != null) {
            boolean whirl = e.scrollStyle == Effect.SCROLL_WHIRLPOOL;
            for (int i = 0; i < e.particleX0.length; i++) {
                float speed = e.particleVX[i];
                float angle, dist, a;
                if (whirl) {
                    angle = e.particleX0[i] + lifeT * speed * 7f;   // spin
                    dist  = (1f - lifeT) * 46f;                     // pull inward
                    a = (float) Math.sin(Math.PI * lifeT) * 0.85f;
                } else {
                    angle = e.particleX0[i];
                    dist  = lifeT * speed * 46f;                    // fly outward
                    a = (1f - lifeT) * 0.9f;
                }
                if (a <= 0f) continue;
                float px = baseX + (float) Math.cos(angle) * dist;
                float py = baseY + (float) Math.sin(angle) * dist;
                batch.setColor(c.r, c.g, c.b, a);
                batch.draw(whiteRegion, px - 1.5f, py - 1.5f, 3f, 3f);
            }
        }
        batch.setColor(Color.WHITE);
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
    }

    /** Render an ENCHANT_SHOWCASE (RL-50): the chosen item blown up in the
     *  screen centre with a pulsing glow halo and a spark shower. Screen-space
     *  (camera-anchored), grows in, holds, fades out. */
    private void drawEnchantShowcase(Effect e, com.badlogic.gdx.graphics.OrthographicCamera camera) {
        int total = e.totalFrames();
        if (total <= 0) return;
        float lifeT = Math.min(1f, e.frame / (float) total);
        float cx = camera.position.x, cy = camera.position.y;
        float screenH = camera.viewportHeight * camera.zoom;
        float grow = lifeT < 0.18f ? (lifeT / 0.18f) : 1f;
        float fade = lifeT > 0.82f ? (1f - (lifeT - 0.82f) / 0.18f) : 1f;
        float alpha = Math.max(0f, Math.min(grow, fade));
        if (alpha <= 0f) return;
        float size = screenH * 0.20f * (0.7f + 0.3f * grow);
        Color glow = tintToColor(e.tint, Color.GOLD);
        // Soft radial glow halo behind the item - the same beacon glow sprite,
        // additive so it reads as emissive (a plain quad looked like a square).
        TextureRegion glowTex = BuffIcons.beaconGlowRegion();
        if (glowTex == null) glowTex = whiteRegion;
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
        float gr = size * (1.1f + 0.08f * (float) Math.sin(lifeT * 24f));
        batch.setColor(glow.r, glow.g, glow.b, alpha * 0.45f);
        batch.draw(glowTex, cx - gr, cy - gr, gr * 2f, gr * 2f);
        // Spark shower - each spark spawns at its phase and flies outward.
        if (e.particleX0 != null) {
            for (int i = 0; i < e.particleX0.length; i++) {
                float t = (lifeT - e.particleY0[i]) / 0.5f;   // 0.5-life spark
                if (t < 0f || t > 1f) continue;
                float dist = t * size * 1.6f;
                float px = cx + (float) Math.cos(e.particleX0[i]) * dist;
                float py = cy + (float) Math.sin(e.particleX0[i]) * dist;
                float sa = (1f - t) * alpha;
                float sz = size * 0.05f;
                batch.setColor(glow.r, glow.g, glow.b, sa);
                batch.draw(whiteRegion, px - sz, py - sz, sz * 2f, sz * 2f);
            }
        }
        // Item sprite on top (normal blend).
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        TextureRegion region = ItemSprites.regionFor(e.thrownItem);
        if (region != null) {
            batch.setColor(1f, 1f, 1f, alpha);
            batch.draw(region, cx - size * 0.5f, cy - size * 0.5f, size, size);
        }
        batch.setColor(Color.WHITE);
    }

    /** Render an ITEM_BIRTH (RL-50 creation scroll): a tile-anchored glow halo
     *  + spark shower behind the conjured item sprite, grows in then fades out.
     *  World-space (drawn in the per-cell content pass). */
    private void drawItemBirth(Effect e) {
        int total = e.totalFrames();
        if (total <= 0) return;
        float lifeT = Math.min(1f, e.frame / (float) total);
        float cx = e.location.tileX() * (float) CELL + CELL * 0.5f;
        float cy = e.location.tileY() * (float) CELL + CELL * 0.5f;
        float grow = lifeT < 0.2f ? (lifeT / 0.2f) : 1f;
        float fade = lifeT > 0.7f ? (1f - (lifeT - 0.7f) / 0.3f) : 1f;
        float alpha = Math.max(0f, Math.min(grow, fade));
        if (alpha <= 0f) return;
        float size = CELL * (0.85f + 0.25f * grow);
        Color glow = tintToColor(e.tint, Color.GOLD);
        // Soft radial glow halo behind the item - the same beacon glow sprite,
        // additive so it reads as emissive (a plain quad looked like a square).
        TextureRegion glowTex = BuffIcons.beaconGlowRegion();
        if (glowTex == null) glowTex = whiteRegion;
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
        float gr = size * (0.85f + 0.08f * (float) Math.sin(lifeT * 24f));
        batch.setColor(glow.r, glow.g, glow.b, alpha * 0.5f);
        batch.draw(glowTex, cx - gr, cy - gr, gr * 2f, gr * 2f);
        // Spark shower flying outward.
        if (e.particleX0 != null) {
            for (int i = 0; i < e.particleX0.length; i++) {
                float t = (lifeT - e.particleY0[i]) / 0.5f;   // 0.5-life spark
                if (t < 0f || t > 1f) continue;
                float dist = t * size * 1.5f;
                float px = cx + (float) Math.cos(e.particleX0[i]) * dist;
                float py = cy + (float) Math.sin(e.particleX0[i]) * dist;
                float sa = (1f - t) * alpha;
                float sz = Math.max(1f, size * 0.06f);
                batch.setColor(glow.r, glow.g, glow.b, sa);
                batch.draw(whiteRegion, px - sz, py - sz, sz * 2f, sz * 2f);
            }
        }
        // Item sprite on top (normal blend).
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        TextureRegion region = ItemSprites.regionFor(e.thrownItem);
        if (region != null) {
            batch.setColor(1f, 1f, 1f, alpha);
            batch.draw(region, cx - size * 0.5f, cy - size * 0.5f, size, size);
        }
        batch.setColor(Color.WHITE);
    }

    /** Render a teleport streak burst - green vertical streaks moving purely along y. */
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
        // Teleport streaks are emissive vertical sparks - additive so they
        // composite as glowing pillars and never tint the floor white at
        // their faded ends.
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
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
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
    }

    /** Render an EXPLOSION effect - particles flying outward at constant velocity. */
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
        // Explosion debris reads as bright sparks - additive so the
        // particle cloud blooms outward without leaving a yellow-white
        // pall on any tile lit underneath.
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
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
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
    }

    /** Integrate each particle from its stored initial state and draw with gravity.
     *  When {@code particleSpawnFrame} is populated (same length as {@code particleX0}),
     *  each particle uses its own age so they stagger independently: colour transitions
     *  from tint to white over the first half of its life, then alpha fades to zero over
     *  the second half.  Without per-particle spawn frames the original global-t path runs. */
    private void drawParticleBurst(Effect e) {
        if (e.particleX0 == null) return;
        float baseX = e.location.tileX() * (float) CELL;
        float baseY = e.location.tileY() * (float) CELL;
        TextureRegion sprite = particleSpriteFor(e);

        if (e.particleSpawnFrame != null
                && e.particleSpawnFrame.length == e.particleX0.length) {
            // Per-particle path - each particle lives for AnimationVars.PARTICLE_LIFE frames
            // starting at its own spawn frame, independent of the others.
            Color base = tintToColor(e.tint, Color.WHITE);
            float size = e.particleSize > 0 ? e.particleSize : AnimationVars.PARTICLE_SIZE;
            // Bright bursts (sparks, magic puffs) read as emissive light;
            // additive blending eliminates white edge fringes on lit
            // floors. Dim particles (debris, dust) stay on straight alpha
            // so opaque silhouettes still look right.
            if (e.particleBright) batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
            for (int i = 0; i < e.particleX0.length; i++) {
                int age = e.frame - e.particleSpawnFrame[i];
                if (age < 0 || age >= AnimationVars.PARTICLE_LIFE) continue;
                float lifeFrac = age / (float) AnimationVars.PARTICLE_LIFE;
                // First half: tint -> white.  Second half: white, alpha fades.
                float whiteFrac = Math.min(1f, lifeFrac * 2f);
                float cr = base.r + (1f - base.r) * whiteFrac;
                float cg = base.g + (1f - base.g) * whiteFrac;
                float cb = base.b + (1f - base.b) * whiteFrac;
                float alpha = lifeFrac < 0.5f ? 1f : 1f - (lifeFrac - 0.5f) * 2f;
                float dx = e.particleVX[i] * age;
                float dy = e.particleVY[i] * age;
                float px = baseX + e.particleX0[i] + dx;
                float py = baseY + e.particleY0[i] + dy;
                if (e.particleBright) {
                    // Soft outer halo at 50% alpha, then crisp core on top.
                    batch.setColor(cr, cg, cb, alpha * 0.5f);
                    batch.draw(sprite, px - size / 2f, py - size / 2f, size * 2f, size * 2f);
                    batch.setColor(cr, cg, cb, alpha);
                    batch.draw(sprite, px, py, size, size);
                } else {
                    batch.setColor(cr, cg, cb, alpha);
                    batch.draw(sprite, px, py, size, size);
                }
            }
            batch.setColor(Color.WHITE);
            if (e.particleBright) batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
            return;
        }

        // Original global-t path.
        float t = e.frame;
        int total = EffectType.PARTICLE_BURST.frameCount;
        float lifeFrac = 1f - Math.max(0f, t - total / 2f) / (total / 2f);
        float alpha = Math.max(0f, Math.min(1f, lifeFrac));
        Color c = tintToColor(e.tint, Color.WHITE);
        float cr = c.r, cg = c.g, cb = c.b;
        if (e.particleFadeToWhite && total > 0) {
            float whiteFrac = Math.min(1f, t / (float) total);
            cr = cr + (1f - cr) * whiteFrac;
            cg = cg + (1f - cg) * whiteFrac;
            cb = cb + (1f - cb) * whiteFrac;
        }
        batch.setColor(cr, cg, cb, alpha);
        float size = e.particleSize > 0 ? e.particleSize : AnimationVars.PARTICLE_SIZE;
        boolean bounce = e.particleBounceDamping > 0f;
        for (int i = 0; i < e.particleX0.length; i++) {
            float dx = e.particleVX[i] * t;
            float py;
            if (bounce) {
                py = baseY + bouncingY(t, e.particleY0[i], e.particleVY[i],
                                       e.particleBounceDamping);
            } else {
                float dy = e.particleVY[i] * t - 0.5f * AnimationVars.PARTICLE_GRAVITY * t * t;
                py = baseY + e.particleY0[i] + dy;
            }
            float px = baseX + e.particleX0[i] + dx;
            batch.draw(sprite, px, py, size, size);
        }
        batch.setColor(Color.WHITE);
    }

    /** Closed-form damped-bounce trajectory. Particle starts at {@code y0} (tile-local
     *  pixels, 0 = bottom of cell) with vertical velocity {@code vy0}, falls under
     *  {@link AnimationVars#PARTICLE_GRAVITY}, and on hitting y = 0 rebounds with its
     *  vertical velocity multiplied by {@code damping}. Returns the tile-local y at
     *  time {@code t}; once the rebound velocity drops below a tiny threshold the
     *  particle settles on the floor. */
    private static float bouncingY(float t, float y0, float vy0, float damping) {
        float g = AnimationVars.PARTICLE_GRAVITY;
        if (y0 < 0f) y0 = 0f;
        // Phase 0: free flight from (y0, vy0). Solve y0 + vy0*t - 0.5*g*t² = 0 for downward hit.
        float disc = vy0*vy0 + 2f*g*y0;
        if (disc < 0f) return y0 + vy0*t - 0.5f*g*t*t;
        float t0 = (vy0 + (float)Math.sqrt(disc)) / g;
        if (t <= t0) {
            return y0 + vy0*t - 0.5f*g*t*t;
        }
        float remaining = t - t0;
        float vyImpact = vy0 - g*t0;           // negative (downward)
        float vy = -vyImpact * damping;         // positive (rebound)
        for (int safety = 0; safety < 16; safety++) {
            if (vy < 0.2f) return 0f;           // settled on the floor
            float bounceTime = 2f * vy / g;
            if (remaining <= bounceTime) {
                return vy*remaining - 0.5f*g*remaining*remaining;
            }
            remaining -= bounceTime;
            vy *= damping;
        }
        return 0f;
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
        // The head is an emissive projectile core - additive so it blooms
        // brighter as it overlaps itself and lights the tile beneath it
        // without a white halo on bright floors. The trail loop below
        // re-enters additive only when {@code particleBright} is set so
        // dim sparks still composite correctly under straight alpha.
        if (e.frame <= flightEnd) {
            batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
            batch.setColor(headColor.r, headColor.g, headColor.b, 0.35f);
            batch.draw(whiteRegion, hx - 5f, hy - 5f, 10f, 10f);
            batch.setColor(headColor.r, headColor.g, headColor.b, 1f);
            batch.draw(whiteRegion, hx - 2.5f, hy - 2.5f, 5f, 5f);
            batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        }

        int trailLife = 14;
        float gravity = e.particleGravity;
        float size    = e.particleSize > 0 ? e.particleSize : 1.5f;
        EffectTint[] palette = e.particleTints;
        if (e.particleBright) batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
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
        if (e.particleBright) batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        batch.setColor(Color.WHITE);
    }

    /** Physical projectile: the col-3 slash sprite translated along the
     *  from→to line, rotated to face its direction of travel. The sprite
     *  points up (+Y) at 0°, so the rotation is {@code atan2(dy,dx) - 90°}. */
    private void drawPhysicalMissile(Effect e) {
        if (e.endLocation == null) return;
        int total = e.totalFrames();
        if (total <= 0) return;
        float t = Math.min(1f, e.frame / (float) total);
        if (t >= 1f) return;
        TextureRegion region = BuffIcons.attackFlashRegion(3);
        if (region == null) return;
        float sx = (e.location.tileX()    + 0.5f) * CELL;
        float sy = (e.location.tileY()    + 0.5f) * CELL;
        float dx = (e.endLocation.tileX() + 0.5f) * CELL;
        float dy = (e.endLocation.tileY() + 0.5f) * CELL;
        float px = sx + (dx - sx) * t;
        float py = sy + (dy - sy) * t;
        float angle = (float) Math.toDegrees(Math.atan2(dy - sy, dx - sx)) - 90f;
        float size = CELL;
        batch.setColor(Color.WHITE);
        batch.draw(region,
                px - size * 0.5f, py - size * 0.5f,
                size * 0.5f, size * 0.5f,
                size, size,
                1f, 1f,
                angle);
    }

    /** Per-floor dust-cloud tints - looked up from the tile under the
     *  effect's spawn point so a wood floor kicks up sawdust-tan and a
     *  stone floor kicks up pale grey. Tiles not in this map (walls,
     *  doors, chasms) get a neutral tan default. */
    private static final java.util.EnumMap<Tile, Color> DUST_TINT
            = new java.util.EnumMap<>(Tile.class);
    static {
        DUST_TINT.put(Tile.FLOOR,         new Color(0.82f, 0.78f, 0.66f, 1f));
        DUST_TINT.put(Tile.FLOOR_WOOD,    new Color(0.78f, 0.62f, 0.42f, 1f));
        DUST_TINT.put(Tile.FLOOR_SPECIAL, new Color(0.78f, 0.78f, 0.86f, 1f));
        DUST_TINT.put(Tile.STAIRS_UP,     new Color(0.78f, 0.74f, 0.66f, 1f));
        DUST_TINT.put(Tile.STAIRS_DOWN,   new Color(0.78f, 0.74f, 0.66f, 1f));
    }
    private static final Color DUST_DEFAULT = new Color(0.82f, 0.78f, 0.66f, 1f);

    /** Foot-dust ellipse - expands, rises, drifts in the spawning mob's
     *  move direction, and fades. Drawn over the bottom of the mob's
     *  tile so it obscures the sprite's feet for a few frames. The
     *  ellipse is approximated with horizontal whitepixel bars (no
     *  native ellipse primitive in the SpriteBatch path). */
    /** Render one {@link Effect.EffectType#CLOUD_PUFF} ellipse - same
     *  horizontal-bar approximation as {@link #drawDustCloud}, tinted by
     *  the cloud type, expanding ~2x and rising / fading over its life.
     *  Many puffs per tile per second compose into the visible cloud. */
    private void drawCloudPuff(Effect e) {
        int total = e.totalFrames();
        if (total <= 0) return;
        float t = Math.min(1f, e.frame / (float) total);
        // Width: starts at the base random (5..9 px), grows ~100% over its
        // life so peak ~ 10..18 px.
        float w = e.dustStartW * (1f + 1.0f * t);
        float h = w * 0.7f;
        // Independent rise atop the drift - stronger upward bias than
        // dust clouds since smoke / steam / fumes float pretty actively.
        float rise = 6f * t;
        // Alpha: ramps in over the first 25% then linear-fades. Smoke
        // gets a heavier peak so a thick plume actually obscures what's
        // underneath - gameplay-wise smoke also blocks sight + light, so
        // it should read as visually opaque too. Steam / poison stay at
        // the lighter "wisps stack up" peak.
        float baseAlpha;
        float r, g, b;
        if (e.cloudType == null) return;
        switch (e.cloudType) {
            case SMOKE  -> { r = 0.02f; g = 0.02f; b = 0.02f; baseAlpha = 0.85f; }
            case STEAM  -> { r = 0.85f; g = 0.88f; b = 0.92f; baseAlpha = 0.55f; }
            case POISON -> { r = 0.18f; g = 0.62f; b = 0.20f; baseAlpha = 0.55f; }
            default     -> { return; }
        }
        float alpha;
        if (t < 0.25f)  alpha = baseAlpha * (t / 0.25f);
        else            alpha = baseAlpha * (1f - (t - 0.25f) / 0.75f);
        if (alpha <= 0f) return;

        float cx = e.dustPxX + e.dustVxPxPerFrame * e.frame;
        float cy = e.dustPxY + e.dustVyPxPerFrame * e.frame + rise;

        batch.setColor(r, g, b, alpha);
        int rows = Math.max(2, Math.round(h));
        for (int i = 0; i < rows; i++) {
            float yRel = (i + 0.5f) / rows * 2f - 1f;
            float halfW = (w * 0.5f)
                    * (float) Math.sqrt(Math.max(0f, 1f - yRel * yRel));
            float yPx = cy - h * 0.5f + i;
            batch.draw(whiteRegion, cx - halfW, yPx, halfW * 2f, 1f);
        }
        batch.setColor(Color.WHITE);
    }

    private void drawDustCloud(Level level, Effect e) {
        int total = e.totalFrames();
        if (total <= 0) return;
        float t = Math.min(1f, e.frame / (float) total);

        // Width: starts at the cloud's randomised base (4..7 px), grows
        // ~60% over its lifetime so peak ~ 6..11 px. Height ~ 0.75x width
        // for a rounder, less squashed puff.
        float w = e.dustStartW * (1f + 0.6f * t);
        float h = w * 0.75f;
        // Independent vertical rise (separate from drift): 0 -> ~4 px up.
        float rise = 4f * t;
        // Alpha: held for the first 40% of life, then linear fade. Paler
        // overall - the dust hints at footing rather than asserting it.
        float baseAlpha = 0.45f;
        float alpha = (t < 0.4f)
                ? baseAlpha
                : baseAlpha * (1f - (t - 0.4f) / 0.6f);
        if (alpha <= 0f) return;

        int tx = e.location.tileX(), ty = e.location.tileY();
        Tile floor = (tx >= 0 && ty >= 0
                && tx < level.width && ty < level.height)
                ? level.tiles[tx][ty] : null;
        Color tint = DUST_TINT.getOrDefault(floor, DUST_DEFAULT);
        // Per-cloud shade variance - same hue, slightly lighter / darker.
        float r = clamp01(tint.r * e.dustShade);
        float g = clamp01(tint.g * e.dustShade);
        float b = clamp01(tint.b * e.dustShade);

        // Pixel anchor + drift in player's direction x frame count + rise.
        float cx = e.dustPxX + e.dustVxPxPerFrame * e.frame;
        float cy = e.dustPxY + e.dustVyPxPerFrame * e.frame + rise;

        batch.setColor(r, g, b, alpha);
        // Stacked horizontal bars to approximate the ellipse.
        int rows = Math.max(2, Math.round(h));
        for (int i = 0; i < rows; i++) {
            float yRel = (i + 0.5f) / rows * 2f - 1f;
            float halfW = (w * 0.5f)
                    * (float) Math.sqrt(Math.max(0f, 1f - yRel * yRel));
            float yPx = cy - h * 0.5f + i;
            batch.draw(whiteRegion, cx - halfW, yPx, halfW * 2f, 1f);
        }
        batch.setColor(Color.WHITE);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** Knockback flash centred on the impacted unit's tile - same fade
     *  curve as the attack flash, but anchored to the centre rather than
     *  offset to one side, so it reads as "hit on the unit". */
    private void drawKnockbackFlash(Effect e) {
        TextureRegion region = BuffIcons.knockbackRegion();
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
        float drawX = e.location.tileX() * CELL;
        float drawY = e.location.tileY() * CELL;
        batch.setColor(1f, 1f, 1f, alpha);
        batch.draw(region, drawX, drawY, drawSize, drawSize);
        batch.setColor(Color.WHITE);
    }

    private void drawSurpriseIcon(Effect e) {
        TextureRegion region = BuffIcons.surpriseRegion();
        if (region == null) return;
        int total = e.totalFrames();
        if (total <= 0) return;
        float t = Math.min(1f, e.frame / (float) total);
        float flash = (e.frame <= 2 || (e.frame >= 5 && e.frame <= 7)) ? 1f : 0.75f;
        float alpha = t < 0.45f ? flash : Math.max(0f, flash * (1f - (t - 0.45f) / 0.55f));
        if (alpha <= 0f) return;
        float rise = 10f * t;
        float scale = t < 0.2f ? 1.25f : 1f;
        float drawSize = CELL * scale;
        float drawX = e.location.tileX() * CELL + CELL * 0.5f - drawSize * 0.5f;
        float drawY = e.location.tileY() * CELL + CELL * 1.85f + rise;
        batch.setColor(1f, 1f, 1f, alpha);
        batch.draw(region, drawX, drawY, drawSize, drawSize);
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

    /** Pickup toss - the item flies off the source tile toward the bottom-right
     *  of the screen while shrinking and fading. Drawn entirely in world coords:
     *  the destination is a fixed offset (+8 tiles right, -6 tiles down) from
     *  the source, which is roughly off-screen for a player-centred camera and
     *  reads as "into the inventory". The arc rises briefly so the item appears
     *  to leap off the floor before drifting away. */
    private void drawPickupToss(Effect e) {
        if (e.thrownItem == null) return;
        TextureRegion region = ItemSprites.regionFor(e.thrownItem);
        if (region == null) return;
        int frames = e.totalFrames();
        float t = frames <= 1 ? 1f : e.frame / (float) (frames - 1);
        float sx = (e.location.tileX() + 0.5f) * CELL;
        float sy = (e.location.tileY() + 0.5f) * CELL;
        float dxTotal =  8f * CELL;   // right
        float dyTotal = -6f * CELL;   // down (y-up world -> bottom-of-screen)
        float px = sx + dxTotal * t;
        float py = sy + dyTotal * t + (float) Math.sin(Math.PI * t * 0.5f) * (CELL * 0.6f);
        float scale = 1f - 0.6f * t;
        float alpha = 1f - t * 0.7f;
        float drawW = CELL * scale, drawH = CELL * scale;
        batch.setColor(1f, 1f, 1f, alpha);
        batch.draw(region, px - drawW / 2f, py - drawH / 2f, drawW, drawH);
        batch.setColor(Color.WHITE);
    }

    /** Mob falling into a chasm - same revolve-shrink-fade as
     *  {@link #drawFallingItem} but reads the mob's sprite from
     *  {@link MobSprites}. */
    private void drawFallingMob(Effect e) {
        if (e.fallenMob == null) return;
        TextureRegion region = MobSprites.regionFor(e.fallenMob);
        if (region == null) return;
        int frames = e.totalFrames();
        float t = frames <= 1 ? 1f : e.frame / (float) (frames - 1);
        float scale = 1f - t;
        float alpha = 1f - t;
        if (alpha <= 0f) return;
        float rotation = t * 720f;
        float cx = (e.location.tileX() + 0.5f) * CELL;
        float cy = (e.location.tileY() + 0.5f) * CELL;
        float drawW = CELL * scale, drawH = CELL * scale;
        batch.setColor(1f, 1f, 1f, alpha);
        batch.draw(region,
                cx - drawW / 2f, cy - drawH / 2f,
                drawW / 2f, drawH / 2f,
                drawW, drawH,
                1f, 1f,
                rotation);
        batch.setColor(Color.WHITE);
    }

    /** Single up-arrow glyph drifting upward and fading from the
     *  effect's tile. Used by the powerup-pickup composites. */
    private void drawUpArrow(Effect e) {
        int total = e.totalFrames();
        if (total <= 0) return;
        float t = Math.min(1f, e.frame / (float) total);
        float alpha = 1f - t;
        if (alpha <= 0f) return;
        float yOffset = e.frame * 0.7f;          // rises straight up
        float baseX = (e.location.tileX() + 0.5f) * CELL;
        float baseY = e.location.tileY() * CELL + CELL * 0.6f + yOffset;
        Color c = tintToColor(e.tint, Color.YELLOW);
        // Prefer the arrow-up sprite from buffs16.png; fall back to a font glyph if the
        // atlas cell is missing.
        TextureRegion arrow = com.bjsp123.rl2.world.render.BuffIcons.arrowUpRegion();
        if (arrow != null) {
            float sz = CELL * 0.7f;
            batch.setColor(c.r, c.g, c.b, alpha);
            batch.draw(arrow, baseX - sz * 0.5f, baseY, sz, sz);
            batch.setColor(Color.WHITE);
            return;
        }
        font.setColor(c.r, c.g, c.b, alpha);
        // GlyphLayout-centred so the arrow head sits on the tile centre
        // regardless of font cap height; the y baseline is the line's top.
        com.badlogic.gdx.graphics.g2d.GlyphLayout layout =
                new com.badlogic.gdx.graphics.g2d.GlyphLayout(font, e.text);
        font.draw(batch, e.text, baseX - layout.width * 0.5f, baseY);
        font.setColor(Color.WHITE);
    }

    /** Tile-anchored sprite redraw, tinted on a grey -> white -> gold
     *  cycle over the effect's lifetime. Stacks on top of the mob's
     *  normal sprite to read as a celebratory pulse. */
    private void drawPowerupFlash(Effect e) {
        if (e.fallenMob == null) return;
        TextureRegion region = MobSprites.regionFor(e.fallenMob);
        if (region == null) return;
        int total = e.totalFrames();
        if (total <= 0) return;
        float t = Math.min(1f, e.frame / (float) total);
        // Color cycle in three equal phases.
        Color base;
        if      (t < 1f / 3f) base = TINT_GREY;
        else if (t < 2f / 3f) base = Color.WHITE;
        else                  base = TINT_GOLD;
        // Pulse alpha - peak at the middle of each phase so the flash
        // reads as a heartbeat rather than a static overlay.
        float phaseT = (t * 3f) % 1f;
        float alpha = 0.55f * (1f - Math.abs(phaseT - 0.5f) * 2f) + 0.15f;
        float bx = e.location.tileX() * CELL;
        float by = e.location.tileY() * CELL;
        batch.setColor(base.r, base.g, base.b, alpha);
        batch.draw(region, bx, by, CELL, CELL);
        batch.setColor(Color.WHITE);
    }

    /** Item falling into a chasm - spins in place while shrinking and fading out. */
    private void drawFallingItem(Effect e) {
        if (e.thrownItem == null) return;
        TextureRegion region = ItemSprites.regionFor(e.thrownItem);
        if (region == null) return;
        int frames = e.totalFrames();
        float t = frames <= 1 ? 1f : e.frame / (float) (frames - 1);
        float scale = 1f - t;
        float alpha = 1f - t;
        if (alpha <= 0f) return;
        float rotation = t * 720f;
        float cx = (e.location.tileX() + 0.5f) * CELL;
        float cy = (e.location.tileY() + 0.5f) * CELL;
        float drawW = CELL * scale, drawH = CELL * scale;
        batch.setColor(1f, 1f, 1f, alpha);
        batch.draw(region,
                cx - drawW / 2f, cy - drawH / 2f,
                drawW / 2f, drawH / 2f,
                drawW, drawH,
                1f, 1f,
                rotation);
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
            case CYAN  -> Color.CYAN;
            case PINK  -> TINT_PINK;
            case MAUVE -> TINT_MAUVE;
            default    -> fallback;
        };
    }
}
