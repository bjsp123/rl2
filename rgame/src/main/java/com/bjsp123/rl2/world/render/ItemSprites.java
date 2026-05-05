package com.bjsp123.rl2.world.render;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.bjsp123.rl2.logic.ItemDefinition;
import com.bjsp123.rl2.logic.ItemRegistry;
import com.bjsp123.rl2.model.Item;

import java.util.HashMap;
import java.util.Map;

/**
 * Shared lazy-loaded sprite source for {@link Item}s. Three places need to render the
 * same item picture — the inventory popup, the HUD's action-bar quickslots, and the
 * dungeon floor where the item lives — and they all go through this class so the art
 * stays in sync.
 *
 * <p>Sprite-cell coordinates live on each item's row in
 * {@code assets/data/items.csv} ({@code spriteCol} / {@code spriteRow} columns).
 * The atlas itself is {@code sprites/items.png}, a 32-px grid; the texture loads on
 * first call and stays live for the lifetime of the JVM. Call {@link #disposeShared}
 * on shutdown if you care about clean tear-down.
 */
public final class ItemSprites {

    private static final int CELL = 32;

    private static Texture saiItemsTex; // sprites/items.png (32-px grid)
    private static Map<String, TextureRegion> regions;

    private ItemSprites() {}

    /** TextureRegion for the item, or {@code null} when no sprite is mapped
     *  (procedural items go through their own path; missing rows return null). */
    public static TextureRegion regionFor(Item item) {
        if (item == null) return null;
        // Gems are procedural — species + size produce a unique colour-and-shape icon
        // that the registry can't address. GemSprites caches per-(species, size).
        if (item.isGem()) return GemSprites.regionFor(item);
        return regionFor(item.type);
    }

    /** Direct type lookup — used by code paths that don't have an Item handle
     *  (e.g. silhouette rendering for an empty slot). */
    public static TextureRegion regionFor(String type) {
        if (type == null) return null;
        if (regions == null) load();
        return regions.get(type);
    }

    private static void load() {
        regions = new HashMap<>();
        saiItemsTex = nearest("sprites/items.png");
        if (saiItemsTex == null) return;
        // Pull (col, row) for every catalogued item type from ItemRegistry. New
        // items added to items.csv pick up sprites here without any code change.
        for (String type : ItemRegistry.knownTypes()) {
            ItemDefinition def = ItemRegistry.get(type);
            if (def == null) continue;
            regions.put(type, sai(def.spriteCol, def.spriteRow));
        }
    }

    /** 32×32 cell at {@code (col, row)} on {@code sprites/items.png}. */
    private static TextureRegion sai(int col, int row) {
        return saiItemsTex == null ? null
                : new TextureRegion(saiItemsTex, col * CELL, row * CELL, CELL, CELL);
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
