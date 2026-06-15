package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Mob.Behavior;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.Tile;
import com.bjsp123.rl2.model.Level.Vegetation;

import java.util.Arrays;

public class LevelSystem {

    /** Fixed light radius for a LAMP tile. */
    private static final int LAMP_LIGHT_RADIUS = 5;
    /** Fixed light radius for a FIRE-tagged vegetation tile - flames are dim sources, so
     *  one tile of glow is the user-spec footprint. */
    private static final int FIRE_LIGHT_RADIUS = 1;
    /** Average ms between mote emissions per visible LAMP tile. Tuned so each lamp emits
     *  ~one mote every couple of seconds - enough to read as "alive" without flooding
     *  rooms with sparkles. Only visible tiles emit; off-screen lamps stay quiet. */
    private static final int LIGHT_MOTE_INTERVAL_MS = 1800;
    /** Faster cadence for power-orb sparkles - magical loot should glint noticeably
     *  more than ambient lamps so the player's eye snaps to it across a room. */
    private static final int POWER_ORB_SPARKLE_INTERVAL_MS = 700;
    /** Cadence for the inward-spiral particle emitted from every visible
     *  active beacon. ~80 ms between emissions reads as a steady spiral
     *  swarm without saturating the effect stage. */
    private static final int INWARD_SPIRAL_INTERVAL_MS = 80;
    private static final java.util.Random LIGHT_MOTE_RNG =
            com.bjsp123.rl2.util.SimRng.register("LevelSystem.lightMote", new java.util.Random());

    /** Mark every tile of {@code level} as explored, so the renderer paints
     *  the whole map (in remembered-but-not-currently-visible state).
     *  Vision (FOV) is unchanged - currently-lit tiles still show in full
     *  colour, dark tiles in the explored-but-fogged tint. Used by the
     *  {@link com.bjsp123.rl2.model.Buff.BuffType#INSIGHT} per-turn
     *  handler. Idempotent - re-stamping already-true flags has no effect. */
    public static void markAllExplored(Level level) {
        if (level == null || level.explored == null) return;
        for (int x = 0; x < level.width; x++) {
            for (int y = 0; y < level.height; y++) {
                level.explored[x][y] = true;
            }
        }
    }

    /** Generic "level seals behind the player" rule. Replaces the level's
     *  {@code STAIRS_UP} (and alt) tiles with {@code FLOOR} and nulls the
     *  {@link Level#stairsUp}/{@link Level#stairsUpAlt} points so there's no
     *  retreat. Fired once on arrival for levels with
     *  {@link Level#sealOnEntry}; idempotent (after the first call the points
     *  are null, so re-running is a no-op). {@code stairsUpTarget} is left
     *  intact for the map screen's connectivity rendering. */
    public static void sealStairsUp(Level level) {
        if (level == null) return;
        clearStairUp(level, level.stairsUp);
        level.stairsUp = null;
        clearStairUp(level, level.stairsUpAlt);
        level.stairsUpAlt = null;
    }

    private static void clearStairUp(Level level, Point up) {
        if (up == null) return;
        int x = up.tileX(), y = up.tileY();
        if (x >= 0 && y >= 0 && x < level.width && y < level.height
                && level.tiles[x][y] == Tile.STAIRS_UP) {
            level.tiles[x][y] = Tile.FLOOR;
        }
    }

    /** Generic "exit unlocks once the level is cleared" rule. For levels with
     *  {@link Level#exitUnlocksOnClear} that haven't opened yet
     *  ({@code stairsDown == null}): once no enemy mob remains alive, stamp
     *  {@code STAIRS_DOWN} at {@link Level#lockedExit} and set
     *  {@link Level#stairsDown}. An "enemy" is anything alive that isn't an
     *  inanimate prop and isn't on the player's side - crucially excluding the
     *  player's own allies/pets AND the SMART-piloted avatar (autoplay/arena),
     *  none of which should hold the exit hostage. Called every standard turn;
     *  cheap no-op on levels that don't opt in. */
    public static void openExitIfCleared(Level level) {
        if (level == null || !level.exitUnlocksOnClear) return;
        if (level.stairsDown != null || level.lockedExit == null) return;
        for (Mob m : level.mobs) {
            if (m == null || m.hp <= 0) continue;
            if (m.behavior == Behavior.INANIMATE) continue;
            if (isPlayerSide(m)) continue; // the avatar + the player's own allies
            return; // an enemy is still alive - exit stays locked
        }
        int x = level.lockedExit.tileX(), y = level.lockedExit.tileY();
        if (x >= 0 && y >= 0 && x < level.width && y < level.height) {
            level.tiles[x][y] = Tile.STAIRS_DOWN;
            level.stairsDown = level.lockedExit;
        }
    }

