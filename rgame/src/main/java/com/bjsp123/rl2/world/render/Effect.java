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
        /** Damage-element floater: rising buff-icon glyph + colored "-N" text
         *  drawn side-by-side. Element-specific (FIRE, POISON, MAGIC, SHOCK).
         *  Same lifetime curve as {@link #FLOATING_TEXT}; the icon is sourced
         *  via {@link Effect#iconAtlasIndex} and the text colour via
         *  {@link Effect#customColor}. */
        DAMAGE_FLOATER(60),
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
        PHYSICAL_MISSILE(36),
        /** Single particle that spirals inward to its anchor tile with a
         *  dim -> bright -> dim Hann-window alpha curve. Active beacons
         *  emit these continuously while in FOV. */
        INWARD_SPIRAL(90),
        /** One-shot screen-spanning brightness pulse. Painted as a
         *  tinted full-viewport quad whose alpha follows a sine curve
         *  (0 -> peak -> 0) over the effect's lifetime. Drawn by
         *  {@code FxRenderer.drawScreenSpaceEffects} once per render
         *  frame, AFTER the per-cell content pass. Used by beacon
         *  activation. */
        LEVEL_FLICKER(60),
        /** Gem-hearth ember (RL-51): a dot that appears near a point above the
         *  hearth, rises while fading and elongating into a vertical line.
         *  Emitted continuously from GEM_HEARTH tiles in FOV. */
        HEARTH_SPARK(70),
        /** Gem-scroll cast (RL-50): a tinted burst around the reader. Style
         *  ({@link Effect#scrollStyle}) selects sparks / expanding glow /
         *  whirlpool; colour comes from {@link Effect#tint}. */
        SCROLL_CAST(40),
        /** Enchant/upgrade showcase (RL-50): screen-space - the chosen item
         *  blown up in the centre with a glow behind and a spark shower. The
         *  item is in {@link Effect#thrownItem}, the glow tint in {@link Effect#tint}. */
        ENCHANT_SHOWCASE(80),
        /** Item-birth burst (RL-50): tile-anchored glow halo + spark shower
         *  behind a conjured item sprite. The item is in {@link Effect#thrownItem},
         *  the glow tint in {@link Effect#tint}; drawn in the world pass. */
        ITEM_BIRTH(54);

        public final int frameCount;

        EffectType(int frameCount) {
            this.frameCount = frameCount;
        }
    }

    public enum EffectTint {
        RED, YELLOW, WHITE, GREEN, BLUE, BROWN, ORANGE, CYAN, PINK, MAUVE
    }

    /** Particle-sprite selector for effects that paint small particles
     *  (burst / splash / fountain / motes / spirals). Source cells live
     *  in {@code sprites/buffs16.png} as a 2x3 grid right of the surprise
     *  sprite; loaded via
     *  {@link com.bjsp123.rl2.world.render.BuffIcons#particleRegion(int)}.
     *  <p>{@link #STARS} / {@link #DROPS} are GROUP values - the builder
     *  resolves them to a specific {@code STAR_*} / {@code DROP_*} variant
     *  via the per-effect {@code Random} at construction time so all
     *  particles in one effect share a single sprite (cheap renderer + a
     *  bit of variation across spawns). Pass a specific variant if you
     *  want to lock the look. */
    public enum ParticleShape {
        STARS, DROPS,
        STAR_0, STAR_1, STAR_2, STAR_3,
        DROP_0, DROP_1;

        /** Resolve a group value to a concrete variant. Specific values
         *  pass through unchanged. */
        public ParticleShape resolve(java.util.Random rng) {
            if (this == STARS) {
                int i = rng == null ? 0 : rng.nextInt(4);
                return new ParticleShape[]{STAR_0, STAR_1, STAR_2, STAR_3}[i];
            }
            if (this == DROPS) {
                int i = rng == null ? 0 : rng.nextInt(2);
                return new ParticleShape[]{DROP_0, DROP_1}[i];
            }
            return this;
        }

        /** Atlas index (0..5) of this shape's source cell. -1 for unresolved
         *  group values (caller must call {@link #resolve(java.util.Random)}
         *  first). */
        public int atlasIndex() {
            return switch (this) {
                case STAR_0 -> 0;
                case STAR_1 -> 1;
                case DROP_0 -> 2;
                case STAR_2 -> 3;
                case STAR_3 -> 4;
                case DROP_1 -> 5;
                default     -> -1;
            };
        }
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

    /** Vertical pixel offset applied on top of the tile-anchor baseline.
     *  Lets the same effect be raised above the tile centre - e.g. beacon
     *  motes / spirals emit from the lit upper half of the 2-tall sprite
     *  rather than the floor cell. 0 = anchored at tile centre as
     *  before. */
    public float pixelOffsetY;

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
    /** Sprite each particle is drawn with. Resolved to a concrete variant
     *  by the builder so {@code FxRenderer} can do a single
     *  {@link com.bjsp123.rl2.world.render.BuffIcons#particleRegion} lookup.
     *  {@code null} = legacy whiteRegion stamp (kept as a fallback). */
    public ParticleShape particleShape;
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

    /** SCROLL_CAST style selector. */
    public static final int SCROLL_SPARKS = 0, SCROLL_GLOW = 1, SCROLL_WHIRLPOOL = 2;
    /** SCROLL_CAST only: which {@link #SCROLL_SPARKS}/{@code GLOW}/{@code WHIRLPOOL} style. */
    public int scrollStyle;

    /** DAMAGE_FLOATER only: flat atlas index into {@code buffs16.png}'s buff-
     *  icon grid (col + row * 20). Negative means "no icon, draw text only". */
    public int iconAtlasIndex = -1;
    /** DAMAGE_FLOATER only: text colour. Overrides the {@link EffectTint}
     *  switch so the floater can pick up the live {@link com.bjsp123.rl2.ui.v2.UIVars}
     *  damage palette. */
    public com.badlogic.gdx.graphics.Color customColor;

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

    /** Element-aware damage floater: rising buff-icon glyph + colored "-N"
     *  text. PHYSICAL falls back to the plain {@link #floatingText} (red,
     *  no icon) since that's the user-requested visual contract. */
    public static Effect damageFloater(Point location, int amount,
                                       com.bjsp123.rl2.logic.MobSystem.DamageElement element) {
        com.badlogic.gdx.graphics.Color color;
        int iconIdx;
        switch (element) {
            case SHOCK      -> { color = com.bjsp123.rl2.ui.v2.UIVars.DAMAGE_SHOCK;  iconIdx = 23; }
            case POISON     -> { color = com.bjsp123.rl2.ui.v2.UIVars.DAMAGE_POISON; iconIdx = 7;  }
            case FIRE       -> { color = com.bjsp123.rl2.ui.v2.UIVars.DAMAGE_FIRE;   iconIdx = 0;  }
            case MAGIC      -> { color = com.bjsp123.rl2.ui.v2.UIVars.DAMAGE_MAGIC;  iconIdx = 4;  }
            default         -> { color = com.bjsp123.rl2.ui.v2.UIVars.DAMAGE_PHYSICAL; iconIdx = -1; }
        }
        return EffectBuilder.damageFloater(location, "-" + amount, color, iconIdx,
                EffectType.DAMAGE_FLOATER.frameCount);
    }

    /** Physical-damage floater with an explicit buff-atlas icon - used for
     *  knockback wall-slam (slot 24) and chasm fall (slot 25). Same red
     *  text colour as the plain physical floater; the icon distinguishes
     *  the cause from a generic melee hit. */
    public static Effect damageFloaterPhysical(Point location, int amount, int iconAtlasIndex) {
        return EffectBuilder.damageFloater(location, "-" + amount,
                com.bjsp123.rl2.ui.v2.UIVars.DAMAGE_PHYSICAL, iconAtlasIndex,
                EffectType.DAMAGE_FLOATER.frameCount);
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

    /** Arrival mist (RL-56 intro): a dense ring of untinted dust puffs blooming
     *  outward from {@code at} as the player materialises, each drifting away
     *  from the centre and fading so the cloud expands and thins to reveal the
     *  player. A composite of {@link #cloudPuff}, added straight to the stage. */
    public static void arrivalCloud(EffectStage stage, Point at, Random rng) {
        final float cell = LevelRenderer.TILE_SIZE;
        final float cx = (at.tileX() + 0.5f) * cell;
        final float cy = (at.tileY() + 0.5f) * cell;
        final int puffs = 16;
        for (int i = 0; i < puffs; i++) {
            double angle = (i / (double) puffs) * Math.PI * 2.0
                    + (rng.nextDouble() - 0.5) * 0.5;
            float radius = cell * (0.15f + rng.nextFloat() * 0.55f);
            float pxX = cx + (float) Math.cos(angle) * radius;
            float pxY = cy + (float) Math.sin(angle) * radius;
            // Drift radially outward (slightly slower vertically, faint upward
            // bias) so the cloud opens like parting smoke rather than scattering.
            float speed = 0.45f + rng.nextFloat() * 0.5f;
            float vx = (float) Math.cos(angle) * speed;
            float vy = (float) Math.sin(angle) * speed * 0.6f + 0.1f;
            stage.add(cloudPuff(at.tileX(), at.tileY(), pxX, pxY, vx, vy,
                    /*type*/ null, rng));
        }
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

    /** Gem-hearth ember (RL-51): rises from ~16 px above the hearth's sprite
     *  centre, fading and stretching into a vertical line as it climbs. */
    public static Effect hearthSpark(Point location, Random rng) {
        return EffectBuilder.hearthSpark(location, rng);
    }

    /** Single inward-spiral particle anchored at {@code location}'s tile
     *  but lifted 32 px above the sprite base so the swirl forms around the
     *  beacon's lit upper half. Wider radius than a single tile so the
     *  spiral reads as a halo rather than a tight curl. Ambient emission
     *  from active beacons; spawned on a real-time cadence by
     *  {@code LevelSystem.tickLightMotesRealTime}. */
    public static Effect inwardSpiralParticle(Point location, Random rng) {
        return EffectBuilder.inwardSpiralParticle(location,
                /*startRadiusPx*/ 28f, /*pixelOffsetY*/ 28f, rng);
    }

    /** Light mote lifted to match the beacon's spiral centre (28 px above
     *  the sprite base) instead of the tile centre, so the two ambient
     *  particle streams share an origin. */
    public static Effect beaconLightMote(Point location, Random rng) {
        Effect e = EffectBuilder.lightMote(location, rng);
        e.pixelOffsetY = 28f;
        return e;
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

    /** Buff-expired floater: the dying buff's own icon on the left and the
     *  CANCELLED glyph (atlas slot 26) on the right - reuses the damage-
     *  floater renderer (icon + text-as-icon) by passing the buff's icon
     *  index for the left slot and a "x" text. The buff's icon makes which
     *  buff ended obvious; the CANCELLED feel comes from the "x" + the
     *  greyed text colour. ~30 frames, no sound. */
    public static Effect buffExpiredIcon(Point location, Buff.BuffType type) {
        int iconIdx = com.bjsp123.rl2.world.render.BuffIcons.iconIndexFor(type);
        if (iconIdx < 0) iconIdx = 26;     // fall back to CANCELLED-only
        // Use a shorter frame count than the standard damage-floater so the
        // "buff faded" beat reads as a quick acknowledgement, not a hit.
        return EffectBuilder.damageFloater(location, "x",
                com.bjsp123.rl2.ui.v2.UIVars.TEXT_DIM, iconIdx,
                30);
    }

    /** Powerup-pickup particle cloud — 64-frame spawn spread, wide initial X jitter. */
    public static Effect powerupParticles(Point at, EffectTint tint, int count, Random rng) {
        return EffectBuilder.fountain(at, tint, count,
                /*spawnSpread*/ 64, /*life*/ 40,
                /*riseSpeedMin*/ 0.25f, /*riseSpeedMax*/ 0.5f,
                /*horizontalJitter*/ 0f, /*fadeToWhite*/ true,
                /*positionJitterX*/ 32f, /*positionJitterY*/ 16f, rng);
    }

    /** Gem-scroll cast around the reader (RL-50): a {@code style}
     *  ({@link #SCROLL_SPARKS}/{@code GLOW}/{@code WHIRLPOOL}) burst tinted by
     *  the scroll's colour. */
    public static Effect scrollCast(Point at, EffectTint tint, int style, Random rng) {
        return EffectBuilder.scrollCast(at, tint, style, rng);
    }

    /** Enchant/upgrade showcase (RL-50): screen-space blow-up of the chosen
     *  {@code item} with a {@code glow}-tinted halo and spark shower. */
    public static Effect enchantShowcase(Item item, EffectTint glow, Random rng) {
        return EffectBuilder.enchantShowcase(item, glow, rng);
    }

    /** Item-birth burst (RL-50 creation scrolls): a tile-anchored {@code glow}
     *  halo + spark shower behind the conjured {@code item} sprite at
     *  {@code tile}, so the player sees what was created and where it landed. */
    public static Effect itemBirth(Point tile, Item item, EffectTint glow, Random rng) {
        return EffectBuilder.itemBirth(tile, item, glow, rng);
    }

    public static Effect particleBurst(Point location, EffectTint tint, int count, Random rng) {
        return EffectBuilder.particleBurst(location, tint, count, rng);
    }

    /** Continuous body-drip for "dripping" buffs (OILY / WET / CHILLED / BLEEDING) -
     *  RL-44. A few drops appear at random spots across the mob's body and fall, fading;
     *  emitted repeatedly by the buff-particle tick so the drip reads as constant. */
    public static Effect buffDrip(Point location, EffectTint tint, Random rng) {
        return EffectBuilder.drip(location, tint,
                /*count*/ 3,
                /*spawnSpreadFrames*/ 14,
                /*particleLifeFrames*/ 22,
                ParticleShape.DROPS, rng);
    }

    /** Liquid (or grass) splash at a character's feet. Tight upward cone with
     *  gravity and a small bounce - kept brief so it reads as a footstep, not
     *  a sustained spray. */
    public static Effect footSplash(Point location, EffectTint tint, Random rng) {
        return EffectBuilder.splash(location, tint,
                /*count*/ 5 + rng.nextInt(3),
                /*speedMin*/ 0.7f, /*speedMax*/ 1.3f,
                /*bounceDamping*/ 0.25f, /*particleSize*/ 1.5f,
                /*angleMinDeg*/ 55f, /*angleMaxDeg*/ 125f,
                /*duration*/ 24,
                /*shape*/ ParticleShape.DROPS, rng);
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

    /** Beacon activation visual. Three layers composed from primitives:
     *  a bright yellow upward fountain rising from the beacon's top cell,
     *  a wide white ignition burst, and a screen-spanning warm flicker
     *  pulse so the player feels the beacon "ignite" the level. */
    public static void beaconActivation(EffectStage stage, Point at, Random rng) {
        // Top cell of the beacon - the fountain rises from the upper sprite half,
        // which sits one tile north of the anchor cell.
        Point top = new Point(at.tileX(), at.tileY() + 1);
        stage.add(EffectBuilder.fountain(top, EffectTint.YELLOW,
                /*count*/ 40,
                /*spawnSpread*/ 30, /*life*/ 50,
                /*riseSpeedMin*/ 0.7f, /*riseSpeedMax*/ 1.6f,
                /*horizontalJitter*/ 0.9f, /*fadeToWhite*/ true,
                /*positionJitterX*/ 6f, /*positionJitterY*/ 4f, rng));
        stage.add(EffectBuilder.burst(top, EffectTint.WHITE,
                /*count*/ 24, /*speedMin*/ 1.4f, /*speedMax*/ 2.8f,
                /*size*/ 2.0f, /*bright*/ true,
                /*duration*/ EffectType.PARTICLE_BURST.frameCount, rng));
        stage.add(EffectBuilder.flickerLevel(top, EffectTint.YELLOW,
                EffectType.LEVEL_FLICKER.frameCount));
    }

    /** Death outro (RL-56): the player's soul leaving as a vertical column of
     *  light that shoots straight up and quickly vanishes - "the soul returning
     *  to the top level". A tight, fast white core shaft launched together,
     *  wrapped in a slightly wider pale-blue halo for body. Composite, added
     *  straight to the stage like {@link #beaconActivation}. */
    public static void columnOfLight(EffectStage stage, Point at, Random rng) {
        // Core shaft: many bright-white particles launched together (tiny spawn
        // spread, near-zero horizontal jitter) straight up, fast, short-lived -
        // a coherent column that rises and vanishes quickly.
        stage.add(EffectBuilder.fountain(at, EffectTint.WHITE,
                /*count*/ 64, /*spawnSpread*/ 5, /*life*/ 34,
                /*riseMin*/ 3.8f, /*riseMax*/ 5.4f,
                /*horizontalJitter*/ 0.12f, /*fadeToWhite*/ true,
                /*posJitterX*/ 3.5f, /*posJitterY*/ 3f, rng));
        // Pale-blue halo: a touch wider + dimmer so the shaft has body.
        stage.add(EffectBuilder.fountain(at, EffectTint.CYAN,
                /*count*/ 30, /*spawnSpread*/ 7, /*life*/ 30,
                /*riseMin*/ 3.2f, /*riseMax*/ 4.6f,
                /*horizontalJitter*/ 0.35f, /*fadeToWhite*/ true,
                /*posJitterX*/ 8f, /*posJitterY*/ 3f, rng));
    }

    /** Player-side teleport visual at the source cell - upward green streaks,
     *  same family as {@link #teleportStreaks} so beacon teleports look like
     *  the existing mob teleport. */
    public static Effect playerTeleportOut(Point location, Random rng) {
        return teleportStreaks(location, /*up=*/true, rng);
    }

    /** Player-side teleport visual at the destination cell - downward green
     *  streaks settling onto the arrival tile. */
    public static Effect playerTeleportIn(Point location, Random rng) {
        return teleportStreaks(location, /*up=*/false, rng);
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
