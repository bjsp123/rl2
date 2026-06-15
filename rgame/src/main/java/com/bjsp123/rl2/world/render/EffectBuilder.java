package com.bjsp123.rl2.world.render;

import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.bjsp123.rl2.model.Buff;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.world.anim.AnimationVars;
import com.bjsp123.rl2.world.render.Effect.EffectTint;
import com.bjsp123.rl2.world.render.Effect.EffectType;

import java.util.Random;

/**
 * Visual primitives for short-lived effects. Heavily parameterised; knows nothing
 * about game events. Game-named factories live in {@link Effect}; each Effect.foo
 * either freezes parameter values as game-visual decisions or composes multiple
 * EffectBuilder primitives.
 *
 * <h2>Eleven canonical primitives</h2>
 * <ol>
 *   <li>{@link #burst} — radial spray, no gravity (magic-missile-impact look).</li>
 *   <li>{@link #splash} — upper-cone spray with gravity + optional bouncing
 *       (angles configurable).</li>
 *   <li>{@link #fountain} — staggered upward stream (initial-position spread
 *       configurable).</li>
 *   <li>{@link #streaks} — vertical bars moving up or down.</li>
 *   <li>{@link #explosion} — radius-scaled fireball with shrink + colour decay.</li>
 *   <li>{@link #arcItem} / {@link #arcMob} / {@link #arcSprite} — parabolic A → B arc.</li>
 *   <li>{@link #beam} — line A → B with optional extend / retract / hold-and-pulse.</li>
 *   <li>{@link #flash} — sprite on a tile with a stylised flicker envelope.</li>
 *   <li>{@link #hoverText} / {@link #hoverSprite} / {@link #hoverGlyph} — single
 *       element drifting upward.</li>
 *   <li>{@link #spinShrinkFadeItem} / {@link #spinShrinkFadeMob} — sprite revolves
 *       while shrinking and fading.</li>
 *   <li>{@link #driftCloud} — soft drifting ellipse (dust, smoke, steam, poison).</li>
 * </ol>
 *
 * <h2>Six specialised atoms</h2>
 * For renderer routines that don't decompose into canonical primitives:
 * {@link #particleBurst}, {@link #projectile} (a magic missile = projectile +
 * burst — the composition lives in {@link Effect}, not here), {@link #pickupToss},
 * {@link #lightMote}, {@link #fireParticle}, {@link #sleepZ}.
 *
 * <p><b>Duration convention.</b> Every builder takes a {@code durationFrames}
 * parameter (render frames at ~60 FPS). For builders where every particle spawns
 * at frame 0 the duration is the per-particle life; for {@link #fountain} the
 * whole-effect lifetime is {@code spawnSpreadFrames + particleLifeFrames}.
 */
public final class EffectBuilder {

    /** Pixel pitch of one tile (matches the renderer's CELL size). */
    public static final float TILE_PX = 16f;

    private EffectBuilder() {}

    // ====================================================================
    // 1. burst — radial spray, no gravity, "magic-missile impact" style.
    // ====================================================================

    /**
     * Radial burst of {@code count} particles fired out from the tile centre at
     * uniform-ish speeds. No gravity. Tint blends to white then alpha fades — the
     * standard magic-missile-impact look. Per-particle path (each particle keyed
     * off its own spawn frame; all spawn at frame 0 here).
     */
    public static Effect burst(Point at, EffectTint tint, int count,
                               float speedMin, float speedMax,
                               float particleSize, boolean bright,
                               int durationFrames, Random rng) {
        return burst(at, tint, count, speedMin, speedMax, particleSize, bright,
                durationFrames, Effect.ParticleShape.STARS, rng);
    }

    /** {@link #burst} variant that pins the particle sprite to a specific
     *  {@link Effect.ParticleShape}. Group values (STARS / DROPS) are
     *  resolved to a concrete variant via {@code rng} at construction. */
    public static Effect burst(Point at, EffectTint tint, int count,
                               float speedMin, float speedMax,
                               float particleSize, boolean bright,
                               int durationFrames,
                               Effect.ParticleShape shape, Random rng) {
        Effect e = new Effect(at, EffectType.PARTICLE_BURST);
        e.tint                = tint;
        e.ignoresFov          = true;
        e.particleFadeToWhite = true;
        e.particleSize        = particleSize;
        e.particleBright      = bright;
        e.frameCount          = durationFrames;
        e.particleShape       = shape == null ? null : shape.resolve(rng);
        e.particleX0          = new float[count];
        e.particleY0          = new float[count];
        e.particleVX          = new float[count];
        e.particleVY          = new float[count];
        e.particleSpawnFrame  = new int[count];
        float cx = TILE_PX * 0.5f, cy = TILE_PX * 0.5f;
        float speedRange = Math.max(0f, speedMax - speedMin);
        for (int i = 0; i < count; i++) {
            float angle = (float)(i * Math.PI * 2.0 / count) + (rng.nextFloat() - 0.5f) * 0.3f;
            float speed = speedMin + rng.nextFloat() * speedRange;
            e.particleX0[i] = cx;
            e.particleY0[i] = cy;
            e.particleVX[i] = (float) Math.cos(angle) * speed;
            e.particleVY[i] = (float) Math.sin(angle) * speed;
        }
        return e;
    }

