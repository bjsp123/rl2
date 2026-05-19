package com.bjsp123.rl2.world.render;
import com.bjsp123.rl2.logic.TextCatalog;
import com.bjsp123.rl2.model.Buff;
import com.bjsp123.rl2.world.anim.AnimationVars;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Point;

import java.util.Random;

/**
 * Short-lived visual element drawn over the level. Pure presentation: every field is a
 * frame counter, pixel coordinate, particle parameter, or sprite hint. The engine
 * ({@code rlib}) doesn't reference this class; rgame's {@link com.bjsp123.rl2.world.anim.Animator}
 * builds Effects in response to {@link com.bjsp123.rl2.event.GameEvent}s and adds them
 * to {@link EffectStage}.
 *
 * <p>Owns its own {@code frame} counter advanced once per render frame by
 * {@link EffectStage#tick()}; finished effects are removed.
 */
public class Effect {
    public enum EffectType {
        EXPLOSION(8),
        SPARK(4),
        BLOOD_SPLATTER(6),
        SMOKE_PUFF(5),
        FLOATING_TEXT(60),
        THROWN_ITEM(8),
        /** Same arc-and-spin visual as {@link #THROWN_ITEM} but explicitly
         *  non-blocking - used by the loot-on-death drop animation, which
         *  shouldn't gate any subsequent action. The Animator's freeze-frames
         *  tally excludes this type. */
        LOOT_TOSS(12),
        /** Item flies off the player's tile toward the bottom-right corner of
         *  the screen - used for the pickup acknowledgement animation. Shrinks
         *  + fades over its lifetime so it reads as "flying into the inventory". */
        PICKUP_TOSS(24),
        PARTICLE_BURST(28),
        MAGIC_MISSILE(36),
        FIRE_PARTICLE(36),
        SLEEP_Z(48),
        TELEPORT_STREAKS(30),
        LIGHT_MOTE(80),
        RAY(24),
        BLAST(20),
        BUFF_ICON(60),
        ATTACK_FLASH(17),
        /** Knockback impact graphic - flash + fade on top of a unit that
         *  was knocked back. Same fade curve as {@link #ATTACK_FLASH}. */
        KNOCKBACK_FLASH(17),
        /** Surprise-attack marker flashing and fading over the victim's head. */
        SURPRISE_ICON(24),
        /** Dust kicked up at a mob's feet when it steps. Small ellipse
         *  that expands and rises, fading to nothing over ~400 ms (24
         *  frames at the default render cadence). Tinted to a lighter
         *  shade of the floor tile beneath the spawn point. Drawn on top
         *  of the mob's lower edge so it visually obscures the sprite's
         *  feet during a step. */
        DUST_CLOUD(24),
        /** Item falls into a chasm - revolves, shrinks, and fades at its origin tile.
         *  Non-blocking so the game continues while the item disappears. */
        FALLING_ITEM(40),
        /** Mob falls into a chasm - same revolve-shrink-fade visual as
         *  {@link #FALLING_ITEM}, but the renderer pulls the sprite from
         *  {@link com.bjsp123.rl2.world.render.MobSprites} via the
         *  {@link Effect#fallenMob} reference. Non-blocking. */
        FALLING_MOB(40),
        /** One soft drifting ellipse spawned from a {@link Level#cloud} cell.
         *  Many of these per tile per second add up to the visual "fog
         *  hangs over a tile" effect. Per-instance lifetime jitter via
         *  {@link Effect#frameCount}; tint comes from {@link Effect#cloudType}. */
        CLOUD_PUFF(48),
        /** Grappling-rope visual - a rope extends from the caster to the
         *  target, then either retracts back (success) while a paired
         *  {@link com.bjsp123.rl2.event.GameEvent.MobKnockedBack} slide
         *  drags the target along, or holds briefly and flashes / fades at
         *  full extent (failure - target too heavy). The instance carries
         *  per-call frame counts via {@link Effect#frameCount} and the
         *  extend / retract split via {@link Effect#grappleExtendFrames}. */
        GRAPPLE_ROPE(28),
        /** Powerup-pickup visual: a single up-arrow glyph drifting upward
         *  and fading. The arrow's start delay (drives the level-up
         *  staggered swarm) sits in {@link Effect#startDelay}. Tint comes
         *  from {@link Effect#tint}. */
        UP_ARROW(36),
        /** Powerup-pickup visual: a tile-sized colored overlay that
         *  redraws the player's sprite tinted, cycling grey -> white ->
         *  gold over the effect's lifetime. The mob whose sprite to
         *  redraw is carried in {@link Effect#fallenMob}. */
        POWERUP_FLASH(36),
        /** Physical projectile (spear, arrow) travelling from
         *  {@link Effect#location} to {@link Effect#endLocation},
         *  rotated to face its travel direction. Sprite is col 3 of
         *  the slash band in {@code buffs16.png}. */
        PHYSICAL_MISSILE(36);

