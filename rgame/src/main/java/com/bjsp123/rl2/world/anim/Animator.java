package com.bjsp123.rl2.world.anim;
import com.bjsp123.rl2.event.GameEvent;
import com.bjsp123.rl2.logic.MobSystem;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.world.render.Effect;
import com.bjsp123.rl2.world.render.EffectStage;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import com.bjsp123.rl2.logic.TurnSystem;

/**
 * Per-render-frame coordinator for in-world animations. Owns the {@link AnimQueue}
 * (tick gate), the {@link EffectStage} (active visual effects), the per-mob
 * {@link MobAnimState} map, the ghost list for dying mobs, and the per-frame fire-/
 * sleep-/teleport-particle cadences. Fed by {@code Level.events} after every
 * {@code TurnSystem.tick}.
 *
 * <p>All visual state lives on this side of the rlib/rgame boundary; {@code rlib}
 * has no awareness of render frames or ms.
 */
public final class Animator {

    public final AnimQueue queue = new AnimQueue();
    public final EffectStage stage = new EffectStage();

    private final IdentityHashMap<Mob, MobAnimState> states = new IdentityHashMap<>();
    private final List<Ghost> ghosts = new ArrayList<>();
    private final List<PendingImpact> pendingImpacts = new ArrayList<>();
    private static final Random RNG = new Random();

    /** Wall-clock emit interval for fire-particle effects. Mirrors the legacy
     *  {@code FireSystem.FIRE_PARTICLE_INTERVAL_MS}. */
    private static final int FIRE_PARTICLE_INTERVAL_MS     = 200;
    private static final int FIRE_PARTICLE_MOB_INTERVAL_MS = 100;
    /** Wall-clock window for sleep-Z emission cadence (1.2 s — 2.0 s). */
    private static final int SLEEP_Z_MIN_MS   = 1200;
    private static final int SLEEP_Z_RANGE_MS = 800;

    public MobAnimState stateOf(Mob mob) {
        if (mob == null) return null;
        MobAnimState s = states.get(mob);
        if (s == null) {
            s = new MobAnimState();
            states.put(mob, s);
        }
        return s;
    }

    public List<Ghost> ghosts() { return ghosts; }

    /** Per-render-frame: advance per-mob anims, ghosts, the freeze queue, the effect
     *  stage, fire-pending-impact callbacks for completing projectiles, and the
     *  real-time-driven teleport-fade / burning / asleep particle cadences.
     *
     *  <p>The frame-counter section runs {@code framesPerRender()} times per render
     *  frame so the user-facing "animation speed" setting (1×, 2×, 4×) shortens every
     *  authored animation duration uniformly. The real-time {@code dtMs} drain is
     *  pre-multiplied by the same factor so fire-particle / sleep-Z / teleport-fade
     *  cadences keep pace with the frame-counter speedup. */
    public void tick(Level level, int dtMs) {
        // queue.tick() is driven by PlayScreen at the top of render(), BEFORE the
        // game-tick gate check, so a step that ends "this frame" can immediately
        // chain into the next one without leaving a stationary gap.
        int n = framesPerRender();
        for (int step = 0; step < n; step++) {
            for (MobAnimState s : states.values()) {
                if (s.delayFrames > 0) { s.delayFrames--; continue; }
                if (s.stepTotal > 0) {
                    s.stepFrame++;
                    if (s.stepFrame >= s.stepTotal) {
                        s.stepFrame = 0;
                        s.stepTotal = 0;
                        s.stepFromDx = 0f;
                        s.stepFromDy = 0f;
                    }
                }
                if (s.animEndFrame > 0) {
                    s.animFrame++;
                    if (s.animFrame >= s.animEndFrame) {
                        s.animFrame = 0;
                        s.animEndFrame = 0;
                        s.animPeakFrame = 0;
                        s.animPeakX = 0f;
                        s.animPeakY = 0f;
                    }
                }
                if (s.spawnTotalFrames > 0) {
                    s.spawnFrame++;
                    if (s.spawnFrame >= s.spawnTotalFrames) {
                        s.spawnFrame = 0;
                        s.spawnTotalFrames = 0;
                    }
                }
            }
            ghosts.removeIf(g -> { g.frame++; return g.done(); });
            // Fire pending impacts at the moment their carrier effect's NEXT advance would
            // remove it — mirrors PlayScreen.resolveCompletingMissiles' "frame + 1 ==
            // totalFrames" check. The impact runs before the effect itself ticks so the
            // hit lines up with the last visible frame. Newly-emitted events are consumed
            // in the same step so the impact's freeze contribution lands before the next
            // render frame's controller.tick (otherwise an explosion / surface change
            // wouldn't block AI turns triggered the same frame).
            firePendingImpacts(level);
            stage.tick();
        }
        int scaledDtMs = dtMs * n;
        if (level != null && scaledDtMs > 0) {
            tickTeleportFades(level, scaledDtMs);
            tickBurningParticles(level, scaledDtMs);
            tickSleepZs(level, scaledDtMs);
        }
    }

