package com.bjsp123.rl2.event;

import com.bjsp123.rl2.model.Buff;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
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
        GameEvent.PotionBurst,
        GameEvent.MobSpawned,
        GameEvent.SurfaceChanged,
        GameEvent.VegetationChanged,
        GameEvent.RainbowBurst,
        GameEvent.PeriodicBuffDamage,
        GameEvent.LootDropped,
        GameEvent.ItemPickedUp,
        GameEvent.MobKnockedBack,
        GameEvent.ItemFallingIntoChasm,
        GameEvent.MobFellThroughChasm,
        GameEvent.GrappleFired,
        GameEvent.MobAbilityUsed {

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
     *  + gravity + brightness from the element. The {@code wand} reference is
     *  carried so the impact site can read per-item scaling columns
     *  ({@code damage}, {@code damagePerLevel}, {@code tilesAffected}, etc.).
     *  {@code effectiveLevel} is the level used for scaling — typically
     *  {@code wand.level} but bumped by +1 when the caster has the WANDMASTER
     *  perk, captured at fire time so the impact callback sees the right
     *  number even though the perk lives on the player. */
    record WandMissileFired(Mob caster, Point from, Point to, Item.ItemEffect element,
                            Item wand, int effectiveLevel,
                            boolean trajectoryVisible) implements GameEvent {}

    /** Wand-cast straight beam (currently only banishment). */
    record WandRayFired(Mob caster, Point from, Point to, Item.ItemEffect element,
                        Item wand, int effectiveLevel,
                        boolean trajectoryVisible) implements GameEvent {}

    /** Mob threw an item at a target tile. */
    record ItemThrown(Mob thrower, Item item, Point from, Point to,
                      boolean trajectoryVisible) implements GameEvent {}

    /** Per-hit damage record (renders floating "5"/"miss"/"blunt" text near {@code target}).
     *  {@code message} is the renderer-side flavor of the floater; the underlying
     *  damage element ({@link com.bjsp123.rl2.logic.MobSystem.DamageElement}) is not
     *  carried in this event because the renderer keys off the floater style alone. */
    record DamageDealt(Mob target, int amount, DamageMessage message, Mob source) implements GameEvent {}
    enum DamageMessage { HIT, MISS, BLUNT, ENVIRONMENTAL }

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
    record WandImpactBurst(Point pos, Item.ItemEffect element) implements GameEvent {}

    /** Particle burst at a potion-drink or potion-throw landing tile. The
     *  renderer maps the {@link Item}'s {@code appliesBuff} (and falls back
     *  to {@code type}) to the swirling-rising particle palette. */
    record PotionBurst(Point pos, Item item) implements GameEvent {}

    /** A new mob has been spawned into the live level (wand-of-dog summon,
     *  ant-hill turn-spawn, mob-eats-vegetation spawn). The Animator plays
     *  a blocking grow-from-zero animation on the mob with rising particles
     *  if the spawn tile is currently visible to the player. */
    record MobSpawned(Mob mob, Point at) implements GameEvent {}

    /** A tile's surface layer just changed to {@code surface} (water/oil/blood
     *  /ice spreading from a wand or thrown bomb). Non-blocking; the renderer
     *  emits a small fountain of particles tinted to the surface element.
     *  Skipped when the new value matches the previous one (no actual change). */
    record SurfaceChanged(Point pos, Level.Surface surface) implements GameEvent {}

    /** A tile's vegetation layer just changed to {@code vegetation}
     *  (grass/mushrooms/fire/trees grew or were painted in). Non-blocking;
     *  the renderer emits a fountain of particles tinted to the vegetation
     *  kind. Skipped when the new value matches the previous one. */
    record VegetationChanged(Point pos, Level.Vegetation vegetation) implements GameEvent {}

    /** Multi-coloured radial particle burst at {@code pos}. Used for the
     *  power-orb absorb celebration and on character level-up. Blocking — the
     *  Animator parks the freeze gate for the burst's duration so the
     *  player notices the level-up before the next turn ticks. */
    record RainbowBurst(Point pos) implements GameEvent {}

    /** Per-turn HP loss from a buff DOT (fire, poison, …). The renderer picks the
     *  floating-text tint from {@code buff} (orange for fire, green for poison, …). */
    record PeriodicBuffDamage(Mob mob, Buff.BuffType buff, int amount) implements GameEvent {}

    /** A single item was dropped on death — the renderer plays a brief arcing toss
     *  from the dying mob's tile to the item's resting spot. The animation is
     *  non-blocking (runs in parallel with whatever animation comes next). */
    record LootDropped(Item item, Point from, Point to) implements GameEvent {}

    /** A single item was just picked up by a mob — the renderer plays a brief
     *  arc from the item's tile toward the bottom-right of the screen (where the
     *  inventory tab lives). Non-blocking. */
    record ItemPickedUp(Mob picker, Item item, Point from) implements GameEvent {}

    /** Mob was knocked back from {@code start} to {@code end}. The animator slides
     *  the mob sprite across the intervening tiles. Blocking. A burst is drawn at
     *  {@code start} to signal the impact. {@code blocked} is {@code true} when
     *  the slide stopped because of an obstacle (wall, edge, or another mob);
     *  {@code false} when the mob travelled the full distance unimpeded. */
    record MobKnockedBack(Mob mob, Point start, Point end, boolean blocked) implements GameEvent {}

    /** An item fell into a chasm after its owner was knocked in. The animator
     *  plays a revolve-shrink-fade on the item sprite at {@code position}.
     *  Non-blocking. */
    record ItemFallingIntoChasm(Item item, Point position) implements GameEvent {}

    /** Non-flying mob fell into a chasm. The animator plays a
     *  revolve-shrink-fade on the mob's sprite at {@code fromTile}. The
     *  engine has either applied half-max-HP fall damage (and possibly
     *  killed the mob), or moved the survivor to the next dungeon level
     *  via the source level's down-stairs link. The renderer doesn't
     *  need to know which — the sprite animation is the same. */
    record MobFellThroughChasm(Mob mob, Point fromTile) implements GameEvent {}

    /** Player (or future AI) fired a grappling rope from {@code from} to
     *  {@code target}. {@code success == true} → the rope retracts and the
     *  animator slides whatever was on the target tile back via a paired
     *  {@link MobKnockedBack} event the engine emitted alongside this one;
     *  {@code success == false} → the target was too heavy and the rope
     *  flashes / fades at full extent without pulling anything. Blocking
     *  during the rope's extend phase so the dragged-mob slide that
     *  follows lines up with the retract. */
    record GrappleFired(Mob caster, Point from, Point target,
                        boolean success) implements GameEvent {}

    /** Mob cast a non-projectile ability on another mob (buff, heal,
     *  or any other targeted special). The renderer paints a green ray
     *  from {@code from} to {@code to} plus a green-spark burst at the
     *  target so the cast reads visually even though no missile flies.
     *  Teleports have their own dedicated visual and don't fire this
     *  event. */
    record MobAbilityUsed(Mob caster, Mob target,
                          Point from, Point to) implements GameEvent {}
}
