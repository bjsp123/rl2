package com.bjsp123.rl2.world.render;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Item.ItemType;

import java.util.EnumMap;
import java.util.Map;

/**
 * Shared lazy-loaded sprite source for {@link Item}s. Three places need to render the
 * same item picture — the inventory popup, the HUD's action-bar quickslots, and the
 * dungeon floor where the item lives — and they all go through this class so the art
 * stays in sync.
 *
 * <p>Sprite layout: {@code sprites/items.png} is a 32×32 grid; rows are wands / food /
 * potions / bombs / (blank) / armor / shield / amulet / sword+dagger.
 * The texture loads on first call and stays live for the lifetime of the JVM. Call
 * {@link #disposeShared} on shutdown if you care about clean tear-down.
 */
public final class ItemSprites {

    private static Texture saiItemsTex; // sprites/items.png (32-px grid)
    private static Map<ItemType, TextureRegion> regions;

    private ItemSprites() {}

    /** TextureRegion for the item's type, or {@code null} when no sprite is mapped
     *  (e.g. a future ItemType added but not yet wired here). */
    public static TextureRegion regionFor(Item item) {
        if (item == null || item.type == null) return null;
        // Gems are procedural — species + size produce a unique colour-and-shape icon
        // that ItemType alone can't address. GemSprites caches per-(species, size).
        if (item.isGem()) return GemSprites.regionFor(item);
        if (regions == null) load();
        return regions.get(item.type);
    }

    /** Direct ItemType lookup — used by code paths that don't have an Item handle
     *  (e.g. silhouette rendering for an empty slot). */
    public static TextureRegion regionFor(ItemType type) {
        if (type == null) return null;
        if (regions == null) load();
        return regions.get(type);
    }

    private static void load() {
        regions = new EnumMap<>(ItemType.class);
        saiItemsTex = nearest("sprites/items.png");
        if (saiItemsTex == null) return;
        // Equipment art lives on sai/items.png (column 0, dedicated rows). Dagger sits
        // next to sword in column 1 — it's the mage's and rogue's starting weapon
        // (weaker than the warrior's sword).
        regions.put(ItemType.SCALE_MAIL,      sai(0, 4));
        regions.put(ItemType.SHIELD,          sai(0, 5));
        regions.put(ItemType.AMULET_OF_LIGHT, sai(0, 6));
        regions.put(ItemType.SWORD,           sai(0, 7));
        regions.put(ItemType.DAGGER,          sai(1, 7));
        // Row 0 — wands.
        regions.put(ItemType.WAND_MAGIC_MISSILE, sai(0, 0));
        regions.put(ItemType.WAND_OIL,           sai(1, 0));
        regions.put(ItemType.WAND_FIRE,          sai(2, 0));
        regions.put(ItemType.WAND_DOG,           sai(3, 0));
        regions.put(ItemType.WAND_WATER,         sai(4, 0));
        regions.put(ItemType.WAND_GRASS,         sai(5, 0));
        regions.put(ItemType.WAND_BANISHMENT,    sai(6, 0));
        // Wands without dedicated art get sensible neighbour fallbacks.
        regions.put(ItemType.WAND_FUNGUS,        sai(5, 0));
        regions.put(ItemType.WAND_DETONATION,    sai(2, 0));
        // Row 1 — food.
        regions.put(ItemType.PEAR,             sai(0, 1));
        regions.put(ItemType.FISH,             sai(1, 1));
        regions.put(ItemType.PEAR_SCRUMPTIOUS, sai(2, 1));
        regions.put(ItemType.PEAR_SILVERY,     sai(3, 1));
        regions.put(ItemType.PEAR_CONFERENCE,  sai(4, 1));
        // Row 2 — potions.
        regions.put(ItemType.POTION_SORCERY,      sai(0, 2));
        regions.put(ItemType.POTION_GHOSTLINESS,  sai(1, 2));
        regions.put(ItemType.HEALING_POTION,      sai(2, 2));
        regions.put(ItemType.POTION_INVISIBILITY, sai(3, 2));
        regions.put(ItemType.POTION_POISON,       sai(4, 2));
        // Row 3 — bombs.
        regions.put(ItemType.FIRE_BOMB,   sai(0, 3));
        regions.put(ItemType.OIL_BOMB,    sai(1, 3));
        regions.put(ItemType.BLAST_BOMB,  sai(2, 3));
        regions.put(ItemType.FREEZE_BOMB, sai(3, 3));
    }

    /** 32×32 cell at {@code (col, row)} on {@code sprites/items.png}. */
    private static TextureRegion sai(int col, int row) {
        return saiItemsTex == null ? null
                : new TextureRegion(saiItemsTex, col * 32, row * 32, 32, 32);
    }

    private static Texture nearest(String path) {
        try {
            Texture t = new Texture(Gdx.files.internal(path));
            t.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            return t;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Release the shared texture. Subsequent {@link #regionFor} calls will reload. */
    public static void disposeShared() {
        if (saiItemsTex != null) { saiItemsTex.dispose(); saiItemsTex = null; }
        regions = null;
    }
}