    /** Drain all events emitted during the previous {@code TurnSystem.tick}, populating
     *  per-mob anim state and pushing visual effects into the stage. */
    public void consume(Level level) {
        if (level.events == null || level.events.isEmpty()) return;
        for (GameEvent ev : level.events) {
            if      (ev instanceof GameEvent.MobMoved m)              onMobMoved(level, m);
            else if (ev instanceof GameEvent.MobMeleeAttacked m)      onMobMeleeAttacked(level, m);
            else if (ev instanceof GameEvent.MobHitFlinched m)        onMobHitFlinched(level, m);
            else if (ev instanceof GameEvent.MobKilled m)             onMobKilled(m);
            else if (ev instanceof GameEvent.MobTeleported m)         onMobTeleported(m);
            else if (ev instanceof GameEvent.MagicMissileFired m)     onMagicMissileFired(level, m);
            else if (ev instanceof GameEvent.WandMissileFired m)      onWandMissileFired(level, m);
            else if (ev instanceof GameEvent.WandRayFired m)          onWandRayFired(level, m);
            else if (ev instanceof GameEvent.ItemThrown m)            onItemThrown(level, m);
            else if (ev instanceof GameEvent.DamageDealt m)           onDamageDealt(level, m);
            else if (ev instanceof GameEvent.HealApplied m)           onHealApplied(level, m);
            else if (ev instanceof GameEvent.MobTamed m)              onMobTamed(level, m);
            else if (ev instanceof GameEvent.BuffApplied m)           onBuffApplied(level, m);
            else if (ev instanceof GameEvent.BuffRemoved m)           { /* no visual */ }
            else if (ev instanceof GameEvent.BlastEffect m)           onBlastEffect(level, m);
            else if (ev instanceof GameEvent.ExplosionEffect m)       onExplosionEffect(level, m);
            else if (ev instanceof GameEvent.LightMoteSpawn m)        stage.add(Effect.lightMote(m.pos(), RNG));
            else if (ev instanceof GameEvent.WandImpactBurst m)       onWandImpactBurst(level, m);
            else if (ev instanceof GameEvent.PotionBurst m)           onPotionBurst(level, m);
            else if (ev instanceof GameEvent.MobSpawned m)            onMobSpawned(level, m);
            else if (ev instanceof GameEvent.SurfaceChanged m)        onSurfaceChanged(level, m);
            else if (ev instanceof GameEvent.VegetationChanged m)     onVegetationChanged(level, m);
            else if (ev instanceof GameEvent.RainbowBurst m)          onRainbowBurst(level, m);
            else if (ev instanceof GameEvent.PeriodicBuffDamage m)    onPeriodicBuffDamage(level, m);
            else if (ev instanceof GameEvent.LootDropped m)           onLootDropped(m);
            else if (ev instanceof GameEvent.ItemPickedUp m)          onItemPickedUp(m);
            else if (ev instanceof GameEvent.MobKnockedBack m)        onMobKnockedBack(level, m);
            else if (ev instanceof GameEvent.ItemFallingIntoChasm m)  onItemFallingIntoChasm(m);
            // MobIgnited / MobExtinguished / MobSlept / MobWoke are polled instead of
            // event-driven (the Animator scans BuffSystem and StateOfMind each tick).
        }
        level.events.clear();
    }

    /** Public helper used by PlayScreen to spawn player-side projectiles directly. */
    public void addPendingImpact(Effect effect, Runnable onComplete) {
        pendingImpacts.add(new PendingImpact(effect, onComplete));
    }

    // ── Event handlers ─────────────────────────────────────────────────────────────

    private void onMobMoved(Level level, GameEvent.MobMoved m) {
        // Skip step animation for off-screen mobs entirely. Without this gate every
        // ant stepping in an unseen ant hill bumps freezeFrames and stalls the
        // player's autotravel for a few frames — autotravel becomes jerky in direct
        // proportion to how busy the rest of the level is. Mobs that aren't visible
        // when they move just snap to their new position the next time the player
        // can see them, which is standard roguelike behaviour.
        if (!MobSystem.isVisibleToPlayer(level, m.mob())) return;
        MobAnimState s = stateOf(m.mob());
        int frames = stepFramesFor(m.mob());
        s.stepFromDx = (float) (m.fromX() - m.toX());
        s.stepFromDy = (float) (m.fromY() - m.toY());
        s.stepFrame  = 0;
        s.stepTotal  = frames;
        s.delayFrames = queue.concurrent(frames);
    }

