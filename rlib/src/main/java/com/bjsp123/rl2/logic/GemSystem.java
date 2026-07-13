package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.GemSpecies;
import com.bjsp123.rl2.model.GemSpecies.GemClass;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.Buff;
import com.bjsp123.rl2.event.GameEvent;
import com.bjsp123.rl2.model.Item.InventoryCategory;
import com.bjsp123.rl2.model.Level.VisualTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Gem spawn helpers and the gem factory used by {@code placeGems} (RL-47). Gems are picked by
 * rarity {@link GemClass} and biased toward the level's theme by affinity (matching 2x, else
 * 0.5x - the same soft weighting items use). Equip/unequip live on
 * {@link com.bjsp123.rl2.model.Inventory}; gems carry no stats today.
 */
public final class GemSystem {

    private GemSystem() {}

    /** Affinity gate: a gem NEVER appears on a non-SHINY level themed against it
     *  (weight 0); otherwise it's neutral (weight 1, no bonus). SHINY is the
     *  generic theme - whether as the level theme or the gem's own affinity it
     *  carries no theme restriction - and a null affinity is unthemed. Mirrors
     *  the item hard gate ({@link ItemDefinition#allowsTheme}). */
    /** Affinity HARD gate: a gem with an affinity theme ONLY appears on levels
     *  of that theme; a null affinity (hamethyst) spawns everywhere. No SHINY
     *  bypass - SHINY-affinity gems are locked to SHINY levels like any other. */
    private static double affinityWeight(VisualTheme gemAffinity, VisualTheme levelTheme) {
        if (gemAffinity == null || levelTheme == null) return 1.0;
        return gemAffinity == levelTheme ? 1.0 : 0.0;
    }

    /** Spawn gate from gems.csv: retired species (the pre-merge basics) never
     *  generate. Missing definition rows default to spawnable. */
    private static boolean spawnable(GemSpecies species) {
        GemDefinition d = Registries.gem(species);
        return d == null || d.spawns;
    }

    private static int classWeight(GemClass cls) {
        return switch (cls) {
            case BASIC  -> GameBalance.GEM_WEIGHT_BASIC;
            case METAL  -> GameBalance.GEM_WEIGHT_METAL;
            case EXOTIC -> GameBalance.GEM_WEIGHT_EXOTIC;
        };
    }

    /** Player-facing rarity word for a gem class: BASIC = "simple",
     *  METAL = "metal", EXOTIC = "exotic". Used in recycle/forge descriptions. */
    public static String classLabel(GemClass cls) {
        return switch (cls) {
            case BASIC  -> "simple";
            case METAL  -> "metal";
            case EXOTIC -> "exotic";
        };
    }

    // --- Recycle: item -> gems generation (RL-50) ----------------------------
    /** Source-item power at which METAL / EXOTIC gems become reachable in
     *  {@link #rollRecycleClass}. */
    private static final double RECYCLE_METAL_POWER  = 0.4;
    private static final double RECYCLE_EXOTIC_POWER = 0.7;

    /** Recycle "power" 0..1 for an item: base tier ({@code minPowerLevel}) plus
     *  +0.1 per enchant level, capped at 1. Drives both gem count and rarity
     *  odds. Single source shared by recycling and {@link #recycleForecast}. */
    public static double recyclePower(Item item) {
        if (item == null) return 0.0;
        return Math.min(1.0,
                Math.max(0.0, item.minPowerLevel) + Math.max(0, item.level) * 0.1);
    }

    /** Expected number of gems from recycling at {@code power}. Deliberately low:
     *  a power-0 item averages ~0.25 (a 1-in-4 chance of a single gem), rising
     *  to ~3 at full power. May be below 1, so weak items often yield nothing.
     *  Used by {@link #recycleGemCount} (the live roll) and
     *  {@link #recycleForecast} (the blurb) so they can't drift. */
    public static double recycleExpectedGems(double power) {
        return GameBalance.RECYCLE_BASE_GEMS + power * GameBalance.RECYCLE_GEMS_PER_POWER;
    }

