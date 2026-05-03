package com.bjsp123.rl2.logic;

import java.util.Arrays;

/**
 * Recursive-shadowcasting FOV. Operates on flat 1D boolean arrays indexed as {@code cell = x + y * w}. Marks a cell visible
 * before testing whether it blocks, so walls at the FOV boundary are included in the output.
 */
public final class ShadowCaster {

    public static final int MAX_DISTANCE = 20;

    // Max column index per row for each vision distance — clips the square FOV into a circle.
    private static final int[][] rounding;
    static {
        rounding = new int[MAX_DISTANCE + 1][];
        for (int i = 1; i <= MAX_DISTANCE; i++) {
            rounding[i] = new int[i + 1];
            for (int j = 1; j <= i; j++) {
                rounding[i][j] = (int) Math.min(j,
                        Math.round(i * Math.cos(Math.asin(j / (i + 0.5)))));
            }
        }
    }

    public static void castShadow(int x, int y, int w, boolean[] fov, boolean[] blocking, int distance) {
        if (distance > MAX_DISTANCE) distance = MAX_DISTANCE;
        Arrays.fill(fov, false);
        fov[y * w + x] = true;
        try {
            scanOctant(distance, fov, blocking, 1, x, y, w, 0.0, 1.0, +1, -1, false);
            scanOctant(distance, fov, blocking, 1, x, y, w, 0.0, 1.0, -1, +1, true);
            scanOctant(distance, fov, blocking, 1, x, y, w, 0.0, 1.0, +1, +1, true);
            scanOctant(distance, fov, blocking, 1, x, y, w, 0.0, 1.0, +1, +1, false);
            scanOctant(distance, fov, blocking, 1, x, y, w, 0.0, 1.0, -1, +1, false);
            scanOctant(distance, fov, blocking, 1, x, y, w, 0.0, 1.0, +1, -1, true);
            scanOctant(distance, fov, blocking, 1, x, y, w, 0.0, 1.0, -1, -1, true);
            scanOctant(distance, fov, blocking, 1, x, y, w, 0.0, 1.0, -1, -1, false);
        } catch (Exception e) {
            // Preserve whatever FOV the successful octants already wrote. Wiping to all-false on
            // any octant's unexpected error would black out the whole view — worse than a
            // partial view with one missing octant.
        }
    }

    private static void scanOctant(int distance, boolean[] fov, boolean[] blocking, int row,
                                   int x, int y, int w, double lSlope, double rSlope,
                                   int mX, int mY, boolean mXY) {

        boolean inBlocking = false;
        int start, end, col;

        int[] roundingAtDist;
        if (distance == 2) {
            // At vision radius 2, fill in corners so diagonals aren't disproportionately punished.
            // GWT doesn't support `.clone()` on primitive arrays — use Arrays.copyOf instead.
            int[] src = rounding[distance];
            roundingAtDist = Arrays.copyOf(src, src.length);
            roundingAtDist[2] = 2;
        } else {
            roundingAtDist = rounding[distance];
        }

        for (; row <= distance; row++) {
            if (rSlope < lSlope) return;

            if (lSlope == 0)    start = 0;
            else                start = (int) Math.floor((row - 0.5) * lSlope + 0.499);

            if (rSlope == 1)    end = roundingAtDist[row];
            else                end = Math.min(roundingAtDist[row],
                                               (int) Math.ceil((row + 0.5) * rSlope - 0.499));

            int cell = x + y * w;
            if (mXY)    cell += mX * start * w + mY * row;
            else        cell += mX * start     + mY * row * w;

            for (col = start; col <= end; col++) {

                if (col == end && inBlocking
                        && (int) Math.ceil((row - 0.5) * rSlope - 0.499) != end) {
                    break;
                }

                fov[cell] = true;

                if (blocking[cell]) {
                    if (!inBlocking) {
                        inBlocking = true;
                        if (col != start) {
                            scanOctant(distance, fov, blocking, row + 1, x, y, w, lSlope,
                                    (col - 0.5) / (row + 0.5),
                                    mX, mY, mXY);
                        }
                    }
                } else {
                    if (inBlocking) {
                        inBlocking = false;
                        lSlope = (col - 0.5) / (row - 0.5);
                    }
                }

                if (!mXY)   cell += mX;
                else        cell += mX * w;
            }

            if (inBlocking) return;
        }
    }
}