    private void onMobMeleeAttacked(Level level, GameEvent.MobMeleeAttacked m) {
        Mob attacker = m.attacker(), target = m.target();
        if (attacker == null || target == null
                || attacker.position == null || target.position == null) return;
        boolean visible = MobSystem.isVisibleToPlayer(level, attacker)
                || MobSystem.isVisibleToPlayer(level, target);
        if (!visible) return;
        // Lunge: forward then back along the attacker→target axis.
        float dx = target.position.tileX() - attacker.position.tileX();
        float dy = target.position.tileY() - attacker.position.tileY();
        float n  = (float) Math.hypot(dx, dy);
        if (n > 0f) {
            startAnim(stateOf(attacker),
                    dx / n * MELEE_LUNGE_PX, dy / n * MELEE_LUNGE_PX,
                    LUNGE_OUT_FRAMES, LUNGE_OUT_FRAMES + LUNGE_BACK_FRAMES,
                    /*concurrent=*/false);
        }
        // Attack-flash sprite next to the attacker. If multiple mobs attack in the
        // same tick, each one's lunge is queued sequentially (different start delays
        // on the AnimQueue), and we use the same start delay for the slash so the
        // flashes appear in sequence with their lunges instead of overlapping.
        boolean isPlayer = attacker.behavior == Mob.Behavior.PLAYER;
        int flashDelay = (n > 0f) ? stateOf(attacker).delayFrames : 0;
        stage.add(Effect.attackFlash(attacker.position, isPlayer,
                attacker.facingEast, flashDelay));
        // Per-hit visuals (burst on hit, "miss" feedback on miss). The DamageDealt event
        // (emitted later in the tick from processAttack) drives the floating "−N" /
        // "miss" / "blunt" text, so we only handle the bursts here.
        if (m.hit() && m.dealt() > 0) {
            stage.add(Effect.particleBurst(target.position, Effect.EffectTint.RED, 10, RNG));
        } else if (!m.hit()) {
            stage.add(Effect.particleBurst(target.position, Effect.EffectTint.WHITE, 7, RNG));
        }
    }

    private void onMobHitFlinched(Level level, GameEvent.MobHitFlinched m) {
        Mob target = m.victim(), src = m.hitSource();
        if (target == null || src == null
                || target.position == null || src.position == null) return;
        // Same off-screen gate as onMobMoved — flinch animation queues a sequential
        // freeze frame, so flinches happening in unseen rooms would stall the
        // player's autotravel for FLINCH_OUT_FRAMES + FLINCH_BACK_FRAMES per hit.
        boolean visible = MobSystem.isVisibleToPlayer(level, target)
                || MobSystem.isVisibleToPlayer(level, src);
        if (!visible) return;
        float dx = target.position.tileX() - src.position.tileX();
        float dy = target.position.tileY() - src.position.tileY();
        float n  = (float) Math.hypot(dx, dy);
        if (n <= 0f) return;
        startAnim(stateOf(target),
                dx / n * HIT_FLINCH_PX, dy / n * HIT_FLINCH_PX,
                FLINCH_OUT_FRAMES, FLINCH_OUT_FRAMES + FLINCH_BACK_FRAMES,
                /*concurrent=*/true);
    }

    private void onMobKilled(GameEvent.MobKilled m) {
        if (!m.visibleAtKill()) return;
        boolean facingEast = m.mob() != null && m.mob().facingEast;
        ghosts.add(new Ghost(m.mob(), m.x(), m.y(), facingEast));
        queue.concurrent(MobAnimState.DEATH_TOTAL_FRAMES);
    }

    private void onMobTeleported(GameEvent.MobTeleported m) {
        if (m.mob() == null) return;
        MobAnimState s = stateOf(m.mob());
        s.teleportFromX = m.fromX();
        s.teleportFromY = m.fromY();
        s.teleportFadeMs = MobSystem.TELEPORT_FADE_TOTAL_MS;
        stage.add(Effect.teleportStreaks(new Point(m.fromX(), m.fromY()), /*up=*/true, RNG));
    }

    private void onMagicMissileFired(Level level, GameEvent.MagicMissileFired m) {
        Effect missile = Effect.magicMissile(m.from(), m.to(), RNG);
        stage.add(missile);
        if (m.trajectoryVisible()) queue.concurrent(missile.totalFrames());
        Mob caster = m.caster();
        int damage = m.damage();
        Point target = m.to();
        pendingImpacts.add(new PendingImpact(missile,
                () -> applyMissileImpact(level, caster, target, damage)));
    }

    private void onWandMissileFired(Level level, GameEvent.WandMissileFired m) {
        Effect missile = buildWandMissile(m.from(), m.to(), m.element());
        stage.add(missile);
        if (m.trajectoryVisible()) queue.concurrent(missile.totalFrames());
        Mob caster = m.caster();
        Point target = m.to();
        Item.ItemEffect element = m.element();
        Item wand = m.wand();
        int effLvl = m.effectiveLevel();
        pendingImpacts.add(new PendingImpact(missile,
                () -> com.bjsp123.rl2.logic.ItemSystem.applyWandImpact(level, caster, target, element, wand, effLvl)));
    }

    private void onWandRayFired(Level level, GameEvent.WandRayFired m) {
        Effect ray = Effect.ray(m.from(), m.to(), Effect.EffectTint.WHITE);
        stage.add(ray);
        if (m.trajectoryVisible()) queue.concurrent(ray.totalFrames());
        Mob caster = m.caster();
        Point target = m.to();
        Item.ItemEffect element = m.element();
        Item wand = m.wand();
        int effLvl = m.effectiveLevel();
        pendingImpacts.add(new PendingImpact(ray,
                () -> com.bjsp123.rl2.logic.ItemSystem.applyWandImpact(level, caster, target, element, wand, effLvl)));
    }