        public final int frameCount;

        EffectType(int frameCount) {
            this.frameCount = frameCount;
        }
    }

    public enum EffectTint {
        RED, YELLOW, WHITE, GREEN, BLUE, BROWN, ORANGE, CYAN, PINK
    }

    private static final float TILE_PX = 16f;
    private static final float MAGIC_MISSILE_FLIGHT_FRACTION = 0.7f;

    public Point location;
    public Point endLocation;
    public EffectType type;
    public int frame;
    /** Per-instance override of the type's default lifetime; 0 means use the type default. */
    public int frameCount;
    /** Render frames to wait before this effect starts playing - see
     *  {@link EffectStage#tick()}. Used to stagger attack flashes when several mobs
     *  attack on the same tick so the slashes appear in sequence rather than all at
     *  once. Counts down once per render frame; while {@code > 0} the effect is
     *  parked at frame 0 and renderers skip drawing it. */
    public int startDelay;
    /** Frame at which a pending impact callback fires. 0 means use the last frame (default). */
    public int impactFrame;
    public String text;
    public EffectTint tint;
    public Item thrownItem;
    /** Mob attached to the effect (FALLING_MOB sprite source;
     *  POWERUP_FLASH tint target). Pulled from
     *  {@link com.bjsp123.rl2.world.render.MobSprites} at draw time so
     *  we don't capture a TextureRegion that the atlas might rebuild. */
    public com.bjsp123.rl2.model.Mob fallenMob;
    /** ATTACK_FLASH only: which side of the attacker to draw on. */
    public boolean facesRight;
    /** ATTACK_FLASH only: column in the slash band of {@code buffs16.png}. */
    public int spriteCol;

    /** DUST_CLOUD only: world pixel anchor (the foot-position of the
     *  spawning mob at the moment of spawn). The cloud drifts from here
     *  along {@link #dustVxPxPerFrame}, {@link #dustVyPxPerFrame}. */
    public float dustPxX, dustPxY;
    /** DUST_CLOUD per-frame drift velocity in world pixels - typically a
     *  unit vector in the spawning mob's move direction times a small
     *  factor, so the cloud trails the mob's feet for a beat then lingers
     *  behind. */
    public float dustVxPxPerFrame, dustVyPxPerFrame;
    /** DUST_CLOUD randomised starting width (px). Each cloud is a
     *  slightly different size for visual variety; the per-frame draw
     *  expands from this value linearly. */
    public float dustStartW;
    /** DUST_CLOUD randomised tint multiplier (~0.85..1.15) applied on top
     *  of the floor's base dust colour, so successive clouds read as
     *  varying shades of the same warm grey. */
    public float dustShade;

    /** When true, PARTICLE_BURST particles blend from their {@link #tint} color
     *  toward white as they age - used by powerup pickups so the sparks start
     *  colored then turn white before fading. */
    public boolean particleFadeToWhite;
    public float[] particleX0;
    public float[] particleY0;
    public float[] particleVX;
    public float[] particleVY;
    public int[] particleSpawnFrame;
    public EffectTint[] particleTints;
    public EffectTint headTint;
    public float particleGravity;
    public float particleSize = 1.5f;
    public boolean particleBright;
    /** BUFF_ICON only: which buff to render the icon for. */
    public Buff.BuffType buffType;

