package com.bjsp123.rl2.ui;

/**
 * Display-name helpers for the UI layer. Lives in rgame because it's a
 * presentation concern — game logic in rlib stores names verbatim.
 */
public final class Names {

    private Names() {}

    /**
     * Capitalises the initial letter of each word, except the connector
     * {@code "of"} which is forced lowercase. Whitespace and the rest of each
     * word are left untouched, so an already-formatted name like
     * {@code "Wand of Light"} round-trips, and an all-lowercase factory string
     * like {@code "wand of magic missile"} becomes {@code "Wand of Magic Missile"}.
     *
     * <p>Null and empty inputs pass through unchanged.
     */
    public static String titleCase(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            // Pass whitespace through verbatim.
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                sb.append(s.charAt(i));
                i++;
            }
            int start = i;
            while (i < s.length() && !Character.isWhitespace(s.charAt(i))) i++;
            if (start == i) continue;
            String word = s.substring(start, i);
            if (word.equalsIgnoreCase("of")) {
                sb.append("of");
            } else {
                sb.append(Character.toUpperCase(word.charAt(0)));
                sb.append(word, 1, word.length());
            }
        }
        return sb.toString();
    }
}
