package com.bjsp123.rl2.save;

import com.bjsp123.rl2.logic.TextCatalog;

/**
 * Player-progression milestones. Each entry is a flat data record - display
 * name + a short description of what triggers it, plus a {@link Category}
 * for grouping in the Hall of Fame Achievements tab and a {@code hidden}
 * flag for surprise / spoiler entries (rendered as {@code ???} until
 * unlocked). Unlock state lives in {@link Achievements}; persistence is in
 * {@link AchievementsStore}.
 *
 * <p>Triggers fire from rgame-side observation in {@link AchievementSystem}
 * so rlib stays free of presentation hooks.
 */
public enum Achievement {

    BEGUN_THE_ADVENTURE(Category.DEPTH, false),
    DUG_DEEPER         (Category.DEPTH, false),
    INTO_THE_DEPTHS    (Category.EXPLORATION, false),
    FIRST_BLOOD        (Category.COMBAT, false),
    GIANT_SLAYER       (Category.COMBAT, true),
    LEVELED_UP         (Category.PROGRESSION, false),
    WAND_NEWBIE        (Category.ITEMS, false),
    THROWN_AWAY        (Category.ITEMS, false),
    ARTISAN            (Category.ITEMS, false),
    HALL_OF_FAMER      (Category.RUN, false);

    public final Category category;
    public final boolean  hidden;

    Achievement(Category category, boolean hidden) {
        this.category    = category;
        this.hidden      = hidden;
    }

    public String displayName() {
        return TextCatalog.get("achievement." + name() + ".name");
    }

    public String description() {
        return TextCatalog.get("achievement." + name() + ".description");
    }

    /** Coarse grouping for display order in the Hall of Fame
     *  Achievements tab. Not a behavioural flag. */
    public enum Category {
        DEPTH,
        EXPLORATION,
        COMBAT,
        PROGRESSION,
        ITEMS,
        RUN;

        public String displayName() {
            return TextCatalog.get("achievement.category." + name());
        }
    }
}