    private void onItemThrown(Level level, GameEvent.ItemThrown m) {
        Item it = m.item();
        Effect thrown = Effect.thrownItem(m.from(), m.to(), it);
        stage.add(thrown);
        if (m.trajectoryVisible()) queue.concurrent(thrown.totalFrames());
        // Throw resolution happens in rlib at fire time; no PendingImpact needed.
    }

    /** Loot tossed off a dying mob — arc the item from the corpse to its
     *  landing tile. Non-blocking (LOOT_TOSS is excluded from the freeze tally). */
    private void onLootDropped(GameEvent.LootDropped m) {
        if (m.item() == null || m.from() == null || m.to() == null) return;
        stage.add(Effect.lootToss(m.from(), m.to(), m.item()));
    }

    /** Item picked up by a mob — arc the item off its tile toward the bottom-
     *  right of the screen so it reads as flying into the inventory.
     *  Non-blocking. Skipped when the picker isn't visible. */
    private void onItemPickedUp(GameEvent.ItemPickedUp m) {
        if (m.item() == null || m.from() == null) return;
        stage.add(Effect.pickupToss(m.from(), m.item()));
    }

    /** Mob was knocked back — slide the sprite from {@code start} to {@code end},
     *  gated as a blocking sequential animation. A small particle burst fires at
     *  the start tile to signal the impact. Off-screen knockbacks are skipped.
     *  Knockbacks use a snappier per-tile pace than regular movement so the shove
     *  reads as sudden rather than a normal stride. */
    private void onMobKnockedBack(Level level, GameEvent.MobKnockedBack m) {
        Mob mob = m.mob();
        if (mob == null) return;
        Point start = m.start(), end = m.end();
        if (start == null || end == null) return;
        boolean visible = MobSystem.isVisibleToPlayer(level, mob)
                || visibleAt(level, start) || visibleAt(level, end);
        if (!visible) return;
        int ddx = start.tileX() - end.tileX();
        int ddy = start.tileY() - end.tileY();
        int dist = Math.max(Math.abs(ddx), Math.abs(ddy));
        if (dist == 0) return;
        int frames = Math.max(STEP_FRAMES_MIN, KNOCKBACK_FRAMES_PER_TILE * dist);
        MobAnimState s = stateOf(mob);
        s.stepFromDx = (float) ddx;
        s.stepFromDy = (float) ddy;
        s.stepFrame  = 0;
        s.stepTotal  = frames;
        s.delayFrames = queue.sequential(frames);
        stage.add(Effect.particleBurst(start, Effect.EffectTint.WHITE, 10, RNG));
    }

    /** Item fell into a chasm — revolve-shrink-fade at its tile. Non-blocking. */
    private void onItemFallingIntoChasm(GameEvent.ItemFallingIntoChasm m) {
        if (m.item() == null || m.position() == null) return;
        stage.add(Effect.fallingItem(m.position(), m.item()));
    }

    private void onDamageDealt(Level level, GameEvent.DamageDealt m) {
        Mob target = m.target();
        if (target == null || target.position == null) return;
        if (!MobSystem.isVisibleToPlayer(level, target)) return;
        switch (m.message()) {
            case HIT   -> stage.add(Effect.floatingText(target.position, "-" + m.amount(), Effect.EffectTint.RED));
            case MISS  -> stage.add(Effect.floatingText(target.position, "miss", Effect.EffectTint.YELLOW));
            case BLUNT -> stage.add(Effect.floatingText(target.position, "blunt", Effect.EffectTint.WHITE));
            case ENVIRONMENTAL ->
                    stage.add(Effect.floatingText(target.position, "-" + m.amount(), Effect.EffectTint.RED));
        }
    }

    private void onHealApplied(Level level, GameEvent.HealApplied m) {
        Mob mob = m.mob();
        if (mob == null || mob.position == null) return;
        if (!MobSystem.isVisibleToPlayer(level, mob)) return;
        stage.add(Effect.floatingText(mob.position, "+" + m.amount(), Effect.EffectTint.GREEN));
    }

    private void onMobTamed(Level level, GameEvent.MobTamed m) {
        Mob mob = m.mob();
        if (mob == null || mob.position == null) return;
        if (!MobSystem.isVisibleToPlayer(level, mob)) return;
        stage.add(Effect.floatingText(mob.position, "tame!", Effect.EffectTint.GREEN));
    }

    private void onBuffApplied(Level level, GameEvent.BuffApplied m) {
        Mob mob = m.mob();
        if (mob == null || mob.position == null) return;
        if (!MobSystem.isVisibleToPlayer(level, mob)) return;
        boolean wantIcons = iconPrefSupplier != null && iconPrefSupplier.getAsBoolean();
        if (wantIcons) {
            stage.add(Effect.buffIcon(mob.position, m.type(), m.displayName()));
        } else {
            stage.add(Effect.floatingText(mob.position, m.displayName(), Effect.EffectTint.YELLOW));
        }
    }

