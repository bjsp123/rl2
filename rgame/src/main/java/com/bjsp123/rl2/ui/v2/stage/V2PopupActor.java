package com.bjsp123.rl2.ui.v2.stage;

import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Touchable;

/**
 * Scene2d actor that wraps a {@link V2Popup} so the existing V2 popup
 * code participates in the {@link com.badlogic.gdx.scenes.scene2d.Stage}
 * z-order without giving up its hand-rolled {@code ShapeRenderer +
 * SpriteBatch} drawing. The popup's {@link V2Popup#renderSelf} is called
 * inside {@link #draw} between a {@code batch.end()} / {@code batch.begin()}
 * pair so the popup's own batch lifecycle and the Stage's batch can
 * coexist.
 *
 * <p>Touch is disabled on the actor: input flows through the existing
 * {@link com.badlogic.gdx.InputMultiplexer} and each popup's own
 * {@code input()} processor - the Stage never claims taps. {@link #act}
 * just mirrors {@link V2Popup#isOpen} onto {@link #setVisible} so a
 * closed popup is skipped by the Stage's draw walk.
 */
public final class V2PopupActor extends Actor {

    private final V2Popup popup;

    public V2PopupActor(V2Popup popup) {
        this.popup = popup;
        // Stage must not claim taps - the existing InputMultiplexer +
        // popup.input() pair already does hit testing and tap-outside-to-
        // close. Touchable.disabled also makes the actor a no-op for
        // hover / focus events.
        setTouchable(Touchable.disabled);
    }

    @Override
    public void act(float delta) {
        super.act(delta);
        // Mirror open-state onto visibility so the Stage skips the actor
        // when the popup is closed, and so a freshly-opened popup
        // renders THIS frame (act() runs before draw()).
        setVisible(popup.isOpen());
    }

    @Override
    public void draw(Batch batch, float parentAlpha) {
        if (!popup.isOpen()) return;
        // Pause the Stage's batch so the popup can run its own
        // shapes.begin / shapes.end and (if needed) batch.begin /
        // batch.end pairs without nested-begin GL errors. The flush is
        // standard and harmless - see scene2d docs on mixing
        // ShapeRenderer with Stage actors.
        batch.end();
        popup.renderSelf();
        batch.begin();
    }
}
