package com.bjsp123.rl2.util;

import com.bjsp123.rl2.event.GameEvent;
import com.bjsp123.rl2.logic.CombatArena;
import com.bjsp123.rl2.logic.GameBalance;
import com.bjsp123.rl2.logic.ItemFactory;
import com.bjsp123.rl2.logic.MobFactory;
import com.bjsp123.rl2.logic.MobProgression;
import com.bjsp123.rl2.logic.Registries;
import com.bjsp123.rl2.logic.TurnSystem;
import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.UniqueTracker;
import com.bjsp123.rl2.model.World;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * [DEV / DIAGNOSTIC] Bomb x warrior-loadout arena comparison.
 *
 * <p>For every (warriorLevel, weapon, bomb) combination, spawn a warrior with
 * exactly that gear (10 copies of the bomb, the named weapon, SCALE_MAIL,
 * nothing else throwable), pit them against {@code MOBS_PER_TEAM} of every
 * non-player mob in the bestiary, and log per-fight + aggregated metrics so
 * we can see which bomb actually carries which loadout.
 *
 * <p>Gradle: {@code ./gradlew :rlib:bombLoadoutArena --args="5"} (trials per
 * pair). Outputs:
 * <ul>
 *   <li>{@code bomb_loadout_arena.csv} - per-fight row
 *   <li>{@code bomb_loadout_summary.csv} - aggregated by (level, weapon,
 *       bomb): win%, avg turns, avg bombs thrown, hit / dud rate
 * </ul>
 */
public final class BombLoadoutArenaMain {

    private BombLoadoutArenaMain() {}

    private static final int[] WARRIOR_LEVELS = {1, 5, 10};
    private static final String[] WEAPONS = {"SWORD", "KATANA", "BLOODYBLADE"};
    private static final int BOMB_STOCK = 10;
    private static final int MOBS_PER_TEAM = 3;
    private static final int ARENA_W = 18;
    private static final int ARENA_H = 18;
    private static final int MAX_STANDARD_TURNS = 200;
    private static final int DEFAULT_TRIALS = 5;

    private static PrintWriter FIGHT_LOG;

    static final class Agg {
        long fights, wins, losses, draws;
        long totalTurns;
        long totalBombsThrown, totalBombsHit, totalBombDmg, totalBombDuds;
        long totalMeleeSwings;
        double totalHpPct;
    }

    private static final Map<String, Agg> AGG = new LinkedHashMap<>();

    public static void main(String[] args) throws IOException {
        int trials = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_TRIALS;
        Path assets = locateAssetsDir();
        loadData(assets);

        List<String> bombs = Registries.itemTypesMatching(
                d -> d.inventoryCategory == Item.InventoryCategory.BOMB
                        && d.damage > 0
                        && d.throwEffect != Item.ItemEffect.TELEPORT
                        && d.throwEffect != Item.ItemEffect.CAPTURE);
        bombs.sort(String::compareTo);

        List<String> opponents = pickOpponents();

        Path fightCsv = Paths.get("results", "bomb_loadout_arena.csv").toAbsolutePath();
        Files.createDirectories(fightCsv.getParent());
        FIGHT_LOG = new PrintWriter(Files.newBufferedWriter(fightCsv));
        FIGHT_LOG.println("warrior_level,weapon,bomb,opponent,trial,outcome,turns,"
                + "bombs_thrown,bombs_hit,bomb_damage_total,"
                + "melee_swings,warrior_hp_remaining,warrior_max_hp,"
                + "opponents_alive,opponents_total");

        System.out.println("[bomb-loadout-arena]");
        System.out.println("  warrior levels: " + java.util.Arrays.toString(WARRIOR_LEVELS));
        System.out.println("  weapons:        " + java.util.Arrays.toString(WEAPONS));
        System.out.println("  bombs:          " + bombs);
        System.out.println("  opponents:      " + opponents.size());
        System.out.println("  trials/pair:    " + trials);
        System.out.println("  total fights:   "
                + WARRIOR_LEVELS.length * WEAPONS.length * bombs.size() * opponents.size() * trials);
        System.out.println("  per-fight CSV:  " + fightCsv);

        long t0 = System.currentTimeMillis();
        for (int charLvl : WARRIOR_LEVELS) {
            for (String weapon : WEAPONS) {
                for (String bomb : bombs) {
                    String aggKey = charLvl + "|" + weapon + "|" + bomb;
                    Agg a = new Agg();
                    AGG.put(aggKey, a);
                    for (String opponent : opponents) {
                        for (int t = 0; t < trials; t++) {
                            long seed = mixSeed(charLvl, weapon, bomb, opponent, t);
                            runOne(charLvl, weapon, bomb, opponent, t, seed, a);
                        }
                    }
                }
            }
            System.out.println("  (level " + charLvl + " done; elapsed "
                    + ((System.currentTimeMillis() - t0) / 1000) + "s)");
        }
        FIGHT_LOG.flush();
        FIGHT_LOG.close();

        writeSummary();
        printSummaryHighlights();
    }

