package com.bjsp123.rl2.save;

import com.bjsp123.rl2.event.GameEvent;
import com.bjsp123.rl2.logic.MobDefinition;
import com.bjsp123.rl2.logic.Registries;
import com.bjsp123.rl2.model.HallOfFameEntry;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.persistence.Persistence;
import com.bjsp123.rl2.save.Achievements.Achievement;

import java.util.function.Consumer;

/**
 * Central observer for achievement triggers. Owns the {@link Achievements}
 * unlock set; routes a small {@code observe*} surface into per-trigger
 * dispatch and persists on first unlock.
 *
 * <p>Lives entirely on the rgame side - every trigger comes from either
 * {@link GameEvent}s drained by the {@link com.bjsp123.rl2.world.anim.Animator}
 * or from PlayScreen / V2 popup hooks. rlib remains free of presentation
 * dependencies.
 *
 * <p>Adding a new achievement is one enum entry in {@link Achievement} plus
 * one branch in the matching {@code observe*} switch.
 */
public final class AchievementSystem {

    private final Achievements achievements;
    private final Persistence  persistence;
    private Consumer<Achievement> listener;

    public AchievementSystem(Achievements achievements, Persistence persistence) {
        this.achievements = achievements;
        this.persistence  = persistence;
    }

    /** Set the unlock listener. Fired synchronously from inside
     *  {@link #unlock(Achievement)} once per first-time unlock. PlayScreen
     *  uses this to drive the toast banner + log line. */
    public void setListener(Consumer<Achievement> onUnlock) {
        this.listener = onUnlock;
    }

    /** Clear the listener. Call from PlayScreen.dispose so the toast
     *  reference doesn't survive screen tear-down. */
    public void clearListener() {
        this.listener = null;
    }

    // -- Observation API ------------------------------------------------

    /** Polled per render frame from PlayScreen - once a depth threshold is
     *  passed the matching achievement unlocks; cheap no-op thereafter. */
    public void observeDepth(int depth) {
        if (depth >= 2) unlock(Achievement.BEGUN_THE_ADVENTURE);
        if (depth >= 5) unlock(Achievement.DUG_DEEPER);
        if (depth >  1) unlock(Achievement.INTO_THE_DEPTHS);
    }

    /** Forwarded from {@link com.bjsp123.rl2.world.anim.Animator#consume}.
     *  {@code player} may be {@code null} during cross-level transitions
     *  where the animator hasn't latched the new level's player yet - the
     *  switch short-circuits in those cases. */
    public void observeEvent(GameEvent ev, Mob player, Level level) {
        if (ev == null) return;
        if (ev instanceof GameEvent.MobKilled m) {
            if (player != null && m.killer() == player && m.mob() != null) {
                unlock(Achievement.FIRST_BLOOD);
                String type = m.mob().mobType;
                MobDefinition def = Registries.mob(type);
                if (def != null && def.unique) {
                    unlock(Achievement.GIANT_SLAYER);
                }
                // Career bestiary: track every species killed so the player
                // can chase "kill one of each" plus the per-faction subsets
                // (kobolds, orcs, pests, blobs, Pangur Ban). Player-class kit
                // rows aren't killable mobs; skip them so the all-species
                // goal stays achievable.
                if (type != null && !isPlayerKitType(type)
                        && achievements != null
                        && achievements.killedMobTypes.add(type)) {
                    AchievementsStore.save(persistence, achievements);
                    checkBestiaryUnlocks();
                }
                // A killed species was surely seen - keep the encyclopedia
                // reveal-set in step even if the FOV scan missed it.
                recordMobSeen(type);
            }
        } else if (ev instanceof GameEvent.ItemPickedUp m) {
            // Encyclopedia reveal: picking an item up counts as seeing it.
            if (m.picker() != null && m.picker().isPlayer && m.item() != null) {
                recordItemSeen(m.item().type);
            }
        } else if (ev instanceof GameEvent.WandMissileFired m) {
            if (player != null && m.caster() == player) {
                unlock(Achievement.WAND_NEWBIE);
            }
        } else if (ev instanceof GameEvent.WandRayFired m) {
            if (player != null && m.caster() == player) {
                unlock(Achievement.WAND_NEWBIE);
            }
        } else if (ev instanceof GameEvent.ItemThrown m) {
            if (player != null && m.thrower() == player) {
                unlock(Achievement.THROWN_AWAY);
            }
        } else if (ev instanceof GameEvent.RainbowBurst m) {
            // RainbowBurst is emitted by MobProgression on level-up at the
            // levelling mob's tile - match against the player's position to
            // distinguish player level-ups from enemy ones.
            if (player != null && player.position != null) {
                Point at = m.pos();
                if (at != null
                        && at.tileX() == player.position.tileX()
                        && at.tileY() == player.position.tileY()) {
                    unlock(Achievement.LEVELED_UP);
                }
            }
        }
    }

