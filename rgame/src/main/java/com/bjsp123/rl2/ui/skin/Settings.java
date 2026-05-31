package com.bjsp123.rl2.ui.skin;

import com.badlogic.gdx.Gdx;
import com.bjsp123.rl2.persistence.Persistence;
import com.bjsp123.rl2.ui.v2.UIVars;

/** Persistent user settings and their defaults. */
public final class Settings {
    private static final String KEY_UI_SCALE = "rl2-ui-scale-v3";
    private static final String KEY_UI_PIXEL_SCALE = "rl2-ui-pixel-scale";
    private static final String KEY_UI_FONT_SCALE = "rl2-ui-font-scale-v2";
    private static final String KEY_QUICKSLOTS = "rl2-quickslot-count";
    private static final String KEY_USE_BUFF_ICONS = "rl2-use-buff-icons";
    private static final String KEY_ANIMATION_SPEED = "rl2-animation-speed";
    private static final String KEY_QUEUE_ACCEL = "rl2-anim-queue-accel";
    private static final String KEY_MOB_OUTLINE_WIDTH = "rl2-mob-outline-width";
    private static final String KEY_MOB_OUTLINE_DARKNESS = "rl2-mob-outline-darkness";
    private static final String KEY_MOB_OUTLINE_SMOOTH = "rl2-mob-outline-smooth";
    private static final String KEY_LOG_FONT_SCALE = "rl2-log-font-scale";
    private static final String KEY_LOG_ON = "rl2-log-on";
    private static final String KEY_LOG_LOW_PRIORITY = "rl2-log-low-priority";
    private static final String KEY_LOG_NON_PLAYER = "rl2-log-non-player";
    private static final String KEY_LOG_EXPANDED = "rl2-log-expanded";
    private static final String KEY_INSTANT_ACTIONS = "rl2-instant-actions";
    private static final String KEY_LOW_RES_RENDER  = "rl2-low-res-render";
    private static final String KEY_PERF_OVERLAY    = "rl2-perf-overlay";
    private static final String KEY_SFX_ENABLED     = "rl2-sfx-enabled";
    private static final String KEY_SFX_VOLUME      = "rl2-sfx-volume";
    private static final String KEY_MUSIC_ENABLED   = "rl2-music-enabled";
    private static final String KEY_MUSIC_VOLUME    = "rl2-music-volume";
    private static final String KEY_TIPS_ENABLED    = "rl2-tips-enabled";
    private static final String KEY_MELEE_PREVIEW   = "rl2-melee-preview";
    private static final String KEY_COLORBLIND      = "rl2-colorblind-preset";

    /** Colorblind palette presets for the {@code UIVars.DAMAGE_*} colors. */
    public enum ColorblindPreset { NONE, DEUTER, PROTAN, TRITAN }

    public static final float UI_SCALE_DEFAULT = 1.0f;
    public static final float[] UI_SCALE_CHOICES = { 1.0f, 1.5f, 2.0f, 2.5f, 3.0f, 3.5f };

    public static final int UI_PIXEL_SCALE_DEFAULT = 1;
    public static final int[] UI_PIXEL_SCALE_CHOICES = { 1, 2, 3, 4 };

    public static final float UI_FONT_SCALE_DEFAULT = 1.0f;
    public static final float[] UI_FONT_SCALE_CHOICES = { 0.75f, 1.0f, 1.5f, 2.0f };

    public static final int QUICKSLOT_COUNT_DEFAULT = 8;
    public static final int[] QUICKSLOT_COUNT_CHOICES = { 4, 6, 8, 9 };

    public static final boolean USE_BUFF_ICONS_DEFAULT = true;

    public static final float ANIMATION_SPEED_DEFAULT = 1.0f;
    public static final float[] ANIMATION_SPEED_CHOICES = { 0.5f, 1f, 2f, 4f };

    public static final float MOB_OUTLINE_WIDTH_DEFAULT = 0.6f;
    public static final float MOB_OUTLINE_DARKNESS_DEFAULT = 0.55f;
    public static final boolean MOB_OUTLINE_SMOOTH_DEFAULT = true;
    public static final float MOB_OUTLINE_MAX_WIDTH = 2.0f;
    public static final float[] MOB_OUTLINE_WIDTH_CHOICES = { 0.2f, 0.3f, 0.5f, 0.8f, 1.0f, 1.5f };
    public static final float[] MOB_OUTLINE_DARKNESS_CHOICES = { 0.6f, 0.8f, 0.9f, 0.95f, 0.98f, 1.0f };