    private static long mixSeed(int lvl, String w, String b, String o, int trial) {
        return 0xD3CAFE17BABEL
                ^ ((long) lvl * 0x100000001b3L)
                ^ ((long) w.hashCode() * 0x9E3779B97F4A7C15L)
                ^ ((long) b.hashCode() * 0xBF58476D1CE4E5B9L)
                ^ ((long) o.hashCode() * 0x94D049BB133111EBL)
                ^ trial;
    }

    /** Run one fight; bump aggregate + write per-fight CSV row. */
    private static void runOne(int charLvl, String weapon, String bomb,
                               String opponentType, int trial, long seed, Agg agg) {
        Random rng = new Random(seed);
        Level level = CombatArena.buildArenaLevel(ARENA_W, ARENA_H, rng);
        World world = new World();
        world.unique = new UniqueTracker();
        level.world = world;
        world.levels = new Level[] { level };

        Mob warrior = MobFactory.player(new Point(0, 0), Mob.CharacterClass.WARRIOR);
        if (warrior == null) return;
        MobProgression.setSpawnLevel(warrior, charLvl);
        equipLoadout(warrior, weapon, bomb);
        MobProgression.autoLevelUpPerks(warrior, rng);
        if (warrior.behavior == Mob.Behavior.PLAYER) warrior.behavior = Mob.Behavior.MOB;
        warrior.stateOfMind = Mob.StateOfMind.AWAKE;

        List<Mob> opponents = new ArrayList<>(MOBS_PER_TEAM);
        for (int i = 0; i < MOBS_PER_TEAM; i++) {
            Mob m = MobFactory.spawn(opponentType, new Point(0, 0));
            if (m == null) return;
            MobProgression.setSpawnLevel(m, charLvl);
            stripFromInventory(m, "TELEPORT_ORB");
            if (m.behavior == Mob.Behavior.PLAYER) m.behavior = Mob.Behavior.MOB;
            m.stateOfMind = Mob.StateOfMind.AWAKE;
            opponents.add(m);
        }

        List<Point> spots = new ArrayList<>();
        List<Mob> placement = new ArrayList<>();
        spots.add(new Point(2, ARENA_H / 2));
        placement.add(warrior);
        int cx = ARENA_W - 3, cy = ARENA_H / 2;
        spots.add(new Point(cx, cy));
        spots.add(new Point(cx, cy - 1));
        spots.add(new Point(cx, cy + 1));
        placement.addAll(opponents);
        CombatArena.placeMobs(level, placement, spots);
        CombatArena.seedTeamHostility(List.of(warrior), opponents);

        ActionTracker.reset();
        ActionTracker.enable();
        try {
            int maxTicks = MAX_STANDARD_TURNS * TurnSystem.STANDARD_TURN_TICKS;
            String outcome = "draw";
            int ticksElapsed = maxTicks;
            // Per-fight event accounting: attribute DamageDealt by warrior to
            // the most recent offensive action so we can split bomb-damage out
            // of total damage dealt.
            BombStats bs = new BombStats();
            for (int t = 0; t < maxTicks; t++) {
                CombatArena.tickHeadless(level, world, 16);
                if (level.events != null) {
                    drainEventsForBombStats(level.events, warrior, bs);
                    level.events.clear();
                }
                boolean warriorDead = warrior.hp <= 0 || !level.mobs.contains(warrior);
                boolean anyOppAlive = false;
                for (Mob m : opponents) {
                    if (m.hp > 0 && level.mobs.contains(m)) { anyOppAlive = true; break; }
                }
                if (warriorDead && !anyOppAlive) { outcome = "draw"; ticksElapsed = t; break; }
                if (warriorDead)                 { outcome = "loss"; ticksElapsed = t; break; }
                if (!anyOppAlive)                { outcome = "win";  ticksElapsed = t; break; }
                if (!CombatArena.hostilePairExists(level)) { outcome = "draw"; ticksElapsed = t; break; }
            }

            int[] counts = ActionTracker.read(warrior);
            int bombsThrown = counts[ActionTracker.BOMB];
            int meleeSwings = counts[ActionTracker.MELEE];
            int oppAlive = 0;
            for (Mob m : opponents) if (m.hp > 0 && level.mobs.contains(m)) oppAlive++;
            double maxHp = warrior.effectiveStats().maxHp;
            double hp = Math.max(0, warrior.hp);
            int turns = ticksElapsed / TurnSystem.STANDARD_TURN_TICKS;

            // A "bomb dud" = a throw that produced no DamageDealt event with
            // attacker == warrior in its wake. bs.bombThrowsWithHit counts
            // the throws that produced at least one DamageDealt event.
            int bombDuds = Math.max(0, bombsThrown - bs.bombThrowsWithHit);

            FIGHT_LOG.printf("%d,%s,%s,%s,%d,%s,%d,%d,%d,%d,%d,%.2f,%.2f,%d,%d%n",
                    charLvl, weapon, bomb, opponentType, trial, outcome, turns,
                    bombsThrown, bs.bombDamageEvents, bs.bombDamageTotal,
                    meleeSwings, hp, maxHp, oppAlive, MOBS_PER_TEAM);

            agg.fights++;
            switch (outcome) {
                case "win"  -> agg.wins++;
                case "loss" -> agg.losses++;
                default     -> agg.draws++;
            }
            agg.totalTurns += turns;
            agg.totalBombsThrown += bombsThrown;
            agg.totalBombsHit += bs.bombDamageEvents;
            agg.totalBombDmg += bs.bombDamageTotal;
            agg.totalBombDuds += bombDuds;
            agg.totalMeleeSwings += meleeSwings;
            agg.totalHpPct += maxHp > 0 ? hp / maxHp : 0.0;
        } finally {
            ActionTracker.disable();
        }
    }

