package com.bjsp123.rl2.ai.game;

import com.bjsp123.rl2.event.GameEvent;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Warrior-stuck diagnostic logger. Aggregates per-level dwell, decision
 * mix, stairs reachability, position diversity, and bomb/wand/melee
 * effectiveness across one {@link AutoplayGame}. Writes a small plaintext
 * log file (~20-100 lines) at run end instead of per-tick CSVs.
 */
public final class StuckTelemetry {

    private static final int STALL_DEPTH_THRESHOLD = 50_000;
    private static final int STALL_KNOWN_THRESHOLD = 25_000;
    private static final int STALL_PICKUP_THRESHOLD = 100_000;
    private static final int STALL_LOG_COOLDOWN = 25_000;
    private static final int RECENT_TILES_WINDOW = 10_000;

    private final PrintWriter writer;
    private final Mob agent;
    private final long seed;

    private int prevLevelIdx = Integer.MIN_VALUE;
    private LevelDwell cur;

    private int lastDepthChangeTick;
    private int lastKnownIncreaseTick;
    private int lastPickupTick;
    private int lastStallEmitTick = Integer.MIN_VALUE;

    private int prevBagSize;

    private String lastOffensiveAction = "-";
    private final Counter bombs = new Counter();
    private final Counter wands = new Counter();
    private final Counter melee = new Counter();

    private int bombThrowsPending;
    private int bombDuds;

    private final List<LevelDwell> allDwells = new ArrayList<>();

    public StuckTelemetry(Mob agent, long seed) throws IOException {
        this.agent = agent;
        this.seed  = seed;
        String name = "autoplay-stuck-" + agent.mobType + "-" + seed + ".log";
        this.writer = new PrintWriter(Files.newBufferedWriter(
                Paths.get(name).toAbsolutePath()));
        line("RUN_START tick=0 char=" + agent.mobType + " seed=" + seed);
    }

    /** Per-tick update, called from AutoplayGame.tick after cur is refreshed. */
    public void onTick(int tick, int levelIdx, double knownFraction,
                       Point exploreTarget, Point bfsStepFrontier,
                       Point bfsStepStairs, Point stairsDown,
                       String branchName, String actionName) {
        if (prevLevelIdx == Integer.MIN_VALUE) {
            startLevel(tick, levelIdx, "start", agent.position);
            lastDepthChangeTick = tick;
            lastKnownIncreaseTick = tick;
            lastPickupTick = tick;
            prevBagSize = bagSize();
        } else if (levelIdx != prevLevelIdx) {
            endLevel(tick);
            String via = "stairs";
            if (levelTransitionVia != null) via = levelTransitionVia;
            startLevel(tick, levelIdx, via, agent.position);
            lastDepthChangeTick = tick;
            levelTransitionVia = null;
        }
        prevLevelIdx = levelIdx;

        if (cur != null) {
            if (agent.position != null) {
                long packed = ((long) agent.position.tileX() << 16)
                        | (agent.position.tileY() & 0xFFFFL);
                cur.tilesVisited.add(packed);
                cur.recentTiles.addLast(packed);
                cur.recentTilesAge.addLast(tick);
                while (!cur.recentTilesAge.isEmpty()
                        && tick - cur.recentTilesAge.peekFirst() > RECENT_TILES_WINDOW) {
                    cur.recentTilesAge.pollFirst();
                    cur.recentTiles.pollFirst();
                }
            }
            if (knownFraction > cur.maxKnown) {
                cur.maxKnown = knownFraction;
                lastKnownIncreaseTick = tick;
            }
            if (stairsDown != null && bfsStepStairs == null) {
                cur.stairsKnownButUnreachable = true;
            }
            if (stairsDown != null && bfsStepStairs != null) {
                cur.stairsDownReached |= isAdjacentOrAt(agent.position, stairsDown);
            }
            String key = (branchName == null ? "?" : branchName);
            cur.branchCounts.merge(key, 1, Integer::sum);
            String dkey = key + "/" + (actionName == null ? "?" : actionName);
            cur.decisionCounts.merge(dkey, 1, Integer::sum);
            cur.lastBranch = key;
            cur.lastAction = actionName == null ? "?" : actionName;
            cur.lastExploreTarget = exploreTarget;
            cur.lastBfsStepFrontier = bfsStepFrontier;
            cur.lastBfsStepStairs = bfsStepStairs;
        }

        maybeStall(tick, levelIdx, knownFraction, exploreTarget,
                bfsStepFrontier, bfsStepStairs);

        int bag = bagSize();
        if (bag > prevBagSize) lastPickupTick = tick;
        prevBagSize = bag;
        if (cur != null) cur.bagDelta = bag - cur.bagSizeAtEntry;
    }

