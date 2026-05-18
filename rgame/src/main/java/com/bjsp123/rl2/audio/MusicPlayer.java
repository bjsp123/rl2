package com.bjsp123.rl2.audio;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Music;
import com.badlogic.gdx.files.FileHandle;
import com.bjsp123.rl2.ui.skin.Settings;

/** Handles looping background music. Switching tracks stops and disposes the
 *  previous one. Missing files are silently skipped — no crash, no log spam.
 *  Idempotent: calling {@link #play} with the currently-playing track is a
 *  no-op. */
public final class MusicPlayer {

    public enum Track {
        TITLE   ("audio/music/title.ogg"),
        GAMEPLAY("audio/music/gameplay.ogg");

        public final String path;
        Track(String p) { this.path = p; }
    }

    private Track current;
    private Music music;

    public void play(Track track) {
        if (track == current && music != null) return;
        stopInternal();
        current = track;
        if (!Settings.musicEnabled()) return;
        FileHandle fh = Gdx.files.internal(track.path);
        if (!fh.exists()) return;
        music = Gdx.audio.newMusic(fh);
        music.setLooping(true);
        music.setVolume(Settings.musicVolume());
        music.play();
    }

    /** Update the live volume — call after the user changes the music-volume
     *  setting so the change is heard immediately without restarting the track. */
    public void applyVolume() {
        if (music != null) music.setVolume(Settings.musicVolume());
    }

    public void dispose() {
        stopInternal();
    }

    private void stopInternal() {
        if (music != null) { music.stop(); music.dispose(); music = null; }
        current = null;
    }
}