    /** Actual gem count for one recycle: the integer part of the expected value
     *  plus a fractional-chance extra, so a power-0 item drops a gem ~1 time in
     *  4 and richer items a few. Capped at {@code GameBalance.RECYCLE_MAX_GEMS}. */
    public static int recycleGemCount(double power, Random rng) {
        double e = recycleExpectedGems(power);
        int n = (int) Math.floor(e);
        if (rng.nextDouble() < (e - n)) n++;
        return Math.max(0, Math.min(GameBalance.RECYCLE_MAX_GEMS, n));
    }

    /** Rarity class for one recycled gem: better odds of metal / exotic the
     *  higher the source item's power. */
    public static GemClass rollRecycleClass(double power, Random rng) {
        double r = rng.nextDouble();
        if (power >= RECYCLE_EXOTIC_POWER && r < power - 0.5) return GemClass.EXOTIC;
        if (power >= RECYCLE_METAL_POWER  && r < power)       return GemClass.METAL;
        return GemClass.BASIC;
    }

    /** Rough, player-facing description of what recycling {@code item} is likely
     *  to yield - for the recycle confirmation dialog. Mirrors
     *  {@link #recycleGemCount} + {@link #rollRecycleClass} thresholds so the
     *  blurb can't drift from the real rolls. Examples: "a simple gem"; "a few
     *  simple gems and perhaps a metal gem"; "a handful of simple gems, likely a
     *  metal gem, with a chance of an exotic gem". */
    public static String recycleForecast(Item item) {
        if (item == null) return "";
        double power = recyclePower(item);
        double expected = recycleExpectedGems(power);
        boolean metal  = power >= RECYCLE_METAL_POWER;
        boolean exotic = power >= RECYCLE_EXOTIC_POWER;
        String simple = classLabel(GemClass.BASIC);
        // Quantity wording from the EXPECTED gem count (deterministic), with a
        // "chance of" phrasing when the expectation is below one gem.
        StringBuilder sb = new StringBuilder();
        boolean singular;
        if (expected < 0.75) {
            sb.append("a chance of a ").append(simple).append(" gem");
            singular = true;
        } else if (expected < 1.5) {
            sb.append("a ").append(simple).append(" gem");
            singular = true;
        } else if (expected < 2.5) {
            sb.append("a couple of ").append(simple).append(" gems");
            singular = false;
        } else if (expected < 3.5) {
            sb.append("a few ").append(simple).append(" gems");
            singular = false;
        } else {
            sb.append("several ").append(simple).append(" gems");
            singular = false;
        }
        if (exotic) {
            sb.append(singular ? ", possibly a " : ", likely a ").append(classLabel(GemClass.METAL))
              .append(" gem, with a chance of an ").append(classLabel(GemClass.EXOTIC))
              .append(" gem");
        } else if (metal) {
            sb.append(" and perhaps a ").append(classLabel(GemClass.METAL)).append(" gem");
        }
        return sb.toString();
    }

    /** Pick a spawnable gem species of rarity {@code cls} whose affinity admits
     *  {@code levelTheme}. Returns {@code null} when no species of the class may
     *  appear on this theme (e.g. METAL on a SHINY level) - callers skip the
     *  spawn. */
    public static GemSpecies rollSpeciesOfClass(GemClass cls, VisualTheme levelTheme, Random rng) {
        return weightedPick(g -> spawnable(g) && g.gemClass == cls
                ? affinityWeight(themeOf(g), levelTheme) : 0.0, rng);
    }

    /** Pick a spawnable gem species of rarity {@code cls} ignoring level
     *  affinity - for player-driven conversion (hearth recycling), which must
     *  work on every level and still never yield a retired gem. */
    public static GemSpecies rollSpeciesOfClassAnywhere(GemClass cls, Random rng) {
        return weightedPick(g -> spawnable(g) && g.gemClass == cls ? 1.0 : 0.0, rng);
    }

    /** Affinity theme for {@code species} from gems.csv, or {@code null} (no
     *  affinity) when gems.csv wasn't loaded / omits it. */
    private static VisualTheme themeOf(GemSpecies species) {
        GemDefinition d = Registries.gem(species);
        return d == null ? null : d.theme;
    }