    public static final float LOG_FONT_SCALE_DEFAULT = 1.5f;
    public static final float[] LOG_FONT_SCALE_CHOICES = { 1.0f, 1.25f, 1.5f, 2.0f, 2.5f, 3.0f };

    public static final float   SFX_VOLUME_DEFAULT   = 1.0f;
    public static final float   MUSIC_VOLUME_DEFAULT = 0.75f;
    public static final float[] VOLUME_CHOICES       = { 0f, 0.25f, 0.5f, 0.75f, 1.0f };

    private static Persistence persistence;

    private static float uiScale = UI_SCALE_DEFAULT;
    private static int uiPixelScale = UI_PIXEL_SCALE_DEFAULT;
    private static float uiFontScale = UI_FONT_SCALE_DEFAULT;
    private static int quickslotCount = QUICKSLOT_COUNT_DEFAULT;
    private static boolean useBuffIcons = USE_BUFF_ICONS_DEFAULT;
    private static float framesPerRender = ANIMATION_SPEED_DEFAULT;
    private static float animationTransientOverride;
    private static boolean queueAccelEnabled = true;
    private static float mobOutlineWidth = MOB_OUTLINE_WIDTH_DEFAULT;
    private static float mobOutlineDarkness = MOB_OUTLINE_DARKNESS_DEFAULT;
    private static boolean mobOutlineSmooth = MOB_OUTLINE_SMOOTH_DEFAULT;
    private static volatile float logFontScale = LOG_FONT_SCALE_DEFAULT;
    private static volatile boolean logOn = true;
    private static volatile boolean showLowPriority;
    private static volatile boolean showNonPlayer;
    private static volatile boolean logExpanded;
    private static boolean instantActions;
    private static boolean lowResRender;
    private static boolean showPerfOverlay;
    private static boolean sfxEnabled    = true;
    private static float   sfxVolume     = SFX_VOLUME_DEFAULT;
    private static boolean musicEnabled  = true;
    private static float   musicVolume   = MUSIC_VOLUME_DEFAULT;
    private static boolean tipsEnabled   = true;
    private static boolean meleePreview  = true;
    private static ColorblindPreset colorblindPreset = ColorblindPreset.NONE;

    private Settings() {}

    public static void init(Persistence p) {
        persistence = p;
        uiScale = loadChoiceFloat(KEY_UI_SCALE, detectUiScaleDefault(), UI_SCALE_CHOICES);
        uiPixelScale = loadChoiceInt(KEY_UI_PIXEL_SCALE, UI_PIXEL_SCALE_DEFAULT, UI_PIXEL_SCALE_CHOICES);
        uiFontScale = loadChoiceFloat(KEY_UI_FONT_SCALE, UI_FONT_SCALE_DEFAULT, UI_FONT_SCALE_CHOICES);
        quickslotCount = loadChoiceInt(KEY_QUICKSLOTS, QUICKSLOT_COUNT_DEFAULT, QUICKSLOT_COUNT_CHOICES);
        useBuffIcons = loadBoolean(KEY_USE_BUFF_ICONS, USE_BUFF_ICONS_DEFAULT);
        framesPerRender = loadChoiceFloat(KEY_ANIMATION_SPEED, ANIMATION_SPEED_DEFAULT, ANIMATION_SPEED_CHOICES);
        queueAccelEnabled = loadBoolean(KEY_QUEUE_ACCEL, true);
        mobOutlineWidth = clampMobOutlineWidth(loadFloat(KEY_MOB_OUTLINE_WIDTH, MOB_OUTLINE_WIDTH_DEFAULT));
        mobOutlineDarkness = clamp01(loadFloat(KEY_MOB_OUTLINE_DARKNESS, MOB_OUTLINE_DARKNESS_DEFAULT));
        mobOutlineSmooth = loadBoolean(KEY_MOB_OUTLINE_SMOOTH, MOB_OUTLINE_SMOOTH_DEFAULT);
        logFontScale = loadChoiceFloat(KEY_LOG_FONT_SCALE, LOG_FONT_SCALE_DEFAULT, LOG_FONT_SCALE_CHOICES);
        logOn = loadBoolean(KEY_LOG_ON, true);
        showLowPriority = loadBoolean(KEY_LOG_LOW_PRIORITY, false);
        showNonPlayer = loadBoolean(KEY_LOG_NON_PLAYER, false);
        logExpanded = loadBoolean(KEY_LOG_EXPANDED, false);
        instantActions = loadBoolean(KEY_INSTANT_ACTIONS, false);
        lowResRender   = loadBoolean(KEY_LOW_RES_RENDER, false);
        showPerfOverlay = loadBoolean(KEY_PERF_OVERLAY, false);
        sfxEnabled    = loadBoolean(KEY_SFX_ENABLED, true);
        sfxVolume     = loadChoiceFloat(KEY_SFX_VOLUME, SFX_VOLUME_DEFAULT, VOLUME_CHOICES);
        musicEnabled  = loadBoolean(KEY_MUSIC_ENABLED, true);
        musicVolume   = loadChoiceFloat(KEY_MUSIC_VOLUME, MUSIC_VOLUME_DEFAULT, VOLUME_CHOICES);
        tipsEnabled   = loadBoolean(KEY_TIPS_ENABLED, true);
        meleePreview  = loadBoolean(KEY_MELEE_PREVIEW, true);
        colorblindPreset = loadColorblindPreset();
        UIVars.applyColorblindPalette(colorblindPreset);
    }

