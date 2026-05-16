package com.bjsp123.rl2.world.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.bjsp123.rl2.ui.skin.Settings;

/**
 * Optional off-screen render target at game (logical) resolution, upscaled to the
 * native screen resolution with nearest-neighbour filtering.
 *
 * <p>When {@link #Settings.lowResRender()} is false every method is a no-op and performance is unchanged.
 *
 * <p>Wrap any self-contained draw pass like this:
 * <pre>
 *     gameFbo.beginWorldPass(batch, camera);
 *     // ... all world draw calls ...
 *     gameFbo.endWorldPass(batch);
 * </pre>
 * {@code beginWorldPass} lazily allocates the FBO at the camera's logical-pixel viewport,
 * binds it, clears to opaque black, and starts the batch.
 * {@code endWorldPass} ends the batch, unbinds the FBO, and issues a single
 * nearest-neighbour full-screen blit to the default framebuffer.
 *
 * <p>Fragment work scales with the FBO's pixel count (≈ 1/9× at 640×360 vs 1920×1080),
 * which eliminates the GPU {@code glClear} stall caused by fill-rate saturation at
 * native resolution.
 */
final class GameFbo {

    private FrameBuffer   fbo;
    private TextureRegion fboRegion;
    private final OrthographicCamera screenCam = new OrthographicCamera();
    private int fboW, fboH;

    /**
     * If {@link #Settings.lowResRender()}: lazily allocates the FBO at the camera's logical-pixel
     * dimensions, binds it, clears to opaque black, then sets the batch projection and
     * starts the batch.
     *
     * <p>If not enabled: equivalent to
     * {@code batch.setProjectionMatrix(cam.combined); batch.begin()}.
     */
    void beginWorldPass(SpriteBatch batch, OrthographicCamera gameCam) {
        if (Settings.lowResRender()) {
            int lw = Math.max(1, Math.round(gameCam.viewportWidth));
            int lh = Math.max(1, Math.round(gameCam.viewportHeight));
            try {
                ensureFbo(lw, lh);
                if (fbo != null) {
                    fbo.begin();
                    Gdx.gl.glClearColor(0f, 0f, 0f, 1f);
                    Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
                }
            } catch (Exception e) {
                Gdx.app.error("GameFbo", "FBO setup failed, rendering direct", e);
                fbo = null;
                fboRegion = null;
            }
        }
        // batch.begin() is unconditional — FBO failures fall back to direct rendering.
        batch.setProjectionMatrix(gameCam.combined);
        batch.begin();
    }

    /**
     * Ends the batch.
     *
     * <p>If {@link #Settings.lowResRender()}: unbinds the FBO, then issues a single nearest-neighbour
     * full-screen blit to the default framebuffer via a fresh screen-aligned camera.
     */
    void endWorldPass(SpriteBatch batch) {
        batch.end();
        if (!Settings.lowResRender() || fbo == null) return;
        fbo.end();
        int sw = Gdx.graphics.getWidth();
        int sh = Gdx.graphics.getHeight();
        screenCam.setToOrtho(false, sw, sh);
        batch.setProjectionMatrix(screenCam.combined);
        batch.begin();
        batch.draw(fboRegion, 0, 0, sw, sh);
        batch.end();
    }

    void dispose() {
        if (fbo != null) { fbo.dispose(); fbo = null; }
        fboRegion = null;
    }

    private void ensureFbo(int lw, int lh) {
        if (fbo != null && fboW == lw && fboH == lh) return;
        dispose();
        fboW = lw;
        fboH = lh;
        fbo = new FrameBuffer(Pixmap.Format.RGBA8888, lw, lh, false);
        Texture tex = fbo.getColorBufferTexture();
        tex.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        fboRegion = new TextureRegion(tex);
        fboRegion.flip(false, true);  // FBO color buffer is Y-flipped vs screen convention
    }
}