    /** Pick any gem species, weighted by rarity class x affinity. Backs the generic
     *  {@code LootCategory.GEM} reference (ANY scatter, themed-room {@code GEM} cells). */
    public static GemSpecies rollSpeciesWeighted(VisualTheme levelTheme, Random rng) {
        return weightedPick(g -> spawnable(g)
                ? classWeight(g.gemClass) * affinityWeight(themeOf(g), levelTheme) : 0.0, rng);
    }

    private interface Weigher { double weight(GemSpecies g); }

    private static GemSpecies weightedPick(Weigher w, Random rng) {
        List<GemSpecies> pool = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        double total = 0;
        for (GemSpecies g : GemSpecies.values()) {
            double weight = w.weight(g);
            if (weight <= 0) continue;
            pool.add(g);
            weights.add(weight);
            total += weight;
        }
        if (pool.isEmpty()) return null;
        double r = rng.nextDouble() * total;
        double acc = 0;
        for (int i = 0; i < pool.size(); i++) {
            acc += weights.get(i);
            if (r < acc) return pool.get(i);
        }
        return pool.get(pool.size() - 1);   // fp-drift fallback
    }

    /** Build a gem of {@code species}. The factory point for level population and loot. */
    public static Item createGem(GemSpecies species) {
        Item it = new Item();
        // Procedural item - null type. Identity is carried by gemSpecies, and Item.isGem
        // reads the species field rather than checking the type.
        it.inventoryCategory = InventoryCategory.GEM;
        it.gemSpecies = species;
        it.name = ItemNames.gemDisplayName(it);
        GemDefinition def = Registries.gem(species);
        it.description = def != null && def.description != null ? def.description : "";
        return it;
    }