    private static ColorblindPreset loadColorblindPreset() {
        if (persistence == null) return ColorblindPreset.NONE;
        String raw = persistence.load(KEY_COLORBLIND);
        if (raw == null) return ColorblindPreset.NONE;
        try { return ColorblindPreset.valueOf(raw); }
        catch (IllegalArgumentException ignored) { return ColorblindPreset.NONE; }
    }

    public static float uiScale() { return uiScale; }
    public static int uiPixelScale() { return uiPixelScale; }
    public static float uiFontScale() { return uiFontScale; }
    public static int quickslotCount() { return quickslotCount; }
    public static boolean useBuffIcons() { return useBuffIcons; }
    public static float framesPerRender() { return animationTransientOverride > 0 ? animationTransientOverride : framesPerRender; }
    public static boolean queueAccelEnabled() { return queueAccelEnabled; }
    public static float mobOutlineWidth() { return mobOutlineWidth; }
    public static float mobOutlineDarkness() { return mobOutlineDarkness; }
    public static boolean mobOutlineSmooth() { return mobOutlineSmooth; }
    public static float logFontScale() { return logFontScale; }
    public static boolean logOn() { return logOn; }
    public static boolean showLowPriority() { return showLowPriority; }
    public static boolean showNonPlayer() { return showNonPlayer; }
    public static boolean logExpanded() { return logExpanded; }
    public static boolean instantActions() { return instantActions; }
    public static boolean lowResRender()    { return lowResRender; }
    public static boolean showPerfOverlay() { return showPerfOverlay; }
    public static boolean sfxEnabled()     { return sfxEnabled; }
    public static float   sfxVolume()      { return sfxVolume; }
    public static boolean musicEnabled()   { return musicEnabled; }
    public static float   musicVolume()    { return musicVolume; }
    public static boolean tipsEnabled()    { return tipsEnabled; }
    public static boolean meleePreview()   { return meleePreview; }
    public static ColorblindPreset colorblindPreset() { return colorblindPreset; }

