package com.bjsp123.rl2.logic;

import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;

/** Shared targeting queries used by AI and UI/controller helpers. */
public final class MobTargeting {
    private MobTargeting() {}

    public static Mob nearestHostile(Mob around, Level level) {
        if (around == null) return null;
        Mob best = null;
        int bestD = Integer.MAX_VALUE;
        int ax = around.position.tileX(), ay = around.position.tileY();
        for (Mob m : level.mobs) {
            if (m == around
                    || MobSystem.getAttitudeToMob(m, around) == MobSystem.Attitude.ALLY) {
                continue;
            }
            if (m.behavior == Mob.Behavior.INANIMATE) continue;
            int mx = m.position.tileX(), my = m.position.tileY();
            if (mx < 0 || my < 0 || mx >= level.width || my >= level.height) continue;
            if (!level.visible[mx][my]) continue;
            int d = Math.max(Math.abs(mx - ax), Math.abs(my - ay));
            if (d < bestD) { bestD = d; best = m; }
        }
        return best;
    }
}
