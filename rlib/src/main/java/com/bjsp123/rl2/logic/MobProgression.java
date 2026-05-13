package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.HistoricalRecord;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;

/**
 * Character progression - XP accumulation and level-ups. The XP cost schedule
 * and global cap come from {@link GameBalance}; per-level stat deltas are
 * carried per-mob on the {@link Mob} itself (set from
 * {@code assets/data/mobs.csv}'s {@code *PerLevel} columns by
 * {@link MobDefinition#apply}).
 *
 * <p><b>XP cost schedule.</b> Advancing from level {@code N} to {@code N+1} costs
 * {@code N x GameBalance.XP_PER_LEVEL_STEP} XP - 10/20/30/... by default. Cumulative XP
 * to <i>reach</i> level {@code L} from level 1 is {@code STEP x (L-1) x L / 2} - level 2 at
 * 10 XP, level 3 at 30, level 4 at 60, level 5 at 100, and so on.
 *
 * <p><b>Level-up effects</b> (applied once per level gained):
 * <ul>
 *   <li>+{@link GameBalance#PERK_POINTS_PER_LEVEL} perk point.</li>
 *   <li>+{@link Mob#accuracyPerLevel} accuracy.</li>
 *   <li>+{@link Mob#evasionPerLevel} evasion.</li>
 *   <li>+{@link Mob#hpPerLevel} max HP (and current HP bumped by the same amount).</li>
 *   <li>+ damage / AP / ranged-damage / ranged-distance / armour ranges per the
 *       matching {@code *PerLevel} fields (defaults: dmg 1-2, armour 0-1, others 0).</li>
 * </ul>
 *
 * <p><b>XP never resets</b>; it's a lifetime counter, so surplus rolls over toward the next
 * threshold and a big kill can push a low-level mob through multiple tiers at once.
 *
 * <p>Applies to ALL mobs, not just the player - any mob that accrues XP can level up, up
 * to {@link GameBalance#MAX_CHARACTER_LEVEL}.
 */
public final class MobProgression {

    private MobProgression() {}

    /** XP cost to advance from {@code fromLevel} to {@code fromLevel + 1}. */
    public static int xpToAdvanceFrom(int fromLevel) {
        return Math.max(1, GameBalance.XP_PER_LEVEL_STEP * fromLevel);
    }

    /** Cumulative XP required to first reach {@code level} (level 1 requires 0 XP). */
    public static int xpToReach(int level) {
        if (level <= 1) return 0;
        int step = GameBalance.XP_PER_LEVEL_STEP;
        return step * (level - 1) * level / 2;
    }

    /**
     * Add {@code amount} XP to {@code mob}, applying any level-ups the new total crosses.
     * Stops at {@link GameBalance#MAX_CHARACTER_LEVEL}; excess XP past the cap is still
     * stored on {@link Mob#xp} but does not produce more level-ups. Returns the number of
     * levels gained (0 if none). Safe for {@code amount <= 0} (no-op).
     *
     * <p>{@code level} is only used to time-stamp history entries (turn + depth) - the
     * game-state mutations happen entirely on {@code mob}. May be {@code null} when history
     * time-stamping isn't available, in which case level-up history entries are skipped.
     */
    public static int awardXp(Level level, Mob mob, int amount) {
        if (mob == null || amount <= 0) return 0;
        mob.xp += amount;
        int gained = 0;
        while (mob.characterLevel < GameBalance.MAX_CHARACTER_LEVEL
                && mob.xp >= xpToReach(mob.characterLevel + 1)) {
            applyLevelUp(level, mob);
            gained++;
        }
        return gained;
    }

    /**
     * Spawn-time level setter for fresh mobs. Bumps {@code mob.characterLevel} from its
     * current value (typically 1) up to {@code targetLevel}, applying the per-mob
     * stat deltas cumulatively. No XP / history / level-up message - this is a quiet
     * "born at level N" assignment.
     */
    public static void setSpawnLevel(Mob mob, int targetLevel) {
        if (mob == null) return;
        int target = Math.min(GameBalance.MAX_CHARACTER_LEVEL, Math.max(1, targetLevel));
        while (mob.characterLevel < target) {
            mob.characterLevel++;
            applyPerLevelDeltas(mob);
        }
    }

    /** Bump the mob one level and apply the level-up bonuses. Honors the
     *  {@link GameBalance#MAX_CHARACTER_LEVEL} cap - calling it on a max-level mob is a no-op.
     *  Records a history entry on the mob if {@code level} is non-null, and emits a
     *  {@link com.bjsp123.rl2.event.GameEvent.RainbowBurst} celebratory visual. */
    public static void applyLevelUp(Level level, Mob mob) {
        applyLevelUp(level, mob, true);
    }

    /** As {@link #applyLevelUp(Level, Mob)} but {@code emitRainbow=false} suppresses the
     *  {@link com.bjsp123.rl2.event.GameEvent.RainbowBurst} - used by powerup pickup
     *  so the caller's own composite visual is the only one shown. */
    public static void applyLevelUp(Level level, Mob mob, boolean emitRainbow) {
        if (mob.characterLevel >= GameBalance.MAX_CHARACTER_LEVEL) return;
        mob.characterLevel++;
        mob.perkPoints++;
        applyPerLevelDeltas(mob);

        if (level != null && mob.history != null) {
            mob.history.add(HistoricalRecord.levelUp(
                    level.currentTurn, level.depth, mob.characterLevel));
        }
        if (emitRainbow && level != null && level.events != null && mob.position != null) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.RainbowBurst(mob.position));
        }
    }

    /** Add one tier's worth of {@code *PerLevel} deltas to the mob's intrinsic stats.
     *  Current HP grows by the same amount as max HP, so a full-HP character stays at
     *  full HP right after the bump. */
    private static void applyPerLevelDeltas(Mob mob) {
        mob.intrinsic.accuracy += mob.accuracyPerLevel;
        mob.intrinsic.evasion  += mob.evasionPerLevel;
        mob.intrinsic.maxHp    += mob.hpPerLevel;
        mob.hp                 += mob.hpPerLevel;
        mob.intrinsic.damage         = mob.intrinsic.damage      .plus(mob.damagePerLevel);
        mob.intrinsic.apDamage       = mob.intrinsic.apDamage    .plus(mob.apPerLevel);
        mob.intrinsic.rangedDamage   = mob.intrinsic.rangedDamage.plus(mob.rangedDamagePerLevel);
        mob.intrinsic.rangedDistance += mob.rangedDistancePerLevel;
        mob.intrinsic.armor          = mob.intrinsic.armor       .plus(mob.armorPerLevel);
    }
}
