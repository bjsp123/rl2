package com.bjsp123.rl2.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bjsp123.rl2.DataFixture;
import com.bjsp123.rl2.logic.GameBalance;
import com.bjsp123.rl2.logic.TextCatalog;
import com.bjsp123.rl2.util.ArenaHarness;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TextCatalog} behaviour AND Tier-B coverage of the real
 * {@code strings.csv}: lookups, the missing-key sentinel, template formatting,
 * and that every fightable monster + every difficulty has a display name.
 */
class StringsCatalogTest extends DataFixture {

    // -- lookup behaviour: happy --------------------------------------------

    @Test
    void formatSubstitutesVars() {
        String out = TextCatalog.formatText("hit for {n} damage",
                TextCatalog.vars("n", 7));
        assertEquals("hit for 7 damage", out);
    }

    @Test
    void capitalizePluralAndArticle() {
        assertEquals("Sword", TextCatalog.capitalize("sword"));
        assertEquals("foxes", TextCatalog.plural("fox", 2));
        assertEquals("berries", TextCatalog.plural("berry", 3));
        assertEquals("an", TextCatalog.indefiniteArticle("apple"));
        assertEquals("a", TextCatalog.indefiniteArticle("dog"));
    }

    // -- lookup behaviour: unhappy ------------------------------------------

    @Test
    void missingKeyReturnsSentinel() {
        assertEquals("??no.such.key??", TextCatalog.get("no.such.key"));
    }

    @Test
    void getOrDefaultFallsBackOnNullOrMissing() {
        assertEquals("fb", TextCatalog.getOrDefault(null, "fb"));
        assertEquals("fb", TextCatalog.getOrDefault("", "fb"));
        assertEquals("fb", TextCatalog.getOrDefault("absent.key", "fb"));
    }

    @Test
    void formatTextToleratesNullTemplateAndVars() {
        assertEquals("", TextCatalog.formatText(null, TextCatalog.vars("a", 1)));
        assertEquals("plain", TextCatalog.formatText("plain", null));
    }

    @Test
    void pluralAndArticleHandleNullAndEmpty() {
        assertEquals("", TextCatalog.plural(null, 2));
        assertEquals("", TextCatalog.indefiniteArticle(null));
        assertEquals("", TextCatalog.capitalize(""));
    }

    // -- Tier-B coverage -----------------------------------------------------

    @Test
    void everyFightableMonsterHasADisplayName() {
        List<String> fightable = ArenaHarness.fightableMobs(false);
        assertTrue(fightable.size() > 0, "no fightable mobs");
        for (String type : fightable) {
            assertNotNull(TextCatalog.mobName(type, null),
                    "mob missing name string: mob." + type + ".name");
        }
    }

    @Test
    void everyDifficultyHasNameAndDescription() {
        for (GameBalance.Difficulty d : GameBalance.Difficulty.values()) {
            String key = "difficulty." + d.name().toLowerCase(java.util.Locale.ROOT);
            assertNotNull(TextCatalog.getOrDefault(key + ".name", null),
                    "missing " + key + ".name");
            assertNotNull(TextCatalog.getOrDefault(key + ".description", null),
                    "missing " + key + ".description");
        }
    }
}