    /** CLOUD_PUFF only: which {@link Level.Cloud} type tinted the puff.
     *  Drives the colour switch in {@link FxRenderer#drawCloudPuff}. */
    public Level.Cloud cloudType;

    /** GRAPPLE_ROPE only: number of frames the extend phase takes. The rest
     *  of the effect's lifetime is the retract / hold-and-fade phase, sized
     *  by {@link #frameCount} minus this value. */
    public int grappleExtendFrames;
    /** GRAPPLE_ROPE only: {@code true} when the grapple succeeded and the
     *  rope retracts; {@code false} when the target was too heavy and the
     *  rope holds at full extent then fades. */
    public boolean grappleSuccess;

    /** When {@code true}, the renderer skips its normal per-tile FOV check
     *  for this effect (PARTICLE_BURST, EXPLOSION). Used for ability sparks
     *  at both the caster and target when the Animator has already confirmed
     *  that at least one end of the ability is visible - so both endpoints
     *  render as a single visual unit. */
    public boolean ignoresFov;

    public Effect() {}

    public Effect(Point location, EffectType type) {
        this.location = location;
        this.type = type;
        this.frame = 0;
    }

    public int totalFrames() {
        return frameCount > 0 ? frameCount : type.frameCount;
    }

    private static int framesForDistance(Point from, Point to, float pxPerFrame) {
        float dxTiles = to.tileX() - from.tileX();
        float dyTiles = to.tileY() - from.tileY();
        float distPx  = (float) Math.sqrt(dxTiles * dxTiles + dyTiles * dyTiles) * TILE_PX;
        return Math.max(1, Math.round(distPx / pxPerFrame));
    }

    public static Effect floatingText(Point location, String text, EffectTint tint) {
        Effect e = new Effect(location, EffectType.FLOATING_TEXT);
        e.text = text;
        e.tint = tint;
        return e;
    }

    public static Effect thrownItem(Point from, Point to, Item item) {
        Effect e = new Effect(from, EffectType.THROWN_ITEM);
        e.endLocation = to;
        e.thrownItem = item;
        e.frameCount = framesForDistance(from, to, com.bjsp123.rl2.world.anim.AnimationVars.THROWN_ITEM_PX_PER_FRAME);
        return e;
    }

    /** Loot-on-death toss - same shape as {@link #thrownItem} (arc + spin) but
     *  uses the non-blocking {@link EffectType#LOOT_TOSS} type so the death
     *  animation doesn't gate the next mob's turn. */
    public static Effect lootToss(Point from, Point to, Item item) {
        Effect e = new Effect(from, EffectType.LOOT_TOSS);
        e.endLocation = to;
        e.thrownItem = item;
        e.frameCount = framesForDistance(from, to, com.bjsp123.rl2.world.anim.AnimationVars.THROWN_ITEM_PX_PER_FRAME);
        return e;
    }

    /** Pickup toss - item flies off {@code from}'s tile toward the screen's
     *  bottom-right corner, shrinking and fading as it goes. Non-blocking. */
    public static Effect pickupToss(Point from, Item item) {
        Effect e = new Effect(from, EffectType.PICKUP_TOSS);
        e.thrownItem = item;
        return e;
    }

    public static Effect attackFlash(Point at, boolean isPlayer, boolean facesRight) {
        return attackFlash(at, isPlayer, facesRight, 0);
    }

    /** Attack-flash with a render-frame start delay. {@code startDelay} parks the
     *  effect at frame 0 for that many frames before it begins playing - used to
     *  stagger flashes when multiple mobs attack on the same tick so the slashes
     *  appear in sequence rather than overlapping. */
    public static Effect attackFlash(Point at, boolean isPlayer, boolean facesRight,
                                     float startDelay) {
        Effect e = new Effect(at, EffectType.ATTACK_FLASH);
        e.spriteCol = isPlayer ? 0 : 1;
        e.facesRight = facesRight;
        e.startDelay = (int)Math.max(0f, startDelay);
        return e;
    }

