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
        if (e == null) return;
        // Stagger damage floaters spawned on the same tile in quick succession
        // so a multi-hit turn (AOE + followup melee, lightning chain) doesn't
        // overlap them into an unreadable pile. We lift the newcomer by
        // FLOATER_STACK_PX times the count of active floaters already at the
        // same tile, and delay its start by a few frames so the rise reads
        // as sequential rather than simultaneous.
        if ((e.type == Effect.EffectType.FLOATING_TEXT
                || e.type == Effect.EffectType.DAMAGE_FLOATER)
                && e.location != null) {
            int stacked = 0;
            for (Effect a : active) {
                if (a == null || a.location == null) continue;
                if (a.type != Effect.EffectType.FLOATING_TEXT
                        && a.type != Effect.EffectType.DAMAGE_FLOATER) continue;
                if (a.location.tileX() == e.location.tileX()
                        && a.location.tileY() == e.location.tileY()) {
                    stacked++;
                }
            }
            if (stacked > 0) {
                e.pixelOffsetY += stacked * FLOATER_STACK_PX;
                e.startDelay   += stacked * FLOATER_STACK_DELAY_FRAMES;
            }
        }
        active.add(e);
    }

    /** Per-stack-step vertical offset (virtual px) applied to a fresh
     *  floater for each floater already alive on the same tile. */
    private static final float FLOATER_STACK_PX = 9f;
    /** Per-stack-step delay (frames) so each stacked floater starts a beat
     *  later, giving the eye a chance to track them as a sequence. */
    private static final int   FLOATER_STACK_DELAY_FRAMES = 4;

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