    /** Per-event update, called from AutoplayGame.observeEvents. */
    public void onEvent(int tick, GameEvent ev) {
        if (ev == null) return;
        if (ev instanceof GameEvent.ItemThrown t) {
            if (t.thrower() == agent && t.item() != null
                    && t.item().inventoryCategory
                            == com.bjsp123.rl2.model.Item.InventoryCategory.BOMB) {
                lastOffensiveAction = "throw";
                bombs.uses++;
                bombThrowsPending++;
            }
        } else if (ev instanceof GameEvent.WandMissileFired w) {
            if (w.caster() == agent) { lastOffensiveAction = "wand"; wands.uses++; }
        } else if (ev instanceof GameEvent.WandRayFired w) {
            if (w.caster() == agent) { lastOffensiveAction = "wand"; wands.uses++; }
        } else if (ev instanceof GameEvent.MagicMissileFired m) {
            if (m.caster() == agent) { lastOffensiveAction = "wand"; wands.uses++; }
        } else if (ev instanceof GameEvent.MobMeleeAttacked m) {
            if (m.attacker() == agent) { lastOffensiveAction = "melee"; melee.uses++; }
        } else if (ev instanceof GameEvent.MobKilled k) {
            if (k.killer() == agent && cur != null) cur.kills++;
        } else if (ev instanceof GameEvent.MobFellThroughChasm) {
            tryRecordTransition(ev, "fall");
        } else if (ev instanceof GameEvent.MobTeleported) {
            tryRecordTransition(ev, "scatter");
        } else {
            String type = ev.getClass().getSimpleName();
            if ("DamageDealt".equals(type) || "PeriodicBuffDamage".equals(type)) {
                Mob attacker = reflectMob(ev, "attacker", "source", "killer");
                int amount = reflectInt(ev, "amount", "damage");
                if (attacker == agent && amount > 0) {
                    Counter c = pickCounter(lastOffensiveAction);
                    if (c != null) { c.hits++; c.damage += amount; }
                    if ("throw".equals(lastOffensiveAction) && bombThrowsPending > 0) {
                        bombThrowsPending = Math.max(0, bombThrowsPending - 1);
                    }
                }
            }
        }
    }

    /** Called at the start of each tick BEFORE observeEvents so we can clear
     *  the per-tick "bomb landed" tracker. */
    public void onTickStart(int tick) {
        if (bombThrowsPending > 0) {
            bombDuds += bombThrowsPending;
            bombThrowsPending = 0;
        }
    }

