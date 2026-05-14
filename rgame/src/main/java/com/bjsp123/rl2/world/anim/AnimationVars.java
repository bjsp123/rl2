package com.bjsp123.rl2.world.anim;

import com.bjsp123.rl2.util.CsvTable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/** Config-backed presentation timing and animation tuning. */
public final class AnimationVars {
    private static final String KIND = "animation";
    private static final String KEY_PREFIX = "animation.";

    public static int FIRE_PARTICLE_INTERVAL_MS = 200;
    public static int FIRE_PARTICLE_MOB_INTERVAL_MS = 100;
    public static int SLEEP_Z_MIN_MS = 1200;
    public static int SLEEP_Z_RANGE_MS = 800;
    public static int LEVITATE_PUFF_MIN_MS = 220;
    public static int LEVITATE_PUFF_RANGE_MS = 180;

    public static int DUST_CLOUDS_PER_FRAME = 2;
    public static float DUST_DRIFT_PX_PER_FRAME = 0.25f;
    public static float DUST_UP_BIAS_PX_PER_FRAME = 0.15f;
    public static int CLOUD_PUFF_FRAME_WINDOW = 12;
    public static float CLOUD_PUFF_DRIFT_PX_PER_FRAME = 0.10f;
    public static float CLOUD_PUFF_UP_BIAS_PX_PER_FRAME = 0.08f;

    public static float MELEE_LUNGE_PX = 4f;
    public static float HIT_FLINCH_PX = 3f;
    public static int LUNGE_OUT_FRAMES = 3;
    public static int LUNGE_BACK_FRAMES = 10;
    public static int FLINCH_OUT_FRAMES = 3;
    public static int FLINCH_BACK_FRAMES = 9;
    public static int STEP_FRAMES_MIN = 3;
    public static int STEP_FRAMES_MAX = 12;
    public static int STEP_FRAMES_DEFAULT = 5;
    public static int KNOCKBACK_FRAMES_PER_TILE = 5;
    public static int GRAPPLE_EXTEND_FRAMES = 10;
    public static int GRAPPLE_RETRACT_FRAMES = 14;
    public static int GRAPPLE_FAIL_TAIL_FRAMES = 22;

    public static int BORDER_FLASH_FRAMES = 30;
    public static int SPAWN_GROW_FRAMES = 30;
    public static int DEATH_FLICKER_HALF_FRAMES = 6;
    public static int DEATH_FADE_FRAMES = 30;
    public static float DEATH_FLICKER_LOW_ALPHA = 0.4f;

    public static float MAGIC_MISSILE_PX_PER_FRAME = 2.25f;
    public static float THROWN_ITEM_PX_PER_FRAME = 3.0f;
    public static int FIRE_FRAME_MS = 90;
    public static float PARTICLE_GRAVITY = 0.32f;
    public static float PARTICLE_SIZE = 1.5f;
    public static int PARTICLE_LIFE = 40;

    private AnimationVars() {}

    public static void load(String text) {
        if (text == null || text.isEmpty()) return;
        CsvTable table = CsvTable.parse(text);
        for (java.util.Map<String, String> row : table.rows) {
            if (!KIND.equals(CsvTable.str(row, "kind", ""))) continue;
            String key = CsvTable.str(row, "key", "").trim();
            String value = CsvTable.str(row, "value", "").trim();
            if (!key.startsWith(KEY_PREFIX) || value.isEmpty()) continue;
            try {
                Field f = AnimationVars.class.getDeclaredField(key.substring(KEY_PREFIX.length()));
                int mods = f.getModifiers();
                if (!Modifier.isStatic(mods) || Modifier.isFinal(mods)) continue;
                Class<?> t = f.getType();
                if (t == int.class) f.setInt(null, Integer.parseInt(value));
                else if (t == float.class) f.setFloat(null, Float.parseFloat(value));
                else if (t == double.class) f.setDouble(null, Double.parseDouble(value));
                else if (t == long.class) f.setLong(null, Long.parseLong(value));
                else if (t == boolean.class) f.setBoolean(null, Boolean.parseBoolean(value));
            } catch (NoSuchFieldException | IllegalAccessException | NumberFormatException ignored) {
                // Unknown or malformed animation config keeps its baked-in default.
            }
        }
    }

    public static int deathFlickerFrames() {
        return DEATH_FLICKER_HALF_FRAMES * 4;
    }

    public static int deathTotalFrames() {
        return deathFlickerFrames() + DEATH_FADE_FRAMES;
    }
}
