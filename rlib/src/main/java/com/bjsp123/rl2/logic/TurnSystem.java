package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Mob.Behavior;
import com.bjsp123.rl2.model.Point;

/**
 * Game-clock driver. The single entry point is {@link #tick(Level)}: each call advances
 * the clock by one game tick, runs any AI mob whose {@link Mob#ticksTillMove} just hit
 * zero, and on every {@link #STANDARD_TURN_TICKS}th call fires the per-turn handlers
 * (heal, satiety, fire damage, vegetation spread, ...). One tick is the same currency
 * used everywhere else ({@link Mob#moveCost}, {@link GameBalance#STARVATION_TICKS_PER_HP}, ...).
 *
 * <p>Three cadences interact:
 * <ul>
 *   <li><b>Game ticks</b> — {@link #tick} drains one tick per call and dispatches AI.</li>
 *   <li><b>Game turns</b> — every {@link #STANDARD_TURN_TICKS}th tick, {@link #tick}
 *       runs {@link #tickStandardTurn}.</li>
 *   <li><b>Real time</b> — {@link #advanceEffects} and {@link MobSystem#advanceMobAnimations}
 *       run once per render frame, independent of the game clock.</li>
 * </ul>
 */
public class TurnSystem {

    /** Game ticks in one "standard turn" — the cadence on which discrete per-turn rolls
     *  (vegetation spread, mushroom decay, fire spread, smoke emit, …) all fire from
     *  {@link #tickStandardTurn}. Independent of any individual mob's {@link Mob#moveCost}
     *  so a fast or slow player doesn't accelerate world-state churn. */
    public static final int STANDARD_TURN_TICKS = 100;

    /**
     * Advance the world by exactly one game tick:
     * <ol>
     *   <li>If the player is already ready ({@code ticksTillMove == 0}), return {@code false}
     *       without advancing — the caller should wait for input.</li>
     *   <li>Drain one tick from every non-INANIMATE, non-dying mob's
     *       {@link Mob#ticksTillMove} (clamped at zero).</li>
     *   <li>Run {@link MobSystem#processAllAiTurns} so any AI mob now at zero acts.</li>
     *   <li>Every {@link #STANDARD_TURN_TICKS}th call, fire {@link #tickStandardTurn}.</li>
     * </ol>
     * Callers maintaining a monotonic world-tick counter add 1 when this returns true.
     */
    public static boolean tick(Level level) {
        if (isPlayerTurn(level)) return false;

        for (Mob mob : level.mobs) {
            if (mob.behavior == Behavior.INANIMATE) continue;
            if (mob.ticksTillMove > 0) mob.ticksTillMove--;
        }

        MobSystem.processAllAiTurns(level);

        level.standardTurnTickAcc++;
        if (level.standardTurnTickAcc >= STANDARD_TURN_TICKS) {
            level.standardTurnTickAcc = 0;
            tickStandardTurn(level);
        }
        return true;
    }

    /**
     * Single dispatch point for everything that fires once per standard turn (every
     * {@link #STANDARD_TURN_TICKS} ticks of game time): per-mob drains (heal, satiety,
     * oily coat, fire damage), per-tile drains (fire lifetime), and per-cell rolls
     * (vegetation spread, mushroom decay, fire spread, smoke emission). New "once per
     * turn" effects should be plugged in here rather than tied to any particular actor's
     * cadence so they keep a stable game-time rate independent of player or mob speed.
     */
    private static final java.util.Random TURN_SPAWN_RNG = new java.util.Random();

