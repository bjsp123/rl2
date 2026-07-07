package com.bjsp123.rl2.util;

/**
 * App version + build number, stamped into every save file's metadata and
 * shown at the bottom of the Settings screen.
 *
 * <p>{@link #BUILD} is generated at build time from the total git commit
 * count (see {@code :rlib:generateBuildInfo} in rlib/build.gradle) - it
 * changes on every commit, not just releases. Bump {@link #VERSION} for
 * user-facing milestones. A save whose stamp doesn't match the running app
 * (including unstamped legacy saves) is treated as incompatible: the loader
 * warns rather than failing silently / crashing on a doomed parse.
 */
public final class AppVersion {

    private AppVersion() {}

    public static final String VERSION = "0.1";
    public static final int    BUILD   = BuildInfo.COMMIT_COUNT;

    /** Running app's stamp, e.g. {@code "0.1 (build 0)"}. */
    public static String label() {
        return VERSION + " (build " + BUILD + ")";
    }

    /** Human label for a stored stamp, tolerating the unstamped legacy form
     *  (empty {@code version}). */
    public static String describe(String version, int build) {
        if (version == null || version.isEmpty()) return "an older, unversioned build";
        return version + " (build " + build + ")";
    }

    /** True only when a stored stamp matches the running app exactly. Unstamped
     *  legacy saves (empty/{@code null} version) never match. */
    public static boolean matches(String version, int build) {
        return VERSION.equals(version) && BUILD == build;
    }
}
