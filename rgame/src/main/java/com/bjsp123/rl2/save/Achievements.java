package com.bjsp123.rl2.save;

import com.bjsp123.rl2.logic.TextCatalog;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/**
 * In-memory unlock-set for {@link Achievement}s. Pure data: tracks which
 * achievements the player has earned across every run. Persistence is the
 * separate concern of {@link AchievementsStore}.
 */
public final class Achievements {

    /** Earned achievements. Sorted by enum order at iteration time. */
    public final Set<Achievement> unlocked = EnumSet.noneOf(Achievement.class);

    /** Mob types the player has killed at least once across every run. Used
     *  by {@link AchievementSystem} to drive {@link Achievement#KILLED_ONE_OF_EACH}
     *  - a long-term collection goal. Persisted alongside {@link #unlocked}. */
    public final Set<String> killedMobTypes = new HashSet<>();

    /** Mob types the player has SEEN (in FOV, killed, or looked at) across
     *  every run. Drives encyclopedia reveal-gating (unseen creatures render
     *  as silhouettes with masked text) and
     *  {@link Achievement#SEEN_ALL_MOBS}. Persisted alongside
     *  {@link #unlocked}. */
    public final Set<String> seenMobTypes = new HashSet<>();

    /** Item types the player has seen (picked up, carried, or inspected on
     *  the floor) across every run. Drives encyclopedia reveal-gating and
     *  {@link Achievement#SEEN_ALL_ITEMS}. Persisted alongside
     *  {@link #unlocked}. */
    public final Set<String> seenItemTypes = new HashSet<>();

    /** {@code true} when the player has earned {@code a}. */
    public boolean isUnlocked(Achievement a) {
        return a != null && unlocked.contains(a);
    }

    /** Record {@code a} as earned. Returns {@code true} when this is the
     *  first time the achievement was unlocked (caller pairs that with a
     *  persistence write + any UI feedback); {@code false} when it was
     *  already on the books. */
    public boolean unlock(Achievement a) {
        if (a == null) return false;
        return unlocked.add(a);
    }

    /**
     * Player-progression milestones. Each entry is a flat data record - display
     * name + a short description of what triggers it, plus a {@link Category}
     * for grouping in the Hall of Fame Achievements tab and a {@code hidden}
     * flag for surprise / spoiler entries (rendered as {@code ???} until
     * unlocked). Triggers fire from rgame-side observation in
     * {@link AchievementSystem} so rlib stays free of presentation hooks.
     */
    public enum Achievement {

        BEGUN_THE_ADVENTURE(Category.DEPTH, false),
        DUG_DEEPER         (Category.DEPTH, false),
        INTO_THE_DEPTHS    (Category.EXPLORATION, false),
        SEEN_ALL_MOBS      (Category.EXPLORATION, false),
        SEEN_ALL_ITEMS     (Category.ITEMS, false),
        FIRST_BLOOD        (Category.COMBAT, false),
        GIANT_SLAYER       (Category.COMBAT, true),
        KILLED_ONE_OF_EACH (Category.COMBAT, true),
        PEST_CONTROLLER    (Category.COMBAT, true),
        KOBOLD_KRUSHER     (Category.COMBAT, true),
        CAT_MURDERER       (Category.COMBAT, true),
        ORC_SLAYER         (Category.COMBAT, true),
        BLOBBICIDE         (Category.COMBAT, true),
        LEVELED_UP         (Category.PROGRESSION, false),
        WAND_NEWBIE        (Category.ITEMS, false),
        THROWN_AWAY        (Category.ITEMS, false),
        LIT_5_BEACONS      (Category.PROGRESSION, false),
        LIT_10_BEACONS     (Category.PROGRESSION, false),
        HALL_OF_FAMER      (Category.RUN, false),
        WIN_WARRIOR        (Category.RUN, false),
        WIN_ROGUE          (Category.RUN, false),
        WIN_MAGE           (Category.RUN, false),
        PERFECT_WIN_WARRIOR(Category.RUN, false),
        PERFECT_WIN_ROGUE  (Category.RUN, false),
        PERFECT_WIN_MAGE   (Category.RUN, false),
        WIN_NORMAL         (Category.RUN, false),
        WIN_HARD           (Category.RUN, false),
        WIN_VERY_HARD      (Category.RUN, false);

        public final Category category;
        public final boolean  hidden;

        Achievement(Category category, boolean hidden) {
            this.category = category;
            this.hidden   = hidden;
        }

        public String displayName() {
            return TextCatalog.get("achievement." + name() + ".name");
        }

        public String description() {
            return TextCatalog.get("achievement." + name() + ".description");
        }

        /** Coarse grouping for display order in the Hall of Fame
         *  Achievements tab. Not a behavioural flag. */
        public enum Category {
            DEPTH,
            EXPLORATION,
            COMBAT,
            PROGRESSION,
            ITEMS,
            RUN;

            public String displayName() {
                return TextCatalog.get("achievement.category." + name());
            }
        }
    }
}