    private void onWandImpactBurst(Level level, GameEvent.WandImpactBurst m) {
        Effect.EffectTint tint = switch (m.element()) {
            case WATER                -> Effect.EffectTint.BLUE;
            case OIL                  -> Effect.EffectTint.YELLOW;
            case GRASS                -> Effect.EffectTint.GREEN;
            case FUNGUS, FIRE         -> Effect.EffectTint.RED;
            case LIGHTNING            -> Effect.EffectTint.YELLOW;
            case FREEZE               -> Effect.EffectTint.BLUE;
            case BLAST, DAMAGE        -> Effect.EffectTint.WHITE;
            case DETONATION,
                 BANISHMENT,
                 MISSILE             -> Effect.EffectTint.WHITE;
        };
        Effect burst = Effect.particleBurst(m.pos(), tint, 18, RNG);
        stage.add(burst);
        if (visibleAt(level, m.pos())) queue.concurrent(burst.totalFrames());
    }

    /** Throw-bomb / blast-bomb shockwave. Blocks while visible so a rapid second
     *  action doesn't slide under the burst. */
    private void onBlastEffect(Level level, GameEvent.BlastEffect m) {
        Effect blast = Effect.blast(m.pos());
        stage.add(blast);
        if (visibleAt(level, m.pos())) queue.concurrent(blast.totalFrames());
    }

    /** Radial fire-ball burst (on-death explosion, detonation wand). Blocks while
     *  visible so the player sees the boom resolve before the next AI turn lands. */
    private void onExplosionEffect(Level level, GameEvent.ExplosionEffect m) {
        Effect boom = Effect.explosion(m.pos(), m.radiusTiles(), RNG);
        stage.add(boom);
        if (visibleAt(level, m.pos())) queue.concurrent(boom.totalFrames());
    }

    /** Swirling rising particles in the potion's colour. Triggered when a
     *  potion is drunk or thrown. Colour is derived from the potion's
     *  {@code appliesBuff} (so any future buff-bearing potion picks up a
     *  sensible tint without code changes), with a fall-through on the item's
     *  {@code type} string for the special-cased POTION_INSIGHT (which carries
     *  no buff). */
    private void onPotionBurst(Level level, GameEvent.PotionBurst m) {
        Item item = m.item();
        if (item == null) return;
        Effect.EffectTint tint = potionTint(item);
        Effect burst = Effect.particleBurst(m.pos(), tint, 18, RNG);
        stage.add(burst);
        if (visibleAt(level, m.pos())) queue.concurrent(burst.totalFrames());
    }

    /** Mob just spawned — blocking grow-from-zero animation while a fountain
     *  of upward-drifting particles plays at the spawn tile. Skipped (and
     *  doesn't block) when the spawn tile isn't currently visible to the
     *  player, so off-screen ant-hill blooms etc. don't freeze the game. */
    private void onMobSpawned(Level level, GameEvent.MobSpawned m) {
        Mob mob = m.mob();
        if (mob == null || mob.position == null) return;
        Point at = m.at();
        if (at == null) return;
        // Reset the per-mob spawn-grow counter regardless of visibility so the
        // sprite always starts at full size for off-camera spawns (no scale
        // hangover when the player later walks into the room).
        MobAnimState s = stateOf(mob);
        s.spawnFrame = 0;
        s.spawnTotalFrames = MobAnimState.SPAWN_GROW_FRAMES;
        if (!visibleAt(level, at)) {
            // Off-screen: skip particles AND skip the blocking freeze. Set
            // spawnFrame to the end so the sprite is full-size by the time
            // the player ever sees it.
            s.spawnFrame = s.spawnTotalFrames;
            return;
        }
        stage.add(Effect.particleBurst(at, Effect.EffectTint.WHITE, 14, RNG));
        if (queue.freezeFrames < MobAnimState.SPAWN_GROW_FRAMES) {
            queue.freezeFrames = MobAnimState.SPAWN_GROW_FRAMES;
        }
    }

    /** Surface (water/oil/blood/ice) just appeared on a tile — fountain of
     *  particles in the surface's colour. Blocks while visible so a wand-of-water
     *  / oil splatter resolves before the next AI turn lands. Skipped (and
     *  doesn't block) when the tile is currently off-screen. */
    private void onSurfaceChanged(Level level, GameEvent.SurfaceChanged m) {
        if (m.pos() == null || !visibleAt(level, m.pos())) return;
        Effect.EffectTint tint = switch (m.surface()) {
            case WATER -> Effect.EffectTint.BLUE;
            case OIL   -> Effect.EffectTint.YELLOW;
            case BLOOD -> Effect.EffectTint.RED;
            case ICE   -> Effect.EffectTint.WHITE;
        };
        Effect burst = Effect.particleBurst(m.pos(), tint, 8, RNG);
        stage.add(burst);
        queue.concurrent(burst.totalFrames());
    }

