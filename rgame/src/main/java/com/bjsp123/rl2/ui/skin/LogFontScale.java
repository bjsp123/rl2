package com.bjsp123.rl2.ui.skin;

/**
 * Process-wide multiplier applied on top of the message-log's base font size.
 * The log was historically tied to {@link UiScale} / {@link UiPixelScale} so it
 * tracked the rest of the UI; this knob lets the user further bump it for
 * readability without inflating every other widget.
 *
 * <p>Volatile because the read happens on the libGDX render thread and the
 * write happens on the Settings input thread (also the render thread in
 * practice, but the volatile is cheap insurance — same pattern as
 * {@link LogPreferences}).
 */
public final class LogFontScale {

    /** Selectable multipliers exposed by the Settings → Log tab. {@code 1.0×} is
     *  the legacy size; defaults bumped to {@code 1.5×} so a fresh install lands
     *  at a more readable size given the new pixel font. */
    public static final float[] CHOICES = { 1.0f, 1.25f, 1.5f, 2.0f, 2.5f, 3.0f };

    private static volatile float scale = 1.5f;

    private LogFontScale() {}

    public static float scale() { return scale; }

    public static void set(float s) {
        if (s <= 0f) return;
        scale = s;
    }
}