    /** Fired from PlayScreen after a HallOfFameEntry is recorded. */
    public void observeRunEnded(HallOfFameEntry entry) {
        if (entry == null) return;
        unlock(Achievement.HALL_OF_FAMER);
        // Career beacon thresholds: the entry carries the BEST single-run
        // beacon count, but we want the per-run total. {@code entry.beaconsLit}
        // is the run total - good enough as the trigger.
        if (entry.beaconsLit >= 5)  unlock(Achievement.LIT_5_BEACONS);
        if (entry.beaconsLit >= 10) unlock(Achievement.LIT_10_BEACONS);
        // Win-conditional achievements: class win, perfect win per class,
        // and difficulty-tier wins. {@code entry.charClass} is the enum
        // name ("WARRIOR" / "ROGUE" / "MAGE"); {@code entry.difficulty}
        // is the GameBalance.Difficulty enum name; legacy entries default
        // to NORMAL so a pre-difficulty save still scores the easier
        // achievement.
        if (!entry.victory) return;
        String cls  = entry.charClass == null ? "" : entry.charClass;
        String diff = entry.difficulty == null ? "" : entry.difficulty;
        switch (cls) {
            case "WARRIOR" -> {
                unlock(Achievement.WIN_WARRIOR);
                if (entry.allBeaconsLit) unlock(Achievement.PERFECT_WIN_WARRIOR);
            }
            case "ROGUE" -> {
                unlock(Achievement.WIN_ROGUE);
                if (entry.allBeaconsLit) unlock(Achievement.PERFECT_WIN_ROGUE);
            }
            case "MAGE" -> {
                unlock(Achievement.WIN_MAGE);
                if (entry.allBeaconsLit) unlock(Achievement.PERFECT_WIN_MAGE);
            }
            default -> { /* unknown class - skip per-class achievements */ }
        }
        switch (diff) {
            case "NORMAL"    -> unlock(Achievement.WIN_NORMAL);
            case "HARD"      -> unlock(Achievement.WIN_HARD);
            case "VERY_HARD" -> unlock(Achievement.WIN_VERY_HARD);
            default -> { /* EASY / GENTLE / SUPEREASY wins don't unlock anything here */ }
        }
    }

    // -- Encyclopedia seen-sets -------------------------------------------

    /** Record that the player has seen mob species {@code mobType}. Adds to
     *  {@link Achievements#seenMobTypes}; persists + re-checks
     *  {@link Achievement#SEEN_ALL_MOBS} only when the set actually grew, so
     *  the per-frame FOV scan stays cheap. */
    public void recordMobSeen(String mobType) {
        if (mobType == null || mobType.isEmpty() || achievements == null) return;
        if (!achievements.seenMobTypes.add(mobType)) return;
        AchievementsStore.save(persistence, achievements);
        if (seenAllMobs()) unlock(Achievement.SEEN_ALL_MOBS);
    }

