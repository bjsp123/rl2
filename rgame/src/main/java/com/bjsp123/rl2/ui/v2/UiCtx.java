package com.bjsp123.rl2.ui.v2;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator.FreeTypeFontParameter;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import com.badlogic.gdx.utils.viewport.Viewport;
import com.bjsp123.rl2.ui.skin.UiFontScale;
import com.bjsp123.rl2.ui.skin.UiScale;

/**
 * Per-game V2 rendering context. Owns the ShapeRenderer + SpriteBatch + the
 * two fonts (header + regular) the entire V2 UI uses. One instance is built
 * by {@link com.bjsp123.rl2.Rl2Game} and shared across every {@link V2Screen}.
 *
 * <p>Two fonts only — no per-call font scaling, no skin chrome, no bitmaps.
 * Panels and buttons are drawn from {@link Pal} colours via the ShapeRenderer.
 */
public final class UiCtx implements Disposable {

    public final ShapeRenderer shapes      = new ShapeRenderer();
    public final SpriteBatch   batch       = new SpriteBatch();
    public final GlyphLayout   layout      = new GlyphLayout();
    public final BitmapFont    fontRegular;
    public final BitmapFont    fontHeader;
    /** 1×1 white pixel for colored rect drawing (brand sparks, charge bars, etc.). */
    public final TextureRegion whitePixel;
    private final Texture      whiteTex;
    public final OrthographicCamera camera = new OrthographicCamera();
    /** ScreenViewport — 1 world unit ≈ 1 screen pixel, so UI controls
     *  drawn at fixed world dimensions (Pal.BTN_W, Pal.BTN_H, etc.) keep
     *  their on-screen size as the window grows or shrinks. The world
     *  width / height is whatever the screen currently is, so layouts use
     *  {@link #worldW()} / {@link #worldH()} to anchor and centre against
     *  the live viewport. {@link UiScale} adjusts {@code unitsPerPixel}
     *  to scale every control uniformly when the user wants bigger /
     *  smaller chrome. */
    public final ScreenViewport    viewport    = new ScreenViewport(camera);
    /** Shared back-history stack — every screen and popup pushes onto
     *  this one stack, and every back gesture pops a single entry. See
     *  {@link WindowStack}. */
    public final WindowStack       stack       = new WindowStack();
    /** Render-time z-order layer for V2 popups. Each popup wraps itself
     *  in a {@link com.bjsp123.rl2.ui.v2.stage.V2PopupActor} and adds
     *  it to one of {@code v2Stage}'s layer Groups; the Stage's
     *  {@code act / draw} pair runs once per frame from the active
     *  screen. Distinct from {@link #stack} (back-button history). */
    public final com.bjsp123.rl2.ui.v2.stage.V2Stage v2Stage;

    private final Vector3 unprojBuf = new Vector3();

    public UiCtx() {
        FreeTypeFontGenerator gen = new FreeTypeFontGenerator(
                Gdx.files.internal("ui/fonts/PixelOperator-Bold.ttf"));
        fontRegular = makeFont(gen, Pal.FONT_REGULAR_PX);
        fontHeader  = makeFont(gen, Pal.FONT_HEADER_PX);
        gen.dispose();
        Pixmap wp = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        wp.setColor(1, 1, 1, 1);
        wp.fill();
        whiteTex   = new Texture(wp);
        whitePixel = new TextureRegion(whiteTex);
        wp.dispose();
        applyFontScale();
        applyUiScale();
        // Built last because it shares this.viewport + this.batch.
        v2Stage = new com.bjsp123.rl2.ui.v2.stage.V2Stage(this);
    }

    /** Re-apply {@link UiFontScale#scale()} to both fonts. Mutates the
     *  BitmapFont instances in place so every screen using the shared
     *  context picks up the new size on the next frame. Call after
     *  {@code UiFontScale.set(...)}. */
    public void applyFontScale() {
        float s = UiFontScale.scale();
        if (s <= 0f) s = 1f;
        fontRegular.getData().setScale(s);
        fontHeader.getData().setScale(s);
    }

