package com.bjsp123.rl2.world.anim;
import com.bjsp123.rl2.audio.SoundManager;
import com.bjsp123.rl2.event.GameEvent;
import com.bjsp123.rl2.logic.MobQueries;
import com.bjsp123.rl2.logic.MobSystem;
import com.bjsp123.rl2.logic.TextCatalog;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.world.render.Effect;
import com.bjsp123.rl2.world.render.EffectStage;
import com.bjsp123.rl2.world.render.Effect.EffectTint;

import java.util.ArrayList;
import java.util.IdentityHashMap;
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

    /** Optional consumer of every drained {@link GameEvent}. PlayScreen
     *  wires the AchievementSystem here so non-presentation observation
     *  hooks into the same drain that drives visuals. {@code null} when
     *  no observer is registered (headless tests, pre-init frames). */
    public interface EventObserver {
        void onEvent(GameEvent ev, Mob player, Level level);
    }
    private EventObserver eventObserver;
    public void setEventObserver(EventObserver obs) { this.eventObserver = obs; }

    private SoundManager sounds;
    public void setSounds(SoundManager s) { this.sounds = s; }
    private int footstepStep = 1;

    private final IdentityHashMap<Mob, MobAnimState> states = new IdentityHashMap<>();
    private final List<Ghost> ghosts = new ArrayList<>();
    private final PendingImpactQueue pendingImpacts = new PendingImpactQueue();
    /** Set to true whenever a pending impact fires this tick; cleared by the caller. */
    public boolean impactFiredThisTick = false;
    static final Random RNG = new Random();


    /** Wall-clock emit interval for fire-particle effects. Mirrors the legacy
     *  {@code FireSystem.FIRE_PARTICLE_INTERVAL_MS}. */
    /** Wall-clock window for sleep-Z emission cadence (1.2 s - 2.0 s). */

    public MobAnimState stateOf(Mob mob) {
        if (mob == null) return null;
        MobAnimState s = states.get(mob);
        if (s == null) {
            s = new MobAnimState();
            states.put(mob, s);
        }
        return s;
    }

    /** Display-only clock tick for the HUD. The engine tick advances after the
     *  player animation gate clears, so an exactly-100-tick move would otherwise
     *  leave the turn clock apparently motionless. While the player is visibly
     *  stepping, interpolate through their pending move cost; gameplay timestamps
     *  still use the real {@code baseTick}. */
    public int visualClockTick(Level level, int baseTick) {
        if (level == null || level.mobs == null) return baseTick;
        Mob player = null;
        for (Mob m : level.mobs) {
            if (m != null && m.behavior == Mob.Behavior.PLAYER) {
                player = m;
                break;
            }
        }
        if (player == null || player.ticksTillMove <= 0) return baseTick;
        MobAnimState s = states.get(player);
        if (s == null || s.stepTotal <= 0 || s.delayFrames > 0) return baseTick;
        float t = Math.min(1f, Math.max(0f, s.stepFrame / (float) s.stepTotal));
        return baseTick + Math.round(player.ticksTillMove * t);
    }

    public List<Ghost> ghosts() { return ghosts; }

    /** Per-render-frame: advance per-mob anims, ghosts, the freeze queue, the effect
     *  stage, fire-pending-impact callbacks for completing projectiles, and the
     *  real-time-driven teleport-fade / burning / asleep particle cadences.
     *
     *  <p>The frame-counter section runs {@code framesPerRender()} times per render
     *  frame so the user-facing "animation speed" setting (1x, 2x, 4x) shortens every
     *  authored animation duration uniformly. The real-time {@code dtMs} drain is
     *  pre-multiplied by the same factor so fire-particle / sleep-Z / teleport-fade
     *  cadences keep pace with the frame-counter speedup. */

    private float frameProgress = 0f;

    public void tick(Level level, int dtMs) {
        // queue.tick() is driven by PlayScreen at the top of render(), BEFORE the
        // game-tick gate check, so a step that ends "this frame" can immediately
        // chain into the next one without leaving a stationary gap.
        float n = framesPerRender();
        frameProgress += n;

        int framesToAdvance = (int) Math.floor(frameProgress);

        frameProgress -= framesToAdvance;

        for (int step = 0; step < framesToAdvance; step++) {
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
                if (s.borderFlashFrames > 0) s.borderFlashFrames--;
            }
            // Advance ghosts - but if the dying mob has a queued slide
            // animation (knockback), park the death-fade counter until
            // the slide completes so the ghost slides first, then fades
            // at the destination.
            ghosts.removeIf(g -> {
                MobAnimState gs = states.get(g.mob);
                if (gs != null && (gs.delayFrames > 0 || gs.stepTotal > 0)) {
                    return false;
                }
                g.frame++;
                return g.done();
            });
            // Fire pending impacts at the moment their carrier effect's NEXT advance would
            // remove it - mirrors PlayScreen.resolveCompletingMissiles' "frame + 1 ==
            // totalFrames" check. The impact runs before the effect itself ticks so the
            // hit lines up with the last visible frame. Newly-emitted events are consumed
            // in the same step so the impact's freeze contribution lands before the next
            // render frame's controller.tick (otherwise an explosion / surface change
            // wouldn't block AI turns triggered the same frame).
            firePendingImpacts(level);
            stage.tick();
        }
        // Environmental cadences run on REAL time - the user-facing
        // animation-speed setting only compresses event-driven action
        // animations (steps, flinches, missile trails, ghost fades).
        // Fire crackle, sleep-Z drift, levitation puffs, and ambient
        // cloud puffs all read the raw dt so a 4x anim-speed run still
        // looks like a calm fire and a steady poison cloud.
        if (level != null && dtMs > 0) {
            tickBurningParticles(level, dtMs);
            tickSleepZs(level, dtMs);
            tickLevitatePuffs(level, dtMs);
            // Cloud puffs are per-render-frame Poisson emission, not
            // wall-clock; emit them once per render regardless of speed.
            emitCloudPuffs(level);
        }
        // Teleport-fades are event-driven (the engine's MobTeleported
        // event spawns them) so they share the speed multiplier with
        // every other event animation.
        int scaledDtMs = (int)((float)dtMs * n);
        if (level != null && scaledDtMs > 0) {
            tickTeleportFades(level, scaledDtMs);
        }
        // Foot dust - spawn one cloud per render frame at the player's
        // current visual foot position, while the player is mid-step.
        // The per-frame cadence (rather than per-game-tick) gives a dense
        // trail that hides the bottom of the sprite.
        spawnFootDust(level);
    }

    /** Per-tile size for converting MobAnimState slide deltas into world
     *  pixels. Mirrors {@link com.bjsp123.rl2.world.render.LevelRenderer#TILE_SIZE}. */
    private static final float DUST_CELL_PX = 16f;
    /** Number of dust clouds to spawn each render frame the player is
     *  mid-step. Each cloud is independently jittered, sized, shaded,
     *  and given a randomised lifetime + drift for visual variety. */
    /** Drift speed magnitude (px/frame) for the small per-cloud random
     *  motion - every cloud picks an independent random direction within
     *  this magnitude so the puff disperses softly rather than sitting
     *  perfectly still. */

    /** Frame window over which cloud-puff spawn rate is expressed -
     *  {@code 2 + duration/3} puffs per this many render frames. 12 is
     *  ~ 200 ms at the default render cadence, so the lowest-density
     *  cell still breathes a couple of puffs every fifth of a second. */
    /** Random-direction drift speed for a freshly-spawned cloud puff. */
    /** Fixed upward bias on top of {@link #AnimationVars.CLOUD_PUFF_DRIFT_PX_PER_FRAME}
     *  so a body of gas overall floats up rather than diffusing equally. */
    /** Constant upward bias (px/frame) added to every cloud's drift, so
     *  the random-direction component still tends to lift rather than
     *  sit or sink. */
    /** Vertical offset (px) applied to the dust spawn point so the puff
     *  starts above the floor rather than at the floor edge - covers the
     *  player sprite's calf / shin area, which is what we want to obscure. */
    private static final float DUST_SPAWN_Y_OFFSET_PX = 4f;

    /** Spawn the foot-dust clouds for the current render frame. Multiple
     *  clouds per frame are emitted with independent random jitter,
     *  starting size, and shade - they don't drift in any direction;
     *  each just expands, rises, and fades in place. No-op when the
     *  player isn't sliding or isn't visible. */
    /** Per-frame cloud-puff emission. Each cell with active duration spawns
     *  ellipse particles at a rate of {@code 2 + duration/3} per
     *  {@link #AnimationVars.CLOUD_PUFF_FRAME_WINDOW} render frames - i.e. denser clouds
     *  emit more puffs, but every visible cell breathes at least 2 / window.
     *  Out-of-FOV cells are skipped (no point burning effect slots on
     *  particles the player can't see). Compresses cleanly with the
     *  animation-speed multiplier - {@code framesPerRender} runs the loop
     *  Nx per render frame, so faster speeds emit proportionally more
     *  particles per real-time second. */
    private void emitCloudPuffs(Level level) {
        if (level == null || level.cloud == null || level.visible == null) return;
        int w = level.width, h = level.height;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int packed = level.cloud[x][y];
                if (packed == 0) continue;
                if (!level.visible[x][y]) continue;
                int dur = com.bjsp123.rl2.logic.CloudSystem.duration(packed);
                if (dur <= 0) continue;
                com.bjsp123.rl2.model.Level.Cloud type =
                        com.bjsp123.rl2.logic.CloudSystem.type(packed);
                if (type == null) continue;
                // Spawn count expressed as a per-frame expectation, with
                // the integer part emitted deterministically and the
                // fractional part rolled probabilistically - gives the
                // exact long-run rate of (2 + dur/3) per AnimationVars.CLOUD_PUFF_FRAME_WINDOW.
                float perFrame = (2f + dur / 3f) / (float) AnimationVars.CLOUD_PUFF_FRAME_WINDOW;
                int whole = (int) Math.floor(perFrame);
                for (int i = 0; i < whole; i++) emitOneCloudPuff(x, y, type);
                float frac = perFrame - whole;
                if (frac > 0f && RNG.nextFloat() < frac) emitOneCloudPuff(x, y, type);
            }
        }
    }

    /** Spawn a single cloud-puff ellipse for cell ({@code tileX}, {@code tileY}).
     *  Pixel anchor is randomly placed inside the tile so the puffs don't
     *  stack on the same column; drift is a small random unit vector with
     *  an upward bias so the body of gas tends to lift over time. */
    private void emitOneCloudPuff(int tileX, int tileY,
                                  com.bjsp123.rl2.model.Level.Cloud type) {
        float pxX = tileX * 16f + 1f + RNG.nextFloat() * 14f;
        float pxY = tileY * 16f + 1f + RNG.nextFloat() * 14f;
        double angle = RNG.nextDouble() * Math.PI * 2.0;
        float vx = (float) Math.cos(angle) * AnimationVars.CLOUD_PUFF_DRIFT_PX_PER_FRAME;
        float vy = (float) Math.sin(angle) * AnimationVars.CLOUD_PUFF_DRIFT_PX_PER_FRAME
                + AnimationVars.CLOUD_PUFF_UP_BIAS_PX_PER_FRAME;
        stage.add(com.bjsp123.rl2.world.render.Effect.cloudPuff(
                tileX, tileY, pxX, pxY, vx, vy, type, RNG));
    }

    private void spawnFootDust(Level level) {
        if (level == null || level.mobs == null) return;
        Mob player = null;
        for (Mob m : level.mobs) {
            if (m != null && m.behavior == Mob.Behavior.PLAYER) { player = m; break; }
        }
        if (player == null || player.position == null) return;
        // Levitating / flying movers don't kick up dust.
        if (player.effectiveStats().flying) return;
        MobAnimState s = states.get(player);
        if (s == null || s.stepTotal <= 0 || s.delayFrames > 0) return;
        if (!MobSystem.isVisibleToPlayer(level, player)) return;

        float t = Math.min(1f, s.stepFrame / (float) s.stepTotal);
        float visualTX = player.position.tileX() + s.stepFromDx * (1f - t);
        float visualTY = player.position.tileY() + s.stepFromDy * (1f - t);

        for (int i = 0; i < AnimationVars.DUST_CLOUDS_PER_FRAME; i++) {
            // Independent +/-3 px x-jitter and +/-1 px y-jitter per cloud so
            // the same render-frame's batch is a small spread rather
            // than a stack at one point.
            float jx = (RNG.nextFloat() - 0.5f) * 6f;
            float jy = (RNG.nextFloat() - 0.5f) * 2f;
            float pxX = (visualTX + 0.5f) * DUST_CELL_PX + jx;
            float pxY = visualTY * DUST_CELL_PX
                    + DUST_SPAWN_Y_OFFSET_PX + jy;
            // Tiny random drift per cloud - picked from a unit circle
            // scaled to AnimationVars.DUST_DRIFT_PX_PER_FRAME, plus a fixed upward
            // bias so the puff overall tends to lift rather than sit.
            double angle = RNG.nextDouble() * Math.PI * 2.0;
            float vx = (float) Math.cos(angle) * AnimationVars.DUST_DRIFT_PX_PER_FRAME;
            float vy = (float) Math.sin(angle) * AnimationVars.DUST_DRIFT_PX_PER_FRAME
                    + AnimationVars.DUST_UP_BIAS_PX_PER_FRAME;
            int tileX = (int) Math.floor(visualTX);
            int tileY = (int) Math.floor(visualTY);
            stage.add(Effect.dustCloud(tileX, tileY, pxX, pxY, vx, vy, RNG));
        }
    }

    /** Drain all events emitted during the previous {@code TurnSystem.tick}, populating
     *  per-mob anim state and pushing visual effects into the stage. */
    public void consume(Level level) {
        if (level.events == null || level.events.isEmpty()) return;
        if (sounds != null) sounds.beginFrame();
        // Reset sequential counter so scaleFrames() reads a fresh depth for this batch.
        queue.resetSequentialCount();
        // Cache the player once for this drain pass so the achievement
        // observer (and any future per-event hooks) don't re-scan
        // level.mobs per event.
        Mob playerForEvents = eventObserver != null ? TurnSystem.findPlayer(level) : null;
        for (GameEvent ev : level.events) {
            if (eventObserver != null) eventObserver.onEvent(ev, playerForEvents, level);
            AnimationEventDispatcher.dispatch(this, level, ev);
            // MobIgnited / MobExtinguished / MobSlept / MobWoke are polled instead of
            // event-driven (the Animator scans BuffSystem and StateOfMind each tick).
        }
        level.events.clear();
    }

    /** Public helper used by PlayScreen to spawn player-side projectiles directly. */
    public void addPendingImpact(Effect effect, Runnable onComplete) {
        pendingImpacts.add(effect, onComplete);
    }

    // -- Event handlers -------------------------------------------------------------

    void onMobMoved(Level level, GameEvent.MobMoved m) {
        // Skip step animation for off-screen mobs entirely. Without this gate every
        // ant stepping in an unseen ant hill bumps freezeFrames and stalls the
        // player's autotravel for a few frames - autotravel becomes jerky in direct
        // proportion to how busy the rest of the level is. Mobs that aren't visible
        // when they move just snap to their new position the next time the player
        // can see them, which is standard roguelike behaviour.
        if (!MobSystem.isVisibleToPlayer(level, m.mob())) return;
        MobAnimState s = stateOf(m.mob());
        int frames = scaleFrames(stepFramesFor(m.mob()));
        s.stepFromDx = (float) (m.fromX() - m.toX());
        s.stepFromDy = (float) (m.fromY() - m.toY());
        s.stepFrame  = 0;
        s.stepTotal  = frames;
        s.delayFrames = queue.concurrent(frames);
        if (sounds != null && m.mob() != null && m.mob().behavior == Mob.Behavior.PLAYER) {
            String step = "." + footstepStep;
            footstepStep = footstepStep == 1 ? 2 : 1;
            Level.Vegetation veg = (level.vegetation != null) ? level.vegetation[m.toX()][m.toY()] : null;
            Level.Surface    sur = (level.surface   != null) ? level.surface  [m.toX()][m.toY()] : null;
            if (veg != null && sounds.play("sfx.player.move." + veg.name().toLowerCase() + step, 0.5f)) {}
            else if (sur != null && sounds.play("sfx.player.move." + sur.name().toLowerCase() + step, 0.5f)) {}
            else if (level.theme != null && sounds.play("sfx.player.move." + level.theme.name().toLowerCase() + step, 0.5f)) {}
            else sounds.play("sfx.player.move" + step, 0.5f);
        }
        Level.Surface splashSur = (level.surface != null) ? level.surface[m.toX()][m.toY()] : null;
        if (splashSur != null) {
            Effect.EffectTint tint = switch (splashSur) {
                case WATER -> Effect.EffectTint.BLUE;
                case BLOOD -> Effect.EffectTint.RED;
                case OIL   -> Effect.EffectTint.YELLOW;
                case ICE   -> Effect.EffectTint.WHITE;
            };
            stage.add(Effect.footSplash(new Point(m.toX(), m.toY()), tint, RNG));
        }
    }

    void onMobMeleeAttacked(Level level, GameEvent.MobMeleeAttacked m) {
        Mob attacker = m.attacker(), target = m.target();
        if (attacker == null || target == null
                || attacker.position == null || target.position == null) return;
        boolean visible = MobSystem.isVisibleToPlayer(level, attacker)
                || MobSystem.isVisibleToPlayer(level, target);
        if (!visible) return;
        // Lunge: forward then back along the attacker->target axis.
        float dx = target.position.tileX() - attacker.position.tileX();
        float dy = target.position.tileY() - attacker.position.tileY();
        float n  = (float) Math.hypot(dx, dy);
        if (n > 0f) {
            startAnim(stateOf(attacker),
                    dx / n * AnimationVars.MELEE_LUNGE_PX, dy / n * AnimationVars.MELEE_LUNGE_PX,
                    scaleFrames(AnimationVars.LUNGE_OUT_FRAMES),
                    scaleFrames(AnimationVars.LUNGE_OUT_FRAMES + AnimationVars.LUNGE_BACK_FRAMES),
                    /*concurrent=*/false);
        }
        // Attack-flash sprite next to the attacker. If multiple mobs attack in the
        // same tick, each one's lunge is queued sequentially (different start delays
        // on the AnimQueue), and we use the same start delay for the slash so the
        // flashes appear in sequence with their lunges instead of overlapping.
        boolean isPlayer = attacker.behavior == Mob.Behavior.PLAYER;
        float flashDelay = (n > 0f) ? stateOf(attacker).delayFrames : 0;
        stage.add(Effect.attackFlash(attacker.position, isPlayer,
                attacker.facingEast, flashDelay));
        if (sounds != null) {
            if (isPlayer) sounds.playAt("sfx.player.attack.melee", level, attacker.position);
            else sounds.playAt("sfx.mob.attack.melee" + (attacker.mobType != null ? "." + attacker.mobType.toLowerCase() : ""), level, attacker.position);
        }
        // Per-hit visuals (burst on hit, "miss" feedback on miss). The DamageDealt event
        // (emitted later in the tick from processAttack) drives the floating "-N" /
        // "miss" / "blunt" text, so we only handle the bursts here.
        if (m.hit() && m.dealt() > 0) {
            stage.add(Effect.particleBurst(target.position, Effect.EffectTint.RED, 10, RNG));
        } else if (!m.hit()) {
            stage.add(Effect.particleBurst(target.position, Effect.EffectTint.WHITE, 7, RNG));
        }
    }

    void onSurpriseAttack(Level level, GameEvent.SurpriseAttack m) {
        Mob target = m.target();
        if (target == null || target.position == null) return;
        if (!MobSystem.isVisibleToPlayer(level, target)) return;
        stage.add(Effect.surpriseIcon(target.position));
    }

    void onMobHitFlinched(Level level, GameEvent.MobHitFlinched m) {
        Mob target = m.victim(), src = m.hitSource();
        if (target == null || src == null
                || target.position == null || src.position == null) return;
        // Same off-screen gate as onMobMoved - flinch animation queues a sequential
        // freeze frame, so flinches happening in unseen rooms would stall the
        // player's autotravel for AnimationVars.FLINCH_OUT_FRAMES + AnimationVars.FLINCH_BACK_FRAMES per hit.
        boolean visible = MobSystem.isVisibleToPlayer(level, target)
                || MobSystem.isVisibleToPlayer(level, src);
        if (!visible) return;
        float dx = target.position.tileX() - src.position.tileX();
        float dy = target.position.tileY() - src.position.tileY();
        float n  = (float) Math.hypot(dx, dy);
        if (n <= 0f) return;
        startAnim(stateOf(target),
                dx / n * AnimationVars.HIT_FLINCH_PX, dy / n * AnimationVars.HIT_FLINCH_PX,
                AnimationVars.FLINCH_OUT_FRAMES, AnimationVars.FLINCH_OUT_FRAMES + AnimationVars.FLINCH_BACK_FRAMES,
                /*concurrent=*/true);
    }

    void onMobKilled(Level level, GameEvent.MobKilled m) {
        if (!m.visibleAtKill()) return;
        if (sounds != null) {
            boolean playerDied = m.mob() != null && m.mob().behavior == Mob.Behavior.PLAYER;
            sounds.playAt(playerDied ? "sfx.player.combat.die" : "sfx.mob.combat.die",
                    level, new Point(m.x(), m.y()));
        }
        boolean facingEast = m.mob() != null && m.mob().facingEast;
        ghosts.add(new Ghost(m.mob(), m.x(), m.y(), facingEast));
        // If the dying mob has a queued knockback slide, chain the death
        // flicker / fade AFTER the slide so the fade plays at the
        // destination once the shove lands. Otherwise the death runs
        // concurrently with whatever's already in flight (the V1 path).
        MobAnimState s = m.mob() != null ? states.get(m.mob()) : null;
        boolean hasSlide = s != null && (s.delayFrames > 0 || s.stepTotal > 0);
        int deathFrames = scaleFrames(AnimationVars.deathTotalFrames());
        if (hasSlide) queue.sequential(deathFrames);
        else          queue.concurrent(deathFrames);
    }

    void onMobTeleported(GameEvent.MobTeleported m) {
        if (m.mob() == null) return;
        MobAnimState s = stateOf(m.mob());
        s.teleportFromX = m.fromX();
        s.teleportFromY = m.fromY();
        s.teleportFadeMs = MobSystem.TELEPORT_FADE_TOTAL_MS;
        stage.add(Effect.teleportStreaks(new Point(m.fromX(), m.fromY()), /*up=*/true, RNG));
    }

    void onMagicMissileFired(Level level, GameEvent.MagicMissileFired m) {
        boolean physical = m.caster() != null
                && m.caster().rangedDamageType == com.bjsp123.rl2.model.Mob.RangedDamageType.PHYSICAL;
        Effect missile = physical
                ? Effect.physicalMissile(m.from(), m.to())
                : Effect.magicMissile(m.from(), m.to(), RNG);
        stage.add(missile);
        if (m.trajectoryVisible()) {
            queue.sequential(missile.totalFrames());
            if (sounds != null) sounds.playAt("sfx.item.use.ranged." + (physical ? "physical" : "magic"), level, m.from());
        }
        Mob caster = m.caster();
        int damage = m.damage();
        Point target = m.to();
        pendingImpacts.add(missile, () -> {
            applyMissileImpact(level, caster, target, damage);
            if (sounds != null) sounds.playAt("sfx.item.impact.ranged." + (physical ? "physical" : "magic"), level, target);
            Effect burst = Effect.wandImpactBurst(target, null, RNG);
            stage.add(burst);
            if (visibleAt(level, target)) queue.concurrent(burst.totalFrames());
        });
    }

    void onWandMissileFired(Level level, GameEvent.WandMissileFired m) {
        Effect missile = buildWandMissile(m.from(), m.to(), m.element());
        stage.add(missile);
        if (m.trajectoryVisible()) {
            queue.sequential(missile.totalFrames());
            if (sounds != null) sounds.playAt(itemUseKey(m.wand()), level, m.from());
        }
        Mob caster = m.caster();
        Point target = m.to();
        Item.ItemEffect element = m.element();
        Item wand = m.wand();
        int effLvl = m.effectiveLevel();
        pendingImpacts.add(missile, () -> {
            com.bjsp123.rl2.logic.ItemSystem.applyWandImpact(level, caster, target, element, wand, effLvl);
            stage.add(com.bjsp123.rl2.world.render.Effect.wandImpactBurst(target, element, RNG));
        });
    }

    void onWandRayFired(Level level, GameEvent.WandRayFired m) {
        Effect ray = Effect.ray(m.from(), m.to(), Effect.EffectTint.WHITE);
        stage.add(ray);
        if (m.trajectoryVisible()) {
            queue.sequential(ray.totalFrames());
            if (sounds != null) sounds.playAt(itemUseKey(m.wand()), level, m.from());
        }
        Mob caster = m.caster();
        Point target = m.to();
        Item.ItemEffect element = m.element();
        Item wand = m.wand();
        int effLvl = m.effectiveLevel();
        pendingImpacts.add(ray,
                () -> com.bjsp123.rl2.logic.ItemSystem.applyWandImpact(level, caster, target, element, wand, effLvl));
    }

    /** Visual for a mob ability cast on another mob - a green ray from
     *  caster to target plus a small burst of green sparks at the target
     *  tile. Blocking on the ray's lifetime so subsequent action animations
     *  in the same tick play after, not over, the cast. */
    void onMobAbilityUsed(Level level, GameEvent.MobAbilityUsed m) {
        Point from = m.from();
        Point to   = m.to();
        if (from == null || to == null) return;
        boolean anyVisible = visibleAt(level, from) || visibleAt(level, to);
        if (!anyVisible) return;
        if (sounds != null) sounds.playAt("sfx.mob.action.ability", level, from);
        Effect ray = Effect.ray(from, to, Effect.EffectTint.GREEN);
        stage.add(ray);
        Effect sparksFrom = Effect.particleBurst(from, Effect.EffectTint.GREEN, 14, RNG);
        sparksFrom.ignoresFov = true;
        stage.add(sparksFrom);
        Effect sparksTo = Effect.particleBurst(to, Effect.EffectTint.GREEN, 28, RNG);
        sparksTo.ignoresFov = true;
        stage.add(sparksTo);
        queue.concurrent(ray.totalFrames());
    }

    void onDoorOpened(Level level, GameEvent.DoorOpened m) {
        if (sounds != null) sounds.playAt("sfx.world.door.open", level, m.pos());
    }

    void onDoorClosed(Level level, GameEvent.DoorClosed m) {
        if (sounds != null) sounds.playAt("sfx.world.door.close", level, m.pos());
    }

    void onOnetimeDoorBroken(Level level, GameEvent.OnetimeDoorBroken m) {
        stage.add(Effect.doorBreakBurst(m.pos(), RNG));
        stage.add(Effect.doorBreakSplash(m.pos(), RNG));
    }

    void onItemThrown(Level level, GameEvent.ItemThrown m) {
        Item it = m.item();
        Mob thrower = m.thrower();
        Point dst = m.to();
        Effect thrown = Effect.thrownItem(m.from(), m.to(), it);
        stage.add(thrown);
        if (m.trajectoryVisible()) {
            queue.sequential(thrown.totalFrames());
            if (sounds != null && it != null && it.inventoryCategory == Item.InventoryCategory.BOMB) {
                sounds.playAt(itemUseKey(it), level, m.from());
            }
        }
        // Defer the engine mutation (damage, knockback, terrain ignite,
        // surface paint, item drop) to the moment the projectile arrives.
        // {@link MobSystem#throwItem} already removed the item from the
        // thrower's inventory and applied move-cost so the turn gating
        // is correct; this PendingImpact resolves the world-side effects
        // exactly when the visual arc lands.
        pendingImpacts.add(thrown,
                () -> com.bjsp123.rl2.logic.MobSystem.applyThrowImpact(
                        level, thrower, it, dst));
    }

    /** Loot tossed off a dying mob - arc the item from the corpse to its
     *  landing tile. Non-blocking (LOOT_TOSS is excluded from the freeze tally). */
    void onLootDropped(GameEvent.LootDropped m) {
        if (m.item() == null || m.from() == null || m.to() == null) return;
        stage.add(Effect.lootToss(m.from(), m.to(), m.item()));
    }

    /** Item picked up by a mob - arc the item off its tile toward the bottom-
     *  right of the screen so it reads as flying into the inventory.
     *  Non-blocking. Skipped when the picker isn't visible. */
    void onItemPickedUp(Level level, GameEvent.ItemPickedUp m) {
        if (m.item() == null || m.from() == null) return;
        stage.add(Effect.pickupToss(m.from(), m.item()));
        if (sounds != null && m.picker() != null) {
            if (m.picker().behavior == Mob.Behavior.PLAYER) {
                Item it = m.item();
                String pickupKey = (it != null && it.inventoryCategory == Item.InventoryCategory.GEM) ? "sfx.player.pickup.gem"
                        : (it != null && it.type != null) ? "sfx.player.pickup." + it.type.toLowerCase()
                        : "sfx.player.pickup";
                sounds.playAt(pickupKey, level, m.from());
            } else {
                sounds.playAt("sfx.mob.action.pickup", level, m.from());
            }
        }
    }

    /** Mob was knocked back - slide the sprite from {@code start} to {@code end},
     *  gated as a blocking sequential animation. A small particle burst fires at
     *  the start tile to signal the impact. Off-screen knockbacks are skipped.
     *  Knockbacks use a snappier per-tile pace than regular movement so the shove
     *  reads as sudden rather than a normal stride. */
    void onMobKnockedBack(Level level, GameEvent.MobKnockedBack m) {
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
        int frames = scaleFrames(
                Math.max(AnimationVars.STEP_FRAMES_MIN, AnimationVars.KNOCKBACK_FRAMES_PER_TILE * dist));
        MobAnimState s = stateOf(mob);
        s.stepFromDx = (float) ddx;
        s.stepFromDy = (float) ddy;
        s.stepFrame  = 0;
        s.stepTotal  = frames;
        s.delayFrames = queue.sequential(frames);
        stage.add(Effect.particleBurst(start, Effect.EffectTint.WHITE, 10, RNG));
        // Two knockback impact flashes:
        //   1) at the start tile, the moment of the original hit;
        //   2) at the end tile, only when the slide stopped because of an
        //      obstacle - held back by the slide duration so the second
        //      flash lands ON the mob as it slams to a stop.
        // A clean full-distance slide (m.blocked() == false) gets only
        // the first flash.
        stage.add(Effect.knockbackFlash(start, 0));
        if (m.blocked()) {
            stage.add(Effect.knockbackFlash(end, frames));
        }
    }

    /** Mob jumped - non-blocking slide from {@code from} to {@code to} with
     *  a puff of dust at both departure and landing tiles. */
    void onMobJumped(Level level, GameEvent.MobJumped m) {
        Mob mob = m.mob();
        if (mob == null) return;
        Point from = m.from(), to = m.to();
        if (from == null || to == null) return;
        boolean visible = MobSystem.isVisibleToPlayer(level, mob)
                || visibleAt(level, from) || visibleAt(level, to);
        if (!visible) return;
        if (sounds != null && mob.behavior == Mob.Behavior.PLAYER) sounds.playAt("sfx.item.use.frog", level, from);
        int ddx = from.tileX() - to.tileX();
        int ddy = from.tileY() - to.tileY();
        int dist = Math.max(Math.abs(ddx), Math.abs(ddy));
        if (dist == 0) return;
        int frames = scaleFrames(Math.max(AnimationVars.STEP_FRAMES_MIN, stepFramesFor(mob) * dist));
        MobAnimState s = stateOf(mob);
        s.stepFromDx = (float) ddx;
        s.stepFromDy = (float) ddy;
        s.stepFrame  = 0;
        s.stepTotal  = frames;
        // Non-blocking - hop and dust run concurrently with the next event.
        float depX = (from.tileX() + 0.5f) * DUST_CELL_PX;
        float depY =  from.tileY()         * DUST_CELL_PX + DUST_SPAWN_Y_OFFSET_PX;
        stage.add(Effect.dustCloud(from.tileX(), from.tileY(), depX, depY,
                0f, AnimationVars.DUST_UP_BIAS_PX_PER_FRAME * 2f, RNG));
        float lanX = (to.tileX() + 0.5f) * DUST_CELL_PX;
        float lanY =  to.tileY()         * DUST_CELL_PX + DUST_SPAWN_Y_OFFSET_PX;
        stage.add(Effect.dustCloud(to.tileX(), to.tileY(), lanX, lanY,
                0f, AnimationVars.DUST_UP_BIAS_PX_PER_FRAME * 2f, RNG));
    }

    /** Grappling-rope visual - extend phase blocks subsequent events
     *  (the dragged-mob's {@link GameEvent.MobKnockedBack} slide that
     *  follows in the same drain pass starts at frame {@code extendFrames}
     *  via {@link AnimQueue#sequential}, lining up with the rope's retract).
     *  Hidden when neither caster nor target tile is visible to the player -
     *  off-screen grapples don't need a visual gate. */
    void onGrappleFired(Level level, GameEvent.GrappleFired m) {
        Point from = m.from(), to = m.target();
        if (from == null || to == null) return;
        boolean visible = visibleAt(level, from) || visibleAt(level, to);
        if (!visible) return;
        if (sounds != null) sounds.playAt("sfx.item.use.grapple", level, from);
        int extendFrames  = scaleFrames(AnimationVars.GRAPPLE_EXTEND_FRAMES);
        int tailFrames    = scaleFrames(m.success()
                ? AnimationVars.GRAPPLE_RETRACT_FRAMES
                : AnimationVars.GRAPPLE_FAIL_TAIL_FRAMES);
        stage.add(Effect.grappleRope(from, to, m.success(),
                extendFrames, tailFrames));
        // sequential() returns the queue delay caused by everything ahead of
        // us - we register ONLY the extend window as the freeze contribution
        // so the retract overlaps the dragged-mob slide that follows.
        // Failure path uses sequential(total) so nothing else animates while
        // the rope holds + fades.
        if (m.success()) {
            queue.sequential(extendFrames);
        } else {
            queue.sequential(extendFrames + tailFrames);
        }
    }

    /** Mob fell through a chasm - revolve-shrink-fade at the source tile.
     *  The engine has already either killed the mob (with items also
     *  falling via {@link GameEvent.ItemFallingIntoChasm}) or relocated
     *  the survivor to the next dungeon level. Non-blocking - same
     *  pattern as the falling-item visual. */
    void onMobFellThroughChasm(GameEvent.MobFellThroughChasm m) {
        if (m.mob() == null || m.fromTile() == null) return;
        stage.add(Effect.fallingMob(m.fromTile(), m.mob()));
    }

    /** Item fell into a chasm - revolve-shrink-fade at its tile. Non-blocking. */
    void onItemFallingIntoChasm(GameEvent.ItemFallingIntoChasm m) {
        if (m.item() == null || m.position() == null) return;
        stage.add(Effect.fallingItem(m.position(), m.item()));
    }

    void onDamageDealt(Level level, GameEvent.DamageDealt m) {
        Mob target = m.target();
        if (target == null || target.position == null) return;
        if (!MobSystem.isVisibleToPlayer(level, target)) return;
        switch (m.message()) {
            case HIT   -> {
                stage.add(Effect.floatingText(target.position, "-" + m.amount(), Effect.EffectTint.RED));
                if (sounds != null) sounds.playAt(target.behavior == Mob.Behavior.PLAYER
                        ? "sfx.player.combat.hit" : "sfx.combat.result.hit", level, target.position);
            }
            case MISS  -> {
                stage.add(Effect.floatingText(target.position,
                        TextCatalog.get("effect.combat.miss"), Effect.EffectTint.YELLOW));
                if (sounds != null) sounds.playAt("sfx.combat.result.miss", level, target.position);
            }
            case BLUNT -> {
                stage.add(Effect.floatingText(target.position,
                        TextCatalog.get("effect.combat.blunt"), Effect.EffectTint.WHITE));
                if (sounds != null) sounds.playAt("sfx.combat.result.block", level, target.position);
            }
            case ENVIRONMENTAL ->
                    stage.add(Effect.floatingText(target.position, "-" + m.amount(), Effect.EffectTint.RED));
        }
    }

    void onHealApplied(Level level, GameEvent.HealApplied m) {
        Mob mob = m.mob();
        if (mob == null || mob.position == null) return;
        if (!MobSystem.isVisibleToPlayer(level, mob)) return;
        stage.add(Effect.floatingText(mob.position, "+" + m.amount(), Effect.EffectTint.GREEN));
    }

    void onMobTamed(Level level, GameEvent.MobTamed m) {
        Mob mob = m.mob();
        if (mob == null || mob.position == null) return;
        if (!MobSystem.isVisibleToPlayer(level, mob)) return;
        stage.add(Effect.floatingText(mob.position,
                TextCatalog.get("effect.mob.tamed"), Effect.EffectTint.GREEN));
        if (sounds != null) sounds.playAt("sfx.mob.action.tame", level, mob.position);
    }

    void onBuffApplied(Level level, GameEvent.BuffApplied m) {
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

    void onWandImpactBurst(Level level, GameEvent.WandImpactBurst m) {
        // Powerup pickups get a custom multi-effect composite instead of
        // the standard 18-spark burst.
        switch (m.element()) {
            case LEVEL_UP -> { spawnLevelUpVisual(level, m.pos()); return; }
            case HP_UP    -> {
                if (sounds != null) sounds.playAt("sfx.player.pickup.healthpill", level, m.pos());
                spawnHpUpVisual(level, m.pos()); return;
            }
            case MANA_UP  -> {
                if (sounds != null) sounds.playAt("sfx.player.pickup.chargepill", level, m.pos());
                spawnManaUpVisual(level, m.pos()); return;
            }
            default -> { /* fall through to the generic-burst path */ }
        }
        if (sounds != null) sounds.playAt("sfx.effect." + elementKey(m.element()), level, m.pos());
        Effect.EffectTint tint = switch (m.element()) {
            case WATER                -> Effect.EffectTint.BLUE;
            case OIL                  -> Effect.EffectTint.YELLOW;
            case GRASS                -> Effect.EffectTint.GREEN;
            case FUNGUS, FIRE         -> Effect.EffectTint.RED;
            case LIGHTNING            -> Effect.EffectTint.YELLOW;
            case FREEZE               -> Effect.EffectTint.BLUE;
            case BLAST, DAMAGE        -> Effect.EffectTint.WHITE;
            case APPLYBUFFS           -> Effect.EffectTint.YELLOW;
            case POISONCLOUD          -> Effect.EffectTint.GREEN;
            case SMOKE                -> Effect.EffectTint.BROWN;
            case DETONATION,
                 BANISHMENT,
                 MISSILE             -> Effect.EffectTint.WHITE;
            case VOID                -> Effect.EffectTint.BROWN;
            case POLYMORPH           -> Effect.EffectTint.ORANGE;
            case LEVEL_UP, HP_UP, MANA_UP -> Effect.EffectTint.YELLOW;
            default -> Effect.EffectTint.CYAN;
        };
        Effect burst = Effect.particleBurst(m.pos(), tint, 18, RNG);
        stage.add(burst);
        if (visibleAt(level, m.pos())) queue.concurrent(burst.totalFrames());
    }

    /** LEVEL_UP pickup composite - non-blocking. Colored particles (gold)
     *  rising and turning white, staggered up-arrows in gold, white border
     *  flash on the player for 0.5 s. */
    private void spawnLevelUpVisual(Level level, Point at) {
        spawnPowerupPickupVisual(level, at, Effect.EffectTint.YELLOW);
    }

    /** HP_UP pickup composite - non-blocking. Red particles rising to white,
     *  red up-arrows, player border flash. */
    private void spawnHpUpVisual(Level level, Point at) {
        spawnPowerupPickupVisual(level, at, Effect.EffectTint.RED);
    }

    /** MANA_UP pickup composite - non-blocking. Blue particles rising to white,
     *  blue up-arrows, player border flash. */
    private void spawnManaUpVisual(Level level, Point at) {
        spawnPowerupPickupVisual(level, at, Effect.EffectTint.BLUE);
    }

    /** Shared pickup composite used by all three powerup types. All effects
     *  are added to the stage without calling {@link AnimQueue#concurrent} so
     *  the animation never blocks the game tick (the player can act immediately). */
    private void spawnPowerupPickupVisual(Level level, Point at, Effect.EffectTint tint) {
        // 32 staggered particles: each spawns at a random frame in [0, 64],
        // drifts upward from near the tile centre, turns white, then fades.
        stage.add(Effect.powerupParticles(at, tint, 32, RNG));
        // Single up-arrow icon rising from the tile in the powerup colour.
        stage.add(Effect.upArrow(at, tint, 0));
        // Player border flash - 0.5 s white border around the player sprite.
        Mob standing = mobAt(level, at);
        if (standing != null) {
            stateOf(standing).borderFlashFrames = AnimationVars.BORDER_FLASH_FRAMES;
        }
    }

    /** Look up the mob whose logical position matches {@code at}. */
    private static Mob mobAt(Level level, Point at) {
        if (level == null || at == null || level.mobs == null) return null;
        int tx = at.tileX(), ty = at.tileY();
        for (Mob m : level.mobs) {
            if (m == null || m.position == null) continue;
            if (m.position.tileX() == tx && m.position.tileY() == ty) return m;
        }
        return null;
    }

    /** Throw-bomb / blast-bomb shockwave. Blocks while visible so a rapid second
     *  action doesn't slide under the burst. */
    void onBlastEffect(Level level, GameEvent.BlastEffect m) {
        Effect blast = Effect.blast(m.pos());
        stage.add(blast);
        if (visibleAt(level, m.pos())) {
            queue.concurrent(blast.totalFrames());
            if (sounds != null) sounds.playAt("sfx.effect.blast", level, m.pos());
        }
    }

    /** Radial fire-ball burst (on-death explosion, detonation wand). Blocks while
     *  visible so the player sees the boom resolve before the next AI turn lands. */
    void onExplosionEffect(Level level, GameEvent.ExplosionEffect m) {
        Effect boom = Effect.explosion(m.pos(), m.radiusTiles(), RNG);
        stage.add(boom);
        if (visibleAt(level, m.pos())) {
            queue.concurrent(boom.totalFrames());
            if (sounds != null) sounds.playAt("sfx.effect.blast", level, m.pos());
        }
    }

    /** Swirling rising particles in the potion's colour. Triggered when a
     *  potion is drunk or thrown. Colour is derived from the potion's
     *  {@code appliesBuff} (so any future buff-bearing potion picks up a
     *  sensible tint without code changes), with a fall-through on the item's
     *  {@code type} string for the special-cased POTION_INSIGHT (which carries
     *  no buff). */
    void onPotionBurst(Level level, GameEvent.PotionBurst m) {
        Item item = m.item();
        if (item == null) return;
        if (sounds != null) sounds.playAt(itemUseKey(item), level, m.pos());
        Effect.EffectTint tint = potionTint(item);
        Effect burst = Effect.particleBurst(m.pos(), tint, 18, RNG);
        stage.add(burst);
        if (visibleAt(level, m.pos())) queue.concurrent(burst.totalFrames());
    }

    /** Mob just spawned - blocking grow-from-zero animation while a fountain
     *  of upward-drifting particles plays at the spawn tile. Skipped (and
     *  doesn't block) when the spawn tile isn't currently visible to the
     *  player, so off-screen ant-hill blooms etc. don't freeze the game. */
    void onMobSpawned(Level level, GameEvent.MobSpawned m) {
        Mob mob = m.mob();
        if (mob == null || mob.position == null) return;
        Point at = m.at();
        if (at == null) return;
        // Reset the per-mob spawn-grow counter regardless of visibility so the
        // sprite always starts at full size for off-camera spawns (no scale
        // hangover when the player later walks into the room).
        MobAnimState s = stateOf(mob);
        s.spawnFrame = 0;
        s.spawnTotalFrames = AnimationVars.SPAWN_GROW_FRAMES;
        if (!visibleAt(level, at)) {
            // Off-screen: skip particles AND skip the blocking freeze. Set
            // spawnFrame to the end so the sprite is full-size by the time
            // the player ever sees it.
            s.spawnFrame = s.spawnTotalFrames;
            return;
        }
        if (sounds != null) sounds.playAt("sfx.mob.spawn.generic", level, at);
        stage.add(Effect.particleBurst(at, Effect.EffectTint.WHITE, 14, RNG));
        if (queue.freezeFrames < AnimationVars.SPAWN_GROW_FRAMES) {
            queue.freezeFrames = AnimationVars.SPAWN_GROW_FRAMES;
        }
    }

    /** Surface (water/oil/blood/ice) just appeared on a tile - fountain of
     *  particles in the surface's colour. Blocks while visible so a wand-of-water
     *  / oil splatter resolves before the next AI turn lands. Skipped (and
     *  doesn't block) when the tile is currently off-screen. */
    void onSurfaceChanged(Level level, GameEvent.SurfaceChanged m) {
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

    /** Vegetation just changed on a tile (grass / mushrooms / fire / trees) -
     *  fountain of particles in the vegetation's colour. Blocks while visible
     *  (wand-of-grass / wand-of-fungus growth, fire spread). */
    void onVegetationChanged(Level level, GameEvent.VegetationChanged m) {
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

    /** Multi-coloured rainbow burst (power-orb absorb, level-up). Blocking -
     *  six per-colour bursts stacked at the same tile so the player visually
     *  reads "many colours flying out". */
    void onRainbowBurst(Level level, GameEvent.RainbowBurst m) {
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

    void onXPGainBurst(Level level, GameEvent.XPGainBurst m) {
        if (m.pos() == null || !visibleAt(level, m.pos())) return;
        if (sounds != null) sounds.playAt("sfx.player.pickup.xppill", level, m.pos());
        spawnPowerupPickupVisual(level, m.pos(), EffectTint.ORANGE);
        spawnPowerupPickupVisual(level, m.pos(), EffectTint.YELLOW);
        spawnPowerupPickupVisual(level, m.pos(), EffectTint.WHITE);
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
     *  first (so HEALING_POTION's REGENERATION -> green works regardless of
     *  the potion's display name), with a special case for POTION_INSIGHT
     *  which carries no buff. Constrained to the seven existing
     *  {@link Effect.EffectTint} values; new tints would also need a
     *  {@code FxRenderer.tintToColor} entry, which isn't worth it for these
     *  one-off potions. */
    private static Effect.EffectTint potionTint(Item item) {
        com.bjsp123.rl2.model.Buff.BuffType primary = item.primaryBuff();
        if (primary == null) return Effect.EffectTint.WHITE;
        return switch (primary) {
            case REGENERATION       -> Effect.EffectTint.GREEN;
            case POISONED           -> Effect.EffectTint.BROWN;
            case SORCERY            -> Effect.EffectTint.BLUE;
            case GHOSTLY            -> Effect.EffectTint.WHITE;
            case INVISIBLE          -> Effect.EffectTint.BLUE;
            case HOPE, ESP, INSIGHT -> Effect.EffectTint.YELLOW;
            default                 -> Effect.EffectTint.WHITE;
        };
    }

    void onPeriodicBuffDamage(Level level, GameEvent.PeriodicBuffDamage m) {
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

    // -- Pending-impact resolution --------------------------------------------------

    /** Fire impact callbacks for any projectile whose visual is one frame from removal,
     *  then drain any events the callback emitted (e.g. explosions, plant growth, blood
     *  surfaces) so their freeze contributions land before the next render frame's
     *  game tick - otherwise the wand's secondary visuals wouldn't block AI turns. */
    private void firePendingImpacts(Level level) {
        if (pendingImpacts.fireCompleting(level, this)) {
            impactFiredThisTick = true;
        }
    }

    /** Damage application for plain magic missiles (PlayScreen's wand-of-magic-missile,
     *  the staff legacy missile, AI ranged shooters). Mirrors the legacy
     *  {@code PlayScreen.resolveCompletingMissiles} body. */
    private static void applyMissileImpact(Level level, Mob caster, Point target, int damage) {
        Mob victim = MobQueries.mobAt(level, target);
        if (victim == null) return;
        // Adjacent ranged penalty: -50 accuracy for point-blank shots.
        if (caster != null && caster.position != null) {
            int cdx = Math.abs(caster.position.tileX() - target.tileX());
            int cdy = Math.abs(caster.position.tileY() - target.tileY());
            if (Math.max(cdx, cdy) == 1 && !MobSystem.rollRangedHit(caster, victim, -50)) {
                String cn = caster.name != null ? caster.name
                        : TextCatalog.get("eventlog.fallback.adventurer");
                String vn = MobSystem.nameForLog(level, victim);
                boolean attackerIsPlayer = caster.behavior == com.bjsp123.rl2.model.Mob.Behavior.PLAYER;
                boolean victimIsPlayer   = victim.behavior  == com.bjsp123.rl2.model.Mob.Behavior.PLAYER;
                com.bjsp123.rl2.logic.EventLog.add(attackerIsPlayer
                        ? com.bjsp123.rl2.logic.Messages.playerMiss(cn, vn)
                        : (victimIsPlayer
                           ? com.bjsp123.rl2.logic.Messages.enemyMiss(cn, vn)
                           : com.bjsp123.rl2.logic.Messages.mobMiss(cn, vn)));
                return;
            }
        }
        boolean physical = caster != null
                && caster.rangedDamageType == com.bjsp123.rl2.model.Mob.RangedDamageType.PHYSICAL;
        MobSystem.DamageElement element = physical
                ? MobSystem.DamageElement.PHYSICAL : MobSystem.DamageElement.MAGIC;
        MobSystem.AttackType attackType = physical
                ? MobSystem.AttackType.RANGED : MobSystem.AttackType.MAGIC;
        int resist = physical
                ? MobSystem.rollRange(MobSystem.resistRange(victim))
                : MobSystem.rollRange(MobSystem.magicResistRange(victim));
        int afterResist = Math.max(0, damage - resist);
        String resistKey = physical ? "armor" : "magicResist";
        MobSystem.DamageBreakdown bk =
                new MobSystem.DamageBreakdown(element, damage)
                        .add(resistKey, Math.min(resist, damage));
        Mob speaker = caster != null ? caster : com.bjsp123.rl2.logic.TurnSystem.findPlayer(level);
        afterResist = MobSystem.applySurpriseIfNeeded(level, speaker, victim,
                afterResist, attackType, element);
        String casterName = speaker != null && speaker.name != null
                ? speaker.name
                : TextCatalog.get("eventlog.fallback.adventurer");
        String victimName = MobSystem.nameForLog(level, victim);
        double hpBefore = victim.hp;
        MobSystem.processAttack(level, speaker, victim, afterResist, attackType, element, bk);
        int dealt = Math.max(0, (int) Math.round(hpBefore - victim.hp));
        com.bjsp123.rl2.logic.EventLog.add(
                com.bjsp123.rl2.logic.Messages.playerHit(casterName, victimName, dealt));
    }

    // -- Real-time particle drains --------------------------------------------------

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
                        countdown += AnimationVars.FIRE_PARTICLE_INTERVAL_MS;
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
                countdown += AnimationVars.FIRE_PARTICLE_MOB_INTERVAL_MS;
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
                s.sleepZCountdownMs = AnimationVars.SLEEP_Z_MIN_MS + RNG.nextInt(AnimationVars.SLEEP_Z_RANGE_MS);
            }
        }
    }

    /** Wall-clock interval between cloud puffs at a levitating mob's feet.
     *  Slow enough that a single mob reads as drifting, not smoking. */

    /** Per-mob foot-puff cadence for mobs with the LEVITATING buff. Visible
     *  mobs only - out-of-FOV mobs don't burn effect slots on particles
     *  the player can't see. The puff appears just below the mob's tile
     *  centre and drifts gently downward / outward to read as displaced
     *  air. */
    private void tickLevitatePuffs(Level level, int dtMs) {
        if (level.mobs == null) return;
        for (Mob m : level.mobs) {
            boolean levitating = com.bjsp123.rl2.logic.BuffSystem.hasBuff(m,
                    com.bjsp123.rl2.model.Buff.BuffType.LEVITATING);
            MobAnimState s = states.get(m);
            if (!levitating || m.hp <= 0 || m.position == null) {
                if (s != null) s.levitatePuffCountdownMs = 0;
                continue;
            }
            if (!MobSystem.isVisibleToPlayer(level, m)) continue;
            if (s == null) s = stateOf(m);
            s.levitatePuffCountdownMs -= dtMs;
            while (s.levitatePuffCountdownMs <= 0) {
                emitLevitatePuff(m, s);
                s.levitatePuffCountdownMs += AnimationVars.LEVITATE_PUFF_MIN_MS
                        + RNG.nextInt(AnimationVars.LEVITATE_PUFF_RANGE_MS);
            }
        }
    }

    /** Spawn one small foot-puff under {@code mob}'s current visual tile. */
    private void emitLevitatePuff(Mob mob, MobAnimState s) {
        float visualTX = mob.position.tileX();
        float visualTY = mob.position.tileY();
        if (s.stepTotal > 0) {
            float t = Math.min(1f, s.stepFrame / (float) s.stepTotal);
            visualTX += s.stepFromDx * (1f - t);
            visualTY += s.stepFromDy * (1f - t);
        }
        float jx = (RNG.nextFloat() - 0.5f) * 8f;
        float jy = (RNG.nextFloat() - 0.5f) * 2f;
        float pxX = (visualTX + 0.5f) * DUST_CELL_PX + jx;
        float pxY = visualTY * DUST_CELL_PX + 1f + jy;
        double angle = RNG.nextDouble() * Math.PI * 2.0;
        float speed = 0.10f + RNG.nextFloat() * 0.10f;
        float vx = (float) Math.cos(angle) * speed;
        float vy = (float) Math.sin(angle) * speed - 0.05f; // slight sink
        int tileX = (int) Math.floor(visualTX);
        int tileY = (int) Math.floor(visualTY);
        stage.add(Effect.dustCloud(tileX, tileY, pxX, pxY, vx, vy, RNG));
    }

    // -- Helpers --------------------------------------------------------------------

    /** Build a coloured wand missile with palette / gravity / brightness picked from the
     *  element. Mirrors the legacy {@code PlayScreen.buildWandMissile}. */
    private static Effect buildWandMissile(Point from, Point to, Item.ItemEffect element) {
        Effect.EffectTint head;
        Effect.EffectTint[] palette;
        float gravity = 0f;
        float size;
        boolean bright = true;
        float baseSize = 2.0f;
        switch (element) {
            case WATER -> {
                head    = Effect.EffectTint.BLUE;
                palette = new Effect.EffectTint[] { Effect.EffectTint.BLUE };
                size = baseSize;
            }
            case OIL -> {
                head    = Effect.EffectTint.YELLOW;
                palette = new Effect.EffectTint[] { Effect.EffectTint.YELLOW, Effect.EffectTint.BROWN };
                size = baseSize;
            }
            case GRASS -> {
                head    = Effect.EffectTint.GREEN;
                palette = new Effect.EffectTint[] { Effect.EffectTint.GREEN, Effect.EffectTint.YELLOW };
                size = baseSize;
            }
            case FUNGUS -> {
                head    = Effect.EffectTint.RED;
                palette = new Effect.EffectTint[] { Effect.EffectTint.RED, Effect.EffectTint.BROWN };
                size = baseSize;
            }
            case FIRE -> {
                head    = Effect.EffectTint.YELLOW;
                palette = new Effect.EffectTint[] { Effect.EffectTint.RED, Effect.EffectTint.YELLOW, Effect.EffectTint.ORANGE };
                size = baseSize + 0.5f;
            }
            case DETONATION -> {
                head    = Effect.EffectTint.YELLOW;
                palette = new Effect.EffectTint[] { Effect.EffectTint.RED, Effect.EffectTint.YELLOW };
                size = baseSize + 0.4f;
            }
            case MISSILE -> {
                head    = Effect.EffectTint.WHITE;
                palette = new Effect.EffectTint[] { Effect.EffectTint.WHITE };
                size = baseSize;
            }
            default -> {
                head    = Effect.EffectTint.WHITE;
                palette = new Effect.EffectTint[] { Effect.EffectTint.WHITE };
                size = baseSize;
            }
        }
        return Effect.magicMissileColored(from, to, palette, head, gravity, size, bright, RNG);
    }

    // -- Sound key helpers ----------------------------------------------------------

    /** Returns the most-specific {@code sfx.item.use.*} key for the given item.
     *  WAND/BOMB/POTION/FOOD get a category segment; ITEM-category items go
     *  straight to their type. SoundManager's fallback strips the last segment
     *  until a CSV entry is found. */
    private static String itemUseKey(Item item) {
        if (item == null) return "sfx.item.use";
        String type = item.type != null ? "." + item.type.toLowerCase() : "";
        return switch (item.inventoryCategory != null ? item.inventoryCategory : Item.InventoryCategory.ITEM) {
            case WAND   -> "sfx.item.use.wand"   + type;
            case BOMB   -> "sfx.item.use.bomb"   + type;
            case POTION -> "sfx.item.use.potion" + type;
            case FOOD   -> "sfx.item.use.food"   + type;
            default     -> "sfx.item.use"        + type;
        };
    }

    /** Lowercases an ItemEffect name for use as an {@code sfx.effect.*} segment. */
    private static String elementKey(Item.ItemEffect e) {
        return e != null ? e.name().toLowerCase() : "missile";
    }

    // -- Anim / step tuning (mirrors MobSystem constants) ---------------------------
    /** Per-tile pace for knockback slides - faster than regular stepping so a shove
     *  reads as a sudden recoil instead of a stride. */
    /** Grappling-rope frame budget. Extend phase is the freeze contribution
     *  ({@link AnimQueue#sequential}); the dragged-mob slide is queued
     *  immediately after and runs concurrently with the retract phase. */
    /** Failure tail - flash + fade-out at full extent when the target is
     *  too heavy. Longer than the success retract because the held flash
     *  needs a beat to read before the fade. */

    private static int stepFramesFor(Mob mob) {
        int cost = (mob == null || mob.intrinsic == null) ? 100 : (int) mob.effectiveStats().moveCost;
        if (cost <= 0) return AnimationVars.STEP_FRAMES_MIN;
        int scaled = Math.round(AnimationVars.STEP_FRAMES_DEFAULT * cost / 100f);
        if (scaled < AnimationVars.STEP_FRAMES_MIN) return AnimationVars.STEP_FRAMES_MIN;
        if (scaled > AnimationVars.STEP_FRAMES_MAX) return AnimationVars.STEP_FRAMES_MAX;
        return scaled;
    }

    /** Compress {@code frames} based on how many sequential animations are
     *  already queued this drain pass. Returns {@code frames} unchanged when
     *  queue-acceleration is disabled or the queue is shallow (0-1 deep).
     *  Step function: 2->-25 %, 3-4->-33 %, 5-6->-50 %, 7+->-60 %. */
    private int scaleFrames(int frames) {
        if (!com.bjsp123.rl2.ui.skin.Settings.queueAccelEnabled()) return frames;
        float scale = queueAccelScale(queue.sequentialCount() + 1);
        if (scale >= 1f) return frames;
        return Math.max(1, Math.round(frames * scale));
    }

    private static float queueAccelScale(int n) {
        if (n >= 7) return 0.25f;
        if (n >= 5) return 0.4f;
        if (n >= 3) return 0.6f;
        if (n >= 2) return 0.70f;
        return 1.0f;
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
    private static java.util.function.DoubleSupplier animationSpeedSupplier;
    public static void setAnimationSpeedSupplier(java.util.function.DoubleSupplier s) { animationSpeedSupplier = s; }

    private static float framesPerRender() {
        if (animationSpeedSupplier == null) return 1;
        double f = animationSpeedSupplier.getAsDouble();
        return (float) (f > 0 ? f : 1);
    }
}
