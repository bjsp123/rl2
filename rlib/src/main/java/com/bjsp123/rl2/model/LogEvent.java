package com.bjsp123.rl2.model;

/**
 * A single text entry in the game's event log. Fields are simple so the log is cheap to
 * construct and render; any interpolation / localisation happens at the call-site (see the
 * {@code Messages} class in the logic package).
 */
public class LogEvent {
    /** Log-entry priority. The HUD filter defaults to HIGH only, so routine combat
     *  blow-by-blow ("miss", "-2", etc.) stays out of the way unless the player toggles the
     *  low-priority switch on. */
    public enum EventPriority { HIGH, LOW }

    public String text;
    public EventPriority priority;
    /** Whether the player was directly involved (attacker, target, victim, etc.). Filters
     *  in the HUD use this to hide pure mob-vs-mob traffic by default. */
    public boolean involvesPlayer;

    public LogEvent(String text, EventPriority priority, boolean involvesPlayer) {
        this.text = text;
        this.priority = priority;
        this.involvesPlayer = involvesPlayer;
    }
}
