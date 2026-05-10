package com.bjsp123.rl2.save;

import java.util.EnumSet;
import java.util.Set;

/**
 * In-memory unlock-set for {@link Achievement}s. Pure data: tracks which
 * achievements the player has earned across every run. Persistence is the
 * separate concern of {@link AchievementsStore}.
 */
public final class Achievements {

    /** Earned achievements. Sorted by enum order at iteration time. */
    public final Set<Achievement> unlocked = EnumSet.noneOf(Achievement.class);

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
}
