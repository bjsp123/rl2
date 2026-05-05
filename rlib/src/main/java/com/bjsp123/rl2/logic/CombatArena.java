package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Level.Surface;
import com.bjsp123.rl2.model.Level.Vegetation;
import com.bjsp123.rl2.model.Level.VisualTheme;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.Tile;
import com.bjsp123.rl2.model.World;

import java.util.List;
import java.util.Random;

/**
 * Headless combat arena — builds a synthetic single-room {@link Level}, drops
 * mob teams in, and advances the simulation through the real game systems
 * ({@link TurnSystem}, {@link BuffSystem}, {@link MobSystem}). Used by the
 * Arena UI for "watch a fight" playback and (potentially) by future MobFight
 * rating sims so combat fidelity stays in lockstep with what actually happens
 * in the dungeon.
 *
 * <p>Zero rendering dependencies. The visual path adds an Animator + renderer
 * on top; the headless path just calls {@link #tickHeadless} in a loop.
 *
 * <p>The arena Level has no PLAYER-behaviour mob, so {@link TurnSystem#tick}'s
 * "isPlayerTurn" gate never blocks — combat auto-advances every call.
 */
public final class CombatArena {

    /** Default arena footprint — comfortable for ~3 mobs per side with room for
     *  ranged volleys. Bumped by callers that pack more mobs in. */
    public static final int DEFAULT_W = 16;
    public static final int DEFAULT_H = 16;

    private CombatArena() {}

    /**
     * Build a combat-ready Level: rectangular interior of FLOOR, perimeter
     * either WALL or CHASM (rolled), optional vegetation patches and surface
     * pools sprinkled in. All transient arrays non-null; every interior tile
     * starts {@code visible[][]=true} and {@code lit[][]=true} so the renderer
     * skips fog-of-war.
     */
    public static Level buildArenaLevel(int w, int h, Random rng) {
        Level level = new Level(w, h);
        level.theme = randomTheme(rng);
        // Layout (BSP) is the default initialiser on Level — leave alone.

        boolean wallPerimeter = rng.nextBoolean();
        Tile borderTile = wallPerimeter ? Tile.WALL : Tile.CHASM;

        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                boolean border = (x == 0 || y == 0 || x == w - 1 || y == h - 1);
                level.tiles[x][y]   = border ? borderTile : Tile.FLOOR;
                level.visible[x][y] = true;
                level.lit[x][y]     = true;
                level.explored[x][y]= true;
            }
        }

        // ~50% chance of one vegetation patch — small cluster of grass / mushrooms
        // / trees somewhere in the interior. Keeps the room visually varied
        // without dominating the fight.
        if (rng.nextDouble() < 0.5) {
            Vegetation veg = pickVegetation(rng);
            sprinkleVegetation(level, veg, 4 + rng.nextInt(8), rng);
        }

        // ~50% chance of one surface pool (water / blood / oil). Same scattering
        // pattern, but only on tiles still empty of vegetation so the layers
        // don't overlap visually.
        if (rng.nextDouble() < 0.5) {
            Surface surf = pickSurface(rng);
            sprinklePool(level, surf, 5 + rng.nextInt(10), rng);
        }

        return level;
    }

    /** Append {@code mobs} to {@code level.mobs}, snapping each mob's
     *  {@code position} to the matching {@code spots} entry. Lists must be the
     *  same length. */
    public static void placeMobs(Level level, List<Mob> mobs, List<Point> spots) {
        if (level == null || mobs == null || spots == null) return;
        int n = Math.min(mobs.size(), spots.size());
        for (int i = 0; i < n; i++) {
            Mob m = mobs.get(i);
            if (m == null) continue;
            m.position = spots.get(i);
            level.mobs.add(m);
        }
    }

    /** Make every mob in {@code teamA} mutually hostile to every mob in
     *  {@code teamB}: each side's {@link Mob#attackTypes} gains the opposing
     *  team's species tags. Idempotent — calling twice is harmless. */
    public static void seedTeamHostility(List<Mob> teamA, List<Mob> teamB) {
        if (teamA == null || teamB == null) return;
        for (Mob a : teamA) {
            if (a == null || a.attackTypes == null) continue;
            for (Mob b : teamB) {
                if (b == null || b.mobType == null) continue;
                a.attackTypes.add(b.mobType);
            }
        }
        for (Mob b : teamB) {
            if (b == null || b.attackTypes == null) continue;
            for (Mob a : teamA) {
                if (a == null || a.mobType == null) continue;
                b.attackTypes.add(a.mobType);
            }
        }
    }

    /** True iff at least one pair of currently-alive mobs has {@code ATTACK}
     *  attitude toward each other. The arena ends as soon as this returns false. */
    public static boolean hostilePairExists(Level level) {
        if (level == null || level.mobs == null) return false;
        for (Mob a : level.mobs) {
            if (a == null || a.hp <= 0) continue;
            for (Mob b : level.mobs) {
                if (a == b || b == null || b.hp <= 0) continue;
                if (MobSystem.getAttitudeToMob(a, b) == MobSystem.Attitude.ATTACK) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Advance one combat tick — drives {@link TurnSystem#tick} (which handles
     * AI, melee, attacks, buff DOTs every {@code STANDARD_TURN_TICKS} ticks)
     * plus the real-time clocks PlayScreen runs each frame. Caller is
     * responsible for the visual layer (Animator + renderer); the headless
     * path discards events.
     */
    public static void tickHeadless(Level level, World world, int dtMs) {
        if (level == null) return;
        // World.turn lets MobSystem time-stamp history events; not strictly
        // required but keeps the side-effect symmetry with the dungeon path.
        if (world != null) {
            world.tick++;
            level.currentTurn = world.turn;
        }
        TurnSystem.tick(level);
        FireSystem.tickRealTime(level, dtMs);
        LevelSystem.tickLightMotesRealTime(level, dtMs);
        MobSystem.advanceDeathAnimations(level);
        // Headless callers drop the event log; visual callers consume it from
        // the Animator before this method returns.
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private static VisualTheme randomTheme(Random rng) {
        VisualTheme[] themes = VisualTheme.values();
        return themes[rng.nextInt(themes.length)];
    }

    private static Vegetation pickVegetation(Random rng) {
        Vegetation[] picks = { Vegetation.GRASS, Vegetation.MUSHROOMS, Vegetation.TREES };
        return picks[rng.nextInt(picks.length)];
    }

    private static Surface pickSurface(Random rng) {
        Surface[] picks = { Surface.WATER, Surface.BLOOD, Surface.OIL };
        return picks[rng.nextInt(picks.length)];
    }

    private static void sprinkleVegetation(Level level, Vegetation v, int count, Random rng) {
        for (int i = 0; i < count; i++) {
            int x = 1 + rng.nextInt(level.width - 2);
            int y = 1 + rng.nextInt(level.height - 2);
            if (level.tiles[x][y] != Tile.FLOOR) continue;
            if (level.vegetation[x][y] != null) continue;
            level.vegetation[x][y] = v;
        }
    }

    private static void sprinklePool(Level level, Surface s, int count, Random rng) {
        for (int i = 0; i < count; i++) {
            int x = 1 + rng.nextInt(level.width - 2);
            int y = 1 + rng.nextInt(level.height - 2);
            if (level.tiles[x][y] != Tile.FLOOR) continue;
            if (level.vegetation[x][y] != null) continue;
            if (level.surface[x][y] != null) continue;
            level.surface[x][y] = s;
        }
    }
}
