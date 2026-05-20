package com.bjsp123.rl2.world.render;
import com.bjsp123.rl2.logic.TextCatalog;
import com.bjsp123.rl2.model.Buff;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.audio.SoundManager;
import com.bjsp123.rl2.world.anim.AnimationVars;
import com.bjsp123.rl2.world.render.EffectBuilder.FlashStyle;
import com.bjsp123.rl2.world.render.EffectBuilder.HoverStyle;
import com.bjsp123.rl2.world.render.EffectBuilder.StreakDirection;

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
 *
 * <p><b>Layering.</b> Every factory in this class is a thin game-named wrapper that
 * delegates to one or more {@link EffectBuilder} primitives. Each factory either
 * freezes parameter values as game-visual decisions (specific counts, durations,
 * palettes) or composes multiple primitives into a named game event. Game-name is
 * the value-add; the underlying parameter-space lives in {@link EffectBuilder}.
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
        RED, YELLOW, WHITE, GREEN, BLUE, BROWN, ORANGE, CYAN, PINK, MAUVE
    }

    /** Pixel pitch of one tile — matches the renderer's CELL size. */
    private static final float CELL = 16f;

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
    /** PARTICLE_BURST only (global-t path): velocity retained on each floor bounce
     *  (0 = no bouncing, 0..1 = damped rebound). Floor is the bottom of the tile
     *  cell (particle y = 0). Set this and the standard gravity drives the
     *  damped-bounce trajectory in {@link FxRenderer#drawParticleBurst}. */
    public float particleBounceDamping;
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

    // ====================================================================
    // Game-named factories. Each is a thin wrapper over EffectBuilder that
    // freezes 1-3 game-visual decisions (counts, durations, palettes, etc.)
    // or composes multiple primitives into a single named game event.
    // ====================================================================

    public static Effect floatingText(Point location, String text, EffectTint tint) {
        return EffectBuilder.hoverText(location, text, tint, 0, EffectType.FLOATING_TEXT.frameCount);
    }

    public static Effect thrownItem(Point from, Point to, Item item) {
        return EffectBuilder.arcItem(from, to, item, /*spinDeg*/ 360f, /*endScale*/ 1f,
                /*arcHeightPx*/ CELL * 0.5f, /*blocking*/ true,
                EffectBuilder.framesForDistance(from, to, AnimationVars.THROWN_ITEM_PX_PER_FRAME));
    }

    /** Loot-on-death toss - same arc + spin as {@link #thrownItem} but non-blocking. */
    public static Effect lootToss(Point from, Point to, Item item) {
        return EffectBuilder.arcItem(from, to, item, /*spinDeg*/ 360f, /*endScale*/ 1f,
                /*arcHeightPx*/ CELL * 0.5f, /*blocking*/ false,
                EffectBuilder.framesForDistance(from, to, AnimationVars.THROWN_ITEM_PX_PER_FRAME));
    }

    /** Pickup toss - item flies off {@code from} toward the inventory tab. */
    public static Effect pickupToss(Point from, Item item) {
        return EffectBuilder.pickupToss(from, item);
    }

    /** Attack-flash on a tile, with required {@code startDelay} (pass 0 for none). */
    public static Effect attackFlash(Point at, boolean isPlayer, boolean facesRight,
                                     int startDelay) {
        Effect e = EffectBuilder.flash(at,
                isPlayer ? FlashStyle.ATTACK_PLAYER : FlashStyle.ATTACK_NPC,
                null, startDelay, EffectType.ATTACK_FLASH.frameCount, null);
        e.facesRight = facesRight;
        return e;
    }

    public static Effect knockbackFlash(Point at, int startDelay) {
        return EffectBuilder.flash(at, FlashStyle.KNOCKBACK, null, startDelay,
                EffectType.KNOCKBACK_FLASH.frameCount, null);
    }

    public static Effect surpriseIcon(Point at) {
        return EffectBuilder.flash(at, FlashStyle.SURPRISE, null, 0,
                EffectType.SURPRISE_ICON.frameCount, null);
    }

    /** Foot-dust cloud — random 20..36 frame lifetime for per-puff variety. */
    public static Effect dustCloud(int tileX, int tileY,
                                   float pxX, float pxY,
                                   float vxPxPerFrame, float vyPxPerFrame,
                                   Random rng) {
        return EffectBuilder.driftCloud(tileX, tileY, pxX, pxY, vxPxPerFrame, vyPxPerFrame,
                /*cloudType*/ null, 20 + rng.nextInt(17), rng);
    }

    /** Cloud-layer puff — random 36..53 frame lifetime, type-tinted. */
    public static Effect cloudPuff(int tileX, int tileY,
                                   float pxX, float pxY,
                                   float vxPxPerFrame, float vyPxPerFrame,
                                   Level.Cloud type, Random rng) {
        return EffectBuilder.driftCloud(tileX, tileY, pxX, pxY, vxPxPerFrame, vyPxPerFrame,
                type, 36 + rng.nextInt(18), rng);
    }

    /**
     * Magic-missile projectile (head + trail). A magic missile is conceptually
     * <i>projectile + impact-burst</i>; this factory produces the projectile half.
     * The impact burst is fired separately by the caller's pendingImpact callback
     * (see {@link #wandImpactBurst}).
     *
     * <p>Pass {@code palette = null} for the plain basic-ranged look (24-particle
     * trail); pass a wand-element palette + element-tinted head for the wand cast
     * (36-particle trail). Trail count is derived from whether {@code palette} is
     * non-null. Velocity jitter is also derived (plain trails wobble more loosely;
     * coloured wand trails are tighter).
     */
    public static Effect magicMissile(Point from, Point to,
                                      EffectTint[] palette, EffectTint headTint,
                                      float particleGravity, float particleSize,
                                      boolean bright, Random rng) {
        int trailCount   = (palette == null) ? 24   : 36;
        float velJitter  = (palette == null) ? 1.2f : 0.8f;
        return EffectBuilder.projectile(from, to, palette, headTint,
                particleGravity, particleSize, bright, trailCount, velJitter, rng);
    }

    /** Impact-burst half of a magic missile — 20 element-tinted sparks at the
     *  arrival tile. Element drives the tint via {@link #wandElementTint}. */
    public static Effect wandImpactBurst(Point location, Item.ItemEffect element, Random rng) {
        return EffectBuilder.burst(location, wandElementTint(element),
                /*count*/ 20, /*speedMin*/ 1.5f, /*speedMax*/ 3f,
                /*size*/ 1.5f, /*bright*/ false,
                /*duration*/ EffectType.PARTICLE_BURST.frameCount, rng);
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
        return EffectBuilder.sleepZ(location, rng);
    }

    public static Effect lightMote(Point location, Random rng) {
        return EffectBuilder.lightMote(location, rng);
    }

    public static Effect fireParticle(Point location, Random rng) {
        return EffectBuilder.fireParticle(location, rng);
    }

    public static Effect explosion(Point location, int radiusTiles, Random rng) {
        return EffectBuilder.explosion(location, radiusTiles, /*duration*/ 28, rng);
    }

    /** Grappling-rope beam — {@code success = true} retracts, {@code false} holds
     *  and pulses. The dragged-mob slide is queued separately by the caller. */
    public static Effect grappleRope(Point from, Point to, boolean success,
                                     int extendFrames, int tailFrames) {
        return EffectBuilder.beam(from, to, /*tint*/ null,
                extendFrames, tailFrames, /*holdAndPulse*/ !success,
                /*particleCount*/ 0, /*particleSize*/ 0f,
                Math.max(1, extendFrames + tailFrames));
    }

    public static Effect ray(Point from, Point to, EffectTint tint) {
        return EffectBuilder.beam(from, to, tint, 0, 0, false, 0, 0,
                EffectType.RAY.frameCount);
    }

    public static Effect blast(Point location) {
        return EffectBuilder.flash(location, FlashStyle.BLAST, null, 0,
                EffectType.BLAST.frameCount, null);
    }

    public static Effect buffIcon(Point location, Buff.BuffType type, String fallbackText) {
        return EffectBuilder.hoverSprite(location, type, fallbackText,
                EffectType.BUFF_ICON.frameCount);
    }

    /** Powerup-pickup particle cloud — 64-frame spawn spread, wide initial X jitter. */
    public static Effect powerupParticles(Point at, EffectTint tint, int count, Random rng) {
        return EffectBuilder.fountain(at, tint, count,
                /*spawnSpread*/ 64, /*life*/ 40,
                /*riseSpeedMin*/ 0.25f, /*riseSpeedMax*/ 0.5f,
                /*horizontalJitter*/ 0f, /*fadeToWhite*/ true,
                /*positionJitterX*/ 32f, /*positionJitterY*/ 16f, rng);
    }

    public static Effect particleBurst(Point location, EffectTint tint, int count, Random rng) {
        return EffectBuilder.particleBurst(location, tint, count, rng);
    }

    /** Liquid (or grass) splash at a character's feet. Radial cone 20°..160° with
     *  gravity and a gentle bounce when droplets hit the tile floor. */
    public static Effect footSplash(Point location, EffectTint tint, Random rng) {
        return EffectBuilder.splash(location, tint,
                /*count*/ 8 + rng.nextInt(4),
                /*speedMin*/ 1.2f, /*speedMax*/ 2.2f,
                /*bounceDamping*/ 0.4f, /*particleSize*/ 1.5f,
                /*angleMinDeg*/ 20f, /*angleMaxDeg*/ 160f,
                /*duration*/ 60, rng);
    }

    /**
     * Bundled visual + audio for the player smashing a one-time door: a cyan
     * magic-missile-style burst of sparks at the tile centre, a mauve bouncing
     * splash in a tight vertical cone at the base of the tile, and a crash sound
     * keyed by {@code sfx.world.door.break}.
     *
     * <p>{@code sounds} may be {@code null} (silent — visual only).
     */
    public static void doorBreakEffect(EffectStage stage, SoundManager sounds,
                                       Level level, Point at, Random rng) {
        stage.add(EffectBuilder.burst(at, EffectTint.CYAN,
                /*count*/ 20, /*speedMin*/ 1.5f, /*speedMax*/ 3f,
                /*size*/ 1.5f, /*bright*/ false,
                /*duration*/ EffectType.PARTICLE_BURST.frameCount, rng));
        stage.add(EffectBuilder.splash(at, EffectTint.MAUVE,
                /*count*/ 12, /*speedMin*/ 1.6f, /*speedMax*/ 3f,
                /*bounceDamping*/ 0.5f, /*particleSize*/ 1.5f,
                /*angleMinDeg*/ 70f, /*angleMaxDeg*/ 110f,
                /*duration*/ 72, rng));
        if (sounds != null) sounds.playAt("sfx.world.door.break", level, at);
    }

    public static Effect fallingItem(Point location, Item item) {
        return EffectBuilder.spinShrinkFadeItem(location, item, EffectType.FALLING_ITEM.frameCount);
    }

    public static Effect fallingMob(Point location, Mob mob) {
        return EffectBuilder.spinShrinkFadeMob(location, mob, EffectType.FALLING_MOB.frameCount);
    }

    public static Effect teleportStreaks(Point location, boolean up, Random rng) {
        return EffectBuilder.streaks(location, EffectTint.GREEN, /*count*/ 12,
                /*speedMin*/ 0.7f, /*speedMax*/ 1.3f,
                up ? StreakDirection.UP : StreakDirection.DOWN,
                EffectType.TELEPORT_STREAKS.frameCount, rng);
    }

    /** Single floating "up" glyph drifting upward and fading. {@code startDelay}
     *  staggers a swarm of arrows for the level-up visual. */
    public static Effect upArrow(Point at, EffectTint tint, int startDelay) {
        return EffectBuilder.hoverGlyph(at, TextCatalog.get("effect.levelUp.up"),
                tint, startDelay, EffectType.UP_ARROW.frameCount, HoverStyle.ARROW_RISE);
    }

    /** Mob-tinted overlay — sprite redraw cycling grey → white → gold. */
    public static Effect powerupFlash(Mob mob, Point at) {
        return EffectBuilder.flash(at, FlashStyle.TINT_CYCLE, null, 0,
                EffectType.POWERUP_FLASH.frameCount, mob);
    }

    /** Physical projectile (spear / arrow) — rotated sprite tracing an A → B line. */
    public static Effect physicalMissile(Point from, Point to) {
        return EffectBuilder.arcSprite(from, to, /*sprite*/ null, /*rotationOnly*/ 0f,
                /*arcHeightPx*/ 0f,
                EffectBuilder.framesForDistance(from, to, AnimationVars.MAGIC_MISSILE_PX_PER_FRAME));
    }
}
