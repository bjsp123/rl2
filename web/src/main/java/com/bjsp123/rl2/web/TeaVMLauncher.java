package com.bjsp123.rl2.web;

import com.bjsp123.rl2.Rl2Game;
import com.bjsp123.rl2.platform.EntitlementService;
import com.bjsp123.rl2.platform.PlatformServices;
import com.github.xpenatan.gdx.teavm.backends.web.WebApplication;
import com.github.xpenatan.gdx.teavm.backends.web.WebApplicationConfiguration;

/**
 * Browser entry point - the {@code main} TeaVM compiles to JavaScript. Mirrors
 * {@link com.bjsp123.rl2.desktop.DesktopLauncher DesktopLauncher}: register the
 * SMART brain, build the platform services, hand them to {@link Rl2Game}.
 */
public class TeaVMLauncher {

    public static void main(String[] args) {
        WebApplicationConfiguration config = new WebApplicationConfiguration("canvas");
        // 0x0 = track the canvas/element size (responsive full-window).
        config.width = 0;
        config.height = 0;
        // Back the canvas with true physical pixels. The default (false)
        // renders at CSS pixels and lets the browser stretch by
        // devicePixelRatio - smearing the nearest-neighbour pixel art.
        config.usePhysicalPixels = true;
        // FreeType is compiled to wasm and loaded as a script before the app
        // starts, so StoneUi's FreeTypeFontGenerator works unchanged. (The
        // Supabase bridge is NOT loaded here: preload scripts arrive after
        // this main() has already constructed the auth service, so
        // BuildTeaVMWeb injects rl2-config.js / rl2-bridge.js as plain
        // <script> tags ahead of app.js instead - window.rl2 exists before
        // any Java code runs.)
        config.preloadListener = assetLoader -> assetLoader.loadScript("freetype.js");

        // Register the SMART mob brain before Rl2Game.create() loads mobs -
        // same ordering rule as the desktop launcher.
        com.bjsp123.rl2.ai.RaiBootstrap.init();

        // Local-first persistence: the game reads/writes localStorage
        // synchronously; the decorator mirrors whitelisted keys to the
        // signed-in user's cloud store in the background. With no Supabase
        // config (rl2-config.js empty), auth reports unavailable, the account
        // UI stays hidden, and sync never starts - fully-offline build.
        WebPersistence localStore = new WebPersistence();
        CloudSyncingPersistence persistence = new CloudSyncingPersistence(localStore);
        WebAuthService auth = new WebAuthService();
        persistence.start(auth);

        PlatformServices services = new PlatformServices(
                persistence, auth, new EntitlementService.NoEntitlementService());
        new WebApplication(new Rl2Game(services), config);
    }
}
