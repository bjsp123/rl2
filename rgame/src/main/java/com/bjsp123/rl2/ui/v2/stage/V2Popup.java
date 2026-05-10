package com.bjsp123.rl2.ui.v2.stage;

/**
 * Contract every V2 popup implements so {@link V2PopupActor} can wrap it.
 *
 * <p>The popup keeps its own layout, drawing helpers, and input processor —
 * only its render lifecycle is plugged into the scene2d {@link com.badlogic.gdx.scenes.scene2d.Stage}
 * via the wrapper actor. The popup remains responsible for its own modal
 * dim layer (typically by drawing a {@link Scrim} as the first thing in
 * {@code renderSelf}); the stage just guarantees popups render in the
 * correct z-order so a higher-layer popup's chrome cleanly covers
 * everything below.
 */
public interface V2Popup {

    /** True while the popup is visible. {@link V2PopupActor} mirrors this
     *  onto its own {@code visible} flag each frame so closed popups are
     *  skipped by the Stage's draw walk. */
    boolean isOpen();

    /** Render the popup in full — chrome shapes AND text — using the
     *  popup's own {@code shapes.begin/end} and {@code batch.begin/end}
     *  pairs. Called from {@link V2PopupActor#draw} after pausing the
     *  Stage's batch and resumed automatically afterwards. */
    void renderSelf();
}
