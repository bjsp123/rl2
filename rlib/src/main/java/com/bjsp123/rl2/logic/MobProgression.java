package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.HistoricalRecord;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;

/**
 * Character progression — XP accumulation and level-ups. Tunables come from
 * {@link GameBalance}.
 *
 * <p><b>XP cost schedule.</b> Advancing from level {@code N} to {@code N+1} costs
 * {@code N × GameBalance.XP_PER_LEVEL_STEP} XP — 10/20/30/... by default. Cumulative XP
 * to <i>reach</i> level {@code L} from level 1 is {@code STEP × (L-1) × L / 2} — level 2 at
 * 10 XP, level 3 at 30, level 4 at 60, level 5 at 100, and so on.
 *
 * <p><b>Level-up effects</b> (applied once per level gained):
 * <ul>
 *   <li>+{@link GameBalance#PERK_POINTS_PER_LEVEL} perk point.</li>
 *   <li>+{@link GameBalance#ATTACK_PER_LEVEL}  attack  ({@link Mob#accuracy}).</li>
 *   <li>+{@link GameBalance#DEFENSE_PER_LEVEL} defense ({@link Mob#evasion}).</li>
 *   <li>+{@link GameBalance#HP_PER_LEVEL}      max HP (and current HP bumped by the same
 *       amount, so a full-HP character stays at full HP after the bonus).</li>
 * </ul>
 *
 * <p><b>XP never resets</b>; it's a lifetime counter, so surplus rolls over toward the next
 * threshold and a big kill can push a low-level mob through multiple tiers at once.
 *
 * <p>Applies to ALL mobs, not just the player — any mob that accrues XP can level up, up
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
     * <p>{@code level} is only used to time-stamp history entries (turn + depth) — the
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
     * current value (typically 1) up to {@code targetLevel}, applying the per-level
     * {@link GameBalance#MOB_ACCURACY_INCREMENT} / {@link GameBalance#MOB_EVASION_INCREMENT}
     * / {@link GameBalance#HP_PER_LEVEL} bumps cumulatively. No XP / history /
     * level-up message — this is a quiet "born at level N" assignment.
     *
     * <p>Used by {@code LevelFactoryPopulate} to scale mobs to dungeon depth and by the
     * wand-of-dog summon path to scale the summoned dog with the wand's level.
     */
    public static void setSpawnLevel(Mob mob, int targetLevel) {
        if (mob == null) return;
        int target = Math.min(GameBalance.MAX_CHARACTER_LEVEL, Math.max(1, targetLevel));
        while (mob.characterLevel < target) {
            mob.characterLevel++;
            mob.intrinsic.accuracy += GameBalance.MOB_ACCURACY_INCREMENT;
            mob.intrinsic.evasion  += GameBalance.MOB_EVASION_INCREMENT;
            mob.intrinsic.maxHp    += GameBalance.HP_PER_LEVEL;
            mob.hp       += GameBalance.HP_PER_LEVEL;
        }
    }

    /** Bump the mob one level and apply the level-up bonuses. Honors the
     *  {@link GameBalance#MAX_CHARACTER_LEVEL} cap — calling it on a max-level mob is a no-op.
     *  Records a history entry on the mob if {@code level} is non-null. */
    public static void applyLevelUp(Level level, Mob mob) {
        if (mob.characterLevel >= GameBalance.MAX_CHARACTER_LEVEL) return;
        mob.characterLevel++;
        mob.perkPoints += GameBalance.PERK_POINTS_PER_LEVEL;
        mob.intrinsic.accuracy   += GameBalance.ATTACK_PER_LEVEL;
        mob.intrinsic.evasion    += GameBalance.DEFENSE_PER_LEVEL;
        // Max HP grows and current HP grows by the same amount, so a full-HP character
        // stays at full HP right after the bump (rather than suddenly being below cap).
        mob.intrinsic.maxHp += GameBalance.HP_PER_LEVEL;
        mob.hp    += GameBalance.HP_PER_LEVEL;

        if (level != null && mob.history != null) {
            mob.history.add(HistoricalRecord.levelUp(
                    level.currentTurn, level.depth, mob.characterLevel));
        }
    }
}
