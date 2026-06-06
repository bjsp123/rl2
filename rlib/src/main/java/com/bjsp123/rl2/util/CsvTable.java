package com.bjsp123.rl2.util;

import com.bjsp123.rl2.model.MinMax;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small CSV reader for the project's data files (mobs.csv, items.csv,
 * themedrooms.csv, config.csv, ...). Self-contained - no third-party
 * dependency.
 *
 * <p>Format:
 * <ul>
 *   <li>{@code #}-prefixed lines are comments (skipped).</li>
 *   <li>Blank lines are skipped.</li>
 *   <li>The first non-comment, non-blank line is the column header.</li>
 *   <li>Quoted fields ({@code "..."}) handle embedded commas. Doubled quotes
 *       inside a quoted field ({@code ""}) emit a single quote.</li>
 *   <li>Empty cell -> empty string (callers turn this into the field default).</li>
 *   <li>Sub-list separator inside a cell: {@code |}. e.g. {@code MOUSE|CAT}.</li>
 * </ul>
 *
 * <p>{@link #rows} is indexed by column name (case-sensitive) so callers can
 * read columns by name instead of position - handy when the CSV grows.
 */
public final class CsvTable {

    public final List<String> headers;
    public final List<Map<String, String>> rows;

    private CsvTable(List<String> headers, List<Map<String, String>> rows) {
        this.headers = Collections.unmodifiableList(headers);
        this.rows    = Collections.unmodifiableList(rows);
    }

    public static CsvTable parse(String text) {
        if (text == null) text = "";
        String[] lines = text.split("\\r?\\n", -1);
        List<String> headers = null;
        List<Map<String, String>> rows = new ArrayList<>();
        for (String line : lines) {
            if (line.isEmpty()) continue;
            // Comment lines are anchored at column 0 - a line whose first
            // non-whitespace char is '#' counts.
            String stripped = line.stripLeading();
            if (stripped.startsWith("#")) continue;
            List<String> cells = parseLine(line);
            if (headers == null) {
                headers = new ArrayList<>(cells.size());
                for (String h : cells) headers.add(h.trim());
                continue;
            }
            // Ignore trailing blank rows.
            if (cells.size() == 1 && cells.get(0).isEmpty()) continue;
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < headers.size(); i++) {
                String value = i < cells.size() ? cells.get(i) : "";
                row.put(headers.get(i), value);
            }
            rows.add(row);
        }
        if (headers == null) headers = new ArrayList<>();
        return new CsvTable(headers, rows);
    }

    /** Parse one CSV line. Handles bare commas, quoted fields, and doubled
     *  quotes inside a quoted field. Honours the RFC 4180 contract that a
     *  quoted field preserves its inner whitespace verbatim - leading and
     *  trailing spaces only get trimmed on UNQUOTED cells. Lets template
     *  strings carry significant whitespace (e.g. " for {turns} turns"
     *  prefixes) by wrapping them in quotes. */
    private static List<String> parseLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;
        boolean cellWasQuoted = false;
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cell.append('"');
                        i += 2;
                        continue;
                    }
                    inQuotes = false;
                    i++;
                    continue;
                }
                cell.append(c);
                i++;
            } else {
                if (c == ',') {
                    out.add(cellWasQuoted ? cell.toString() : cell.toString().trim());
                    cell.setLength(0);
                    cellWasQuoted = false;
                } else if (c == '"' && cell.length() == 0) {
                    inQuotes = true;
                    cellWasQuoted = true;
                } else {
                    cell.append(c);
                }
                i++;
            }
        }
        out.add(cellWasQuoted ? cell.toString() : cell.toString().trim());
        return out;
    }

    // -- Typed cell accessors ------------------------------------------------

    public static String str(Map<String, String> row, String key, String def) {
        String v = row.get(key);
        return (v == null || v.isEmpty()) ? def : v;
    }

    public static int intCell(Map<String, String> row, String key, int def) {
        String v = row.get(key);
        if (v == null || v.isEmpty()) return def;
        return Integer.parseInt(v);
    }

    public static double dblCell(Map<String, String> row, String key, double def) {
        String v = row.get(key);
        if (v == null || v.isEmpty()) return def;
        return Double.parseDouble(v);
    }

    public static boolean boolCell(Map<String, String> row, String key, boolean def) {
        String v = row.get(key);
        if (v == null || v.isEmpty()) return def;
        return Boolean.parseBoolean(v);
    }

    /** Parse {@code N} or {@code MIN_MAX} (e.g. {@code 4} or {@code 2_4}) into
     *  a {@link MinMax}. Returns {@code def} on empty cell. The underscore
     *  separator is used (not the more natural {@code -} or {@code :}) so
     *  spreadsheet editors don't reinterpret cells like {@code 1-2} or
     *  {@code 1:02} as a date or time. */
    public static MinMax minMaxCell(Map<String, String> row, String key, MinMax def) {
        String v = row.get(key);
        if (v == null || v.isEmpty()) return def;
        int sep = v.indexOf('_');
        if (sep < 0) {
            int n = Integer.parseInt(v);
            return MinMax.of(n);
        }
        int min = Integer.parseInt(v.substring(0, sep));
        int max = Integer.parseInt(v.substring(sep + 1));
        return new MinMax(min, max);
    }

    /** Float-range counterpart of {@link #minMaxCell}. Parses {@code N} or
     *  {@code MIN_MAX} (e.g. {@code 0.5} or {@code 0.3_0.7}) into a
     *  {@code [min, max]} double pair. Single-value cells degenerate to
     *  {@code min = max}. Returns the supplied defaults on empty cell. */
    public static double[] dblRangeCell(Map<String, String> row, String key,
                                        double defMin, double defMax) {
        String v = row.get(key);
        if (v == null || v.isEmpty()) return new double[]{defMin, defMax};
        int sep = v.indexOf('_');
        if (sep < 0) {
            double n = Double.parseDouble(v);
            return new double[]{n, n};
        }
        return new double[]{
            Double.parseDouble(v.substring(0, sep)),
            Double.parseDouble(v.substring(sep + 1))
        };
    }

    /** Parsed entry from a spawn-spec list cell - a reference (mob type, item
     *  type, or {@code @category}) plus an optional {@code [min, max]} count
     *  range. Used by both {@code MobDefinition.startingInventory} and the
     *  themed-room mob/item columns; the syntax is the same across all of them. */
    public static final class SpawnSpec {
        public final String ref;
        public final int    min;
        public final int    max;
        public SpawnSpec(String ref, int min, int max) {
            this.ref = ref;
            this.min = min;
            this.max = max;
        }
    }

    /** Parse a pipe-separated spawn-spec cell. Each entry takes the form
     *  {@code <ref>}, {@code <ref>*<count>}, or {@code <ref>*<min>_<max>}. The
     *  reference may be a literal mob/item type or an {@code @category} token -
     *  this parser treats it as opaque text. Examples:
     *  <pre>
     *  DAGGER                                  -> ref=DAGGER,         min=1, max=1
     *  KOBOLD_GENERAL*3                        -> ref=KOBOLD_GENERAL, min=3, max=3
     *  LOATHESOME_BUG*2_3                      -> ref=LOATHESOME_BUG, min=2, max=3
     *  &#64;potion*1                           -> ref=&#64;potion,    min=1, max=1
     *  </pre>
     *  Empty / null cells return an empty list. */
    public static List<SpawnSpec> parseSpawnSpecList(String cell) {
        List<SpawnSpec> out = new ArrayList<>();
        if (cell == null || cell.isEmpty()) return out;
        for (String entry : cell.split("\\|")) {
            String e = entry.trim();
            if (e.isEmpty()) continue;
            int star = e.indexOf('*');
            if (star < 0) {
                out.add(new SpawnSpec(e, 1, 1));
                continue;
            }
            String ref = e.substring(0, star).trim();
            String countPart = e.substring(star + 1).trim();
            int sep = countPart.indexOf('_');
            if (sep < 0) {
                int n = Integer.parseInt(countPart);
                out.add(new SpawnSpec(ref, n, n));
            } else {
                int lo = Integer.parseInt(countPart.substring(0, sep));
                int hi = Integer.parseInt(countPart.substring(sep + 1));
                out.add(new SpawnSpec(ref, lo, hi));
            }
        }
        return out;
    }

    /** Parsed entry from a drop-spec list cell - a keyword (item type, category
     *  name, {@code NONE}, or {@code STUFF}) plus an optional explicit plus-level
     *  ({@code +n}) and a fractional count ({@code *n}). The integer part of
     *  {@code count} is the guaranteed number of items; the fractional remainder
     *  is the probability of one bonus item. {@code plusLevel == -1} means "derive
     *  the item's plus level from the level's power fraction" (the normal path);
     *  a non-negative value overrides the generated plus directly.
     *
     * <p>Used by {@code MobDefinition.drops} (the {@code dropQuality} CSV column). */
    public static final class DropSpec {
        public final String keyword;
        public final int    plusLevel;  // -1 = power-derived
        public final double count;

        public DropSpec(String keyword, int plusLevel, double count) {
            this.keyword   = keyword;
            this.plusLevel = plusLevel;
            this.count     = count;
        }
    }

    /** Parse a pipe-separated drop-spec cell. Each entry takes the form
     *  {@code <keyword>}, {@code <keyword>+<n>}, {@code <keyword>*<n>}, or
     *  {@code <keyword>+<n>*<n>} where {@code n} may be a decimal for the count
     *  part. Keywords: {@code NONE} (no drop), {@code STUFF} (any item), any
     *  {@link com.bjsp123.rl2.logic.ItemGenerator.LootCategory} name, or a
     *  literal item type. Examples:
     *  <pre>
     *  NONE              -> no drop
     *  STUFF             -> 1 random item, power-derived level
     *  STUFF*2.5         -> 2-3 random items (2 guaranteed, 50% chance of third)
     *  EQUIPMENT+2       -> 1 equipment item forced to +2
     *  HEALING_POTION*0.5 -> 50% chance of one healing potion
     *  STUFF+3*2         -> 2 random items each forced to +3
     *  </pre>
     *  Empty / null cells return an empty list. */
    public static List<DropSpec> parseDropSpecList(String cell) {
        List<DropSpec> out = new ArrayList<>();
        if (cell == null || cell.isEmpty()) return out;
        for (String entry : cell.split("\\|")) {
            String e = entry.trim();
            if (e.isEmpty()) continue;

            double count = 1.0;
            int star = e.indexOf('*');
            if (star >= 0) {
                count = Double.parseDouble(e.substring(star + 1).trim());
                e = e.substring(0, star).trim();
            }

            int plusLevel = -1;
            int plus = e.indexOf('+');
            if (plus >= 0) {
                plusLevel = Integer.parseInt(e.substring(plus + 1).trim());
                e = e.substring(0, plus).trim();
            }

            out.add(new DropSpec(e, plusLevel, count));
        }
        return out;
    }

    /** Sub-list cells use {@code |} as the separator. {@code MOUSE|CAT|RAT}
     *  -> {@code [MOUSE, CAT, RAT]}. Empty cell -> empty list. */
    public static List<String> listCell(Map<String, String> row, String key) {
        String v = row.get(key);
        if (v == null || v.isEmpty()) return Collections.emptyList();
        String[] parts = v.split("\\|");
        List<String> out = new ArrayList<>(parts.length);
        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    public static <E extends Enum<E>> E enumCell(Map<String, String> row, String key,
                                                 Class<E> type, E def) {
        String v = row.get(key);
        if (v == null || v.isEmpty()) return def;
        return Enum.valueOf(type, v);
    }
}
