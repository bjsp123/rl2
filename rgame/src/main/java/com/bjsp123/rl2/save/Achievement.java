package com.bjsp123.rl2.save;

/**
 * Player-progression milestones. Each entry is a flat data record — display
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

    BEGUN_THE_ADVENTURE("Begun the Adventure", "Descend to depth 2.",
            Category.DEPTH, false),
    DUG_DEEPER         ("Dug Deeper",          "Descend to depth 5.",
            Category.DEPTH, false),
    INTO_THE_DEPTHS    ("Into the Depths",     "Take the stairs down for the first time.",
            Category.EXPLORATION, false),
    FIRST_BLOOD        ("First Blood",         "Kill your first foe.",
            Category.COMBAT, false),
    GIANT_SLAYER       ("Giant Slayer",        "Slay a unique creature.",
            Category.COMBAT, true),
    LEVELED_UP         ("Leveling Up",         "Gain your first character level.",
            Category.PROGRESSION, false),
    WAND_NEWBIE        ("Magical Education",   "Fire a wand for the first time.",
            Category.ITEMS, false),
    THROWN_AWAY        ("Thrown Away",         "Throw an item for the first time.",
            Category.ITEMS, false),
    ARTISAN            ("Artisan",             "Craft your first item.",
            Category.ITEMS, false),
    HALL_OF_FAMER      ("Memento Mori",        "Complete a run — your name in the Hall of Fame.",
            Category.RUN, false);

    public final String   displayName;
    public final String   description;
    public final Category category;
    public final boolean  hidden;

    Achievement(String displayName, String description,
                Category category, boolean hidden) {
        this.displayName = displayName;
        this.description = description;
        this.category    = category;
        this.hidden      = hidden;
    }

    /** Coarse grouping for display order in the Hall of Fame
     *  Achievements tab. Not a behavioural flag. */
    public enum Category {
        DEPTH("Depth"),
        EXPLORATION("Exploration"),
        COMBAT("Combat"),
        PROGRESSION("Progression"),
        ITEMS("Items"),
        RUN("Run");

        public final String displayName;
        Category(String displayName) { this.displayName = displayName; }
    }
}
