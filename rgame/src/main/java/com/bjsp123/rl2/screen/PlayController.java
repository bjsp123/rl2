package com.bjsp123.rl2.screen;

import com.bjsp123.rl2.logic.EventLog;
import com.bjsp123.rl2.logic.ItemSystem;
import com.bjsp123.rl2.logic.LevelSystem;
import com.bjsp123.rl2.logic.Messages;
import com.bjsp123.rl2.logic.MobQueries;
import com.bjsp123.rl2.logic.MobSystem;
import com.bjsp123.rl2.logic.TurnSystem;
import com.bjsp123.rl2.model.HallOfFameEntry;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Item.UseBehavior;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.LogEvent;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Mob.CharacterClass;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.Tile;
import com.bjsp123.rl2.model.World;
import com.bjsp123.rl2.model.WorldTopology;
import com.bjsp123.rl2.ui.hud.ActionBar;
import com.bjsp123.rl2.ui.overlay.TargetingOverlay;
import com.bjsp123.rl2.world.anim.Animator;
import com.bjsp123.rl2.world.render.LevelRenderer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gameplay action layer for {@link PlayScreen}. Owns everything between "user pressed
 * a button" and "an event lands in {@code level.events}": the per-tick advance loop,
 * the action-slot dispatcher, ability-firing flows (wand / throw),
 * inventory item use, stair traversal, the wait/pickup/interact tile action, the
 * auto-move snapshot + interrupt gate, action-bar bookkeeping (audit + auto-assign +
 * default seeding), and the player snapshot used for hall-of-fame entries.
 *
 * <p>{@link PlayScreen} keeps the lifecycle (create / show / persist / dispose) and the
 * per-frame render orchestration; this controller is constructed by {@code initialize()}
 * and held as a field. {@code GameInput} callbacks bind directly to the methods here.
 */
final class PlayController {

    private final World world;
    private final Animator animator;
    private final ActionBar actionBar;
    private final TargetingOverlay targetingOverlay;
    private final LevelRenderer levelRenderer;
    private final Runnable recenterCamera;
    private final FrameProfiler profiler;

    /** Auto-move interrupt - set of hostile mobs visible at the start of the current
     *  auto-path. While auto-pathing, any newly-visible hostile (not in this set) aborts
     *  the path. {@code null} when no auto-path is in progress. */
    private Set<Mob> autoMoveSnapshotHostiles;
    /** Auto-move interrupt - player HP at the start of the most recent step. If HP drops
     *  between steps, the path aborts. {@code -1} when no auto-path is in progress. */
    private double autoMoveLastHp = -1;

    PlayController(World world,
                   Animator animator,
                   ActionBar actionBar,
                   TargetingOverlay targetingOverlay,
                   LevelRenderer levelRenderer,
                   Runnable recenterCamera,
                   FrameProfiler profiler) {
        this.world = world;
        this.animator = animator;
        this.actionBar = actionBar;
        this.targetingOverlay = targetingOverlay;
        this.levelRenderer = levelRenderer;
        this.recenterCamera = recenterCamera;
        this.profiler = profiler;
    }

