package com.bjsp123.rl2;

import com.bjsp123.rl2.util.CsvTable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Game-start tips: shown once per new game, between the world-graph fly-in
 * and the level zoom-in. Loaded from {@code assets/data/tip.csv}.
 *
 * <p>CSV columns:
 * <ul>
 *   <li>{@code title} - displayed prefixed with "Tip:".</li>
 *   <li>{@code spritelist} - pipe-separated item types (e.g.
 *       {@code XPPILL|POWER_ORB}). Resolved via {@code ItemSprites.regionFor}.
 *       A token may carry a caption override after a colon
 *       ({@code XPPILL:free levels}); otherwise the item/mob display name
 *       is used. Empty cell = no sprite row.</li>
 *   <li>{@code text} - body. Supports literal {@code \n} for line breaks and
 *       {@code *emphasis*} markup. Quote the cell if it contains commas.</li>
 * </ul>
 */
public final class GameStartTipsRegistry {

    public static final class GameStartTip {
        public final String title;
        public final List<String> spriteRefs;
        public final String text;

        public GameStartTip(String title, List<String> spriteRefs, String text) {
            this.title      = title == null ? "" : title;
            this.spriteRefs = spriteRefs == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(spriteRefs));
            this.text       = text == null ? "" : text;
        }
    }

    private static final List<GameStartTip> TIPS = new ArrayList<>();

    private GameStartTipsRegistry() {}

    public static void load(String csv) {
        TIPS.clear();
        CsvTable table = CsvTable.parse(csv);
        for (Map<String, String> row : table.rows) {
            String title = CsvTable.str(row, "title", "");
            String text  = CsvTable.str(row, "text",  "");
            if (title.isEmpty() && text.isEmpty()) continue;
            List<String> sprites = CsvTable.listCell(row, "spritelist");
            TIPS.add(new GameStartTip(title, sprites, text));
        }
    }

    public static List<GameStartTip> tips() {
        return Collections.unmodifiableList(TIPS);
    }

    public static boolean isEmpty() {
        return TIPS.isEmpty();
    }
}