    // ====================================================================
    // 2. splash — upper-cone spray, gravity, optional bouncing.
    // ====================================================================

    /**
     * Droplets launched into an upper cone (bounded by {@code angleMinDeg} and
     * {@code angleMaxDeg}; canonical full upper half-disc is 20°..160°, a tight
     * vertical cone is e.g. 70°..110°) from a feet-level anchor. Gravity arcs them
     * back down; when {@code bounceDamping > 0} they bounce off the tile floor
     * (y = 0) with vertical velocity multiplied by {@code bounceDamping} per hit.
     */
    public static Effect splash(Point at, EffectTint tint, int count,
                                float speedMin, float speedMax,
                                float bounceDamping, float particleSize,
                                float angleMinDeg, float angleMaxDeg,
                                int durationFrames, Random rng) {
        return splash(at, tint, count, speedMin, speedMax, bounceDamping,
                particleSize, angleMinDeg, angleMaxDeg, durationFrames,
                Effect.ParticleShape.STARS, rng);
    }

    /** {@link #splash} variant pinning the particle sprite. */
    public static Effect splash(Point at, EffectTint tint, int count,
                                float speedMin, float speedMax,
                                float bounceDamping, float particleSize,
                                float angleMinDeg, float angleMaxDeg,
                                int durationFrames,
                                Effect.ParticleShape shape, Random rng) {
        Effect e = new Effect(at, EffectType.PARTICLE_BURST);
        e.tint                  = tint;
        e.ignoresFov            = true;
        e.particleFadeToWhite   = true;
        e.particleSize          = particleSize;
        e.particleBounceDamping = bounceDamping;
        e.frameCount            = durationFrames;
        e.particleShape         = shape == null ? null : shape.resolve(rng);
        e.particleX0 = new float[count];
        e.particleY0 = new float[count];
        e.particleVX = new float[count];
        e.particleVY = new float[count];
        float cx = TILE_PX * 0.5f, cy = TILE_PX * 0.35f;
        float speedRange = Math.max(0f, speedMax - speedMin);
        float angleRange = Math.max(0f, angleMaxDeg - angleMinDeg);
        for (int i = 0; i < count; i++) {
            float angle = (float) Math.toRadians(angleMinDeg + rng.nextFloat() * angleRange);
            float speed = speedMin + rng.nextFloat() * speedRange;
            e.particleX0[i] = cx + (rng.nextFloat() - 0.5f) * 4f;
            e.particleY0[i] = cy + (rng.nextFloat() - 0.5f) * 2f;
            e.particleVX[i] = (float) Math.cos(angle) * speed;
            e.particleVY[i] = (float) Math.sin(angle) * speed;
        }
        return e;
    }

    // ====================================================================
    // drip — staggered downward drops spread across a body footprint (RL-44).
    // ====================================================================

    /** Drops that spawn at random staggered frames across a mob's body footprint and
     *  fall under gravity, fading out - the continuous "dripping" look for OILY / WET /
     *  CHILLED / BLEEDING. {@code count} drops spawn over {@code spawnSpreadFrames}; each
     *  lives {@code particleLifeFrames}. Emitted repeatedly by the buff-particle tick so
     *  drops keep appearing at random spots on the body. */
    public static Effect drip(Point at, EffectTint tint, int count,
                              int spawnSpreadFrames, int particleLifeFrames,
                              Effect.ParticleShape shape, Random rng) {
        Effect e = new Effect(at, EffectType.PARTICLE_BURST);
        e.tint                = tint;
        e.ignoresFov          = true;
        e.particleFadeToWhite = false;
        e.particleSize        = 1.3f;
        e.particleGravity     = 0.04f;
        e.frameCount          = spawnSpreadFrames + particleLifeFrames;
        e.particleShape       = shape == null ? null : shape.resolve(rng);
        e.particleX0          = new float[count];
        e.particleY0          = new float[count];
        e.particleVX          = new float[count];
        e.particleVY          = new float[count];
        e.particleSpawnFrame  = new int[count];
        for (int i = 0; i < count; i++) {
            // Random spots across the body footprint (x 0.25..0.75, y 0.4..1.05 of the
            // tile); drops fall straight down (no horizontal drift) and fade out.
            e.particleX0[i]         = TILE_PX * (0.25f + rng.nextFloat() * 0.5f);
            e.particleY0[i]         = TILE_PX * (0.40f + rng.nextFloat() * 0.65f);
            e.particleVX[i]         = 0f;
            e.particleVY[i]         = -(0.05f + rng.nextFloat() * 0.15f);   // downward
            e.particleSpawnFrame[i] = rng.nextInt(spawnSpreadFrames + 1);
        }
        return e;
    }