    boolean tick(Level level) {
        long tickStart = profiler.start();
        if (TurnSystem.isPlayerTurn(level)) {
            long playerTurnStart = profiler.start();
            Mob player = TurnSystem.getActivePlayer(level);
            if (player == null) return false;
            if (player.visibleMobsAtTurnStart == null) {
                MobSystem.snapshotVisibleMobsAtTurnStart(level, player);
            }
            if (player.targetPosition == null) return false;
            if (shouldInterruptAutoMove(level, player)) {
                player.targetPosition = null;
                autoMoveSnapshotHostiles = null;
                autoMoveLastHp = -1;
                return false;
            }
            MobSystem.stepTowardTarget(player, level);
            profiler.add("playerStep", playerTurnStart);
        }
        int safety = TurnSystem.STANDARD_TURN_TICKS * 4;
        // Drain any pre-existing sequential flag so the loop's break test
        // only reflects what THIS loop's ticks queue.
        animator.queue.consumeSequentialFlag();
        long turnLoopStart = profiler.start();
        while (safety-- > 0 && !TurnSystem.isPlayerTurn(level)) {
            if (!TurnSystem.tick(level)) break;
            world.tick++;
            // Drain events into the animator after every game tick so
            // the sequential flag reflects animations queued by THIS
            // tick - not just whatever was in flight at the start of
            // the render frame.
            animator.consume(level);
            // Only a SEQUENTIAL animation (lunge, knockback slide,
            // chained death-fade) breaks the loop - a pile of concurrent
            // mob slides should NOT, since they're meant to play
            // simultaneously with the player's slide. This lets many
            // mobs move on a single render frame and eliminates the
            // jerky one-mob-per-render-frame autotravel cadence.
            if (animator.queue.consumeSequentialFlag()) break;
        }
        profiler.add("turnLoop", turnLoopStart);
        afterMove(level);
        Mob player = TurnSystem.findPlayer(level);
        if (player != null && player.targetPosition == null) {
            autoMoveSnapshotHostiles = null;
            autoMoveLastHp = -1;
        }
        profiler.add("controllerTick", tickStart);
        return true;
    }

    void afterMove(Level level) {
        long start = profiler.start();
        long lightingStart = profiler.start();
        LevelSystem.computeLighting(level);
        profiler.add("computeLighting", lightingStart);
        long visStart = profiler.start();
        LevelSystem.updateVisibility(level);
        profiler.add("updateVisibility", visStart);
        long auditStart = profiler.start();
        auditActionSlots(TurnSystem.findPlayer(level));
        profiler.add("auditAfterMove", auditStart);
        profiler.add("afterMove", start);
    }

    void auditActionSlots(Mob player) {
        if (actionBar != null) actionBar.audit(player);
    }

    /**
     * HUD action-button handler. Resolves the bound item on the current player and
     * dispatches on {@link UseBehavior}.
     *
     * <p>Re-tap semantics when targeting is already open: same item -> confirm; different
     * item -> cancel old, start new for the new item.
     */
    void triggerActionSlot(int slotIndex) {
        Level level = world.currentLevel();
        Mob player = TurnSystem.findPlayer(level);
        if (player == null || actionBar == null) return;
        if (slotIndex < 0 || slotIndex >= actionBar.size()) return;
        Item bound = actionBar.get(slotIndex);
        if (bound == null) return;
        if (!com.bjsp123.rl2.ui.skin.Settings.instantActions() && animator.queue.freezeFrames > 0) return;
        if (!TurnSystem.isPlayerTurn(level)) return;

        if (targetingOverlay.isActive()) {
            if (targetingOverlay.sourceKey() == bound) {
                targetingOverlay.confirm();
                return;
            }
            targetingOverlay.cancel();
        }

        UseBehavior ub = bound.useBehavior == null ? UseBehavior.NONE : bound.useBehavior;
        if (ub == UseBehavior.NONE) {
            beginThrow(player, bound);
            return;
        }
        switch (ub) {
            case EAT, DRINK, GRANT_PERK, APPLYBUFF -> {
                ItemSystem.useItem(level, player, bound);
                afterMove(level);
            }
            case WAND    -> beginWand(level, player, bound);
            case GRAPPLE -> beginGrapple(level, player, bound);
            case JUMP    -> beginJump(level, player, bound);
            case NONE -> { /* handled above */ }
        }
    }

    /** Apply the inventory popup's "Use" action. Non-targeted use behaviours
     *  (eat / drink / grant-perk) route through the shared
     *  {@link ItemSystem#useItem} entry point; WAND hands off to the targeting
     *  overlay (now visible because tryUse closed the inventory before invoking
     *  this callback). */
    void useItemFromInventory(Mob user, Item item) {
        if (user == null || item == null || item.useBehavior == null) return;
        Level level = world.currentLevel();
        if (!TurnSystem.isPlayerTurn(level)) return;
        switch (item.useBehavior) {
            case EAT, DRINK, GRANT_PERK, APPLYBUFF -> {
                ItemSystem.useItem(level, user, item);
                afterMove(level);
            }
            case WAND    -> beginWand(level, user, item);
            case GRAPPLE -> beginGrapple(level, user, item);
            case JUMP    -> beginJump(level, user, item);
            case NONE -> { /* unreachable - the popup gates Use on isUsable() */ }
        }
    }

