package com.bjsp123.rl2.ui.v2;

import com.bjsp123.rl2.world.render.IconSprites;

/**
 * Standard info button - a square {@link Btn} carrying the shared INFO
 * glyph from the UI icon sheet. Used wherever a V2 surface needs an
 * "open encyclopaedia for this thing" affordance (item detail popup,
 * per-perk row in the character stats popup, etc.) so info buttons
 * read identically across screens.
 *
 * <p>Not a subclass - just a factory that returns a configured
 * {@link Btn}, so callers can drop the result straight into their
 * existing button list and reuse the existing hit-test / press-state
 * machinery.
 */
public final class InfoBtn {

    private InfoBtn() {}

    /** Build a square info button at {@code (x, y)} of edge {@code size}.
     *  Click fires {@code onClick}; {@code onClick} == null builds an
     *  inert visual stub. */
    public static Btn make(float x, float y, float size, Runnable onClick) {
        Btn b = new Btn("", x, y, size, size, onClick);
        b.icon = IconSprites.regionFor(IconSprites.Icon.INFO);
        return b;
    }
}
