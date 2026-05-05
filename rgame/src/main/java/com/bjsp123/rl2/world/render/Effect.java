package com.bjsp123.rl2.world.render;
import com.bjsp123.rl2.model.Buff;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Point;

import java.util.Random;

/**
 * Short-lived visual element drawn over the level. Pure presentation: every field is a
 * frame counter, pixel coordinate, particle parameter, or sprite hint. The engine
 * ({@code rlib}) doesn't reference this class; rgame's {@link com.bjsp123.rl2.world.anim.Animator}
 * builds Effects in response to {@link com.bjsp123.rl2.event.GameEvent}s and adds them
 * to {@link EffectStage}.
 *
 * <p>Owns its own {@code frame} counter advanced once per render frame by
 * {@link EffectStage#tick()}; finished effects are removed.
 */
public class Effect {
    public enum EffectType {
        EXPLOSION(8),
        SPARK(4),
        BLOOD_SPLATTER(6),
        SMOKE_PUFF(5),
        FLOATING_TEXT(60),
        THROWN_ITEM(8),
        PARTICLE_BURST(28),
        MAGIC_MISSILE(36),
        FIRE_PARTICLE(36),
        SLEEP_Z(48),
        TELEPORT_STREAKS(30),
        LIGHT_MOTE(80),
        RAY(24),
        BLAST(20),
        BUFF_ICON(60),
        ATTACK_FLASH(17);

        public final int frameCount;

        EffectType(int frameCount) {
            this.frameCount = frameCount;
        }
    }

    public enum EffectTint {
        RED, YELLOW, WHITE, GREEN, BLUE, BROWN, ORANGE
    }

    public static final float MAGIC_MISSILE_PX_PER_FRAME = 2.25f;
    public static final float THROWN_ITEM_PX_PER_FRAME   = 3.0f;
    private static final float TILE_PX = 16f;
    private static final float MAGIC_MISSILE_FLIGHT_FRACTION = 0.7f;

    public Point location;
    public Point endLocation;
    public EffectType type;
    public int frame;
    /** Per-instance override of the type's default lifetime; 0 means use the type default. */
    public int frameCount;
    /** Render frames to wait before this effect starts playing — see
     *  {@link EffectStage#tick()}. Used to stagger attack flashes when several mobs
     *  attack on the same tick so the slashes appear in sequence rather than all at
     *  once. Counts down once per render frame; while {@code > 0} the effect is
     *  parked at frame 0 and renderers skip drawing it. */
    public int startDelay;
    public String text;
    public EffectTint tint;
    public Item thrownItem;
    /** ATTACK_FLASH only: which side of the attacker to draw on. */
    public boolean facesRight;
    /** ATTACK_FLASH only: column on {@code buffs.png}'s lower row. */
    public int spriteCol;

    public float[] particleX0;
    public float[] particleY0;
    public float[] particleVX;
    public float[] particleVY;
    public int[] particleSpawnFrame;
    public EffectTint[] particleTints;
    public EffectTint headTint;
    public float particleGravity;
    public float particleSize = 1.5f;
    public boolean particleBright;
    /** BUFF_ICON only: which buff to render the icon for. */
    public Buff.BuffType buffType;

    public Effect() {}

    public Effect(Point location, EffectType type) {
        this.location = location;
        this.type = type;
        this.frame = 0;
    }

    public int totalFrames() {
        return frameCount > 0 ? frameCount : type.frameCount;
    }

    private static int framesForDistance(Point from, Point to, float pxPerFrame) {
        float dxTiles = to.tileX() - from.tileX();
        float dyTiles = to.tileY() - from.tileY();
        float distPx  = (float) Math.sqrt(dxTiles * dxTiles + dyTiles * dyTiles) * TILE_PX;
        return Math.max(1, Math.round(distPx / pxPerFrame));
    }

    public static Effect floatingText(Point location, String text, EffectTint tint) {
        Effect e = new Effect(location, EffectType.FLOATING_TEXT);
        e.text = text;
        e.tint = tint;
        return e;
    }