    /** Knockback flash on a unit's tile. {@code startDelay} parks the
     *  effect at frame 0 for that many render frames before the flash
     *  begins - used to wait until a knockback slide finishes so the
     *  graphic appears ON the unit at its destination, not at the
     *  midpoint of the slide. */
    public static Effect knockbackFlash(Point at, int startDelay) {
        Effect e = new Effect(at, EffectType.KNOCKBACK_FLASH);
        e.startDelay = Math.max(0, startDelay);
        return e;
    }

    public static Effect surpriseIcon(Point at) {
        return new Effect(at, EffectType.SURPRISE_ICON);
    }

    /** Foot-dust cloud - anchored to the spawning mob's foot in world
     *  pixel coords with a per-frame drift in the mob's move direction.
     *  Each cloud randomises its starting size and grey-shade for
     *  visual variety. {@code tileX/tileY} is the tile under the spawn
     *  point - used by the renderer's per-tile dust-tint lookup and the
     *  level-visibility cull. */
    public static Effect dustCloud(int tileX, int tileY,
                                   float pxX, float pxY,
                                   float vxPxPerFrame, float vyPxPerFrame,
                                   Random rng) {
        Effect e = new Effect(new Point(tileX, tileY), EffectType.DUST_CLOUD);
        e.dustPxX = pxX;
        e.dustPxY = pxY;
        e.dustVxPxPerFrame = vxPxPerFrame;
        e.dustVyPxPerFrame = vyPxPerFrame;
        e.dustStartW = 4f + rng.nextFloat() * 3f;       // 4..7 px
        e.dustShade  = 1.0f + rng.nextFloat() * 0.30f;  // 1.0..1.3 (paler)
        // Per-cloud duration jitter - overrides EffectType.DUST_CLOUD's
        // default so a batch of clouds doesn't all fade out on the same
        // beat. ~ 333..600 ms at the default render cadence.
        e.frameCount = 20 + rng.nextInt(17);             // 20..36 frames
        return e;
    }

    /** One soft tinted ellipse spawned from a cloud-layer cell. The puff
     *  appears at ({@code pxX}, {@code pxY}) within the cell, expands to
     *  ~2x its starting width over its lifetime, drifts at the supplied
     *  per-frame velocity, and fades out. Use many per tile per second
     *  (rate driven by the cell's duration) to read as a body of gas. */
    public static Effect cloudPuff(int tileX, int tileY,
                                   float pxX, float pxY,
                                   float vxPxPerFrame, float vyPxPerFrame,
                                   Level.Cloud type, Random rng) {
        Effect e = new Effect(new Point(tileX, tileY), EffectType.CLOUD_PUFF);
        e.dustPxX = pxX;
        e.dustPxY = pxY;
        e.dustVxPxPerFrame = vxPxPerFrame;
        e.dustVyPxPerFrame = vyPxPerFrame;
        e.dustStartW = 5f + rng.nextFloat() * 4f;       // 5..9 px base
        e.cloudType  = type;
        // Lifetime jitter so a batch of puffs from the same tile doesn't
        // all fade on the same beat - ~ 600..900 ms at the default render
        // cadence.
        e.frameCount = 36 + rng.nextInt(18);            // 36..53 frames
        return e;
    }

