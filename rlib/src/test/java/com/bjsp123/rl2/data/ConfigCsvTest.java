package com.bjsp123.rl2.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.bjsp123.rl2.DataFixture;
import com.bjsp123.rl2.logic.GameBalance;
import com.bjsp123.rl2.util.CsvTable;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tier-B integrity tests over the real {@code config.csv}. The production
 * {@link GameBalance#load} silently ignores any key it can't resolve - so a
 * typo'd key would quietly leave the baked default in place with no error. These
 * tests make that failure mode loud: every UPPER_SNAKE {@code gamebalance} key
 * must name a real, overridable field, and its parsed value must actually land
 * on that field after a load.
 */
class ConfigCsvTest extends DataFixture {

    private static String configText() throws IOException {
        return Files.readString(assets.resolve("config.csv"));
    }

    @Test
    void configParsesAndHasGameBalanceRows() throws IOException {
        CsvTable table = CsvTable.parse(configText());
        long gb = table.rows.stream()
                .filter(r -> "gamebalance".equals(CsvTable.str(r, "kind", "")))
                .count();
        assertTrue(gb > 0, "config.csv has no gamebalance rows");
    }

    @Test
    void everyGameBalanceKeyResolvesAndApplies() throws IOException {
        String text = configText();
        // Apply the file the way production does, then verify each scalar field
        // actually took the configured value.
        GameBalance.load(text);
        CsvTable table = CsvTable.parse(text);
        for (Map<String, String> row : table.rows) {
            if (!"gamebalance".equals(CsvTable.str(row, "kind", ""))) continue;
            String key = CsvTable.str(row, "key", "");
            String value = CsvTable.str(row, "value", "");
            if (key.isEmpty() || value.isEmpty()) continue;
            // Every gamebalance key is expected to be canonical UPPER_SNAKE that
            // maps 1:1 to a GameBalance field. (Legacy dotted-camel keys routed
            // through the load normalizer were error-prone - a name with a run of
            // capitals like "...NoLOSNow" silently failed to resolve - so they
            // were retired in favour of exact field names.)
            assertTrue(key.matches("[A-Z0-9_]+"),
                    "config gamebalance key is not canonical UPPER_SNAKE: " + key);

            Field f;
            try {
                f = GameBalance.class.getDeclaredField(key);
            } catch (NoSuchFieldException e) {
                fail("config.csv gamebalance key does not match any GameBalance field: " + key);
                continue;
            }
            int mods = f.getModifiers();
            assertTrue(Modifier.isStatic(mods) && !Modifier.isFinal(mods),
                    "config key " + key + " maps to a non-overridable (static/final) field");
            f.setAccessible(true);
            assertFieldEqualsValue(f, key, value);
        }
    }

    private static void assertFieldEqualsValue(Field f, String key, String value) {
        try {
            Class<?> t = f.getType();
            if (t == int.class) {
                assertEquals(Integer.parseInt(value), f.getInt(null), "int field " + key);
            } else if (t == double.class) {
                assertEquals(Double.parseDouble(value), f.getDouble(null), 1e-9, "double field " + key);
            } else if (t == long.class) {
                assertEquals(Long.parseLong(value), f.getLong(null), "long field " + key);
            } else if (t == boolean.class) {
                assertEquals(Boolean.parseBoolean(value), f.getBoolean(null), "boolean field " + key);
            }
            // Other field types: resolution alone (above) is the contract.
        } catch (IllegalAccessException e) {
            fail("could not read GameBalance." + key + ": " + e.getMessage());
        }
    }

    /** Read one field of a {@link GameBalance.DifficultyTuning} by its config
     *  field name, as a double (booleans map to 0/1). */
    private static double tuningField(GameBalance.DifficultyTuning t, String field) {
        return switch (field) {
            case "PLAYER_HP_MULT"     -> t.playerHpMult();
            case "ENEMY_HP_MULT"      -> t.enemyHpMult();
            case "REGEN_FRAC"         -> t.regenFracPerTurn();
            case "SPAWN_CADENCE_MULT" -> t.spawnCadenceMult();
            case "SPEED_MULT"         -> t.playerSpeedMult();
            case "REVIVE_CHARMS"      -> t.startingReviveCharms();
            case "JADE_FREE_CHARGES"  -> t.jadeItemsFreeCharges() ? 1 : 0;
            case "SCORE_MULT"         -> t.scoreMult();
            default -> throw new IllegalArgumentException("unknown difficulty field: " + field);
        };
    }

    @Test
    void everyDifficultyRowResolvesAndApplies() throws IOException {
        String text = configText();
        GameBalance.load(text);
        CsvTable table = CsvTable.parse(text);
        int seen = 0;
        for (Map<String, String> row : table.rows) {
            if (!"difficulty".equals(CsvTable.str(row, "kind", ""))) continue;
            String key = CsvTable.str(row, "key", "");
            String value = CsvTable.str(row, "value", "");
            if (key.isEmpty() || value.isEmpty()) continue;
            seen++;
            int dot = key.indexOf('.');
            assertTrue(dot > 0, "difficulty key is not <TIER>.<FIELD>: " + key);
            GameBalance.Difficulty tier;
            try {
                tier = GameBalance.Difficulty.valueOf(key.substring(0, dot));
            } catch (IllegalArgumentException e) {
                fail("config.csv difficulty key names an unknown tier: " + key);
                continue;
            }
            String field = key.substring(dot + 1);
            double expected = "true".equals(value) ? 1
                    : "false".equals(value) ? 0
                    : Double.parseDouble(value);
            assertEquals(expected, tuningField(GameBalance.tuningFor(tier), field), 1e-9,
                    "difficulty row did not land: " + key);
        }
        assertTrue(seen > 0, "config.csv has no difficulty rows");
    }

    @Test
    void difficultyRowOverrideAppliesAndBadRowsAreSkipped() {
        double before = GameBalance.tuningFor(GameBalance.Difficulty.NORMAL).scoreMult();
        // Unknown tier, unknown field, and malformed value must all be swallowed
        // (warn + keep baseline), while the valid override lands.
        GameBalance.load("kind,key,value\n"
                + "difficulty,NOT_A_TIER.SCORE_MULT,9.5\n"
                + "difficulty,NORMAL.NOT_A_FIELD,9.5\n"
                + "difficulty,NORMAL.SCORE_MULT,banana\n"
                + "difficulty,NORMAL.SCORE_MULT,9.5\n");
        assertEquals(9.5, GameBalance.scoreMultiplier(GameBalance.Difficulty.NORMAL), 1e-9);
        // Restore the baseline so later tests see the shipped value.
        GameBalance.load("kind,key,value\ndifficulty,NORMAL.SCORE_MULT," + before + "\n");
        assertEquals(before, GameBalance.scoreMultiplier(GameBalance.Difficulty.NORMAL), 1e-9);
    }

    @Test
    void loadIgnoresUnknownKeyWithoutThrowing() {
        int before = GameBalance.MAX_CHARACTER_LEVEL;
        // A bogus row must be swallowed, leaving real fields untouched.
        GameBalance.load("kind,key,value\ngamebalance,NOT_A_REAL_KEY_XYZ,123\n");
        assertEquals(before, GameBalance.MAX_CHARACTER_LEVEL);
    }

    @Test
    void loadToleratesNullAndEmptyText() {
        GameBalance.load(null);  // must not throw
        GameBalance.load("");    // must not throw
    }
}
