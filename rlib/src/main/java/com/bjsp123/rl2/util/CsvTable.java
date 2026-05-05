package com.bjsp123.rl2.util;

import com.bjsp123.rl2.model.MinMax;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small CSV reader for the project's data files (mobs.csv, items.csv,
 * mob_sprites.csv, item_sprites.csv). Self-contained — no third-party
 * dependency.
 *
 * <p>Format:
 * <ul>
 *   <li>{@code #}-prefixed lines are comments (skipped).</li>
 *   <li>Blank lines are skipped.</li>
 *   <li>The first non-comment, non-blank line is the column header.</li>
 *   <li>Quoted fields ({@code "..."}) handle embedded commas. Doubled quotes
 *       inside a quoted field ({@code ""}) emit a single quote.</li>
 *   <li>Empty cell → empty string (callers turn this into the field default).</li>
 *   <li>Sub-list separator inside a cell: {@code |}. e.g. {@code MOUSE|CAT}.</li>
 * </ul>
 *
 * <p>{@link #rows} is indexed by column name (case-sensitive) so callers can
 * read columns by name instead of position — handy when the CSV grows.
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
            // Comment lines are anchored at column 0 — a line whose first
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
     *  quotes inside a quoted field. Returned cells are NOT trimmed (a quoted
     *  field with leading whitespace inside the quotes preserves it). */
    private static List<String> parseLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;
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
                    out.add(cell.toString().trim());
                    cell.setLength(0);
                } else if (c == '"' && cell.length() == 0) {
                    inQuotes = true;
                } else {
                    cell.append(c);
                }
                i++;
            }
        }
        out.add(cell.toString().trim());
        return out;
    }

    // ── Typed cell accessors ────────────────────────────────────────────────

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

    /** Sub-list cells use {@code |} as the separator. {@code MOUSE|CAT|RAT}
     *  → {@code [MOUSE, CAT, RAT]}. Empty cell → empty list. */
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
