package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputMultiplexer;
import com.bjsp123.rl2.Rl2Game;
import com.bjsp123.rl2.ui.v2.stage.V2PopupActor;

/**
 * Thin host screen that presents the {@link V2Encyclopedia} popup as a
 * standalone, burger-reachable destination - a pre-game "How to Play" /
 * reference that's available from any screen, in or out of a run.
 *
 * <p>The popup owns its own chrome, scrim, scrolling, and close affordances.
 * This screen just parks it on the shared stage's sub-popup layer (so the
 * base {@link V2Screen#render} draws it in z-order), routes input to it, and
 * pops itself when the popup is dismissed - tap-outside or ESC closes the
 * popup, which unwinds {@code ctx.stack} and runs the {@code popScreen}
 * callback opened with below.
 */
public final class V2EncyclopediaScreen extends V2Screen {

    private final Rl2Game game;
    private final V2Encyclopedia enc;
    private final V2PopupActor actor;

    public V2EncyclopediaScreen(Rl2Game game) {
        super(game.ui);
        this.game  = game;
        this.enc   = new V2Encyclopedia(game.ui);
        this.actor = new V2PopupActor(enc);
    }

    @Override
    protected void buildLayout() {
        // No body chrome - the encyclopedia popup is the entire screen.
    }

    @Override
    public void show() {
        super.show();
        ctx.v2Stage.addToSubPopup(actor);
        // Open to the list view; closing unwinds via ctx.stack, which runs
        // this callback to leave the screen.
        enc.openTo(null, game::popScreen);
        // Encyclopedia input first; base input (ESC / stray taps) behind it.
        Gdx.input.setInputProcessor(new InputMultiplexer(enc.input(), baseInput()));
    }

    @Override
    public void hide() {
        ctx.v2Stage.remove(actor);
        super.hide();
    }

    @Override
    protected void drawBodyShape(UiCtx ctx) { }

    @Override
    protected void drawBodyText(UiCtx ctx) { }
}
