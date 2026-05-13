package com.bjsp123.rl2.ui.skin;

/**
 * Process-wide log filter preferences. Lives in {@code ui.skin} alongside the
 * other granular Settings backing stores ({@link UiScale}, {@link AnimationSpeed},
 * {@link MobOutline}). The HUD's {@code LogView} reads these flags each frame to
 * decide what to render; the Log tab in {@code SettingsScreen} flips them.
 *
 * <p>Volatile because the read happens on the libGDX render thread and the
 * write happens on the UI input thread (also the render thread in practice,
 * but the volatile is cheap insurance).
 */
public final class LogPreferences {

    /** Master switch - false hides every log line regardless of the per-line
     *  priority / non-player filters. Default true so a fresh install shows
     *  the log. */
    private static volatile boolean logOn = true;
    /** When false, lines tagged {@code LogEvent.EventPriority.LOW} are dropped
     *  (e.g. minor combat misses, identity-already-known notices). Default
     *  false - keep the log focused on noteworthy events. */
    private static volatile boolean showLowPriority = false;
    /** When false, lines whose source isn't the player are dropped (mob-vs-mob
     *  events, ambient world chatter). Default false. */
    private static volatile boolean showNonPlayer = false;
    /** Expanded mode - show ~10 lines instead of ~2. Toggled by the user when
     *  they want to scan the recent backlog; collapses again by default. */
    private static volatile boolean expanded = false;

    private LogPreferences() {}

    public static boolean logOn()           { return logOn; }
    public static boolean showLowPriority() { return showLowPriority; }
    public static boolean showNonPlayer()   { return showNonPlayer; }
    public static boolean expanded()        { return expanded; }

    public static void setLogOn(boolean v)           { logOn = v; }
    public static void setShowLowPriority(boolean v) { showLowPriority = v; }
    public static void setShowNonPlayer(boolean v)   { showNonPlayer = v; }
    public static void setExpanded(boolean v)        { expanded = v; }
}
