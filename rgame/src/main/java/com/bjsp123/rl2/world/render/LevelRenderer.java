package com.bjsp123.rl2.world.render;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.World;

/**
 * Pluggable level renderer. Concrete implementations (ASCII, sprite, ...) handle drawing the level
 * contents in their own way. All implementations share the same {@link #TILE_SIZE} so camera math is
 * identical across renderers.
 */
public interface LevelRenderer {

    int TILE_SIZE = 16;

    void create();

    void render(Level level, OrthographicCamera camera);

    void dispose();

    /**
     * Tell the renderer its cached per-level data (fog overlay, item/mob index) is stale
     * vs the current level state. The caller should invoke this after any event that could
     * have changed visibility, lighting, or the position / presence of a mob or item - in
     * practice, on every completed game tick and on every level transition.
     *
     * <p>Default no-op so renderers without per-frame caches don't need to implement it.
     */
    default void markDirty() {}

    /**
     * Hand the renderer a reference to the {@link World} so it can look up neighbouring
     * levels (e.g. to label a stair with the depth and side of the level it leads to).
     * Default no-op for renderers that don't need cross-level info.
     */
    default void setWorld(World world) {}

    /**
     * Hand the renderer the in-world {@link com.bjsp123.rl2.world.anim.Animator} so it can read
     * per-mob {@link com.bjsp123.rl2.world.anim.MobAnimState} for sprite offsets and alpha.
     * Default no-op for renderers that draw without animation state (e.g. a static
     * minimap renderer).
     */
    default void setAnimator(com.bjsp123.rl2.world.anim.Animator animator) {}
}
