package com.bjsp123.rl2.save;

import com.bjsp123.rl2.event.GameEvent;
import com.bjsp123.rl2.logic.MobDefinition;
import com.bjsp123.rl2.logic.MobRegistry;
import com.bjsp123.rl2.model.HallOfFameEntry;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.persistence.Persistence;

import java.util.function.Consumer;

/**
 * Central observer for achievement triggers. Owns the {@link Achievements}
 * unlock set; routes a small {@code observe*} surface into per-trigger
 * dispatch and persists on first unlock.
 *
 * <p>Lives entirely on the rgame side — every trigger comes from either
 * {@link GameEvent}s drained by the {@link com.bjsp123.rl2.world.anim.Animator}
 * or from PlayScreen / V2 popup hooks. rlib remains free of presentation
 * dependencies.
 *
 * <p>Adding a new achievement is one enum entry in {@link Achievement} plus
 * one branch in the matching {@code observe*} switch.
 */
public final class AchievementSystem {

    private final Achievements achievements;
    private final Persistence  persistence;
    private Consumer<Achievement> listener;

    public AchievementSystem(Achievements achievements, Persistence persistence) {
        this.achievements = achievements;
        this.persistence  = persistence;
    }

    /** Set the unlock listener. Fired synchronously from inside
     *  {@link #unlock(Achievement)} once per first-time unlock. PlayScreen
     *  uses this to drive the toast banner + log line. */
    public void setListener(Consumer<Achievement> onUnlock) {
        this.listener = onUnlock;
    }

    /** Clear the listener. Call from PlayScreen.dispose so the toast
     *  reference doesn't survive screen tear-down. */
    public void clearListener() {
        this.listener = null;
    }

    // ── Observation API ────────────────────────────────────────────────

    /** Polled per render frame from PlayScreen — once a depth threshold is
     *  passed the matching achievement unlocks; cheap no-op thereafter. */
    public void observeDepth(int depth) {
        if (depth >= 2) unlock(Achievement.BEGUN_THE_ADVENTURE);
        if (depth >= 5) unlock(Achievement.DUG_DEEPER);
        if (depth >  1) unlock(Achievement.INTO_THE_DEPTHS);
    }

    /** Forwarded from {@link com.bjsp123.rl2.world.anim.Animator#consume}.
     *  {@code player} may be {@code null} during cross-level transitions
     *  where the animator hasn't latched the new level's player yet — the
     *  switch short-circuits in those cases. */
    public void observeEvent(GameEvent ev, Mob player, Level level) {
        if (ev == null) return;
        if (ev instanceof GameEvent.MobKilled m) {
            if (player != null && m.killer() == player && m.mob() != null) {
                unlock(Achievement.FIRST_BLOOD);
                MobDefinition def = MobRegistry.get(m.mob().mobType);
                if (def != null && def.unique) {
                    unlock(Achievement.GIANT_SLAYER);
                }
            }
        } else if (ev instanceof GameEvent.WandMissileFired m) {
            if (player != null && m.caster() == player) {
                unlock(Achievement.WAND_NEWBIE);
            }
        } else if (ev instanceof GameEvent.WandRayFired m) {
            if (player != null && m.caster() == player) {
                unlock(Achievement.WAND_NEWBIE);
            }
        } else if (ev instanceof GameEvent.ItemThrown m) {
            if (player != null && m.thrower() == player) {
                unlock(Achievement.THROWN_AWAY);
            }
        } else if (ev instanceof GameEvent.RainbowBurst m) {
            // RainbowBurst is emitted by MobProgression on level-up at the
            // levelling mob's tile — match against the player's position to
            // distinguish player level-ups from enemy ones.
            if (player != null && player.position != null) {
                Point at = m.pos();
                if (at != null
                        && at.tileX() == player.position.tileX()
                        && at.tileY() == player.position.tileY()) {
                    unlock(Achievement.LEVELED_UP);
                }
            }
        }
    }

    /** Fired from PlayScreen after a HallOfFameEntry is recorded. */
    public void observeRunEnded(HallOfFameEntry entry) {
        if (entry == null) return;
        unlock(Achievement.HALL_OF_FAMER);
    }

    /** Fired from V2Crafting on a successful craft. */
    public void observeCrafted(Item result) {
        if (result == null) return;
        unlock(Achievement.ARTISAN);
    }

    // ── Internal ────────────────────────────────────────────────────────

    /** Unlock {@code a}. No-op when already on the books. Persists the
     *  store and fires the listener exactly once per first-time unlock. */
    private void unlock(Achievement a) {
        if (a == null || achievements == null) return;
        if (!achievements.unlock(a)) return;        // already unlocked
        AchievementsStore.save(persistence, achievements);
        if (listener != null) listener.accept(a);
    }
}
