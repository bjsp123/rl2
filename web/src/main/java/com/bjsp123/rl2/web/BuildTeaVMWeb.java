package com.bjsp123.rl2.web;

import com.github.xpenatan.gdx.teavm.backends.shared.config.AssetFileHandle;
import com.github.xpenatan.gdx.teavm.backends.shared.config.compiler.TeaCompiler;
import com.github.xpenatan.gdx.teavm.backends.web.config.backend.WebBackend;
import java.io.File;
import org.teavm.vm.TeaVMOptimizationLevel;

/**
 * Build-time entry point (plain JVM, run by the {@code :web:buildWeb} /
 * {@code :web:run} Gradle tasks) - configures and runs the TeaVM compiler that
 * turns the game into a static webapp under {@code web/build/dist/web/webapp}.
 *
 * <p>This is also where ALL TeaVM reflection metadata is registered. TeaVM has
 * no open reflection: every class reached via {@code java.lang.reflect} at
 * runtime must be listed here. rl2 uses reflection in exactly four places:
 * <ul>
 *   <li>libGDX {@code Json} (de)serialization of the save graph - the whole
 *       {@code model} package plus the save-side metadata/roster classes;</li>
 *   <li>{@code GameBalance.load} - config.csv rows set fields by name;</li>
 *   <li>{@code UIVars.load} - same pattern;</li>
 *   <li>{@code AnimationVars.load} - same pattern.</li>
 * </ul>
 * A missing entry surfaces as a SerializationException / NoSuchFieldException
 * in the browser console, not a compile error - if a save fails to load or a
 * config override silently stops applying on web, look here first.
 */
public class BuildTeaVMWeb {

    public static void main(String[] args) {
        boolean serve = args.length > 0 && "serve".equalsIgnoreCase(args[0]);

        // Jetty is started explicitly AFTER the build + bridge-script copy
        // below (setStartJettyAfterBuild would block inside build() before
        // the copy could run).
        WebBackend backend = new WebBackend()
                .setWebAssembly(false)
                .setStartJettyAfterBuild(false)
                .setHtmlTitle("rl2");

        // Shared root assets dir - same files desktop/android use, minus content
        // the browser build should never download. The filter gates both the
        // copy and the preload manifest:
        //  - scratch/   : dev-only reference material (screenshots etc).
        //  - sfx/wav/   : the original uncompressed WAV archive (~340 MB); the
        //                 game only loads the sfx/*.ogg conversions (see
        //                 assets/data/sounds.csv).
        AssetFileHandle assets = new AssetFileHandle("../assets");
        assets.filter = path -> {
            String p = path.replace('\\', '/');
            return !p.contains("/scratch/") && !p.contains("/sfx/wav/");
        };

        TeaCompiler builder = new TeaCompiler(backend)
                .addAssets(assets)
                // gdx-teavm's DefaultReflectionListener force-enables reflection for
                // ALL of com.badlogic.gdx.scenes.scene2d.** (and gltf). rl2 never
                // constructs scene2d classes reflectively (no Actions pools, no Skin
                // JSON - the V2 UI is hand-drawn), and the default breaks the build:
                // the generated $rt_simpleConstructors registry eagerly references
                // constructors (e.g. RelativeTemporalAction.<init>) that the FULL
                // optimizer dead-code-eliminated, throwing a ReferenceError before
                // main() runs. Empty pattern list = only the explicit
                // addReflectionClass entries below get reflection metadata.
                .setReflectionListener(new com.github.xpenatan.gdx.teavm.backends
                        .shared.config.reflection.DefaultReflectionListener() {
                    @Override protected void setupDefaultPatterns() { }
                })
                .setMainClass(TeaVMLauncher.class.getName())
                // FULL = optimized runtime (inlining, devirtualization, GC
                // tuning) - the lever that matters for smooth movement; SIMPLE
                // produced slow JS. Kept un-obfuscated so DevTools profiles
                // show real method names; flip obfuscated on for release to
                // also shrink the download.
                .setObfuscated(false)
                .setOptimizationLevel(TeaVMOptimizationLevel.FULL);

        // --- Reflection registration (see class javadoc) -------------------
        // Save graph: World -> Level -> Mob/Item/... all live in model.
        builder.addReflectionClass("com.bjsp123.rl2.model");
        // Reflective CSV config loaders (field-by-name assignment).
        builder.addReflectionClass("com.bjsp123.rl2.logic.GameBalance");
        builder.addReflectionClass("com.bjsp123.rl2.ui.v2.UIVars");
        builder.addReflectionClass("com.bjsp123.rl2.world.anim.AnimationVars");
        // Save-side Json models: slot metadata, hall-of-fame stores, achievements.
        builder.addReflectionClass("com.bjsp123.rl2.save");

        builder.build(new File("build/dist/web"));

        patchDanglingConstructorRefs();
        copyBridgeScripts();
        injectBridgeScriptTags();

        if (serve) {
            backend.startJetty(8080, "build/dist/web/webapp");
        }
    }

