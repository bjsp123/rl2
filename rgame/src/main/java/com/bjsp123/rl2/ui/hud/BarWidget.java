package com.bjsp123.rl2.ui.hud;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;

/**
 * Horizontal bar widget — dark stone background, colored fill clipped to a 0..1 fraction, and
 * an optional centered label like "12/30". Used for the HUD's HP / XP / Mana bars. Built as a
 * scene2d {@link Actor} so it lives inside the same {@code Stage} as the rest of the HUD.
 */
public class BarWidget extends Actor {

    private final Drawable fillDrawable;
    private final BitmapFont font;
    private final Color barColor;
    private final Color bgColor   = new Color(0.05f, 0.06f, 0.10f, 1f);
    private final Color edgeColor = new Color(0.0f,  0.0f,  0.0f,  1f);
    private final GlyphLayout glyph = new GlyphLayout();

    private float fraction = 1f;
    private String label = "";

    /**
     * @param fill a 1×1 (or otherwise tileable) white drawable; tinted at draw time
     * @param font font used for the centred label
     * @param barColor color of the fill area
     */
    public BarWidget(Drawable fill, BitmapFont font, Color barColor) {
        this.fillDrawable = fill;
        this.font = font;
        this.barColor = new Color(barColor);
    }

    public void setFraction(float f) { this.fraction = MathUtils.clamp(f, 0f, 1f); }
    public void setLabel(String s)    { this.label = s == null ? "" : s; }

    @Override
    public void draw(Batch batch, float parentAlpha) {
        Color saved = batch.getColor();
        float x = getX(), y = getY(), w = getWidth(), h = getHeight();

        // 1px black edge
        batch.setColor(edgeColor.r, edgeColor.g, edgeColor.b, edgeColor.a * parentAlpha);
        fillDrawable.draw(batch, x, y, w, h);

        // Inset dark background (the empty bar)
        batch.setColor(bgColor.r, bgColor.g, bgColor.b, bgColor.a * parentAlpha);
        fillDrawable.draw(batch, x + 1, y + 1, w - 2, h - 2);

        // Colored fill, scaled to fraction
        if (fraction > 0f) {
            batch.setColor(barColor.r, barColor.g, barColor.b, barColor.a * parentAlpha);
            fillDrawable.draw(batch, x + 1, y + 1, (w - 2) * fraction, h - 2);
        }

        // Centered label
        if (!label.isEmpty()) {
            float prevScale = font.getData().scaleX;
            font.getData().setScale(0.85f);
            font.setColor(1f, 1f, 1f, parentAlpha);
            glyph.setText(font, label);
            font.draw(batch, label,
                      x + (w - glyph.width) / 2f,
                      y + (h + glyph.height) / 2f);
            font.getData().setScale(prevScale);
        }

        batch.setColor(saved);
    }
}