    /** Per-fight accumulator. */
    private static final class BombStats {
        String lastOffensive = "-";
        int bombDamageEvents;       // count of DamageDealt events crediting bomb
        long bombDamageTotal;       // sum of those damages
        int bombThrowsWithHit;      // count of distinct throws that produced any DamageDealt
        boolean bombThrowHadHitThisCycle;
        int pendingThrows;          // 0 or 1 - tracks "did the last throw deal damage?"
    }

    /** Single-pass over level.events: attribute DamageDealt to whichever
     *  offensive event most recently fired by the warrior. */
    private static void drainEventsForBombStats(List<GameEvent> events, Mob warrior,
                                                BombStats bs) {
        for (GameEvent ev : events) {
            if (ev instanceof GameEvent.ItemThrown t) {
                if (t.thrower() == warrior && t.item() != null
                        && t.item().inventoryCategory == Item.InventoryCategory.BOMB) {
                    // Close out the previous throw's hit-cycle first.
                    if (bs.pendingThrows > 0 && bs.bombThrowHadHitThisCycle) {
                        bs.bombThrowsWithHit++;
                    }
                    bs.lastOffensive = "throw";
                    bs.bombThrowHadHitThisCycle = false;
                    bs.pendingThrows = 1;
                }
            } else if (ev instanceof GameEvent.MobMeleeAttacked m) {
                if (m.attacker() == warrior) {
                    // Close prior throw cycle before switching attribution.
                    if (bs.pendingThrows > 0 && bs.bombThrowHadHitThisCycle) {
                        bs.bombThrowsWithHit++;
                    }
                    bs.lastOffensive = "melee";
                    bs.pendingThrows = 0;
                }
            } else if (ev instanceof GameEvent.DamageDealt d) {
                if (d.source() == warrior && d.amount() > 0
                        && "throw".equals(bs.lastOffensive)) {
                    bs.bombDamageEvents++;
                    bs.bombDamageTotal += d.amount();
                    bs.bombThrowHadHitThisCycle = true;
                }
            }
        }
    }

    private static void equipLoadout(Mob warrior, String weaponType, String bombType) {
        // Strip everything from the bag.
        if (warrior.inventory != null && warrior.inventory.bag != null) {
            warrior.inventory.bag.clear();
        }
        // Equip the test weapon.
        Item weapon = ItemFactory.build(weaponType);
        warrior.inventory.weapon = weapon;
        // Constant armor for fairness.
        try {
            warrior.inventory.armor = ItemFactory.build("SCALE_MAIL");
        } catch (RuntimeException ignored) {
            // SCALE_MAIL missing -> leave whatever the factory gave; balanced
            // away by the level-progression armor scaling.
        }
        // 10 copies of the test bomb. Each bomb is a discrete Item because
        // bombs are stackable by `count`, but the AI throws them one per turn
        // and the dud-counting works either way.
        for (int i = 0; i < BOMB_STOCK; i++) {
            warrior.inventory.bag.add(ItemFactory.build(bombType));
        }
    }

