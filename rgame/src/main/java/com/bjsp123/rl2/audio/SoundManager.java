package com.bjsp123.rl2.audio;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.files.FileHandle;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;
import com.bjsp123.rl2.model.Point;
import com.bjsp123.rl2.ui.skin.Settings;
import com.bjsp123.rl2.util.CsvTable;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Loads SFX from sounds.csv and plays them on demand. Deduplicates files so
 *  each unique path is opened exactly once, even if multiple keys share it.
 *  A per-key wall-clock cooldown prevents the same sound from stacking when
 *  many game events fire the same key across consecutive AI turns. */
public final class SoundManager {

    /** Minimum milliseconds between two plays of the same sound key.
     *  Keeps a rapid burst of identical events (e.g. 5 mobs attacking in one
     *  render frame) from stacking concurrent instances and exhausting the
     *  OpenAL source pool. 60 ms lets a run-over of several powerups rattle off
     *  distinct dings while still collapsing true same-frame bursts. */
    private static final long COOLDOWN_MS = 60;

    private final Map<String, Sound> fileCache    = new LinkedHashMap<>();
    private final Map<String, Sound> keyMap       = new HashMap<>();
    private final Map<String, Long>  lastPlayedMs = new HashMap<>();

    public SoundManager(CsvTable table) {
        for (Map<String, String> row : table.rows) {
            String key  = CsvTable.str(row, "key", "");
            String path = CsvTable.str(row, "sfx", "");
            if (key.isEmpty() || path.isEmpty()) continue;
            Sound s = fileCache.computeIfAbsent(path, p -> {
                FileHandle f = Gdx.files.internal(p);
                return f.exists() ? Gdx.audio.newSound(f) : null;
            });
            if (s != null) keyMap.put(key, s);
        }
    }

    /** Play the sound for {@code key}, falling back to progressively shorter
     *  prefixes (stripping the last dot-segment) until a mapped key is found.
     *  E.g. {@code sfx.item.use.wand.wand_fire} tries that key first, then
     *  {@code sfx.item.use.wand}, then {@code sfx.item.use}, then gives up.
     *  Cooldown is tracked on the resolved key so two callers that share the
     *  same fallback still rate-limit against each other.
     *
     * @return {@code true} if a key was resolved to a mapped sound (the sound
     *         may have been suppressed by the cooldown); {@code false} if no
     *         mapped key was found anywhere in the prefix chain.  Callers that
     *         want to try an alternative key on failure can branch on this. */
    public boolean play(String key) {
        if (!Settings.sfxEnabled()) return false;
        long now = System.currentTimeMillis();
        String k = key;
        while (true) {
            Sound s = keyMap.get(k);
            if (s != null) {
                Long last = lastPlayedMs.get(k);
                if (last == null || now - last >= COOLDOWN_MS) {
                    lastPlayedMs.put(k, now);
                    s.play(Settings.sfxVolume());
                }
                return true;
            }
            int dot = k.lastIndexOf('.');
            if (dot < 0) return false;
            k = k.substring(0, dot);
        }
    }

    /** Like {@link #play(String)} but scales the final volume by {@code volumeMultiplier}
     *  (clamped to [0,1]). Use for distance attenuation or always-quiet sounds. */
    public boolean play(String key, float volumeMultiplier) {
        if (!Settings.sfxEnabled()) return false;
        long now = System.currentTimeMillis();
        String k = key;
        while (true) {
            Sound s = keyMap.get(k);
            if (s != null) {
                Long last = lastPlayedMs.get(k);
                if (last == null || now - last >= COOLDOWN_MS) {
                    lastPlayedMs.put(k, now);
                    s.play(Settings.sfxVolume() * Math.max(0f, Math.min(1f, volumeMultiplier)));
                }
                return true;
            }
            int dot = k.lastIndexOf('.');
            if (dot < 0) return false;
            k = k.substring(0, dot);
        }
    }

    /** Play a sound anchored at a world tile. Silently skips the sound if
     *  {@code pos} is not in the player's line-of-sight. Volume falls off 10%
     *  per tile beyond Chebyshev distance 4 from the player. */
    public boolean playAt(String key, Level level, Point pos) {
        return playAt(key, level, pos, 1.0f);
    }

    /** Like {@link #playAt(String, Level, Point)} but applies an additional
     *  {@code base} multiplier before distance attenuation (e.g. 0.5f for footsteps). */
    public boolean playAt(String key, Level level, Point pos, float base) {
        float vol = spatialVolume(level, pos);
        if (vol <= 0f) return false;
        return play(key, vol * base);
    }

    // -- spatial helpers -----------------------------------------------------

    private static float spatialVolume(Level level, Point pos) {
        if (level == null || pos == null) return 1.0f;
        int px = pos.tileX(), py = pos.tileY();
        if (px < 0 || py < 0 || px >= level.width || py >= level.height) return 0f;
        if (level.visible == null || !level.visible[px][py]) return 0f;
        Mob player = findPlayer(level);
        if (player == null || player.position == null) return 1.0f;
        int dist = Math.max(Math.abs(px - player.position.tileX()),
                            Math.abs(py - player.position.tileY()));
        if (dist <= 4) return 1.0f;
        return Math.max(0f, 1.0f - (dist - 4) * 0.10f);
    }

    private static Mob findPlayer(Level level) {
        if (level.mobs == null) return null;
        for (Mob m : level.mobs) {
            if (m != null && m.isPlayer) return m;
        }
        return null;
    }

    /** No-op — retained so existing call sites in {@link com.bjsp123.rl2.world.anim.Animator}
     *  compile without changes. The wall-clock cooldown in {@link #play} makes
     *  the per-drain dedup set unnecessary. */
    public void beginFrame() {}

    public void dispose() {
        for (Sound s : fileCache.values()) if (s != null) s.dispose();
        fileCache.clear();
        keyMap.clear();
    }
}
