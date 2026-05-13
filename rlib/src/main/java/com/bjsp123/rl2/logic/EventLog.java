package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.LogEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Process-wide rolling event log. Static because event emitters ({@link MobSystem},
 * {@link VegetationSystem}, PlayScreen, etc.) are deeply distributed and threading a log
 * reference through every call would be invasive. A single game instance runs one world
 * at a time, so a shared buffer is fine.
 *
 * <p>Capped at {@link #MAX_EVENTS}; older entries drop off the front. Not persisted - a
 * fresh session starts with an empty log.
 */
public final class EventLog {

    /** Hard cap on the buffer. High enough that a full game session stays captured, low
     *  enough that the list doesn't grow unbounded during long idle runs. */
    public static final int MAX_EVENTS = 400;

    private static final List<LogEvent> events = new ArrayList<>();

    private EventLog() {}

    public static void add(LogEvent e) {
        if (e == null) return;
        events.add(e);
        while (events.size() > MAX_EVENTS) events.remove(0);
    }

    /** Read-only view of the entire log, oldest first. Useful for full-log views. */
    public static List<LogEvent> all() {
        return Collections.unmodifiableList(events);
    }

    /** Most recent {@code n} events, oldest first. */
    public static List<LogEvent> tail(int n) {
        int from = Math.max(0, events.size() - n);
        return Collections.unmodifiableList(events.subList(from, events.size()));
    }

    /** Wipe the log - called on new-game start so the Hall-of-Fame kill chatter from a
     *  previous run doesn't stick around. */
    public static void clear() {
        events.clear();
    }
}
