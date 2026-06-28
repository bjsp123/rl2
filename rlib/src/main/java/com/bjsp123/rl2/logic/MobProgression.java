package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.HistoricalRecord;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Perk;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Character progression - XP accumulation and level-ups. The XP cost schedule
 * and global cap come from {@link GameBalance}; stat growth flows through the
 * shared {@code scaleAmount} rule in {@link MobStats#writeEffectiveStats},
 * driven by the mob's {@code characterLevel}. No per-level deltas live on the
 * Mob anymore - {@code setSpawnLevel} / {@code applyLevelUp} just bump the
 * level number and mark effective stats dirty.
 *
 * <p><b>XP cost schedule.</b> Advancing from level {@code N} to {@code N+1} costs
 * {@code N x GameBalance.XP_PER_LEVEL_STEP} XP. Cumulative XP to reach level
 * {@code L} from level 1 is {@code STEP x (L-1) x L / 2}.
 *
 * <p><b>Level-up effects</b> (applied once per level gained):
 * <ul>
 *   <li>+{@link GameBalance#PERK_POINTS_PER_LEVEL} perk point.</li>
 *   <li>Effective stats recomputed at the new level via the AMOUNT rule
 *       (damage / armour / accuracy / evasion / maxHp / etc).</li>
 *   <li>Current HP bumped by the {@code maxHp} delta so a full-HP character
 *       stays at full HP across the level-up.</li>
 * </ul>
 *
 * <p>Applies to ALL mobs, not just the player.
 */
public final class MobProgression {

    private MobProgression() {}

    /** Hard cap on per-perk level used by {@link #autoLevelUpPerks}. */
    private static final int PERK_LEVEL_CAP = 8;

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
     * Depth-adjusted spawn level for a mob of definition {@code def} appearing
     * on {@code level}. A mob whose depth-fraction band starts exactly at this
     * level spawns at level 1; for every {@link GameBalance#MOB_DEPTH_LEVEL_SCALE}
     * the level's depth-fraction is above {@code def.powerMin} the spawn gains
     * one level, capped at {@link GameBalance#MAX_MOB_DEPTH_LEVEL_SCALE} extra
     * levels. Clamped to [1, {@link GameBalance#MAX_CHARACTER_LEVEL}].
     *
     * <p>When {@code def} is null (unknown mob type) or its band isn't set,
     * falls back to level 1 - safer than the old {@code 1 + level.depth} which
     * over-leveled unknown / out-of-band spawns.
     */
    public static int depthAdjustedSpawnLevel(com.bjsp123.rl2.model.Level level,
                                              MobDefinition def) {
        if (level == null) return 1;
        double frac = LevelFactoryPopulate.depthFraction(level);
        double min  = def == null ? frac : def.powerMin;
        double over = frac - min;
        int extra   = over <= 0 ? 0
                : Math.min(GameBalance.MAX_MOB_DEPTH_LEVEL_SCALE,
                        (int) Math.floor(over / Math.max(1e-9, GameBalance.MOB_DEPTH_LEVEL_SCALE)));
        return Math.min(GameBalance.MAX_CHARACTER_LEVEL, 1 + extra);
    }

    /**
     * Spawn-time level setter for fresh mobs. Sets {@code mob.characterLevel}
     * to {@code targetLevel} and re-seats HP at the new effective max. No
     * XP / history / level-up message - this is a quiet "born at level N"
     * assignment. Stat growth is computed lazily in MobStats.
     */
    public static void setSpawnLevel(Mob mob, int targetLevel) {
        if (mob == null) return;
        int target = Math.min(GameBalance.MAX_CHARACTER_LEVEL, Math.max(1, targetLevel));
        mob.characterLevel = target;
        // Born at level N => carry the matching cumulative XP, so the mob can
        // actually advance from here. Without this a mob spawned above level 1
        // sits at xp 0 - a full level schedule below its rank - and can never
        // gain a level (awardXp needs xp >= xpToReach(level+1)).
        mob.xp = xpToReach(target);
        mob.statsDirty = true;
        mob.hp = mob.effectiveStats().maxHp;
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
        // Snapshot the pre-bump effective maxHp so we can carry the same
        // amount onto current HP (full-HP characters stay full).
        double oldMaxHp = mob.effectiveStats().maxHp;
        mob.characterLevel++;
        mob.perkPoints++;
        mob.statsDirty = true;
        double newMaxHp = mob.effectiveStats().maxHp;
        mob.hp = Math.min(newMaxHp, mob.hp + Math.max(0, newMaxHp - oldMaxHp));

        if (level != null && mob.history != null) {
            mob.history.add(HistoricalRecord.levelUp(
                    level.currentTurn, level.depth, mob.characterLevel));
        }
        if (emitRainbow && level != null && level.events != null && mob.position != null) {
            level.events.add(new com.bjsp123.rl2.event.GameEvent.RainbowBurst(mob.position));
        }
    }

    /** Auto-spend perk points appropriate to {@code mob.characterLevel} for
     *  AI-controlled players (arena, attract). Computes target
     *  {@code perkPoints = (charLvl - 1) * PERK_POINTS_PER_LEVEL}, then spends
     *  them: first across the mob's signature perks (those already at level
     *  >= 1 from {@code MobDefinition.apply}) until each hits the
     *  {@link #PERK_LEVEL_CAP}, then across any remaining perks at random.
     *  No-op on null mobs or those with a null {@code perks} map. */
    public static void autoLevelUpPerks(Mob mob, Random rng) {
        if (mob == null || mob.perks == null) return;
        int target = Math.max(0,
                (mob.characterLevel - 1) * GameBalance.PERK_POINTS_PER_LEVEL);
        mob.perkPoints = target;

        // Snapshot signature perks (currently at level >= 1).
        EnumSet<Perk> signatures = EnumSet.noneOf(Perk.class);
        for (Map.Entry<Perk, Integer> e : mob.perks.entrySet()) {
            if (e.getValue() != null && e.getValue() >= 1) signatures.add(e.getKey());
        }

        Perk[] all = Perk.values();
        List<Perk> candidates = new ArrayList<>(all.length);

        while (mob.perkPoints > 0) {
            candidates.clear();
            // Phase 1: signature perks below cap.
            for (Perk p : signatures) {
                if (mob.perks.getOrDefault(p, 0) < PERK_LEVEL_CAP) candidates.add(p);
            }
            // Phase 2: any perk below cap.
            if (candidates.isEmpty()) {
                for (Perk p : all) {
                    if (mob.perks.getOrDefault(p, 0) < PERK_LEVEL_CAP) candidates.add(p);
                }
            }
            if (candidates.isEmpty()) break;
            Perk chosen = candidates.get(rng.nextInt(candidates.size()));
            mob.perks.merge(chosen, 1, Integer::sum);
            mob.perkPoints--;
        }
    }
}
