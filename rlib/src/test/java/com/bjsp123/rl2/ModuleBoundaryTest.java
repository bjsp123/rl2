package com.bjsp123.rl2;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * rlib is the headless model/logic module: it must never grow a dependency on
 * libGDX rendering, rgame, or rai. The compiler can't catch a maintainer who
 * adds the dependency to rlib/build.gradle first, so this test pins the
 * boundary: every import in rlib main sources must come from an allowed
 * prefix, and the gdx artifacts must not appear in rlib/build.gradle.
 */
class ModuleBoundaryTest {

    private static final String[] ALLOWED_IMPORT_PREFIXES = {
            "java.",
            "com.bjsp123.rl2.model.",
            "com.bjsp123.rl2.logic.",
            "com.bjsp123.rl2.util.",
            "com.bjsp123.rl2.event.",
    };

    /** Forbidden anywhere in file text — catches fully-qualified inline use too. */
    private static final String[] FORBIDDEN_ANYWHERE = {
            "com.badlogic.",          // all of libGDX — rlib uses none of it
            "com.bjsp123.rl2.save",   // rgame packages
            "com.bjsp123.rl2.persistence",
            "com.bjsp123.rl2.ui",
            "com.bjsp123.rl2.screen",
            "com.bjsp123.rl2.world",
            "com.bjsp123.rl2.input",
            "com.bjsp123.rl2.ai",     // rai
    };

    private static final Pattern IMPORT = Pattern.compile("^import\\s+(static\\s+)?([\\w.]+)");

    /** Gradle runs rlib tests with workingDir = rlib/; walk up as a fallback
     *  (same trick as ArenaHarness.locateAssetsDir). */
    private static Path rlibDir() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("src/main/java/com/bjsp123/rl2"))
                    && dir.getFileName() != null
                    && dir.getFileName().toString().equals("rlib")) {
                return dir;
            }
            Path sub = dir.resolve("rlib");
            if (Files.isDirectory(sub.resolve("src/main/java/com/bjsp123/rl2"))) {
                return sub;
            }
        }
        throw new IllegalStateException("cannot locate rlib module dir from " + Paths.get("").toAbsolutePath());
    }

    @Test
    void rlibSourcesOnlyImportAllowedPackages() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(rlibDir().resolve("src/main/java"))) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                // Strip comments first: javadoc {@link}s to rgame classes are
                // legitimate documentation, not a code dependency.
                String text = Files.readString(file)
                        .replaceAll("(?s)/\\*.*?\\*/", "")
                        .replaceAll("//[^\n]*", "");
                for (String line : text.split("\n")) {
                    Matcher m = IMPORT.matcher(line.trim());
                    if (!m.find()) continue;
                    String imported = m.group(2);
                    boolean ok = false;
                    for (String prefix : ALLOWED_IMPORT_PREFIXES) {
                        if (imported.startsWith(prefix)) { ok = true; break; }
                    }
                    if (!ok) violations.add(file.getFileName() + ": import " + imported);
                }
                for (String forbidden : FORBIDDEN_ANYWHERE) {
                    if (text.contains(forbidden)) {
                        violations.add(file.getFileName() + ": references " + forbidden);
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "rlib is the headless model/logic module; rendering belongs in rgame,"
                + " AI agents in rai. Boundary violations:\n  "
                + String.join("\n  ", violations));
    }

    @Test
    void rlibBuildGradleHasNoGdxDependency() throws IOException {
        String gradle = Files.readString(rlibDir().resolve("build.gradle"));
        assertTrue(!gradle.contains("badlogicgames") && !gradle.contains("libgdx"),
                "rlib/build.gradle must not depend on libGDX — rlib is the"
                + " headless model/logic module; put GDX-dependent code in rgame.");
    }
}
