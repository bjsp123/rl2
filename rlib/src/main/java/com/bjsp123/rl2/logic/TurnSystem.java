package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Mob.Behavior;
import com.bjsp123.rl2.model.Point;

/**
 * Game-clock driver. The single entry point is {@link #tick(Level)}: each call advances
 * the clock by one game tick, runs any AI mob whose {@link Mob#ticksTillMove} just hit
 * zero, and on every {@link #STANDARD_TURN_TICKS}th call fires the per-turn handlers
 * (heal, fire damage, vegetation spread, ...). One tick is the same currency used
 * everywhere else ({@link Mob#moveCost}, {@link #STANDARD_TURN_TICKS}, ...).
 *
 * <p>Two cadences interact here:
 * <ul>
 *   <li><b>Game ticks</b> - {@link #tick} drains one tick per call and dispatches AI.</li>
 *   <li><b>Game turns</b> - every {@link #STANDARD_TURN_TICKS}th tick, {@link #tick}
 *       runs {@link #tickStandardTurn}.</li>
 * </ul>
 * Real-time, per-render-frame effects (animations, FX) live in rgame, not here.
 */
public class TurnSystem {

    /** Game ticks in one "standard turn" - the cadence on which discrete per-turn rolls
     *  (vegetation spread, mushroom decay, fire spread, smoke emit, ...) all fire from
     *  {@link #tickStandardTurn}. Independent of any individual mob's {@link Mob#moveCost}
     *  so a fast or slow player doesn't accelerate world-state churn. */
    public static final int STANDARD_TURN_TICKS = 100;

    public static int standardTurnForTick(int tick) {
        return Math.max(0, tick) / STANDARD_TURN_TICKS;
    }

    public static int tickWithinStandardTurn(int tick) {
        return Math.floorMod(tick, STANDARD_TURN_TICKS);
    }

    /**
     * Advance the world by exactly one game tick:
     * <ol>
     *   <li>If the player is already ready ({@code ticksTillMove == 0}), return {@code false}
     *       without advancing - the caller should wait for input.</li>
     *   <li>Drain one tick from every non-INANIMATE, non-dying mob's
     *       {@link Mob#ticksTillMove} (clamped at zero).</li>
     *   <li>Run {@link MobSystem#processAllAiTurns} so any AI mob now at zero acts.</li>
     *   <li>Every {@link #STANDARD_TURN_TICKS}th call, fire {@link #tickStandardTurn}.</li>
     * </ol>
     * Callers maintaining a monotonic world-tick counter add 1 when this returns true.
     */
    public static boolean tick(Level level) {
        billFrozenReadyMobs(level);
        if (isPlayerTurn(level)) return false;

        for (Mob mob : level.mobs) {
            if (mob.behavior == Behavior.INANIMATE) continue;
            int before = mob.ticksTillMove;
            if (mob.ticksTillMove > 0) mob.ticksTillMove--;
            if (before > 0 && mob.ticksTillMove == 0) {
                MobCombat.snapshotVisibleMobsAtTurnStart(level, mob);
            }
        }

        // Buff stacks count down once per standard turn in BuffSystem.tickPerTurn
        // (driven from tickStandardTurn below) - there is no per-game-tick path.
        MobAi.processAllAiTurns(level);

        level.standardTurnTickAcc++;
        if (level.standardTurnTickAcc >= STANDARD_TURN_TICKS) {
            level.standardTurnTickAcc = 0;
            tickStandardTurn(level);
        }
        return true;
    }

    /**
     * Advance over idle game ticks in one pass, stopping at the next actor-ready tick or
     * standard-turn heartbeat. Returns the number of game ticks advanced, or 0 if the
     * player is already ready and the caller should wait for input.
     */
    public static int advanceToNextEvent(Level level) {
        return advanceToNextEvent(level, 0L);
    }

