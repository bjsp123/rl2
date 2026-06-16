package com.bjsp123.rl2.util;

import com.badlogic.gdx.utils.Json;
import com.bjsp123.rl2.model.World;
import com.bjsp123.rl2.save.SaveSystem;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * [DEV / DIAGNOSTIC] Round-trips an on-disk save through {@link SaveSystem}'s
 * exact Json config and prints the full stack trace on failure, so a
 * "could not load" can be pinpointed offline. Not shipping code.
 *   ./gradlew :rgame:debugSave                 # ~/.rl2/rl2-save-1
 *   ./gradlew :rgame:debugSave --args="rl2-save-0"
 */
public final class SaveLoadDebugMain {
    private SaveLoadDebugMain() {}

    public static void main(String[] args) throws Exception {
        String key = args.length > 0 ? args[0] : "rl2-save-1";
        Path p = Paths.get(System.getProperty("user.home"), ".rl2", key);
        String raw = Files.readString(p);
        System.out.println("[debugSave] " + p + " (" + raw.length() + " chars)");
        // Mirror SaveSystem.migrateLegacy so this validates the real load path.
        raw = raw.replace("java.util.ImmutableCollections$ListN",  "java.util.ArrayList")
                 .replace("java.util.ImmutableCollections$List12", "java.util.ArrayList")
                 .replace("java.util.ImmutableCollections$SetN",   "java.util.HashSet")
                 .replace("java.util.ImmutableCollections$Set12",  "java.util.HashSet")
                 .replace("java.util.ImmutableCollections$MapN",   "java.util.HashMap")
                 .replace("java.util.ImmutableCollections$Map1",   "java.util.HashMap");
        Json json = SaveSystem.buildJson();
        try {
            World w = json.fromJson(World.class, raw);
            System.out.println("[debugSave] OK - levels=" + (w == null || w.levels == null
                    ? "null" : w.levels.length));
        } catch (Throwable t) {
            System.out.println("[debugSave] FAILED: " + t);
            t.printStackTrace(System.out);
            for (Throwable c = t.getCause(); c != null; c = c.getCause()) {
                System.out.println("--- caused by: " + c);
            }
        }
    }
}
