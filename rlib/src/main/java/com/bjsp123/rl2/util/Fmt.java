package com.bjsp123.rl2.util;

/**
 * Minimal {@code String.format}-style helper. GWT's emulated {@code java.lang.String} does not
 * expose {@code format(String, Object...)} reliably, so any call site that targets both JVM and
 * web builds should go through this method instead. Supports the subset we actually use:
 * <ul>
 *   <li>{@code %s} — value's {@code toString()} (or {@code "null"}).</li>
 *   <li>{@code %d} — integer.</li>
 *   <li>{@code %<N>d} — right-aligned integer padded to width {@code N}.</li>
 *   <li>{@code %-<N>d}, {@code %-<N>s} — left-aligned integer / string padded to width {@code N}.</li>
 *   <li>{@code %.<N>f} — float with {@code N} decimal places.</li>
 *   <li>{@code %%} — literal percent sign.</li>
 * </ul>
 * Anything fancier (flags, grouping, hex) is intentionally unsupported.
 */
public final class Fmt {
    private Fmt() {}

    public static String of(String pattern, Object... args) {
        StringBuilder out = new StringBuilder(pattern.length() + 16);
        int argIdx = 0;
        int i = 0;
        int n = pattern.length();
        while (i < n) {
            char c = pattern.charAt(i);
            if (c != '%') { out.append(c); i++; continue; }
            if (i + 1 >= n) { out.append('%'); break; }
            if (pattern.charAt(i + 1) == '%') { out.append('%'); i += 2; continue; }

            int j = i + 1;
            boolean leftAlign = false;
            if (j < n && pattern.charAt(j) == '-') { leftAlign = true; j++; }
            int width = 0;
            while (j < n && Character.isDigit(pattern.charAt(j))) {
                width = width * 10 + (pattern.charAt(j) - '0');
                j++;
            }
            int precision = -1;
            if (j < n && pattern.charAt(j) == '.') {
                j++;
                precision = 0;
                while (j < n && Character.isDigit(pattern.charAt(j))) {
                    precision = precision * 10 + (pattern.charAt(j) - '0');
                    j++;
                }
            }
            if (j >= n) { out.append(pattern.substring(i)); break; }
            char spec = pattern.charAt(j);

            String piece;
            switch (spec) {
                case 'd' -> {
                    long v = ((Number) args[argIdx++]).longValue();
                    piece = Long.toString(v);
                }
                case 'f' -> {
                    double v = ((Number) args[argIdx++]).doubleValue();
                    piece = precision >= 0 ? roundTo(v, precision) : Double.toString(v);
                }
                case 's' -> {
                    Object v = args[argIdx++];
                    piece = v == null ? "null" : v.toString();
                }
                default -> {
                    // Unknown specifier — emit verbatim and move on without consuming an arg.
                    out.append('%').append(spec);
                    i = j + 1;
                    continue;
                }
            }

            if (width > piece.length()) {
                int pad = width - piece.length();
                if (leftAlign) {
                    out.append(piece);
                    for (int k = 0; k < pad; k++) out.append(' ');
                } else {
                    for (int k = 0; k < pad; k++) out.append(' ');
                    out.append(piece);
                }
            } else {
                out.append(piece);
            }
            i = j + 1;
        }
        return out.toString();
    }

    /** Round {@code v} to {@code digits} decimal places as a string (without locale / grouping). */
    private static String roundTo(double v, int digits) {
        if (digits <= 0) return Long.toString(Math.round(v));
        double scale = Math.pow(10, digits);
        long scaled = Math.round(v * scale);
        String whole = Long.toString(scaled / (long) scale);
        long frac = Math.abs(scaled % (long) scale);
        StringBuilder fracStr = new StringBuilder(Long.toString(frac));
        while (fracStr.length() < digits) fracStr.insert(0, '0');
        return whole + '.' + fracStr;
    }
}