    /** Vegetation just changed on a tile (grass / mushrooms / fire / trees) —
     *  fountain of particles in the vegetation's colour. Blocks while visible
     *  (wand-of-grass / wand-of-fungus growth, fire spread). */
    private void onVegetationChanged(Level level, GameEvent.VegetationChanged m) {
        if (m.pos() == null || m.vegetation() == null || !visibleAt(level, m.pos())) return;
        Effect.EffectTint tint = switch (m.vegetation()) {
            case GRASS     -> Effect.EffectTint.GREEN;
            case MUSHROOMS -> Effect.EffectTint.RED;
            case FIRE      -> Effect.EffectTint.ORANGE;
            case TREES     -> Effect.EffectTint.GREEN;
        };
        Effect burst = Effect.particleBurst(m.pos(), tint, 8, RNG);
        stage.add(burst);
        queue.concurrent(burst.totalFrames());
    }

    /** Multi-coloured rainbow burst (power-orb absorb, level-up). Blocking —
     *  six per-colour bursts stacked at the same tile so the player visually
     *  reads "many colours flying out". */
    private void onRainbowBurst(Level level, GameEvent.RainbowBurst m) {
        if (m.pos() == null || !visibleAt(level, m.pos())) return;
        Effect.EffectTint[] palette = {
                Effect.EffectTint.RED, Effect.EffectTint.YELLOW,
                Effect.EffectTint.GREEN, Effect.EffectTint.BLUE,
                Effect.EffectTint.ORANGE, Effect.EffectTint.WHITE };
        for (Effect.EffectTint t : palette) {
            stage.add(Effect.particleBurst(m.pos(), t, 8, RNG));
        }
        int frames = 30;
        if (queue.freezeFrames < frames) queue.freezeFrames = frames;
    }

    /** True when {@code at} is currently visible to the player. Used by the
     *  spawn / fountain / rainbow handlers so off-screen events don't burn
     *  particle budget or freeze the game. */
    private static boolean visibleAt(Level level, Point at) {
        if (level == null || level.visible == null || at == null) return false;
        int x = at.tileX(), y = at.tileY();
        if (x < 0 || y < 0 || x >= level.width || y >= level.height) return false;
        return level.visible[x][y];
    }

    /** Pick a particle palette for a potion. The mapping is by {@code appliesBuff}
     *  first (so HEALING_POTION's REGENERATION → green works regardless of
     *  the potion's display name), with a special case for POTION_INSIGHT
     *  which carries no buff. Constrained to the seven existing
     *  {@link Effect.EffectTint} values; new tints would also need a
     *  {@code FxRenderer.tintToColor} entry, which isn't worth it for these
     *  one-off potions. */
    private static Effect.EffectTint potionTint(Item item) {
        if ("POTION_INSIGHT".equals(item.type)) return Effect.EffectTint.YELLOW;
        if (item.appliesBuff == null) return Effect.EffectTint.WHITE;
        return switch (item.appliesBuff) {
            case REGENERATION -> Effect.EffectTint.GREEN;
            case POISONED     -> Effect.EffectTint.BROWN;
            case SORCERY      -> Effect.EffectTint.BLUE;
            case GHOSTLY      -> Effect.EffectTint.WHITE;
            case INVISIBLE    -> Effect.EffectTint.BLUE;
            case HOPE, ESP    -> Effect.EffectTint.YELLOW;
            default           -> Effect.EffectTint.WHITE;
        };
    }

    private void onPeriodicBuffDamage(Level level, GameEvent.PeriodicBuffDamage m) {
        Mob mob = m.mob();
        if (mob == null || mob.position == null) return;
        if (!MobSystem.isVisibleToPlayer(level, mob)) return;
        Effect.EffectTint tint = switch (m.buff()) {
            case ON_FIRE  -> Effect.EffectTint.ORANGE;
            case POISONED -> Effect.EffectTint.GREEN;
            default       -> Effect.EffectTint.RED;
        };
        stage.add(Effect.floatingText(mob.position, "-" + m.amount(), tint));
    }

    // ── Pending-impact resolution ──────────────────────────────────────────────────

    /** A deferred game-state mutation tied to a visible projectile's completion.
     *  When the effect's frame counter reaches its lifetime the {@code onComplete}
     *  runnable fires, calling back into rlib to apply damage, banish a ghost,
     *  etc. Held alongside the in-flight effect list so the animator owns both. */
    private static final class PendingImpact {
        final Effect effect;
        final Runnable onComplete;
        PendingImpact(Effect effect, Runnable onComplete) {
            this.effect = effect;
            this.onComplete = onComplete;
        }
    }

    /** Fire impact callbacks for any projectile whose visual is one frame from removal,
     *  then drain any events the callback emitted (e.g. explosions, plant growth, blood
     *  surfaces) so their freeze contributions land before the next render frame's
     *  game tick — otherwise the wand's secondary visuals wouldn't block AI turns. */
    private void firePendingImpacts(Level level) {
        boolean fired = false;
        for (Iterator<PendingImpact> it = pendingImpacts.iterator(); it.hasNext(); ) {
            PendingImpact pi = it.next();
            if (pi.effect.frame + 1 < pi.effect.totalFrames()) continue;
            try { pi.onComplete.run(); fired = true; } finally { it.remove(); }
        }
        if (fired && level != null) consume(level);
    }

