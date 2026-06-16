package com.bjsp123.rl2.ai.util;

import com.bjsp123.rl2.ai.RaiBootstrap;
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
import com.bjsp123.rl2.util.PlayerGearProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * [DEV / DIAGNOSTIC] Arena ranker that pits each player class against the full NPC
 * field at character levels 1 and 10, run twice per pair: once with the player
 * mob converted to {@link Mob.Behavior#MOB} (the baseline used by
 * {@link com.bjsp123.rl2.util.FullArenaRankMain}) and once with the player mob
 * converted to {@link Mob.Behavior#SMART} so the new {@link com.bjsp123.rl2.ai.SmartAi}
 * brain drives them. Reports the delta in win rate per class so we can see what
 * the rules-based planner is worth.
 *
 * <p>Gradle: {@code ./gradlew :rai:rankSmartArena --args="5"} (trials per pair).
 */
public final class SmartArenaRankMain {

    private SmartArenaRankMain() {}

    private static final int[] LEVELS = {1, 10};
    private static final int MAX_STANDARD_TURNS = 200;
    private static final int ARENA_W = 14;
    private static final int ARENA_H = 14;
    private static final int DEFAULT_TRIALS = 5;
    private static final String[] PLAYER_FIGHTERS = {"PLAYER_WARRIOR", "PLAYER_ROGUE", "PLAYER_MAGE"};

    private static PlayerGearProvider GEAR;

    public static void main(String[] args) throws IOException {
        // Spec: "TRIALS" or "TRIALSxCLUSTER" (also accepts a comma) - a single
        // token so it survives shells that won't pass a space inside --args.
        // CLUSTER = opponents per fight (1 = classic 1v1; 3 = a 1-vs-3 pack,
        // mirroring rank1vN but with the SMART brain).
        int trials = DEFAULT_TRIALS, cluster = 1;
        if (args.length > 0 && !args[0].isEmpty()) {
            String[] parts = args[0].split("[x,]");
            trials = Integer.parseInt(parts[0].trim());
            if (parts.length > 1) cluster = Math.max(1, Integer.parseInt(parts[1].trim()));
        }
        Path assets = locateAssetsDir();
        loadData(assets);
        RaiBootstrap.init();
        GEAR = new PlayerGearProvider(0xC0FFEEL);

        List<String> opponents = pickOpponents();
        System.out.println("[smart-arena] player classes: 3, opponents: " + opponents.size()
                + ", trials per pair: " + trials + ", opponents per fight: " + cluster);

        // One-time inventory dump so we can see what each class actually carries
        // at lvl 1 and lvl 10 - quick sanity check that the gear-world picker is
        // actually upgrading wands / bombs / weapons past the CSV starter kit.
        for (int lvl : LEVELS) {
            for (String pt : PLAYER_FIGHTERS) {
                Mob.CharacterClass cls = Mob.CharacterClass.valueOf(pt.substring("PLAYER_".length()));
                Mob inspect = MobFactory.player(new Point(0, 0), cls);
                MobProgression.setSpawnLevel(inspect, lvl);
                GEAR.applyKit(inspect, GEAR.kitForCharLvl(lvl));
                System.out.println("[KIT] " + pt + " @ lvl " + lvl + " ----");
                if (inspect.inventory.weapon != null)
                    System.out.println("  weapon: " + inspect.inventory.weapon.type + " +" + inspect.inventory.weapon.level
                            + " dmg=" + inspect.inventory.weapon.damage);
                if (inspect.inventory.armor != null)
                    System.out.println("  armor: " + inspect.inventory.armor.type + " +" + inspect.inventory.armor.level);
                for (com.bjsp123.rl2.model.Item it : inspect.inventory.bag) {
                    if (it == null) continue;
                    System.out.println("  bag: " + it.type + " +" + it.level
                            + " cat=" + it.inventoryCategory + " ub=" + it.useBehavior
                            + " dmg=" + it.damage + " charges=" + (int)it.charge + "/" + it.maxCharge());
                }
            }
        }

        Path csvOut = Paths.get("results", "smart_arena.csv").toAbsolutePath();
        Files.createDirectories(csvOut.getParent());
        try (java.io.PrintWriter csv = new java.io.PrintWriter(Files.newBufferedWriter(csvOut))) {
            csv.println("player,char_level,brain,opponent,trial,outcome,turns,player_hp,opp_hp");
            for (int lvl : LEVELS) {
                long t0 = System.currentTimeMillis();
                runMatrix(opponents, lvl, trials, cluster, csv);
                long elapsed = System.currentTimeMillis() - t0;
                System.out.println("  (level " + lvl + " run took " + (elapsed / 1000) + "s)");
            }
        }
        System.out.println("[smart-arena] per-fight CSV: " + csvOut);
    }

    /** Every fightable mob type, excluding the player classes themselves. */
    private static List<String> pickOpponents() {
        List<String> out = new ArrayList<>();
        for (String type : Registries.mobTypes()) {
            com.bjsp123.rl2.logic.MobDefinition def = Registries.mob(type);
            if (def == null) continue;
            if (def.maxHp <= 0) continue;
            boolean canHit = def.damage > 0 || def.rangedDamage > 0;
            if (!canHit) continue;
            if (type.startsWith("PLAYER_")) continue;
            out.add(type);
        }
        out.sort(String::compareTo);
        return out;
    }

    private static void runMatrix(List<String> opponents, int charLvl, int trials,
                                  int cluster, java.io.PrintWriter csv) {
        long baseSeed = 0xA15Eb00BBeefcafeL ^ charLvl;
        // For each player class + opponent + brain mode, run `trials` fights.
        // Tally wins/draws/losses and dump per-fight rows for downstream slicing.
        for (String playerType : PLAYER_FIGHTERS) {
            for (Mob.Behavior brain : new Mob.Behavior[]{Mob.Behavior.MOB, Mob.Behavior.SMART}) {
                int totalFights = 0, wins = 0, losses = 0, draws = 0;
                long brainOffset = brain == Mob.Behavior.SMART ? 0xDEADBEEFL : 0;
                if (brain == Mob.Behavior.SMART) com.bjsp123.rl2.ai.SmartAi.resetDecisionCounters();
                for (int oi = 0; oi < opponents.size(); oi++) {
                    String opp = opponents.get(oi);
                    for (int t = 0; t < trials; t++) {
                        int outcome = simulateMatch(playerType, opp, charLvl, brain,
                                baseSeed + (long) oi * 1009L + t + brainOffset, t, cluster, csv);
                        if      (outcome == +1) { wins++; }
                        else if (outcome == -1) { losses++; }
                        else                    { draws++; }
                        totalFights++;
                    }
                }
                double winPct = (wins + 0.5 * draws) / Math.max(1, totalFights) * 100.0;
                System.out.printf("[%s | lvl %2d | %-5s] %5d fights  %4d-%4d-%4d (W-L-D)  %5.1f%%%n",
                        playerType, charLvl, brain.name(), totalFights, wins, losses, draws, winPct);
                if (brain == Mob.Behavior.SMART) {
                    var counts = com.bjsp123.rl2.ai.SmartAi.decisionCounters();
                    long total = counts.values().stream().mapToLong(Long::longValue).sum();
                    if (total > 0) {
                        var top = counts.entrySet().stream()
                                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                                .limit(8)
                                .toList();
                        StringBuilder sb = new StringBuilder("    decisions: ");
                        for (var e : top) {
                            sb.append(String.format("%s=%.0f%%(%d) ",
                                    e.getKey(), 100.0 * e.getValue() / total, e.getValue()));
                        }
                        System.out.println(sb.toString().trim());
                    }
                }
            }
        }
    }

    private static int simulateMatch(String playerType, String oppType, int charLvl,
                                     Mob.Behavior playerBrain, long seed, int trial,
                                     int cluster, java.io.PrintWriter csv) {
        Random rng = new Random(seed);
        Level level = CombatArena.buildArenaLevel(ARENA_W, ARENA_H, rng);
        World world = new World();
        world.unique = new UniqueTracker();
        level.world = world;
        world.levels = new Level[] { level };

        Mob a = buildFighter(playerType);
        if (a == null) return 0;
        MobProgression.setSpawnLevel(a, charLvl);
        GEAR.applyKit(a, GEAR.kitForCharLvl(charLvl));
        MobProgression.autoLevelUpPerks(a, rng);
        stripFromInventory(a, "TELEPORT_ORB");
        // Flip player from PLAYER (turn-loop stall) to either MOB (baseline) or SMART.
        a.behavior = playerBrain;
        a.stateOfMind = Mob.StateOfMind.AWAKE;

        // Spawn `cluster` opponents stacked vertically around the far side.
        List<Mob> foes = new ArrayList<>();
        List<Point> positions = new ArrayList<>();
        Point aPos = new Point(2, ARENA_H / 2);
        positions.add(aPos);
        List<Mob> all = new ArrayList<>();
        all.add(a);
        for (int k = 0; k < cluster; k++) {
            Mob b = buildFighter(oppType);
            if (b == null) continue;
            MobProgression.setSpawnLevel(b, charLvl);
            b.stateOfMind = Mob.StateOfMind.AWAKE;
            int dy = (k % 2 == 0 ? 1 : -1) * ((k + 1) / 2);
            int y = Math.max(1, Math.min(ARENA_H - 2, ARENA_H / 2 + dy));
            foes.add(b);
            all.add(b);
            positions.add(new Point(ARENA_W - 3, y));
        }
        if (foes.isEmpty()) return 0;
        CombatArena.placeMobs(level, all, positions);
        CombatArena.seedTeamHostility(List.of(a), foes);

        int maxTicks = MAX_STANDARD_TURNS *
                com.bjsp123.rl2.logic.TurnSystem.STANDARD_TURN_TICKS;
        int result = 0;
        int ticksElapsed = maxTicks;
        for (int t = 0; t < maxTicks; t++) {
            CombatArena.tickHeadless(level, world, 16);
            if (level.events != null) level.events.clear();
            boolean aDead = a.hp <= 0 || !level.mobs.contains(a);
            boolean foesDead = foes.stream().allMatch(f -> f.hp <= 0 || !level.mobs.contains(f));
            if (aDead && foesDead) { result =  0; ticksElapsed = t; break; }
            if (aDead)             { result = -1; ticksElapsed = t; break; }
            if (foesDead)          { result = +1; ticksElapsed = t; break; }
            if (!CombatArena.hostilePairExists(level)) { ticksElapsed = t; break; }
        }
        String outcome = result == +1 ? "win" : result == -1 ? "loss" : "draw";
        int turns = ticksElapsed / com.bjsp123.rl2.logic.TurnSystem.STANDARD_TURN_TICKS;
        if (csv != null) {
            double foeHp = foes.stream().mapToDouble(f -> Math.max(0, f.hp)).sum();
            csv.printf("%s,%d,%s,%s,%d,%s,%d,%.2f,%.2f%n",
                    playerType, charLvl, playerBrain.name(), oppType, trial, outcome, turns,
                    Math.max(0, a.hp), foeHp);
        }
        return result;
    }

    private static void stripFromInventory(Mob m, String typeKey) {
        if (m == null || m.inventory == null || m.inventory.bag == null) return;
        m.inventory.bag.removeIf(it -> it != null && typeKey.equals(it.type));
    }

    private static Mob buildFighter(String type) {
        if (type.startsWith("PLAYER_")) {
            Mob.CharacterClass cls = Mob.CharacterClass.valueOf(type.substring("PLAYER_".length()));
            return MobFactory.player(new Point(0, 0), cls);
        }
        return MobFactory.spawn(type, new Point(0, 0));
    }

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
        if (Files.exists(strings)) com.bjsp123.rl2.logic.TextCatalog.load(Files.readString(strings));
        Path config = assets.resolve("config.csv");
        if (Files.exists(config)) GameBalance.load(Files.readString(config));
        Registries.loadMobs(Files.readString(assets.resolve("mobs.csv")));
        Registries.loadItems(Files.readString(assets.resolve("items.csv")));
        Path themed = assets.resolve("themedrooms.csv");
        if (Files.exists(themed)) Registries.loadThemedRooms(Files.readString(themed));
    }
}
