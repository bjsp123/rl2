package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.HistoricalRecord;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob.Material;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;

import java.util.Random;

/**
 * Per-event hooks that fire as mobs come into existence, kill, or die. Centralises the
 * special-case behaviours that don't generalise into a single rule across all mobs - e.g.
 * the kissyblob budding off a fresh blob whenever it eats prey.
 *
 * <p><b>Design rule:</b> hooks dispatch on <i>flag fields</i> set on the mob, never on the
 * mob's glyph or species type. A new species that should bud on kill just sets
 * {@link Mob#eatSpawnChance} / {@link Mob#eatSpawnType}; the hook code below stays
 * unchanged. Branching on {@link com.bjsp123.rl2.model.Mob.MobType} is reserved for cases that
 * truly cannot be expressed as a flag.
 */
public final class MobHooks {

    private MobHooks() {}

    private static final Random RANDOM =
            com.bjsp123.rl2.util.SimRng.register("MobHooks", new Random());

    /**
     * Fires after {@code killer} lands the killing blow on {@code victim}, before the
     * victim is removed from the level. Currently handles the eat-spawn flag pair: if
     * the killer has {@code eatSpawnChance > 0} and the victim is flesh, roll the dice
     * and drop a freshly-spawned mob of {@code eatSpawnType} on a free adjacent tile.
     *
     * <p>{@code killer} may be null (environmental damage, starvation, etc.); in that
     * case the hook does nothing.
     */
    public static void onKill(Level level, Mob victim, Mob killer) {
        if (killer == null || victim == null) return;

        if (killer.effectiveStats().eatSpawnChance > 0 && killer.eatSpawnType != null
                && victim.material == Material.FLESH
                && RANDOM.nextDouble() < killer.effectiveStats().eatSpawnChance) {
            // Cap the level's total mob count - the dice roll is consumed first so
            // RNG state stays deterministic, but the spawn fizzles if we're full.
            if (!MobQueries.levelHasRoomForSpawn(level)) return;
            Point spawnPos = freeAdjacentFloor(level, killer.position);
            if (spawnPos == null) spawnPos = victim.position;
            Mob bud = MobFactory.spawn(killer.eatSpawnType, spawnPos);
            if (bud != null) {
                level.mobs.add(bud);
                onSpawn(level, bud);
                if (level.events != null) {
                    level.events.add(new com.bjsp123.rl2.event.GameEvent.MobSpawned(bud, spawnPos));
                }
                if (killer.history != null) {
                    killer.history.add(HistoricalRecord.spawned(
                            level.currentTurn, level.depth,
                            bud.name != null ? bud.name : "?"));
                }
            }
        }
    }

    /**
     * Fires when {@code victim} dies, regardless of whether a {@code killer} can be
     * attributed (e.g. starvation has none). Placeholder for future death-triggers
     * - corpse explosions, "in death I gain power" passives, etc. - that follow the
     * same flag-driven pattern as {@link #onKill}.
     */
    public static void onDie(Level level, Mob victim, Mob killer) {
        // Reserved for flag-driven death effects. None defined yet.
    }

    /**
     * Fires when {@code mob} is added to the world via {@link MobFactory#spawn} or any
     * other spawning path. Placeholder for entry effects (auras, summoning particles,
     * arrival messages); like the other hooks, all dispatch should ultimately read flag
     * fields on {@code mob}, not the species type.
     */
    public static void onSpawn(Level level, Mob mob) {
        // Reserved for flag-driven spawn effects. None defined yet.
    }

    // -- helpers -------------------------------------------------------------

    /** First walkable tile in the 8-neighborhood of {@code center} not occupied by a live
     *  mob, or null if every neighbor is blocked. Package-accessible so the horror's
     *  LOS-teleport (in {@link TurnSystem}) can land adjacent to the player. */
    static Point freeAdjacentFloor(Level level, Point center) {
        int cx = center.tileX(), cy = center.tileY();
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                int x = cx + dx, y = cy + dy;
                if (x < 0 || y < 0 || x >= level.width || y >= level.height) continue;
                if (!level.tiles[x][y].isFloorLike()) continue;
                boolean occupied = false;
                for (Mob m : level.mobs) {
                    if (m.position.tileX() == x && m.position.tileY() == y) {
                        occupied = true;
                        break;
                    }
                }
                if (!occupied) return new Point(x, y);
            }
        }
        return null;
    }
}
