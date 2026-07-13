package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.util.CsvTable;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Single player-facing string source. The game loads {@code assets/data/strings.csv}
 *  at startup; code asks for stable keys and supplies template values. */
public final class TextCatalog {

    private static final String DEFAULT_LOCALE = "en";
    private static String locale = DEFAULT_LOCALE;
    private static final Map<String, Map<String, String>> STRINGS = new LinkedHashMap<>();

    private TextCatalog() {}

    public static void load(String csv) {
        STRINGS.clear();
        CsvTable table = CsvTable.parse(csv);
        for (Map<String, String> row : table.rows) {
            String key = CsvTable.str(row, "key", "");
            if (key.isEmpty()) continue;
            for (String header : table.headers) {
                if ("key".equals(header)) continue;
                String value = CsvTable.str(row, header, null);
                if (value == null) continue;
                STRINGS.computeIfAbsent(header, ignored -> new LinkedHashMap<>())
                        .put(key, value);
            }
        }
    }

    public static void setLocale(String newLocale) {
        locale = newLocale == null || newLocale.isBlank()
                ? DEFAULT_LOCALE
                : newLocale.trim();
    }

    public static String get(String key) {
        return getOrDefault(key, "??" + key + "??");
    }

    public static String getOrDefault(String key, String fallback) {
        if (key == null || key.isEmpty()) return fallback == null ? "" : fallback;
        String active = lookup(locale, key);
        if (active != null) return active;
        String english = lookup(DEFAULT_LOCALE, key);
        if (english != null) return english;
        return fallback == null ? "" : fallback;
    }

    public static String format(String key, Map<String, ?> vars) {
        return formatText(get(key), vars);
    }

    public static String formatText(String template, Map<String, ?> vars) {
        if (template == null || template.isEmpty() || vars == null || vars.isEmpty()) {
            return template == null ? "" : template;
        }
        String out = template;
        for (Map.Entry<String, ?> e : vars.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", String.valueOf(e.getValue()));
        }
        return out;
    }

    public static Map<String, Object> vars(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            out.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return out;
    }

    public static String itemName(String type, String fallback) {
        return getOrDefault("item." + type + ".name", fallback);
    }

    public static String itemDescription(String type, String fallback) {
        return getOrDefault("item." + type + ".description", fallback);
    }

    public static String itemDescription2(String type, String fallback) {
        return getOrDefault("item." + type + ".description2", fallback);
    }

    // Gem names/descriptions moved to gems.csv (GemDefinition.name/description).

    public static String mobName(String type, String fallback) {
        return getOrDefault("mob." + type + ".name", fallback);
    }

    public static String mobDescription(String type, String fallback) {
        return getOrDefault("mob." + type + ".description", fallback);
    }

    public static String brandName(String type, String fallback) {
        return getOrDefault("brand." + type + ".name", fallback);
    }

    public static String brandDescription(String type, String fallback) {
        return getOrDefault("brand." + type + ".description", fallback);
    }

    public static String capitalize(String text) {
        if (text == null || text.isEmpty()) return "";
        int first = text.offsetByCodePoints(0, 1);
        return text.substring(0, first).toUpperCase(Locale.ROOT) + text.substring(first);
    }

    /** Title Case for headings: capitalizes the first letter of every word
     *  (whitespace- or hyphen-separated). Names are stored lowercase in the
     *  catalog (house style) and Title-Cased only where they render as a
     *  heading (buff info title, perk rows, encyclopedia detail pages). */
    public static String titleCase(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder out = new StringBuilder(text.length());
        boolean atWordStart = true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            out.append(atWordStart ? Character.toUpperCase(c) : c);
            atWordStart = Character.isWhitespace(c) || c == '-';
        }
        return out.toString();
    }

    public static String plural(String singular, int count) {
        if (singular == null || singular.isEmpty() || count == 1) return singular == null ? "" : singular;
        if (singular.endsWith("s") || singular.endsWith("x") || singular.endsWith("ch") || singular.endsWith("sh")) {
            return singular + "es";
        }
        if (singular.endsWith("y") && singular.length() > 1
                && !"aeiou".contains(singular.substring(singular.length() - 2, singular.length() - 1).toLowerCase(Locale.ROOT))) {
            return singular.substring(0, singular.length() - 1) + "ies";
        }
        return singular + "s";
    }

    public static String indefiniteArticle(String text) {
        if (text == null || text.isBlank()) return "";
        char c = Character.toLowerCase(text.trim().charAt(0));
        return "aeiou".indexOf(c) >= 0 ? "an" : "a";
    }

    public static String withIndefiniteArticle(String text) {
        return indefiniteArticle(text) + " " + text;
    }

    private static String lookup(String loc, String key) {
        Map<String, String> map = STRINGS.get(loc);
        if (map == null) return null;
        String value = map.get(key);
        return value == null || value.isEmpty() ? null : value;
    }
}