    public static Effect magicMissile(Point from, Point to, Random rng) {
        Effect e = new Effect(from, EffectType.MAGIC_MISSILE);
        e.endLocation = to;
        int flightFrames = framesForDistance(from, to, com.bjsp123.rl2.world.anim.AnimationVars.MAGIC_MISSILE_PX_PER_FRAME);
        int flightEnd    = flightFrames;
        e.frameCount     = Math.max(1, Math.round(flightFrames / MAGIC_MISSILE_FLIGHT_FRACTION));
        e.impactFrame    = flightEnd;
        int count = 24;
        e.particleX0 = new float[count];
        e.particleY0 = new float[count];
        e.particleVX = new float[count];
        e.particleVY = new float[count];
        e.particleSpawnFrame = new int[count];
        for (int i = 0; i < count; i++) {
            e.particleSpawnFrame[i] = (int) Math.round(i * (flightEnd - 1f) / Math.max(1, count - 1));
            e.particleX0[i] = (rng.nextFloat() - 0.5f) * 4f;
            e.particleY0[i] = (rng.nextFloat() - 0.5f) * 4f;
            e.particleVX[i] = (rng.nextFloat() - 0.5f) * 1.2f;
            e.particleVY[i] = (rng.nextFloat() - 0.5f) * 1.2f;
        }
        return e;
    }

    public static Effect magicMissileColored(Point from, Point to,
                                             EffectTint[] palette, EffectTint headTint,
                                             float gravity, float size, boolean bright,
                                             Random rng) {
        Effect e = new Effect(from, EffectType.MAGIC_MISSILE);
        e.endLocation     = to;
        e.particleTints   = palette;
        e.headTint        = headTint;
        e.particleGravity = gravity;
        e.particleSize    = size;
        e.particleBright  = bright;
        int flightFrames = framesForDistance(from, to, com.bjsp123.rl2.world.anim.AnimationVars.MAGIC_MISSILE_PX_PER_FRAME);
        int flightEnd    = flightFrames;
        e.frameCount     = Math.max(1, Math.round(flightFrames / MAGIC_MISSILE_FLIGHT_FRACTION));
        e.impactFrame    = flightEnd;
        int count = 36;
        e.particleX0         = new float[count];
        e.particleY0         = new float[count];
        e.particleVX         = new float[count];
        e.particleVY         = new float[count];
        e.particleSpawnFrame = new int[count];
        for (int i = 0; i < count; i++) {
            e.particleSpawnFrame[i] = (int) Math.round(i * (flightEnd - 1f) / Math.max(1, count - 1));
            e.particleX0[i] = (rng.nextFloat() - 0.5f) * 4f;
            e.particleY0[i] = (rng.nextFloat() - 0.5f) * 4f;
            e.particleVX[i] = (rng.nextFloat() - 0.5f) * 0.8f;
            e.particleVY[i] = (rng.nextFloat() - 0.5f) * 0.8f;
        }
        return e;
    }

    /** Radial particle burst spawned when a wand missile visually arrives.
     *  Particles fire outward from the tile centre, coloured to match the element. */
    public static Effect wandImpactBurst(Point location, Item.ItemEffect element, Random rng) {
        Effect e = new Effect(location, EffectType.PARTICLE_BURST);
        e.ignoresFov = true;
        e.tint = wandElementTint(element);
        int count = 20;
        e.particleX0 = new float[count];
        e.particleY0 = new float[count];
        e.particleVX = new float[count];
        e.particleVY = new float[count];
        e.particleSpawnFrame = new int[count]; // all spawn at frame 0
        float cx = TILE_PX * 0.5f;
        float cy = TILE_PX * 0.5f;
        for (int i = 0; i < count; i++) {
            float angle = (float)(i * Math.PI * 2.0 / count) + rng.nextFloat() * 0.3f;
            float speed = 1.5f + rng.nextFloat() * 1.5f;
            e.particleX0[i] = cx;
            e.particleY0[i] = cy;
            e.particleVX[i] = (float) Math.cos(angle) * speed;
            e.particleVY[i] = (float) Math.sin(angle) * speed;
        }
        return e;
    }

    private static EffectTint wandElementTint(Item.ItemEffect element) {
        if (element == null) return EffectTint.WHITE;
        return switch (element) {
            case WATER     -> EffectTint.BLUE;
            case OIL, GRASS-> EffectTint.YELLOW;
            case FUNGUS    -> EffectTint.RED;
            case FIRE, DETONATION -> EffectTint.ORANGE;
            default        -> EffectTint.WHITE;
        };
    }

