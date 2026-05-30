package com.bjsp123.rl2.util;

import com.bjsp123.rl2.logic.CombatArena;
import com.bjsp123.rl2.logic.GameBalance;
import com.bjsp123.rl2.logic.MobFactory;
import com.bjsp123.rl2.logic.MobProgression;
import com.bjsp123.rl2.logic.Registries;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.UniqueTracker;
import com.bjsp123.rl2.model.World;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * [DEV / DIAGNOSTIC] 1-vs-N arena ranker. Not shipping code.
 *
 * <p>Each of the three player classes fights three instances of every
 * non-player mob in the roster. Outputs, per character level + player class,
 * a sorted list of which mobs give the player the most trouble, plus a
 * win/loss/draw summary, an action-mix breakdown, and per-fight rows in
 * {@code arena_fights.csv} (under the repo root).
 *
 * <p>Gradle: {@code ./gradlew :rlib:rank1vN --args="20"} (trials per pair).
 * Optional second arg {@code strip-jade-bull} strips JADE_BULL from the
 * player before each fight so we can A/B the perk's impact.
 *
 * <p>Reuses the per-class gear kit from {@link PlayerGearProvider} so the
 * player has depth-appropriate equipment, matching the 1v1
 * {@link FullArenaRankMain} run.
 */
public final class Arena1vNRankMain {

    private Arena1vNRankMain() {}

    private static final int[] LEVELS = {1, 10};
    private static final int MAX_STANDARD_TURNS = 200;
    private static final int ARENA_W = 18;
    private static final int ARENA_H = 18;
    private static final int DEFAULT_TRIALS = 5;
    private static final int MOBS_PER_TEAM = 3;

    private static final String[] PLAYER_CLASSES = {
            "PLAYER_WARRIOR", "PLAYER_MAGE", "PLAYER_ROGUE"
    };

    /** Built in {@link #main(String[])} after {@link #loadData(Path)} populates
     *  the registries - if we initialised this at class-load time, the world
     *  build would run against an empty registry and produce gem-only loot
     *  (gems are procedural and don't depend on the registry). */
    private static PlayerGearProvider GEAR;

    /** Per-fight action counts bucketed by (player class, char level, outcome).
     *  Keyed by {@code "<class>|<level>|<outcome>"} - flat keying keeps the
     *  printer trivial. Outcome is "win" / "loss" / "draw". */
    private static final java.util.Map<String, Tally> TALLIES =
            new java.util.LinkedHashMap<>();

    static final class Tally {
        long fights, melee, wand, bomb, potion, tool, eat, thrw;
    }

    private static Tally tally(String cls, int lvl, String outcome) {
        return TALLIES.computeIfAbsent(cls + "|" + lvl + "|" + outcome,
                k -> new Tally());
    }

    /** Per-fight CSV writer. One row per simulated fight so downstream
     *  analyses (action mixes, HP-remaining distributions, per-mob success
     *  rates) don't need a fresh arena run. */
    private static java.io.PrintWriter FIGHT_LOG;

    /** Diagnostic: strip JADE_BULL from the player's bag before each fight.
     *  Toggled via the {@code strip-jade-bull} CLI arg so we can measure
     *  the perk's impact by comparing W/L vs the baseline. */
    private static boolean STRIP_JADE_BULL = false;

