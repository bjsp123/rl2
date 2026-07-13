package com.bjsp123.rl2.world.anim;
import com.bjsp123.rl2.audio.SoundManager;
import com.bjsp123.rl2.event.GameEvent;
import com.bjsp123.rl2.logic.MobQueries;
import com.bjsp123.rl2.logic.MobMovement;
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

    /** Fired when the player takes damage and their POST-hit HP is at or
     *  below 20% of max. The HUD uses this to play a warning sfx + flash.
     *  No-op when null. */
    private Runnable onPlayerLowHpHit;
    public void setOnPlayerLowHpHit(Runnable r) { this.onPlayerLowHpHit = r; }

    private final IdentityHashMap<Mob, MobAnimState> states = new IdentityHashMap<>();
    private final List<Ghost> ghosts = new ArrayList<>();
    private final PendingImpactQueue pendingImpacts = new PendingImpactQueue();
    /** Set to true whenever a pending impact fires this tick; cleared by the caller. */
    public boolean impactFiredThisTick = false;
    static final Random RNG = new Random();

    /** Shared effect RNG, exposed so callers outside this package (e.g.
     *  {@code PlayScreen}'s intro arrival cloud) can spawn effects with the
     *  same jitter source. */
    public Random rng() { return RNG; }

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
            if (m != null && m.isPlayer) {
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
        //
        // Logical animation frames advance on WALL-CLOCK time (one logical frame =
        // 1/60 s) rather than once per render frame, so a 120 Hz display plays
        // event animations at the same duration as a 60 Hz one instead of 2x
        // speed. The animation-speed multiplier (0.5/1/2/4x) still scales it.
        float speed = framesPerRender();
        frameProgress += frameDelta(dtMs);

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
                if (s.phaseDodgeFrames > 0) s.phaseDodgeFrames--;
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
            // Pure-ambience cadences are skipped in fast graphics: fire embers
            // (the burning tile/mob still draws), levitation puffs and DRIFT
            // buff sparkles (both duplicated by buff icons). Sleep-Zs stay -
            // they're the only at-a-glance ASLEEP tell - and cloud puffs stay
            // because they ARE the rendering of gas clouds (poison etc.).
            boolean fast = com.bjsp123.rl2.ui.skin.Settings.fastGraphics();
            if (!fast) {
                tickBurningParticles(level, dtMs);
                tickLevitatePuffs(level, dtMs);
                tickBuffParticles(level, dtMs);
            }
            tickSleepZs(level, dtMs);
            // Cloud puffs are per-render-frame Poisson emission, not
            // wall-clock; emit them once per render regardless of speed.
            emitCloudPuffs(level);
        }
        // Teleport-fades are event-driven (the engine's MobTeleported
        // event spawns them) so they share the speed multiplier with
        // every other event animation.
        int scaledDtMs = (int)((float)dtMs * speed);
        if (level != null && scaledDtMs > 0) {
            tickTeleportFades(level, scaledDtMs);
        }
        // Foot dust - spawn one cloud per render frame at the player's
        // current visual foot position, while the player is mid-step.
        // The per-frame cadence (rather than per-game-tick) gives a dense
        // trail that hides the bottom of the sprite. Pure ambience - skipped
        // in fast graphics (one particle spawn per frame while moving).
        if (!com.bjsp123.rl2.ui.skin.Settings.fastGraphics()) spawnFootDust(level);
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
            if (m != null && m.isPlayer) { player = m; break; }
        }
        if (player == null || player.position == null) return;
        // Levitating / flying movers don't kick up dust.
        if (player.effectiveStats().flying) return;
        MobAnimState s = states.get(player);
        if (s == null || s.stepTotal <= 0 || s.delayFrames > 0) return;
        if (!MobSystem.isVisibleToPlayer(level, player)) return;
        // No dust on surface (water / oil / blood / ice) or vegetation (grass /
        // mushrooms / trees / fire) tiles — splashes handle those instead.
        int destX = player.position.tileX();
        int destY = player.position.tileY();
        if (destX >= 0 && destY >= 0 && destX < level.width && destY < level.height) {
            if (level.surface    != null && level.surface[destX][destY]    != null) return;
            if (level.vegetation != null && level.vegetation[destX][destY] != null) return;
        }

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
     *  per-mob anim state and pushing visual effects into the stage.
     *
     *  <p>Arc-aware dispatch: throws and wand projectiles mutate world state
     *  synchronously in rlib (see {@code MobSystem.throwItem} javadoc), so a
     *  single batch typically contains the {@code ItemThrown} /
     *  {@code WandMissileFired} event followed immediately by the damage /
     *  death / blast / ignition events from {@code applyThrowImpact} /
     *  {@code applyWandImpact}. To preserve the on-screen "arc flies ->
     *  impact -> target reacts" reading we dispatch the projectile-spawning
     *  event normally (registers the arc with {@link PendingImpactQueue})
     *  and defer the dispatch of every subsequent event in the batch until
     *  the arc lands. Cross-batch deferral works too: if another batch
     *  arrives while an arc is still in flight (rare; the world-tick gate
     *  in PlayScreen normally prevents this), its events also defer. */
    public void consume(Level level) {
        if (level.events == null || level.events.isEmpty()) return;
        if (sounds != null) sounds.beginFrame();
        // Reset sequential counter so scaleFrames() reads a fresh depth for this batch.
        queue.resetSequentialCount();
        // Stagger index for scroll item-create showcases drained this batch.
        itemShowcaseCount = 0;
        // Cache the player once for this drain pass so the achievement
        // observer (and any future per-event hooks) don't re-scan
        // level.mobs per event.
        Mob playerForEvents = eventObserver != null ? TurnSystem.findPlayer(level) : null;
        for (GameEvent ev : level.events) {
            if (eventObserver != null) eventObserver.onEvent(ev, playerForEvents, level);
            if (shouldDispatchImmediately(ev)) {
                AnimationEventDispatcher.dispatch(this, level, ev);
            } else {
                final GameEvent deferred = ev;
                boolean queued = pendingImpacts.addToLatestArc(
                        () -> AnimationEventDispatcher.dispatch(this, level, deferred));
                if (!queued) {
                    // No active arc - dispatch normally.
                    AnimationEventDispatcher.dispatch(this, level, ev);
                }
            }
            // MobIgnited / MobExtinguished / MobSlept / MobWoke are polled instead of
            // event-driven (the Animator scans BuffSystem and StateOfMind each tick).
        }
        level.events.clear();
    }

    /** Which events bypass arc-deferral. The projectile-spawning events
     *  themselves (ItemThrown, WandMissileFired, WandRayFired) must run
     *  immediately so the arc registers with {@link PendingImpactQueue}
     *  before subsequent damage/death/blast events would queue against it.
     *  Everything else gets the arc-deferral check. */
    private static boolean shouldDispatchImmediately(GameEvent ev) {
        return ev instanceof GameEvent.ItemThrown
            || ev instanceof GameEvent.WandMissileFired
            || ev instanceof GameEvent.WandRayFired;
    }

    /** Public helper used by PlayScreen to spawn player-side projectiles directly. */
    public void addPendingImpact(Effect effect, Runnable onComplete) {
        pendingImpacts.add(effect, onComplete);
    }

    /** Whether any projectile / thrown-item visual is still awaiting its
     *  impact callback. Used by {@link com.bjsp123.rl2.screen.PlayScreen} as
     *  an additional gate on the per-frame world tick: if a projectile is
     *  about to resolve, the world must not advance again until the impact
     *  has applied, otherwise mobs targeted by the projectile can slip a
     *  free move before the hit lands. */
    public boolean hasPendingImpacts() {
        return !pendingImpacts.isEmpty();
    }

    /** True when no ghost currently in the fade-out list belongs to a PLAYER mob.
     *  Used by {@code PlayScreen} to hold the V2GameOver transition until the
     *  killing attack's lunge / flinch / flicker / fade animation has fully
     *  played for the dying player; without this latch the screen swap fires
     *  the same render frame the death event is emitted, cutting the animation
     *  short. Returns true (no animation in flight) when there's no player
     *  ghost - works for live runs and for the pre-death state. */
    public boolean playerDeathAnimComplete() {
        for (Ghost g : ghosts) {
            if (g.mob != null && g.mob.isPlayer) return false;
        }
        return true;
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
        if (sounds != null && m.mob() != null && m.mob().isPlayer) {
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
        } else if (m.mob() != null && m.mob().isPlayer
                && level.vegetation != null
                && level.vegetation[m.toX()][m.toY()] == Level.Vegetation.GRASS) {
            // Player stepping into grass kicks up a pale-green splash. Green tint
            // blends to white over the lifetime, reading as pale green throughout.
            stage.add(Effect.footSplash(new Point(m.toX(), m.toY()), Effect.EffectTint.GREEN, RNG));
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
        boolean isPlayer = attacker.isPlayer;
        float flashDelay = (n > 0f) ? stateOf(attacker).delayFrames : 0;
        stage.add(Effect.attackFlash(attacker.position, isPlayer,
                attacker.facingEast, (int) flashDelay));
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
        if (sounds != null) sounds.playAt("sfx.combat.surprise", level, target.position);
        // First-encounter tip: explain the 1.5x damage mechanic. Fires on any
        // visible surprise (player surprising a mob OR a mob surprising the
        // player); once shown, the tip stays dismissed for the rest of the run.
        com.bjsp123.rl2.ui.v2.TipSystem.maybeShow(
                "concept:surprise", "concept.surprise.tip",
                "concept.surprise.name", null);
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
        // Fires alongside the DamageDealt HIT sound; leave the key empty in
        // sounds.csv unless a distinct flinch thud is wanted.
        if (sounds != null) sounds.playAt("sfx.combat.flinch", level, target.position, 0.5f);
    }

    void onMobKilled(Level level, GameEvent.MobKilled m) {
        if (!m.visibleAtKill()) return;
        if (sounds != null) {
            Mob dead = m.mob();
            if (dead != null && dead.isPlayer) {
                sounds.playAt("sfx.player.combat.die", level, new Point(m.x(), m.y()));
            } else if (level.kind == Level.LevelKind.FINAL_BOSS
                    && dead != null && "GREAT_WRAITH".equals(dead.mobType)) {
                // Final-boss death: a climactic scream at full volume (non-spatial).
                sounds.play("sfx.boss.death");
            } else {
                sounds.playAt("sfx.mob.combat.die", level, new Point(m.x(), m.y()));
            }
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

    void onMobTeleported(Level level, GameEvent.MobTeleported m) {
        if (m.mob() == null) return;
        MobAnimState s = stateOf(m.mob());
        s.teleportFromX = m.fromX();
        s.teleportFromY = m.fromY();
        s.teleportFadeMs = MobMovement.TELEPORT_FADE_TOTAL_MS;
        Point from = new Point(m.fromX(), m.fromY());
        stage.add(Effect.teleportStreaks(from, /*up=*/true, RNG));
        if (sounds != null) sounds.playAt("sfx.mob.action.teleport", level, from);
    }

    void onMagicMissileFired(Level level, GameEvent.MagicMissileFired m) {
        boolean physical = m.caster() != null
                && m.caster().rangedDamageType == com.bjsp123.rl2.model.Mob.RangedDamageType.PHYSICAL;
        Effect missile = physical
                ? Effect.physicalMissile(m.from(), m.to())
                : Effect.magicMissile(m.from(), m.to(), null, Effect.EffectTint.WHITE,
                                      com.bjsp123.rl2.world.anim.AnimationVars.PARTICLE_GRAVITY,
                                      1.5f, false, RNG);
        stage.add(missile);
        if (m.trajectoryVisible()) {
            queue.sequential(missile.totalFrames());
            if (sounds != null) sounds.playAt("sfx.item.use.ranged." + (physical ? "physical" : "magic"), level, m.from());
        }
        Point target = m.to();
        // Point-blank first-encounter tip - renderer-side concern, so it stays
        // here rather than in rlib's resolve. Fires only when the penalty
        // actually applied: shooter adjacent to the impact tile AND a victim
        // stood there when the missile landed.
        boolean pointBlank = m.from() != null
                && com.bjsp123.rl2.logic.LevelFactoryUtils.chebyshev(m.from(), target) == 1;
        // ANIMATION-GATED LIFECYCLE: pop the deferred resolve that rlib's
        // tryRangedShot queued (hit roll, resist, processAttack) and run it
        // when the missile arc lands - same pattern as wand missiles.
        Runnable resolve = com.bjsp123.rl2.logic.MobSystem.popNextPendingImpact(level);
        pendingImpacts.add(missile, () -> {
            boolean victimPresent = MobQueries.mobAt(level, target) != null;
            if (resolve != null) resolve.run();
            if (pointBlank && victimPresent) {
                com.bjsp123.rl2.ui.v2.TipSystem.maybeShow(
                        "concept:pointBlank", "concept.pointBlank.tip",
                        "concept.pointBlank.name", null);
            }
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
        Point target = m.to();
        Item.ItemEffect element = m.element();
        // ANIMATION-GATED LIFECYCLE: pop the deferred resolve that rlib's
        // fireWand queued and run it when the missile arc lands. The visual
        // impact burst stages at the same moment.
        Runnable resolve = com.bjsp123.rl2.logic.MobSystem.popNextPendingImpact(level);
        pendingImpacts.add(missile, () -> {
            if (resolve != null) resolve.run();
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
        // ANIMATION-GATED LIFECYCLE: pop the deferred resolve that rlib's
        // fireWand queued and run it when the ray finishes.
        Runnable resolve = com.bjsp123.rl2.logic.MobSystem.popNextPendingImpact(level);
        pendingImpacts.add(ray, () -> {
            if (resolve != null) resolve.run();
        });
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
        // No first-encounter tip for plain or crystal doors - only the
        // onetime door (handled by onOnetimeDoorBroken) carries one, since
        // it's the variant with non-obvious mechanics worth surfacing.
    }

    void onDoorClosed(Level level, GameEvent.DoorClosed m) {
        if (sounds != null) sounds.playAt("sfx.world.door.close", level, m.pos());
    }

    void onOnetimeDoorBroken(Level level, GameEvent.OnetimeDoorBroken m) {
        Effect.doorBreakEffect(stage, sounds, level, m.pos(), RNG);
        com.bjsp123.rl2.ui.v2.TipSystem.maybeShow(
                "concept:onetime", "concept.onetime.tip", "concept.onetime.name", null);
    }

    void onBeaconActivated(GameEvent.BeaconActivated m) {
        Effect.beaconActivation(stage, m.pos(), RNG);
        // Climactic, always at the player's feet - full-volume non-spatial.
        if (sounds != null) sounds.play("sfx.world.beacon.activate");
        com.bjsp123.rl2.ui.v2.TipSystem.maybeShow(
                "concept:beacon", "concept.beacon.tip", "concept.beacon.name", null);
    }

    void onPlayerTeleportOut(GameEvent.PlayerTeleportOut m) {
        stage.add(Effect.playerTeleportOut(m.pos(), RNG));
        if (sounds != null) sounds.play("sfx.player.teleport.out");
    }

    void onPlayerTeleportIn(GameEvent.PlayerTeleportIn m) {
        stage.add(Effect.playerTeleportIn(m.pos(), RNG));
        if (sounds != null) sounds.play("sfx.player.teleport.in");
    }

    /** Latched true for one consume() when the player's Jade Peach fires, so
     *  PlayScreen can kick off the full-screen revive cinematic. */
    public boolean playerRevivedSignal = false;

    void onPlayerRevived(GameEvent.PlayerRevived m) {
        Effect.reviveRing(stage, m.pos(), RNG);
        if (sounds != null) sounds.play("sfx.player.revive");
        playerRevivedSignal = true;
    }

    void onItemThrown(Level level, GameEvent.ItemThrown m) {
        Item it = m.item();
        Effect thrown = Effect.thrownItem(m.from(), m.to(), it);
        stage.add(thrown);
        if (m.trajectoryVisible()) {
            queue.sequential(thrown.totalFrames());
            if (sounds != null && it != null && it.inventoryCategory == Item.InventoryCategory.BOMB) {
                sounds.playAt(itemUseKey(it), level, m.from());
            }
            // First-encounter tip: any visible throw from a non-player teaches
            // the player that mobs pick up and use items too. Bomb-flavoured
            // wording since that's what the player will see most often, but
            // a tossed potion / orb fires the same hook.
            Mob thrower = m.thrower();
            if (thrower != null && !thrower.isPlayer) {
                com.bjsp123.rl2.ui.v2.TipSystem.maybeShow(
                        "concept:enemyThrows", "concept.enemyThrows.tip",
                        "concept.enemyThrows.name", null);
            }
        }
        // ANIMATION-GATED LIFECYCLE step 4: pop the deferred resolve that
        // rlib's throwItem queued for this throw and run it when the arc
        // lands. The resolve is the call to applyThrowImpact - it mutates
        // world state (damage, knockback, ignition, item-fate) AT THE
        // MOMENT THE BOMB ARRIVES, not when the throw was decided. Damage
        // popups / explosion FX / etc. all fire in the same tick as the
        // mutation so the player never sees state ahead of the visual.
        Runnable resolve = com.bjsp123.rl2.logic.MobSystem.popNextPendingImpact(level);
        pendingImpacts.add(thrown, () -> {
            if (resolve != null) resolve.run();
        });
    }

    /** Loot tossed off a dying mob - arc the item from the corpse to its
     *  landing tile. Non-blocking (LOOT_TOSS is excluded from the freeze tally). */
    void onLootDropped(Level level, GameEvent.LootDropped m) {
        if (m.item() == null || m.from() == null || m.to() == null) return;
        stage.add(Effect.lootToss(m.from(), m.to(), m.item()));
        if (sounds != null) sounds.playAt("sfx.world.loot.drop", level, m.to(), 0.6f);
    }

    /** Frames between successive scroll item-create showcases (rapid succession). */
    private static final int SCROLL_SHOWCASE_STAGGER = 16;
    /** Per-consume stagger index for scroll create showcases (reset in consume). */
    private int itemShowcaseCount = 0;

    /** Item conjured onto the floor by a creation scroll - glow + spark birth
     *  burst behind the item sprite at its drop tile. Scroll creations also get a
     *  centre-screen glow showcase of the item (staggered for several at once). */
    void onItemCreated(Level level, GameEvent.ItemCreated m) {
        if (m.item() == null || m.at() == null) return;
        stage.add(Effect.itemBirth(m.at(), m.item(), Effect.EffectTint.YELLOW, RNG));
        if (sounds != null) sounds.playAt("sfx.item.create", level, m.at());
        if (m.showcase()) {
            Effect show = Effect.enchantShowcase(m.item(), Effect.EffectTint.YELLOW, RNG);
            show.startDelay = itemShowcaseCount * SCROLL_SHOWCASE_STAGGER;
            stage.add(show);
            itemShowcaseCount++;
        }
    }

    /** Item picked up by a mob - arc the item off its tile toward the bottom-
     *  right of the screen so it reads as flying into the inventory.
     *  Non-blocking. Skipped when the picker isn't visible. */
    void onItemPickedUp(Level level, GameEvent.ItemPickedUp m) {
        if (m.item() == null || m.from() == null) return;
        stage.add(Effect.pickupToss(m.from(), m.item()));
        if (m.picker() != null && m.picker().isPlayer) playerPickedUpSignal = true;
        if (sounds != null && m.picker() != null) {
            if (m.picker().isPlayer) {
                Item it = m.item();
                String pickupKey = (it != null && it.inventoryCategory == Item.InventoryCategory.GEM) ? "sfx.player.pickup.gem"
                        : (it != null && it.type != null) ? "sfx.player.pickup." + it.type.toLowerCase()
                        : "sfx.player.pickup";
                sounds.playAt(pickupKey, level, m.from());
            } else {
                sounds.playAt("sfx.mob.action.pickup", level, m.from());
            }
        }
        // First-encounter tip: when the PLAYER picks up an item type for
        // the first time this run, queue its tip. TipSystem.maybeShow
        // dedupes by key so repeat pickups are silent.
        if (m.picker() != null && m.picker().isPlayer
                && m.item() != null && m.item().type != null) {
            com.bjsp123.rl2.ui.v2.TipSystem.maybeShow(
                    "item:" + m.item().type,
                    "item." + m.item().type + ".tip",
                    "item." + m.item().type + ".name",
                    com.bjsp123.rl2.world.render.ItemSprites.regionFor(m.item()));
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
        // First-encounter tip: the player has now witnessed a knockback,
        // so it's a good time to explain the slam damage + cascade rules.
        // Fires whether the player is the slammer, the slamee, or just a
        // bystander watching mobs collide.
        com.bjsp123.rl2.ui.v2.TipSystem.maybeShow(
                "concept:knockback", "concept.knockback.tip",
                "concept.knockback.name", null);
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
        if (sounds != null) sounds.playAt("sfx.combat.knockback", level, end);
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
        if (sounds != null && mob.isPlayer) sounds.playAt(itemUseKey(m.item()), level, from);
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

    /** Phase-dodge: a quick smooth slide from {@code from} to {@code to}, rendered with the
     *  phasing shimmer (via {@link MobAnimState#phaseDodgeFrames}). No dust - it's a phase,
     *  not a hop. The gameplay model already moved the mob; this is visual only. */
    void onMobPhaseDodged(Level level, GameEvent.MobPhaseDodged m) {
        Mob mob = m.mob();
        if (mob == null) return;
        Point from = m.from(), to = m.to();
        if (from == null || to == null) return;
        boolean visible = MobSystem.isVisibleToPlayer(level, mob)
                || visibleAt(level, from) || visibleAt(level, to);
        if (!visible) return;
        if (sounds != null) sounds.playAt("sfx.mob.action.phasedodge", level, to);
        int ddx = from.tileX() - to.tileX();
        int ddy = from.tileY() - to.tileY();
        int dist = Math.max(Math.abs(ddx), Math.abs(ddy));
        if (dist == 0) return;
        // Zip: roughly twice as fast as a normal step so the dodge reads as a
        // sharp phase-blink rather than a leisurely slide.
        int frames = scaleFrames(Math.max(AnimationVars.STEP_FRAMES_MIN, stepFramesFor(mob) * dist));
        frames = Math.max(scaleFrames(2), frames / 2);
        MobAnimState s = stateOf(mob);
        s.stepFromDx = (float) ddx;
        s.stepFromDy = (float) ddy;
        s.stepFrame  = 0;
        s.stepTotal  = frames;
        // Longer shimmer afterglow so the phasing look lingers past the zip.
        s.phaseDodgeFrames = frames + scaleFrames(16);
        // Punctuate the phase with a quick shimmer burst at the departure tile
        // (pale blue "phase-out") and the arrival tile (white "phase-in"), so
        // the dodge reads as a hard blink between two points.
        stage.add(com.bjsp123.rl2.world.render.Effect.particleBurst(
                from, com.bjsp123.rl2.world.render.Effect.EffectTint.CYAN, 12, RNG));
        stage.add(com.bjsp123.rl2.world.render.Effect.particleBurst(
                to, com.bjsp123.rl2.world.render.Effect.EffectTint.WHITE, 12, RNG));
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
    void onMobFellThroughChasm(Level level, GameEvent.MobFellThroughChasm m) {
        if (m.mob() == null || m.fromTile() == null) return;
        stage.add(Effect.fallingMob(m.fromTile(), m.mob()));
        if (sounds != null) sounds.playAt("sfx.world.chasm.fall", level, m.fromTile(), 0.7f);
        // A surviving player has already been relocated a level down - signal
        // PlayScreen to play the cloud level-transition cinematic.
        if (m.mob().isPlayer && m.mob().hp > 0) playerFellSignal = true;
    }

    /** Latched true for one consume() when the player survives a chasm fall to
     *  the next level, so PlayScreen can play the level-transition cinematic. */
    public boolean playerFellSignal = false;

    /** Latched true for one consume() when the player picks up an item, so
     *  PlayScreen can flash the HUD portrait's happy expression. */
    public boolean playerPickedUpSignal = false;

    /** Item fell into a chasm - revolve-shrink-fade at its tile. Non-blocking. */
    void onItemFallingIntoChasm(Level level, GameEvent.ItemFallingIntoChasm m) {
        if (m.item() == null || m.position() == null) return;
        stage.add(Effect.fallingItem(m.position(), m.item()));
        if (sounds != null) sounds.playAt("sfx.world.chasm.itemfall", level, m.position(), 0.5f);
    }

    void onDamageDealt(Level level, GameEvent.DamageDealt m) {
        Mob target = m.target();
        if (target == null || target.position == null) return;
        if (!MobSystem.isVisibleToPlayer(level, target)) return;
        // Low-HP hit warning: when the player takes any damaging blow and their
        // POST-hit HP is at or below 20% of max, fire the HUD's hit-flash
        // hook. Threshold is a snapshot - a blow that drops them through the
        // line trips it; a blow taken while already below it also trips.
        if (target.isPlayer && m.amount() > 0
                && onPlayerLowHpHit != null) {
            double maxHp = target.effectiveStats().maxHp;
            if (maxHp > 0
                    && target.hp <= maxHp * com.bjsp123.rl2.logic.GameBalance.LOW_HP_HIT_FLASH_THRESHOLD) {
                onPlayerLowHpHit.run();
            }
        }
        switch (m.message()) {
            case HIT   -> {
                // Element-aware floater: PHYSICAL keeps the plain red "-N"
                // contract; the four mitigated elements (MAGIC/FIRE/POISON/SHOCK)
                // get a coloured number with a buff-icon glyph to its left.
                // PHYSICAL knockback wall-slam and chasm fall get their own
                // buff-icon glyphs (slots 24 / 25) so the player can tell
                // a wall-slam apart from a sword swing at a glance.
                MobSystem.DamageElement el = m.element();
                String medium = m.cause() != null ? m.cause().medium() : null;
                if (el == MobSystem.DamageElement.PHYSICAL && "wall-slam".equals(medium)) {
                    stage.add(Effect.damageFloaterPhysical(target.position, m.amount(),
                            /*iconAtlasIndex=*/24));
                } else if (el == MobSystem.DamageElement.PHYSICAL && "fall".equals(medium)) {
                    stage.add(Effect.damageFloaterPhysical(target.position, m.amount(),
                            /*iconAtlasIndex=*/25));
                } else if (el == null || el == MobSystem.DamageElement.PHYSICAL) {
                    stage.add(Effect.floatingText(target.position,
                            "-" + m.amount(), Effect.EffectTint.RED));
                } else {
                    stage.add(Effect.damageFloater(target.position, m.amount(), el));
                }
                if (sounds != null) sounds.playAt(target.isPlayer
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
        if (sounds != null) sounds.playAt(mob.isPlayer ? "sfx.player.heal" : "sfx.mob.heal",
                level, mob.position);
    }

    void onMobTamed(Level level, GameEvent.MobTamed m) {
        Mob mob = m.mob();
        if (mob == null || mob.position == null) return;
        if (!MobSystem.isVisibleToPlayer(level, mob)) return;
        stage.add(Effect.floatingText(mob.position,
                TextCatalog.get("effect.mob.tamed"), Effect.EffectTint.GREEN));
        if (sounds != null) sounds.playAt("sfx.mob.action.tame", level, mob.position);
    }

    /** Buff just expired (natural decrement to zero). Renders a brief
     *  "buff fade" floater above the mob: the dying buff's icon side-by-
     *  side with the CANCELLED glyph at atlas slot 26. Silent for cooldown
     *  buffs (already filtered upstream in
     *  {@link com.bjsp123.rl2.logic.BuffSystem#tickPerTurn} so the
     *  rolling log doesn't fill with "no longer on teleport cooldown"
     *  lines). Off-screen expiries are suppressed. */
    void onBuffRemoved(Level level, GameEvent.BuffRemoved m) {
        Mob mob = m.mob();
        if (mob == null || mob.position == null) return;
        if (!MobSystem.isVisibleToPlayer(level, mob)) return;
        stage.add(Effect.buffExpiredIcon(mob.position, m.type()));
        // Per-type key (e.g. sfx.buff.remove.on_fire = extinguish), falling back
        // to the generic sfx.buff.remove. Empty in csv = silent.
        if (sounds != null && m.type() != null)
            sounds.playAt("sfx.buff.remove." + m.type().name().toLowerCase(), level, mob.position);
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
        // Per-type key (e.g. sfx.buff.apply.on_fire = ignite, .frozen = freeze,
        // .poisoned = poison), falling back to the generic sfx.buff.apply.
        if (sounds != null && m.type() != null)
            sounds.playAt("sfx.buff.apply." + m.type().name().toLowerCase(), level, mob.position);
        // First-encounter tip: buffs trigger their tip the first time the
        // player is on the receiving end. Other-mob buff applications stay
        // silent (would spam the popup in busy rooms).
        if (mob.isPlayer && m.type() != null) {
            com.bjsp123.rl2.ui.v2.TipSystem.maybeShow(
                    "buff:" + m.type().name(),
                    "buff." + m.type().name() + ".tip",
                    "buff." + m.type().name() + ".name",
                    com.bjsp123.rl2.world.render.BuffIcons.regionFor(m.type()));
        }
    }

    void onWandImpactBurst(Level level, GameEvent.WandImpactBurst m) {
        // Powerup pickups get a custom multi-effect composite instead of
        // the standard 18-spark burst.
        switch (m.element()) {
            case LEVEL_UP -> { spawnLevelUpVisual(level, m.pos()); return; }
            case HP_UP    -> {
                if (sounds != null) sounds.playAt("sfx.player.pickup.healthpill", level, m.pos());
                com.bjsp123.rl2.ui.v2.TipSystem.maybeShow(
                        "concept:hp", "concept.hp.tip", "concept.hp.name", null);
                spawnHpUpVisual(level, m.pos()); return;
            }
            case MANA_UP  -> {
                if (sounds != null) sounds.playAt("sfx.player.pickup.chargepill", level, m.pos());
                com.bjsp123.rl2.ui.v2.TipSystem.maybeShow(
                        "concept:charges", "concept.charges.tip",
                        "concept.charges.name", null);
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

    /** LEVEL_UP / XP pickup composite - non-blocking. Purple particles + up-arrows
     *  rising and turning white, white border flash on the player for 0.5 s. */
    private void spawnLevelUpVisual(Level level, Point at) {
        spawnPowerupPickupVisual(level, at, Effect.EffectTint.MAUVE);
    }

    /** HP_UP (health) pickup composite - non-blocking. Green particles + up-arrows
     *  rising to white, player border flash. */
    private void spawnHpUpVisual(Level level, Point at) {
        spawnPowerupPickupVisual(level, at, Effect.EffectTint.GREEN);
    }

    /** MANA_UP (charges) pickup composite - non-blocking. Cyan particles + up-arrows
     *  rising to white, player border flash. */
    private void spawnManaUpVisual(Level level, Point at) {
        spawnPowerupPickupVisual(level, at, Effect.EffectTint.CYAN);
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
        if (sounds != null) sounds.playAt("sfx.world.surface." + m.surface().name().toLowerCase(),
                level, m.pos(), 0.6f);
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
        if (sounds != null) sounds.playAt("sfx.world.vegetation." + m.vegetation().name().toLowerCase(),
                level, m.pos(), 0.6f);
    }

    /** A mushroom puffed a spore cloud - spatial "puff" sfx. Visual-only event
     *  (the spore cloud itself is polled from {@code level.cloud}); the
     *  per-key cooldown collapses a whole room of mushrooms firing on one turn
     *  into a single puff. */
    void onSporeEmitted(Level level, GameEvent.SporeEmitted m) {
        if (m.pos() == null) return;
        if (sounds != null) sounds.playAt("sfx.world.spore", level, m.pos(), 0.6f);
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
        if (sounds != null) sounds.playAt("sfx.player.levelup", level, m.pos());
        int frames = 30;
        if (queue.freezeFrames < frames) queue.freezeFrames = frames;
    }

    /** Duration (frames) each level-up gain floater lingers, and the stagger
     *  between successive lines so they rise as a readable sequence. */
    private static final int LEVELUP_FLOATER_FRAMES  = 90;
    private static final int LEVELUP_FLOATER_STAGGER = 14;

    /** Staggered rising floaters spelling out a level-up's gains: "Level N",
     *  then "+Xhp", then "+1 perk", each starting a beat after the last so
     *  they read top-to-bottom like a rising column. Player-only, gated on
     *  the player's own visibility (always true in practice). */
    void onLevelUpGains(Level level, GameEvent.LevelUpGains m) {
        Mob mob = m.mob();
        if (mob == null || mob.position == null) return;
        if (!MobSystem.isVisibleToPlayer(level, mob)) return;
        Point p = mob.position;
        int delay = 0;
        stage.add(com.bjsp123.rl2.world.render.EffectBuilder.hoverText(
                p, TextCatalog.format("effect.levelup.level",
                        TextCatalog.vars("level", m.newLevel())),
                Effect.EffectTint.WHITE, delay, LEVELUP_FLOATER_FRAMES));
        delay += LEVELUP_FLOATER_STAGGER;
        if (m.hpGain() > 0) {
            stage.add(com.bjsp123.rl2.world.render.EffectBuilder.hoverText(
                    p, TextCatalog.format("effect.levelup.hp",
                            TextCatalog.vars("n", m.hpGain())),
                    Effect.EffectTint.GREEN, delay, LEVELUP_FLOATER_FRAMES));
            delay += LEVELUP_FLOATER_STAGGER;
        }
        if (m.perkGain() > 0) {
            stage.add(com.bjsp123.rl2.world.render.EffectBuilder.hoverText(
                    p, TextCatalog.format("effect.levelup.perk",
                            TextCatalog.vars("n", m.perkGain())),
                    Effect.EffectTint.YELLOW, delay, LEVELUP_FLOATER_FRAMES));
        }
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
        // DOT tick (e.g. sfx.buff.dot.on_fire, .poisoned). Quiet + cooldown-gated
        // since it fires every turn the buff persists.
        if (sounds != null && m.buff() != null)
            sounds.playAt("sfx.buff.dot." + m.buff().name().toLowerCase(), level, mob.position, 0.5f);
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

    // -- Real-time particle drains --------------------------------------------------

    private void tickTeleportFades(Level level, int dtMs) {
        if (level.mobs == null) return;
        for (Mob m : level.mobs) {
            MobAnimState s = states.get(m);
            if (s == null || s.teleportFadeMs <= 0) continue;
            int before = s.teleportFadeMs;
            int after  = before - dtMs;
            int half   = MobMovement.TELEPORT_FADE_HALF_MS;
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

    private static final int BUFF_PARTICLE_MIN_MS   = 240;
    private static final int BUFF_PARTICLE_RANGE_MS = 200;

    /** Per-mob emission of {@link BuffVisuals.Category#DRIFT} buff particles (RL-44):
     *  regeneration arrows, poison/chill/oil/wet/bleed drops, etc. One shared cadence;
     *  each firing emits one particle per active drift buff. Visible mobs only. */
    private void tickBuffParticles(Level level, int dtMs) {
        if (level.mobs == null) return;
        for (Mob m : level.mobs) {
            MobAnimState s = states.get(m);
            boolean active = m.hp > 0 && m.position != null
                    && com.bjsp123.rl2.world.render.BuffVisuals.has(
                            m, com.bjsp123.rl2.world.render.BuffVisuals.Category.DRIFT);
            if (!active) {
                if (s != null) s.buffParticleCountdownMs = 0;
                continue;
            }
            if (!MobSystem.isVisibleToPlayer(level, m)) continue;
            if (s == null) s = stateOf(m);
            s.buffParticleCountdownMs -= dtMs;
            while (s.buffParticleCountdownMs <= 0) {
                emitBuffParticles(m, s);
                s.buffParticleCountdownMs += BUFF_PARTICLE_MIN_MS + RNG.nextInt(BUFF_PARTICLE_RANGE_MS);
            }
        }
    }

    /** Emit one particle per active drift buff at {@code mob}'s current visual tile. */
    private void emitBuffParticles(Mob mob, MobAnimState s) {
        float vtx = mob.position.tileX(), vty = mob.position.tileY();
        if (s.stepTotal > 0) {
            float t = Math.min(1f, s.stepFrame / (float) s.stepTotal);
            vtx += s.stepFromDx * (1f - t);
            vty += s.stepFromDy * (1f - t);
        }
        com.bjsp123.rl2.model.Point at =
                new com.bjsp123.rl2.model.Point(Math.round(vtx), Math.round(vty));
        if (mob.buffs == null) return;
        for (com.bjsp123.rl2.model.Buff b : mob.buffs) {
            com.bjsp123.rl2.world.render.BuffVisuals.V v =
                    com.bjsp123.rl2.world.render.BuffVisuals.of(b.type);
            if (v.category != com.bjsp123.rl2.world.render.BuffVisuals.Category.DRIFT) continue;
            switch (v.drift) {
                case ARROW_UP -> stage.add(Effect.upArrow(at, v.particleTint, 0));
                case BURST    -> stage.add(Effect.particleBurst(at, v.particleTint, 4, RNG));
                case DROPS    -> stage.add(Effect.buffDrip(at, v.particleTint, RNG));
                case BUBBLES  -> stage.add(Effect.poisonBubbles(at, v.particleTint, RNG));
                default       -> { }
            }
        }
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
        return Effect.magicMissile(from, to, palette, head, gravity, size, bright, RNG);
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
            case GEM    -> "sfx.item.use.gem"    + type;
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

    /** Base rate the authored frame counts were tuned for: one logical animation
     *  frame is 1/60 s. */
    private static final float BASE_FPS = 60f;

    /** Logical animation frames to advance this render, given the real elapsed
     *  {@code dtMs}. Scales the authored-frame durations to wall-clock time so
     *  animations play at the same speed at any display refresh rate, while the
     *  animation-speed multiplier (0.5/1/2/4x) still compresses them. Used by
     *  both {@link #tick} (the anim accumulator) and PlayScreen's freeze-gate
     *  drain so the two stay in lock-step. */
    public static float frameDelta(int dtMs) {
        return framesPerRender() * dtMs * (BASE_FPS / 1000f);
    }

    /** Residual fraction (0..1) toward the next not-yet-applied logical frame,
     *  left over after {@link #tick} floors the accumulator. The renderer adds
     *  this to interpolation numerators (step slide, lunge/flinch, spawn-grow,
     *  ghost fade) so motion is smooth at frame rates above 60 Hz instead of
     *  snapping a logical frame at a time. */
    public float subFrame() { return frameProgress; }
}