    /** Damage application for plain magic missiles (PlayScreen's wand-of-magic-missile,
     *  the staff legacy missile, AI ranged shooters). Mirrors the legacy
     *  {@code PlayScreen.resolveCompletingMissiles} body. */
    private static void applyMissileImpact(Level level, Mob caster, Point target, int damage) {
        Mob victim = MobSystem.mobAt(level, target);
        if (victim == null) return;
        // Stat-based magic resist (analogous to physical armor) is applied here; the
        // ANTI_MAGIC buff is applied centrally inside processAttack via DamageElement.MAGIC.
        int magicResist = MobSystem.rollRange(MobSystem.magicResistRange(victim));
        int afterResist = Math.max(0, damage - magicResist);
        MobSystem.DamageBreakdown bk =
                new MobSystem.DamageBreakdown(MobSystem.DamageElement.MAGIC, damage)
                        .add("magicResist", Math.min(magicResist, damage));
        Mob speaker = caster != null ? caster : com.bjsp123.rl2.logic.TurnSystem.findPlayer(level);
        String casterName = speaker != null && speaker.name != null ? speaker.name : "Adventurer";
        String victimName = MobSystem.nameForLog(level, victim);
        double hpBefore = victim.hp;
        MobSystem.processAttack(level, speaker, victim, afterResist,
                MobSystem.AttackType.MAGIC, MobSystem.DamageElement.MAGIC, bk);
        int dealt = Math.max(0, (int) Math.round(hpBefore - victim.hp));
        com.bjsp123.rl2.logic.EventLog.add(
                com.bjsp123.rl2.logic.Messages.playerHit(casterName, victimName, dealt));
    }

    // ── Real-time particle drains ──────────────────────────────────────────────────

    private void tickTeleportFades(Level level, int dtMs) {
        if (level.mobs == null) return;
        for (Mob m : level.mobs) {
            MobAnimState s = states.get(m);
            if (s == null || s.teleportFadeMs <= 0) continue;
            int before = s.teleportFadeMs;
            int after  = before - dtMs;
            int half   = MobSystem.TELEPORT_FADE_HALF_MS;
            if (before > half && after <= half && m.position != null) {
                stage.add(Effect.teleportStreaks(m.position, /*up=*/false, RNG));
            }
            s.teleportFadeMs = Math.max(0, after);
        }
    }

    private void tickBurningParticles(Level level, int dtMs) {
        // Per-tile fire-vegetation particles.
        if (level.vegetation != null && level.fireEmitCountdown != null) {
            for (int x = 0; x < level.width; x++) {
                for (int y = 0; y < level.height; y++) {
                    if (level.vegetation[x][y] != Level.Vegetation.FIRE) continue;
                    int countdown = level.fireEmitCountdown[x][y] - dtMs;
                    while (countdown <= 0) {
                        stage.add(Effect.fireParticle(new Point(x, y), RNG));
                        countdown += FIRE_PARTICLE_INTERVAL_MS;
                    }
                    level.fireEmitCountdown[x][y] = countdown;
                }
            }
        }
        // Per-mob particles for ON_FIRE-buffed mobs (twice the tile rate).
        if (level.mobs == null) return;
        for (Mob m : level.mobs) {
            boolean burning = com.bjsp123.rl2.logic.BuffSystem.hasBuff(m,
                    com.bjsp123.rl2.model.Buff.BuffType.ON_FIRE);
            MobAnimState s = states.get(m);
            if (!burning || m.hp <= 0 || m.position == null) {
                if (s != null) s.fireParticleCountdownMs = 0;
                continue;
            }
            if (s == null) s = stateOf(m);
            int countdown = s.fireParticleCountdownMs - dtMs;
            while (countdown <= 0) {
                stage.add(Effect.fireParticle(m.position, RNG));
                countdown += FIRE_PARTICLE_MOB_INTERVAL_MS;
            }
            s.fireParticleCountdownMs = countdown;
        }
    }

