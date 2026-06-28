package com.bjsp123.rl2.world.anim;
import com.bjsp123.rl2.model.Mob;

/**
 * Snapshot of a mob that's been removed from the level for game purposes but is
 * still mid-flicker / mid-fade on screen. The {@link Animator} drops it from the
 * level renderer once {@link #frame} crosses {@link MobAnimState#DEATH_TOTAL_FRAMES}.
 *
 * <p>The {@link #mob} reference is held only for sprite identity (mobType + faces);
 * gameplay code never sees it because it's already out of {@code level.mobs}.
 */
public final class Ghost {
    public final Mob mob;
    public final int x, y;
    public final boolean facingEast;
    public int frame;

    public Ghost(Mob mob, int x, int y, boolean facingEast) {
        this.mob = mob;
        this.x = x;
        this.y = y;
        this.facingEast = facingEast;
        this.frame = 0;
    }

    public boolean done() {
        return frame >= AnimationVars.deathTotalFrames();
    }

    public float alpha() { return alpha(0f); }

    /** Render alpha for the ghost - flicker twice then linear fade to zero.
     *  {@code sub} (0..1) smooths the linear fade above 60 Hz; the flicker phase
     *  stays on integer frames since it is a deliberate hard on/off blink. */
    public float alpha(float sub) {
        if (frame < AnimationVars.deathFlickerFrames()) {
            int half = frame / AnimationVars.DEATH_FLICKER_HALF_FRAMES;
            return (half % 2 == 0) ? AnimationVars.DEATH_FLICKER_LOW_ALPHA : 1f;
        }
        float fadeFrame = (frame + sub) - AnimationVars.deathFlickerFrames();
        if (fadeFrame >= AnimationVars.DEATH_FADE_FRAMES) return 0f;
        return 1f - fadeFrame / (float) AnimationVars.DEATH_FADE_FRAMES;
    }
}