    public static Effect sleepZ(Point location, Random rng) {
        Effect e = new Effect(location, EffectType.SLEEP_Z);
        e.particleX0 = new float[]{ (rng.nextFloat() - 0.5f) * 6f };
        e.particleY0 = new float[]{ rng.nextFloat() * (float) (Math.PI * 2) };
        return e;
    }

    public static Effect lightMote(Point location, Random rng) {
        Effect e = new Effect(location, EffectType.LIGHT_MOTE);
        e.particleX0 = new float[]{ rng.nextFloat() * (float) (Math.PI * 2) };
        e.particleY0 = new float[]{ (rng.nextFloat() - 0.5f) * 6f };
        e.particleVX = new float[]{ 0.7f + rng.nextFloat() * 0.6f };
        return e;
    }

    public static Effect fireParticle(Point location, Random rng) {
        Effect e = new Effect(location, EffectType.FIRE_PARTICLE);
        e.particleX0 = new float[]{ rng.nextFloat() * (float) (Math.PI * 2) };
        e.particleY0 = new float[]{ 1.0f + rng.nextFloat() * 1.5f };
        e.particleVX = new float[]{ -1.5f + rng.nextFloat() * 3f };
        return e;
    }

    public static Effect explosion(Point location, int radiusTiles, Random rng) {
        Effect e = new Effect(location, EffectType.EXPLOSION);
        int frames = 28;
        e.frameCount = frames;
        int count = 18 + 10 * Math.max(1, radiusTiles);
        e.particleX0 = new float[count];
        e.particleY0 = new float[count];
        e.particleVX = new float[count];
        e.particleVY = new float[count];
        float maxRange = Math.max(1, radiusTiles) * TILE_PX;
        for (int i = 0; i < count; i++) {
            e.particleX0[i] = TILE_PX * 0.5f;
            e.particleY0[i] = TILE_PX * 0.5f;
            float ang = rng.nextFloat() * (float) (Math.PI * 2);
            float frac = 0.4f + rng.nextFloat() * 0.6f;
            float v = (frac * maxRange) / frames;
            e.particleVX[i] = (float) Math.cos(ang) * v;
            e.particleVY[i] = (float) Math.sin(ang) * v;
        }
        return e;
    }

    /** Build a grappling-rope effect from {@code from} to {@code to}.
     *  {@code success == true} -> the rope extends then retracts over the
     *  paired total of {@code extendFrames + retractFrames}; the dragged
     *  mob's {@link com.bjsp123.rl2.event.GameEvent.MobKnockedBack} slide
     *  is queued to start at frame {@code extendFrames} so it visually
     *  rides the retract. {@code success == false} -> the rope holds at
     *  full extent for the second phase then flashes and fades. */
    public static Effect grappleRope(Point from, Point to, boolean success,
                                     int extendFrames, int tailFrames) {
        Effect e = new Effect(from, EffectType.GRAPPLE_ROPE);
        e.endLocation = to;
        e.grappleSuccess = success;
        e.grappleExtendFrames = Math.max(1, extendFrames);
        e.frameCount = Math.max(1, extendFrames + tailFrames);
        return e;
    }

    public static Effect ray(Point from, Point to, EffectTint tint) {
        Effect e = new Effect(from, EffectType.RAY);
        e.endLocation = to;
        e.tint        = tint;
        return e;
    }

    public static Effect blast(Point location) {
        return new Effect(location, EffectType.BLAST);
    }

    public static Effect buffIcon(Point location, Buff.BuffType type, String fallbackText) {
        Effect e = new Effect(location, EffectType.BUFF_ICON);
        e.text = fallbackText;
        e.buffType = type;
        return e;
    }