    /** Wand use entry point. Summon-style wands ({@code summonsWhenUsed != null})
     *  summon immediately with no targeting; tile-targeting wands open the
     *  targeting overlay. Both branches delegate to the shared
     *  {@link ItemSystem#fireWand} so target clipping, event emission, and the
     *  attack-cost charge are identical to the AI path. The animator translates
     *  the resulting event into a coloured missile / ray and fires the impact
     *  callback on the final frame. */
    private void beginWand(Level level, Mob user, Item wand) {
        if (wand.summonsWhenUsed != null) {
            ItemSystem.fireWand(level, user, wand, null);
            // Drain the MobSpawned event into the Animator now so the spawn-grow
            // freeze is in place before the next render frame's controller.tick
            // can let the new pet take its first turn - otherwise the dog acts
            // before its own spawn animation plays.
            animator.consume(level);
            // Force a renderer index rebuild - no game tick ran, so the mob-by-cell
            // cache wouldn't otherwise pick up the freshly-summoned pet and its
            // grow animation wouldn't play until the freeze finally drains.
            levelRenderer.markDirty();
            afterMove(level);
            return;
        }
        targetingOverlay.setPlayer(user);
        targetingOverlay.setLevel(level);
        targetingOverlay.setValidTiles(visibleGrid(level), level.width, level.height);
        targetingOverlay.activate(target -> {
            Level cur = world.currentLevel();
            ItemSystem.fireWand(cur, user, wand, target);
            // Drain the wand event into the Animator now so its visible-trajectory
            // freeze contribution is in place before the next render frame's
            // controller.tick gets a chance to run AI turns. Without this, the
            // projectile and the adjacent enemy's reaction would queue together
            // in the same consume() pass and animate concurrently.
            animator.consume(cur);
        }, wand);
    }

    /** Grappling-rope use entry point. Opens the targeting overlay (default
     *  reticle on the nearest hostile, same as wands), then routes the
     *  confirmed tile through {@link ItemSystem#castGrapple}. The animator
     *  drains the resulting {@link com.bjsp123.rl2.event.GameEvent.GrappleFired}
     *  and {@link com.bjsp123.rl2.event.GameEvent.MobKnockedBack} events
     *  immediately so the rope's extend phase queues before the next AI
     *  catch-up tick can run. */
    private void beginGrapple(Level level, Mob user, Item item) {
        targetingOverlay.setPlayer(user);
        targetingOverlay.setLevel(level);
        targetingOverlay.setValidTiles(grappleGrid(level, user, item), level.width, level.height);
        targetingOverlay.activate(target -> {
            Level cur = world.currentLevel();
            ItemSystem.castGrapple(cur, user, item, target);
            animator.consume(cur);
            afterMove(cur);
        }, item);
    }

    /** Jump use entry point. Opens the targeting overlay restricted to tiles within
     *  Chebyshev radius {@code item.abilityPower}, then routes the confirmed tile
     *  through {@link ItemSystem#castJump}. Non-blocking hop animation. */
    private void beginJump(Level level, Mob user, Item item) {
        targetingOverlay.setPlayer(user);
        targetingOverlay.setLevel(level);
        targetingOverlay.setValidTiles(jumpGrid(level, user, item), level.width, level.height);
        targetingOverlay.activate(target -> {
            Level cur = world.currentLevel();
            ItemSystem.castJump(cur, user, item, target);
            animator.consume(cur);
            afterMove(cur);
        }, item);
    }

    /** Kick off target-picking for a throw action from the inventory popup. */
    void beginThrow(Mob thrower, Item item) {
        if (thrower == null || item == null) return;
        Level level = world.currentLevel();
        targetingOverlay.setPlayer(thrower);
        targetingOverlay.setLevel(level);
        targetingOverlay.setValidTiles(throwGrid(level, thrower), level.width, level.height);
        targetingOverlay.activate(target -> {
            Level cur = world.currentLevel();
            MobSystem.throwItem(cur, thrower, item, target);
            afterMove(cur);
        }, item);
    }