    /** Record that the player has seen item type {@code itemType}. Same
     *  grow-then-persist contract as {@link #recordMobSeen}. */
    public void recordItemSeen(String itemType) {
        if (itemType == null || itemType.isEmpty() || achievements == null) return;
        if (!achievements.seenItemTypes.add(itemType)) return;
        AchievementsStore.save(persistence, achievements);
        if (seenAllItems()) unlock(Achievement.SEEN_ALL_ITEMS);
    }

    /** Sweep the player's own species + everything carried / equipped into
     *  the seen-sets. Called per tick from PlayScreen so starting kit,
     *  purchases, and forge outputs all reveal without bespoke hooks; the
     *  {@code record*} dedupe makes repeat sweeps near-free. */
    public void observePlayerLoadout(Mob player) {
        if (player == null) return;
        recordMobSeen(player.mobType);
        if (player.inventory == null) return;
        for (com.bjsp123.rl2.model.Item it : player.inventory.bag) {
            if (it != null) recordItemSeen(it.type);
        }
        player.inventory.forEachEquipped(it -> {
            if (it != null) recordItemSeen(it.type);
        });
    }

    /** Forget every seen mob / item so the encyclopedia hides its entries
     *  again (Settings > "Reset encyclopaedia"). Already-unlocked
     *  achievements stay unlocked - this only re-masks the book. */
    public void resetEncyclopedia() {
        if (achievements == null) return;
        if (achievements.seenMobTypes.isEmpty()
                && achievements.seenItemTypes.isEmpty()) return;
        achievements.seenMobTypes.clear();
        achievements.seenItemTypes.clear();
        AchievementsStore.save(persistence, achievements);
    }

    /** True when {@link Achievements#seenMobTypes} covers every creature the
     *  encyclopedia lists. Mirrors V2Encyclopedia.buildCreatureEntries's
     *  filter (every registry type MobFactory can spawn) so the achievement
     *  is exactly "reveal the whole Creatures tab". */
    private boolean seenAllMobs() {
        if (achievements == null) return false;
        Point dummy = new Point(0, 0);
        for (String type : Registries.mobTypes()) {
            if (com.bjsp123.rl2.logic.MobFactory.spawn(type, dummy) == null) continue;
            if (!achievements.seenMobTypes.contains(type)) return false;
        }
        return true;
    }

    /** True when {@link Achievements#seenItemTypes} covers every item type -
     *  the encyclopedia's Items tab lists the whole registry, so no filter. */
    private boolean seenAllItems() {
        if (achievements == null) return false;
        for (String type : Registries.itemTypes()) {
            if (!achievements.seenItemTypes.contains(type)) return false;
        }
        return true;
    }

    /** Player-class kit rows in mobs.csv that the player never actually
     *  encounters as a mob to fight. Their ENEMY_PLAYER_* counterparts are
     *  the enemy mirror-matches and DO count. */
    private static boolean isPlayerKitType(String type) {
        return "PLAYER_WARRIOR".equals(type)
            || "PLAYER_ROGUE".equals(type)
            || "PLAYER_MAGE".equals(type);
    }

    /** True when {@link Achievements#killedMobTypes} contains every
     *  killable mob species. Walked over the live registry so future
     *  species automatically join the requirement. */
    private boolean killedAllSpecies() {
        if (achievements == null) return false;
        for (String type : Registries.mobTypes()) {
            if (isPlayerKitType(type)) continue;
            if (!achievements.killedMobTypes.contains(type)) return false;
        }
        return true;
    }