    /** 32-particle powerup-pickup cloud: each particle has an individual spawn
     *  time in [0, 64] frames (+/-32 stagger), a random offset of +/-16 px in X and
     *  +/-8 px in Y from the tile centre, drifts straight up, turns white, then
     *  fades. Per-particle timing is honoured by {@code FxRenderer.drawParticleBurst}
     *  when {@code particleSpawnFrame} is populated. */
    public static Effect powerupParticles(Point at, EffectTint tint, int count, Random rng) {
        int particleLife = 40;
        int spawnSpread  = 64;   // particles spawn between frame 0 and frame 64
        Effect e = new Effect(at, EffectType.PARTICLE_BURST);
        e.frameCount = spawnSpread + particleLife;
        e.tint = tint;
        e.particleX0         = new float[count];
        e.particleY0         = new float[count];
        e.particleVX         = new float[count];
        e.particleVY         = new float[count];
        e.particleSpawnFrame = new int[count];
        for (int i = 0; i < count; i++) {
            e.particleX0[i]         = 8f + (rng.nextFloat() - 0.5f) * 32f;   // centre +/- 16 px
            e.particleY0[i]         = 8f + (rng.nextFloat() - 0.5f) * 16f;   // centre +/- 8 px
            e.particleVX[i]         = 0f;
            e.particleVY[i]         = 0.25f + rng.nextFloat() * 0.25f;        // gentle upward drift
            e.particleSpawnFrame[i] = rng.nextInt(spawnSpread + 1);            // 0 .. 64
        }
        return e;
    }

