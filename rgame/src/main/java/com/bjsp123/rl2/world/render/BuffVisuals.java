package com.bjsp123.rl2.world.render;

import com.bjsp123.rl2.model.Buff;
import com.bjsp123.rl2.model.Buff.BuffType;
import com.bjsp123.rl2.model.Mob;

import java.util.EnumMap;

/**
 * Data-driven table of the continuous on-mob visual for each {@link BuffType} (RL-44).
 * Presentation-side (lives in {@code rgame} like {@link BuffIcons}) - maps a buff to a
 * {@link Category} plus the tint / particle parameters its generic renderer needs.
 *
 * <p>The five buffs that already had bespoke render-time visuals before RL-44 - ON_FIRE
 * (fire particles), INVISIBLE (alpha pulse), FROZEN (ice overlay), PHASE (strips),
 * SHIELDED (cyan glow), LEVITATING (hover + foot-puffs) - keep their existing code paths
 * and map to {@link Category#NONE} here so they aren't double-drawn.
 *
 * <p>Consumers:
 * <ul>
 *   <li>{@code DefaultLevelRenderer} - OUTLINE_PULSE (tinted outline), GLOW (shell behind
 *       sprite), DESATURATE (grey overlay), TREMBLE (x jitter), ZOOM (motion smear),
 *       OVERHEAD_ICON (icon above the head).</li>
 *   <li>{@code Animator.tickBuffParticles} - DRIFT (emits a tinted particle near the mob).</li>
 * </ul>
 */
public final class BuffVisuals {

    private BuffVisuals() {}

    /** How a buff is rendered continuously while active. */
    public enum Category { NONE, OUTLINE_PULSE, GLOW, DRIFT, OVERHEAD_ICON, DESATURATE, TREMBLE, ZOOM }

    /** Which particle factory a {@link Category#DRIFT} buff emits. */
    public enum Drift { NONE, ARROW_UP, BURST, DROPS, BUBBLES }

    /** One buff's visual descriptor. {@code r,g,b} are the outline/glow tint (0..1);
     *  {@code particleTint}/{@code drift} drive DRIFT emission; {@code grayAlpha} is the
     *  DESATURATE overlay strength. Fields not relevant to the category are left at their
     *  neutral defaults. */
    public static final class V {
        public final Category category;
        public final float r, g, b;
        public final Effect.EffectTint particleTint;
        public final Drift drift;
        public final float grayAlpha;

        V(Category category, float r, float g, float b,
          Effect.EffectTint particleTint, Drift drift, float grayAlpha) {
            this.category = category;
            this.r = r; this.g = g; this.b = b;
            this.particleTint = particleTint;
            this.drift = drift;
            this.grayAlpha = grayAlpha;
        }
    }

    public static final V NONE = new V(Category.NONE, 0, 0, 0, null, Drift.NONE, 0);

    private static V outline(float r, float g, float b) {
        return new V(Category.OUTLINE_PULSE, r, g, b, null, Drift.NONE, 0);
    }
    private static V glow(float r, float g, float b) {
        return new V(Category.GLOW, r, g, b, null, Drift.NONE, 0);
    }
    private static V drift(Effect.EffectTint tint, Drift d) {
        return new V(Category.DRIFT, 0, 0, 0, tint, d, 0);
    }
    private static V icon() {
        return new V(Category.OVERHEAD_ICON, 0, 0, 0, null, Drift.NONE, 0);
    }
    private static V gray(float alpha) {
        return new V(Category.DESATURATE, 0, 0, 0, null, Drift.NONE, alpha);
    }
    private static V simple(Category c) {
        return new V(c, 0, 0, 0, null, Drift.NONE, 0);
    }

    private static final EnumMap<BuffType, V> TABLE = new EnumMap<>(BuffType.class);
    static {
        // Outline pulses - resist buffs.
        TABLE.put(BuffType.PROTECTION, outline(0.30f, 1.00f, 0.40f));   // green
        TABLE.put(BuffType.ANTI_MAGIC, outline(0.35f, 0.55f, 1.00f));   // blue
        TABLE.put(BuffType.SORCERY,    outline(0.30f, 0.95f, 1.00f));   // cyan
        // Glow behind the sprite.
        TABLE.put(BuffType.HOPE,       glow(1.00f, 0.85f, 0.35f));      // golden
        // Drifting particles.
        TABLE.put(BuffType.REGENERATION, drift(Effect.EffectTint.GREEN, Drift.ARROW_UP));
        TABLE.put(BuffType.POISONED,     drift(Effect.EffectTint.GREEN, Drift.BUBBLES));
        TABLE.put(BuffType.CHILLED,      drift(Effect.EffectTint.WHITE, Drift.DROPS));
        TABLE.put(BuffType.OILY,         drift(Effect.EffectTint.BROWN, Drift.DROPS));
        TABLE.put(BuffType.WET,          drift(Effect.EffectTint.BLUE,  Drift.DROPS));
        TABLE.put(BuffType.BLEEDING,     drift(Effect.EffectTint.RED,   Drift.DROPS));
        // Over-head icons (eye / killer eyes) via BuffIcons atlas. Cooldown buffs are
        // deliberately NOT shown over mobs' heads - they're internal recharging timers.
        TABLE.put(BuffType.ESP,    icon());
        TABLE.put(BuffType.INSIGHT, icon());
        // KILLER: no over-head icon - rendered as a red flame around the head by
        // DefaultLevelRenderer.drawKillerFlame instead.
        TABLE.put(BuffType.KILLER,  simple(Category.NONE));
        // Sprite-modulation effects.
        TABLE.put(BuffType.GHOSTLY,    gray(0.45f));
        TABLE.put(BuffType.HIDING,     gray(0.30f));
        // Boss revenants read washed-out; the blurry-edge pass is added directly
        // in DefaultLevelRenderer.drawMobSprite (keyed off the REVENANT buff).
        TABLE.put(BuffType.REVENANT,   gray(0.40f));
        TABLE.put(BuffType.FRIGHTENED, simple(Category.TREMBLE));
        TABLE.put(BuffType.HASTED,     simple(Category.ZOOM));
        // NONE (handled by pre-existing render code): ON_FIRE, INVISIBLE, FROZEN, PHASE,
        // SHIELDED, LEVITATING.
    }

    /** Visual descriptor for {@code type}, or {@link #NONE}. */
    public static V of(BuffType type) {
        return type == null ? NONE : TABLE.getOrDefault(type, NONE);
    }

    /** The descriptor of the first active buff on {@code mob} in category {@code cat},
     *  or {@code null} when the mob carries no such buff. Used by the render passes that
     *  need a single tint (outline / glow / grey). */
    public static V active(Mob mob, Category cat) {
        if (mob == null || mob.buffs == null) return null;
        for (Buff b : mob.buffs) {
            V v = of(b.type);
            if (v.category == cat) return v;
        }
        return null;
    }

    /** True if {@code mob} carries any buff in category {@code cat}. */
    public static boolean has(Mob mob, Category cat) {
        return active(mob, cat) != null;
    }
}
