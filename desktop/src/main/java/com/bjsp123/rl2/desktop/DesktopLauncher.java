package com.bjsp123.rl2.desktop;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.bjsp123.rl2.Rl2Game;

public class DesktopLauncher {

    private static final int DEFAULT_W = 960;
    private static final int DEFAULT_H = 560;
    private static final int MIN_W     = 400;
    private static final int MIN_H     = 300;

    public static void main(String[] args) {
        DesktopPersistence persistence = new DesktopPersistence();
        int[] size = readWindowSize(persistence);

        // Register the SMART mob brain before Rl2Game.create() finishes loading mobs.
        com.bjsp123.rl2.ai.RaiBootstrap.init();

        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setForegroundFPS(60);
        config.setTitle("rl2");
        config.setWindowedMode(size[0], size[1]);
        new Lwjgl3Application(new Rl2Game(persistence), config);
    }

    /** Parse the persisted "WxH" string, falling back to the default on missing / malformed. */
    private static int[] readWindowSize(DesktopPersistence persistence) {
        String raw = persistence.load(Rl2Game.WINDOW_SIZE_KEY);
        if (raw != null) {
            int x = raw.indexOf('x');
            if (x > 0 && x < raw.length() - 1) {
                try {
                    int w = Integer.parseInt(raw.substring(0, x).trim());
                    int h = Integer.parseInt(raw.substring(x + 1).trim());
                    if (w >= MIN_W && h >= MIN_H) return new int[]{w, h};
                } catch (NumberFormatException ignored) { /* fall through to default */ }
            }
        }
        return new int[]{DEFAULT_W, DEFAULT_H};
    }
}
