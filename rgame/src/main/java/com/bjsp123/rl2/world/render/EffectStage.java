package com.bjsp123.rl2.world.render;
import java.util.ArrayList;
import java.util.List;

/**
 * Active visual-effect list - successor to {@code Level.effects}. Owned by
 * {@link com.bjsp123.rl2.world.anim.Animator}; populated as it consumes
 * {@link com.bjsp123.rl2.event.GameEvent}s, drained one frame per render tick by
 * {@link #tick()}, and read by {@code DefaultLevelRenderer} during the per-cell draw.
 */
public final class EffectStage {

    public final List<Effect> active = new ArrayList<>();

    public void add(Effect e) {
        if (e != null) active.add(e);
    }

    /** Per-render-frame: advance every active effect by one frame; remove finished ones.
     *  Effects with {@link Effect#startDelay} &gt; 0 are parked at frame 0 and only their
     *  delay counts down - useful for staggering several effects spawned on the same
     *  tick (e.g. attack flashes from multiple attackers). */
    public void tick() {
        active.removeIf(e -> {
            if (e.startDelay > 0) {
                e.startDelay--;
                return false;
            }
            e.frame++;
            return e.frame >= e.totalFrames();
        });
    }

    public void clear() {
        active.clear();
    }
}
