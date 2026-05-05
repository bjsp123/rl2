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
            }
            ghosts.removeIf(g -> { g.frame++; return g.done(); });
            // Fire pending impacts at the moment their carrier effect's NEXT advance would
            // remove it — mirrors PlayScreen.resolveCompletingMissiles' "frame + 1 ==
            // totalFrames" check. The impact runs before the effect itself ticks so the
            // hit lines up with the last visible frame.
            firePendingImpacts();
            stage.tick();
        }
        int scaledDtMs = dtMs * n;
        if (level != null && scaledDtMs > 0) {
            tickTeleportFades(level, scaledDtMs);
            tickBurningParticles(level, scaledDtMs);
            tickSleepZs(level, scaledDtMs);
        }
        // Project the freeze gate against any in-flight visible projectile (until rlib
        // spawns ALL effects via events with their own freeze contribution).
        if (level != null && level.visible != null) {
            int needed = 0;
            for (Effect e : stage.active) {
                if (e.type != Effect.EffectType.MAGIC_MISSILE
                        && e.type != Effect.EffectType.THROWN_ITEM
                        && e.type != Effect.EffectType.RAY) continue;
                if (!trajectoryVisible(level, e.location, e.endLocation)) continue;
                int rem = e.totalFrames() - e.frame;
                if (rem > needed) needed = rem;
            }
            if (needed > queue.freezeFrames) queue.freezeFrames = needed;
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
            else if (ev instanceof GameEvent.BlastEffect m)           stage.add(Effect.blast(m.pos()));
            else if (ev instanceof GameEvent.ExplosionEffect m)       stage.add(Effect.explosion(m.pos(), m.radiusTiles(), RNG));
            else if (ev instanceof GameEvent.LightMoteSpawn m)        stage.add(Effect.lightMote(m.pos(), RNG));
            else if (ev instanceof GameEvent.WandImpactBurst m)       onWandImpactBurst(m);
            else if (ev instanceof GameEvent.PeriodicBuffDamage m)    onPeriodicBuffDamage(level, m);
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
        Mob caster = m.caster();
        int damage = m.damage();
        Point target = m.to();
        pendingImpacts.add(new PendingImpact(missile,
                () -> applyMissileImpact(level, caster, target, damage)));
    }

    private void onWandMissileFired(Level level, GameEvent.WandMissileFired m) {
        Effect missile = buildWandMissile(m.from(), m.to(), m.element());
        stage.add(missile);
        Mob caster = m.caster();
        Point target = m.to();
        Item.WandElement element = m.element();
        int wandLevel = m.wandLevel();
        pendingImpacts.add(new PendingImpact(missile,
                () -> com.bjsp123.rl2.logic.ItemSystem.applyWandImpact(level, caster, target, element, wandLevel)));
    }

    private void onWandRayFired(Level level, GameEvent.WandRayFired m) {
        Effect ray = Effect.ray(m.from(), m.to(), Effect.EffectTint.WHITE);
        stage.add(ray);
        Mob caster = m.caster();
        Point target = m.to();
        Item.WandElement element = m.element();
        int wandLevel = m.wandLevel();
        pendingImpacts.add(new PendingImpact(ray,
                () -> com.bjsp123.rl2.logic.ItemSystem.applyWandImpact(level, caster, target, element, wandLevel)));
    }

    private void onItemThrown(Level level, GameEvent.ItemThrown m) {
        Item it = m.item();
        stage.add(Effect.thrownItem(m.from(), m.to(), it));
        // Throw resolution happens in rlib at fire time; no PendingImpact needed.
    }

    private void onDamageDealt(Level level, GameEvent.DamageDealt m) {
        Mob target = m.target();
        if (target == null || target.position == null) return;
        if (!MobSystem.isVisibleToPlayer(level, target)) return;
        switch (m.kind()) {
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

    private void onWandImpactBurst(GameEvent.WandImpactBurst m) {
        Effect.EffectTint tint = switch (m.element()) {
            case WATER     -> Effect.EffectTint.BLUE;
            case OIL       -> Effect.EffectTint.YELLOW;
            case GRASS     -> Effect.EffectTint.GREEN;
            case FUNGUS    -> Effect.EffectTint.RED;
            case FIRE      -> Effect.EffectTint.RED;
            case LIGHTNING -> Effect.EffectTint.YELLOW;
            case DETONATION,
                 BANISHMENT -> Effect.EffectTint.WHITE;
        };
        stage.add(Effect.particleBurst(m.pos(), tint, 18, RNG));
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

    /** Fire impact callbacks for any projectile whose visual is one frame from removal. */
    private void firePendingImpacts() {
        for (Iterator<PendingImpact> it = pendingImpacts.iterator(); it.hasNext(); ) {
            PendingImpact pi = it.next();
            if (pi.effect.frame + 1 < pi.effect.totalFrames()) continue;
            try { pi.onComplete.run(); } finally { it.remove(); }
        }
    }

    /** Damage application for plain magic missiles (PlayScreen's wand-of-magic-missile,
     *  the staff legacy missile, AI ranged shooters). Mirrors the legacy
     *  {@code PlayScreen.resolveCompletingMissiles} body. */
    private static void applyMissileImpact(Level level, Mob caster, Point target, int damage) {
        Mob victim = MobSystem.mobAt(level, target);
        if (victim == null) return;
        int afterResist = Math.max(0,
                damage - MobSystem.rollRange(MobSystem.magicResistRange(victim)));
        int dealt = com.bjsp123.rl2.logic.BuffSystem.mitigateMagicDamage(victim, afterResist);
        Mob speaker = caster != null ? caster : com.bjsp123.rl2.logic.TurnSystem.findPlayer(level);
        String casterName = speaker != null && speaker.name != null ? speaker.name : "Adventurer";
        String victimName = MobSystem.nameForLog(level, victim);
        com.bjsp123.rl2.logic.EventLog.add(
                com.bjsp123.rl2.logic.Messages.playerHit(casterName, victimName, dealt));
        MobSystem.processAttack(level, speaker, victim, dealt, MobSystem.AttackType.MAGIC);
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

    /** Trajectory-visibility check used by the freeze-projection pass. Bresenham-walks
     *  the line from {@code from} to {@code to} and returns true the moment one tile is
     *  in {@code level.visible}. */
    private static boolean trajectoryVisible(Level level, Point from, Point to) {
        if (level == null || level.visible == null) return false;
        if (from == null && to == null) return false;
        if (from == null) return tileVisible(level, to.tileX(), to.tileY());
        if (to == null)   return tileVisible(level, from.tileX(), from.tileY());
        int x0 = from.tileX(), y0 = from.tileY();
        int x1 = to.tileX(),   y1 = to.tileY();
        int dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;
        int x = x0, y = y0;
        while (true) {
            if (tileVisible(level, x, y)) return true;
            if (x == x1 && y == y1) return false;
            int e2 = err << 1;
            if (e2 > -dy) { err -= dy; x += sx; }
            if (e2 <  dx) { err += dx; y += sy; }
        }
    }

    private static boolean tileVisible(Level level, int x, int y) {
        if (x < 0 || y < 0 || x >= level.width || y >= level.height) return false;
        return level.visible[x][y];
    }

    /** Build a coloured wand missile with palette / gravity / brightness picked from the
     *  element. Mirrors the legacy {@code PlayScreen.buildWandMissile}. */
    private static Effect buildWandMissile(Point from, Point to, Item.WandElement element) {
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
