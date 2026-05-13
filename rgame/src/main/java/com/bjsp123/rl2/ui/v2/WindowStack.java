package com.bjsp123.rl2.ui.v2;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Single back-history stack for every window in the game - full screens
 * (V2Screen subclasses) and popups (V2Inventory, V2Encyclopedia, etc.)
 * alike. Every "navigate forward" pushes a {@link Runnable} that
 * "navigates back" - closing whatever is now on top and restoring whatever
 * was beneath. {@link #back()} pops one entry and runs it, returning
 * {@code true} when something was popped.
 *
 * <p>Owned by {@link UiCtx} so any V2 surface can reach it without
 * plumbing a {@code Rl2Game} reference through every popup constructor.
 * {@link com.bjsp123.rl2.Rl2Game#pushScreen} / {@code popScreen} /
 * {@code setRootScreen} all delegate to this same stack so screen-level
 * and popup-level navigation share one history.
 */
public final class WindowStack {

    private final Deque<Runnable> backActions = new ArrayDeque<>();

    /** Push a "go back" action - typically closes the window just opened
     *  and restores the window beneath. */
    public void push(Runnable backAction) {
        if (backAction != null) backActions.push(backAction);
    }

    /** Pop and run the most recent back action. Returns {@code true} when
     *  something was popped, {@code false} when the stack was empty. */
    public boolean back() {
        Runnable r = backActions.pollFirst();
        if (r == null) return false;
        r.run();
        return true;
    }

    /** Drop every back action - used for "go to root" navigations
     *  (return to title, begin game, game over) where the destination
     *  shouldn't have any pop-able history. */
    public void clear() {
        backActions.clear();
    }

    public boolean isEmpty() { return backActions.isEmpty(); }
    public int size() { return backActions.size(); }
}