    /**
     * Budgeted form of {@link #advanceToNextEvent(Level)}. A return value of -1 means
     * ready AI made progress without advancing the world clock, usually because a prior
     * bulk advance left more same-tick AI work to finish on the next render frame.
     */
    public static int advanceToNextEvent(Level level, long deadlineNs) {
        billFrozenReadyMobs(level);
        if (isPlayerTurn(level)) return 0;

        boolean aiAlreadyReady = false;
        int delta = STANDARD_TURN_TICKS - level.standardTurnTickAcc;
        if (delta <= 0) delta = STANDARD_TURN_TICKS;

        for (Mob mob : level.mobs) {
            if (mob.behavior == Behavior.INANIMATE) continue;
            if (mob.ticksTillMove == 0) {
                if (mob.behavior != Behavior.PLAYER) aiAlreadyReady = true;
                continue;
            }
            delta = Math.min(delta, mob.ticksTillMove);
        }
        if (aiAlreadyReady) {
            MobAi.processAllAiTurns(level, deadlineNs);
            return -1;
        }
        if (delta <= 0) delta = 1;

        for (Mob mob : level.mobs) {
            if (mob.behavior == Behavior.INANIMATE) continue;
            int before = mob.ticksTillMove;
            if (mob.ticksTillMove > 0) mob.ticksTillMove = Math.max(0, mob.ticksTillMove - delta);
            if (before > 0 && mob.ticksTillMove == 0) {
                MobCombat.snapshotVisibleMobsAtTurnStart(level, mob);
            }
        }

        // Buff stacks decrement per standard turn inside tickStandardTurn (below),
        // so this bulk advance applies them via the same standard-turn boundary loop.
        level.standardTurnTickAcc += delta;
        while (level.standardTurnTickAcc >= STANDARD_TURN_TICKS) {
            level.standardTurnTickAcc -= STANDARD_TURN_TICKS;
            tickStandardTurn(level);
        }
        MobAi.processAllAiTurns(level, deadlineNs);
        return delta;
    }

    private static void billFrozenReadyMobs(Level level) {
        for (Mob mob : level.mobs) {
            if (mob.behavior == Behavior.INANIMATE || mob.ticksTillMove != 0) continue;
            if (!BuffSystem.hasBuff(mob, com.bjsp123.rl2.model.Buff.BuffType.FROZEN)) continue;
            MobCombat.snapshotVisibleMobsAtTurnStart(level, mob);
            applyMoveCost(mob, mob.effectiveStats().moveCost);
        }
    }

    /**
     * Single dispatch point for everything that fires once per standard turn (every
     * {@link #STANDARD_TURN_TICKS} ticks of game time): per-mob drains (heal, oily coat,
     * fire damage), per-tile drains (fire lifetime), and per-cell rolls
     * (vegetation spread, mushroom decay, fire spread, smoke emission). New "once per
     * turn" effects should be plugged in here rather than tied to any particular actor's
     * cadence so they keep a stable game-time rate independent of player or mob speed.
     */
    private static final java.util.Random TURN_SPAWN_RNG =
            com.bjsp123.rl2.util.SimRng.register("TurnSystem.spawn", new java.util.Random());

