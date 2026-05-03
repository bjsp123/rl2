package com.bjsp123.rl2.ui.hud;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Item.ItemSlot;
import com.bjsp123.rl2.model.Mob;

/**
 * Player-facing HUD action-bar bindings. Six slots that map 1:1 to the bottom-right
 * action buttons; each slot is either {@code null} or a reference to an {@link Item} the
 * player holds. Living on the rgame side rather than as a field on {@link Mob} so the
 * domain model in rlib stays free of UI concepts — nothing in rlib has to know an action
 * bar exists.
 *
 * <p>Bindings are aliases — the {@link Item} referenced here is the same instance that
 * sits in {@code mob.inventory.bag} (or in an equipment slot). Removing the item from
 * inventory orphans the binding; {@link #audit(Mob)} sweeps stale references on every
 * turn advancement and refills with a same-type item from the bag where possible.
 */
public final class ActionBar {

    /** Number of HUD action buttons (1..6 mapped to indices 0..5). */
    public static final int SLOTS = 6;

    private final Item[] slots = new Item[SLOTS];

    public int size() { return SLOTS; }

    public Item get(int i) {
        if (i < 0 || i >= SLOTS) return null;
        return slots[i];
    }

    public void set(int i, Item it) {
        if (i < 0 || i >= SLOTS) return;
        slots[i] = it;
    }

    public void clear() {
        for (int i = 0; i < SLOTS; i++) slots[i] = null;
    }

    /**
     * Sweep stale bindings (slot's item is no longer in the player's bag or equipment)
     * and refill from the bag where a same-type item is available. Lifted verbatim from
     * the previous {@code PlayScreen.auditActionSlots}; the only change is that the slot
     * array now lives on {@code this} instead of on {@code Mob}.
     *
     * <p>Called from rgame after every game-state advancement (move, attack, throw, eat,
     * missile, stairs) so a consumed bomb-binding immediately backfills with the next
     * bomb of the same kind in the bag.
     */
    public void audit(Mob player) {
        if (player == null) return;
        // Phase 1 — collect items already accounted for by HELD slot bindings. Stale slot
        // references (item gone from inventory) don't reserve anything: if both slots are
        // stale, both should be eligible for refill from whatever is in the bag, and a
        // newly-picked replacement only "reserves" itself for one of them.
        java.util.Set<Item> reserved = new java.util.HashSet<>();
        for (int i = 0; i < SLOTS; i++) {
            Item bound = slots[i];
            if (bound != null && heldByPlayer(player, bound)) reserved.add(bound);
        }

        // Phase 2 — for each stale slot, pick the first bag item of the same type that's
        // not yet reserved. Add the picked item to the reserved set so a later iteration
        // doesn't draft the same instance into a second slot. If nothing matches, the
        // slot is cleared.
        for (int i = 0; i < SLOTS; i++) {
            Item bound = slots[i];
            if (bound == null) continue;
            if (heldByPlayer(player, bound)) continue;
            Item replacement = null;
            if (bound.type != null) {
                for (Item it : player.inventory.bag) {
                    if (it.type != bound.type) continue;
                    if (reserved.contains(it)) continue;
                    replacement = it;
                    reserved.add(it);
                    break;
                }
            }
            slots[i] = replacement;
        }
    }

    private static boolean heldByPlayer(Mob player, Item it) {
        if (player.inventory.bag.contains(it)) return true;
        for (ItemSlot s : ItemSlot.values()) {
            if (player.inventory.equipped(s) == it) return true;
        }
        return false;
    }
}
