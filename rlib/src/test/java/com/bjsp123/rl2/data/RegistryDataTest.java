package com.bjsp123.rl2.data;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bjsp123.rl2.DataFixture;
import com.bjsp123.rl2.logic.ItemDefinition;
import com.bjsp123.rl2.logic.MobDefinition;
import com.bjsp123.rl2.logic.Registries;
import com.bjsp123.rl2.util.CsvTable;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tier-B data-integrity tests over the real {@code mobs.csv} / {@code items.csv}.
 * These guard the contracts the engine assumes: non-empty registries, no
 * duplicate type keys, well-ordered power bands, and that every cross-reference
 * (retainers, eat/turn spawns, player starting kits) points at a known type.
 */
class RegistryDataTest extends DataFixture {

    // -- registries populate -------------------------------------------------

    @Test
    void registriesAreNonEmpty() {
        assertFalse(Registries.mobTypes().isEmpty(), "no mobs loaded");
        assertFalse(Registries.itemTypes().isEmpty(), "no items loaded");
    }

    // -- mob rows ------------------------------------------------------------

    @Test
    void everyMobHasTypeAndWellOrderedPowerBand() {
        for (String type : Registries.mobTypes()) {
            MobDefinition d = Registries.mob(type);
            assertNotNull(d, "null def for mob " + type);
            assertNotNull(d.type, "mob with null type key");
            assertFalse(d.type.isBlank(), "mob with blank type key");
            assertTrue(d.powerMin <= d.powerMax,
                    "mob " + type + " has powerMin > powerMax");
            assertTrue(d.maxHp >= 0, "mob " + type + " has negative maxHp");
        }
    }

    @Test
    void mobCrossReferencesResolveToKnownTypes() {
        Set<String> known = Registries.mobTypes();
        for (String type : Registries.mobTypes()) {
            MobDefinition d = Registries.mob(type);
            for (String r : d.retainerTypes) {
                assertTrue(known.contains(r),
                        "mob " + type + " retainer references unknown type: " + r);
            }
            assertSpawnRefKnown(known, type, "eatSpawnType", d.eatSpawnType);
            assertSpawnRefKnown(known, type, "mushroomEatSpawnType", d.mushroomEatSpawnType);
            assertSpawnRefKnown(known, type, "turnSpawnType", d.turnSpawnType);
        }
    }

    private static void assertSpawnRefKnown(Set<String> known, String owner,
                                            String field, String ref) {
        if (ref == null || ref.isBlank()) return;
        assertTrue(known.contains(ref),
                "mob " + owner + " " + field + " references unknown type: " + ref);
    }

    @Test
    void playerStartingKitsReferenceKnownItems() {
        Set<String> items = Registries.itemTypes();
        for (String type : Registries.mobTypes()) {
            MobDefinition d = Registries.mob(type);
            for (MobDefinition.StartItem s : d.startingInventory) {
                assertTrue(items.contains(s.type),
                        "mob " + type + " starting item references unknown item: " + s.type);
            }
        }
    }

    @Test
    void mobTypeKeysAreUnique() throws IOException {
        assertNoDuplicateTypeColumn("mobs.csv");
    }

    // -- item rows -----------------------------------------------------------

    @Test
    void everyItemHasTypeCategoryAndWellOrderedPowerBand() {
        for (String type : Registries.itemTypes()) {
            ItemDefinition d = Registries.item(type);
            assertNotNull(d, "null def for item " + type);
            assertNotNull(d.type, "item with null type key");
            assertFalse(d.type.isBlank(), "item with blank type key");
            assertNotNull(d.inventoryCategory,
                    "item " + type + " has no inventoryCategory");
            assertTrue(d.powerMin <= d.powerMax,
                    "item " + type + " has powerMin > powerMax");
        }
    }

    @Test
    void itemTypeKeysAreUnique() throws IOException {
        assertNoDuplicateTypeColumn("items.csv");
    }

    // -- helpers -------------------------------------------------------------

    /** Parse the named CSV directly and assert no {@code type} value repeats -
     *  the registry map would silently collapse duplicates, hiding the error. */
    private static void assertNoDuplicateTypeColumn(String fileName) throws IOException {
        CsvTable table = CsvTable.parse(Files.readString(assets.resolve(fileName)));
        Set<String> seen = new HashSet<>();
        for (Map<String, String> row : table.rows) {
            String type = CsvTable.str(row, "type", "");
            if (type.isEmpty()) continue;
            assertTrue(seen.add(type),
                    fileName + " has duplicate type key: " + type);
        }
    }
}
