package com.bjsp123.rl2.ui.v2.stage;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.utils.Disposable;
import com.bjsp123.rl2.ui.v2.UiCtx;

/**
 * Z-order layer for V2 popups, built on a libGDX scene2d {@link Stage}.
 * One instance lives on {@link UiCtx}; every screen registers its popups
 * with it on {@code show()} and the Stage's per-frame {@link #act} +
 * {@link #draw} pair handles the rest.
 *
 * <p>Three stable z-layer Groups are inserted on construction:
 * <ol>
 *   <li>{@link #popupLayer} - primary popups (inventory, encyclopedia,
 *       look, character stats, crafting, map, level info, hall of fame,
 *       game over).</li>
 *   <li>{@link #subPopupLayer} - popups that overlay a primary popup
 *       (inventory item-detail, V2Saves delete-confirm).</li>
 *   <li>{@link #burgerLayer} - the burger drop-down. Always on top so
 *       its scrim hides everything below.</li>
 * </ol>
 *
 * <p>Insertion order = back-to-front. Within a layer, an actor's index
 * determines its draw order (later = on top). Each popup is wrapped in
 * a {@link V2PopupActor}; its own {@code isOpen()} controls whether it
 * draws or is skipped this frame.
 *
 * <p>The Stage shares {@link UiCtx#viewport} and {@link UiCtx#batch} so
 * the V2 ScreenViewport conversion + UiScale apply to it for free, and
 * we don't double-allocate a SpriteBatch.
 */
public final class V2Stage implements Disposable {

    private final Stage stage;
    public final Group popupLayer    = new Group();
    public final Group subPopupLayer = new Group();
    public final Group burgerLayer   = new Group();

    public V2Stage(UiCtx ctx) {
        // Reuse the existing batch - we don't want a second SpriteBatch
        // hanging off the Stage with its own font texture binding.
        this.stage = new Stage(ctx.viewport, ctx.batch);
        stage.addActor(popupLayer);
        stage.addActor(subPopupLayer);
        stage.addActor(burgerLayer);
    }

    /** Add an actor to the primary-popup layer. Caller usually wraps a
     *  {@link V2Popup} via {@link V2PopupActor} before adding. */
    public void add(Actor actor)            { popupLayer.addActor(actor); }
    public void addToSubPopup(Actor actor)  { subPopupLayer.addActor(actor); }
    public void addToBurger(Actor actor)    { burgerLayer.addActor(actor); }

    /** Remove an actor from whatever layer it's currently in. Used on
     *  screen disposal so popups belonging to a discarded screen don't
     *  linger in the stage. */
    public void remove(Actor actor)         { if (actor != null) actor.remove(); }

    /** Per-frame heartbeat. Call BEFORE {@link #draw} so each
     *  {@link V2PopupActor#act} can flip its visibility based on
     *  {@code isOpen()} this frame - otherwise a freshly-opened popup
     *  waits a frame to appear. */
    public void act(float delta) { stage.act(delta); }

    /** Walk the stage and draw every visible actor. Each
     *  {@link V2PopupActor#draw} pauses the Stage's batch, calls the
     *  popup's own renderSelf, then resumes - see that class for why. */
    public void draw() { stage.draw(); }

    /** Forward a viewport resize to the Stage. {@link UiCtx#resize}
     *  already updates the shared viewport, but the Stage holds its own
     *  reference and needs the explicit call. */
    public void resize(int width, int height) {
        stage.getViewport().update(width, height, true);
    }

    @Override
    public void dispose() {
        // The Stage doesn't own the SpriteBatch (we passed in
        // ctx.batch), so its dispose() won't dispose the batch - fine.
        stage.dispose();
    }
}