    /** Grid of valid grapple targets: visible + within Chebyshev range {@code 2 + abilityPower}. */
    private static boolean[][] grappleGrid(Level level, Mob user, Item item) {
        boolean[][] grid = new boolean[level.width][level.height];
        if (user.position == null || level.visible == null) return grid;
        int radius = 2 + Math.max(0, (int) item.abilityPower);
        int px = user.position.tileX(), py = user.position.tileY();
        for (int x = Math.max(0, px - radius); x <= Math.min(level.width - 1, px + radius); x++) {
            for (int y = Math.max(0, py - radius); y <= Math.min(level.height - 1, py + radius); y++) {
                if (Math.max(Math.abs(x - px), Math.abs(y - py)) > radius) continue;
                grid[x][y] = level.visible[x][y];
            }
        }
        return grid;
    }

    /** Grid of valid throw targets: visible + within Chebyshev throw range (base + Hurler bonus). */
    private static boolean[][] throwGrid(Level level, Mob thrower) {
        boolean[][] grid = new boolean[level.width][level.height];
        if (thrower.position == null || level.visible == null) return grid;
        int range = com.bjsp123.rl2.logic.GameBalance.DEFAULT_THROW_RANGE;
        if (thrower.perks != null)
            range += thrower.perks.getOrDefault(com.bjsp123.rl2.model.Perk.HURLER, 0)
                     * com.bjsp123.rl2.logic.GameBalance.HURLER_RANGE_PER_LEVEL;
        int px = thrower.position.tileX(), py = thrower.position.tileY();
        for (int x = Math.max(0, px - range); x <= Math.min(level.width - 1, px + range); x++) {
            for (int y = Math.max(0, py - range); y <= Math.min(level.height - 1, py + range); y++) {
                if (Math.max(Math.abs(x - px), Math.abs(y - py)) > range) continue;
                grid[x][y] = level.visible[x][y];
            }
        }
        return grid;
    }

    /** Grid of tiles currently visible to the player - valid targets for wand/grapple/throw. */
    private static boolean[][] visibleGrid(Level level) {
        boolean[][] grid = new boolean[level.width][level.height];
        if (level.visible == null) return grid;
        for (int x = 0; x < level.width; x++)
            for (int y = 0; y < level.height; y++)
                grid[x][y] = level.visible[x][y];
        return grid;
    }

    /** Grid of valid jump destinations: within Chebyshev radius, passable, unoccupied. */
    private static boolean[][] jumpGrid(Level level, Mob jumper, Item item) {
        boolean[][] grid = new boolean[level.width][level.height];
        if (jumper.position == null) return grid;
        int radius = Math.max(0, (int) item.abilityPower);
        int px = jumper.position.tileX(), py = jumper.position.tileY();
        for (int x = Math.max(0, px - radius); x <= Math.min(level.width - 1, px + radius); x++) {
            for (int y = Math.max(0, py - radius); y <= Math.min(level.height - 1, py + radius); y++) {
                if (Math.max(Math.abs(x - px), Math.abs(y - py)) > radius) continue;
                if (level.tiles[x][y].blocksMovement()) continue;
                if (MobQueries.mobAt(level, new Point(x, y)) != null) continue;
                grid[x][y] = true;
            }
        }
        return grid;
    }

    void cancelThrow() {
        if (targetingOverlay != null) targetingOverlay.cancel();
    }

    HallOfFameEntry snapshotOf(Mob p) {
        List<String> equipment = new ArrayList<>();
        for (Item it : p.inventory.allEquipped()) {
            String cat = it.inventoryCategory != null ? it.inventoryCategory.name()
                    : com.bjsp123.rl2.logic.TextCatalog.get("eventlog.fallback.equipped");
            equipment.add(cat + ": " + it.describe());
        }
        HallOfFameEntry entry = new HallOfFameEntry(
                p.characterClass != null ? p.characterClass.displayName()
                        : com.bjsp123.rl2.logic.TextCatalog.get("eventlog.fallback.adventurer"),
                p.characterLevel,
                p.score, world.currentLevel().depth, equipment, System.currentTimeMillis());
        entry.totalTurns  = TurnSystem.standardTurnForTick(world.tick);
        entry.beastsTamed = p.beastsTamed;
        entry.favPerk     = favPerkOf(p);
        return entry;
    }

