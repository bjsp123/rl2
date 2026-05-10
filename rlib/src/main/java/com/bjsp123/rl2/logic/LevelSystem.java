package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Mob.Behavior;
import com.bjsp123.rl2.model.Tile;
import com.bjsp123.rl2.model.Level.Vegetation;

public class LevelSystem {

    /** Fixed light radius for a LAMP tile. */
    private static final int LAMP_LIGHT_RADIUS = 5;
    /** Fixed light radius for a FIRE-tagged vegetation tile — flames are dim sources, so
     *  one tile of glow is the user-spec footprint. */
    private static final int FIRE_LIGHT_RADIUS = 1;
    /** Average ms between mote emissions per visible LAMP tile. Tuned so each lamp emits
     *  ~one mote every couple of seconds — enough to read as "alive" without flooding
     *  rooms with sparkles. Only visible tiles emit; off-screen lamps stay quiet. */
    private static final int LIGHT_MOTE_INTERVAL_MS = 1800;
    /** Faster cadence for power-orb sparkles — magical loot should glint noticeably
     *  more than ambient lamps so the player's eye snaps to it across a room. */
    private static final int POWER_ORB_SPARKLE_INTERVAL_MS = 700;
    private static final java.util.Random LIGHT_MOTE_RNG = new java.util.Random();

    /** Mark every tile of {@code level} as explored, so the renderer paints
     *  the whole map (in remembered-but-not-currently-visible state).
     *  Vision (FOV) is unchanged — currently-lit tiles still show in full
     *  colour, dark tiles in the explored-but-fogged tint. Used by the
     *  {@link com.bjsp123.rl2.model.Buff.BuffType#INSIGHT} per-turn
     *  handler. Idempotent — re-stamping already-true flags has no effect. */
    public static void markAllExplored(Level level) {
        if (level == null || level.explored == null) return;
        for (int x = 0; x < level.width; x++) {
            for (int y = 0; y < level.height; y++) {
                level.explored[x][y] = true;
            }
        }
    }

