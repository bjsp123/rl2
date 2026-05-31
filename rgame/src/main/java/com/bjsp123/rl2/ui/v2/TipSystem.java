package com.bjsp123.rl2.ui.v2;

import com.bjsp123.rl2.logic.TextCatalog;
import com.bjsp123.rl2.ui.skin.Settings;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * First-encounter tip tracking + queue.
 *
 * <p>Each unique entity ({@code "mob:WRAITH"}, {@code "item:HEALING_POTION"},
 * {@code "buff:POISONED"}, {@code "perk:KILLER"}, {@code "concept:hp"}) shows
 * its tip exactly once per character. The first time a trigger site calls
 * {@link #maybeShow(String, String, String, com.badlogic.gdx.graphics.g2d.TextureRegion)},
 * the tip is queued into {@link V2TipPopup}; subsequent triggers for the same
 * key are silent no-ops.
 *
 * <p>Tracking is in-memory per character (reset on new-game); we don't
 * persist {@code tipsShown} because tips are run-scoped — a new dungeon dive
 * should re-introduce species and items.
 *
 * <p>When {@link Settings#tipsEnabled()} is false, tips don't display but
 * are still <em>recorded</em> as shown — so re-enabling mid-run doesn't
 * fire-hose the player with everything they've already encountered.
 *
 * <p>The popup actor is set by {@link com.bjsp123.rl2.screen.PlayScreen} on
 * world init; {@link #setPopup(V2TipPopup)} is a no-op outside the live game.
 */
public final class TipSystem {

    private static final Set<String> shown = new HashSet<>();
    private static final Deque<Entry> queue = new ArrayDeque<>();
    private static V2TipPopup popup;

    private TipSystem() {}

    /** Wire the popup actor; called once from PlayScreen when the play
     *  surface is constructed. Null here means tip-show requests are
     *  silently dropped (e.g. attract mode, headless arena). */
    public static void setPopup(V2TipPopup p) { popup = p; }

    /** Drop the per-character tracking + queue. Called on new-game so the
     *  next character starts fresh. */
    public static void reset() {
        shown.clear();
        queue.clear();
    }

    /** Try to fire a tip. Looks up
     *  {@code mob.<TYPE>.tip} / {@code item.<TYPE>.tip} etc. via
     *  {@link TextCatalog}; missing keys silently no-op. Returns true if a
     *  tip was queued, false if it was already shown / tips are off / no
     *  tip text exists.
     *
     *  @param key       canonical key (e.g. "mob:WRAITH"), used for
     *                   uniqueness tracking only.
     *  @param textKey   strings.csv key (e.g. "mob.WRAITH.tip").
     *  @param titleKey  strings.csv key for the display title (e.g.
     *                   "mob.WRAITH.name"). When null/empty, the popup
     *                   title falls back to the key suffix.
     *  @param icon      icon to render in the popup. May be null. */
    public static boolean maybeShow(String key, String textKey, String titleKey,
                                    com.badlogic.gdx.graphics.g2d.TextureRegion icon) {
        if (key == null) return false;
        if (!shown.add(key)) return false;
        if (!Settings.tipsEnabled()) return false;
        String body = TextCatalog.getOrDefault(textKey, null);
        if (body == null || body.isEmpty()) return false;
        String title = titleKey == null
                ? null
                : TextCatalog.getOrDefault(titleKey, null);
        if (title == null || title.isEmpty()) {
            int colon = key.indexOf(':');
            title = colon >= 0 ? key.substring(colon + 1) : key;
        }
        queue.addLast(new Entry(title, body, icon));
        promoteQueueHeadIfIdle();
        return true;
    }

    /** Called by {@link V2TipPopup} when its current tip dismisses (click
     *  or auto-fade). Promotes the next queued tip if any. */
    static void onPopupDismissed() {
        promoteQueueHeadIfIdle();
    }

    private static void promoteQueueHeadIfIdle() {
        if (popup == null) return;
        if (popup.isShowing()) return;
        Entry e = queue.pollFirst();
        if (e != null) popup.show(e.title, e.body, e.icon);
    }

    /** Pending-tip record. */
    private record Entry(String title, String body,
                         com.badlogic.gdx.graphics.g2d.TextureRegion icon) {}
}