    public static Effect particleBurst(Point location, EffectTint tint, int count, Random rng) {
        Effect e = new Effect(location, EffectType.PARTICLE_BURST);
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

    /** Small liquid splash at a character's feet when stepping onto a surface tile.
     *  Particles arc up then fall back under gravity; tint fades from the surface
     *  colour toward white over each particle's lifetime for a washed-out splash look. */
    public static Effect footSplash(Point location, EffectTint tint, Random rng) {
        Effect e = new Effect(location, EffectType.PARTICLE_BURST);
        e.tint = tint;
        e.ignoresFov = true;
        e.particleFadeToWhite = true;
        int count = 4 + rng.nextInt(3);
        e.particleX0 = new float[count];
        e.particleY0 = new float[count];
        e.particleVX = new float[count];
        e.particleVY = new float[count];
        e.particleSpawnFrame = new int[count];
        float cx = TILE_PX * 0.5f;
        float cy = TILE_PX * 0.35f;
        for (int i = 0; i < count; i++) {
            e.particleX0[i] = cx + (rng.nextFloat() - 0.5f) * 7f;
            e.particleY0[i] = cy + (rng.nextFloat() - 0.5f) * 3f;
            e.particleVX[i] = (rng.nextFloat() - 0.5f) * 0.5f;
            e.particleVY[i] = 1.5f + rng.nextFloat() * 1.0f;
            e.particleSpawnFrame[i] = rng.nextInt(3);
        }
        return e;
    }

    /** Particle burst when the player smashes a one-time door. Two layers: a radial
     *  outward spray of shards and a cluster of upward-arcing splinters that rise
     *  4-8 px then fall back under gravity. Pink tint, chunky particles, non-blocking. */
    public static Effect doorBreakBurst(Point location, Random rng) {
        int radialCount  = 14;
        int splashCount  = 16;
        int count        = radialCount + splashCount;
        Effect e = new Effect(location, EffectType.PARTICLE_BURST);
        e.tint         = EffectTint.PINK;
        e.ignoresFov   = true;
        e.particleSize = 3f;
        e.particleX0 = new float[count];
        e.particleY0 = new float[count];
        e.particleVX = new float[count];
        e.particleVY = new float[count];
        float cx = TILE_PX * 0.5f;
        float cy = TILE_PX * 0.35f;   // feet height
        // Radial shards - spread at even angles, moderate outward speed.
        for (int i = 0; i < radialCount; i++) {
            float angle = (float)(i * Math.PI * 2.0 / radialCount) + rng.nextFloat() * 0.4f;
            float speed = 0.5f + rng.nextFloat() * 0.7f;
            e.particleX0[i] = cx + (rng.nextFloat() - 0.5f) * 4f;
            e.particleY0[i] = cy + (rng.nextFloat() - 0.5f) * 3f;
            e.particleVX[i] = (float) Math.cos(angle) * speed;
            e.particleVY[i] = (float) Math.sin(angle) * speed;
        }
        // Upward-arcing splinters - vy chosen so peak height is 4-8 px.
        for (int i = 0; i < splashCount; i++) {
            int j = radialCount + i;
            float h  = 4f + rng.nextFloat() * 4f;
            float vy = (float) Math.sqrt(2.0 * AnimationVars.PARTICLE_GRAVITY * h);
            e.particleX0[j] = cx + (rng.nextFloat() - 0.5f) * 6f;
            e.particleY0[j] = cy + (rng.nextFloat() - 0.5f) * 2f;
            e.particleVX[j] = (rng.nextFloat() - 0.5f) * 0.8f;
            e.particleVY[j] = vy;
        }
        return e;
    }

    /** Item falling into a chasm - revolves, shrinks, and fades at {@code location}.
     *  Non-blocking (excluded from the Animator's freeze-frames tally). */
    public static Effect fallingItem(Point location, Item item) {
        Effect e = new Effect(location, EffectType.FALLING_ITEM);
        e.thrownItem = item;
        return e;
    }

    /** Mob falling into a chasm - revolves, shrinks, and fades at
     *  {@code location} using the mob's sprite. Non-blocking. */
    public static Effect fallingMob(Point location,
                                    com.bjsp123.rl2.model.Mob mob) {
        Effect e = new Effect(location, EffectType.FALLING_MOB);
        e.fallenMob = mob;
        return e;
    }

    public static Effect teleportStreaks(Point location, boolean up, Random rng) {
        int count = 12;
        Effect e = new Effect(location, EffectType.TELEPORT_STREAKS);
        e.tint = EffectTint.GREEN;
        e.particleX0 = new float[count];
        e.particleY0 = new float[count];
        e.particleVX = new float[count];
        e.particleVY = new float[count];
        for (int i = 0; i < count; i++) {
            e.particleX0[i] = 1f + rng.nextFloat() * 14f;
            float speed = 0.7f + rng.nextFloat() * 0.6f;
            if (up) {
                e.particleY0[i] = rng.nextFloat() * 4f;
                e.particleVY[i] =  speed;
            } else {
                e.particleY0[i] = 12f + rng.nextFloat() * 4f;
                e.particleVY[i] = -speed;
            }
            e.particleVX[i] = 0f;
        }
        return e;
    }

    /** Single floating "up" glyph drifting upward and fading from the
     *  given tile. {@code startDelay} is in render frames so a swarm of
     *  arrows can be staggered (level-up uses 10 arrows over ~600 ms). */
    public static Effect upArrow(Point at, EffectTint tint, int startDelay) {
        Effect e = new Effect(at, EffectType.UP_ARROW);
        e.tint       = tint;
        e.text       = TextCatalog.get("effect.levelUp.up");
        e.startDelay = Math.max(0, startDelay);
        return e;
    }

    /** Mob-tinted overlay: redraws {@code mob}'s sprite layered on top
     *  of its tile, with tint cycling grey -> white -> gold over the
     *  effect's lifetime. Used by the LEVEL_UP powerup pickup. */
    public static Effect powerupFlash(com.bjsp123.rl2.model.Mob mob, Point at) {
        Effect e = new Effect(at, EffectType.POWERUP_FLASH);
        e.fallenMob = mob;
        return e;
    }

    /** Physical projectile travelling from {@code from} to {@code to} at the
     *  standard missile speed. The sprite (col 3 of the slash band) is rotated
     *  to face the travel direction at draw time. */
    public static Effect physicalMissile(Point from, Point to) {
        Effect e = new Effect(from, EffectType.PHYSICAL_MISSILE);
        e.endLocation = to;
        e.frameCount = framesForDistance(from, to, com.bjsp123.rl2.world.anim.AnimationVars.MAGIC_MISSILE_PX_PER_FRAME);
        return e;
    }
}
