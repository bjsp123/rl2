package com.bjsp123.rl2.util;

import com.bjsp123.rl2.logic.GameBalance;
import com.bjsp123.rl2.logic.MobProgression;
import com.bjsp123.rl2.model.Mob;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * [DEV / DIAGNOSTIC] Headless 1v1 round-robin power ranker. Not shipping code.
 *
 * <p>For each requested character level it spawns every "fightable" mob type
 * (plus the three player classes), runs a shared-pool
 * {@link GameBalance#mobfight} between every pair, and prints a power
 * ranking sorted by mean win-rate against the field.
 *
 * <p>Gradle: {@code ./gradlew :rlib:rankPower --args="500"} (optional trial
 * count override; defaults to 500 per pair for a snappy run).
 *
 * <p>Output goes to stdout. No file artefact. No mutation to game data.
 */
public final class MobPowerRankMain {

    private MobPowerRankMain() {}

    /** Character levels to rank at. Each gets its own ranking table. */
    private static final int[] LEVELS = {1, 10};

    public static void main(String[] args) throws IOException {
        int trials = args.length > 0 ? Integer.parseInt(args[0]) : 500;
        Path assets = ArenaHarness.locateAssetsDir();
        ArenaHarness.loadData(assets);

        List<String> fighters = ArenaHarness.fightableMobs(true);
        System.out.println("[rl2-rank] fighters: " + fighters.size()
                + ", trials per pair: " + trials);

        for (int charLvl : LEVELS) {
            rankAt(fighters, charLvl, trials);
        }
    }

    /** Run the round-robin at a fixed character level and print the ranked table. */
    private static void rankAt(List<String> fighters, int charLvl, int trials) {
        int n = fighters.size();
        // Spawn one instance per type at the requested character level so we don't
        // pay the build cost again per pair. mobfight is non-mutating.
        Mob[] mobs = new Mob[n];
        for (int i = 0; i < n; i++) {
            mobs[i] = ArenaHarness.buildFighter(fighters.get(i));
            MobProgression.setSpawnLevel(mobs[i], charLvl);
        }
        double[] meanWinRate = new double[n];
        int[] pairs = new int[n];
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double aWins = GameBalance.mobfight(mobs[i], mobs[j], trials);
                meanWinRate[i] += aWins;
                meanWinRate[j] += (1.0 - aWins);
                pairs[i]++;
                pairs[j]++;
            }
        }
        for (int i = 0; i < n; i++) {
            if (pairs[i] > 0) meanWinRate[i] /= pairs[i];
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        java.util.Arrays.sort(order, Comparator.comparingDouble((Integer i) -> -meanWinRate[i]));

        System.out.println();
        System.out.println("==== Power ranking @ character level " + charLvl + " ====");
        System.out.printf("%-4s %-28s %6s   %6s %5s %4s%n",
                "rank", "mob", "win%", "hp", "dmg", "armr");
        for (int rank = 0; rank < n; rank++) {
            int i = order[rank];
            Mob m = mobs[i];
            com.bjsp123.rl2.model.StatBlock s = m.effectiveStats();
            String dmg = s.damage.min() + "-" + s.damage.max();
            String arm = s.armor.min() + "-" + s.armor.max();
            System.out.printf("%4d %-28s %5.1f%%   %6.0f %5s %4s%n",
                    rank + 1, fighters.get(i),
                    meanWinRate[i] * 100.0,
                    s.maxHp, dmg, arm);
        }
    }

}