    public static void setUiScale(float v) { uiScale = v; save(KEY_UI_SCALE, v); }
    public static void setUiPixelScale(int v) { uiPixelScale = Math.max(1, v); save(KEY_UI_PIXEL_SCALE, uiPixelScale); }
    public static void setUiFontScale(float v) { if (v > 0f) { uiFontScale = v; save(KEY_UI_FONT_SCALE, v); } }
    public static void setQuickslotCount(int v) { quickslotCount = v; save(KEY_QUICKSLOTS, v); }
    public static void setUseBuffIcons(boolean v) { useBuffIcons = v; save(KEY_USE_BUFF_ICONS, v); }
    public static void setFramesPerRender(float v) { framesPerRender = v; save(KEY_ANIMATION_SPEED, v); }
    public static void setAnimationTransientOverride(float v) { animationTransientOverride = Math.max(0f, v); }
    public static void setQueueAccelEnabled(boolean v) { queueAccelEnabled = v; save(KEY_QUEUE_ACCEL, v); }
    public static void setMobOutlineWidth(float v) { mobOutlineWidth = clampMobOutlineWidth(v); save(KEY_MOB_OUTLINE_WIDTH, mobOutlineWidth); }
    public static void setMobOutlineDarkness(float v) { mobOutlineDarkness = clamp01(v); save(KEY_MOB_OUTLINE_DARKNESS, mobOutlineDarkness); }
    public static void setMobOutlineSmooth(boolean v) { mobOutlineSmooth = v; save(KEY_MOB_OUTLINE_SMOOTH, v); }
    public static void setLogFontScale(float v) { if (v > 0f) { logFontScale = v; save(KEY_LOG_FONT_SCALE, v); } }
    public static void setLogOn(boolean v) { logOn = v; save(KEY_LOG_ON, v); }
    public static void setShowLowPriority(boolean v) { showLowPriority = v; save(KEY_LOG_LOW_PRIORITY, v); }
    public static void setShowNonPlayer(boolean v) { showNonPlayer = v; save(KEY_LOG_NON_PLAYER, v); }
    public static void setLogExpanded(boolean v) { logExpanded = v; save(KEY_LOG_EXPANDED, v); }
    public static void setInstantActions(boolean v) { instantActions = v; save(KEY_INSTANT_ACTIONS, v); }
    public static void setLowResRender(boolean v)    { lowResRender = v;    save(KEY_LOW_RES_RENDER, v); }
    public static void setShowPerfOverlay(boolean v) { showPerfOverlay = v; save(KEY_PERF_OVERLAY, v); }
    public static void setSfxEnabled(boolean v)    { sfxEnabled = v;    save(KEY_SFX_ENABLED, v); }
    public static void setSfxVolume(float v)       { sfxVolume = v;     save(KEY_SFX_VOLUME, v); }
    public static void setMusicEnabled(boolean v)  { musicEnabled = v;  save(KEY_MUSIC_ENABLED, v); }
    public static void setMusicVolume(float v)     { musicVolume = v;   save(KEY_MUSIC_VOLUME, v); }
    public static void setTipsEnabled(boolean v)   { tipsEnabled = v;   save(KEY_TIPS_ENABLED, v); }
    public static void setMeleePreview(boolean v)  { meleePreview = v;  save(KEY_MELEE_PREVIEW, v); }
    public static void setColorblindPreset(ColorblindPreset v) {
        colorblindPreset = v == null ? ColorblindPreset.NONE : v;
        if (persistence != null) persistence.save(KEY_COLORBLIND, colorblindPreset.name());
        UIVars.applyColorblindPalette(colorblindPreset);
    }

    private static float detectUiScaleDefault() {
        if (Gdx.graphics == null) return UI_SCALE_DEFAULT;
        int w = Gdx.graphics.getWidth();
        if (w <= 0) return UI_SCALE_DEFAULT;
        int narrow = Math.min(w, Gdx.graphics.getHeight());
        if (narrow >= 1440) return 3.0f;
        if (narrow >= 1080) return 2.5f;
        if (narrow >= 720) return 2.0f;
        if (narrow >= 480) return 1.5f;
        return UI_SCALE_DEFAULT;
    }

    private static float loadChoiceFloat(String key, float fallback, float[] choices) {
        float v = loadFloat(key, fallback);
        for (float c : choices) if (Math.abs(v - c) < 0.001f) return c;
        return fallback;
    }

    private static int loadChoiceInt(String key, int fallback, int[] choices) {
        int v = loadInt(key, fallback);
        for (int c : choices) if (v == c) return c;
        return fallback;
    }

    private static float loadFloat(String key, float fallback) {
        if (persistence == null) return fallback;
        String raw = persistence.load(key);
        if (raw == null) return fallback;
        try { return Float.parseFloat(raw); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static int loadInt(String key, int fallback) {
        if (persistence == null) return fallback;
        String raw = persistence.load(key);
        if (raw == null) return fallback;
        try { return Integer.parseInt(raw); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static boolean loadBoolean(String key, boolean fallback) {
        if (persistence == null) return fallback;
        String raw = persistence.load(key);
        return raw == null ? fallback : Boolean.parseBoolean(raw);
    }

    private static void save(String key, float v) {
        if (persistence != null) persistence.save(key, Float.toString(v));
    }

    private static void save(String key, int v) {
        if (persistence != null) persistence.save(key, Integer.toString(v));
    }

    private static void save(String key, boolean v) {
        if (persistence != null) persistence.save(key, Boolean.toString(v));
    }

    private static float clampMobOutlineWidth(float v) {
        return Math.max(0f, Math.min(MOB_OUTLINE_MAX_WIDTH, v));
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