    // ====================================================================
    // 3. fountain — staggered upward stream from a point.
    // ====================================================================

    /**
     * Stream of {@code count} particles spawned at random frames over
     * {@code spawnSpreadFrames}, each drifting straight up at
     * {@code riseSpeedMin..riseSpeedMax} px/frame. {@code positionJitterX/Y}
     * controls the initial-position spread (small for tight stream; large for
     * "puff cloud over a tile"). Each particle lives {@code particleLifeFrames}.
     */
    public static Effect fountain(Point at, EffectTint tint, int count,
                                  int spawnSpreadFrames, int particleLifeFrames,
                                  float riseSpeedMin, float riseSpeedMax,
                                  float horizontalJitter, boolean fadeToWhite,
                                  float positionJitterX, float positionJitterY,
                                  Random rng) {
        return fountain(at, tint, count, spawnSpreadFrames, particleLifeFrames,
                riseSpeedMin, riseSpeedMax, horizontalJitter, fadeToWhite,
                positionJitterX, positionJitterY,
                Effect.ParticleShape.STARS, rng);
    }

    /** {@link #fountain} variant pinning the particle sprite. */
    public static Effect fountain(Point at, EffectTint tint, int count,
                                  int spawnSpreadFrames, int particleLifeFrames,
                                  float riseSpeedMin, float riseSpeedMax,
                                  float horizontalJitter, boolean fadeToWhite,
                                  float positionJitterX, float positionJitterY,
                                  Effect.ParticleShape shape, Random rng) {
        Effect e = new Effect(at, EffectType.PARTICLE_BURST);
        e.tint                = tint;
        e.ignoresFov          = true;
        e.particleFadeToWhite = fadeToWhite;
        e.frameCount          = spawnSpreadFrames + particleLifeFrames;
        e.particleShape       = shape == null ? null : shape.resolve(rng);
        e.particleX0          = new float[count];
        e.particleY0          = new float[count];
        e.particleVX          = new float[count];
        e.particleVY          = new float[count];
        e.particleSpawnFrame  = new int[count];
        float cx = TILE_PX * 0.5f, cy = TILE_PX * 0.5f;
        float riseRange = Math.max(0f, riseSpeedMax - riseSpeedMin);
        for (int i = 0; i < count; i++) {
            e.particleX0[i]         = cx + (rng.nextFloat() - 0.5f) * positionJitterX;
            e.particleY0[i]         = cy + (rng.nextFloat() - 0.5f) * positionJitterY;
            e.particleVX[i]         = (horizontalJitter > 0)
                                        ? (rng.nextFloat() - 0.5f) * horizontalJitter
                                        : 0f;
            e.particleVY[i]         = riseSpeedMin + rng.nextFloat() * riseRange;
            e.particleSpawnFrame[i] = rng.nextInt(spawnSpreadFrames + 1);
        }
        return e;
    }

    // ====================================================================
    // 4. streaks — vertical bars moving up or down.
    // ====================================================================

    /** Direction of {@link #streaks}. */
    public enum StreakDirection { UP, DOWN }

