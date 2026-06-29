package com.bjsp123.rl2.screen;

import com.bjsp123.rl2.logic.EventLog;
import com.bjsp123.rl2.logic.GemSystem;
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
import com.bjsp123.rl2.audio.SoundManager;
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
    private final SoundManager sounds;

    /** Auto-move interrupt - set of hostile mobs visible at the start of the current
     *  auto-path. While auto-pathing, any newly-visible hostile (not in this set) aborts
     *  the path. {@code null} when no auto-path is in progress. */
    private Set<Mob> autoMoveSnapshotHostiles;
    /** Auto-move interrupt - player HP at the start of the most recent step. If HP drops
     *  between steps, the path aborts. {@code -1} when no auto-path is in progress. */
    private double autoMoveLastHp = -1;

    /** RL-53: auto-explore active. While on, each {@link #tick} drives one SMART
     *  explore/pickup step for the player; stops on any visible hostile, damage,
     *  or when nothing is left to explore. Never descends. */
    private boolean autoExplore;

    /** Toggle auto-explore. Cancels any manual auto-move and primes the HP
     *  baseline so the first step doesn't false-trip the damage interrupt. */
    public void toggleAutoExplore() {
        autoExplore = !autoExplore;
        autoMoveLastHp = -1;
        if (autoExplore) {
            Mob p = TurnSystem.findPlayer(world.currentLevel());
            if (p != null) p.targetPosition = null;   // can't auto-explore mid auto-move
        }
    }

    public boolean isAutoExploring() { return autoExplore; }

    /** Cancel both auto-explore and any in-progress auto-travel. Wired to
     *  GameInput so any key press or screen tap interrupts them. */
    public void cancelAutoActions() {
        stopAutoExplore();
        Mob player = TurnSystem.findPlayer(world.currentLevel());
        if (player != null) player.targetPosition = null;
        autoMoveSnapshotHostiles = null;
        autoMoveLastHp = -1;
    }

    private void stopAutoExplore() {
        autoExplore = false;
        autoMoveLastHp = -1;
    }

    /** Hand-back test for auto-explore: stop on ANY visible hostile or on damage. */
    private boolean autoExploreShouldStop(Level level, Mob player) {
        Set<Mob> vis = currentlyVisibleHostiles(level, player);
        if (!vis.isEmpty()) {
            Mob m = vis.iterator().next();
            String name = m.name != null ? m.name : m.mobType;
            EventLog.add(new LogEvent(
                    com.bjsp123.rl2.logic.TextCatalog.format("eventlog.path.hostileAppears",
                            com.bjsp123.rl2.logic.TextCatalog.vars("mob", name)),
                    LogEvent.EventPriority.HIGH, true));
            return true;
        }
        if (autoMoveLastHp >= 0 && player.hp < autoMoveLastHp) {
            EventLog.add(new LogEvent(
                    com.bjsp123.rl2.logic.TextCatalog.get("eventlog.path.damage"),
                    LogEvent.EventPriority.HIGH, true));
            return true;
        }
        autoMoveLastHp = player.hp;
        return false;
    }

    /** Callback to push the world-map screen from the controller. Set by
     *  {@link PlayScreen} after the screen graph is wired so the controller
     *  can request map-open in response to using a teleport-orb item
     *  without taking a hard dependency on {@code Rl2Game}. */
    private Runnable openMapScreen;
    /** Wire the map-screen-open callback. */
    public void setOpenMapScreen(Runnable r) { this.openMapScreen = r; }

    /** Callback to push the gem-forge screen when the player interacts beside a
     *  GEM_HEARTH tile. Wired by {@link PlayScreen} so the controller can open
     *  the forge without a hard dependency on the UI layer. */
    private Runnable openForgeScreen;
    /** Wire the forge-screen-open callback. */
    public void setOpenForgeScreen(Runnable r) { this.openForgeScreen = r; }

    /** Opens the inventory as a target chooser for item-targeting scrolls
     *  (enchant brands / level-ups). Wired by {@link PlayScreen} to
     *  {@code V2Inventory.openPicker} so the controller stays free of a hard
     *  dependency on the UI layer. */
    public interface ItemPicker {
        void open(java.util.function.Predicate<Item> eligible,
                  java.util.function.BiConsumer<Mob, Item> onPick,
                  Runnable onCancel);
    }
    private ItemPicker itemPicker;
    /** Wire the inventory item-picker callback. */
    public void setItemPicker(ItemPicker p) { this.itemPicker = p; }

    PlayController(World world,
                   Animator animator,
                   ActionBar actionBar,
                   TargetingOverlay targetingOverlay,
                   LevelRenderer levelRenderer,
                   Runnable recenterCamera,
                   FrameProfiler profiler,
                   SoundManager sounds) {
        this.world = world;
        this.animator = animator;
        this.actionBar = actionBar;
        this.targetingOverlay = targetingOverlay;
        this.levelRenderer = levelRenderer;
        this.recenterCamera = recenterCamera;
        this.profiler = profiler;
        this.sounds = sounds;
    }

    boolean tick(Level level) {
        long tickStart = profiler.start();
        if (TurnSystem.isPlayerTurn(level)) {
            long playerTurnStart = profiler.start();
            Mob player = TurnSystem.getActivePlayer(level);
            if (player == null) return false;
            if (player.visibleMobsAtTurnStart == null) {
                // First player turn on this level — prime the effectiveStats cache for
                // every mob so the imminent afterMove/computeLighting/buildBlocking pass
                // doesn't pay N recomputes for sleeping mobs that skipped the AI path.
                for (Mob mob : level.mobs) mob.effectiveStats();
                MobSystem.snapshotVisibleMobsAtTurnStart(level, player);
            }
            if (autoExplore) {
                // RL-53: drive one SMART explore/pickup step. Stops the instant
                // a hostile is visible or the player takes damage, or when the
                // planner has nothing left to explore (hands back, never descends).
                if (autoExploreShouldStop(level, player)) { stopAutoExplore(); return false; }
                com.bjsp123.rl2.logic.AutoExplore.Driver drv = com.bjsp123.rl2.logic.AutoExplore.get();
                com.bjsp123.rl2.logic.AutoExplore.Result res = drv == null
                        ? com.bjsp123.rl2.logic.AutoExplore.Result.DONE_EXPLORED
                        : drv.step(player, level);
                if (res != com.bjsp123.rl2.logic.AutoExplore.Result.STEPPED) {
                    // Only claim the level is explored when that's actually why we
                    // stopped; a BUSY hand-back (heal, etc.) stops quietly.
                    if (res == com.bjsp123.rl2.logic.AutoExplore.Result.DONE_EXPLORED) {
                        EventLog.add(new LogEvent(
                                com.bjsp123.rl2.logic.TextCatalog.get("eventlog.path.explored"),
                                LogEvent.EventPriority.LOW, true));
                    }
                    stopAutoExplore();
                    return false;
                }
                // stepped — fall through to run enemy turns below.
            } else {
                if (player.targetPosition == null) return false;
                if (shouldInterruptAutoMove(level, player)) {
                    player.targetPosition = null;
                    autoMoveSnapshotHostiles = null;
                    autoMoveLastHp = -1;
                    return false;
                }
                MobSystem.stepTowardTarget(player, level);
                if (player.targetPosition == null && player.ticksTillMove == 0
                        && currentlyVisibleHostiles(level, player).isEmpty()) {
                    int bagBefore = player.inventory.bag.size();
                    if (MobSystem.pickupAtFeet(level, player) > 0) {
                        autoAssignNewPickups(player, bagBefore);
                        TurnSystem.applyMoveCost(player, player.effectiveStats().moveCost);
                    }
                }
            }
            profiler.add("playerStep", playerTurnStart);
        }
        // Safety bound: expressed as event-count (advanceToNextEvent calls), not raw
        // ticks. Each call either advances the clock to the next actor/standard-turn
        // event, or flushes already-ready AI — both terminate quickly, so 200 calls
        // comfortably covers any realistic mob count × turn window.
        int safety = 200;
        // Drain any pre-existing sequential flag so the loop's break test
        // only reflects what THIS loop's ticks queue.
        animator.queue.consumeSequentialFlag();
        long turnLoopStart = profiler.start();
        while (safety-- > 0 && !TurnSystem.isPlayerTurn(level)) {
            int delta = TurnSystem.advanceToNextEvent(level);
            if (delta == 0) break;           // player is ready
            if (delta > 0) world.tick += delta; // delta == -1 means AI ran, no clock advance
            // Drain events into the animator after every event so the sequential flag
            // reflects animations queued by THIS advance — not just pre-existing state.
            animator.consume(level);
            // Refresh the player's visibility map immediately so any mob that just
            // moved / teleported into LoS becomes visible before the next iteration
            // (or before this frame's render). Without this the renderer's mob list
            // is stale until {@link #afterMove} fires at the end of the controller
            // tick, which is what produced the "killed by an enemy I never saw"
            // phantom-damage bug: a mob could step into LoS and fire a projectile
            // mid-tick and its sprite wouldn't appear until the NEXT render frame.
            LevelSystem.updateVisibility(level);
            // Only a SEQUENTIAL animation (lunge, knockback slide, chained death-fade)
            // breaks the loop — concurrent mob slides play together on one render frame.
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
        // Rebuild the cached fog / remembered-terrain + item/mob cell buckets so
        // terrain and surface changes from non-movement actions (VOID / fire /
        // creation & terraforming scrolls, bombs) are reflected immediately
        // rather than lingering until the next move or tick rebuild.
        levelRenderer.markDirty();
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
                if (!ItemSystem.useItem(level, player, bound)) playInvocationFail();
                afterMove(level);
            }
            case WAND     -> beginWand(level, player, bound);
            case GRAPPLE  -> beginGrapple(level, player, bound);
            case JUMP     -> beginJump(level, player, bound);
            case CHARGE   -> beginCharge(level, player, bound);
            case TELEPORT -> {
                if (!ItemSystem.useItem(level, player, bound)) playInvocationFail();
                afterMove(level);
            }
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
        // Crafted gem-items (RL-50) are equipped in gem slots and triggered as
        // single-use effects, not used like bag consumables - hand off to the
        // dedicated trigger flow that consumes the gem from its slot.
        if (com.bjsp123.rl2.logic.RecipeSystem.isCraftedGem(item)) {
            triggerCraftedGem(user, item);
            return;
        }
        switch (item.useBehavior) {
            case EAT, DRINK, GRANT_PERK, APPLYBUFF -> {
                boolean acted = ItemSystem.useItem(level, user, item);
                if (!acted) playInvocationFail();
                else if (item.useBehavior == Item.UseBehavior.EAT && sounds != null)
                    sounds.play("sfx.player.action.eat");
                afterMove(level);
            }
            case WAND     -> beginWand(level, user, item);
            case GRAPPLE  -> beginGrapple(level, user, item);
            case JUMP     -> beginJump(level, user, item);
            case CHARGE   -> beginCharge(level, user, item);
            case TELEPORT -> {
                if (!ItemSystem.useItem(level, user, item)) playInvocationFail();
                afterMove(level);
            }
            case NONE -> { /* unreachable - the popup gates Use on isUsable() */ }
        }
    }

    /** Trigger a crafted gem-item (RL-50) - a read-once scroll. WAND-flavoured
     *  gems gather a target tile first; the rest fire at once. The scroll is
     *  consumed (removed from wherever it lives - bag or gem slot) only when
     *  the effect actually fires; unforged stubs return false and are kept. */
    private void triggerCraftedGem(Mob user, Item gem) {
        Level level = world.currentLevel();
        // Item-targeting scrolls (enchant brand / +1 level) open the inventory
        // as a chooser; the effect applies to the picked item and only then is
        // the scroll consumed. Cancelling keeps the scroll.
        java.util.function.Predicate<Item> eligible = ItemSystem.gemItemTarget(gem);
        // With no valid target, skip the all-grey chooser and fall through to
        // the no-target path, which fizzles (logs, keeps the scroll).
        if (eligible != null && itemPicker != null && hasEligibleItem(user, eligible)) {
            itemPicker.open(eligible,
                    (u, chosen) -> {
                        Level cur = world.currentLevel();
                        if (GemSystem.triggerGemOnItem(cur, u, gem, chosen)) {
                            playScrollFx(u, gem);
                            // Showcase the enchanted item centre-screen.
                            if (animator != null) {
                                animator.stage.add(com.bjsp123.rl2.world.render.Effect.enchantShowcase(
                                        chosen, scrollTint(gem.type), FX_RNG));
                            }
                            com.bjsp123.rl2.logic.MobSystem.removeFromInventoryPublic(u, gem);
                            afterMove(cur);
                        } else {
                            playInvocationFail();
                        }
                    },
                    () -> { /* cancelled - scroll kept */ });
            return;
        }
        if (gem.useBehavior == UseBehavior.WAND) {
            triggerCraftedGemWand(user, gem);
            return;
        }
        if (GemSystem.triggerGem(level, user, gem, null)) {
            playScrollFx(user, gem);
            com.bjsp123.rl2.logic.MobSystem.removeFromInventoryPublic(user, gem);
            afterMove(level);
        } else {
            playInvocationFail();
        }
    }

    /** Audible feedback that an item could not be invoked. The matching log
     *  line (why it failed) is emitted by the rlib use/trigger path. */
    private void playInvocationFail() {
        if (sounds != null) sounds.play("sfx.item.fail");
    }

    /** Gem-hearth "recycle" recipe: open the inventory as a picker to choose any
     *  non-gem item, then break it down into gems. Invoked from the forge screen
     *  (which pops itself first so the inventory picker is the front-most modal). */
    void openRecyclePicker() {
        Mob user = TurnSystem.findPlayer(world.currentLevel());
        if (user == null || itemPicker == null) return;
        java.util.function.Predicate<Item> eligible = it -> it != null && !it.isGem();
        if (!hasEligibleItem(user, eligible)) { playInvocationFail(); return; }
        itemPicker.open(eligible,
                (u, chosen) -> {
                    Level cur = world.currentLevel();
                    Runnable doRecycle = () -> {
                        ItemSystem.recycleIntoGems(cur, u, chosen);
                        afterMove(cur);
                    };
                    // Confirm first, showing roughly what the player will get.
                    if (confirmRequest != null && chosen != null) {
                        String name = chosen.name != null ? chosen.name : chosen.type;
                        String forecast = com.bjsp123.rl2.logic.GemSystem.recycleForecast(chosen);
                        String msg = forecast.isEmpty() ? "Break it down for gems?"
                                : Character.toUpperCase(forecast.charAt(0)) + forecast.substring(1) + ".";
                        confirmRequest.show("Recycle the " + name + "?", msg, doRecycle);
                    } else {
                        doRecycle.run();
                    }
                },
                () -> { /* cancelled */ });
    }

    /** Confirm-dialog hook, set by {@link PlayScreen}. Shows a modal with a
     *  title + message and runs {@code onConfirm} only if the player accepts. */
    public interface ConfirmRequest { void show(String title, String message, Runnable onConfirm); }
    private ConfirmRequest confirmRequest;
    public void setConfirmRequest(ConfirmRequest c) { this.confirmRequest = c; }

    private static final java.util.Random FX_RNG = new java.util.Random();

    /** Per-scroll read feedback (RL-50): the {@code sfx.item.use.gem.<type>}
     *  sound (falling back to the any-scroll default) plus a colour-matched
     *  SCROLL_CAST effect around the reader. */
    private void playScrollFx(Mob user, Item gem) {
        if (gem == null || gem.type == null) return;
        if (sounds != null) sounds.play("sfx.item.use.gem." + gem.type.toLowerCase());
        if (animator != null && user != null && user.position != null) {
            animator.stage.add(com.bjsp123.rl2.world.render.Effect.scrollCast(
                    user.position, scrollTint(gem.type), scrollStyle(gem.type), FX_RNG));
        }
    }

    /** Colour for a scroll's cast effect, matching its emblem art. */
    private static com.bjsp123.rl2.world.render.Effect.EffectTint scrollTint(String t) {
        return switch (t) {
            case "SC_BLOOD_NOVA", "SC_CONJURE_BRANDED_SWORD" -> com.bjsp123.rl2.world.render.Effect.EffectTint.RED;
            case "SC_UPGRADE_WEAPON", "SC_CONJURE_WAND", "SC_SUMMON_CLONES",
                 "SC_SHIELD_SELF" -> com.bjsp123.rl2.world.render.Effect.EffectTint.YELLOW;
            case "SC_FREEZE_ALL_ENEMIES", "SC_RANDOM_TELEPORT", "SC_SEAL_DOORS_CRYSTAL",
                 "SC_BRAND_WEAPON" -> com.bjsp123.rl2.world.render.Effect.EffectTint.CYAN;
            case "SC_HARVEST_PLANTS", "SC_POLYMORPH_ENEMIES" -> com.bjsp123.rl2.world.render.Effect.EffectTint.GREEN;
            case "SC_GREAT_WARD", "SC_CONJURE_ARMOR" -> com.bjsp123.rl2.world.render.Effect.EffectTint.BLUE;
            case "SC_FULL_RESTORE", "SC_BLAST_THROUGH_WALLS" -> com.bjsp123.rl2.world.render.Effect.EffectTint.PINK;
            case "SC_SMOKE_LEVEL_AND_ESP", "SC_MADDEN_ENEMIES" -> com.bjsp123.rl2.world.render.Effect.EffectTint.MAUVE;
            case "SC_CONJURE_BOMBS", "SC_CONJURE_TOOLS", "SC_IGNITE_NEAR_STATUES" -> com.bjsp123.rl2.world.render.Effect.EffectTint.ORANGE;
            case "SC_SEAL_DOORS_ONETIME", "SC_CONJURE_ITEMS" -> com.bjsp123.rl2.world.render.Effect.EffectTint.BROWN;
            default -> com.bjsp123.rl2.world.render.Effect.EffectTint.WHITE;
        };
    }

    /** Cast style per scroll: whirlpool for travel/transform/arcane, glow for
     *  buffs/seals/creation-of-gear, sparks for offensive/elemental. */
    private static int scrollStyle(String t) {
        return switch (t) {
            case "SC_RANDOM_TELEPORT", "SC_BRAND_WEAPON", "SC_POLYMORPH_ENEMIES",
                 "SC_SUMMON_CLONES", "SC_MADDEN_ENEMIES", "SC_VOID_NEARBY_FLOOR"
                    -> com.bjsp123.rl2.world.render.Effect.SCROLL_WHIRLPOOL;
            case "SC_UPGRADE_WEAPON", "SC_SMOKE_LEVEL_AND_ESP", "SC_GREAT_WARD",
                 "SC_FULL_RESTORE", "SC_HARVEST_PLANTS", "SC_SEAL_DOORS_ONETIME", "SC_SEAL_DOORS_CRYSTAL",
                 "SC_CONJURE_ITEMS", "SC_CONJURE_ARMOR", "SC_SHIELD_SELF"
                    -> com.bjsp123.rl2.world.render.Effect.SCROLL_GLOW;
            default -> com.bjsp123.rl2.world.render.Effect.SCROLL_SPARKS;
        };
    }

    /** True if the player holds (bag or equipped) at least one item the picker
     *  would accept - gates whether the chooser is worth opening. */
    private static boolean hasEligibleItem(Mob user, java.util.function.Predicate<Item> elig) {
        if (user == null || user.inventory == null) return false;
        if (user.inventory.bag != null) {
            for (Item it : user.inventory.bag) if (it != null && elig.test(it)) return true;
        }
        for (Item it : user.inventory.allEquipped()) if (it != null && elig.test(it)) return true;
        return false;
    }

    /** WAND-flavoured crafted gem - gather a target tile, then fire + consume. */
    private void triggerCraftedGemWand(Mob user, Item gem) {
        Level level = world.currentLevel();
        targetingOverlay.setPlayer(user);
        targetingOverlay.setLevel(level);
        targetingOverlay.setValidTiles(lineOfFireGrid(level, user), level.width, level.height);
        targetingOverlay.activate(target -> {
            Level cur = world.currentLevel();
            if (GemSystem.triggerGem(cur, user, gem, target)) {
                playScrollFx(user, gem);
                com.bjsp123.rl2.logic.MobSystem.removeFromInventoryPublic(user, gem);
                animator.consume(cur);
                afterMove(cur);
            } else {
                playInvocationFail();
            }
        }, gem);
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
        targetingOverlay.setValidTiles(lineOfFireGrid(level, user), level.width, level.height);
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
     *  Chebyshev radius {@link ItemStats#effectiveRange}, then routes the confirmed
     *  tile through {@link ItemSystem#castJump}. Non-blocking hop animation. */
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

    /** Charge use entry point. Opens the targeting overlay restricted to
     *  visible hostile mobs within Chebyshev range {@link ItemStats#effectiveRange},
     *  then routes the confirmed tile through {@link ItemSystem#castCharge}.
     *  Mirrors {@link #beginJump} in shape. */
    private void beginCharge(Level level, Mob user, Item item) {
        targetingOverlay.setPlayer(user);
        targetingOverlay.setLevel(level);
        targetingOverlay.setValidTiles(chargeGrid(level, user, item), level.width, level.height);
        targetingOverlay.activate(target -> {
            Level cur = world.currentLevel();
            ItemSystem.castCharge(cur, user, item, target);
            animator.consume(cur);
            afterMove(cur);
        }, item);
    }

    /** Kick off target-picking for a throw action from the inventory popup. */
    void beginThrow(Mob thrower, Item item) {
        if (thrower == null || item == null || !item.isThrowable()) return;
        Level level = world.currentLevel();
        targetingOverlay.setPlayer(thrower);
        targetingOverlay.setLevel(level);
        targetingOverlay.setValidTiles(throwGrid(level, thrower), level.width, level.height);
        targetingOverlay.activate(target -> {
            Level cur = world.currentLevel();
            MobSystem.throwItem(cur, thrower, item, target);
            animator.consume(cur);
            afterMove(cur);
        }, item);
    }

    /** Drop {@code item} from the player's inventory onto the nearest free floor
     *  tile. Always available for any carried item (the inventory only offers it
     *  in place of Throw for non-throwable items); costs one move. */
    void dropItem(Mob user, Item item) {
        if (user == null || item == null) return;
        Level level = world.currentLevel();
        if (!TurnSystem.isPlayerTurn(level)) return;
        if (!ItemSystem.dropItem(level, user, item)) return;
        animator.consume(level);
        afterMove(level);
    }

    /** Grid of valid grapple targets: visible + within Chebyshev range {@code 2 + effectPower}. */
    private static boolean[][] grappleGrid(Level level, Mob user, Item item) {
        boolean[][] grid = new boolean[level.width][level.height];
        if (user.position == null || level.visible == null) return grid;
        int radius = 2 + Math.max(0, (int) com.bjsp123.rl2.logic.ItemStats.effectivePower(item));
        int px = user.position.tileX(), py = user.position.tileY();
        for (int x = Math.max(0, px - radius); x <= Math.min(level.width - 1, px + radius); x++) {
            for (int y = Math.max(0, py - radius); y <= Math.min(level.height - 1, py + radius); y++) {
                if (Math.max(Math.abs(x - px), Math.abs(y - py)) > radius) continue;
                grid[x][y] = level.visible[x][y];
            }
        }
        return grid;
    }

    /** Grid of valid throw targets: any tile the thrown item could actually land
     *  on - a clear line-of-FIRE (no wall/mob clips the flight first), capped at
     *  the thrower's Chebyshev throw range (base + Hurler). NOT gated on FOV: a
     *  tile down an unlit but open corridor is hittable even if currently dark. */
    private static boolean[][] throwGrid(Level level, Mob thrower) {
        boolean[][] grid = new boolean[level.width][level.height];
        if (thrower.position == null) return grid;
        int range = com.bjsp123.rl2.logic.MobSystem.throwRangeFor(thrower);
        int px = thrower.position.tileX(), py = thrower.position.tileY();
        for (int x = Math.max(0, px - range); x <= Math.min(level.width - 1, px + range); x++) {
            for (int y = Math.max(0, py - range); y <= Math.min(level.height - 1, py + range); y++) {
                if (Math.max(Math.abs(x - px), Math.abs(y - py)) > range) continue;
                grid[x][y] = hasClearShot(level, thrower, x, y);
            }
        }
        return grid;
    }

    /** Grid of tiles a wand can actually hit: any tile with a clear line-of-FIRE
     *  from the caster (the projectile clips at the first wall/mob, so the impact
     *  lands exactly there). Unlimited range, NOT gated on FOV - matches what
     *  {@link com.bjsp123.rl2.logic.ItemSystem#fireWand} resolves at fire-time. */
    private static boolean[][] lineOfFireGrid(Level level, Mob caster) {
        boolean[][] grid = new boolean[level.width][level.height];
        if (caster == null || caster.position == null) return grid;
        for (int x = 0; x < level.width; x++)
            for (int y = 0; y < level.height; y++)
                grid[x][y] = hasClearShot(level, caster, x, y);
        return grid;
    }

    /** True when a projectile aimed at ({@code tx},{@code ty}) from {@code shooter}
     *  would actually land on that tile - i.e. nothing intercepts the flight
     *  first. Uses the same {@code firstMobBlocking} clip the throw / wand
     *  resolution uses, so the highlighted set matches real reach exactly. */
    private static boolean hasClearShot(Level level, Mob shooter, int tx, int ty) {
        if (shooter == null || shooter.position == null) return false;
        com.bjsp123.rl2.model.Point impact = com.bjsp123.rl2.logic.MobSystem.firstMobBlocking(
                level, shooter.position, new com.bjsp123.rl2.model.Point(tx, ty), shooter);
        return impact != null && impact.tileX() == tx && impact.tileY() == ty;
    }

    /** Grid of valid jump destinations: within Chebyshev radius, passable, unoccupied.
     *  Radius widens with the JUMP perk via {@link ItemStats#effectiveLevel}. */
    private static boolean[][] jumpGrid(Level level, Mob jumper, Item item) {
        boolean[][] grid = new boolean[level.width][level.height];
        if (jumper.position == null) return grid;
        int effLvl = com.bjsp123.rl2.logic.ItemStats.effectiveLevel(item, jumper);
        int radius = Math.max(0,
                com.bjsp123.rl2.logic.ItemStats.effectiveRange(item, effLvl));
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

    /** Grid of valid charge targets: visible hostile mob within
     *  {@link com.bjsp123.rl2.logic.ItemStats#effectiveRange} Chebyshev tiles,
     *  with at least one walkable 8-neighbor (so {@code castCharge} has a
     *  landing tile). */
    private static boolean[][] chargeGrid(Level level, Mob user, Item item) {
        boolean[][] grid = new boolean[level.width][level.height];
        if (user.position == null || level.visible == null) return grid;
        int effLvl = com.bjsp123.rl2.logic.ItemStats.effectiveLevel(item, user);
        int radius = Math.max(1,
                com.bjsp123.rl2.logic.ItemStats.effectiveRange(item, effLvl));
        int px = user.position.tileX(), py = user.position.tileY();
        for (com.bjsp123.rl2.model.Mob m : level.mobs) {
            if (m == null || m == user || m.position == null || m.hp <= 0) continue;
            int mx = m.position.tileX(), my = m.position.tileY();
            if (!level.visible[mx][my]) continue;
            int d = Math.max(Math.abs(mx - px), Math.abs(my - py));
            // Charge needs a runway - adjacent (d==1) targets are excluded.
            // castCharge enforces the same gate; mirroring it here just keeps
            // the targeting overlay from highlighting illegal tiles.
            if (d > radius || d < 2) continue;
            if (com.bjsp123.rl2.logic.MobSystem.getAttitudeToMob(user, m)
                    != com.bjsp123.rl2.logic.MobSystem.Attitude.ATTACK) continue;
            // Require at least one walkable 8-neighbor for the arrival tile.
            if (!hasFreeNeighbor(level, mx, my)) continue;
            // A charge is a ground dash - skip targets walled off from the user
            // (castCharge enforces the same path check).
            if (!com.bjsp123.rl2.logic.MobSystem.chargePathClear(level, user.position, m.position)) continue;
            grid[mx][my] = true;
        }
        return grid;
    }

    /** True iff at least one Chebyshev-1 neighbour of {@code (x,y)} is in
     *  bounds, not movement-blocking, and not occupied by another mob.
     *  Used by {@link #chargeGrid} to gate dash targets to mobs the user
     *  can actually land next to. */
    private static boolean hasFreeNeighbor(Level level, int x, int y) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                int nx = x + dx, ny = y + dy;
                if (nx < 0 || ny < 0 || nx >= level.width || ny >= level.height) continue;
                if (level.tiles[nx][ny].blocksMovement()) continue;
                if (MobQueries.mobAt(level, new Point(nx, ny)) != null) continue;
                return true;
            }
        }
        return false;
    }

    void cancelThrow() {
        if (targetingOverlay != null) targetingOverlay.cancel();
    }

    HallOfFameEntry snapshotOf(Mob p) {
        List<String> equipment = new ArrayList<>();
        List<String> equipmentTypes = new ArrayList<>();
        for (Item it : p.inventory.allEquipped()) {
            String cat = it.inventoryCategory != null ? it.inventoryCategory.name()
                    : com.bjsp123.rl2.logic.TextCatalog.get("eventlog.fallback.equipped");
            equipment.add(cat + ": " + it.describe());
            if (it.type != null) equipmentTypes.add(it.type);
        }
        int score = com.bjsp123.rl2.logic.GameBalance.runScore(
                p.runStats, p.beaconsLit, p.killedGreatWraith, /*allBeaconsLit=*/false);
        HallOfFameEntry entry = new HallOfFameEntry(
                p.characterClass != null ? p.characterClass.displayName()
                        : com.bjsp123.rl2.logic.TextCatalog.get("eventlog.fallback.adventurer"),
                p.characterLevel,
                score, world.currentLevel().depth, equipment, System.currentTimeMillis());
        entry.totalTurns  = TurnSystem.standardTurnForTick(world.tick);
        entry.beastsTamed = p.beastsTamed;
        entry.favPerk     = favPerkOf(p);
        entry.beaconsLit        = p.beaconsLit;
        entry.killedGreatWraith = p.killedGreatWraith;
        entry.difficulty        = com.bjsp123.rl2.logic.GameBalance.difficulty.name();
        com.bjsp123.rl2.model.RunStats rs = p.runStats;
        entry.mobsKilled    = rs.mobsKilled;
        entry.itemsPickedUp = rs.itemsPickedUp;
        entry.foodEaten     = rs.foodEaten;
        entry.gemsFound     = rs.gemsFound;
        entry.topWand = nz(rs.topWand()); entry.topWandCount = rs.topWandCount();
        entry.topBomb = nz(rs.topBomb()); entry.topBombCount = rs.topBombCount();
        entry.topTool = nz(rs.topTool()); entry.topToolCount = rs.topToolCount();
        entry.equipmentTypes = equipmentTypes;
        return entry;
    }

    private static String nz(String s) { return s == null ? "" : s; }

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
        // Fill any remaining empty slots with the rest of the starting inventory's
        // usable / throwable items (e.g. the rogue's cherry bombs), using the same
        // first-empty-slot rule as pickups - the explicit class defaults above keep
        // priority since they're already bound.
        autoAssignNewPickups(player, 0);
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
        // Gem hearth is impassable, so the player stands beside it; interacting
        // while adjacent opens the forge crafting screen (no turn cost - it's a
        // menu, same as the stairs transitions above).
        if (openForgeScreen != null && adjacentToGemHearth(cur, player.position)) {
            if (sounds != null) sounds.play("sfx.world.forge.open");
            openForgeScreen.run();
            return;
        }
        // Waiting beside a beacon opens the teleport map (no turn cost - it's a
        // menu, like the stairs / forge transitions above).
        if (openMapScreen != null && adjacentToBeacon(cur, player.position)) {
            openMapScreen.run();
            return;
        }
        TurnSystem.applyMoveCost(player, player.effectiveStats().moveCost);
    }

    /** Open the teleport-enabled map - triggered by walking into a beacon
     *  (from {@link com.bjsp123.rl2.input.GameInput}) or waiting beside one. */
    public void openBeaconMap() {
        if (openMapScreen != null) openMapScreen.run();
    }

    /** True when any 8-neighbour of {@code at} is a beacon tile. */
    private static boolean adjacentToBeacon(Level level, Point at) {
        if (at == null) return false;
        int px = at.tileX(), py = at.tileY();
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                int x = px + dx, y = py + dy;
                if (x < 0 || y < 0 || x >= level.width || y >= level.height) continue;
                if (level.tiles[x][y].isBeacon()) return true;
            }
        }
        return false;
    }

    /** True when any 8-neighbour of {@code at} is a gem-hearth tile
     *  ({@link Tile#GEM_HEARTH_L} / {@link Tile#GEM_HEARTH_R}). */
    private static boolean adjacentToGemHearth(Level level, Point at) {
        if (at == null) return false;
        int px = at.tileX(), py = at.tileY();
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                int x = px + dx, y = py + dy;
                if (x < 0 || y < 0 || x >= level.width || y >= level.height) continue;
                Tile t = level.tiles[x][y];
                if (t == Tile.GEM_HEARTH_L || t == Tile.GEM_HEARTH_R) return true;
            }
        }
        return false;
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
            boolean throwable = it.isThrowable();
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

    /** Teleport the player to a 4-neighbour of {@code beacon} on level
     *  {@code destLevelIdx}. Mirrors {@link #tryStairs} for the cross-level
     *  swap, but brackets the move with player-teleport-out / teleport-in
     *  events so the animator can play a fade visual at the source and
     *  destination. Consumes the player's move cost so any mobs on the
     *  destination level act once before the player gets input.
     *
     *  <p>Returns {@code true} if the teleport succeeded, {@code false} if
     *  the destination is invalid or no walkable neighbour exists (in which
     *  case the player stays put). */
    public boolean teleportToBeacon(int destLevelIdx, Point beacon) {
        if (destLevelIdx < 0 || destLevelIdx >= world.levels.length) return false;
        Level destLevel = world.levels[destLevelIdx];
        if (destLevel == null || beacon == null) return false;
        // The beacon tile itself blocks movement, so the player lands on a
        // walkable 4-neighbour. Deterministic order keeps reloads stable.
        int bx = beacon.tileX(), by = beacon.tileY();
        Point arrival = null;
        int[][] dirs = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}};
        for (int[] d : dirs) {
            int ax = bx + d[0], ay = by + d[1];
            if (ax < 0 || ay < 0 || ax >= destLevel.width || ay >= destLevel.height) continue;
            if (destLevel.tiles[ax][ay].blocksMovement()) continue;
            // Don't land on top of another mob.
            boolean occupied = false;
            for (Mob m : destLevel.mobs) {
                if (m != null && m.position != null
                        && m.position.tileX() == ax && m.position.tileY() == ay) {
                    occupied = true;
                    break;
                }
            }
            if (occupied) continue;
            arrival = new Point(ax, ay);
            break;
        }
        if (arrival == null) return false;

        Level cur = world.currentLevel();
        Mob player = TurnSystem.findPlayer(cur);
        if (player == null) return false;

        // Departure visual on the source level.
        if (cur.events != null) {
            cur.events.add(new com.bjsp123.rl2.event.GameEvent.PlayerTeleportOut(player.position));
        }

        cur.mobs.remove(player);
        player.position       = arrival;
        player.targetPosition = null;
        destLevel.mobs.add(player);
        world.currentLevelIndex = destLevelIdx;
        destLevel.visited = true;

        // Arrival visual on the destination level.
        if (destLevel.events != null) {
            destLevel.events.add(new com.bjsp123.rl2.event.GameEvent.PlayerTeleportIn(arrival));
        }

        TurnSystem.applyMoveCost(player, player.effectiveStats().moveCost);
        for (Mob mob : destLevel.mobs) mob.effectiveStats();
        afterMove(destLevel);
        recenterCamera.run();
        levelRenderer.markDirty();

        if (sounds != null) {
            if (destLevel.kind == Level.LevelKind.LANDING) sounds.play("sfx.world.arrive.landing");
            else sounds.play("sfx.world.action.enter_level");
        }
        return true;
    }

    private void tryStairs(int direction) {
        Level cur = world.currentLevel();
        Mob player = TurnSystem.findPlayer(cur);
        if (player == null) return;
        boolean ok = direction > 0
                ? com.bjsp123.rl2.logic.LevelSystem.descendStairs(world, player)
                : com.bjsp123.rl2.logic.LevelSystem.ascendStairs(world, player);
        if (!ok) return;
        Level next = world.currentLevel();
        afterMove(next);
        recenterCamera.run();
        levelRenderer.markDirty();
        if (sounds != null) {
            // Arriving on the endgame Landing gets its own suspense sting.
            if (next.kind == Level.LevelKind.LANDING) sounds.play("sfx.world.arrive.landing");
            else sounds.play("sfx.world.action.enter_level");
        }
        String playerName = player.name != null ? player.name
                : com.bjsp123.rl2.logic.TextCatalog.get("eventlog.fallback.adventurer");
        EventLog.add(Messages.enterLevel(playerName, next.depth, next.flags));
    }
}
