package com.bjsp123.rl2.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bjsp123.rl2.model.MinMax;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CsvTable} - the hand-rolled CSV reader behind every
 * data file. Covers happy-path parsing AND the defensive contracts the project
 * relies on: comment/blank skipping, quoted fields, short/long rows, and the
 * crucial "enumCell never throws on a bad token" rule (hand-edited CSVs).
 */
class CsvTableTest {

    private enum Fruit { APPLE, PEAR }

    // -- parsing happy path --------------------------------------------------

    @Test
    void parsesHeaderAndRows() {
        CsvTable t = CsvTable.parse("a,b,c\n1,2,3\n4,5,6\n");
        assertEquals(List.of("a", "b", "c"), t.headers);
        assertEquals(2, t.rows.size());
        assertEquals("2", t.rows.get(0).get("b"));
        assertEquals("6", t.rows.get(1).get("c"));
    }

    @Test
    void skipsCommentAndBlankLines() {
        CsvTable t = CsvTable.parse("# comment\n\na,b\n# mid comment\n1,2\n\n");
        assertEquals(List.of("a", "b"), t.headers);
        assertEquals(1, t.rows.size());
        assertEquals("1", t.rows.get(0).get("a"));
    }

    @Test
    void quotedFieldKeepsEmbeddedCommaAndWhitespace() {
        CsvTable t = CsvTable.parse("a,b\n\" hello, world \",x\n");
        assertEquals(" hello, world ", t.rows.get(0).get("a"));
        assertEquals("x", t.rows.get(0).get("b"));
    }

    @Test
    void doubledQuotesEmitSingleQuote() {
        CsvTable t = CsvTable.parse("a\n\"say \"\"hi\"\"\"\n");
        assertEquals("say \"hi\"", t.rows.get(0).get("a"));
    }

    @Test
    void unquotedCellsAreTrimmed() {
        CsvTable t = CsvTable.parse("a,b\n  x  ,  y\n");
        assertEquals("x", t.rows.get(0).get("a"));
        assertEquals("y", t.rows.get(0).get("b"));
    }

    // -- parsing unhappy / edge ----------------------------------------------

    @Test
    void nullTextYieldsEmptyTable() {
        CsvTable t = CsvTable.parse(null);
        assertTrue(t.headers.isEmpty());
        assertTrue(t.rows.isEmpty());
    }

    @Test
    void emptyTextYieldsEmptyTable() {
        CsvTable t = CsvTable.parse("");
        assertTrue(t.headers.isEmpty());
        assertTrue(t.rows.isEmpty());
    }

    @Test
    void shortRowFillsMissingCellsWithEmptyString() {
        CsvTable t = CsvTable.parse("a,b,c\n1\n");
        Map<String, String> row = t.rows.get(0);
        assertEquals("1", row.get("a"));
        assertEquals("", row.get("b"));
        assertEquals("", row.get("c"));
    }

    @Test
    void extraCellsBeyondHeaderAreDropped() {
        CsvTable t = CsvTable.parse("a,b\n1,2,3,4\n");
        Map<String, String> row = t.rows.get(0);
        assertEquals("1", row.get("a"));
        assertEquals("2", row.get("b"));
        assertEquals(1, t.rows.size());
    }

    @Test
    void resultCollectionsAreUnmodifiable() {
        CsvTable t = CsvTable.parse("a\n1\n");
        assertThrows(UnsupportedOperationException.class,
                () -> t.rows.add(Map.of()));
        assertThrows(UnsupportedOperationException.class,
                () -> t.headers.add("b"));
    }

    // -- typed accessors: happy ----------------------------------------------

    @Test
    void typedAccessorsReadValues() {
        CsvTable t = CsvTable.parse("i,d,b\n7,1.5,true\n");
        Map<String, String> row = t.rows.get(0);
        assertEquals(7, CsvTable.intCell(row, "i", -1));
        assertEquals(1.5, CsvTable.dblCell(row, "d", -1), 0.0);
        assertTrue(CsvTable.boolCell(row, "b", false));
        assertEquals("7", CsvTable.str(row, "i", "def"));
    }

    @Test
    void typedAccessorsReturnDefaultOnEmptyOrMissing() {
        CsvTable t = CsvTable.parse("i,d,b,s\n,,,\n");
        Map<String, String> row = t.rows.get(0);
        assertEquals(42, CsvTable.intCell(row, "i", 42));
        assertEquals(3.14, CsvTable.dblCell(row, "d", 3.14), 0.0);
        assertTrue(CsvTable.boolCell(row, "b", true));
        assertEquals("fallback", CsvTable.str(row, "s", "fallback"));
        // Absent key altogether.
        assertEquals(99, CsvTable.intCell(row, "nope", 99));
    }