    /**
     * {@code count} short vertical bars distributed across the tile width.
     * {@code UP}: particles start near the bottom with {@code vy = +speed};
     * {@code DOWN}: particles start near the top with {@code vy = -speed}.
     */
    public static Effect streaks(Point at, EffectTint tint, int count,
                                 float speedMin, float speedMax,
                                 StreakDirection direction,
                                 int durationFrames, Random rng) {
        Effect e = new Effect(at, EffectType.TELEPORT_STREAKS);
        e.tint       = tint;
        e.frameCount = durationFrames;
        e.particleX0 = new float[count];
        e.particleY0 = new float[count];
        e.particleVX = new float[count];
        e.particleVY = new float[count];
        float speedRange = Math.max(0f, speedMax - speedMin);
        for (int i = 0; i < count; i++) {
            e.particleX0[i] = 1f + rng.nextFloat() * (TILE_PX - 2f);
            float speed = speedMin + rng.nextFloat() * speedRange;
            if (direction == StreakDirection.UP) {
                e.particleY0[i] = rng.nextFloat() * 4f;
                e.particleVY[i] = +speed;
            } else {
                e.particleY0[i] = TILE_PX - 4f + rng.nextFloat() * 4f;
                e.particleVY[i] = -speed;
            }
            e.particleVX[i] = 0f;
        }
        return e;
    }

    // ====================================================================
    // 5. explosion — radius-scaled fireball with shrink + colour decay.
    // ====================================================================

    /**
     * Fireball at the tile centre. Particle count = {@code 18 + 10 * radiusTiles};
     * velocities scaled so each particle travels {@code (0.4..1.0) * radius} tiles
     * over {@code durationFrames}. Renderer handles the shrink + orange → yellow
     * colour decay specific to {@link EffectType#EXPLOSION}.
     */
    public static Effect explosion(Point at, int radiusTiles,
                                   int durationFrames, Random rng) {
        Effect e = new Effect(at, EffectType.EXPLOSION);
        e.ignoresFov = true;
        e.frameCount = durationFrames;
        int count = 18 + 10 * Math.max(1, radiusTiles);
        e.particleX0 = new float[count];
        e.particleY0 = new float[count];
        e.particleVX = new float[count];
        e.particleVY = new float[count];
        float maxRange = Math.max(1, radiusTiles) * TILE_PX;
        for (int i = 0; i < count; i++) {
            e.particleX0[i] = TILE_PX * 0.5f;
            e.particleY0[i] = TILE_PX * 0.5f;
            float ang  = rng.nextFloat() * (float)(Math.PI * 2);
            float frac = 0.4f + rng.nextFloat() * 0.6f;
            float v    = (frac * maxRange) / durationFrames;
            e.particleVX[i] = (float) Math.cos(ang) * v;
            e.particleVY[i] = (float) Math.sin(ang) * v;
        }
        return e;
    }

    // ====================================================================
    // 6. arc — parabolic A → B arc with optional spin, shrink, height.
    // ====================================================================

    /**
     * Item arcs from {@code from} to {@code to}. {@code blocking = true} routes
     * through {@link EffectType#THROWN_ITEM} (the freeze-frame counter waits on
     * it); {@code false} routes through {@link EffectType#LOOT_TOSS}.
     *
     * <p><i>Phase B note:</i> {@code spinDegrees}, {@code endScale} and
     * {@code arcHeightPx} are accepted but not yet read by the renderer (it
     * still uses its built-in 720° spin, 1× scale, and {@code CELL * 0.5} arc
     * height). Adding the corresponding Effect fields + renderer hookup is a
     * follow-up.
     */
    public static Effect arcItem(Point from, Point to, Item item,
                                 float spinDegrees, float endScale,
                                 float arcHeightPx, boolean blocking,
                                 int durationFrames) {
        Effect e = new Effect(from, blocking ? EffectType.THROWN_ITEM : EffectType.LOOT_TOSS);
        e.endLocation = to;
        e.thrownItem  = item;
        e.frameCount  = durationFrames;
        return e;
    }

    /**
     * Mob sprite arcs from {@code from} to {@code to}. (Currently no in-renderer
     * use case — provided for symmetry; the renderer would need a {@code MOB_ARC}
     * draw routine, or {@code THROWN_ITEM} with a mob-sprite source.)
     */
    public static Effect arcMob(Point from, Point to, Mob mob,
                                float spinDegrees, float endScale,
                                float arcHeightPx, int durationFrames) {
        Effect e = new Effect(from, EffectType.FALLING_MOB);  // closest current type
        e.endLocation = to;
        e.fallenMob   = mob;
        e.frameCount  = durationFrames;
        return e;
    }

    /**
     * Sprite traces an A → B path with a fixed orientation (rotated to face
     * travel direction). Used for spears / arrows. {@code rotationOnly} is
     * currently recomputed from the trajectory by the renderer.
     */
    public static Effect arcSprite(Point from, Point to, TextureRegion sprite,
                                   float rotationOnly, float arcHeightPx,
                                   int durationFrames) {
        Effect e = new Effect(from, EffectType.PHYSICAL_MISSILE);
        e.endLocation = to;
        e.frameCount  = durationFrames;
        return e;
    }