    private static void tickStandardTurn(Level level) {
        // Per-turn spawn (e.g. ant hills budding off ants). Runs for INANIMATE mobs too,
        // since those are exactly the things that spawn-but-don't-act. Keep the mobs list
        // snapshot stable while rolling — newly-spawned mobs append to level.mobs and we
        // don't want them rolling their own spawn on the same turn they were created.
        int rollUpTo = level.mobs.size();
        for (int i = 0; i < rollUpTo; i++) {
            Mob mob = level.mobs.get(i);
            double chance = mob.effectiveStats().turnSpawnChance;
            if (chance <= 0 || mob.turnSpawnType == null) continue;
            if (TURN_SPAWN_RNG.nextDouble() >= chance) continue;
            // Per-type cap (so an anthill stops budding when its species fills the
            // level) AND the global cap (a level full of other mobs blocks spawn
            // too). Both must pass before we even look for a free tile.
            if (MobSystem.countMobsOfType(level, mob.turnSpawnType)
                    >= GameBalance.MAX_MOBS_FROM_SPAWNER) continue;
            if (!MobSystem.levelHasRoomForSpawn(level)) continue;
            Point spawnPos = MobHooks.freeAdjacentFloor(level, mob.position);
            if (spawnPos == null) continue;
            Mob bud = MobFactory.spawn(mob.turnSpawnType, spawnPos);
            if (bud == null) continue;
            level.mobs.add(bud);
            MobHooks.onSpawn(level, bud);
        }
        // Snapshot the mob list — advanceSatiety can starve the player (which routes
        // through processAttack → killMob → level.mobs.remove now), so iterating the
        // live list throws ConcurrentModificationException.
        java.util.List<Mob> heartbeat = new java.util.ArrayList<>(level.mobs);
        for (Mob mob : heartbeat) {
            if (mob.behavior == Behavior.INANIMATE) continue;
            // Heal: healRate is HP-per-tick, so one standard turn restores
            // STANDARD_TURN_TICKS * healRate. Suppressed while STARVING so the player
            // who's run out of food can't passively recover. POISONED would suppress it
            // too if/when that buff lands its drain.
            com.bjsp123.rl2.model.StatBlock s = mob.effectiveStats();
            boolean canHeal = s.healRate > 0 && mob.hp > 0
                    && !BuffSystem.hasBuff(mob, com.bjsp123.rl2.model.Buff.BuffType.STARVING);
            if (canHeal) {
                mob.hp = Math.min(s.maxHp,
                        mob.hp + STANDARD_TURN_TICKS * s.healRate);
            }
            // Satiety drains by one full turn's worth of ticks (and may cascade into
            // starvation HP loss for the player — see advanceSatiety).
            advanceSatiety(level, mob, STANDARD_TURN_TICKS);
            // Teleport cadence — once per standard turn, fires when no cooldown buff
            // is active AND the player is in line of sight; the cooldown is re-applied
            // either way as a TELEPORT_COOLDOWN buff with duration = teleportRate.
            int tRate = mob.effectiveStats().teleportRate;
            if (tRate > 0
                    && !BuffSystem.hasBuff(mob, com.bjsp123.rl2.model.Buff.BuffType.TELEPORT_COOLDOWN)) {
                MobSystem.tryTeleportToPlayer(level, mob);
                BuffSystem.apply(level, mob,
                        com.bjsp123.rl2.model.Buff.BuffType.TELEPORT_COOLDOWN,
                        /*level=*/1, tRate, mob);
            }
        }
        // Fire lifetime decay + on-fire-mob damage. Both live on the standard-turn cadence
        // (fire damage interval = standard turn, fire lifetime measured in ticks but only
        // decayed at this beat).
        FireSystem.tickGameTicks(level, STANDARD_TURN_TICKS);
        // Per-cell stochastic rolls — spread, smoke, mob/tile propagation; vegetation
        // spread + mushroom decay.
        FireSystem.tickPerTurn(level);
        VegetationSystem.tickPerTurn(level);
        // Buff system runs after vegetation/fire so fresh ON_FIRE buffs applied this
        // turn (mob steps onto a fire tile during its move, FireSystem.tickPerTurn
        // applies the buff) immediately deal damage on the same turn instead of waiting
        // a turn to land.
        BuffSystem.tickPerTurn(level);
    }

    /**
     * Count down the mob's {@code satiety} by {@code gameTicks} <b>game ticks</b>.
     * Non-players are unaffected once satiety hits 0 — the counter just sits there. The
     * player, once starving, accumulates ticks and loses 1 HP for every
     * {@link GameBalance#STARVATION_TICKS_PER_HP} ticks elapsed; the death is credited to
     * no one (environmental damage, no XP).
     */
    private static void advanceSatiety(Level level, Mob mob, int gameTicks) {
        if (mob.satiety > 0) {
            int drop = Math.min(mob.satiety, gameTicks);
            mob.satiety -= drop;
            // Eating something between turns clears the starving buff immediately.
            if (mob.satiety > 0
                    && BuffSystem.hasBuff(mob, com.bjsp123.rl2.model.Buff.BuffType.STARVING)) {
                BuffSystem.removeBuff(mob, com.bjsp123.rl2.model.Buff.BuffType.STARVING);
            }
            if (mob.satiety == 0 && mob.behavior == Behavior.PLAYER) {
                String name = mob.name != null ? mob.name : "Adventurer";
                EventLog.add(Messages.playerStarves(name));
            }
        }
        if (mob.satiety > 0) return;
        // Hit zero — apply STARVING (player only). NPCs sit at zero harmlessly.
        // STARVING blocks heal regen but doesn't drain HP; it stays on until satiety
        // ticks back up. Re-apply each turn so it doesn't expire under the per-turn
        // duration drain in BuffSystem.tickPerTurn.
        if (mob.behavior == Behavior.PLAYER) {
            BuffSystem.apply(level, mob, com.bjsp123.rl2.model.Buff.BuffType.STARVING,
                    1, 999_999, mob);
        }
    }

    public static boolean isPlayerTurn(Level level) {
        for (Mob mob : level.mobs) {
            if (mob.behavior == Behavior.PLAYER && mob.ticksTillMove == 0) return true;
        }
        return false;
    }

    public static Mob getActivePlayer(Level level) {
        for (Mob mob : level.mobs) {
            if (mob.behavior == Behavior.PLAYER && mob.ticksTillMove == 0) return mob;
        }
        return null;
    }

    /** Find the player mob regardless of ticksTillMove. */
    public static Mob findPlayer(Level level) {
        for (Mob mob : level.mobs) {
            if (mob.behavior == Behavior.PLAYER) return mob;
        }
        return null;
    }

    public static void applyMoveCost(Mob mob, int cost) {
        // CHILLED adds a flat penalty to every action cost. The penalty applies once
        // per applyMoveCost call regardless of cost magnitude, so a chilled mob's
        // every step / swing / ranged shot is slowed by the same amount.
        cost += BuffSystem.chilledCostPenalty(mob);
        // HASTED + KILLER multiplicatively reduce action cost.
        double scaled = cost * BuffSystem.actionCostMultiplier(mob);
        mob.ticksTillMove += Math.max(1, (int) Math.round(scaled));
    }

    // Effect-frame advancement moved to rgame.render.EffectStage; freeze scheduling
    // moved to rgame.anim.AnimQueue. rlib has no concept of render frames or visual
    // effects any more.
}