    /** TeaVM workaround: the generated {@code $rt_simpleConstructors([...])} call
     *  runs eagerly at script load and may reference constructor functions the
     *  optimizer never emitted. It happens for classes reflection dataflow can
     *  reach but that are never legitimately constructable - e.g. abstract
     *  collection superclasses ({@code java.util.AbstractSet}) pulled in because
     *  libGDX Json walks {@code getSuperclass()} chains into
     *  {@code ClassReflection.newInstance}. One dangling identifier throws a
     *  ReferenceError before {@code main()} runs and kills the whole app.
     *  Neither lowering the optimization level nor {@code addReflectionClass}
     *  preserves the constructor, so we patch the artifact: scan the registry
     *  list for identifiers with no definition anywhere else in app.js and
     *  prepend a {@code var} declaration for each ({@code undefined} is a safe
     *  value - such classes can never be reflectively instantiated anyway). */
    private static void patchDanglingConstructorRefs() {
        File appJs = new File("build/dist/web/webapp/app.js");
        try {
            String src = java.nio.file.Files.readString(appJs.toPath());
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\\$rt_simpleConstructors\\(\\[(.*?)\\]\\)",
                            java.util.regex.Pattern.DOTALL)
                    .matcher(src);
            java.util.LinkedHashSet<String> dangling = new java.util.LinkedHashSet<>();
            while (m.find()) {
                for (String raw : m.group(1).split(",")) {
                    String id = raw.trim();
                    if (id.isEmpty() || !isDangling(src, id)) continue;
                    dangling.add(id);
                }
            }
            if (dangling.isEmpty()) return;
            String decls = "// [rl2-web] TeaVM patch: registry references with no emitted definition\n"
                    + "var " + String.join(", ", dangling) + ";\n";
            // Insert AFTER the "use strict" directive - prepending would demote
            // it to a plain string and turn the whole script sloppy-mode.
            String directive = "\"use strict\";";
            int at = src.indexOf(directive);
            String patched = at >= 0
                    ? src.substring(0, at + directive.length()) + "\n" + decls
                            + src.substring(at + directive.length())
                    : decls + src;
            java.nio.file.Files.writeString(appJs.toPath(), patched);
            System.out.println("[rl2-web] patched dangling constructor refs: " + dangling);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to patch app.js constructor refs", e);
        }
    }

    /** True when {@code id} occurs exactly once in {@code src} as a whole word -
     *  i.e. the registry reference itself, with no definition elsewhere. */
    private static boolean isDangling(String src, String id) {
        int n = 0, i = -1;
        while ((i = src.indexOf(id, i + 1)) != -1) {
            char b = i == 0 ? ' ' : src.charAt(i - 1);
            char a = i + id.length() >= src.length() ? ' ' : src.charAt(i + id.length());
            if (!isIdentChar(b) && !isIdentChar(a) && ++n > 1) return false;
        }
        return n == 1;
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /** Add the bridge scripts as plain {@code <script>} tags BEFORE app.js in
     *  the generated index.html, so {@code window.rl2} exists before the
     *  TeaVM-compiled {@code main()} constructs the auth service. (The
     *  preloadListener path would load them too late - preload runs after
     *  main() has already built the platform services.) */
    private static void injectBridgeScriptTags() {
        File html = new File("build/dist/web/webapp/index.html");
        try {
            String text = java.nio.file.Files.readString(html.toPath());
            String appTag = "<script type=\"text/javascript\" charset=\"utf-8\" src=\"app.js\">";
            String inject = "<script src=\"scripts/rl2-config.js\"></script>\n        "
                    + "<script src=\"scripts/rl2-bridge.js\"></script>\n        ";
            if (!text.contains(appTag)) {
                throw new IllegalStateException(
                        "index.html app.js tag not found - gdx-teavm template changed?");
            }
            if (!text.contains("rl2-bridge.js")) {
                java.nio.file.Files.writeString(html.toPath(),
                        text.replace(appTag, inject + appTag));
                System.out.println("[rl2-web] injected bridge <script> tags into index.html");
            }
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to patch index.html", e);
        }
    }

    /** Copy the hand-written JS (Supabase bridge + per-deployment config,
     *  src/main/resources/*.js) into the generated webapp's scripts/ dir -
     *  where freetype.js lives and where the launcher's {@code loadScript}
     *  resolves. (gdx-teavm's own script-collection convention only scans
     *  classpath JARs, not the module's resources directory, hence the
     *  explicit copy.) */
    private static void copyBridgeScripts() {
        File dstDir = new File("build/dist/web/webapp/scripts");
        dstDir.mkdirs();
        for (String name : new String[]{"rl2-config.js", "rl2-bridge.js"}) {
            try (java.io.InputStream in =
                         BuildTeaVMWeb.class.getResourceAsStream("/" + name)) {
                if (in == null) throw new java.io.FileNotFoundException(name + " not on classpath");
                java.nio.file.Files.copy(in, new File(dstDir, name).toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[rl2-web] copied " + name + " -> scripts/");
            } catch (java.io.IOException e) {
                throw new RuntimeException("Failed to copy bridge script " + name, e);
            }
        }
    }
}