    private static String favPerkOf(Mob p) {
        if (p.perks == null || p.perks.isEmpty()) return "";
        return p.perks.entrySet().stream()
                .max(Comparator.comparingInt(Map.Entry::getValue))
                .map(e -> e.getKey().name()).orElse("");
    }

    /** Auto-move abort gate. Snapshots once then compares each tick. */
    private boolean shouldInterruptAutoMove(Level level, Mob player) {
        Set<Mob> visibleHostiles = currentlyVisibleHostiles(level, player);
        if (autoMoveSnapshotHostiles == null) {
            autoMoveSnapshotHostiles = visibleHostiles;
            autoMoveLastHp = player.hp;
            return false;
        }
        if (player.hp < autoMoveLastHp) {
            EventLog.add(new LogEvent(
                    com.bjsp123.rl2.logic.TextCatalog.get("eventlog.path.damage"),
                    LogEvent.EventPriority.HIGH, true));
            return true;
        }
        for (Mob m : visibleHostiles) {
            if (!autoMoveSnapshotHostiles.contains(m)) {
                EventLog.add(new LogEvent(
                        com.bjsp123.rl2.logic.TextCatalog.format("eventlog.path.hostileAppears",
                                com.bjsp123.rl2.logic.TextCatalog.vars("mob",
                                        m.name == null
                                                ? com.bjsp123.rl2.logic.TextCatalog.get("eventlog.fallback.creature")
                                                : m.name)),
                        LogEvent.EventPriority.HIGH, true));
                return true;
            }
        }
        autoMoveLastHp = player.hp;
        return false;
    }

    private static Set<Mob> currentlyVisibleHostiles(Level level, Mob player) {
        Set<Mob> out = new HashSet<>();
        if (level.mobs == null || level.visible == null) return out;
        for (Mob m : level.mobs) {
            if (m == player || m.position == null) continue;
            int mx = m.position.tileX(), my = m.position.tileY();
            if (mx < 0 || my < 0 || mx >= level.width || my >= level.height) continue;
            if (!level.visible[mx][my]) continue;
            // Only true hostiles (ATTACK attitude) interrupt auto-move.
            // Neutrals (NOTHING, FLEE) and allies are ignored - wandering
            // past a passive mob shouldn't break a long path.
            if (MobSystem.getAttitudeToMob(player, m) != MobSystem.Attitude.ATTACK) continue;
            out.add(m);
        }
        return out;
    }

    /** Seed the HUD action bar with the class-default bindings on a brand-new run.
     *  Bindings come from the {@code actionBar} cell of the player's row in
     *  {@code mobs.csv} - pipe-separated {@code <slotIndex>:<itemType>} entries.
     *  Each entry resolves against the player's current inventory; entries whose
     *  item isn't carried are skipped. */
    void seedDefaultActionBar(Mob player, CharacterClass cls) {
        if (player == null || actionBar == null || cls == null) return;
        com.bjsp123.rl2.logic.MobDefinition def =
                com.bjsp123.rl2.logic.Registries.mob("PLAYER_" + cls.name());
        if (def == null || def.actionBar == null || def.actionBar.isEmpty()) return;
        for (String entry : def.actionBar.split("\\|")) {
            String e = entry.trim();
            int colon = e.indexOf(':');
            if (colon <= 0) continue;
            int slot;
            try { slot = Integer.parseInt(e.substring(0, colon).trim()); }
            catch (NumberFormatException nfe) { continue; }
            String type = e.substring(colon + 1).trim();
            actionBar.set(slot, firstByType(player, type));
        }
    }

    private static Item firstByType(Mob player, String type) {
        if (type == null) return null;
        // Equipped items first so an equipped sword binds over a bag duplicate.
        for (Item eq : player.inventory.allEquipped()) {
            if (type.equals(eq.type)) return eq;
        }
        for (Item it : player.inventory.bag) if (type.equals(it.type)) return it;
        return null;
    }