    /** Run-end summary. */
    public void close(String outcome, int totalTicks, int finalDepth, int maxDepth) {
        if (cur != null) endLevel(totalTicks);
        line("EFFECTIVENESS bombs=thrown:" + bombs.uses
                + ",hit:" + bombs.hits
                + ",dmg:" + bombs.damage
                + " (dpt:" + safeRatio(bombs.damage, bombs.uses)
                + " dph:" + safeRatio(bombs.damage, bombs.hits)
                + ") bombDuds:" + bombDuds);
        line("EFFECTIVENESS wands=fired:" + wands.uses
                + ",hit:" + wands.hits
                + ",dmg:" + wands.damage
                + " (dpf:" + safeRatio(wands.damage, wands.uses)
                + " dph:" + safeRatio(wands.damage, wands.hits) + ")");
        line("EFFECTIVENESS melee=swings:" + melee.uses
                + ",hit:" + melee.hits
                + ",dmg:" + melee.damage
                + " (dph:" + safeRatio(melee.damage, melee.hits) + ")");

        StringBuilder timeline = new StringBuilder("LEVELS_TIMELINE ");
        long totalTicksAccounted = 0;
        long weightedDepth = 0;
        for (LevelDwell d : allDwells) {
            timeline.append("d").append(d.depth)
                    .append("[t=").append(d.ticksHere())
                    .append(",vis=").append(d.tilesVisited.size())
                    .append(",known=").append(String.format("%.0f%%", d.maxKnown * 100))
                    .append(",kills=").append(d.kills)
                    .append(d.stairsDownReached ? ",reached" : "")
                    .append(d.stairsKnownButUnreachable ? ",unreachable" : "")
                    .append("] ");
            totalTicksAccounted += d.ticksHere();
            weightedDepth += (long) d.depth * d.ticksHere();
        }
        line(timeline.toString().trim());

        double twDepth = totalTicksAccounted == 0
                ? 0.0 : (double) weightedDepth / totalTicksAccounted;
        line("RUN_END outcome=" + outcome
                + " finalDepth=" + finalDepth
                + " maxDepth=" + maxDepth
                + " totalLevelsVisited=" + allDwells.size()
                + " timeWeightedDepth=" + String.format("%.2f", twDepth));
        writer.close();
    }

    /* ---------- internals ---------- */

    private String levelTransitionVia;

    private void tryRecordTransition(GameEvent ev, String via) {
        Mob mob = reflectMob(ev, "mob", "target", "victim");
        if (mob == agent) levelTransitionVia = via;
    }

    private void maybeStall(int tick, int levelIdx, double knownFraction,
                            Point exploreTarget, Point bfsStepFrontier,
                            Point bfsStepStairs) {
        if (cur == null) return;
        int dDepth = tick - lastDepthChangeTick;
        int dKnown = tick - lastKnownIncreaseTick;
        int dPick  = tick - lastPickupTick;
        boolean isStall = dDepth > STALL_DEPTH_THRESHOLD
                || dKnown > STALL_KNOWN_THRESHOLD
                || dPick  > STALL_PICKUP_THRESHOLD;
        if (!isStall) return;
        if (tick - lastStallEmitTick < STALL_LOG_COOLDOWN) return;
        lastStallEmitTick = tick;
        line("STALL tick=" + tick
                + " depth=" + levelIdx
                + " sinceDepth=" + dDepth
                + " sinceKnown=" + dKnown
                + " sincePickup=" + dPick
                + " branchNow=" + cur.lastBranch + "/" + cur.lastAction
                + " exploreTarget=" + fmt(exploreTarget)
                + " bfsFrontier=" + fmt(bfsStepFrontier)
                + " bfsStairs=" + fmt(bfsStepStairs)
                + " distinctTilesLast10k=" + new HashSet<>(cur.recentTiles).size()
                + " bag=" + bagSummary());
    }

    private void startLevel(int tick, int levelIdx, String via, Point at) {
        cur = new LevelDwell();
        cur.depth = levelIdx;
        cur.enterTick = tick;
        cur.bagSizeAtEntry = bagSize();
        line("LEVEL_ENTER tick=" + tick
                + " depth=" + levelIdx
                + " via=" + via
                + " enterPos=" + fmt(at));
    }

