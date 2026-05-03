package com.bjsp123.rl2.ui.skin;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;

import java.util.Random;

/**
 * A scene2d {@link Drawable} that layers decorative corner ornaments over a base drawable
 * (usually a {@code NinePatchDrawable} for a frame or panel). Each of the four corners picks
 * a random ornament shape from the pool and renders the pre-lit variant for its position,
 * so the highlight / lowlight on every ornament stays consistent with a top-left light
 * direction regardless of where the corner sits on the panel.
 *
 * <p>Randomization happens <b>once per instance</b> (at construction) — a given dialog
 * shows the same four corners every time it opens in a session, which keeps the UI from
 * flickering while still letting different panels on screen look distinct from one another.
 *
 * <p>Used by {@link StoneUi}'s minimalist theme to richen the otherwise plain gold 9-patches
 * without having to produce a bespoke corner-baked-in frame for each panel size.
 */
public class CornerDecoratedDrawable extends BaseDrawable {

    private final Drawable base;
    /** [shape][position] — 9 shapes × 4 pre-lit positions (TL, TR, BL, BR). Indices 0..3
     *  match {@link StoneUi#POS_TL} .. {@link StoneUi#POS_BR}. */
    private final TextureRegion[][] cornerPool;
    /** Single shape index used for all four corners — the user-stated invariant is that all
     *  four corners of a frame must show the same ornament at any given moment. */
    private final int shape;
    private final int cornerSize;

    public CornerDecoratedDrawable(Drawable base, TextureRegion[][] cornerPool, int cornerSize,
                                   long seed) {
        this.base       = base;
        this.cornerPool = cornerPool;
        this.cornerSize = cornerSize;
        Random rng = new Random(seed);
        this.shape = rng.nextInt(cornerPool.length);
        setLeftWidth(base.getLeftWidth());
        setRightWidth(base.getRightWidth());
        setTopHeight(base.getTopHeight());
        setBottomHeight(base.getBottomHeight());
        setMinWidth(base.getMinWidth());
        setMinHeight(base.getMinHeight());
    }

    @Override
    public void draw(Batch batch, float x, float y, float width, float height) {
        base.draw(batch, x, y, width, height);
        float sz = Math.min(cornerSize, Math.min(width, height) * 0.45f);
        if (sz < 4f) return;
        // scene2d y-up: y+height is TOP, y is BOTTOM. All four corners share the same shape;
        // only the per-position pre-lit variant differs (so the highlight stays consistent
        // with a top-left light direction).
        batch.draw(cornerPool[shape][StoneUi.POS_TL],
                x,              y + height - sz, sz, sz);
        batch.draw(cornerPool[shape][StoneUi.POS_TR],
                x + width - sz, y + height - sz, sz, sz);
        batch.draw(cornerPool[shape][StoneUi.POS_BL],
                x,              y,               sz, sz);
        batch.draw(cornerPool[shape][StoneUi.POS_BR],
                x + width - sz, y,               sz, sz);
    }
}