    /** True for the player's own side: the avatar itself (human {@code PLAYER}
     *  or an unowned {@code SMART} mob - the autoplay/arena pilot) and any mob
     *  loyal to the player (faction {@code PLAYER}, or owned by a PLAYER/SMART
     *  avatar). Mirrors the crystal-door pass rule in {@code DoorBehavior} so a
     *  locked exit opens exactly when every ENEMY is dead - not held open by the
     *  player's pets or, in autoplay, by miscounting the agent as a hostile. */
    private static boolean isPlayerSide(Mob m) {
        return m.isPlayer
                || "PLAYER".equals(m.faction)
                || (m.owner != null && m.owner.isPlayer);
    }

    public static void computeLighting(Level level) {
        int w = level.width, h = level.height;
        boolean[] blocking = buildBlocking(level, /*forLight=*/ true);
        boolean[] accum = scratchAccum(level);
        boolean[] temp  = scratchTemp(level);

        for (Mob mob : level.mobs) {
            double radius = mob.lightRadius();
            if (radius <= 0) continue;
            int cx = mob.position.tileX();
            int cy = mob.position.tileY();
            if (cx < 0 || cy < 0 || cx >= w || cy >= h) continue;
            ShadowCaster.castShadow(cx, cy, w, temp, blocking, (int) Math.ceil(radius));
            for (int i = 0; i < accum.length; i++) if (temp[i]) accum[i] = true;
        }

        // Items dropped on the floor emit their own light (amulets of light, for example).
        // Same radius as when equipped - the amulet keeps glowing whether someone's wearing
        // it or not. Picked-up items have location == null and are skipped.
        if (level.items != null) {
            for (Item it : level.items) {
                if (it.location == null || it.lightRadius <= 0) continue;
                int cx = it.location.tileX();
                int cy = it.location.tileY();
                if (cx < 0 || cy < 0 || cx >= w || cy >= h) continue;
                ShadowCaster.castShadow(cx, cy, w, temp, blocking, (int) Math.ceil(it.lightRadius));
                for (int i = 0; i < accum.length; i++) if (temp[i]) accum[i] = true;
            }
        }

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (level.tiles[x][y] != Tile.LAMP && level.tiles[x][y] != Tile.BEACON_ACTIVE
                        && level.tiles[x][y] != Tile.GEM_HEARTH_L) continue;
                ShadowCaster.castShadow(x, y, w, temp, blocking, LAMP_LIGHT_RADIUS);
                for (int i = 0; i < accum.length; i++) if (temp[i]) accum[i] = true;
            }
        }

        // Fire vegetation acts as a small light source. Single ring of glow around each
        // burning tile so the player can see by torchlight inside dark rooms.
        if (level.vegetation != null) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (level.vegetation[x][y] != Vegetation.FIRE) continue;
                    ShadowCaster.castShadow(x, y, w, temp, blocking, FIRE_LIGHT_RADIUS);
                    for (int i = 0; i < accum.length; i++) if (temp[i]) accum[i] = true;
                }
            }
        }

        writeBack(level, level.lit, accum);
        propagateToWalls(level, level.lit);
    }

    /**
     * Real-time ticker for light-source ambience. Each visible LAMP tile rolls
     * {@code dtMs / LIGHT_MOTE_INTERVAL_MS} per call to spit out a faint upward mote.
     * Lives on the wall-clock domain (like {@link com.bjsp123.rl2.logic.FireSystem#tickRealTime})
     * so the sparkle keeps trickling while the game is paused on input. Off-screen lamps
     * are skipped - there's no point burning effect slots on rooms the player can't see.
     */
    public static void tickLightMotesRealTime(Level level, int dtMs) {
        if (level == null || level.tiles == null || level.events == null) return;
        if (dtMs <= 0) return;
        double pPerLamp = dtMs / (double) LIGHT_MOTE_INTERVAL_MS;
        double pPerSpiral = dtMs / (double) INWARD_SPIRAL_INTERVAL_MS;
        int w = level.width, h = level.height;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                Tile t = level.tiles[x][y];
                if (!level.visible[x][y]) continue;
                if (t == Tile.LAMP) {
                    // Plain lamps emit tile-anchored motes on the standard cadence.
                    // 16-px lift puts the spawn point at the lit upper half of
                    // the 2-tile-tall sprite, not the dark base.
                    if (LIGHT_MOTE_RNG.nextDouble() < pPerLamp) {
                        level.events.add(new com.bjsp123.rl2.event.GameEvent.LightMoteSpawn(
                                new com.bjsp123.rl2.model.Point(x, y), /*pixelOffsetY*/ 16f));
                    }
                } else if (t == Tile.BEACON_ACTIVE) {
                    // Active beacons emit InwardSpiralSpawn at the faster
                    // spiral cadence; the dispatcher spawns BOTH a spiral
                    // particle and a co-located mote (both lifted to the
                    // sprite's lit upper half), so there's no separate
                    // LightMoteSpawn from beacons.
                    if (LIGHT_MOTE_RNG.nextDouble() < pPerSpiral) {
                        level.events.add(new com.bjsp123.rl2.event.GameEvent.InwardSpiralSpawn(
                                new com.bjsp123.rl2.model.Point(x, y)));
                    }
                }
            }
        }
        // Glowing items (power orbs and friends) sparkle on the same channel - same
        // upward-drifting LIGHT_MOTE visual, just emitted faster so the item catches
        // the eye as a pickup.
        if (level.items != null) {
            double pPerOrb = dtMs / (double) POWER_ORB_SPARKLE_INTERVAL_MS;
            for (Item it : level.items) {
                if (it == null || !it.glows) continue;
                if (it.location == null) continue;
                int x = it.location.tileX(), y = it.location.tileY();
                if (x < 0 || y < 0 || x >= w || y >= h) continue;
                if (!level.visible[x][y]) continue;
                if (LIGHT_MOTE_RNG.nextDouble() >= pPerOrb) continue;
                // Items emit at the tile centre - no vertical lift.
                level.events.add(new com.bjsp123.rl2.event.GameEvent.LightMoteSpawn(
                        new com.bjsp123.rl2.model.Point(x, y), /*pixelOffsetY*/ 0f));
            }
        }
    }

    /**
     * Run a bounded shadow-cast FOV for {@code mob} and write the result into
     * {@code out} (one boolean per tile, row-major, addressed as {@code out[x][y]}).
     * Used by external decision systems (e.g. the SMART AI in {@code rai}) that need
     * a per-mob vision grid without disturbing {@link Level#visible} (which is the
     * player's FOV). Caller is responsible for sizing {@code out} to the level.
     */
    public static void computeMobVisibilityInto(Level level, Mob mob, boolean[][] out) {
        if (level == null || mob == null || out == null) return;
        int w = level.width, h = level.height;
        int cx = mob.position.tileX();
        int cy = mob.position.tileY();
        if (cx < 0 || cy < 0 || cx >= w || cy >= h) return;
        int radius = (int) Math.ceil(mob.effectiveStats().visionRadius);
        boolean[] blocking = buildBlockingLocal(level, cx, cy, radius);
        level.initVisibilityScratch();
        boolean[] fov = level.visibilityTempScratch;
        Arrays.fill(fov, 0, w * h, false);
        ShadowCaster.castShadow(cx, cy, w, fov, blocking, radius);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (fov[y * w + x]) out[x][y] = true;
    }

    /**
     * Move {@code mob} from its current level's STAIRS_DOWN tile to the linked deeper
     * level (or alt target). Pure-model side of the stairs transition - no rendering,
     * sound, or camera concerns. Used by {@code PlayController.tryStairs} (player) and
     * by SMART AI's descend action (NPC agent).
     *
     * @return true on success, false when not on stairs / target invalid.
     */
    public static boolean descendStairs(com.bjsp123.rl2.model.World world, Mob mob) {
        return useStairs(world, mob, /*direction=*/ 1);
    }

    /**
     * Move {@code mob} from its current level's STAIRS_UP tile to the linked
     * shallower level (or alt target). Pure-model side of the transition.
     */
    public static boolean ascendStairs(com.bjsp123.rl2.model.World world, Mob mob) {
        return useStairs(world, mob, /*direction=*/ -1);
    }

    private static boolean useStairs(com.bjsp123.rl2.model.World world, Mob mob, int direction) {
        if (world == null || mob == null || mob.position == null) return false;
        Level cur = world.currentLevel();
        if (cur == null) return false;
        Tile here = cur.tiles[mob.position.tileX()][mob.position.tileY()];
        if (direction > 0 && here != Tile.STAIRS_DOWN) return false;
        if (direction < 0 && here != Tile.STAIRS_UP)   return false;

        int srcIdx = world.currentLevelIndex;
        int target;
        if (direction > 0) {
            target = mob.position.equals(cur.stairsDown)
                    ? cur.stairsDownTarget
                    : cur.stairsDownAltTarget;
        } else {
            target = mob.position.equals(cur.stairsUp)
                    ? cur.stairsUpTarget
                    : cur.stairsUpAltTarget;
        }
        if (target < 0 || target >= world.levels.length) return false;
        Level next = world.levels[target];
        com.bjsp123.rl2.model.Point dest =
                com.bjsp123.rl2.model.WorldTopology.arrivalPointFrom(next, srcIdx, direction > 0);
        if (dest == null) return false;

        MobSystem.transferMobToLevel(cur, mob, next, dest);

        TurnSystem.applyMoveCost(mob, mob.effectiveStats().moveCost);
        for (Mob other : next.mobs) other.effectiveStats();
        if (mob.isPlayer) {
            String name = MobSystem.nameForLog(cur, mob);
            EventLog.add(direction > 0
                    ? Messages.stairsDescended(name, next.depth)
                    : Messages.stairsAscended(name, next.depth));
        }
        return true;
    }

    public static void updateVisibility(Level level) {
        int w = level.width, h = level.height;
        // No PLAYER mob on this level (just died, or arena-style headless
        // level)? Leave the prior FOV in place. Otherwise a freshly empty
        // mob list wipes level.visible to all-false the moment the player
        // is removed, blacking out the entire screen during the death
        // cinematic before any animation can play.
        boolean hasPlayer = false;
        for (Mob mob : level.mobs) {
            if (mob.isPlayer) { hasPlayer = true; break; }
        }
        if (!hasPlayer) return;

        boolean[] blocking = buildBlocking(level, /*forLight=*/ false);
        boolean[] accum = scratchAccum(level);
        boolean[] temp  = scratchTemp(level);

        for (Mob mob : level.mobs) {
            if (!mob.isPlayer) continue;
            int cx = mob.position.tileX();
            int cy = mob.position.tileY();
            if (cx < 0 || cy < 0 || cx >= w || cy >= h) continue;
            // KEEN_SIGHT relaxes the blocking grid for this mob: tree-canopy
            // and smoke tiles within Chebyshev range = perk level become
            // transparent, so the player sees through them. Beyond the
            // range they still block sight normally. Returns the global
            // blocking array unchanged when the mob has no levels in the
            // perk to keep the common case allocation-free.
            boolean[] mobBlocking = relaxBlockingForKeenSight(level, mob, blocking, w, h);
            ShadowCaster.castShadow(cx, cy, w, temp, mobBlocking,
                    (int) Math.ceil(mob.effectiveStats().visionRadius));
            for (int i = 0; i < accum.length; i++) if (temp[i]) accum[i] = true;
        }

        writeBack(level, level.visible, accum);
        propagateToWalls(level, level.visible);
        // ESP override - if the player carries the buff, every mob's tile becomes
        // visible regardless of FOV. Doesn't reveal terrain, just bodies; the renderer
        // pairs this with explored=true for those cells so the mob sprite doesn't render
        // over an unexplored void.
        for (Mob mob : level.mobs) {
            if (!mob.isPlayer) continue;
            if (com.bjsp123.rl2.logic.BuffSystem.hasBuff(mob,
                    com.bjsp123.rl2.model.Buff.BuffType.ESP)) {
                for (Mob other : level.mobs) {
                    int x = other.position.tileX(), y = other.position.tileY();
                    if (x >= 0 && y >= 0 && x < w && y < h) {
                        level.visible[x][y] = true;
                    }
                }
            }
        }
        for (int x = 0; x < w; x++)
            for (int y = 0; y < h; y++)
                if (level.visible[x][y]) level.explored[x][y] = true;
    }

    /**
     * Flat blocking bitmap for the shadowcaster. Wall tiles and (closed) door tiles always
     * block - that's the static "scenery is opaque" layer. Mobs and tree-canopy vegetation
     * additionally block <i>light</i> propagation but not <i>sight</i>: lamps don't shine
     * through trees or past a mob, but the player can still spot what's behind them as long
     * as there's some other light source. {@code forLight} switches between the two:
     * {@code true} for the lighting pass (mobs + trees on), {@code false} for the FOV pass
     * (mobs + trees off). A closed door is opened up for both passes if a mob is standing
     * on it.
     *
     * <p>The 1-cell border of the map is forced to "blocks" - normal levels already have
     * walls there, but {@link com.bjsp123.rl2.model.Level.LevelFlag#WALKWAY_LEVEL} can leave
     * CHASM at the edge with no adjacent FLOOR to wall it in, and the shadowcaster has no
     * bounds check, so an unblocked edge lets it index past the array end and the
     * try/catch wipes the FOV.
     *
     * <p>The shadowcaster sets the source cell visible before scanning, so a mob's own tile
     * being flagged "blocks" is harmless - the bit only matters when light tries to pass
     * <i>through</i> that cell to a farther one.
     *
     * <p>Package-accessible so {@link LevelUtilities#getLineOfSight} can reuse the same
     * bitmap rules for ad-hoc LOS queries - sight queries should pass {@code forLight=false}.
     */
    static boolean[] buildBlocking(Level level, boolean forLight) {
        int w = level.width, h = level.height;
        boolean[] blocking = blockingScratch(level, forLight);
        Arrays.fill(blocking, 0, w * h, false);
        // Map of mob positions for O(1) lookups while we paint the grid. Track LARGE mobs
        // separately because they block both sight AND light, where small/medium mobs only
        // block light.
        boolean[] hasMob = scratchAccum(level);
        boolean[] hasLargeMob = scratchTemp(level);
        for (Mob m : level.mobs) {
            int mx = m.position.tileX(), my = m.position.tileY();
            if (mx >= 0 && my >= 0 && mx < w && my < h) {
                int idx = my * w + mx;
                hasMob[idx] = true;
                if (m.effectiveStats().size >= Mob.BIG_ENOUGH_TO_BLOCK_SIGHT) hasLargeMob[idx] = true;
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                Tile t = level.tiles[x][y];
                boolean blocks = t.blocksSight();
                // A LARGE mob's silhouette is tall enough to occlude sight (and therefore
                // light too) regardless of whatever the underlying tile would do - wins over
                // both the door-transparency hack and the open-floor case.
                if (hasLargeMob[idx]) {
                    blocks = true;
                } else if (blocks && t == Tile.DOOR && hasMob[idx]) {
                    // Open doors: a non-large mob standing on a closed door pries it open.
                    blocks = false;
                } else if (!blocks && forLight) {
                    // Lighting pass only: small/medium mobs and tree-canopy vegetation absorb light.
                    if (hasMob[idx]) blocks = true;
                    Vegetation v = level.vegetation[x][y];
                    if (v != null && v.blocksLight()) blocks = true;
                } else if (!blocks) {
                    // Sight pass: tree-canopy vegetation hides what's behind
                    // it. The lighting branch above already handles trees
                    // for the {@code forLight} build; this branch handles
                    // the FOV build.
                    Vegetation v = level.vegetation[x][y];
                    if (v != null && v.blocksSight()) blocks = true;
                }
                // Smoke clouds block both sight and light - opaque to FOV
                // (so a smoky room hides whatever's inside) and to lamps
                // (so a torch can't shine through a plume). Steam and
                // poison are see-through. Projectile traces don't consult
                // this bitmap, so missiles still fly through smoke.
                if (!blocks && CloudSystem.smokeAt(level, x, y)) {
                    blocks = true;
                }
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) blocks = true;
                blocking[idx] = blocks;
            }
        }
        return blocking;
    }

    /**
     * Bounding-box variant of {@link #buildBlocking} for sight queries.  Only fills the
     * {@code [vx-r .. vx+r] × [vy-r .. vy+r]} rectangle inside {@link Level#sightBlockingScratch},
     * leaving the rest stale (the shadow caster never reads past the radius anyway).  Cost
     * is O(r² + M) instead of O(W×H + M) — a ~3× win for vision radius ≈ 12 on a 40×50 level.
     *
     * <p>Uses {@link Level#visibilityAccumScratch} (hasMob) and
     * {@link Level#visibilityTempScratch} (hasLargeMob) as scratch; the caller must
     * clear/reuse {@code visibilityTempScratch} for its FOV array before calling the
     * shadow caster (which {@link MobSystem#snapshotVisibleMobsAtTurnStart} already does).
     */
    static boolean[] buildBlockingLocal(Level level, int vx, int vy, int radius) {
        int w = level.width, h = level.height;
        int xMin = Math.max(0, vx - radius), xMax = Math.min(w - 1, vx + radius);
        int yMin = Math.max(0, vy - radius), yMax = Math.min(h - 1, vy + radius);

        level.initVisibilityScratch();
        boolean[] blocking    = level.sightBlockingScratch;
        boolean[] hasMob      = level.visibilityAccumScratch;
        boolean[] hasLargeMob = level.visibilityTempScratch;

        // Clear only the rows the shadow caster will visit
        for (int y = yMin; y <= yMax; y++) {
            int base = y * w;
            Arrays.fill(blocking,    base + xMin, base + xMax + 1, false);
            Arrays.fill(hasMob,      base + xMin, base + xMax + 1, false);
            Arrays.fill(hasLargeMob, base + xMin, base + xMax + 1, false);
        }

        // Paint only mobs inside the bounding box
        for (Mob m : level.mobs) {
            int mx = m.position.tileX(), my = m.position.tileY();
            if (mx < xMin || my < yMin || mx > xMax || my > yMax) continue;
            int idx = my * w + mx;
            hasMob[idx] = true;
            if (m.effectiveStats().size >= Mob.BIG_ENOUGH_TO_BLOCK_SIGHT) hasLargeMob[idx] = true;
        }

        // Fill blocking for cells in the box (mirrors buildBlocking, forLight=false path)
        for (int y = yMin; y <= yMax; y++) {
            boolean yBorder = (y == 0 || y == h - 1);
            for (int x = xMin; x <= xMax; x++) {
                int idx = y * w + x;
                if (yBorder || x == 0 || x == w - 1) {
                    blocking[idx] = true;
                    continue;
                }
                Tile t = level.tiles[x][y];
                boolean blocks = t.blocksSight();
                if (hasLargeMob[idx]) {
                    blocks = true;
                } else if (blocks && t == Tile.DOOR && hasMob[idx]) {
                    blocks = false;
                }
                if (!blocks && CloudSystem.smokeAt(level, x, y)) {
                    blocks = true;
                }
                blocking[idx] = blocks;
            }
        }
        return blocking;
    }

    private static void writeBack(Level level, boolean[][] out, boolean[] src) {
        int w = level.width, h = level.height;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                out[x][y] = src[y * w + x];
    }

    /** Return a blocking grid for {@code mob}'s FOV pass with KEEN_SIGHT
     *  relaxations applied: any cell within Chebyshev range = perk level
     *  of the mob whose blocking comes from smoke or tree canopy is
     *  cleared. Other blockers (walls, doors, large mobs) stay opaque.
     *
     *  <p>When the mob has no levels in KEEN_SIGHT the input array is
     *  returned unchanged - this hot path runs every render frame, so
     *  allocating a copy for non-keen mobs would be wasteful. */
    private static boolean[] relaxBlockingForKeenSight(Level level, Mob mob,
                                                       boolean[] blocking,
                                                       int w, int h) {
        int keen = (mob.perks != null)
                ? mob.perks.getOrDefault(com.bjsp123.rl2.model.Perk.KEEN_SIGHT, 0)
                : 0;
        if (keen <= 0) return blocking;
        boolean[] relaxed = new boolean[blocking.length];
        System.arraycopy(blocking, 0, relaxed, 0, blocking.length);
        int mx = mob.position.tileX(), my = mob.position.tileY();
        int x0 = Math.max(0, mx - keen), x1 = Math.min(w - 1, mx + keen);
        int y0 = Math.max(0, my - keen), y1 = Math.min(h - 1, my + keen);
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                int idx = y * w + x;
                if (!relaxed[idx]) continue;
                // Only relax tiles whose blocking comes from smoke or
                // tree canopy. A wall or door within range stays opaque -
                // KEEN_SIGHT is about peering through soft cover, not
                // x-ray vision.
                boolean isSmoke = CloudSystem.smokeAt(level, x, y);
                Vegetation v = level.vegetation[x][y];
                boolean isTree = v != null && v.blocksSight();
                if (isSmoke || isTree) relaxed[idx] = false;
            }
        }
        return relaxed;
    }

    /**
     * Mark walls adjacent to a flag-true NON-WALL 8-neighbor as flag-true too. This promotes
     * just the first ring of walls bordering the visible floor area (the only walls the player
     * is looking at directly). We intentionally do NOT propagate through walls - that extra ring
     * reveals the outer layer of wall masses between rooms, which the player shouldn't see.
     */
    private static void propagateToWalls(Level level, boolean[][] flag) {
        boolean[] add = wallScratch(level);
        for (int x = 0; x < level.width; x++) {
            for (int y = 0; y < level.height; y++) {
                if (!level.tiles[x][y].blocksSight()) continue;
                if (flag[x][y]) continue;
                int idx = y * level.width + x;
                for (int dx = -1; dx <= 1 && !add[idx]; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        if (dx == 0 && dy == 0) continue;
                        int nx = x + dx, ny = y + dy;
                        if (nx < 0 || ny < 0 || nx >= level.width || ny >= level.height) continue;
                        if (level.tiles[nx][ny].blocksSight()) continue;
                        if (flag[nx][ny]) { add[idx] = true; break; }
                    }
                }
            }
        }
        for (int x = 0; x < level.width; x++)
            for (int y = 0; y < level.height; y++)
                if (add[y * level.width + x]) flag[x][y] = true;
    }

    private static boolean[] blockingScratch(Level level, boolean forLight) {
        level.initVisibilityScratch();
        return forLight ? level.lightBlockingScratch : level.sightBlockingScratch;
    }

    private static boolean[] scratchAccum(Level level) {
        level.initVisibilityScratch();
        Arrays.fill(level.visibilityAccumScratch, 0, level.width * level.height, false);
        return level.visibilityAccumScratch;
    }

    private static boolean[] scratchTemp(Level level) {
        level.initVisibilityScratch();
        Arrays.fill(level.visibilityTempScratch, 0, level.width * level.height, false);
        return level.visibilityTempScratch;
    }

    private static boolean[] wallScratch(Level level) {
        level.initVisibilityScratch();
        Arrays.fill(level.wallPropagationScratch, 0, level.width * level.height, false);
        return level.wallPropagationScratch;
    }
}
