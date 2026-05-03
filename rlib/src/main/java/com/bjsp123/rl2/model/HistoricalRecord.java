package com.bjsp123.rl2.model;

/**
 * One entry in a character's lifetime history — "slew goblin on turn 42, dungeon level 3",
 * "reached level 5", "found healing potion". The History tab of the character stats frame
 * scrolls a list of these.
 *
 * <p><b>Memory layout is intentionally tight.</b> Typical characters will accumulate hundreds
 * to thousands of kills, so per-entry overhead matters. The three metadata fields pack into
 * a single {@code int} via bitfields instead of three primitives, cutting object overhead to
 * one reference ({@link #label}), one enum reference ({@link #kind}), and one int.
 *
 * <p><b>Packed metadata layout.</b> Turn occupies the low 24 bits (≈16M turns — comfortably
 * beyond any session), dungeon depth occupies the next 7 bits (up to 127 floors — plenty),
 * and 1 bit is reserved. This keeps arithmetic on {@link #turn()} / {@link #depth()} trivial
 * (mask + shift) without forcing callers to do the bit math.
 *
 * <p>The short-lived entity description ({@link #label} — species name, item name, new
 * level number) is stored as-is; callers pass {@code mob.name} / {@code item.name} /
 * {@code Integer.toString(level)}, which are already shared references from their source
 * objects so no extra interning is needed.
 */
public final class HistoricalRecord {

    public enum Kind {
        /** A hostile creature was slain. {@link #label} = species name. */
        KILL,
        /** The character gained a level. {@link #label} = new level number as a string. */
        LEVEL_UP,
        /** An item was picked up. {@link #label} = item name. */
        ITEM_FOUND,
        /** Another mob was spawned by this one (e.g. kissyblob bud). {@link #label} = species. */
        SPAWNED
    }

    private static final int TURN_MASK  = 0x00FFFFFF;          // low 24 bits
    private static final int DEPTH_MASK = 0x7F;                // 7 bits
    private static final int DEPTH_SHIFT = 24;

    /** Packed {@code turn} (bits 0-23) + {@code depth} (bits 24-30). */
    public int packed;
    public Kind kind;
    public String label;

    public HistoricalRecord() {}

    public HistoricalRecord(int turn, int depth, Kind kind, String label) {
        this.packed = pack(turn, depth);
        this.kind   = kind;
        this.label  = label;
    }

    public int turn()  { return packed & TURN_MASK; }
    public int depth() { return (packed >>> DEPTH_SHIFT) & DEPTH_MASK; }

    private static int pack(int turn, int depth) {
        // Clamp silently instead of throwing — a clamped history entry is always preferable
        // to crashing the game mid-tick because someone overflowed the counter.
        int t = Math.max(0, Math.min(TURN_MASK, turn));
        int d = Math.max(0, Math.min(DEPTH_MASK, depth));
        return (d << DEPTH_SHIFT) | t;
    }

    // Factory methods keep call sites terse and type-safe.

    public static HistoricalRecord kill(int turn, int depth, String speciesName) {
        return new HistoricalRecord(turn, depth, Kind.KILL, speciesName);
    }

    public static HistoricalRecord levelUp(int turn, int depth, int newLevel) {
        return new HistoricalRecord(turn, depth, Kind.LEVEL_UP, Integer.toString(newLevel));
    }

    public static HistoricalRecord itemFound(int turn, int depth, String itemName) {
        return new HistoricalRecord(turn, depth, Kind.ITEM_FOUND, itemName);
    }

    public static HistoricalRecord spawned(int turn, int depth, String speciesName) {
        return new HistoricalRecord(turn, depth, Kind.SPAWNED, speciesName);
    }

    /** Human-readable one-liner for the History tab. Format keeps prefixes short so many
     *  entries fit on-screen. */
    public String describe() {
        return switch (kind) {
            case KILL       -> "T" + turn() + " L" + depth() + ": slew "          + label;
            case LEVEL_UP   -> "T" + turn() + " L" + depth() + ": reached level " + label;
            case ITEM_FOUND -> "T" + turn() + " L" + depth() + ": found "         + label;
            case SPAWNED    -> "T" + turn() + " L" + depth() + ": spawned "       + label;
        };
    }
}