    public static void main(String[] args) throws IOException {
        int trials = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_TRIALS;
        for (int i = 1; i < args.length; i++) {
            if ("strip-jade-bull".equalsIgnoreCase(args[i])) STRIP_JADE_BULL = true;
        }
        Path assets = locateAssetsDir();
        loadData(assets);
        GEAR = new PlayerGearProvider(0xC0FFEEL);

        Path csvOut = Paths.get(STRIP_JADE_BULL
                ? "arena_fights_no_jade_bull.csv"
                : "arena_fights.csv");
        FIGHT_LOG = new java.io.PrintWriter(Files.newBufferedWriter(csvOut));
        FIGHT_LOG.println("player_class,char_level,opponent_type,trial,outcome,"
                + "turns,melee,wand,bomb,potion,tool,eat,throw,"
                + "player_hp,player_max_hp,mobs_alive,mobs_total");

        List<String> mobs = pickMobs();
        System.out.println("[rl2-rank-1vN] mobs: " + mobs.size()
                + ", trials per pair: " + trials
                + ", player classes: " + PLAYER_CLASSES.length);
        System.out.println("[rl2-rank-1vN] per-fight CSV: " + csvOut.toAbsolutePath());

        for (int charLvl : LEVELS) {
            long t0 = System.currentTimeMillis();
            for (String playerType : PLAYER_CLASSES) {
                rankPlayerVsAll(playerType, mobs, charLvl, trials);
            }
            long elapsed = System.currentTimeMillis() - t0;
            System.out.println("  (level " + charLvl + " run took "
                    + (elapsed / 1000) + "s)");
        }
        FIGHT_LOG.flush();
        FIGHT_LOG.close();
        printWinLossSummary();
        printActionBreakdown();
    }

    /** Overall fight totals per (class x level): wins / losses / draws and
     *  win-percentage (counting draws as half wins, matching the rank table). */
    private static void printWinLossSummary() {
        System.out.println();
        System.out.println("==== win / loss / draw totals (class x level) ====");
        System.out.printf("%-15s %5s %6s %6s %6s %6s %7s%n",
                "class", "lvl", "fights", "wins", "losses", "draws", "win%");
        for (int charLvl : LEVELS) {
            for (String playerType : PLAYER_CLASSES) {
                long w = countOutcome(playerType, charLvl, "win");
                long l = countOutcome(playerType, charLvl, "loss");
                long d = countOutcome(playerType, charLvl, "draw");
                long total = w + l + d;
                if (total == 0) continue;
                double winPct = (w + 0.5 * d) / total * 100.0;
                System.out.printf("%-15s %5d %6d %6d %6d %6d %6.1f%%%n",
                        playerType, charLvl, total, w, l, d, winPct);
            }
        }
    }

    private static long countOutcome(String cls, int lvl, String outcome) {
        Tally t = TALLIES.get(cls + "|" + lvl + "|" + outcome);
        return t == null ? 0 : t.fights;
    }

    /** Per (class x level x outcome) row of mean melee / wand / bomb action
     *  counts. "Draw" rows show too - they cover stalemates and mutual wipes. */
    private static void printActionBreakdown() {
        System.out.println();
        System.out.println("==== average actions per fight (class x level x outcome) ====");
        System.out.printf("%-15s %5s %-5s %7s %6s %6s %6s %6s %6s %6s %6s%n",
                "class", "lvl", "outc.", "fights",
                "melee", "wand", "bomb", "potion", "tool", "eat", "throw");
        for (int charLvl : LEVELS) {
            for (String playerType : PLAYER_CLASSES) {
                for (String outcome : new String[] { "win", "loss", "draw" }) {
                    Tally t = TALLIES.get(playerType + "|" + charLvl + "|" + outcome);
                    if (t == null || t.fights == 0) continue;
                    double n = t.fights;
                    System.out.printf("%-15s %5d %-5s %7d %6.2f %6.2f %6.2f %6.2f %6.2f %6.2f %6.2f%n",
                            playerType, charLvl, outcome, t.fights,
                            t.melee / n, t.wand / n, t.bomb / n,
                            t.potion / n, t.tool / n, t.eat / n, t.thrw / n);
                }
            }
        }
    }

    /** Non-player mobs that can fight (have damage or ranged damage). */
    private static List<String> pickMobs() {
        List<String> out = new ArrayList<>();
        for (String type : Registries.mobTypes()) {
            if (type.startsWith("PLAYER_")) continue;
            com.bjsp123.rl2.logic.MobDefinition def = Registries.mob(type);
            if (def == null) continue;
            if (def.maxHp <= 0) continue;
            boolean canHit = def.damage > 0 || def.rangedDamage > 0;
            if (!canHit) continue;
            out.add(type);
        }
        out.sort(String::compareTo);
        return out;
    }