    public static Effect thrownItem(Point from, Point to, Item item) {
        Effect e = new Effect(from, EffectType.THROWN_ITEM);
        e.endLocation = to;
        e.thrownItem = item;
        e.frameCount = framesForDistance(from, to, THROWN_ITEM_PX_PER_FRAME);
        return e;
    }

    public static Effect attackFlash(Point at, boolean isPlayer, boolean facesRight) {
        return attackFlash(at, isPlayer, facesRight, 0);
    }

    /** Attack-flash with a render-frame start delay. {@code startDelay} parks the
     *  effect at frame 0 for that many frames before it begins playing — used to
     *  stagger flashes when multiple mobs attack on the same tick so the slashes
     *  appear in sequence rather than overlapping. */
    public static Effect attackFlash(Point at, boolean isPlayer, boolean facesRight,
                                     int startDelay) {
        Effect e = new Effect(at, EffectType.ATTACK_FLASH);
        e.spriteCol = isPlayer ? 0 : 1;
        e.facesRight = facesRight;
        e.startDelay = Math.max(0, startDelay);
        return e;
    }

    public static Effect magicMissile(Point from, Point to, Random rng) {
        Effect e = new Effect(from, EffectType.MAGIC_MISSILE);
        e.endLocation = to;
        int flightFrames = framesForDistance(from, to, MAGIC_MISSILE_PX_PER_FRAME);
        int flightEnd    = flightFrames;
        e.frameCount     = Math.max(1, Math.round(flightFrames / MAGIC_MISSILE_FLIGHT_FRACTION));
        int count = 24;
        e.particleX0 = new float[count];
        e.particleY0 = new float[count];
        e.particleVX = new float[count];
        e.particleVY = new float[count];
        e.particleSpawnFrame = new int[count];
        for (int i = 0; i < count; i++) {
            e.particleSpawnFrame[i] = (int) Math.round(i * (flightEnd - 1f) / Math.max(1, count - 1));
            e.particleX0[i] = (rng.nextFloat() - 0.5f) * 4f;
            e.particleY0[i] = (rng.nextFloat() - 0.5f) * 4f;
            e.particleVX[i] = (rng.nextFloat() - 0.5f) * 1.2f;
            e.particleVY[i] = (rng.nextFloat() - 0.5f) * 1.2f;
        }
        return e;
    }

    public static Effect magicMissileColored(Point from, Point to,
                                             EffectTint[] palette, EffectTint headTint,
                                             float gravity, float size, boolean bright,
                                             Random rng) {
        Effect e = new Effect(from, EffectType.MAGIC_MISSILE);
        e.endLocation     = to;
        e.particleTints   = palette;
        e.headTint        = headTint;
        e.particleGravity = gravity;
        e.particleSize    = size;
        e.particleBright  = bright;
        int flightFrames = framesForDistance(from, to, MAGIC_MISSILE_PX_PER_FRAME);
        int flightEnd    = flightFrames;
        e.frameCount     = Math.max(1, Math.round(flightFrames / MAGIC_MISSILE_FLIGHT_FRACTION));
        int count = 36;
        e.particleX0         = new float[count];
        e.particleY0         = new float[count];
        e.particleVX         = new float[count];
        e.particleVY         = new float[count];
        e.particleSpawnFrame = new int[count];
        for (int i = 0; i < count; i++) {
            e.particleSpawnFrame[i] = (int) Math.round(i * (flightEnd - 1f) / Math.max(1, count - 1));
            e.particleX0[i] = (rng.nextFloat() - 0.5f) * 4f;
            e.particleY0[i] = (rng.nextFloat() - 0.5f) * 4f;
            e.particleVX[i] = (rng.nextFloat() - 0.5f) * 0.8f;
            e.particleVY[i] = gravity > 0
                    ? 0.2f * (rng.nextFloat() - 0.5f)
                    : 0.6f + rng.nextFloat() * 0.7f;
        }
        return e;
    }

    public static Effect sleepZ(Point location, Random rng) {
        Effect e = new Effect(location, EffectType.SLEEP_Z);
        e.particleX0 = new float[]{ (rng.nextFloat() - 0.5f) * 6f };
        e.particleY0 = new float[]{ rng.nextFloat() * (float) (Math.PI * 2) };
        return e;
    }

