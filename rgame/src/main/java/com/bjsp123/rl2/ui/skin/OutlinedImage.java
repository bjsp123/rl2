package com.bjsp123.rl2.ui.skin;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;

/**
 * Image variant that paints a 1-px black silhouette behind the drawable. Item
 * sprites shown in inventory cells and HUD action-bar buttons sit on light slot
 * chrome where their dark contour pixels would otherwise dissolve into the
 * sprite's own outlines; rendering the same drawable four times offset by 1 px
 * and tinted black restores a crisp boundary.
 *
 * <p>The technique is the chrome equivalent of {@link MobOutline} for in-world
 * mobs — same idea (cardinal-offset taps to silhouette a sprite), simpler scope
 * (single fixed-width tap, no smoothing). Atlas pixel bleeding is not a risk:
 * the offsets are screen-space, the texture region UVs are unchanged.
 */
public class OutlinedImage extends Image {

    private static final float OUTLINE_PX = 1f;

    public OutlinedImage() { super(); }
    public OutlinedImage(Drawable d) { super(d); }

    @Override
    public void draw(Batch batch, float parentAlpha) {
        Drawable d = getDrawable();
        if (d == null) { super.draw(batch, parentAlpha); return; }
        validate();
        Color prev = batch.getColor();
        float a = parentAlpha * getColor().a;
        batch.setColor(0f, 0f, 0f, a);
        float ix = getX() + getImageX();
        float iy = getY() + getImageY();
        float iw = getImageWidth();
        float ih = getImageHeight();
        d.draw(batch, ix - OUTLINE_PX, iy,              iw, ih);
        d.draw(batch, ix + OUTLINE_PX, iy,              iw, ih);
        d.draw(batch, ix,              iy - OUTLINE_PX, iw, ih);
        d.draw(batch, ix,              iy + OUTLINE_PX, iw, ih);
        batch.setColor(prev);
        super.draw(batch, parentAlpha);
    }
}
