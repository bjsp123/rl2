package com.bjsp123.rl2.model;

import com.bjsp123.rl2.logic.LevelFactory.Layout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Level {
    /**
     * Optional modifiers rolled at generation time (currently 20% chance each). Each flag
     * nudges the level builder toward a different style without changing the core
     * room/corridor algorithm.
     */
    public enum LevelFlag {
        /** Spawns many more water pools than usual. */
        WATER,
        /** Corridors become FLOOR_WOOD plank bridges over chasm - chasm to either side stays
         *  chasm rather than being walled in, so corridors look like walkways suspended over
         *  a drop. Rooms still get conventional walls. */
        WALKWAY_LEVEL,
        /** Spawns many more vegetation patches than usual. */
        PLANTS,
        /** Scales the level's tile dimensions up by 1.5x. The downstream builders are
         *  data-driven (BSP partitions to fit, Poisson scales with area, Loop's radius
         *  derives from {@code min(w,h)}) so room counts and corridor lengths grow with
         *  the canvas without any builder having to know about the flag. */
        BIGLEVEL,
        /** Corridors are random-walk "rough" paths instead of clean L-shapes - they drift
         *  toward the goal but wander on the cross axis ~20% of the time, so the level
         *  reads more cave-like and fewer corridors meet a room edge head-on. */
        ROUGH
    }

    /**
     * Liquid/slick overlaid on a floor tile. A cell with a surface renders like a shallow
     * water tile - scrolling animated base plus shore stitch overlay - tinted per surface
     * type. Does not block movement or sight.
     */
    public enum Surface {
        WATER, BLOOD, OIL, ICE
    }

    /**
     * Ground-level flora/effect overlay drawn on top of the floor. GRASS is a few green
     * blades, MUSHROOMS is a small cluster, FIRE is a burning patch managed by
     * {@link com.bjsp123.rl2.logic.FireSystem}, TREES are large trunks that block both
     * light and line-of-sight (canopy + trunk together hide what's behind), and are
     * flammable like grass - they don't spread on their own.
     */
    public enum Vegetation {
        GRASS, MUSHROOMS, FIRE, TREES;

        /** True if a tile carrying this vegetation should block <i>light</i> - lamps and
         *  glowing items don't shine past it. */
        public boolean blocksLight() { return this == TREES; }

        /** True if a tile carrying this vegetation should block <i>sight</i> - the
         *  player can't see mobs hiding behind it. Currently only TREES does;
         *  the {@link com.bjsp123.rl2.model.Perk#KEEN_SIGHT} perk lets a mob
         *  see through up to N tree/cloud tiles within Chebyshev range N. */
        public boolean blocksSight() { return this == TREES; }
    }

    /**
     * Gaseous overlay on a tile. Stored as part of the packed {@link Level#cloud}
     * grid: each cell carries a {@link Cloud} type and a duration (in standard
     * turns) capped at {@link com.bjsp123.rl2.logic.CloudSystem#MAX_DURATION}.
     *
     * <p>SMOKE is black and blocks sight + light (but not projectiles); STEAM is
     * pale grey; POISON is green and applies the {@link Buff.BuffType#POISONED}
     * buff to any mob standing in it. See {@link com.bjsp123.rl2.logic.CloudSystem}
     * for the per-turn drift / spread / emission rules.
     */
    public enum Cloud {
        SMOKE, STEAM, POISON, SPORE
    }

    /**
     * Lateral position of a level on the dungeon map. The world graph is a diamond:
     * depth 1 and depth 5 each have one CENTER level; depths 2-4 each have a WEST and an
     * EAST level. Defaults to {@link #CENTER} so old (linear) saves still load - the
     * MapScreen treats CENTER levels as a column down the middle.
     */
    public enum Side {
        WEST, CENTER, EAST
    }

    /**
     * Picked per level at generation time to drive tileset selection. Purely a visual tag -
     * doesn't affect gameplay. Renderers map a theme to a concrete texture asset.
     */
    public enum VisualTheme {
                CRYSTAL,
               CONCRETE,
               SHINY,
               GOTHIC
    }

    /**
     * Gameplay kind of the level. Regular dungeon floors are REGULAR; the
     * four endgame floors below the main dungeon use a fixed kind so the
     * per-turn endgame tick + the renderer can branch on layout-specific
     * rules (stairs vanishing on entry, horde spawner, win conditions).
     */
    public enum LevelKind {
        REGULAR, LANDING, MIRRORMATCH, HORDE, WALKWAY, FINAL_BOSS, EXIT_PORTAL
    }

    public int width;
    public int height;
    /** 1-based dungeon depth (1 = surface, higher = deeper). Set by the level factory; used
     *  by history records to time-stamp events with location. */
    public int depth = 1;
    /** Completed standard turn copied in from the world's tick counter each frame.
     *  Lets stateless {@code MobSystem} functions time-stamp events without having
     *  to thread a turn parameter through every call site. */
    public transient int currentTurn;
    /** Tick counter for the standard-turn cadence (see
     *  {@link com.bjsp123.rl2.logic.TurnSystem#STANDARD_TURN_TICKS}). Each call to
     *  {@link com.bjsp123.rl2.logic.TurnSystem#tick} increments this by 1; when it
     *  reaches {@code STANDARD_TURN_TICKS} it resets and fires one pass of the per-turn
     *  handlers (vegetation spread, fire spread, ...). Transient - on load the cadence
     *  simply resumes from zero, which at worst delays the next per-turn pulse by a
     *  fraction of a turn. */
    public transient int standardTurnTickAcc;
    // Animation freeze fields moved out - owned by rgame.anim.AnimQueue. rlib has no
    // concept of render frames.
    /** Engine->renderer event log. Appended to by {@code rlib} systems during a tick and
     *  drained by {@code rgame}'s animator immediately after each
     *  {@link com.bjsp123.rl2.logic.TurnSystem#tick}. Transient - events never survive
     *  a save/load cycle. */
    public transient List<com.bjsp123.rl2.event.GameEvent> events = new ArrayList<>();

    /** FIFO queue of "resolve this impact" callbacks added during this tick.
     *  Populated by {@code MobSystem.throwItem} and {@code ItemSystem.fireWand}
     *  when they defer their world-state mutation to step 4 of the animation-
     *  gated lifecycle (see throwItem javadoc). Drained by the rgame Animator
     *  at arc completion (on-screen) or by {@code MobAi.processAllAiTurns}
     *  between mob brains (headless). Each Runnable IS the deferred call to
     *  the apply*Impact method and decrements {@link #pendingImpactCount}
     *  when it runs. */
    public transient java.util.Deque<Runnable> pendingImpacts = new java.util.ArrayDeque<>();

    /** Count of outstanding pending impacts whose visuals haven't completed
     *  yet. Increments when an animation-gated action queues an impact;
     *  decrements when the impact's resolve callback runs. Used as the "world
     *  is frozen" gate: while {@code > 0}, no mob brain should execute and no
     *  game-tick should advance. */
    public transient int pendingImpactCount = 0;
    public Tile[][] tiles;
    /** Liquid/slick overlay on a tile (water, blood, oil). {@code null} = none. */
    public Surface[][] surface;
    /** Floating gas on a tile (smoke, steam, poison) packed into a single int per
     *  cell - the high nybble carries the {@link Cloud} type ordinal + 1 (so
     *  0 means "no cloud"), the low nybble carries the duration (turns until the
     *  cloud dissipates, capped at {@link com.bjsp123.rl2.logic.CloudSystem#MAX_DURATION}).
     *  Read / write through {@link com.bjsp123.rl2.logic.CloudSystem} helpers
     *  rather than raw to keep the encoding contained. */
    public int[][] cloud;
    /** Ground flora on a tile (grass, mushrooms, fire). {@code null} = none. */
    public Vegetation[][] vegetation;
    /** Remaining lifetime in ticks for a fire vegetation tile; 0 elsewhere. Ticked down by
     *  {@link com.bjsp123.rl2.logic.FireSystem}; once it reaches 0 the FIRE vegetation is
     *  cleared back to null. Persisted so a save mid-conflagration stays mid-conflagration. */
    public int[][] fireRemaining;
    public boolean[][] explored;
    public List<Mob> mobs;
    public List<Item> items;

    /** Re-derived per frame from light sources; not serialized. */
    public transient boolean[][] lit;
    /** Re-derived per frame from the player's vision; not serialized. */
    public transient boolean[][] visible;
    /** Reusable flat scratch buffers for FOV/lighting computations. */
    public transient boolean[] lightBlockingScratch;
    public transient boolean[] sightBlockingScratch;
    public transient boolean[] visibilityAccumScratch;
    public transient boolean[] visibilityTempScratch;
    public transient boolean[] wallPropagationScratch;
    // Visual effects moved out - owned by rgame.render.EffectStage.
    /** Per-tile countdown to the next fire-particle emission. Ticked down by
     *  {@link com.bjsp123.rl2.logic.FireSystem}; on zero/negative, a particle Effect is
     *  spawned and the counter resets to ~50. Transient - emit cadence is purely visual. */
    public transient int[][] fireEmitCountdown;

    /** Per-tile countdown until a MUSHROOMS vegetation tile emits a spore cloud;
     *  0 elsewhere. Ticked by {@link com.bjsp123.rl2.logic.CloudSystem}; on
     *  reaching 0 the tile emits a {@link Cloud#SPORE} cloud and resets to
     *  {@code MUSHROOM_SPORE_INTERVAL}. Transient - re-seeded (staggered 0-6 so
     *  mushrooms don't fire in lockstep) on the first tick after gen / load,
     *  gated by {@link #sporesSeeded}. */
    public transient int[][] sporeCountdown;
    /** False until {@link com.bjsp123.rl2.logic.CloudSystem} has seeded the
     *  {@link #sporeCountdown} for this level's mushroom tiles. Transient, so a
     *  loaded level re-seeds (fresh stagger) on its next tick. */
    public transient boolean sporesSeeded;

    /** Back-reference to the {@link World} that owns this level. Set by
     *  {@link World#linkLevels} on construction and on save load (after
     *  the transient is wiped by JSON deserialisation). Lets level-local
     *  systems (e.g. the chasm-fall-to-next-level path) resolve their
     *  staircase neighbours without threading a {@code World} parameter
     *  through every call site. */
    public transient World world;

    /** Generation-time flags that nudge overall level style (more water, void corridors, etc.).
     *  Stored as a plain {@link HashSet} rather than {@link java.util.EnumSet} because
     *  libGDX's {@code Json} can't instantiate {@code EnumSet} subclasses on load - saves
     *  would silently fail to deserialize. */
    public Set<LevelFlag> flags = new HashSet<>();

    /** Tileset theme assigned at generation time. Drives which terrain PNG the renderer uses;
     *  no gameplay impact. Defaults to {@link VisualTheme#CRYSTAL} so older saves without the
     *  field still load into the default look. */
    public VisualTheme theme = VisualTheme.CRYSTAL;

    /** Structural archetype rolled at generation time (hub-and-spoke, wheel, labyrinth, ...).
     *  Set by the level factory and otherwise informational - post-generation, the tile grid
     *  is authoritative. Defaults to {@link Layout#BSP} so older saves still load. */
    public Layout layout = Layout.BSP;

    public Point stairsUp;
    public Point stairsDown;
    /** Optional second stairs-up tile, used by levels that branch upward to two different
     *  parents (in the diamond topology, this is the depth-5 boundary level). {@code null}
     *  when absent. */
    public Point stairsUpAlt;
    /** Optional second stairs-down tile, used by levels that branch downward to two
     *  different children (in the diamond topology, this is the depth-1 boundary level).
     *  {@code null} when absent. */
    public Point stairsDownAlt;

    /** Index in {@code World.levels} of the level reached by ascending {@link #stairsUp}.
     *  {@code -1} = no stair / no link. Set by the world generator; read by stair
     *  transitions and by the map screen's edge-drawing pass so the topology is whatever
     *  the per-level fields say it is - no separate hardcoded graph table. */
    public int stairsUpTarget    = -1;
    /** Sibling of {@link #stairsUpTarget} for {@link #stairsUpAlt}. */
    public int stairsUpAltTarget = -1;
    /** Index in {@code World.levels} of the level reached by descending {@link #stairsDown}. */
    public int stairsDownTarget    = -1;
    /** Sibling of {@link #stairsDownTarget} for {@link #stairsDownAlt}. */
    public int stairsDownAltTarget = -1;

    /** Where a brand-new character spawns on this level (typically equals stairsUp, or a floor tile on the top level). */
    public Point spawnPoint;

    /** Lateral position on the dungeon map (WEST, CENTER, EAST). Drives which side of the
     *  level's box flag glyphs render on. Independent of {@link #mapColumn} (which is the
     *  numeric layout coordinate the map screen uses), but typically kept in sync. */
    public Side side = Side.CENTER;

    /** Horizontal coordinate of this level on the dungeon map. The map screen reads this
     *  from every level, finds the min/max across the world, and normalises into pixel
     *  positions - the world generator can pick any scale (we use {@code -1, 0, +1} for
     *  the diamond), so the map screen handles arbitrary layered DAGs without knowing
     *  about specific topologies. */
    public float mapColumn;

    /** True once the player has set foot on this level. Drives the map screen's
     *  visited-vs-unknown rendering - known levels show a tile-grid mini map, unknown
     *  levels show a question mark. Default false. */
    public boolean visited;

    // -- Special-level capabilities -------------------------------------------
    // Hand-built "special" levels (Landing, Mirrormatch, Horde, Walkway) opt
    // into the rules below via plain data set by the factory. Generic handlers
    // in LevelSystem / MobSystem / TurnSystem read these - nothing keys runtime
    // behaviour off LevelKind any more, so any level (special or regular,
    // anywhere in the dungeon) can use them. All default to off, so regular
    // floors are unaffected.

    /** Gameplay kind of this level. {@link LevelKind#REGULAR} for normal
     *  procedurally-generated floors; one of the special values for the
     *  hand-built floors. Construction / map metadata only - the level
     *  factory dispatches on it and the map renderer may style on it, but it
     *  no longer gates any per-turn or arrival behaviour. */
    public LevelKind kind = LevelKind.REGULAR;

    /** When true, the level's stairs-up vanish the first time the PLAYER
     *  arrives (no retreat). Handled generically on arrival - see
     *  {@code LevelSystem.sealStairsUp}. Idempotent: once sealed,
     *  {@link #stairsUp} is null so it never re-fires. */
    public boolean sealOnEntry;

    /** When true, the exit stairs are withheld until every hostile mob is
     *  dead; the per-turn handler then stamps {@code STAIRS_DOWN} at
     *  {@link #lockedExit}. See {@code LevelSystem.openExitIfCleared}. */
    public boolean exitUnlocksOnClear;

    /** Tile where {@code STAIRS_DOWN} is stamped once the level is cleared.
     *  Used only with {@link #exitUnlocksOnClear}; the factory owns the
     *  coordinate so no logic hard-codes it. */
    public Point lockedExit;

    /** Generic count of standard turns the level has been ticking. Bumped once
     *  per standard turn by {@code TurnSystem}; drives spawner escalation.
     *  Zero until the level starts ticking (i.e. the player is on it). */
    public int turnsOnLevel;

    /** RL-54 hazard level (0..HAZARD_MAX). Rises by 1 when the beacon is lit and
     *  by 1 per HAZARD_TURNS_PER_POINT turns spent here; raises renewing-enemy
     *  frequency + cap. Recomputed each standard turn by {@code TurnSystem}. */
    public int hazardLevel;
    /** True once this level's beacon has been lit (contributes +1 hazard). */
    public boolean beaconLit;

    /** Optional data-driven mob spawner. {@code null} on levels that don't
     *  spawn - the per-turn handler ({@code MobSystem.runLevelSpawner}) is a
     *  no-op then. Set by the factory (e.g. the Horde floor). */
    public Spawner spawner;

    // --- Final-boss floor (FINAL_BOSS) --------------------------------------
    /** True once the Great Wraith has been defeated. Stairs are stamped at
     *  {@link #lockedExit} on its death; stepping there wins. */
    public boolean bossDefeated;
    /** Cardinal soul-spawner anchor tiles (the SOUL_SPAWNER_L cells). Revenant
     *  adds spawn on free floor adjacent to a random one. */
    public java.util.List<Point> spawnerTiles = new java.util.ArrayList<>();
    /** Depleting copy of the player's kill roster (mobType per slain individual)
     *  seeded on boss-floor entry; the add-spawner pops from it until empty, then
     *  support ends. {@code null} on non-boss floors. */
    public java.util.List<String> remainingRoster;

    /**
     * Data-driven per-turn mob spawner attached to a level. Every standard
     * turn the handler rolls {@link #chancePerTurn}; on success it spawns a
     * random species from {@link #speciesPool} at a tile chosen by
     * {@link #placement}, optionally awake, scaling the spawn level by
     * {@link #levelRampPer10Turns}. Capped at {@link #maxAlive} live mobs of
     * the pooled species.
     */
    public static class Spawner {
        /** Where a spawned mob is placed relative to the level. */
        public enum Placement {
            /** Near an arbitrary spawner anchor (legacy ant-hill style). */
            ADJACENT,
            /** Roughly halfway between the player and the exit stairs, on
             *  average - jittered around the midpoint then snapped to a free
             *  floor tile. */
            MIDPOINT_TO_EXIT,
            /** On free floor adjacent to a random {@link Level#spawnerTiles}
             *  anchor (final-boss soul spawners). */
            SOUL_SPAWNERS
        }

        /** Probability per standard turn of spawning one mob. */
        public double chancePerTurn;
        /** Deterministic cadence: when {@code > 0}, spawn once every this many
         *  standard turns ({@code turnsOnLevel % everyNTurns == 0}) instead of
         *  rolling {@link #chancePerTurn}. */
        public int everyNTurns;
        /** Species keys to pick from at random for each spawn. */
        public java.util.List<String> speciesPool = new java.util.ArrayList<>();
        /** Placement strategy for the spawned mob. */
        public Placement placement = Placement.MIDPOINT_TO_EXIT;
        /** When true, the spawned mob starts awake and aware of the player. */
        public boolean spawnAwake;
        /** Spawn-level escalation: spawn level = {@code 1 + ramp * (turnsOnLevel/10)},
         *  capped at the progression max. Zero = no escalation. */
        public int levelRampPer10Turns;
        /** Cap on live mobs (counted across {@link #speciesPool}) before the
         *  spawner pauses. */
        public int maxAlive = 30;
    }

    /** Rectangles that themed-room generation has reserved on this level. Random-scatter
     *  passes (items / mobs in {@link com.bjsp123.rl2.logic.LevelFactoryPopulate}) skip
     *  any tile inside one of these so the themed room's contents stand alone. Transient:
     *  generated as a side-effect of {@code LevelFactory.createDungeonLevel}, not used
     *  after the level is populated. */
    public transient java.util.List<int[]> reservedRects = new java.util.ArrayList<>();

    /** Snapshot of a single room rectangle plus its themed-room kind, if any.
     *  {@code kind} is the {@code ThemedRoomDefinition.type} string (e.g.
     *  {@code "POTION_ROOM"}, {@code "KOBOLD_FORTRESS"}) when stamping claimed
     *  the rectangle, else {@code null} for plain layout-builder rooms. */
    public static final class RoomSnapshot {
        public int x, y, w, h;
        public String kind;
        public RoomSnapshot() {}
        public RoomSnapshot(int x, int y, int w, int h) {
            this.x = x; this.y = y; this.w = w; this.h = h;
        }
    }

    /** Snapshot of room rectangles produced by the layout builder, captured in
     *  {@code LevelFactory.createDungeonLevel} after carving + corridor-connect
     *  but before themed-room stamping starts removing rooms from the working
     *  list. Themed-room stamping back-fills each entry's {@code kind}.
     *  Diagnostic / dump-only - runtime systems read tiles directly. Transient
     *  (regenerated per session). */
    public transient java.util.List<RoomSnapshot> rooms = new java.util.ArrayList<>();

    /** True iff (x, y) sits inside any of {@link #reservedRects}. Cheap linear scan -
     *  the list is at most a couple of entries per level. */
    public boolean isReserved(int x, int y) {
        if (reservedRects == null) return false;
        for (int[] r : reservedRects) {
            if (x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3]) return true;
        }
        return false;
    }

    /** No-arg constructor for JSON deserialization - does not allocate arrays. */
    public Level() {}

    public Level(int width, int height) {
        this.width = width;
        this.height = height;
        this.tiles      = new Tile[width][height];
        this.surface    = new Surface[width][height];
        this.cloud      = new int[width][height];
        this.vegetation = new Vegetation[width][height];
        this.fireRemaining     = new int[width][height];
        this.fireEmitCountdown = new int[width][height];
        this.sporeCountdown    = new int[width][height];
        this.explored = new boolean[width][height];
        this.lit = new boolean[width][height];
        this.visible = new boolean[width][height];
        initVisibilityScratch();
        this.mobs = new ArrayList<>();
        this.items = new ArrayList<>();

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                tiles[x][y] = Tile.CHASM;
            }
        }
    }

    /** Re-allocate the transient per-frame arrays after deserialization. */
    public void initTransients() {
        if (lit     == null) lit     = new boolean[width][height];
        if (visible == null) visible = new boolean[width][height];
        initVisibilityScratch();
        if (events  == null) events  = new ArrayList<>();
        // Older saved levels may predate the overlay arrays.
        if (surface    == null) surface    = new Surface[width][height];
        if (cloud      == null) cloud      = new int[width][height];
        if (vegetation == null) vegetation = new Vegetation[width][height];
        if (fireRemaining     == null) fireRemaining     = new int[width][height];
        if (fireEmitCountdown == null) fireEmitCountdown = new int[width][height];
        if (sporeCountdown == null) sporeCountdown = new int[width][height];
        if (flags      == null) flags      = new HashSet<>();
        if (theme      == null) theme      = VisualTheme.CRYSTAL;
        if (layout     == null) layout     = Layout.BSP;
        if (side       == null) side       = Side.CENTER;
        if (reservedRects == null) reservedRects = new ArrayList<>();
        if (rooms == null) rooms = new ArrayList<>();
    }

    public void initVisibilityScratch() {
        int n = Math.max(0, width * height);
        if (lightBlockingScratch == null || lightBlockingScratch.length != n) {
            lightBlockingScratch = new boolean[n];
        }
        if (sightBlockingScratch == null || sightBlockingScratch.length != n) {
            sightBlockingScratch = new boolean[n];
        }
        if (visibilityAccumScratch == null || visibilityAccumScratch.length != n) {
            visibilityAccumScratch = new boolean[n];
        }
        if (visibilityTempScratch == null || visibilityTempScratch.length != n) {
            visibilityTempScratch = new boolean[n];
        }
        if (wallPropagationScratch == null || wallPropagationScratch.length != n) {
            wallPropagationScratch = new boolean[n];
        }
    }
}
