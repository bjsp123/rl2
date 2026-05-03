package com.bjsp123.rl2.event;

import com.bjsp123.rl2.model.Buff;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;

/**
 * Engine-side notification of a game-state change for the rendering layer to animate.
 * Emitted by {@code rlib} systems (see {@code Level.events}); consumed once per
 * {@code TurnSystem.tick} by {@code rgame}'s animator.
 *
 * <p>Events are passive data records — there are no listeners and no callbacks. They
 * are appended in the order things happened during a tick and drained same-frame.
 * Carrying direct {@link Mob} / {@link Item} / {@link Point} references is safe because
 * consumption happens before the next tick mutates state.
 *
 * <p>Sub-types are flat records so the consumer can pattern-match in a single
 * {@code switch} expression. Keep payloads minimal — anything purely visual (frame
 * counts, palettes, particle counts) is the consumer's concern.
 */
public sealed interface GameEvent permits
        GameEvent.MobMoved,
        GameEvent.MobMeleeAttacked,
        GameEvent.MobHitFlinched,
        GameEvent.MobKilled,
        GameEvent.MobTeleported,
        GameEvent.MobIgnited,
        GameEvent.MobExtinguished,
        GameEvent.MobSlept,
        GameEvent.MobWoke,
        GameEvent.MagicMissileFired,
        GameEvent.WandMissileFired,
        GameEvent.WandRayFired,
        GameEvent.ItemThrown,
        GameEvent.DamageDealt,
        GameEvent.BuffApplied,
        GameEvent.BuffRemoved,
        GameEvent.BlastEffect,
        GameEvent.ExplosionEffect,
        GameEvent.LightMoteSpawn,
        GameEvent.HealApplied,
        GameEvent.MobTamed,
        GameEvent.WandImpactBurst,
        GameEvent.PeriodicBuffDamage {

    /** Mob took a single tile step. The animator translates this into a step interpolation. */
    record MobMoved(Mob mob, int fromX, int fromY, int toX, int toY) implements GameEvent {}

    /** Mob swung a melee attack. {@code hit} == false signals a miss; {@code dealt} is
     *  raw damage applied (post-armour). */
    record MobMeleeAttacked(Mob attacker, Mob target, boolean hit, int dealt) implements GameEvent {}

    /** Mob took a damaging hit and should flinch in the direction away from {@code hitSource}. */
    record MobHitFlinched(Mob victim, Mob hitSource) implements GameEvent {}

    /** Mob was killed. By the time this fires the mob has been removed from
     *  {@code level.mobs}; consumers play the death animation against this snapshot. */
    record MobKilled(Mob mob, Mob killer, int x, int y, boolean visibleAtKill) implements GameEvent {}

    /** Mob teleported between two tiles. The animator plays origin streaks → fade-out
     *  → fade-in at destination → arrival streaks. */
    record MobTeleported(Mob mob, int fromX, int fromY, int toX, int toY) implements GameEvent {}

    /** Mob became / stopped being on fire. Maintains the consumer's burning set so it
     *  knows which mobs to emit fire-particle effects for on its own real-time clock. */
    record MobIgnited(Mob mob)      implements GameEvent {}
    record MobExtinguished(Mob mob) implements GameEvent {}

    /** Mob fell asleep / woke up. Maintains the consumer's asleep set for sleep-Z
     *  particle cadence. */
    record MobSlept(Mob mob) implements GameEvent {}
    record MobWoke (Mob mob) implements GameEvent {}

    /** Plain magic-missile fired (caster's basic ranged attack, AI ranged shot, staff).
     *  Damage is applied logically when the visual completes — the consumer holds the
     *  pending impact and calls back into {@code rlib}. */
    record MagicMissileFired(Mob caster, Point from, Point to, int damage,
                             boolean trajectoryVisible) implements GameEvent {}

    /** Wand-cast missile carrying an element to apply at impact. Consumer chooses palette
     *  + gravity + brightness from the element. */
    record WandMissileFired(Mob caster, Point from, Point to, Item.WandElement element,
                            int wandLevel, boolean trajectoryVisible) implements GameEvent {}

    /** Wand-cast straight beam (currently only banishment). */
    record WandRayFired(Mob caster, Point from, Point to, Item.WandElement element,
                        int wandLevel, boolean trajectoryVisible) implements GameEvent {}

    /** Mob threw an item at a target tile. */
    record ItemThrown(Mob thrower, Item item, Point from, Point to,
                      boolean trajectoryVisible) implements GameEvent {}

    /** Per-hit damage record (renders floating "5"/"miss"/"blunt" text near {@code target}). */
    record DamageDealt(Mob target, int amount, DamageKind kind, Mob source) implements GameEvent {}
    enum DamageKind { HIT, MISS, BLUNT, ENVIRONMENTAL }

    /** Buff applied / removed (drives floating buff icon or text). */
    record BuffApplied(Mob mob, Buff.BuffType type, String displayName) implements GameEvent {}
    record BuffRemoved(Mob mob, Buff.BuffType type, String displayName) implements GameEvent {}

    /** One-tile concussive flash (blast bomb). */
    record BlastEffect(Point pos) implements GameEvent {}

    /** Radial fire-ball burst (on-death explosion, detonation). */
    record ExplosionEffect(Point pos, int radiusTiles) implements GameEvent {}

    /** Single faint mote drifting up from a light source. */
    record LightMoteSpawn(Point pos) implements GameEvent {}

    /** Mob recovered HP — drives the "+N" green heal-text floater. */
    record HealApplied(Mob mob, int amount) implements GameEvent {}

    /** Tame succeeded (thrown food on a feral pet, etc.) — drives the "tame!" floater. */
    record MobTamed(Mob mob) implements GameEvent {}

    /** Coloured particle burst at a wand impact tile. The renderer maps {@code element}
     *  to the corresponding palette (water → blue, fire → red, …). */
    record WandImpactBurst(Point pos, Item.WandElement element) implements GameEvent {}

    /** Per-turn HP loss from a buff DOT (fire, poison, …). The renderer picks the
     *  floating-text tint from {@code buff} (orange for fire, green for poison, …). */
    record PeriodicBuffDamage(Mob mob, Buff.BuffType buff, int amount) implements GameEvent {}
}
