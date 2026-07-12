package com.bjsp123.rl2.ai.action;

import com.bjsp123.rl2.ai.WorldState;
import com.bjsp123.rl2.ai.eval.CombatEval;
import com.bjsp123.rl2.logic.MobMovement;
import com.bjsp123.rl2.logic.MobSystem;
import com.bjsp123.rl2.logic.Pathfinder;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.model.Tile;

import java.util.HashSet;
import java.util.Set;

/**
 * Pathfind one step toward {@link #destination}. The workhorse motion action - the
 * goal supplies the destination, the action handles the step. Honours the
 * "don't step onto a ONETIME_DOOR unless it's safe or unavoidable" rule from the plan.
 */
public final class ActionMoveToward implements Action {

    public final Point destination;
    public final String label;
    public final double basePriority;
    /** When true, step source is {@link com.bjsp123.rl2.ai.eval.ExplorationEval#nextStepToTarget}
     *  instead of {@link Pathfinder#nextStep}, and the ONETIME_DOOR safety bail is skipped.
     *  EXPLORE / DESCEND set this so a BFS-reachable target always produces a steppable
     *  move - matching the rules BFS itself uses. */
    public final boolean useBfsStep;

    public ActionMoveToward(Point destination, String label, double basePriority) {
        this(destination, label, basePriority, false);
    }

    public ActionMoveToward(Point destination, String label, double basePriority, boolean useBfsStep) {
        this.destination = destination;
        this.label = label;
        this.basePriority = basePriority;
        this.useBfsStep = useBfsStep;
    }

    @Override public String name() { return "move"; }

    @Override public boolean isApplicable(WorldState s) {
        return destination != null && computeStep(s) != null;
    }

    @Override public double utility(WorldState s) { return basePriority; }

    @Override public void execute(Mob mob, Level level) {
        Point step = useBfsStep
                ? com.bjsp123.rl2.ai.eval.ExplorationEval.nextStepToTarget(
                        mob, level, com.bjsp123.rl2.ai.SmartAi.peekMemory(mob), destination)
                : computeStepFor(mob, level, destination);
        if (step == null) {
            com.bjsp123.rl2.logic.TurnSystem.applyMoveCost(mob, mob.effectiveStats().moveCost);
            return;
        }
        mob.targetPosition = step;
        MobMovement.stepTowardTarget(mob, level);
    }

    @Override public String intentDetail() { return label; }

    /* ----- step computation with one-time-door safety ----- */

    private Point computeStep(WorldState s) {
        return useBfsStep
                ? com.bjsp123.rl2.ai.eval.ExplorationEval.nextStepToTarget(
                        s.mob, s.level, s.memory, destination)
                : computeStepFor(s.mob, s.level, destination);
    }

    /**
     * Try an avoid-onetime-door route first; fall back to the unrestricted route only
     * when the avoid route fails AND stepping onto a onetime door is judged safe.
     */
    static Point computeStepFor(Mob mob, Level level, Point dest) {
        if (dest == null) return null;
        Set<Point> avoid = collectOnetimeDoors(level);
        Point step = avoid.isEmpty()
                ? Pathfinder.nextStep(level, mob, dest)
                : Pathfinder.nextStepAvoiding(level, mob, dest, avoid);
        if (step != null && !isOnetimeDoorAt(level, step)) return step;
        Point unrestricted = Pathfinder.nextStep(level, mob, dest);
        if (unrestricted == null) return null;
        if (!isOnetimeDoorAt(level, unrestricted)) return unrestricted;
        return isOnetimeStepSafe(mob, level, unrestricted) ? unrestricted : null;
    }

    private static Set<Point> collectOnetimeDoors(Level level) {
        Set<Point> out = new HashSet<>();
        for (int y = 0; y < level.height; y++) {
            for (int x = 0; x < level.width; x++) {
                if (level.tiles[x][y] == Tile.ONETIME_DOOR) out.add(new Point(x, y));
            }
        }
        return out;
    }

    private static boolean isOnetimeDoorAt(Level level, Point p) {
        return p != null && level.tiles[p.tileX()][p.tileY()] == Tile.ONETIME_DOOR;
    }

    /**
     * Safe-to-break check: only consume the door when (a) no alternative path exists
     * (already true when we reach this branch) AND (b) the threats visible beyond it
     * collectively rate below half the mob's own combat power.
     */
    private static boolean isOnetimeStepSafe(Mob mob, Level level, Point step) {
        double allowedThreat = 0.5;
        double total = 0.0;
        if (mob.visibleMobsAtTurnStart == null) return true;
        for (Mob other : mob.visibleMobsAtTurnStart) {
            if (other == mob || other.hp <= 0 || other.position == null) continue;
            com.bjsp123.rl2.logic.MobSystem.Attitude att =
                    com.bjsp123.rl2.logic.MobSystem.getAttitudeToMob(mob, other);
            if (att != com.bjsp123.rl2.logic.MobSystem.Attitude.ATTACK) continue;
            // Only count threats on the "far" side of the door (closer to step than to mob).
            int dStep = WorldState.chebyshev(step, other.position);
            int dSelf = WorldState.chebyshev(mob.position, other.position);
            if (dStep <= dSelf) total += CombatEval.threatRating(mob, other);
        }
        return total < allowedThreat;
    }
}