    private static void rankPlayerVsAll(String playerType, List<String> mobs,
                                        int charLvl, int trials) {
        long seed = 0xA15Eb00BBeefcafeL ^ charLvl ^ playerType.hashCode();
        double[] winRates = new double[mobs.size()];
        for (int i = 0; i < mobs.size(); i++) {
            double playerWins = 0;
            for (int t = 0; t < trials; t++) {
                int outcome = simulate1vN(playerType, mobs.get(i), charLvl,
                        seed + (long) i * 1_000_003L + t, t);
                if (outcome == +1) playerWins += 1;
                else if (outcome == 0) playerWins += 0.5;
            }
            winRates[i] = playerWins / Math.max(1, trials);
        }

        // Print sorted by win-rate descending (easiest opponents first).
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < mobs.size(); i++) order.add(i);
        order.sort(Comparator.<Integer, Double>comparing(i -> -winRates[i]));

        System.out.println();
        System.out.println("==== " + playerType + " vs " + MOBS_PER_TEAM
                + "x mob @ character level " + charLvl + " ====");
        System.out.println("rank mob                            win%");
        for (int rank = 0; rank < order.size(); rank++) {
            int i = order.get(rank);
            System.out.printf("%4d %-29s %5.1f%%%n",
                    rank + 1, mobs.get(i), winRates[i] * 100.0);
        }
    }

    /** Simulate one fight: 1 player vs {@code MOBS_PER_TEAM} instances of
     *  {@code mobType}. Returns +1 if player wins, -1 if mobs win, 0 stalemate. */
    private static int simulate1vN(String playerType, String mobType,
                                   int charLvl, long seed, int trial) {
        Random rng = new Random(seed);
        // Diagnostic: clear the rolling log before each Mage fight so we can
        // dump just this fight's events at the end. Other classes skip the
        // clear so we don't add overhead to runs that don't need it.
        boolean diag = "PLAYER_MAGE".equals(playerType);
        if (diag) com.bjsp123.rl2.logic.EventLog.clear();
        Level level = CombatArena.buildArenaLevel(ARENA_W, ARENA_H, rng);
        World world = new World();
        world.unique = new UniqueTracker();
        level.world = world;
        world.levels = new Level[] { level };

        Mob player = buildPlayer(playerType);
        if (player == null) return 0;
        MobProgression.setSpawnLevel(player, charLvl);
        com.bjsp123.rl2.util.PlayerGearProvider.InventoryKit kit =
                GEAR.kitForCharLvl(charLvl);
        if (diag) {
            System.err.println("[KIT] charLvl=" + charLvl + " depth="
                    + GEAR.depthForCharLvl(charLvl)
                    + " weapon=" + (kit.weapon == null ? "null" : kit.weapon.type)
                    + " armor=" + (kit.armor == null ? "null" : kit.armor.type)
                    + " bag.size=" + kit.bag.size());
            int show = Math.min(20, kit.bag.size());
            for (int i = 0; i < show; i++) {
                com.bjsp123.rl2.model.Item it = kit.bag.get(i);
                System.err.println("  bag[" + i + "]=" + (it == null ? "null" : it.type)
                        + "+" + it.level);
            }
        }
        GEAR.applyKit(player, kit);
        MobProgression.autoLevelUpPerks(player, rng);
        stripFromInventory(player, "TELEPORT_ORB");
        if (STRIP_JADE_BULL) stripFromInventory(player, "JADE_BULL");

        List<Mob> mobs = new ArrayList<>(MOBS_PER_TEAM);
        for (int i = 0; i < MOBS_PER_TEAM; i++) {
            Mob m = MobFactory.spawn(mobType, new Point(0, 0));
            if (m == null) return 0;
            MobProgression.setSpawnLevel(m, charLvl);
            stripFromInventory(m, "TELEPORT_ORB");
            if (m.behavior == Mob.Behavior.PLAYER) m.behavior = Mob.Behavior.MOB;
            m.stateOfMind = Mob.StateOfMind.AWAKE;
            mobs.add(m);
        }
        if (player.behavior == Mob.Behavior.PLAYER) player.behavior = Mob.Behavior.MOB;
        player.stateOfMind = Mob.StateOfMind.AWAKE;

        // Place player on the left edge, mobs clustered on the right.
        Point playerPos = new Point(2, ARENA_H / 2);
        List<Point> spots = new ArrayList<>(1 + MOBS_PER_TEAM);
        List<Mob> placement = new ArrayList<>(1 + MOBS_PER_TEAM);
        spots.add(playerPos);
        placement.add(player);
        int cx = ARENA_W - 3, cy = ARENA_H / 2;
        // 3 mobs: centre + two neighbours (small cluster).
        spots.add(new Point(cx, cy));
        spots.add(new Point(cx, cy - 1));
        spots.add(new Point(cx, cy + 1));
        placement.addAll(mobs);
        CombatArena.placeMobs(level, placement, spots);
        CombatArena.seedTeamHostility(List.of(player), mobs);
        if (diag) {
            StringBuilder sb = new StringBuilder();
            sb.append("[INV] PLAYER_MAGE vs ").append(mobType).append(" bag=[");
            if (player.inventory != null && player.inventory.bag != null) {
                for (int i = 0; i < player.inventory.bag.size(); i++) {
                    com.bjsp123.rl2.model.Item it = player.inventory.bag.get(i);
                    if (i > 0) sb.append(", ");
                    sb.append(it == null ? "null" : it.type).append("+").append(it.level);
                    if (it.baseChargeMax > 0) sb.append("(c=").append((int)it.charge).append(")");
                }
            }
            sb.append("] weapon=").append(player.inventory.weapon == null
                    ? "null" : player.inventory.weapon.type);
            System.err.println(sb);
        }

        com.bjsp123.rl2.util.ActionTracker.reset();
        com.bjsp123.rl2.util.ActionTracker.enable();
        try {
            int maxTicks = MAX_STANDARD_TURNS
                    * com.bjsp123.rl2.logic.TurnSystem.STANDARD_TURN_TICKS;
            String outcome = "draw";
            int result = 0;
            int ticksElapsed = maxTicks;
            outer:
            for (int t = 0; t < maxTicks; t++) {
                CombatArena.tickHeadless(level, world, 16);
                if (level.events != null) level.events.clear();
                boolean playerDead = player.hp <= 0 || !level.mobs.contains(player);
                boolean anyMobAlive = false;
                for (Mob m : mobs) {
                    if (m.hp > 0 && level.mobs.contains(m)) {
                        anyMobAlive = true; break;
                    }
                }
                if (playerDead && !anyMobAlive)  { outcome = "draw"; result =  0; ticksElapsed = t; break; }
                if (playerDead)                  { outcome = "loss"; result = -1; ticksElapsed = t; break; }
                if (!anyMobAlive)                { outcome = "win";  result = +1; ticksElapsed = t; break; }
                if (!CombatArena.hostilePairExists(level)) { outcome = "draw"; ticksElapsed = t; break outer; }
            }
            int[] counts = com.bjsp123.rl2.util.ActionTracker.read(player);
            Tally tly = tally(playerType, charLvl, outcome);
            tly.fights++;
            tly.melee += counts[com.bjsp123.rl2.util.ActionTracker.MELEE];
            tly.wand  += counts[com.bjsp123.rl2.util.ActionTracker.WAND];
            tly.bomb  += counts[com.bjsp123.rl2.util.ActionTracker.BOMB];
            tly.potion += counts[com.bjsp123.rl2.util.ActionTracker.POTION];
            tly.tool   += counts[com.bjsp123.rl2.util.ActionTracker.TOOL];
            tly.eat    += counts[com.bjsp123.rl2.util.ActionTracker.EAT];
            tly.thrw   += counts[com.bjsp123.rl2.util.ActionTracker.THROW];
            // Per-fight CSV row for downstream slicing.
            int mobsAlive = 0;
            for (Mob m : mobs) if (m.hp > 0 && level.mobs.contains(m)) mobsAlive++;
            double playerMaxHp = player.effectiveStats().maxHp;
            double playerHp = Math.max(0, player.hp);
            int turns = ticksElapsed / com.bjsp123.rl2.logic.TurnSystem.STANDARD_TURN_TICKS;
            if (FIGHT_LOG != null) {
                FIGHT_LOG.printf("%s,%d,%s,%d,%s,%d,%d,%d,%d,%d,%d,%d,%d,%.2f,%.2f,%d,%d%n",
                        playerType, charLvl, mobType, trial, outcome, turns,
                        counts[com.bjsp123.rl2.util.ActionTracker.MELEE],
                        counts[com.bjsp123.rl2.util.ActionTracker.WAND],
                        counts[com.bjsp123.rl2.util.ActionTracker.BOMB],
                        counts[com.bjsp123.rl2.util.ActionTracker.POTION],
                        counts[com.bjsp123.rl2.util.ActionTracker.TOOL],
                        counts[com.bjsp123.rl2.util.ActionTracker.EAT],
                        counts[com.bjsp123.rl2.util.ActionTracker.THROW],
                        playerHp, playerMaxHp, mobsAlive, MOBS_PER_TEAM);
            }
            // Diagnostic: when a single fight burns through 50+ wand fires
            // without killing the opposing team, log the matchup, mob hp, and
            // a damage-roll tail so we can see why the wand isn't landing.
            if (counts[com.bjsp123.rl2.util.ActionTracker.WAND] >= 50) {
                StringBuilder sb = new StringBuilder();
                sb.append("[BIGWAND] ").append(playerType)
                        .append(" L").append(charLvl)
                        .append(" vs 3x ").append(mobType)
                        .append(" -> ").append(outcome)
                        .append(" wand=").append(counts[com.bjsp123.rl2.util.ActionTracker.WAND])
                        .append(" melee=").append(counts[com.bjsp123.rl2.util.ActionTracker.MELEE])
                        .append(" bomb=").append(counts[com.bjsp123.rl2.util.ActionTracker.BOMB]);
                for (Mob m : mobs) {
                    sb.append(" | ").append(m.mobType).append(" hp=").append(m.hp)
                            .append("/").append(m.effectiveStats().maxHp);
                }
                System.err.println(sb);
            }
            dumpDiag(diag, mobType, outcome);
            return result;
        } finally {
            com.bjsp123.rl2.util.ActionTracker.disable();
        }
    }

    private static void dumpDiag(boolean enabled, String opponent, String outcome) {
        if (!enabled) return;
        System.err.println("[FIGHT] PLAYER_MAGE vs 3x " + opponent + " -> " + outcome);
        for (com.bjsp123.rl2.model.LogEvent e : com.bjsp123.rl2.logic.EventLog.all()) {
            if (e == null || e.text == null) continue;
            System.err.println("  " + e.text);
        }
    }

    private static Mob buildPlayer(String type) {
        if (!type.startsWith("PLAYER_")) return null;
        String classKey = type.substring("PLAYER_".length());
        Mob.CharacterClass cls = Mob.CharacterClass.valueOf(classKey);
        return MobFactory.player(new Point(0, 0), cls);
    }

    private static void stripFromInventory(Mob m, String typeKey) {
        if (m == null || m.inventory == null || m.inventory.bag == null) return;
        m.inventory.bag.removeIf(it -> it != null && typeKey.equals(it.type));
    }

    private static Path locateAssetsDir() {
        Path cwd = Paths.get("").toAbsolutePath();
        for (Path p = cwd; p != null; p = p.getParent()) {
            Path candidate = p.resolve("assets").resolve("data");
            if (Files.isDirectory(candidate)) return candidate;
        }
        throw new IllegalStateException(
                "Could not find assets/data starting from " + cwd);
    }

    private static void loadData(Path assets) throws IOException {
        Path strings = assets.resolve("strings.csv");
        if (Files.exists(strings))
            com.bjsp123.rl2.logic.TextCatalog.load(Files.readString(strings));
        Path config = assets.resolve("config.csv");
        if (Files.exists(config))
            GameBalance.load(Files.readString(config));
        Registries.loadMobs(Files.readString(assets.resolve("mobs.csv")));
        Registries.loadItems(Files.readString(assets.resolve("items.csv")));
        Path themed = assets.resolve("themedrooms.csv");
        if (Files.exists(themed))
            Registries.loadThemedRooms(Files.readString(themed));
    }
}