    void tryStairsUp()   { tryStairs(-1); }
    void tryStairsDown() { tryStairs(+1); }

    /**
     * Use the tile the player is standing on: stairs -> go up/down; otherwise wait one full
     * move-cost worth of ticks so the rest of the level (AI, vegetation, effects) advances
     * without the player moving. Triggered by SPACE or by tapping the player's own tile.
     */
    void tryInteract() {
        Level cur = world.currentLevel();
        Mob player = TurnSystem.findPlayer(cur);
        if (player == null) return;
        if (!TurnSystem.isPlayerTurn(cur)) return;

        int bagBefore = player.inventory.bag.size();
        if (MobSystem.pickupAtFeet(cur, player) > 0) {
            autoAssignNewPickups(player, bagBefore);
            TurnSystem.applyMoveCost(player, player.effectiveStats().moveCost);
            return;
        }
        Tile here = cur.tiles[player.position.tileX()][player.position.tileY()];
        if (here == Tile.STAIRS_UP)   { tryStairsUp();   return; }
        if (here == Tile.STAIRS_DOWN) { tryStairsDown(); return; }
        TurnSystem.applyMoveCost(player, player.effectiveStats().moveCost);
    }

    /** After {@link MobSystem#pickupAtFeet} adds items to the bag, walk the new tail
     *  entries (everything added after {@code bagBefore}) and bind each usable /
     *  throwable item to the first empty action-bar slot. Items that are already in a
     *  quickslot, or that have no use / throw behaviour, are skipped. Existing bindings
     *  are never overwritten - full action bar means no auto-assign. */
    private void autoAssignNewPickups(Mob player, int bagBefore) {
        if (actionBar == null || player == null) return;
        List<Item> bag = player.inventory.bag;
        for (int i = bagBefore; i < bag.size(); i++) {
            Item it = bag.get(i);
            if (it == null) continue;
            boolean usable = it.useBehavior != null && it.useBehavior != UseBehavior.NONE;
            boolean throwable = it.throwEffect != null;
            if (!usable && !throwable) continue;
            boolean alreadyBound = false;
            for (int s = 0; s < actionBar.size(); s++) {
                if (actionBar.get(s) == it) { alreadyBound = true; break; }
            }
            if (alreadyBound) continue;
            for (int s = 0; s < actionBar.size(); s++) {
                if (actionBar.get(s) == null) {
                    actionBar.set(s, it);
                    break;
                }
            }
        }
    }

    private void tryStairs(int direction) {
        Level cur = world.currentLevel();
        Mob player = TurnSystem.findPlayer(cur);
        if (player == null) return;

        Tile here = cur.tiles[player.position.tileX()][player.position.tileY()];
        if (direction < 0 && here != Tile.STAIRS_UP)   return;
        if (direction > 0 && here != Tile.STAIRS_DOWN) return;

        int srcIdx = world.currentLevelIndex;
        int target;
        if (direction > 0) {
            target = player.position.equals(cur.stairsDown)
                    ? cur.stairsDownTarget
                    : cur.stairsDownAltTarget;
        } else {
            target = player.position.equals(cur.stairsUp)
                    ? cur.stairsUpTarget
                    : cur.stairsUpAltTarget;
        }
        if (target < 0 || target >= world.levels.length) return;
        Level next = world.levels[target];
        Point dest = WorldTopology.arrivalPointFrom(next, srcIdx, direction > 0);
        if (dest == null) return;

        cur.mobs.remove(player);
        player.position       = dest;
        player.targetPosition = null;
        next.mobs.add(player);
        world.currentLevelIndex = target;
        next.visited = true;

        TurnSystem.applyMoveCost(player, player.effectiveStats().moveCost);
        afterMove(next);
        recenterCamera.run();
        levelRenderer.markDirty();

        String playerName = player.name != null ? player.name
                : com.bjsp123.rl2.logic.TextCatalog.get("eventlog.fallback.adventurer");
        EventLog.add(Messages.enterLevel(playerName, next.depth, next.flags));
    }
}
