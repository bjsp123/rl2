package com.bjsp123.rl2;

import com.badlogic.gdx.Game;
import com.bjsp123.rl2.save.HallOfFame;
import com.bjsp123.rl2.save.SaveSystem;
import com.bjsp123.rl2.persistence.Persistence;
import com.bjsp123.rl2.screen.PlayScreen;
import com.bjsp123.rl2.screen.TitleScreen;
import com.bjsp123.rl2.ui.skin.UiPixelScale;
import com.bjsp123.rl2.ui.skin.UiScale;
import com.bjsp123.rl2.ui.skin.UiStyleChoice;
import com.bjsp123.rl2.world.anim.Animator;

public class Rl2Game extends Game {

    /** Persistence key for the most recent desktop window size, formatted as "WxH". */
    public static final String WINDOW_SIZE_KEY = "desktop-window";
    /** Minimum gap between window-size persistence writes during a resize drag. */
    private static final long  RESIZE_PERSIST_THROTTLE_MS = 250;

    public final Persistence persistence;
    public HallOfFame  hallOfFame;
    public SaveSystem  saveSystem;

    /** The currently in-progress run, if any. Null means "no game to resume". */
    public PlayScreen currentPlay;

    private long lastResizePersistMs;
    private int  lastPersistedW, lastPersistedH;

    public Rl2Game(Persistence persistence) {
        this.persistence = persistence;
    }

    @Override
    public void create() {
        UiScale.init(persistence);
        UiPixelScale.init(persistence);
        UiStyleChoice.init(persistence);
        com.bjsp123.rl2.ui.skin.UseBuffIcons.init(persistence);
        // Plug the rgame-side icon-pref toggle into the in-world Animator so its
        // BuffApplied event handler renders an icon when the user wants icons and a
        // text float otherwise.
        com.bjsp123.rl2.world.anim.Animator.setIconPreferenceSupplier(
                com.bjsp123.rl2.ui.skin.UseBuffIcons::enabled);
        hallOfFame = new HallOfFame(persistence);
        saveSystem = new SaveSystem(persistence);
        setScreen(new TitleScreen(this));
    }

    @Override
    public void pause() {
        super.pause();
        if (currentPlay != null) currentPlay.persist();
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        if (persistence == null || width <= 0 || height <= 0) return;
        long now = System.currentTimeMillis();
        // Drag-resizes fire at frame rate; throttle so we don't hammer disk. Also skip
        // duplicate writes when nothing actually changed.
        if (width == lastPersistedW && height == lastPersistedH) return;
        if (now - lastResizePersistMs < RESIZE_PERSIST_THROTTLE_MS) return;
        persistence.save(WINDOW_SIZE_KEY, width + "x" + height);
        lastResizePersistMs = now;
        lastPersistedW = width;
        lastPersistedH = height;
    }

    @Override
    public void dispose() {
        // Final flush — guarantees the size after the user's last drag (which may have been
        // dropped by the throttle) is written before exit.
        if (persistence != null
                && com.badlogic.gdx.Gdx.graphics != null
                && com.badlogic.gdx.Gdx.graphics.getWidth() > 0) {
            persistence.save(WINDOW_SIZE_KEY,
                    com.badlogic.gdx.Gdx.graphics.getWidth() + "x"
                  + com.badlogic.gdx.Gdx.graphics.getHeight());
        }
        super.dispose();
    }
}
