package com.bjsp123.rl2.ui.hud;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Mob;

/**
 * Player-facing HUD action-bar bindings. Six slots that map 1:1 to the bottom-right
 * action buttons; each slot is either {@code null} or a reference to an {@link Item} the
 * player holds. Living on the rgame side rather than as a field on {@link Mob} so the
 * domain model in rlib stays free of UI concepts - nothing in rlib has to know an action
 * bar exists.
 *
 * <p>Bindings are aliases - the {@link Item} referenced here is the same instance that
 * sits in {@code mob.inventory.bag} (or in an equipment slot). Removing the item from
 * inventory orphans the binding; {@link #audit(Mob)} sweeps stale references on every
 * turn advancement and refills with a same-type item from the bag where possible.
 */
public final class ActionBar {

    /** Maximum number of HUD action buttons. Matches the largest quickslot
     *  count choice (10) and the size of {@link com.bjsp123.rl2.model.Mob#actionSlotTypes};
     *  the visible count is controlled by
     *  {@link com.bjsp123.rl2.ui.skin.Settings#quickslotCount}. */
    public static final int SLOTS = 10;

    private final Item[] slots = new Item[SLOTS];
    /** Player Mob this action bar persists into. Set by {@link #bindToPlayer}
     *  on PlayScreen show; the bar mirrors {@code slots[i].type} into
     *  {@link Mob#actionSlotTypes} every time the player binds / unbinds
     *  so the assignment survives a save / load. */
    private Mob owner;

    public int size() { return SLOTS; }

    public Item get(int i) {
        if (i < 0 || i >= SLOTS) return null;
        return slots[i];
    }

    public void set(int i, Item it) {
        if (i < 0 || i >= SLOTS) return;
        // An item lives in at most one slot at a time - binding it to a
        // new slot evicts it from any other it was already in. Same-type
        // duplicates (different Item instances) are unaffected.
        if (it != null) {
            for (int j = 0; j < SLOTS; j++) {
                if (j != i && slots[j] == it) slots[j] = null;
            }
        }
        slots[i] = it;
        persistTypes();
    }

    public void clear() {
        for (int i = 0; i < SLOTS; i++) slots[i] = null;
        persistTypes();
    }

    /** Bind the action bar to the player Mob whose save persists the
     *  quickslot assignments. Restores the in-memory slots from the
     *  player's saved {@link Mob#actionSlotTypes} by looking up matching
     *  item instances in the bag. Called from PlayScreen.show after the
     *  world is loaded. */
    public void bindToPlayer(Mob player) {
        this.owner = player;
        if (player == null) return;
        ensureSlotCapacity(player);
        // Restore live Item references from the persisted type strings.
        // Each saved type matches the first bag item of that type that
        // hasn't already been claimed by an earlier slot.
        java.util.Set<Item> claimed = new java.util.HashSet<>();
        for (int i = 0; i < SLOTS; i++) {
            slots[i] = null;
            String want = player.actionSlotTypes[i];
            if (want == null) continue;
            if (player.inventory == null) continue;
            for (Item it : player.inventory.bag) {
                if (it == null) continue;
                if (claimed.contains(it)) continue;
                if (want.equals(it.type)) { slots[i] = it; claimed.add(it); break; }
            }
            // Fall back to equipped items for gear-class quickslots.
            if (slots[i] == null) {
                for (Item it : player.inventory.allEquipped()) {
                    if (it == null || claimed.contains(it)) continue;
                    if (want.equals(it.type)) { slots[i] = it; claimed.add(it); break; }
                }
            }
        }
    }

    /** Mirror current slot types into {@link Mob#actionSlotTypes} on the
     *  bound owner so the save format captures them. */
    private void persistTypes() {
        if (owner == null) return;
        ensureSlotCapacity(owner);
        for (int i = 0; i < SLOTS; i++) {
            owner.actionSlotTypes[i] = slots[i] != null ? slots[i].type : null;
        }
    }

    /** Make {@code player.actionSlotTypes} at least {@link #SLOTS} long,
     *  preserving any existing entries. Handles older saves whose array was
     *  sized to a smaller {@code SLOTS} (e.g. 9) so a slot-count bump can't
     *  index out of bounds. */
    private static void ensureSlotCapacity(Mob player) {
        if (player.actionSlotTypes == null
                || player.actionSlotTypes.length < SLOTS) {
            String[] grown = new String[SLOTS];
            if (player.actionSlotTypes != null) {
                System.arraycopy(player.actionSlotTypes, 0, grown, 0,
                        player.actionSlotTypes.length);
            }
            player.actionSlotTypes = grown;
        }
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
        // Phase 1 - collect items already accounted for by HELD slot bindings. Stale slot
        // references (item gone from inventory) don't reserve anything: if both slots are
        // stale, both should be eligible for refill from whatever is in the bag, and a
        // newly-picked replacement only "reserves" itself for one of them.
        java.util.Set<Item> reserved = new java.util.HashSet<>();
        for (int i = 0; i < SLOTS; i++) {
            Item bound = slots[i];
            if (bound != null && heldByPlayer(player, bound)) reserved.add(bound);
        }

        // Phase 2 - for each stale slot, pick the first bag item of the same type that's
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
        for (Item eq : player.inventory.allEquipped()) {
            if (eq == it) return true;
        }
        return false;
    }
}
