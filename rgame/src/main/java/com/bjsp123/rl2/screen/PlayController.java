package com.bjsp123.rl2.screen;

import com.bjsp123.rl2.event.GameEvent;
import com.bjsp123.rl2.logic.EventLog;
import com.bjsp123.rl2.logic.GameBalance;
import com.bjsp123.rl2.logic.ItemSystem;
import com.bjsp123.rl2.logic.LevelSystem;
import com.bjsp123.rl2.logic.Messages;
import com.bjsp123.rl2.logic.MobSystem;
import com.bjsp123.rl2.logic.TurnSystem;
import com.bjsp123.rl2.model.HallOfFameEntry;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Item.ItemSlot;
import com.bjsp123.rl2.model.Item.ItemType;
import com.bjsp123.rl2.model.Item.UseBehavior;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.LogEvent;
import com.bjsp123.rl2.model.MinMax;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Mob.CharacterClass;
import com.bjsp123.rl2.model.Perk;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.Tile;
import com.bjsp123.rl2.model.World;
import com.bjsp123.rl2.model.WorldTopology;
import com.bjsp123.rl2.ui.hud.ActionBar;
import com.bjsp123.rl2.ui.overlay.TargetingOverlay;
import com.bjsp123.rl2.world.anim.Animator;
import com.bjsp123.rl2.world.render.LevelRenderer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Gameplay action layer for {@link PlayScreen}. Owns everything between "user pressed
 * a button" and "an event lands in {@code level.events}": the per-tick advance loop,
 * the action-slot dispatcher, ability-firing flows (magic missile / wand / throw),
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

    private static final Random MISSILE_RNG = new Random();

    /** Auto-move interrupt — set of hostile mobs visible at the start of the current
     *  auto-path. While auto-pathing, any newly-visible hostile (not in this set) aborts
     *  the path. {@code null} when no auto-path is in progress. */
    private Set<Mob> autoMoveSnapshotHostiles;
    /** Auto-move interrupt — player HP at the start of the most recent step. If HP drops
     *  between steps, the path aborts. {@code -1} when no auto-path is in progress. */
    private double autoMoveLastHp = -1;

    PlayController(World world,
                   Animator animator,
                   ActionBar actionBar,
                   TargetingOverlay targetingOverlay,
                   LevelRenderer levelRenderer,
                   Runnable recenterCamera) {
        this.world = world;
        this.animator = animator;
        this.actionBar = actionBar;
        this.targetingOverlay = targetingOverlay;
        this.levelRenderer = levelRenderer;
        this.recenterCamera = recenterCamera;
    }

    boolean tick(Level level) {
        if (TurnSystem.isPlayerTurn(level)) {
            Mob player = TurnSystem.getActivePlayer(level);
            if (player == null || player.targetPosition == null) return false;
            if (shouldInterruptAutoMove(level, player)) {
                player.targetPosition = null;
                autoMoveSnapshotHostiles = null;
                autoMoveLastHp = -1;
                return false;
            }
            MobSystem.stepTowardTarget(player, level);
        }
        int safety = TurnSystem.STANDARD_TURN_TICKS * 4;
        while (safety-- > 0 && !TurnSystem.isPlayerTurn(level)) {
            if (!TurnSystem.tick(level)) break;
            world.tick++;
            if (animator.queue.freezeFrames > 0) break;
        }
        afterMove(level);
        Mob player = TurnSystem.findPlayer(level);
        if (player != null && player.targetPosition == null) {
            autoMoveSnapshotHostiles = null;
            autoMoveLastHp = -1;
        }
        return true;
    }

    void afterMove(Level level) {
        LevelSystem.computeLighting(level);
        LevelSystem.updateVisibility(level);
        auditActionSlots(TurnSystem.findPlayer(level));
    }

    void auditActionSlots(Mob player) {
        if (actionBar != null) actionBar.audit(player);
    }

    /**
     * HUD action-button handler. Resolves the bound item on the current player and
     * dispatches on {@link UseBehavior}.
     *
     * <p>Re-tap semantics when targeting is already open: same item → confirm; different
     * item → cancel old, start new for the new item.
     */
    void triggerActionSlot(int slotIndex) {
        Level level = world.currentLevel();
        Mob player = TurnSystem.findPlayer(level);
        if (player == null || actionBar == null) return;
        if (slotIndex < 0 || slotIndex >= actionBar.size()) return;
        Item bound = actionBar.get(slotIndex);
        if (bound == null) return;
        if (animator.queue.freezeFrames > 0) return;

        if (targetingOverlay.isActive()) {
            if (targetingOverlay.sourceKey() == bound) {
                targetingOverlay.confirm();
                return;
            }
            targetingOverlay.cancel();
        }

        if (!TurnSystem.isPlayerTurn(level)) return;

        UseBehavior ub = bound.useBehavior == null ? UseBehavior.NONE : bound.useBehavior;
        if (ub == UseBehavior.NONE) {
            beginThrow(player, bound);
            return;
        }
        switch (ub) {
            case MAGIC_MISSILE -> beginMagicMissile(level, player, bound);
            case EAT -> {
                ItemSystem.eat(player, bound);
                TurnSystem.applyMoveCost(player, player.effectiveStats().moveCost);
                afterMove(level);
            }
            case HEAL -> {
                MobSystem.heal(level, player, bound.healAmount);
                MobSystem.removeFromInventoryPublic(player, bound);
                TurnSystem.applyMoveCost(player, player.effectiveStats().moveCost);
                afterMove(level);
            }
            case WAND -> beginWand(level, player, bound);
            case NONE -> { /* handled above */ }
        }
    }

    private void beginMagicMissile(Level level, Mob caster, Item source) {
        targetingOverlay.setPlayer(caster);
        targetingOverlay.setLevel(level);
        targetingOverlay.activate(target -> fireMagicMissile(level, caster, target, source),
                                  source);
    }

    private void fireMagicMissile(Level level, Mob caster, Point target, Item source) {
        int dmg;
        if (source != null) {
            // WANDMASTER perk bumps the source's effective level by 1 for damage rolls.
            boolean wandmaster = caster.perks != null
                    && caster.perks.getOrDefault(Perk.WANDMASTER, 0) > 0;
            MinMax r;
            if (wandmaster) {
                int origLvl = source.level;
                source.level = origLvl + 1;
                r = ItemSystem.effectiveWandDamageRange(source);
                source.level = origLvl;
            } else {
                r = ItemSystem.effectiveWandDamageRange(source);
            }
            dmg = r.max() > r.min()
                    ? r.min() + MISSILE_RNG.nextInt(r.max() - r.min() + 1)
                    : r.min();
        } else {
            dmg = GameBalance.MAGIC_MISSILE_DAMAGE;
        }
        boolean trajectoryVisible = MobSystem.trajectoryTouchesVisible(level, caster.position, target);
        level.events.add(new GameEvent.MagicMissileFired(
                caster, caster.position, target, dmg, trajectoryVisible));
        TurnSystem.applyMoveCost(caster, caster.effectiveStats().moveCost);
    }

    /** Apply the inventory popup's "Use" action. EAT resolves immediately; MAGIC_MISSILE
     *  hands off to the targeting overlay (which is now visible because tryUse closed the
     *  inventory before invoking this callback). */
    void useItemFromInventory(Mob user, Item item) {
        if (user == null || item == null || item.useBehavior == null) return;
        Level level = world.currentLevel();
        if (!TurnSystem.isPlayerTurn(level)) return;
        switch (item.useBehavior) {
            case EAT -> {
                ItemSystem.eat(level, user, item);
                TurnSystem.applyMoveCost(user, user.effectiveStats().moveCost);
                afterMove(level);
            }
            case MAGIC_MISSILE -> beginMagicMissile(level, user, item);
            case HEAL -> {
                int amount = ItemSystem.effectiveHealAmount(item);
                MobSystem.heal(level, user, amount);
                MobSystem.removeFromInventoryPublic(user, item);
                TurnSystem.applyMoveCost(user, user.effectiveStats().moveCost);
                afterMove(level);
            }
            case DRINK -> {
                ItemSystem.drinkPotion(level, user, item);
                TurnSystem.applyMoveCost(user, user.effectiveStats().moveCost);
                afterMove(level);
            }
            case WAND -> beginWand(level, user, item);
            case GRANT_PERK -> {
                ItemSystem.grantPerk(level, user, item);
                TurnSystem.applyMoveCost(user, user.effectiveStats().moveCost);
                afterMove(level);
            }
            case NONE -> { /* unreachable — the popup gates Use on isUsable() */ }
        }
    }

    /** Wand use entry point. Summon-style wands (non-null
     *  {@link Item#summonsWhenUsed}) summon immediately with no targeting; tile-
     *  targeting wands open the targeting overlay and emit the projectile event
     *  on confirm. The Animator translates the event into a coloured missile /
     *  ray and fires the impact callback on the final frame. */
    private void beginWand(Level level, Mob user, Item wand) {
        if (wand.summonsWhenUsed != null) {
            // Always burns a turn — the room-check / no-spot fallback inside
            // castSummonWand silently no-ops the spawn but the user still pays.
            ItemSystem.castSummonWand(level, user, wand);
            TurnSystem.applyMoveCost(user, user.effectiveStats().attackCost);
            afterMove(level);
            return;
        }
        targetingOverlay.setPlayer(user);
        targetingOverlay.setLevel(level);
        targetingOverlay.activate(target -> {
            Level cur = world.currentLevel();
            boolean trajectoryVisible =
                    MobSystem.trajectoryTouchesVisible(cur, user.position, target);
            int effLvl = wand.level
                    + (user.perks != null
                            && user.perks.getOrDefault(Perk.WANDMASTER, 0) > 0
                            ? 1 : 0);
            if (wand.wandElement == Item.WandElement.BANISHMENT) {
                cur.events.add(new GameEvent.WandRayFired(
                        user, user.position, target, wand.wandElement, effLvl,
                        trajectoryVisible));
            } else {
                cur.events.add(new GameEvent.WandMissileFired(
                        user, user.position, target, wand.wandElement, effLvl,
                        trajectoryVisible));
            }
            TurnSystem.applyMoveCost(user, user.effectiveStats().attackCost);
        }, wand);
    }

    /** Kick off target-picking for a throw action from the inventory popup. */
    void beginThrow(Mob thrower, Item item) {
        if (thrower == null || item == null) return;
        targetingOverlay.setPlayer(thrower);
        targetingOverlay.setLevel(world.currentLevel());
        targetingOverlay.activate(target -> {
            Level cur = world.currentLevel();
            MobSystem.throwItem(cur, thrower, item, target);
            afterMove(cur);
        }, item);
    }

    void cancelThrow() {
        if (targetingOverlay != null) targetingOverlay.cancel();
    }

    HallOfFameEntry snapshotOf(Mob p) {
        List<String> equipment = new ArrayList<>();
        for (ItemSlot s : ItemSlot.values()) {
            Item it = p.inventory.equipped(s);
            if (it != null) equipment.add(s.name() + ": " + it.describe());
        }
        return new HallOfFameEntry(
                p.characterClass != null ? p.characterClass.displayName : "Adventurer",
                p.score, world.currentLevel().depth, equipment, System.currentTimeMillis());
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
                    "Path interrupted: you took damage.",
                    LogEvent.EventPriority.HIGH, true));
            return true;
        }
        for (Mob m : visibleHostiles) {
            if (!autoMoveSnapshotHostiles.contains(m)) {
                EventLog.add(new LogEvent(
                        "Path interrupted: a " + (m.name == null ? "creature" : m.name)
                                + " comes into view.",
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
            if (MobSystem.getAttitudeToMob(player, m) == MobSystem.Attitude.ALLY) continue;
            out.add(m);
        }
        return out;
    }

    /** Seed the HUD action bar with the class-default bindings on a brand-new run. */
    void seedDefaultActionBar(Mob player, CharacterClass cls) {
        if (player == null || actionBar == null) return;
        actionBar.set(2, firstOfType(player, ItemType.HEALING_POTION));
        if (cls == null) return;
        switch (cls) {
            case WARRIOR -> { /* sword + scale mail equipped; no quickslot bindings */ }
            case ROGUE -> {
                actionBar.set(0, firstOfType(player, ItemType.FIRE_BOMB));
                actionBar.set(1, firstOfType(player, ItemType.OIL_BOMB));
            }
            case MAGE -> {
                actionBar.set(0, firstEquipped(player, ItemType.DAGGER));
                actionBar.set(1, firstOfType(player, ItemType.WAND_DOG));
            }
        }
    }

    private static Item firstOfType(Mob player, ItemType type) {
        for (Item it : player.inventory.bag) if (it.type == type) return it;
        return null;
    }

    private static Item firstEquipped(Mob player, ItemType type) {
        for (ItemSlot s : ItemSlot.values()) {
            Item eq = player.inventory.equipped(s);
            if (eq != null && eq.type == type) return eq;
        }
        return firstOfType(player, type);
    }

    void tryStairsUp()   { tryStairs(-1); }
    void tryStairsDown() { tryStairs(+1); }

    /**
     * Use the tile the player is standing on: stairs → go up/down; otherwise wait one full
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
     *  are never overwritten — full action bar means no auto-assign. */
    private void autoAssignNewPickups(Mob player, int bagBefore) {
        if (actionBar == null || player == null) return;
        List<Item> bag = player.inventory.bag;
        for (int i = bagBefore; i < bag.size(); i++) {
            Item it = bag.get(i);
            if (it == null) continue;
            boolean usable = it.useBehavior != null && it.useBehavior != UseBehavior.NONE;
            boolean throwable = it.thrownBehavior != null
                    && it.thrownBehavior != Item.ThrownBehavior.NOTHING;
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

        String playerName = player.name != null ? player.name : "Adventurer";
        EventLog.add(Messages.enterLevel(playerName, next.depth, next.flags));
    }
}