    /**
     * Fire a crafted gem-scroll's effect, dispatching on the gem's item type.
     * These are the read-once scrolls forged at a gem hearth (the old gem-slot
     * equip system was retired) - {@link com.bjsp123.rl2.logic.ItemSystem#dropItem}
     * and the forge build them; reading one from the bag routes here via
     * {@code PlayController.triggerCraftedGem}.
     *
     * <p>Returns {@code true} when the effect fired (the caller then consumes the
     * scroll). Returns {@code false} when the scroll found nothing to act on (a
     * {@link com.bjsp123.rl2.logic.ItemSystem#gemFizzle} log fires) or its type
     * has no implementation yet (a stub log fires) - in both cases the scroll is
     * NOT consumed, so the read isn't wasted.
     *
     * <p>Most of the RL-50 roster is implemented (see the cases below); only a
     * few unimplemented types fall through to the stub. {@code target} is the
     * pre-gathered aim tile, non-null only for the tile-targeted scrolls whose
     * {@code useBehavior} is {@link Item.UseBehavior#WAND} (e.g. the Scroll of
     * Probability Inversion). Item-targeted enchant scrolls go through
     * {@link #triggerGemOnItem} instead.
     */
    public static boolean triggerGem(Level level, Mob user, Item gem, com.bjsp123.rl2.model.Point target) {
        if (level == null || user == null || gem == null || gem.type == null) return false;
        switch (gem.type) {
            case "SC_BLAST_THROUGH_WALLS" -> {
                if (target == null) return false;
                // Erupt the detonation directly at the chosen point - seen or
                // unseen, straight through walls. Applying the impact at the
                // target tile (rather than firing a blockable bolt via fireWand)
                // is what lets it reach a point behind cover.
                ItemSystem.applyWandImpact(level, user, target, gem.wandEffect, gem,
                        ItemStats.effectiveLevel(gem, user));
                ItemSystem.announceGemUse(user, gem);
                return true;
            }
            case "SC_FREEZE_ALL_ENEMIES" -> {
                // A swirl of blue-white frost bursts from the caster, then every
                // enemy on the level is frozen solid.
                if (level.events != null && user.position != null) {
                    level.events.add(new GameEvent.WandImpactBurst(
                            user.position, Item.ItemEffect.FREEZE));
                }
                for (Mob m : ItemSystem.enemiesOnLevel(level, user)) {
                    BuffSystem.apply(level, m, com.bjsp123.rl2.model.Buff.BuffType.FROZEN, 99, user, gem);
                }
                ItemSystem.announceGemUse(user, gem);
                return true;
            }
            case "SC_SMOKE_LEVEL_AND_ESP" -> {
                BuffSystem.apply(level, user, com.bjsp123.rl2.model.Buff.BuffType.ESP, 99, user, gem);
                BuffSystem.apply(level, user, com.bjsp123.rl2.model.Buff.BuffType.INSIGHT, 99, user, gem);
                // Fill EVERY eligible (non-blocking) square on the level with smoke.
                for (int x = 0; x < level.width; x++) {
                    for (int y = 0; y < level.height; y++) {
                        if (level.tiles[x][y].blocksMovement()) continue;
                        CloudSystem.addCloud(level, x, y, com.bjsp123.rl2.model.Level.Cloud.SMOKE, 8);
                    }
                }
                ItemSystem.announceGemUse(user, gem);
                return true;
            }
            case "SC_BRAND_WEAPON" -> {
                // No-target fallback only. The normal path lets the player CHOOSE
                // the item to brand (PlayController -> triggerGemOnItem). We land
                // here only when the chooser was skipped (no eligible item), so
                // brand the equipped weapon if there is one, else fizzle.
                Item w = user.inventory != null ? user.inventory.weapon : null;
                if (w == null) { ItemSystem.gemFizzle(user, gem, "has nothing to inscribe"); return false; }
                for (int i = 0; i < 80 && w.brand == null; i++) BrandSystem.applyRandomBrand(w, ItemSystem.RANDOM);
                ItemSystem.announceGemUse(user, gem);
                return true;
            }
            case "SC_UPGRADE_WEAPON" -> {
                // No-target fallback only. The normal path lets the player CHOOSE
                // which item to upgrade (PlayController -> triggerGemOnItem). We
                // land here only when the chooser was skipped (no eligible item),
                // so +1 the equipped weapon if there is one, else fizzle.
                Item w = user.inventory != null ? user.inventory.weapon : null;
                if (w == null) { ItemSystem.gemFizzle(user, gem, "finds nothing to empower"); return false; }
                w.level += 1;
                ItemSystem.announceGemUse(user, gem);
                return true;
            }
            case "SC_RANDOM_TELEPORT" -> {
                // Random teleport to a free floor tile on the current level.
                java.util.List<Point> spots = new java.util.ArrayList<>();
                for (int x = 0; x < level.width; x++) {
                    for (int y = 0; y < level.height; y++) {
                        if (!level.tiles[x][y].isFloorLike()) continue;
                        if (ItemSystem.occupied(level, x, y)) continue;
                        spots.add(new Point(x, y));
                    }
                }
                if (spots.isEmpty()) { ItemSystem.gemFizzle(user, gem, "finds nowhere to send you"); return false; }
                user.position = spots.get(ItemSystem.RANDOM.nextInt(spots.size()));
                ItemSystem.announceGemUse(user, gem);
                return true;
            }
            case "SC_GREAT_WARD" -> {
                // Scroll of Warding: 8 stacks of heavy physical + magical
                // mitigation. (Full negative-status immunity has no dedicated
                // buff yet; PROTECTION + ANTI_MAGIC cover the armour/resist half.)
                BuffSystem.apply(level, user, Buff.BuffType.PROTECTION, 8, user, gem);
                BuffSystem.apply(level, user, Buff.BuffType.ANTI_MAGIC, 8, user, gem);
                ItemSystem.announceGemUse(user, gem);
                return true;
            }
            case "SC_FULL_RESTORE" -> {
                MobSystem.heal(level, user, (int) Math.ceil(user.effectiveStats().maxHp));
                ItemSystem.rechargeAllItems(user);
                int need = MobProgression.xpToReach(user.characterLevel + 1) - user.xp;
                if (need > 0) MobProgression.awardXp(level, user, need);
                ItemSystem.announceGemUse(user, gem);
                return true;
            }
            case "SC_CONJURE_BOMBS" -> {
                // A batch of bombs, as if found CREATION_SCROLL_DEPTH_BONUS levels deeper.
                java.util.List<Item> bombs = ItemGenerator.generateItems(
                        GameBalance.INVOCATION_CHIYOU_BOMB_COUNT,
                        ItemSystem.gemPowerForDepth(level, GameBalance.CREATION_SCROLL_DEPTH_BONUS), level.theme,
                        ItemGenerator.LootCategory.BOMBS, ItemSystem.RANDOM);
                ItemSystem.dropItemsNearPlayer(level, user, bombs);
                ItemSystem.announceGemUse(user, gem);
                return true;
            }
            case "SC_CONJURE_POTIONS" -> {
                // A handful of random potions, as if found DEPTH_BONUS levels deeper.
                java.util.List<Item> potions = ItemGenerator.generateItems(
                        GameBalance.ELIXIR_FORMULA_POTION_COUNT,
                        ItemSystem.gemPowerForDepth(level, GameBalance.ELIXIR_FORMULA_DEPTH_BONUS),
                        level.theme, ItemGenerator.LootCategory.POTIONS, ItemSystem.RANDOM);
                ItemSystem.dropItemsNearPlayer(level, user, potions);
                ItemSystem.announceGemUse(user, gem);
                return true;
            }
            case "SC_HARVEST_PLANTS" -> {
                // Destroy visible grass/trees; leave a healing or mana pill behind.
                double power = ItemSystem.gemPowerForDepth(level, GameBalance.CREATION_SCROLL_DEPTH_BONUS);
                int reaped = 0;
                for (int x = 0; x < level.width; x++) {
                    for (int y = 0; y < level.height; y++) {
                        Level.Vegetation v = level.vegetation != null ? level.vegetation[x][y] : null;
                        if (v != Level.Vegetation.GRASS && v != Level.Vegetation.TREES) continue;
                        if (!MobSystem.tileVisibleToPlayer(level, new Point(x, y))) continue;
                        level.vegetation[x][y] = null;
                        Item pill = ItemGenerator.buildItem(
                                ItemSystem.RANDOM.nextBoolean() ? "HEALTHPILL" : "CHARGEPILL", power, ItemSystem.RANDOM);
                        if (pill != null) {
                            pill.location = new Point(x, y);
                            level.items.add(pill);
                        }
                        reaped++;
                    }
                }
                if (reaped == 0) { ItemSystem.gemFizzle(user, gem, "finds nothing to harvest"); return false; }
                ItemSystem.announceGemUse(user, gem);
                return true;
            }
            case "SC_SEAL_DOORS_ONETIME" -> {
                int n = ItemSystem.convertDoors(level, com.bjsp123.rl2.model.Tile.ONETIME_DOOR);
                if (n == 0) { ItemSystem.gemFizzle(user, gem, "finds no doors to seal"); return false; }
                ItemSystem.announceGemUse(user, gem);
                return true;
            }
            case "SC_SEAL_DOORS_CRYSTAL" -> {
                int n = ItemSystem.convertDoors(level, com.bjsp123.rl2.model.Tile.CRYSTAL_DOOR);
                if (n == 0) { ItemSystem.gemFizzle(user, gem, "finds no doors to seal"); return false; }
                ItemSystem.announceGemUse(user, gem);
                return true;
            }
            case "SC_POLYMORPH_ENEMIES" -> {
                java.util.List<Mob> vis = ItemSystem.visibleEnemies(level, user);
                if (vis.isEmpty()) { ItemSystem.gemFizzle(user, gem, "finds no foe to reshape"); return false; }
                for (Mob m : vis) {
                    String pick = ItemSystem.pickPolymorphReplacement(Integer.MIN_VALUE, m.mobType);
                    if (pick != null) ItemSystem.polymorphMob(level, m, pick);
                }
                ItemSystem.announceGemUse(user, gem);
                return true;
            }
            case "SC_CONJURE_WAND" -> {
                // A random wand, as if found CREATION_SCROLL_DEPTH_BONUS levels deeper.
                double power = ItemSystem.gemPowerForDepth(level, GameBalance.CREATION_SCROLL_DEPTH_BONUS);
                Item wand = null;
                for (int i = 0; i < 30; i++) {
                    Item it = ItemGenerator.generateItem(power, level.theme,
                            ItemGenerator.LootCategory.MAGIC_ITEMS, ItemSystem.RANDOM);
                    if (ItemSystem.isJadeItem(it)) continue;   // scrolls never forge jade
                    if (it != null && it.useBehavior == Item.UseBehavior.WAND) { wand = it; break; }
                    if (wand == null) wand = it;
                }
                if (wand == null) { ItemSystem.gemFizzle(user, gem, "fails to conjure a wand"); return false; }
                ItemSystem.dropItemsNearPlayer(level, user, java.util.List.of(wand));
                ItemSystem.announceGemUse(user, gem);
                return true;
            }
            case "SC_CONJURE_TOOLS" -> {
                // Two random tools, never jade items.
                java.util.List<String> tools = new java.util.ArrayList<>(
                        Registries.itemTypesMatching(d ->
                                d.inventoryCategory == Item.InventoryCategory.TOOL));
                tools.removeIf(t -> t.startsWith("JADE"));
                if (tools.isEmpty()) { ItemSystem.gemFizzle(user, gem, "fails to summon the foxes"); return false; }
                double power = ItemSystem.gemPowerForDepth(level, GameBalance.CREATION_SCROLL_DEPTH_BONUS);
                java.util.List<Item> made = new java.util.ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    Item it = ItemGenerator.buildItem(tools.get(ItemSystem.RANDOM.nextInt(tools.size())), power, ItemSystem.RANDOM);
                    if (it != null) made.add(it);
                }
                ItemSystem.dropItemsNearPlayer(level, user, made);
                ItemSystem.announceGemUse(user, gem);
                return true;
            }
            case "SC_BLOOD_NOVA" -> {
                // Clear visible blood; damage every visible enemy, scaled by blood.
                int blood = 0;
                for (int x = 0; x < level.width; x++) {
                    for (int y = 0; y < level.height; y++) {
                        if (level.surface == null || level.surface[x][y] != Level.Surface.BLOOD) continue;
                        if (!MobSystem.tileVisibleToPlayer(level, new Point(x, y))) continue;
                        level.surface[x][y] = null;
                        blood++;
                    }
                }
                int dmg = 10 + 6 * blood;
                for (Mob m : ItemSystem.visibleEnemies(level, user)) {
                    MobCombat.processAttack(level, user, m, dmg,
                            MobSystem.AttackType.MAGIC, MobSystem.DamageElement.PHYSICAL);
                }
                ItemSystem.announceGemUse(user, gem);
                return true;
            }
            case "SC_IGNITE_NEAR_STATUES" -> {
                // Ignite every flammable square beside a statue or lamp.
                for (int x = 0; x < level.width; x++) {
                    for (int y = 0; y < level.height; y++) {
                        if (!ItemSystem.isStatueOrLamp(level.tiles[x][y])) continue;
                        for (int dx = -1; dx <= 1; dx++) {
                            for (int dy = -1; dy <= 1; dy++) {
                                if (dx == 0 && dy == 0) continue;
                                FireSystem.ignite(level, x + dx, y + dy);
                            }
                        }
                    }
                }
                ItemSystem.announceGemUse(user, gem);
                return true;
            }
            case "SC_SUMMON_CLONES" -> {
                // Eight autonomous allied clones of the player on adjacent tiles.
                if (user.characterClass == null || user.position == null) {
                    ItemSystem.gemFizzle(user, gem, "cannot split itself"); return false;
                }
                String key = "ENEMY_PLAYER_" + user.characterClass.name();
                int px = user.position.tileX(), py = user.position.tileY(), made = 0;
                for (int dy = -1; dy <= 1 && made < 8; dy++) {
                    for (int dx = -1; dx <= 1 && made < 8; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        int x = px + dx, y = py + dy;
                        if (x < 0 || y < 0 || x >= level.width || y >= level.height) continue;
                        if (!level.tiles[x][y].isFloorLike() || ItemSystem.occupied(level, x, y)) continue;
                        Point spot = new Point(x, y);
                        Mob clone = MobFactory.spawn(key, spot);
                        if (clone == null) continue;
                        clone.isClone = true;   // render from the clone sprite column
                        clone.owner = user;
                        clone.faction = user.faction;
                        clone.enemyFactions = user.enemyFactions != null
                                ? new java.util.HashSet<>(user.enemyFactions) : new java.util.HashSet<>();
                        MobProgression.setSpawnLevel(clone, Math.max(1, user.characterLevel));
                        level.mobs.add(clone);
                        if (level.events != null) level.events.add(new GameEvent.MobSpawned(clone, spot));
                        made++;
                    }
                }
                if (made == 0) { ItemSystem.gemFizzle(user, gem, "has no room to multiply"); return false; }
                ItemSystem.announceGemUse(user, gem);
                return true;
            }
            case "SC_MADDEN_ENEMIES" -> {
                java.util.List<Mob> vis = ItemSystem.visibleEnemies(level, user);
                if (vis.isEmpty()) { ItemSystem.gemFizzle(user, gem, "finds no mind to break"); return false; }
                java.util.Set<String> all = new java.util.HashSet<>();
                if (user.faction != null) all.add(user.faction);
                for (Mob m : level.mobs) if (m != null && m.faction != null) all.add(m.faction);
                int idx = 0;
                for (Mob m : vis) { String f = "INSANE_" + (idx++); m.faction = f; all.add(f); }
                for (Mob m : vis) {
                    java.util.Set<String> en = new java.util.HashSet<>(all);
                    en.remove(m.faction);
                    m.enemyFactions = en;
                }
                ItemSystem.announceGemUse(user, gem);
                return true;
            }
            case "SC_CONJURE_ITEMS" -> {
                // Three objects, as if found GameBalance.CREATION_SCROLL_DEPTH_BONUS levels deeper.
                java.util.List<Item> made = ItemGenerator.generateItems(3,
                        ItemSystem.gemPowerForDepth(level, GameBalance.CREATION_SCROLL_DEPTH_BONUS), level.theme,
                        ItemGenerator.LootCategory.NON_GEM, ItemSystem.RANDOM);
                ItemSystem.dropItemsNearPlayer(level, user, made);
                ItemSystem.announceGemUse(user, gem);
                return true;
            }
            case "SC_CONJURE_ARMOR" -> {
                // A piece of armour, as if found GameBalance.CREATION_SCROLL_DEPTH_BONUS levels deeper.
                double power = ItemSystem.gemPowerForDepth(level, GameBalance.CREATION_SCROLL_DEPTH_BONUS);
                Item armor = null;
                for (int i = 0; i < 30; i++) {
                    Item it = ItemGenerator.generateItem(power, level.theme,
                            ItemGenerator.LootCategory.EQUIPMENT, ItemSystem.RANDOM);
                    if (ItemSystem.isJadeItem(it)) continue;   // scrolls never forge jade
                    if (it != null && it.inventoryCategory == Item.InventoryCategory.ARMOR) { armor = it; break; }
                    if (armor == null) armor = it;
                }
                if (armor == null) { ItemSystem.gemFizzle(user, gem, "fails to forge armour"); return false; }
                ItemSystem.dropItemsNearPlayer(level, user, java.util.List.of(armor));
                ItemSystem.announceGemUse(user, gem);
                return true;
            }
            case "SC_CONJURE_BRANDED_SWORD" -> {
                // A branded sword, as if found GameBalance.CREATION_SCROLL_DEPTH_BONUS levels deeper.
                Item sword = ItemGenerator.buildItem("SWORD", ItemSystem.gemPowerForDepth(level, GameBalance.CREATION_SCROLL_DEPTH_BONUS), ItemSystem.RANDOM);
                if (sword == null) { ItemSystem.gemFizzle(user, gem, "fails to forge a blade"); return false; }
                for (int i = 0; i < 80 && sword.brand == null; i++) BrandSystem.applyRandomBrand(sword, ItemSystem.RANDOM);
                ItemSystem.dropItemsNearPlayer(level, user, java.util.List.of(sword));
                ItemSystem.announceGemUse(user, gem);
                return true;
            }
            case "SC_VOID_NEARBY_FLOOR" -> {
                // Eight turns of levitation, then floor within 8 squares becomes
                // chasm. The caster is levitating (flying) so they stay aloft;
                // any non-flying mob now standing over the void falls in.
                BuffSystem.apply(level, user, Buff.BuffType.LEVITATING, 8, user, gem);
                if (user.position != null) {
                    int px = user.position.tileX(), py = user.position.tileY();
                    for (int x = Math.max(0, px - 8); x <= Math.min(level.width - 1, px + 8); x++) {
                        for (int y = Math.max(0, py - 8); y <= Math.min(level.height - 1, py + 8); y++) {
                            if (level.tiles[x][y].isFloorLike()) level.tiles[x][y] = com.bjsp123.rl2.model.Tile.CHASM;
                        }
                    }
                }
                // Drop anyone left standing on the new void (snapshot first -
                // fallToNextLevel relocates mobs off this level's list).
                if (level.mobs != null) {
                    for (Mob m : new java.util.ArrayList<>(level.mobs)) {
                        if (m == null || m == user || m.position == null || m.hp <= 0) continue;
                        int mx = m.position.tileX(), my = m.position.tileY();
                        if (mx < 0 || my < 0 || mx >= level.width || my >= level.height) continue;
                        if (level.tiles[mx][my] == com.bjsp123.rl2.model.Tile.CHASM
                                && !m.effectiveStats().flying) {
                            MobLifecycle.fallToNextLevel(level, m);
                        }
                    }
                }
                ItemSystem.announceGemUse(user, gem);
                return true;
            }
            case "SC_SHIELD_SELF" -> {
                // Invulnerability scroll: push SHIELDED to 100 turns via the
                // cap-override apply (the ordinary SHIELDED cap is 10).
                BuffSystem.apply(level, user, Buff.BuffType.SHIELDED, 100, user, gem, 100);
                ItemSystem.announceGemUse(user, gem);
                return true;
            }
            default -> {
                // Effect not forged yet (RL-50 body). Log a stub; do NOT consume.
                if (user.isPlayer) {
                    EventLog.add(new com.bjsp123.rl2.model.LogEvent(
                            "The " + (gem.name != null ? gem.name : gem.type)
                                    + " shimmers, but its power is not yet forged.",
                            com.bjsp123.rl2.model.LogEvent.EventPriority.HIGH, true));
                }
                return false;
            }
        }
    }

    /**
     * Apply an item-targeting scroll's effect to the player-chosen
     * {@code targetItem}. Returns {@code true} when the effect applied (caller
     * then consumes the scroll); {@code false} otherwise.
     */
    public static boolean triggerGemOnItem(Level level, Mob user, Item gem, Item targetItem) {
        if (gem == null || gem.type == null || targetItem == null) return false;
        switch (gem.type) {
            case "SC_BRAND_WEAPON" -> {
                if (!BrandSystem.isBrandable(targetItem)) return false;
                for (int i = 0; i < 80 && targetItem.brand == null; i++) {
                    BrandSystem.applyRandomBrand(targetItem, ItemSystem.RANDOM);
                }
                if (user != null) user.statsDirty = true;
                ItemSystem.announceGemUse(user, gem);
                return true;
            }
            case "SC_UPGRADE_WEAPON" -> {
                // Equipment and jade companions both have a meaningful level.
                if (!targetItem.isEquippable() && !ItemSystem.isJadeItem(targetItem)) return false;
                targetItem.level += 1;
                if (user != null) user.statsDirty = true;
                ItemSystem.announceGemUse(user, gem);
                return true;
            }
            default -> { return false; }
        }
    }
}