    /** Re-apply {@link UiScale#scale()} to the viewport's
     *  {@code unitsPerPixel}. {@code units = pixels / scale} means a
     *  larger scale draws each world unit across more pixels, so chrome
     *  reads bigger on screen. Call after {@code UiScale.set(...)};
     *  fires a viewport update against the current GL surface so the
     *  change takes effect on the next draw. */
    public void applyUiScale() {
        float s = UiScale.scale();
        if (s <= 0f) s = 1f;
        viewport.setUnitsPerPixel(1f / s);
        if (Gdx.graphics != null && Gdx.graphics.getWidth() > 0) {
            viewport.update(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), true);
        }
    }

    /** Configure both axes' projection matrices from the viewport — call inside
     *  every {@link V2Screen#render(float)} BEFORE shapes.begin / batch.begin. */
    public void applyProjection() {
        camera.update();
        shapes.setProjectionMatrix(camera.combined);
        batch.setProjectionMatrix(camera.combined);
    }

    /** Current world width / height in virtual coordinates. Layout code reads
     *  these instead of {@link Pal#VIRTUAL_W} / {@link Pal#VIRTUAL_H} so a
     *  user-changed {@link com.bjsp123.rl2.ui.skin.UiScale} (which shrinks
     *  the viewport's min size) flows through to button placement, window
     *  sizing, and chrome anchors. */
    public float worldW() { return viewport.getWorldWidth(); }
    public float worldH() { return viewport.getWorldHeight(); }

    public float spacerSmallY() { return 2f; }
    public float spacerMediumY() { return 4f; }
    public float spacerLargeY() { return 8f; }

    /** Standard line height for body text ({@link #fontRegular}), including
     *  a small inter-line gap. Use this wherever vertical Y offsets between
     *  consecutive body-text lines were previously hardcoded (e.g. 16f, 18f). */
    public float lineH() { return fontRegular.getLineHeight() + spacerSmallY(); }

    /** Standard line height for header text ({@link #fontHeader}), including
     *  a small inter-line gap. Use this wherever vertical Y offsets between
     *  a header and the next element were previously hardcoded (e.g. 22f, 28f). */
    public float headerLineH() { return fontHeader.getLineHeight() + spacerMediumY(); }

    /** Translate a screen-space pointer location (0,0 at top-left, y-down — the
     *  format Gdx.input gives us) into virtual-world coordinates (y-up, scaled
     *  by the viewport). Used by V2Screen to hit-test buttons. */
    public float unprojectX(int screenX, int screenY) {
        unprojBuf.set(screenX, screenY, 0);
        viewport.unproject(unprojBuf);
        return unprojBuf.x;
    }
    public float unprojectY(int screenX, int screenY) {
        unprojBuf.set(screenX, screenY, 0);
        viewport.unproject(unprojBuf);
        return unprojBuf.y;
    }

    public void resize(int width, int height) {
        viewport.update(width, height, true);
        if (v2Stage != null) v2Stage.resize(width, height);
    }

    /** Clear the framebuffer to the dim-overlay-suitable black so popups dim
     *  cleanly on top, and full-screen menus read against a solid backdrop. */
    public void clear() {
        Gdx.gl.glClearColor(0, 0, 0, 1);
        Gdx.gl.glClear(com.badlogic.gdx.graphics.GL20.GL_COLOR_BUFFER_BIT);
    }

    @Override
    public void dispose() {
        if (v2Stage != null) v2Stage.dispose();
        shapes.dispose();
        batch.dispose();
        fontRegular.dispose();
        fontHeader.dispose();
        whiteTex.dispose();
    }

    /** Build a Pixel Operator BitmapFont at {@code px} native size with a
     *  1-px black outline baked in by FreeType. Nearest-neighbour filtering
     *  keeps the pixel-art glyphs crisp at any scale. */
    private static BitmapFont makeFont(FreeTypeFontGenerator gen, int px) {
        FreeTypeFontParameter p = new FreeTypeFontParameter();
        p.size           = px;
        p.borderWidth    = 1f;
        p.borderColor    = Color.BLACK;
        p.borderStraight = true;
        p.minFilter      = Texture.TextureFilter.Nearest;
        p.magFilter      = Texture.TextureFilter.Nearest;
        BitmapFont font = gen.generateFont(p);
        font.setUseIntegerPositions(true);
        return font;
    }
}