    @Test
    void minMaxCellParsesSingleAndRange() {
        CsvTable t = CsvTable.parse("a,b\n4,2_7\n");
        Map<String, String> row = t.rows.get(0);
        assertEquals(MinMax.of(4), CsvTable.minMaxCell(row, "a", MinMax.ZERO));
        assertEquals(new MinMax(2, 7), CsvTable.minMaxCell(row, "b", MinMax.ZERO));
        assertEquals(MinMax.ZERO, CsvTable.minMaxCell(row, "missing", MinMax.ZERO));
    }

    @Test
    void listCellSplitsOnPipe() {
        CsvTable t = CsvTable.parse("a\nMOUSE|CAT|RAT\n");
        assertEquals(List.of("MOUSE", "CAT", "RAT"),
                CsvTable.listCell(t.rows.get(0), "a"));
        assertTrue(CsvTable.listCell(t.rows.get(0), "missing").isEmpty());
    }

    @Test
    void spawnSpecParsesCountForms() {
        List<CsvTable.SpawnSpec> specs =
                CsvTable.parseSpawnSpecList("DAGGER|KOBOLD*3|BUG*2_3");
        assertEquals(3, specs.size());
        assertEquals("DAGGER", specs.get(0).ref);
        assertEquals(1, specs.get(0).min);
        assertEquals(1, specs.get(0).max);
        assertEquals(3, specs.get(1).min);
        assertEquals(3, specs.get(1).max);
        assertEquals(2, specs.get(2).min);
        assertEquals(3, specs.get(2).max);
    }

    @Test
    void dropSpecParsesPlusAndCount() {
        List<CsvTable.DropSpec> drops =
                CsvTable.parseDropSpecList("STUFF+3*2|HEALING_POTION*0.5");
        assertEquals(2, drops.size());
        assertEquals("STUFF", drops.get(0).keyword);
        assertEquals(3, drops.get(0).plusLevel);
        assertEquals(2.0, drops.get(0).count, 0.0);
        assertEquals(-1, drops.get(1).plusLevel); // power-derived default
        assertEquals(0.5, drops.get(1).count, 0.0);
    }

    @Test
    void spawnAndDropSpecsOnEmptyCellAreEmpty() {
        assertTrue(CsvTable.parseSpawnSpecList(null).isEmpty());
        assertTrue(CsvTable.parseSpawnSpecList("").isEmpty());
        assertTrue(CsvTable.parseDropSpecList(null).isEmpty());
        assertTrue(CsvTable.parseDropSpecList("").isEmpty());
    }

    // -- typed accessors: unhappy --------------------------------------------

    @Test
    void enumCellReturnsDefaultOnUnknownTokenWithoutThrowing() {
        // The key defensive contract: a typo in a hand-edited CSV must not
        // abort the whole load.
        CsvTable t = CsvTable.parse("f\nBANANA\n");
        Fruit got = CsvTable.enumCell(t.rows.get(0), "f", Fruit.class, Fruit.PEAR);
        assertEquals(Fruit.PEAR, got);
    }

    @Test
    void enumCellParsesValidTokenAndToleratesWhitespace() {
        CsvTable t = CsvTable.parse("f\n\"  APPLE  \"\n");
        assertEquals(Fruit.APPLE,
                CsvTable.enumCell(t.rows.get(0), "f", Fruit.class, Fruit.PEAR));
    }

    @Test
    void enumCellReturnsDefaultOnEmptyOrMissing() {
        CsvTable t = CsvTable.parse("f\n\n");
        // No data rows at all -> craft an empty row via a present-but-blank cell.
        CsvTable t2 = CsvTable.parse("f,g\n,APPLE\n");
        assertEquals(Fruit.PEAR,
                CsvTable.enumCell(t2.rows.get(0), "f", Fruit.class, Fruit.PEAR));
        assertEquals(Fruit.PEAR,
                CsvTable.enumCell(t2.rows.get(0), "absent", Fruit.class, Fruit.PEAR));
        assertTrue(t.rows.isEmpty());
    }

    @Test
    void intCellThrowsOnNonNumericValue() {
        // intCell is intentionally strict (unlike enumCell) - a non-numeric
        // token is a hard error, not a silent default.
        CsvTable t = CsvTable.parse("i\nNaNxyz\n");
        assertThrows(NumberFormatException.class,
                () -> CsvTable.intCell(t.rows.get(0), "i", 0));
    }

    @Test
    void minMaxCellThrowsOnNonNumericRange() {
        CsvTable t = CsvTable.parse("a\nx_y\n");
        assertThrows(NumberFormatException.class,
                () -> CsvTable.minMaxCell(t.rows.get(0), "a", MinMax.ZERO));
    }

    @Test
    void boolCellTreatsNonTrueAsFalse() {
        // Boolean.parseBoolean: anything but case-insensitive "true" is false.
        CsvTable t = CsvTable.parse("b\nyes\n");
        assertFalse(CsvTable.boolCell(t.rows.get(0), "b", true));
    }
}