    private static List<String> pickOpponents() {
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

    private static void stripFromInventory(Mob m, String typeKey) {
        if (m == null || m.inventory == null || m.inventory.bag == null) return;
        m.inventory.bag.removeIf(it -> it != null && typeKey.equals(it.type));
    }

    private static void writeSummary() throws IOException {
        Path p = Paths.get("results", "bomb_loadout_summary.csv").toAbsolutePath();
        Files.createDirectories(p.getParent());
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(p))) {
            w.println("warrior_level,weapon,bomb,total_fights,win_pct,draw_pct,loss_pct,"
                    + "avg_turns,avg_bombs_thrown,avg_bomb_dmg_per_throw,bomb_dud_pct,"
                    + "avg_melee_swings,avg_warrior_hp_pct");
            for (Map.Entry<String, Agg> e : AGG.entrySet()) {
                String[] k = e.getKey().split("\\|");
                Agg a = e.getValue();
                double n = Math.max(1, a.fights);
                double winPct = 100.0 * a.wins / n;
                double drawPct = 100.0 * a.draws / n;
                double lossPct = 100.0 * a.losses / n;
                double avgTurns = a.totalTurns / n;
                double avgBombs = a.totalBombsThrown / n;
                double avgBombDmgPerThrow = a.totalBombsThrown == 0
                        ? 0.0 : (double) a.totalBombDmg / a.totalBombsThrown;
                double dudPct = a.totalBombsThrown == 0
                        ? 0.0 : 100.0 * a.totalBombDuds / a.totalBombsThrown;
                double avgMelee = a.totalMeleeSwings / n;
                double avgHpPct = 100.0 * a.totalHpPct / n;
                w.printf("%s,%s,%s,%d,%.1f,%.1f,%.1f,%.1f,%.2f,%.2f,%.1f,%.2f,%.1f%n",
                        k[0], k[1], k[2],
                        a.fights, winPct, drawPct, lossPct,
                        avgTurns, avgBombs, avgBombDmgPerThrow, dudPct,
                        avgMelee, avgHpPct);
            }
        }
        System.out.println("[bomb-loadout-arena] summary CSV: " + p);
    }

    /** Print the per-(level,weapon) ranking of bombs by win-pct. */
    private static void printSummaryHighlights() {
        System.out.println();
        System.out.println("==== bomb ranking by win% (per warrior level x weapon) ====");
        for (int lvl : WARRIOR_LEVELS) {
            for (String w : WEAPONS) {
                List<Map.Entry<String, Agg>> rows = new ArrayList<>();
                for (Map.Entry<String, Agg> e : AGG.entrySet()) {
                    if (e.getKey().startsWith(lvl + "|" + w + "|")) rows.add(e);
                }
                rows.sort((x, y) -> Double.compare(winPct(y.getValue()), winPct(x.getValue())));
                System.out.printf("%n[L%d / %s] (%d fights per row)%n",
                        lvl, w, rows.isEmpty() ? 0 : (int) rows.get(0).getValue().fights);
                System.out.printf("  %-15s %6s %7s %6s %5s%n",
                        "bomb", "win%", "avg_t", "thrown", "dud%");
                for (Map.Entry<String, Agg> e : rows) {
                    Agg a = e.getValue();
                    if (a.fights == 0) continue;
                    String bomb = e.getKey().substring((lvl + "|" + w + "|").length());
                    double n = a.fights;
                    System.out.printf("  %-15s %5.1f%% %7.1f %6.2f %4.1f%%%n",
                            bomb, winPct(a), a.totalTurns / n, a.totalBombsThrown / n,
                            a.totalBombsThrown == 0 ? 0.0
                                    : 100.0 * a.totalBombDuds / a.totalBombsThrown);
                }
            }
        }
    }

    private static double winPct(Agg a) {
        return a.fights == 0 ? 0.0 : 100.0 * (a.wins + 0.5 * a.draws) / a.fights;
    }

    /* ---------- asset loading (matches Arena1vNRankMain) ---------- */

    private static Path locateAssetsDir() {
        Path cwd = Paths.get("").toAbsolutePath();
        for (Path p = cwd; p != null; p = p.getParent()) {
            Path candidate = p.resolve("assets").resolve("data");
            if (Files.isDirectory(candidate)) return candidate;
        }
        throw new IllegalStateException("Could not find assets/data starting from " + cwd);
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