    private void tickSleepZs(Level level, int dtMs) {
        if (level.mobs == null) return;
        for (Mob m : level.mobs) {
            MobAnimState s = states.get(m);
            if (m.stateOfMind != Mob.StateOfMind.ASLEEP) {
                if (s != null) s.sleepZCountdownMs = 0;
                continue;
            }
            if (s == null) s = stateOf(m);
            s.sleepZCountdownMs -= dtMs;
            if (s.sleepZCountdownMs <= 0 && m.position != null) {
                stage.add(Effect.sleepZ(m.position, RNG));
                s.sleepZCountdownMs = SLEEP_Z_MIN_MS + RNG.nextInt(SLEEP_Z_RANGE_MS);
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────────

    /** Build a coloured wand missile with palette / gravity / brightness picked from the
     *  element. Mirrors the legacy {@code PlayScreen.buildWandMissile}. */
    private static Effect buildWandMissile(Point from, Point to, Item.ItemEffect element) {
        Effect.EffectTint head;
        Effect.EffectTint[] palette;
        float gravity;
        float size;
        boolean bright = true;
        float baseSize = 1.0f;
        switch (element) {
            case WATER -> {
                head    = Effect.EffectTint.BLUE;
                palette = new Effect.EffectTint[] { Effect.EffectTint.BLUE };
                gravity = 0.45f; size = baseSize;
            }
            case OIL -> {
                head    = Effect.EffectTint.YELLOW;
                palette = new Effect.EffectTint[] { Effect.EffectTint.YELLOW, Effect.EffectTint.BROWN };
                gravity = 0.35f; size = baseSize;
            }
            case GRASS -> {
                head    = Effect.EffectTint.GREEN;
                palette = new Effect.EffectTint[] { Effect.EffectTint.GREEN, Effect.EffectTint.YELLOW };
                gravity = 0.25f; size = baseSize;
            }
            case FUNGUS -> {
                head    = Effect.EffectTint.RED;
                palette = new Effect.EffectTint[] { Effect.EffectTint.RED, Effect.EffectTint.BROWN };
                gravity = 0.20f; size = baseSize;
            }
            case FIRE -> {
                head    = Effect.EffectTint.YELLOW;
                palette = new Effect.EffectTint[] { Effect.EffectTint.RED, Effect.EffectTint.YELLOW, Effect.EffectTint.ORANGE };
                gravity = 0f; size = baseSize + 0.5f;
            }
            case DETONATION -> {
                head    = Effect.EffectTint.YELLOW;
                palette = new Effect.EffectTint[] { Effect.EffectTint.RED, Effect.EffectTint.YELLOW };
                gravity = 0f; size = baseSize + 0.4f;
            }
            case MISSILE -> {
                head    = Effect.EffectTint.WHITE;
                palette = new Effect.EffectTint[] { Effect.EffectTint.WHITE };
                gravity = 0.30f; size = baseSize;
            }
            default -> {
                head    = Effect.EffectTint.WHITE;
                palette = new Effect.EffectTint[] { Effect.EffectTint.WHITE };
                gravity = 0.30f; size = baseSize;
            }
        }
        return Effect.magicMissileColored(from, to, palette, head, gravity, size, bright, RNG);
    }

    // ── Anim / step tuning (mirrors MobSystem constants) ───────────────────────────
    private static final float MELEE_LUNGE_PX  = 4f;
    private static final float HIT_FLINCH_PX   = 3f;
    private static final int   LUNGE_OUT_FRAMES   = 3;
    private static final int   LUNGE_BACK_FRAMES  = 10;
    private static final int   FLINCH_OUT_FRAMES  = 3;
    private static final int   FLINCH_BACK_FRAMES = 9;
    private static final int   STEP_FRAMES_MIN     = 3;
    private static final int   STEP_FRAMES_MAX     = 12;
    private static final int   STEP_FRAMES_DEFAULT = 5;
    /** Per-tile pace for knockback slides — faster than regular stepping so a shove
     *  reads as a sudden recoil instead of a stride. */
    private static final int   KNOCKBACK_FRAMES_PER_TILE = 2;

    private static int stepFramesFor(Mob mob) {
        int cost = (mob == null || mob.intrinsic == null) ? 100 : (int) mob.effectiveStats().moveCost;
        if (cost <= 0) return STEP_FRAMES_MIN;
        int scaled = Math.round(STEP_FRAMES_DEFAULT * cost / 100f);
        if (scaled < STEP_FRAMES_MIN) return STEP_FRAMES_MIN;
        if (scaled > STEP_FRAMES_MAX) return STEP_FRAMES_MAX;
        return scaled;
    }

    private void startAnim(MobAnimState s,
                           float peakX, float peakY,
                           int peakFrame, int endFrame,
                           boolean concurrent) {
        s.animPeakX     = peakX;
        s.animPeakY     = peakY;
        s.animFrame     = 0;
        s.animPeakFrame = peakFrame;
        s.animEndFrame  = endFrame;
        s.delayFrames   = concurrent ? queue.rideLastSlot(endFrame) : queue.sequential(endFrame);
    }

    /** Renderer-side preference: icon vs text "buff applied" floats. Wired by the rgame
     *  layer at startup. {@code null} means fall back to text. */
    private static java.util.function.BooleanSupplier iconPrefSupplier;
    public static void setIconPreferenceSupplier(java.util.function.BooleanSupplier s) { iconPrefSupplier = s; }

    /** Renderer-side preference: animation-speed multiplier (frames advanced per
     *  render frame). Wired by the rgame layer at startup. {@code null} means
     *  default to 1 (animations play at their authored frame counts). */
    private static java.util.function.IntSupplier animationSpeedSupplier;
    public static void setAnimationSpeedSupplier(java.util.function.IntSupplier s) { animationSpeedSupplier = s; }

    private static int framesPerRender() {
        if (animationSpeedSupplier == null) return 1;
        int n = animationSpeedSupplier.getAsInt();
        return n > 0 ? n : 1;
    }
}