    private static void tickStandardTurn(Level level) {
        // Per-turn spawn (e.g. ant hills budding off ants). Runs for INANIMATE mobs too,
        // since those are exactly the things that spawn-but-don't-act. Keep the mobs list
        // snapshot stable while rolling - newly-spawned mobs append to level.mobs and we
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
            if (MobQueries.countMobsOfType(level, mob.turnSpawnType)
                    >= GameBalance.MAX_MOBS_FROM_SPAWNER) continue;
            if (!MobQueries.levelHasRoomForSpawn(level)) continue;
            Point spawnPos = MobHooks.freeAdjacentFloor(level, mob.position);
            if (spawnPos == null) continue;
            Mob bud = MobFactory.spawn(mob.turnSpawnType, spawnPos);
            if (bud == null) continue;
            level.mobs.add(bud);
            MobHooks.onSpawn(level, bud);
            if (level.events != null) {
                level.events.add(new com.bjsp123.rl2.event.GameEvent.MobSpawned(bud, spawnPos));
            }
        }
        // Snapshot the mob list - per-turn handlers can add or remove mobs, so
        // iterating the live list risks a ConcurrentModificationException.
        java.util.List<Mob> heartbeat = new java.util.ArrayList<>(level.mobs);
        for (Mob mob : heartbeat) {
            if (mob.behavior == Behavior.INANIMATE) continue;
            // Heal: healRate is HP-per-tick, so one standard turn restores
            // STANDARD_TURN_TICKS * healRate.
            com.bjsp123.rl2.model.StatBlock s = mob.effectiveStats();
            boolean canHeal = s.healRate > 0 && mob.hp > 0;
            if (canHeal) {
                mob.hp = Math.min(s.maxHp,
                        mob.hp + STANDARD_TURN_TICKS * s.healRate);
            }
            // INSIGHT: while the buff is active, re-stamp every tile as
            // explored so the player's map stays revealed for the duration.
            // Player-only - NPCs don't have a personal map.
            if (mob.isPlayer
                    && BuffSystem.hasBuff(mob, com.bjsp123.rl2.model.Buff.BuffType.INSIGHT)) {
                LevelSystem.markAllExplored(level);
            }
            // Teleport now lives on Mob.abilities (kind = TELEPORT) and dispatches
            // through {@code MobSystem.tryCastAbilities} on the mob's own AI turn,
            // so there's no per-turn special case to fire here.
        }
        // Fire lifetime decay + on-fire-mob damage. Both live on the standard-turn cadence
        // (fire damage interval = standard turn, fire lifetime measured in ticks but only
        // decayed at this beat).
        FireSystem.tickGameTicks(level, STANDARD_TURN_TICKS);
        // Per-cell stochastic rolls - spread, smoke, mob/tile propagation; vegetation
        // spread + mushroom decay.
        FireSystem.tickPerTurn(level);
        VegetationSystem.tickPerTurn(level);
        // Cloud layer ticks after fire/vegetation so the same turn's freshly-
        // ignited / freshly-watered cells feed into smoke + steam emission.
        // Cloud poison effects feed buffs that BuffSystem.tickPerTurn then
        // sees this same turn.
        CloudSystem.tickPerTurn(level);
        // Buff system runs after vegetation/fire so fresh ON_FIRE buffs applied this
        // turn (mob steps onto a fire tile during its move, FireSystem.tickPerTurn
        // applies the buff) immediately deal damage on the same turn instead of waiting
        // a turn to land.
        BuffSystem.tickPerTurn(level);
        // Generic data-driven level rules layered on top of the standard
        // pipeline. Each is a cheap no-op unless the level opts in via a flag
        // / config, so regular floors pay only a boolean (or null) check.
        level.turnsOnLevel++;
        tickHazardAndRenew(level);              // RL-54: hazard climb + renewing spawns
        LevelSystem.openExitIfCleared(level);   // no-op unless exitUnlocksOnClear
        MobLifecycle.runLevelSpawner(level);       // no-op unless level.spawner != null
    }

    /**
     * RL-54: recompute the floor's hazard level and, on the hazard-scaled
     * cadence, spawn a level-appropriate renewing enemy out of the player's
     * sight. Hazard = min(HAZARD_MAX, beaconLit + turnsOnLevel/HAZARD_TURNS_PER_POINT);
     * each point shortens the spawn cadence by 2, raises the enemy cap by 1, and
     * gives a 5% chance per point of an extra wraith.
     */
    private static void tickHazardAndRenew(Level level) {
        // The boss floor sets its hazard from the player's kills on arrival
        // (MobSystem.spawnFinalBoss) and uses it to drive the revenant spawner -
        // so don't recompute it from time here, and don't run the regular
        // renewing-enemy spawns on the bespoke arena.
        if (level.kind == com.bjsp123.rl2.model.Level.LevelKind.FINAL_BOSS) return;
        level.hazardLevel = Math.min(GameBalance.HAZARD_MAX,
                (level.beaconLit ? 1 : 0)
                        + level.turnsOnLevel / Math.max(1, GameBalance.HAZARD_TURNS_PER_POINT));
        // Difficulty scales how often fresh enemies arrive: >1 = less frequent
        // (Easy), <1 = more frequent (Very Hard).
        int cadence = Math.max(1, (int) Math.round(
                (GameBalance.RENEWING_SPAWN_CADENCE - 2 * level.hazardLevel)
                        * GameBalance.tuning().spawnCadenceMult()));
        if (level.turnsOnLevel % cadence != 0) return;
        if (MobQueries.countLivingHostiles(level)
                >= GameBalance.RENEWING_ENEMY_CAP + level.hazardLevel) return;
        Mob player = findPlayer(level);
        if (player == null || player.position == null) return;
        spawnRenewing(level, LevelFactoryPopulate.pickTypicalMob(level, TURN_SPAWN_RNG), player);
        if (level.hazardLevel > 0 && TURN_SPAWN_RNG.nextDouble() < 0.05 * level.hazardLevel) {
            spawnRenewing(level, "WRAITH", player);
        }
    }

    /** Spawn one awake, non-dropping renewing enemy of {@code type} on a floor
     *  tile outside the player's FOV and >= 20 tiles away. No-op if no spot. */
    private static void spawnRenewing(Level level, String type, Mob player) {
        if (type == null) return;
        int px = player.position.tileX(), py = player.position.tileY();
        for (int tries = 0; tries < 80; tries++) {
            int x = TURN_SPAWN_RNG.nextInt(level.width);
            int y = TURN_SPAWN_RNG.nextInt(level.height);
            if (!level.tiles[x][y].isFloorLike()) continue;
            if (level.visible != null && level.visible[x][y]) continue;
            if (Math.max(Math.abs(x - px), Math.abs(y - py)) < 20) continue;
            Point spot = new Point(x, y);
            if (MobQueries.mobAt(level, spot) != null) continue;
            Mob m = LevelFactoryPopulate.spawnMobAt(level, type, spot, 1 + level.depth,
                    TURN_SPAWN_RNG, false);
            if (m == null) return;
            m.stateOfMind = Mob.StateOfMind.AWAKE;
            m.suppressLoot = true;
            if (level.events != null) {
                level.events.add(new com.bjsp123.rl2.event.GameEvent.MobSpawned(m, spot));
            }
            return;
        }
    }

    public static boolean isPlayerTurn(Level level) {
        for (Mob mob : level.mobs) {
            if (mob.behavior == Behavior.PLAYER && mob.ticksTillMove == 0
                    && !BuffSystem.hasBuff(mob, com.bjsp123.rl2.model.Buff.BuffType.FROZEN)) return true;
        }
        return false;
    }

    public static Mob getActivePlayer(Level level) {
        for (Mob mob : level.mobs) {
            if (mob.behavior == Behavior.PLAYER && mob.ticksTillMove == 0
                    && !BuffSystem.hasBuff(mob, com.bjsp123.rl2.model.Buff.BuffType.FROZEN)) return mob;
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

    /** Charge a movement (step) cost. Buff speed modifiers are already folded into the
     *  cost supplied by {@link Mob#effectiveStats()}. */
    public static void applyMoveCost(Mob mob, int cost) {
        mob.ticksTillMove += Math.max(1, cost);
    }

    /** Charge an attack / action cost (melee, ranged, wand, throw). Buff speed
     *  modifiers are already folded into the cost supplied by {@link Mob#effectiveStats()}. */
    public static void applyActionCost(Mob mob, int cost) {
        mob.ticksTillMove += Math.max(1, cost);
    }

    // Effect-frame advancement moved to rgame.render.EffectStage; freeze scheduling
    // moved to rgame.anim.AnimQueue. rlib has no concept of render frames or visual
    // effects any more.
}