    public static void computeLighting(Level level) {
        int w = level.width, h = level.height;
        boolean[] blocking = buildBlocking(level, /*forLight=*/ true);
        boolean[] accum = new boolean[w * h];
        boolean[] temp  = new boolean[w * h];

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
        // Same radius as when equipped — the amulet keeps glowing whether someone's wearing
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
                if (level.tiles[x][y] != Tile.LAMP) continue;
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
     * are skipped — there's no point burning effect slots on rooms the player can't see.
     */
    public static void tickLightMotesRealTime(Level level, int dtMs) {
        if (level == null || level.tiles == null || level.events == null) return;
        if (dtMs <= 0) return;
        double pPerLamp = dtMs / (double) LIGHT_MOTE_INTERVAL_MS;
        int w = level.width, h = level.height;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (level.tiles[x][y] != Tile.LAMP) continue;
                if (!level.visible[x][y]) continue;
                if (LIGHT_MOTE_RNG.nextDouble() >= pPerLamp) continue;
                level.events.add(new com.bjsp123.rl2.event.GameEvent.LightMoteSpawn(
                        new com.bjsp123.rl2.model.Point(x, y)));
            }
        }
        // Glowing items (power orbs and friends) sparkle on the same channel — same
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
                level.events.add(new com.bjsp123.rl2.event.GameEvent.LightMoteSpawn(
                        new com.bjsp123.rl2.model.Point(x, y)));
            }
        }
    }

    public static void updateVisibility(Level level) {
        int w = level.width, h = level.height;
        boolean[] blocking = buildBlocking(level, /*forLight=*/ false);
        boolean[] accum = new boolean[w * h];
        boolean[] temp  = new boolean[w * h];

        for (Mob mob : level.mobs) {
            if (mob.behavior != Behavior.PLAYER) continue;
            int cx = mob.position.tileX();
            int cy = mob.position.tileY();
            if (cx < 0 || cy < 0 || cx >= w || cy >= h) continue;
            ShadowCaster.castShadow(cx, cy, w, temp, blocking,
                    (int) Math.ceil(mob.effectiveStats().visionRadius));
            for (int i = 0; i < accum.length; i++) if (temp[i]) accum[i] = true;
        }

        writeBack(level, level.visible, accum);
        propagateToWalls(level, level.visible);
        // ESP override — if the player carries the buff, every mob's tile becomes
        // visible regardless of FOV. Doesn't reveal terrain, just bodies; the renderer
        // pairs this with explored=true for those cells so the mob sprite doesn't render
        // over an unexplored void.
        for (Mob mob : level.mobs) {
            if (mob.behavior != Behavior.PLAYER) continue;
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
     * block — that's the static "scenery is opaque" layer. Mobs and tree-canopy vegetation
     * additionally block <i>light</i> propagation but not <i>sight</i>: lamps don't shine
     * through trees or past a mob, but the player can still spot what's behind them as long
     * as there's some other light source. {@code forLight} switches between the two:
     * {@code true} for the lighting pass (mobs + trees on), {@code false} for the FOV pass
     * (mobs + trees off). A closed door is opened up for both passes if a mob is standing
     * on it.
     *
     * <p>The 1-cell border of the map is forced to "blocks" — normal levels already have
     * walls there, but {@link com.bjsp123.rl2.model.Level.LevelFlag#WALKWAY_LEVEL} can leave
     * CHASM at the edge with no adjacent FLOOR to wall it in, and the shadowcaster has no
     * bounds check, so an unblocked edge lets it index past the array end and the
     * try/catch wipes the FOV.
     *
     * <p>The shadowcaster sets the source cell visible before scanning, so a mob's own tile
     * being flagged "blocks" is harmless — the bit only matters when light tries to pass
     * <i>through</i> that cell to a farther one.
     *
     * <p>Package-accessible so {@link LevelUtilities#getLineOfSight} can reuse the same
     * bitmap rules for ad-hoc LOS queries — sight queries should pass {@code forLight=false}.
     */
    static boolean[] buildBlocking(Level level, boolean forLight) {
        int w = level.width, h = level.height;
        boolean[] blocking = new boolean[w * h];
        // Map of mob positions for O(1) lookups while we paint the grid. Track LARGE mobs
        // separately because they block both sight AND light, where small/medium mobs only
        // block light.
        boolean[] hasMob = new boolean[w * h];
        boolean[] hasLargeMob = new boolean[w * h];
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
                // light too) regardless of whatever the underlying tile would do — wins over
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
                }
                // Smoke clouds block both sight and light — opaque to FOV
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

    private static void writeBack(Level level, boolean[][] out, boolean[] src) {
        int w = level.width, h = level.height;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                out[x][y] = src[y * w + x];
    }

    /**
     * Mark walls adjacent to a flag-true NON-WALL 8-neighbor as flag-true too. This promotes
     * just the first ring of walls bordering the visible floor area (the only walls the player
     * is looking at directly). We intentionally do NOT propagate through walls — that extra ring
     * reveals the outer layer of wall masses between rooms, which the player shouldn't see.
     */
    private static void propagateToWalls(Level level, boolean[][] flag) {
        boolean[][] add = new boolean[level.width][level.height];
        for (int x = 0; x < level.width; x++) {
            for (int y = 0; y < level.height; y++) {
                if (!level.tiles[x][y].blocksSight()) continue;
                if (flag[x][y]) continue;
                for (int dx = -1; dx <= 1 && !add[x][y]; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        if (dx == 0 && dy == 0) continue;
                        int nx = x + dx, ny = y + dy;
                        if (nx < 0 || ny < 0 || nx >= level.width || ny >= level.height) continue;
                        if (level.tiles[nx][ny].blocksSight()) continue;
                        if (flag[nx][ny]) { add[x][y] = true; break; }
                    }
                }
            }
        }
        for (int x = 0; x < level.width; x++)
            for (int y = 0; y < level.height; y++)
                if (add[x][y]) flag[x][y] = true;
    }
}
