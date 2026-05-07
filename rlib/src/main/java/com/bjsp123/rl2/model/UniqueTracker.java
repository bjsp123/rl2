package com.bjsp123.rl2.model;

import java.util.HashSet;
import java.util.Set;

/**
 * World-wide bag of one-shot content ids: things that have already been generated
 * somewhere in the dungeon and must not be generated again.
 *
 * <p>Three parallel sets so callers don't have to namespace ids by hand. Today only
 * {@link #rooms} is consulted — unique mobs and unique items will land in the other
 * two without any other plumbing change. Saved with the {@link World} (libGDX
 * {@code Json} serialises {@code HashSet<String>} reflectively).
 */
public final class UniqueTracker {
    public Set<String> rooms = new HashSet<>();
    public Set<String> mobs  = new HashSet<>();
    public Set<String> items = new HashSet<>();
}
