package com.bjsp123.rl2.util;

import com.bjsp123.rl2.logic.GameBalance;
import com.bjsp123.rl2.logic.InventorySystem;
import com.bjsp123.rl2.logic.MobProgression;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.UniqueTracker;
import com.bjsp123.rl2.model.WorldTopology;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Player-class gear provider for headless surfaces (arena, attract mode).
 * Generates one world at construction, then answers two questions:
 * <ul>
 *   <li>What depth does a given character level correspond to? (XP-measured
 *       from cumulative POWER_ORB / XPPILL grants found in the world, plus
 *       {@link GameBalance#MISC_XP_PER_DEPTH} per depth as a fudge term.)</li>
 *   <li>What gear would the player carry if they'd cleared every level up
 *       to that depth?</li>
 * </ul>
 *
 * <p>The kit picker uses {@link Item#getValue()} to rank candidates, so it's
 * scaling-independent: rebalancing the AMOUNT formula doesn't change which
 * items the picker prefers. Class identity comes from perks and base stats
 * elsewhere, so the kit is uniform across PLAYER_*.
 */
public final class PlayerGearProvider {

    /** Per-slot picks + flat consumable bag list ready for inventory apply. */
    public static final class InventoryKit {
        public Item weapon, offhand, armor;
        public Item amulet1, amulet2;
        public Item gem1, gem2, gem3;
        public final List<Item> bag = new ArrayList<>();
    }

    private final Level[] world;
    private final List<Item>[] itemsByDepth;
    private final int[] cumXpByDepth;        // cumXpByDepth[d] = XP available
                                              //   over levels 1..d
    private final int[] depthByCharLvl;       // depthByCharLvl[L] = depth
                                              //   reached at character level L

    @SuppressWarnings("unchecked")
    public PlayerGearProvider(long seed) {
        Random rng = new Random(seed);
        this.world = WorldTopology.build(
                GameBalance.LEVEL_BASE_W, GameBalance.LEVEL_BASE_H,
                rng, new UniqueTracker());

        int maxDepth = 0;
        int totalItems = 0;
        for (Level lvl : world) {
            if (lvl.depth > maxDepth) maxDepth = lvl.depth;
            if (lvl.items != null) totalItems += lvl.items.size();
        }
        System.err.println("[GEAR] world levels=" + world.length
                + " maxDepth=" + maxDepth + " totalItems=" + totalItems);

        this.itemsByDepth = new List[maxDepth + 1];
        this.cumXpByDepth = new int[maxDepth + 1];
        for (int d = 0; d <= maxDepth; d++) itemsByDepth[d] = new ArrayList<>();

        // Bucket every item by the depth of the Level it sits on. XP-granting
        // items contribute to the running cumulative-XP count, since the
        // player would consume them on the way down.
        int[] perDepthXp = new int[maxDepth + 1];
        for (Level lvl : world) {
            int d = Math.max(1, Math.min(maxDepth, lvl.depth));
            List<Item> bucket = itemsByDepth[d];
            if (lvl.items != null) {
                for (Item it : lvl.items) {
                    if (it == null) continue;
                    bucket.add(it);
                    perDepthXp[d] += xpValue(it);
                }
            }
            // Mob inventories: bosses and themed-room dwellers can carry
            // gear and XP rewards. Player picks these up on kill.
            if (lvl.mobs != null) {
                for (Mob m : lvl.mobs) {
                    if (m == null || m.inventory == null) continue;
                    addMobItem(bucket, perDepthXp, d, m.inventory.weapon);
                    addMobItem(bucket, perDepthXp, d, m.inventory.offhand);
                    addMobItem(bucket, perDepthXp, d, m.inventory.armor);
                    if (m.inventory.amulets != null)
                        for (Item it : m.inventory.amulets)
                            addMobItem(bucket, perDepthXp, d, it);
                    if (m.inventory.gems != null)
                        for (Item it : m.inventory.gems)
                            addMobItem(bucket, perDepthXp, d, it);
                    if (m.inventory.bag != null)
                        for (Item it : m.inventory.bag)
                            addMobItem(bucket, perDepthXp, d, it);
                }
            }
        }

        // Cumulative pass + fudge term.
        int running = 0;
        for (int d = 1; d <= maxDepth; d++) {
            running += perDepthXp[d];
            running += GameBalance.MISC_XP_PER_DEPTH;
            cumXpByDepth[d] = running;
        }

        for (int d = 1; d <= maxDepth; d++) {
            System.err.println("[GEAR] depth=" + d
                    + " items=" + itemsByDepth[d].size()
                    + " cumXp=" + cumXpByDepth[d]);
            for (Item it : itemsByDepth[d]) {
                System.err.println("  " + (it == null ? "null"
                        : it.type + " cat=" + it.inventoryCategory
                                + " +" + it.level));
            }
        }
        // Precompute character-level → depth mapping.
        this.depthByCharLvl = new int[GameBalance.MAX_CHARACTER_LEVEL + 1];
        for (int L = 1; L <= GameBalance.MAX_CHARACTER_LEVEL; L++) {
            int target = MobProgression.xpToReach(L);
            int chosen = maxDepth;     // cap at dungeon bottom
            for (int d = 1; d <= maxDepth; d++) {
                if (cumXpByDepth[d] >= target) { chosen = d; break; }
            }
            depthByCharLvl[L] = chosen;
        }
    }

    private static void addMobItem(List<Item> bucket, int[] perDepthXp,
                                   int d, Item it) {
        if (it == null) return;
        bucket.add(it);
        perDepthXp[d] += xpValue(it);
    }

    /** XP value of a single item, mirroring the live LEVEL_UP pickup branch
     *  in ItemSystem: {@code effectPower x XP_PER_POWER_ORB}, with blank
     *  {@code effectPower} falling back to 1.0. */
    private static int xpValue(Item it) {
        if (it == null || it.wandEffect != Item.ItemEffect.LEVEL_UP) return 0;
        float p = it.effectPower > 0f ? it.effectPower : 1f;
        return (int) (p * GameBalance.XP_PER_POWER_ORB);
    }

    /** Smallest depth at which a player would have accumulated enough XP to
     *  be at {@code charLvl}. Capped at the world's deepest level — if the
     *  dungeon's total available XP doesn't meet the threshold, the deepest
     *  depth is returned (and the player just has all of the gear). */
    public int depthForCharLvl(int charLvl) {
        int L = Math.max(1, Math.min(GameBalance.MAX_CHARACTER_LEVEL, charLvl));
        return depthByCharLvl[L];
    }

    /** Every item the player would have access to by the time they clear
     *  depth {@code D}. Includes the depth itself. */
    public List<Item> itemsAtDepth(int D) {
        int cap = Math.max(1, Math.min(D, itemsByDepth.length - 1));
        List<Item> out = new ArrayList<>();
        for (int d = 1; d <= cap; d++) out.addAll(itemsByDepth[d]);
        return out;
    }

    /** Kit a player at depth {@code D} would plausibly carry. */
    public InventoryKit kitForDepth(int D) {
        List<Item> pool = itemsAtDepth(D);
        InventoryKit kit = new InventoryKit();

        kit.weapon  = bestForSlot(pool, Item.InventoryCategory.WEAPON,  null, null);
        kit.armor   = bestForSlot(pool, Item.InventoryCategory.ARMOR,   null, null);
        kit.offhand = bestForSlot(pool, Item.InventoryCategory.OFFHAND, null, null);
        kit.amulet1 = bestForSlot(pool, Item.InventoryCategory.AMULET,  null, null);
        kit.amulet2 = bestForSlot(pool, Item.InventoryCategory.AMULET,  kit.amulet1, null);
        kit.gem1    = bestForSlot(pool, Item.InventoryCategory.GEM,     null, null);
        kit.gem2    = bestForSlot(pool, Item.InventoryCategory.GEM,     kit.gem1, null);
        kit.gem3    = bestForSlot(pool, Item.InventoryCategory.GEM,     kit.gem1, kit.gem2);

        // Bag consumables: every eligible stack; addToBag merges duplicates.
        // POWERUPs (POWER_ORB / XPPILL / CHARGEPILL / HEALTHPILL with the
        // POWERUP use behavior) are walk-over pickups that get consumed on
        // tile step in live play - they never actually enter a bag, so they
        // shouldn't enter the kit either. Putting them in the bag let the
        // arena mage burn through perpetual recharges.
        for (Item it : pool) {
            if (it == null || it.inventoryCategory == null) continue;
            if (it.useBehavior == Item.UseBehavior.POWERUP) continue;
            switch (it.inventoryCategory) {
                case POTION, BOMB, WAND, FOOD, ITEM, TOOL -> kit.bag.add(it);
                default -> { /* equipment + gems handled above */ }
            }
        }
        return kit;
    }

    /** Arena convenience: maps charLvl → depth then picks the kit. */
    public InventoryKit kitForCharLvl(int charLvl) {
        return kitForDepth(depthForCharLvl(charLvl));
    }

    /** Highest-{@code getValue} item in {@code pool} matching {@code cat},
     *  excluding any of the explicit exclusions. */
    private static Item bestForSlot(List<Item> pool, Item.InventoryCategory cat,
                                    Item exclude1, Item exclude2) {
        Item best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Item it : pool) {
            if (it == null || it.inventoryCategory != cat) continue;
            if (it == exclude1 || it == exclude2) continue;
            double s = it.getValue();
            if (s > bestScore) { bestScore = s; best = it; }
        }
        return best;
    }

    /** Apply the kit on top of the fighter's existing inventory. Each kit
     *  item is cloned so the same world item can serve multiple fighters
     *  without one fighter's pickup affecting another's.
     *
     *  <p>Additive: the L1 starting inventory (mage's wands, etc.) survives.
     *  {@link InventorySystem#equip} naturally displaces a previously
     *  equipped item back to the bag, so the kit's higher-depth gear wins
     *  slot ownership without us having to wipe anything. The previous
     *  always-wipe behaviour left fighters bare-handed when the gear-world
     *  produced an empty kit. */
    public void applyKit(Mob fighter, InventoryKit kit) {
        if (fighter == null || fighter.inventory == null || kit == null) return;

        if (kit.weapon  != null) InventorySystem.equip(fighter.inventory, cloneOne(kit.weapon));
        if (kit.armor   != null) InventorySystem.equip(fighter.inventory, cloneOne(kit.armor));
        if (kit.offhand != null) InventorySystem.equip(fighter.inventory, cloneOne(kit.offhand));
        if (kit.amulet1 != null) InventorySystem.equip(fighter.inventory, cloneOne(kit.amulet1));
        if (kit.amulet2 != null) InventorySystem.equip(fighter.inventory, cloneOne(kit.amulet2));
        if (kit.gem1    != null) InventorySystem.equip(fighter.inventory, cloneOne(kit.gem1));
        if (kit.gem2    != null) InventorySystem.equip(fighter.inventory, cloneOne(kit.gem2));
        if (kit.gem3    != null) InventorySystem.equip(fighter.inventory, cloneOne(kit.gem3));

        for (Item it : kit.bag) InventorySystem.addToBag(fighter.inventory, cloneOne(it));

        fighter.statsDirty = true;
    }

    /** Deep-copy an Item including its {@code count} (unlike
     *  {@code InventorySystem.shallowSingleton} which forces count=1). */
    private static Item cloneOne(Item src) {
        if (src == null) return null;
        Item out = new Item();
        out.type = src.type;
        out.material = src.material;
        out.name = src.name;
        out.description = src.description;
        out.description2 = src.description2;
        out.damage = src.damage;
        out.armor = src.armor;
        out.apDamage = src.apDamage;
        out.magicResist = src.magicResist;
        out.accuracy = src.accuracy;
        out.evasion = src.evasion;
        out.attackSpeed = src.attackSpeed;
        out.moveSpeed = src.moveSpeed;
        out.lightRadius = src.lightRadius;
        out.foodValue = src.foodValue;
        out.effectSize = src.effectSize;
        out.effectDuration = src.effectDuration;
        out.effectRange = src.effectRange;
        out.effectPower = src.effectPower;
        out.knockbackSquares = src.knockbackSquares;
        out.throwEffect = src.throwEffect;
        out.throwResult = src.throwResult;
        out.useBehavior = src.useBehavior;
        out.useVerb = src.useVerb;
        out.wandEffect = src.wandEffect;
        out.summonsWhenUsed = src.summonsWhenUsed;
        out.chargeGain = src.chargeGain;
        out.baseChargeMax = src.baseChargeMax;
        out.charge = src.charge;
        out.glows = src.glows;
        out.level = src.level;
        out.brand = src.brand;
        out.minPowerLevel = src.minPowerLevel;
        out.inventoryCategory = src.inventoryCategory;
        out.gemSpecies = src.gemSpecies;
        out.gemSize = src.gemSize;
        out.count = src.count;
        out.appliesBuff = new ArrayList<>(src.appliesBuff);
        out.tameOnThrow = new ArrayList<>(src.tameOnThrow);
        return out;
    }
}
