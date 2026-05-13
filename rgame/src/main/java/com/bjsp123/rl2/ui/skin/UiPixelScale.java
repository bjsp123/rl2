package com.bjsp123.rl2.ui.skin;
import com.bjsp123.rl2.persistence.Persistence;

/**
 * UI "pixel scale" - an integer multiplier applied on top of {@link UiScale} that controls
 * how chunky the UI pixel art reads. A value of 1 means each source pixel maps to one
 * UiScale-sized screen pixel; 2 doubles the pixel size (nearest-neighbor upscale on top of
 * {@link UiScale}); 3 triples it; etc.
 *
 * <p>How it composes: the scene2d UI stage's {@code unitsPerPixel} is set to
 * {@code 1 / (UiScale.scale() * UiPixelScale.scale())}. Each stage unit therefore covers
 * {@code UiScale x UiPixelScale} screen pixels - textures and widget layout both inflate
 * equally, so buttons, 9-patches, and icons grow blockier when {@code UiPixelScale} goes up.
 *
 * <p>Text that should stay at its natural screen size regardless of pixel scale (e.g. the
 * scrolling event log - readability beats chunkiness there) compensates by dividing its
 * own font scale by {@code UiPixelScale.scale()} in addition to {@link UiScale}. All other
 * UI text follows {@code UiPixelScale}, which the user explicitly wants: "make all graphics
 * pixelated except the text log".
 *
 * <p>UiScale and UiPixelScale are two separate knobs by design: UiScale is the user's
 * accessibility / overall-size preference; UiPixelScale is the retro-chunkiness preference.
 * They multiply into the final on-screen footprint but remain independently configurable.
 */
public class UiPixelScale {
    private static final String KEY = "rl2-ui-pixel-scale";

    public static final int   DEFAULT = 1;
    public static final int[] CHOICES = { 1, 2, 3, 4 };

    private static Persistence persistence;
    private static int scale = DEFAULT;

    public static void init(Persistence p) {
        persistence = p;
        String raw = persistence.load(KEY);
        if (raw != null) {
            try { scale = Math.max(1, Integer.parseInt(raw)); }
            catch (NumberFormatException e) { scale = DEFAULT; }
        }
    }

    public static int scale() { return scale; }

    public static void set(int s) {
        scale = Math.max(1, s);
        if (persistence != null) persistence.save(KEY, Integer.toString(scale));
    }
}