    public static Effect lightMote(Point location, Random rng) {
        Effect e = new Effect(location, EffectType.LIGHT_MOTE);
        e.particleX0 = new float[]{ rng.nextFloat() * (float) (Math.PI * 2) };
        e.particleY0 = new float[]{ (rng.nextFloat() - 0.5f) * 6f };
        e.particleVX = new float[]{ 0.7f + rng.nextFloat() * 0.6f };
        return e;
    }

    public static Effect fireParticle(Point location, Random rng) {
        Effect e = new Effect(location, EffectType.FIRE_PARTICLE);
        e.particleX0 = new float[]{ rng.nextFloat() * (float) (Math.PI * 2) };
        e.particleY0 = new float[]{ 1.0f + rng.nextFloat() * 1.5f };
        e.particleVX = new float[]{ -1.5f + rng.nextFloat() * 3f };
        return e;
    }

    public static Effect explosion(Point location, int radiusTiles, Random rng) {
        Effect e = new Effect(location, EffectType.EXPLOSION);
        int frames = 28;
        e.frameCount = frames;
        int count = 18 + 10 * Math.max(1, radiusTiles);
        e.particleX0 = new float[count];
        e.particleY0 = new float[count];
        e.particleVX = new float[count];
        e.particleVY = new float[count];
        float maxRange = Math.max(1, radiusTiles) * TILE_PX;
        for (int i = 0; i < count; i++) {
            e.particleX0[i] = TILE_PX * 0.5f;
            e.particleY0[i] = TILE_PX * 0.5f;
            float ang = rng.nextFloat() * (float) (Math.PI * 2);
            float frac = 0.4f + rng.nextFloat() * 0.6f;
            float v = (frac * maxRange) / frames;
            e.particleVX[i] = (float) Math.cos(ang) * v;
            e.particleVY[i] = (float) Math.sin(ang) * v;
        }
        return e;
    }

    public static Effect ray(Point from, Point to, EffectTint tint) {
        Effect e = new Effect(from, EffectType.RAY);
        e.endLocation = to;
        e.tint        = tint;
        return e;
    }

    public static Effect blast(Point location) {
        return new Effect(location, EffectType.BLAST);
    }

    public static Effect buffIcon(Point location, Buff.BuffType type, String fallbackText) {
        Effect e = new Effect(location, EffectType.BUFF_ICON);
        e.text = fallbackText;
        e.buffType = type;
        return e;
    }

    public static Effect particleBurst(Point location, EffectTint tint, int count, Random rng) {
        Effect e = new Effect(location, EffectType.PARTICLE_BURST);
        e.tint = tint;
        e.particleX0 = new float[count];
        e.particleY0 = new float[count];
        e.particleVX = new float[count];
        e.particleVY = new float[count];
        for (int i = 0; i < count; i++) {
            e.particleX0[i] = 2f + rng.nextFloat() * 12f;
            e.particleY0[i] = 8f + rng.nextFloat() * 8f;
            e.particleVX[i] = (rng.nextFloat() - 0.5f) * 4.5f;
            e.particleVY[i] = 1.6f + rng.nextFloat() * 2.0f;
        }
        return e;
    }

    public static Effect teleportStreaks(Point location, boolean up, Random rng) {
        int count = 12;
        Effect e = new Effect(location, EffectType.TELEPORT_STREAKS);
        e.tint = EffectTint.GREEN;
        e.particleX0 = new float[count];
        e.particleY0 = new float[count];
        e.particleVX = new float[count];
        e.particleVY = new float[count];
        for (int i = 0; i < count; i++) {
            e.particleX0[i] = 1f + rng.nextFloat() * 14f;
            float speed = 0.7f + rng.nextFloat() * 0.6f;
            if (up) {
                e.particleY0[i] = rng.nextFloat() * 4f;
                e.particleVY[i] =  speed;
            } else {
                e.particleY0[i] = 12f + rng.nextFloat() * 4f;
                e.particleVY[i] = -speed;
            }
            e.particleVX[i] = 0f;
        }
        return e;
    }
}