    private void endLevel(int tick) {
        if (cur == null) return;
        cur.leftTick = tick;
        allDwells.add(cur);
        int total = cur.ticksHere();
        StringBuilder branchPct = new StringBuilder();
        cur.branchCounts.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String,Integer>>comparingInt(Map.Entry::getValue).reversed())
                .forEach(e -> {
                    if (branchPct.length() > 0) branchPct.append(" ");
                    branchPct.append(e.getKey()).append("=")
                            .append(total == 0 ? 0 : (e.getValue() * 100 / total))
                            .append("%");
                });
        StringBuilder top3 = new StringBuilder();
        cur.decisionCounts.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String,Integer>>comparingInt(Map.Entry::getValue).reversed())
                .limit(3)
                .forEach(e -> {
                    if (top3.length() > 0) top3.append(",");
                    top3.append(e.getKey()).append(":").append(e.getValue());
                });
        line("LEVEL_LEAVE tick=" + tick
                + " depth=" + cur.depth
                + " ticksHere=" + total
                + " tilesVisited=" + cur.tilesVisited.size()
                + " kills=" + cur.kills
                + " bagDelta=" + (cur.bagDelta >= 0 ? "+" : "") + cur.bagDelta
                + " maxKnown=" + String.format("%.0f%%", cur.maxKnown * 100)
                + " branchPct={" + branchPct + "}"
                + " top3={" + top3 + "}"
                + " stairsReached=" + cur.stairsDownReached
                + " stairsKnownButUnreachable=" + cur.stairsKnownButUnreachable);
    }

    private Counter pickCounter(String tag) {
        return switch (tag) {
            case "throw" -> bombs;
            case "wand"  -> wands;
            case "melee" -> melee;
            default      -> null;
        };
    }

    private int bagSize() {
        if (agent.inventory == null || agent.inventory.bag == null) return 0;
        int n = 0;
        for (var it : agent.inventory.bag) {
            if (it != null) n += Math.max(1, it.count);
        }
        return n;
    }

    private String bagSummary() {
        if (agent.inventory == null || agent.inventory.bag == null) return "-";
        int p = 0, b = 0, f = 0, t = 0, w = 0, a = 0;
        for (var it : agent.inventory.bag) {
            if (it == null || it.inventoryCategory == null) continue;
            int n = Math.max(1, it.count);
            switch (it.inventoryCategory) {
                case POTION -> p += n;
                case BOMB   -> b += n;
                case FOOD   -> f += n;
                case TOOL   -> t += n;
                case WEAPON, OFFHAND -> w += n;
                case ARMOR  -> a += n;
                default     -> { }
            }
        }
        return "P=" + p + ";B=" + b + ";F=" + f + ";T=" + t + ";W=" + w + ";A=" + a;
    }

    private static boolean isAdjacentOrAt(Point a, Point b) {
        if (a == null || b == null) return false;
        return Math.max(Math.abs(a.tileX() - b.tileX()),
                        Math.abs(a.tileY() - b.tileY())) <= 1;
    }

    private static String fmt(Point p) {
        return p == null ? "null" : p.tileX() + "," + p.tileY();
    }

    private static String safeRatio(long num, long den) {
        if (den == 0) return "n/a";
        return String.format("%.2f", (double) num / den);
    }

    private static Mob reflectMob(GameEvent ev, String... names) {
        var comps = ev.getClass().getRecordComponents();
        if (comps == null) return null;
        for (var c : comps) {
            for (String n : names) {
                if (c.getName().equals(n)) {
                    try {
                        Object v = c.getAccessor().invoke(ev);
                        if (v instanceof Mob m) return m;
                    } catch (Exception ignored) { }
                }
            }
        }
        return null;
    }

    private static int reflectInt(GameEvent ev, String... names) {
        var comps = ev.getClass().getRecordComponents();
        if (comps == null) return 0;
        for (var c : comps) {
            for (String n : names) {
                if (c.getName().equals(n)) {
                    try {
                        Object v = c.getAccessor().invoke(ev);
                        if (v instanceof Number num) return num.intValue();
                    } catch (Exception ignored) { }
                }
            }
        }
        return 0;
    }

    private void line(String s) { writer.println(s); writer.flush(); }

    private static final class Counter {
        long uses, hits, damage;
    }

    private static final class LevelDwell {
        int depth, enterTick, leftTick;
        int bagSizeAtEntry, bagDelta;
        int kills;
        double maxKnown;
        boolean stairsDownReached;
        boolean stairsKnownButUnreachable;
        final Set<Long> tilesVisited = new HashSet<>();
        final Deque<Long> recentTiles = new ArrayDeque<>();
        final Deque<Integer> recentTilesAge = new ArrayDeque<>();
        final Map<String,Integer> branchCounts = new HashMap<>();
        final Map<String,Integer> decisionCounts = new HashMap<>();
        String lastBranch = "?";
        String lastAction = "?";
        Point lastExploreTarget;
        Point lastBfsStepFrontier;
        Point lastBfsStepStairs;
        int ticksHere() { return leftTick - enterTick; }
    }
}
