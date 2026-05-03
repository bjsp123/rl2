package com.bjsp123.rl2.ui.hud;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;

/**
 * HUD action button — square slot-shaped surface that fires a single tap action.
 * Tap-only by design; binding now happens through the inventory popup's slot
 * quickslot buttons rather than a long-press gesture (which removed the only
 * gesture-based interaction in the HUD per the no-drag / no-long-press
 * accessibility rule).
 *
 * <p>Visual chrome: subtle press-offset on the content cell (1, -1) so the surface
 * reads as being pushed in. The keyboard accelerator label (e.g. "1") is drawn
 * in the top-left corner so the player can scan the bar at a glance and know
 * which key fires which slot.
 */
public class ActionButton extends TextButton {

    /** Pixel inset of the keyboard-accelerator badge from the top-left corner. */
    private static final float BADGE_INSET = 2f;
    /** Scale factor applied to the BitmapFont for the corner accelerator badge. */
    private static final float BADGE_SCALE = 0.7f;

    private final String accelerator;
    /** Cached glyph layout for the accelerator label — recomputed only when the
     *  accelerator string or font changes (which never happens for HUD buttons,
     *  so once at construct time). */
    private final GlyphLayout accelLayout = new GlyphLayout();

    /**
     * Build an action button bound to the given keyboard {@code accelerator}
     * (rendered as a small corner badge) and the {@code onTap} action. The
     * displayed glyph in the centre of the button is set externally by the HUD's
     * per-frame refresh — we leave the parent label empty.
     */
    public ActionButton(String accelerator, Skin skin, Runnable onTap) {
        super("", skin, "action-text");
        this.accelerator = accelerator;

        // Visible "press in" — contents shift down-right by 1 px when held / checked.
        getStyle().pressedOffsetX = 1;
        getStyle().pressedOffsetY = -1;
        getStyle().checkedOffsetX = 1;
        getStyle().checkedOffsetY = -1;

        setTouchable(Touchable.enabled);
        addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                if (onTap != null) onTap.run();
            }
        });

        if (accelerator != null && !accelerator.isEmpty()) {
            BitmapFont font = getStyle().font;
            float prevScale = font.getData().scaleX;
            font.getData().setScale(prevScale * BADGE_SCALE);
            accelLayout.setText(font, accelerator);
            font.getData().setScale(prevScale);
        }
    }

    @Override
    public void draw(Batch batch, float parentAlpha) {
        super.draw(batch, parentAlpha);
        drawAccelBadge(batch, getStyle().font, accelerator,
                getX(), getY(), getHeight(), parentAlpha);
    }

    /** Paint the small keyboard-accelerator badge in the top-left corner of a HUD
     *  button. Shared by {@link ActionButton} and {@link HudRenderer}'s icon buttons
     *  so every clickable HUD tile that has a hotkey advertises that hotkey at the
     *  same scale and position. No-op when {@code accelerator} is null/empty. */
    static void drawAccelBadge(Batch batch, BitmapFont font, String accelerator,
                               float x, float y, float height, float parentAlpha) {
        if (accelerator == null || accelerator.isEmpty()) return;
        float prevScale = font.getData().scaleX;
        Color prevColor = font.getColor().cpy();
        font.getData().setScale(prevScale * BADGE_SCALE);
        font.setColor(0.85f, 0.85f, 0.85f, parentAlpha);
        font.draw(batch, accelerator, x + BADGE_INSET, y + height - BADGE_INSET);
        font.getData().setScale(prevScale);
        font.setColor(prevColor);
    }
}