    // ====================================================================
    // 7. beam — line A → B, optional extend/retract/hold-pulse.
    // ====================================================================

    /**
     * Coloured line from {@code from} to {@code to}.
     * <ul>
     *   <li>{@code retractFrames == 0 && !holdAndPulse && particleCount == 0} →
     *       {@link EffectType#RAY}, a plain solid line.</li>
     *   <li>otherwise → {@link EffectType#GRAPPLE_ROPE} with
     *       {@code grappleExtendFrames = extendFrames} and
     *       {@code grappleSuccess = !holdAndPulse}.</li>
     * </ul>
     *
     * <p><i>Phase B note:</i> {@code particleCount} / {@code particleSize} are
     * accepted but not yet rendered — a follow-up will add a small flashing-particle
     * pass inside the beam renderers.
     */
    public static Effect beam(Point from, Point to, EffectTint tint,
                              int extendFrames, int retractFrames,
                              boolean holdAndPulse,
                              int particleCount, float particleSize,
                              int durationFrames) {
        boolean plainRay = retractFrames == 0 && !holdAndPulse && particleCount == 0;
        Effect e = new Effect(from, plainRay ? EffectType.RAY : EffectType.GRAPPLE_ROPE);
        e.endLocation = to;
        e.tint        = tint;
        e.frameCount  = durationFrames;
        if (!plainRay) {
            e.grappleExtendFrames = Math.max(1, extendFrames);
            e.grappleSuccess      = !holdAndPulse;
        }
        return e;
    }

    // ====================================================================
    // 8. flash — sprite on tile with stylised flicker envelope.
    // ====================================================================

    /** Which on-tile flash envelope to play. */
    public enum FlashStyle { ATTACK_PLAYER, ATTACK_NPC, KNOCKBACK, SURPRISE, BLAST, TINT_CYCLE }

    /**
     * Park a sprite on the tile and play a stylised flicker pattern (each style
     * has its own envelope baked into the renderer). {@code startDelay} parks
     * the effect at frame 0 for that many render frames — used to stagger
     * multiple flashes from a single tick. {@code fallenMob} is required for
     * {@code TINT_CYCLE} (the mob whose sprite is being re-tinted) and ignored
     * otherwise.
     */
    public static Effect flash(Point at, FlashStyle style, EffectTint tint,
                               int startDelay, int durationFrames,
                               Mob fallenMob) {
        EffectType type = switch (style) {
            case ATTACK_PLAYER, ATTACK_NPC -> EffectType.ATTACK_FLASH;
            case KNOCKBACK -> EffectType.KNOCKBACK_FLASH;
            case SURPRISE  -> EffectType.SURPRISE_ICON;
            case BLAST     -> EffectType.BLAST;
            case TINT_CYCLE -> EffectType.POWERUP_FLASH;
        };
        Effect e = new Effect(at, type);
        e.tint       = tint;
        e.startDelay = Math.max(0, startDelay);
        e.frameCount = durationFrames;
        if (style == FlashStyle.ATTACK_PLAYER) e.spriteCol = 0;
        else if (style == FlashStyle.ATTACK_NPC) e.spriteCol = 1;
        if (style == FlashStyle.TINT_CYCLE) e.fallenMob = fallenMob;
        return e;
    }

    // ====================================================================
    // 9. hover — single text/sprite/glyph drifts upward.
    // ====================================================================

    /** Hover sub-style. */
    public enum HoverStyle { TEXT_RISE, ARROW_RISE, SLEEP_Z_WOBBLE }

    /** Floating damage / heal / event text above a tile. */
    public static Effect hoverText(Point at, String text, EffectTint tint,
                                   int startDelay, int durationFrames) {
        Effect e = new Effect(at, EffectType.FLOATING_TEXT);
        e.text       = text;
        e.tint       = tint;
        e.startDelay = Math.max(0, startDelay);
        e.frameCount = durationFrames;
        return e;
    }

    /** Element-aware damage floater - buff-icon glyph to the left, colored
     *  text to the right, both rising in lockstep. Pass {@code iconAtlasIndex
     *  < 0} to suppress the icon (PHYSICAL uses {@link #hoverText} instead,
     *  but the field is here for any future no-icon element). */
    public static Effect damageFloater(Point at, String text,
                                       com.badlogic.gdx.graphics.Color color,
                                       int iconAtlasIndex, int durationFrames) {
        Effect e = new Effect(at, EffectType.DAMAGE_FLOATER);
        e.text            = text;
        e.customColor     = color;
        e.iconAtlasIndex  = iconAtlasIndex;
        e.frameCount      = durationFrames;
        return e;
    }

