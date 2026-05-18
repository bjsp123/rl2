package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.bjsp123.rl2.ui.v2.stage.V2Popup;

/**
 * Shared lifecycle and modal chrome for the simple V2 popups.
 *
 * <p>Subclasses still own layout, drawing, and hit-testing details. This base
 * only centralizes the common open/close state and the usual render sequence:
 * layout, shape pass, text pass.
 */
public abstract class BasePopup implements V2Popup {

    protected final UiCtx ctx;
    protected final Rect window = new Rect();

    private boolean open;

    protected com.bjsp123.rl2.audio.SoundManager sounds;
    public void setSounds(com.bjsp123.rl2.audio.SoundManager s) { this.sounds = s; }
    protected String popupId() { return null; }

    protected BasePopup(UiCtx ctx) {
        this.ctx = ctx;
    }

    public void open() {
        open = true;
        if (sounds != null) {
            String id = popupId();
            sounds.play(id != null ? "sfx.ui.popup." + id : "sfx.ui.popup");
        }
        onOpened();
    }

    public void close() {
        open = false;
        onClosed();
    }

    public void toggle() {
        if (open) close();
        else open();
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public final void renderSelf() {
        if (!isOpen() || !canRender()) return;
        layoutRects();
        renderShapesPass();
        renderTextPass();
        afterRender();
    }

    protected boolean canRender() {
        return true;
    }

    protected void onOpened() {}

    protected void onClosed() {}

    protected void afterRender() {}

    protected abstract void layoutRects();

    protected abstract void renderShapesPass();

    protected abstract void renderTextPass();

    protected void beginModalShapes() {
        ctx.applyProjection();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        ctx.shapes.begin(ShapeRenderer.ShapeType.Filled);
    }

    protected void endModalShapes() {
        ctx.shapes.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    protected void drawScrim() {
        ctx.shapes.setColor(0f, 0f, 0f, UIVars.DIM_ALPHA);
        ctx.shapes.rect(0, 0, ctx.worldW(), ctx.worldH());
    }

    protected void drawWindow() {
        Window.drawShape(ctx, window.x, window.y, window.w, window.h);
    }

    protected boolean closeOnBack(int keycode) {
        if (!isOpen()) return false;
        if (keycode == Input.Keys.ESCAPE || keycode == Input.Keys.BACK) {
            close();
            return true;
        }
        return false;
    }

    protected boolean closeIfOutside(float vx, float vy) {
        if (!window.contains(vx, vy)) {
            close();
            return true;
        }
        return false;
    }

    protected InputProcessor simpleDismissInput() {
        return new InputAdapter() {
            @Override
            public boolean touchDown(int sx, int sy, int pointer, int button) {
                if (!isOpen()) return false;
                closeIfOutside(ctx.unprojectX(sx, sy), ctx.unprojectY(sx, sy));
                return true;
            }

            @Override
            public boolean touchUp(int sx, int sy, int pointer, int button) {
                return isOpen();
            }

            @Override
            public boolean keyDown(int keycode) {
                return closeOnBack(keycode);
            }
        };
    }
}
