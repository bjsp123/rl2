package com.bjsp123.rl2.model;

import java.util.HashSet;
import java.util.Set;

/**
 * World-wide bag of one-shot content ids: things that have already been generated
 * somewhere in the dungeon and must not be generated again.
 *
 * <p>Three parallel sets so callers don't have to namespace ids by hand. Today only
 * {@link #rooms} is consulted - unique mobs and unique items will land in the other
 * two without any other plumbing change. Saved with the {@link World} (libGDX
 * {@code Json} serialises {@code HashSet<String>} reflectively).
 */
public final class UniqueTracker {
    public Set<String> rooms = new HashSet<>();
    public Set<String> mobs  = new HashSet<>();
    public Set<String> items = new HashSet<>();

    /** Depths (1-based) that produced a gem hearth (RL-51). Drives the
     *  escalating spawn odds: a depth with no hearth raises the next depth's
     *  chance (30% -> 50% -> 75%). World-persistent. */
    public Set<Integer> hearthDepths = new HashSet<>();

    /** Scratch set of perLevelUnique tags claimed during the level currently
     *  being generated. Cleared by {@link #resetForNewLevel} at the top of
     *  every {@code LevelFactory.createDungeonLevel} call so the constraint
     *  is correctly per-level. Transient - not saved with the world; once
     *  generation finishes the contents are irrelevant. */
    public transient Set<String> currentLevelPerLevelUniques = new HashSet<>();

    /** Called at the start of each level's generation to reset the
     *  per-level uniqueness scratch. */
    public void resetForNewLevel() {
        if (currentLevelPerLevelUniques == null) {
            currentLevelPerLevelUniques = new HashSet<>();
        } else {
            currentLevelPerLevelUniques.clear();
        }
    }
}