    /** Buff icon sprite with text fallback when the sprite isn't loaded. */
    public static Effect hoverSprite(Point at, Buff.BuffType buffSprite,
                                     String fallbackText, int durationFrames) {
        Effect e = new Effect(at, EffectType.BUFF_ICON);
        e.text       = fallbackText;
        e.buffType   = buffSprite;
        e.frameCount = durationFrames;
        return e;
    }

    /** Single drifting glyph — for up-arrows, sleep Z's, etc. {@code style}
     *  picks the rise pattern in the renderer. */
    public static Effect hoverGlyph(Point at, String glyph, EffectTint tint,
                                    int startDelay, int durationFrames,
                                    HoverStyle style) {
        EffectType type = switch (style) {
            case ARROW_RISE     -> EffectType.UP_ARROW;
            case SLEEP_Z_WOBBLE -> EffectType.SLEEP_Z;
            case TEXT_RISE      -> EffectType.FLOATING_TEXT;
        };
        Effect e = new Effect(at, type);
        e.text       = glyph;
        e.tint       = tint;
        e.startDelay = Math.max(0, startDelay);
        e.frameCount = durationFrames;
        return e;
    }

    // ====================================================================
    // 10. spinShrinkFade — sprite revolves while shrinking and fading.
    // ====================================================================

    /** Item sprite revolving twice while shrinking and fading at its tile. */
    public static Effect spinShrinkFadeItem(Point at, Item item, int durationFrames) {
        Effect e = new Effect(at, EffectType.FALLING_ITEM);
        e.thrownItem = item;
        e.frameCount = durationFrames;
        return e;
    }

    /** Mob sprite revolving twice while shrinking and fading at its tile. */
    public static Effect spinShrinkFadeMob(Point at, Mob mob, int durationFrames) {
        Effect e = new Effect(at, EffectType.FALLING_MOB);
        e.fallenMob  = mob;
        e.frameCount = durationFrames;
        return e;
    }

    // ====================================================================
    // 11. driftCloud — soft drifting ellipse (dust / smoke / steam / poison).
    // ====================================================================

    /**
     * Soft ellipse that expands, rises, drifts, and fades. {@code cloudType == null}
     * → floor-tinted {@link EffectType#DUST_CLOUD}; otherwise type-tinted
     * {@link EffectType#CLOUD_PUFF}.
     */
    public static Effect driftCloud(int tileX, int tileY,
                                    float pxX, float pxY,
                                    float vx, float vy,
                                    Level.Cloud cloudType,
                                    int durationFrames, Random rng) {
        EffectType type = (cloudType == null) ? EffectType.DUST_CLOUD : EffectType.CLOUD_PUFF;
        Effect e = new Effect(new Point(tileX, tileY), type);
        e.dustPxX           = pxX;
        e.dustPxY           = pxY;
        e.dustVxPxPerFrame  = vx;
        e.dustVyPxPerFrame  = vy;
        e.frameCount        = durationFrames;
        if (cloudType == null) {
            e.dustStartW = 4f + rng.nextFloat() * 3f;
            e.dustShade  = 1.0f + rng.nextFloat() * 0.30f;
        } else {
            e.dustStartW = 5f + rng.nextFloat() * 4f;
            e.cloudType  = cloudType;
        }
        return e;
    }

    // ====================================================================
    // Specialised atoms — each maps 1-to-1 to a specialised FxRenderer routine.
    // ====================================================================

    /**
     * Generic upward spray with wide initial position. Used at hit landings,
     * banishment-ray sparks, explosion edges. Particles distributed across the
     * upper half of the tile with random horizontal velocities and always-upward
     * y velocity; global-t path with gravity.
     */
    public static Effect particleBurst(Point at, EffectTint tint, int count, Random rng) {
        Effect e = new Effect(at, EffectType.PARTICLE_BURST);
        e.tint = tint;
        e.particleX0 = new float[count];
        e.particleY0 = new float[count];
        e.particleVX = new float[count];
        e.particleVY = new float[count];
        for (int i = 0; i < count; i++) {
            e.particleX0[i] = 2f + rng.nextFloat() * 12f;
            e.particleY0[i] = 8f + rng.nextFloat() * 8f;
            e.particleVX[i] = (rng.nextFloat() - 0.5f) * 4.5f;
            e.particleVY[i] = 1.6f + rng.nextFloat() * 2.0f;
        }
        return e;
    }