    /** Faction kill-set achievements (kobolds, orcs, pests, blobs, Pangur Ban)
     *  plus the bestiary-complete sweep. Each requirement is a list of slots;
     *  every slot must be filled by killing ANY species in its alternatives
     *  set, so a unique counts the same as its base species (e.g. either RAT
     *  or UNIQUE_RAT satisfies the "rat" slot). Called after a new species
     *  lands in {@link Achievements#killedMobTypes}; each {@code unlock}
     *  short-circuits if already on the books, so re-evaluating every kill
     *  is cheap. */
    private void checkBestiaryUnlocks() {
        if (achievements == null) return;
        java.util.Set<String> killed = achievements.killedMobTypes;
        if (slotsFilled(killed, PEST_SLOTS))   unlock(Achievement.PEST_CONTROLLER);
        if (slotsFilled(killed, KOBOLD_SLOTS)) unlock(Achievement.KOBOLD_KRUSHER);
        if (slotsFilled(killed, CAT_SLOTS))    unlock(Achievement.CAT_MURDERER);
        if (slotsFilled(killed, ORC_SLOTS))    unlock(Achievement.ORC_SLAYER);
        if (slotsFilled(killed, BLOB_SLOTS))   unlock(Achievement.BLOBBICIDE);
        if (killedAllSpecies())                unlock(Achievement.KILLED_ONE_OF_EACH);
    }

    /** Every slot satisfied: each slot is an alternatives set, and the slot
     *  is filled when {@code killed} contains at least one of its options. */
    private static boolean slotsFilled(java.util.Set<String> killed,
                                       java.util.List<java.util.Set<String>> slots) {
        for (java.util.Set<String> slot : slots) {
            boolean any = false;
            for (String opt : slot) {
                if (killed.contains(opt)) { any = true; break; }
            }
            if (!any) return false;
        }
        return true;
    }

    /** Each slot lists every species that fills it - a base species and any
     *  {@code UNIQUE_*} variant. Killing either counts. */
    private static final java.util.List<java.util.Set<String>> PEST_SLOTS = java.util.List.of(
            java.util.Set.of("RAT",           "UNIQUE_RAT"),
            java.util.Set.of("SPIDER",        "UNIQUE_SPIDER"),
            java.util.Set.of("SOLDIER_BUG",   "UNIQUE_SOLDIER_BUG"),
            java.util.Set.of("LOATHESOME_BUG","UNIQUE_LOATHESOME_BUG"));
    private static final java.util.List<java.util.Set<String>> KOBOLD_SLOTS = java.util.List.of(
            java.util.Set.of("KOBOLD_FIGHTER",  "UNIQUE_KOBOLD_FIGHTER"),
            java.util.Set.of("KOBOLD_SPEARMAN", "UNIQUE_KOBOLD_SPEARMAN"),
            java.util.Set.of("KOBOLD_CLEAVER",  "UNIQUE_KOBOLD_CLEAVER"),
            java.util.Set.of("KOBOLD_GENERAL",  "UNIQUE_KOBOLD_GENERAL"));
    private static final java.util.List<java.util.Set<String>> ORC_SLOTS = java.util.List.of(
            java.util.Set.of("ORC_HALBERDIER", "UNIQUE_ORC_HALBERDIER"),
            java.util.Set.of("ORC_PRESIDENT",  "UNIQUE_ORC_PRESIDENT"),
            java.util.Set.of("ORC_SNIPER",     "UNIQUE_ORC_SNIPER"));
    private static final java.util.List<java.util.Set<String>> BLOB_SLOTS = java.util.List.of(
            java.util.Set.of("BLOB",      "UNIQUE_BLOB"),
            java.util.Set.of("KISSYBLOB", "UNIQUE_KISSYBLOB"));
    private static final java.util.List<java.util.Set<String>> CAT_SLOTS = java.util.List.of(
            java.util.Set.of("CAT_PB", "UNIQUE_CAT_PB"));

    // -- Internal --------------------------------------------------------

    /** Unlock {@code a}. No-op when already on the books. Persists the
     *  store and fires the listener exactly once per first-time unlock. */
    private void unlock(Achievement a) {
        if (a == null || achievements == null) return;
        if (!achievements.unlock(a)) return;        // already unlocked
        AchievementsStore.save(persistence, achievements);
        if (listener != null) listener.accept(a);
    }
}
