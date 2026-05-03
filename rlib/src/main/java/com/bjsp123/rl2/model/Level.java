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
        /** Corridors become FLOOR_WOOD plank bridges over chasm — chasm to either side stays
         *  chasm rather than being walled in, so corridors look like walkways suspended over
         *  a drop. Rooms still get conventional walls. */
        WALKWAY_LEVEL,
        /** Spawns many more vegetation patches than usual. */
        PLANTS,
        /** Only 3-5 rooms on this level, but each is much larger than a normal room. */
        BIGROOMS,
        /** Scales the level's tile dimensions up by 1.5×. The downstream builders are
         *  data-driven (BSP partitions to fit, Poisson scales with area, Loop's radius
         *  derives from {@code min(w,h)}) so room counts and corridor lengths grow with
         *  the canvas without any builder having to know about the flag. */
        BIGLEVEL,
        /** Corridors are random-walk "rough" paths instead of clean L-shapes — they drift
         *  toward the goal but wander on the cross axis ~20% of the time, so the level
         *  reads more cave-like and fewer corridors meet a room edge head-on. */
        ROUGH
    }

    /**
     * Liquid/slick overlaid on a floor tile. A cell with a surface renders like a shallow
     * water tile — scrolling animated base plus shore stitch overlay — tinted per surface
     * type. Does not block movement or sight.
     */
    public enum Surface {
        WATER, BLOOD, OIL, ICE
    }

    /**
     * Ground-level flora/effect overlay drawn on top of the floor. GRASS is a few green
     * blades, MUSHROOMS is a small cluster, FIRE is a burning patch managed by
     * {@link com.bjsp123.rl2.logic.FireSystem}, TREES are large trunks that block light
     * (but not movement or line-of-sight) and are flammable like grass — they don't spread
     * on their own. None of these block sight; TREES blocks light propagation only (see
     * {@link #blocksLight}).
     */
    public enum Vegetation {
        GRASS, MUSHROOMS, FIRE, TREES;

        /** True if a tile carrying this vegetation should block <i>light</i> — lamps and
         *  glowing items don't shine past it. Sight is unaffected: the player can still
         *  spot mobs through the canopy as long as the tile is lit by something else. */
        public boolean blocksLight() { return this == TREES; }
    }

    /**
     * Gaseous overlay on a tile, rendered as a bobbing semi-transparent oval on top of
     * everything else. SMOKE is black, STEAM is white. Purely visual — doesn't block
     * movement or sight.
     */
    public enum Cloud {
        SMOKE, STEAM
    }

    /**
     * Lateral position of a level on the dungeon map. The world graph is a diamond:
     * depth 1 and depth 5 each have one CENTER level; depths 2-4 each have a WEST and an
     * EAST level. Defaults to {@link #CENTER} so old (linear) saves still load — the
     * MapScreen treats CENTER levels as a column down the middle.
     */
    public enum Side {
        WEST, CENTER, EAST
    }

    /**
     * Picked per level at generation time to drive tileset selection. Purely a visual tag —
     * doesn't affect gameplay. Renderers map a theme to a concrete texture asset.
     */
    public enum VisualTheme {
                CRYSTAL,
               CONCRETE
    }

    public int width;
    public int height;
    /** 1-based dungeon depth (1 = surface, higher = deeper). Set by the level factory; used
     *  by history records to time-stamp events with location. */
    public int depth = 1;
    /** Monotonic game-turn counter copied in from {@code World.turn} each frame by the play
     *  screen. Lets stateless {@code MobSystem} functions time-stamp events without having
     *  to thread a turn parameter through every call site. Transient — restored from
     *  {@code World.turn} on load. */
    public transient int currentTurn;
    /** Tick counter for the standard-turn cadence (see
     *  {@link com.bjsp123.rl2.logic.TurnSystem#STANDARD_TURN_TICKS}). Each call to
     *  {@link com.bjsp123.rl2.logic.TurnSystem#tick} increments this by 1; when it
     *  reaches {@code STANDARD_TURN_TICKS} it resets and fires one pass of the per-turn
     *  handlers (vegetation spread, fire spread, …). Transient — on load the cadence
     *  simply resumes from zero, which at worst delays the next per-turn pulse by a
     *  fraction of a turn. */
    public transient int standardTurnTickAcc;
    // Animation freeze fields moved out — owned by rgame.anim.AnimQueue. rlib has no
    // concept of render frames.
    /** Engine→renderer event log. Appended to by {@code rlib} systems during a tick and
     *  drained by {@code rgame}'s animator immediately after each
     *  {@link com.bjsp123.rl2.logic.TurnSystem#tick}. Transient — events never survive
     *  a save/load cycle. */
    public transient List<com.bjsp123.rl2.event.GameEvent> events = new ArrayList<>();
    public Tile[][] tiles;
    /** Liquid/slick overlay on a tile (water, blood, oil). {@code null} = none. */
    public Surface[][] surface;
    /** Floating gas on a tile (smoke, steam). {@code null} = none. */
    public Cloud[][] cloud;
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
    // Visual effects moved out — owned by rgame.render.EffectStage.
    /** Per-tile countdown to the next fire-particle emission. Ticked down by
     *  {@link com.bjsp123.rl2.logic.FireSystem}; on zero/negative, a particle Effect is
     *  spawned and the counter resets to ~50. Transient — emit cadence is purely visual. */
    public transient int[][] fireEmitCountdown;

    /** Generation-time flags that nudge overall level style (more water, void corridors, etc.).
     *  Stored as a plain {@link HashSet} rather than {@link java.util.EnumSet} because
     *  libGDX's {@code Json} can't instantiate {@code EnumSet} subclasses on load — saves
     *  would silently fail to deserialize. */
    public Set<LevelFlag> flags = new HashSet<>();

    /** Tileset theme assigned at generation time. Drives which terrain PNG the renderer uses;
     *  no gameplay impact. Defaults to {@link VisualTheme#CLASSIC} so older saves without the
     *  field still load into the classic look. */
    public VisualTheme theme = VisualTheme.CRYSTAL;

    /** Structural archetype rolled at generation time (hub-and-spoke, wheel, labyrinth, …).
     *  Set by the level factory and otherwise informational — post-generation, the tile grid
     *  is authoritative. Defaults to {@link Layout#SPD} so older saves still load. */
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
     *  the per-level fields say it is — no separate hardcoded graph table. */
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
     *  positions — the world generator can pick any scale (we use {@code -1, 0, +1} for
     *  the diamond), so the map screen handles arbitrary layered DAGs without knowing
     *  about specific topologies. */
    public float mapColumn;

    /** True once the player has set foot on this level. Drives the map screen's
     *  visited-vs-unknown rendering — known levels show a tile-grid mini map, unknown
     *  levels show a question mark. Default false. */
    public boolean visited;

    /** No-arg constructor for JSON deserialization — does not allocate arrays. */
    public Level() {}

    public Level(int width, int height) {
        this.width = width;
        this.height = height;
        this.tiles      = new Tile[width][height];
        this.surface    = new Surface[width][height];
        this.cloud      = new Cloud[width][height];
        this.vegetation = new Vegetation[width][height];
        this.fireRemaining     = new int[width][height];
        this.fireEmitCountdown = new int[width][height];
        this.explored = new boolean[width][height];
        this.lit = new boolean[width][height];
        this.visible = new boolean[width][height];
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
        if (events  == null) events  = new ArrayList<>();
        // Older saved levels may predate the overlay arrays.
        if (surface    == null) surface    = new Surface[width][height];
        if (cloud      == null) cloud      = new Cloud[width][height];
        if (vegetation == null) vegetation = new Vegetation[width][height];
        if (fireRemaining     == null) fireRemaining     = new int[width][height];
        if (fireEmitCountdown == null) fireEmitCountdown = new int[width][height];
        if (flags      == null) flags      = new HashSet<>();
        if (theme      == null) theme      = VisualTheme.CRYSTAL;
        if (layout     == null) layout     = Layout.BSP;
        if (side       == null) side       = Side.CENTER;
    }
}