    /** Magic-missile flight fraction — head finishes at this fraction of the total
     *  lifetime so the trail has time to dissipate after impact. */
    private static final float MAGIC_MISSILE_FLIGHT_FRACTION = 0.7f;

    /**
     * Projectile flying from {@code from} to {@code to}: a glowing head (halo +
     * core) travelling the line, with a trail of {@code particleCount} particles
     * spawning at staggered moments along the trajectory.
     *
     * <p>Pass {@code palette = null} for a single-tint trail (uses {@code e.tint},
     * defaulting to WHITE); pass a palette to cycle per-particle. {@code bright}
     * toggles the halo around each trail particle. {@code velocityJitter}
     * controls how much each trail particle wobbles from the trajectory line.
     *
     * <p>Renders via {@link EffectType#MAGIC_MISSILE} (head + trail in one draw
     * routine). The impact burst is NOT included — caller composes it via
     * {@code Effect.magicMissile} + the existing pendingImpact plumbing.
     */
    public static Effect projectile(Point from, Point to,
                                    EffectTint[] palette, EffectTint headTint,
                                    float particleGravity, float particleSize,
                                    boolean bright, int particleCount,
                                    float velocityJitter, Random rng) {
        Effect e = new Effect(from, EffectType.MAGIC_MISSILE);
        e.endLocation     = to;
        e.particleTints   = palette;
        e.headTint        = headTint;
        e.particleGravity = particleGravity;
        e.particleSize    = particleSize;
        e.particleBright  = bright;
        int flightFrames = framesForDistance(from, to, AnimationVars.MAGIC_MISSILE_PX_PER_FRAME);
        e.frameCount     = Math.max(1, Math.round(flightFrames / MAGIC_MISSILE_FLIGHT_FRACTION));
        e.impactFrame    = flightFrames;
        e.particleX0         = new float[particleCount];
        e.particleY0         = new float[particleCount];
        e.particleVX         = new float[particleCount];
        e.particleVY         = new float[particleCount];
        e.particleSpawnFrame = new int[particleCount];
        for (int i = 0; i < particleCount; i++) {
            e.particleSpawnFrame[i] = (int) Math.round(i * (flightFrames - 1f) / Math.max(1, particleCount - 1));
            e.particleX0[i] = (rng.nextFloat() - 0.5f) * 4f;
            e.particleY0[i] = (rng.nextFloat() - 0.5f) * 4f;
            e.particleVX[i] = (rng.nextFloat() - 0.5f) * velocityJitter;
            e.particleVY[i] = (rng.nextFloat() - 0.5f) * velocityJitter;
        }
        return e;
    }

    /** Item flies off {@code from} toward the bottom-right corner (inventory).
     *  Specialised renderer with fixed pixel destination; non-blocking. */
    public static Effect pickupToss(Point from, Item item) {
        Effect e = new Effect(from, EffectType.PICKUP_TOSS);
        e.thrownItem = item;
        return e;
    }

    /** Single ambient mote rising with sinusoidal sway. Specialised renderer. */
    public static Effect lightMote(Point at, Random rng) {
        Effect e = new Effect(at, EffectType.LIGHT_MOTE);
        e.particleX0 = new float[]{ rng.nextFloat() * (float)(Math.PI * 2) };
        e.particleY0 = new float[]{ (rng.nextFloat() - 0.5f) * 6f };
        e.particleVX = new float[]{ 0.7f + rng.nextFloat() * 0.6f };
        e.particleShape = Effect.ParticleShape.STARS.resolve(rng);
        return e;
    }

    /** Gem-hearth ember (RL-51). One dot scattered around a point ~16 px above
     *  the hearth's sprite centre; it rises while fading and elongating into a
     *  vertical line. {@code particleX0[0]} = sway phase, {@code particleY0[0]}
     *  = horizontal scatter, {@code particleVX[0]} = rise speed. */
    public static Effect hearthSpark(Point at, Random rng) {
        Effect e = new Effect(at, EffectType.HEARTH_SPARK);
        e.particleX0 = new float[]{ rng.nextFloat() * (float)(Math.PI * 2) };
        e.particleY0 = new float[]{ (rng.nextFloat() - 0.5f) * 16f };
        e.particleVX = new float[]{ 0.7f + rng.nextFloat() * 0.7f };
        e.pixelOffsetY = 16f;
        return e;
    }

    /** Teleport-orb palette for {@link #inwardSpiralParticle}. Cool
     *  white/cyan/blue mix matching the teleport-bomb sprite's colours
     *  so the swirl reads as the same arcane energy. WHITE appears twice
     *  for a slightly higher proportion of bright cores; the renderer
     *  brightens whichever tint is picked so the silhouette stays
     *  punchy. */
    private static final Effect.EffectTint[] SPIRAL_TINTS = {
            Effect.EffectTint.WHITE,
            Effect.EffectTint.WHITE,
            Effect.EffectTint.CYAN,
            Effect.EffectTint.BLUE
    };

    /** Single particle that spirals inward to {@code at} with a
     *  dim -> bright -> dim Hann-window alpha curve over its lifetime; the
     *  renderer is in {@code FxRenderer.drawInwardSpiral}. Like
     *  {@link #lightMote}, this is a one-particle effect that encodes its
     *  parameters in the 1-element {@code particleX0/Y0/VX} arrays:
     *  {@code particleX0[0]} = initial angle (rad), {@code particleY0[0]} =
     *  initial radius (px), {@code particleVX[0]} = number of rotations
     *  over the particle's lifetime. {@code pixelOffsetY} lifts the
     *  effect's centre above the tile's anchor baseline (e.g. beacons
     *  spawn from the lit upper half of the sprite, not the floor cell).
     *  Tint is randomised from a small palette so a steady emission
     *  cadence reads as multicolour shimmer. */
    public static Effect inwardSpiralParticle(Point at, float startRadiusPx,
                                              float pixelOffsetY, Random rng) {
        Effect e = new Effect(at, EffectType.INWARD_SPIRAL);
        e.particleX0    = new float[]{ rng.nextFloat() * (float)(Math.PI * 2) };
        e.particleY0    = new float[]{ startRadiusPx };
        e.particleVX    = new float[]{ 1.5f + rng.nextFloat() };
        e.tint          = SPIRAL_TINTS[rng.nextInt(SPIRAL_TINTS.length)];
        e.pixelOffsetY  = pixelOffsetY;
        e.particleShape = Effect.ParticleShape.STARS.resolve(rng);
        return e;
    }

    /** Screen-spanning brightness pulse. Painted by
     *  {@code FxRenderer.drawScreenSpaceEffects} as a tinted full-viewport
     *  quad whose alpha follows {@code sin(pi * lifeT)} so it rises from 0,
     *  peaks at mid-life, and fades to 0 at the end. The {@code anchor}
     *  point is required by the {@link Effect} contract but doesn't drive
     *  the visual - the viewport bounds come from the camera at draw time. */
    public static Effect flickerLevel(Point anchor, EffectTint tint, int durationFrames) {
        Effect e = new Effect(anchor, EffectType.LEVEL_FLICKER);
        e.tint       = tint;
        e.ignoresFov = true;       // screen-space; FOV doesn't apply
        e.frameCount = durationFrames;
        return e;
    }

    /** Single ember rising with sinusoidal sway and multi-stage colour gradient. */
    public static Effect fireParticle(Point at, Random rng) {
        Effect e = new Effect(at, EffectType.FIRE_PARTICLE);
        e.particleX0 = new float[]{ rng.nextFloat() * (float)(Math.PI * 2) };
        e.particleY0 = new float[]{ 1.0f + rng.nextFloat() * 1.5f };
        e.particleVX = new float[]{ -1.5f + rng.nextFloat() * 3f };
        return e;
    }

    /** Single wobbling "Z" glyph rising from a sleeper. Specialised renderer. */
    public static Effect sleepZ(Point at, Random rng) {
        Effect e = new Effect(at, EffectType.SLEEP_Z);
        e.particleX0 = new float[]{ (rng.nextFloat() - 0.5f) * 6f };
        e.particleY0 = new float[]{ rng.nextFloat() * (float)(Math.PI * 2) };
        return e;
    }

    // ====================================================================
    // Helpers
    // ====================================================================

    /** Frames a {@code from → to} travel takes at {@code pxPerFrame} (≥ 1 frame). */
    public static int framesForDistance(Point from, Point to, float pxPerFrame) {
        float dxTiles = to.tileX() - from.tileX();
        float dyTiles = to.tileY() - from.tileY();
        float distPx  = (float) Math.sqrt(dxTiles * dxTiles + dyTiles * dyTiles) * TILE_PX;
        return Math.max(1, Math.round(distPx / pxPerFrame));
    }

    /** Random int in {@code [min, max]} inclusive. */
    public static int jitter(int min, int max, Random rng) {
        if (max <= min) return min;
        return min + rng.nextInt(max - min + 1);
    }
}
