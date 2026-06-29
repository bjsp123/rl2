package com.bjsp123.rl2.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link SeedCode} - the seed&lt;-&gt;six-letter-code codec. */
class SeedCodeTest {

    // -- happy path ----------------------------------------------------------

    @Test
    void encodeProducesSixUppercaseLetters() {
        String code = SeedCode.encode(123456789L);
        assertEquals(6, code.length());
        assertTrue(code.matches("[A-Z]{6}"), "code was: " + code);
    }

    @Test
    void everyCodeRoundTripsBackToItself() {
        // The guaranteed direction: code -> seed -> code is the identity.
        for (String code : new String[]{"AAAAAA", "ABCDEF", "ZZZZZZ", "QWERTY"}) {
            assertEquals(code, SeedCode.encode(SeedCode.decode(code)));
        }
    }

    @Test
    void seedRoundTripsWhenInsideCodeSpace() {
        for (long seed : new long[]{0L, 1L, 25L, 26L, 1000L, SeedCode.SPACE - 1}) {
            assertEquals(seed, SeedCode.decode(SeedCode.encode(seed)));
        }
    }

    @Test
    void decodeIsCaseInsensitiveAndTrimmed() {
        assertEquals(SeedCode.decode("ABCDEF"), SeedCode.decode("  abcdef  "));
    }

    @Test
    void negativeAndOversizeSeedsFoldIntoSpace() {
        // encode folds via floorMod, so the decoded value is the folded seed.
        assertEquals(Math.floorMod(-1L, SeedCode.SPACE),
                SeedCode.decode(SeedCode.encode(-1L)));
        assertEquals(Math.floorMod(SeedCode.SPACE + 5, SeedCode.SPACE),
                SeedCode.decode(SeedCode.encode(SeedCode.SPACE + 5)));
    }

    @Test
    void aaaaaaIsZeroAndAaaaabIsOne() {
        assertEquals(0L, SeedCode.decode("AAAAAA"));
        assertEquals(1L, SeedCode.decode("AAAAAB"));
    }

    @Test
    void isValidAcceptsWellFormedCodes() {
        assertTrue(SeedCode.isValid("ABCDEF"));
        assertTrue(SeedCode.isValid("  abcdef "));
        assertTrue(SeedCode.isValid("ZZZZZZ"));
    }

    // -- unhappy path --------------------------------------------------------

    @Test
    void decodeNullThrows() {
        assertThrows(IllegalArgumentException.class, () -> SeedCode.decode(null));
    }

    @Test
    void decodeWrongLengthThrows() {
        assertThrows(IllegalArgumentException.class, () -> SeedCode.decode("ABC"));
        assertThrows(IllegalArgumentException.class, () -> SeedCode.decode("ABCDEFG"));
        assertThrows(IllegalArgumentException.class, () -> SeedCode.decode(""));
    }

    @Test
    void decodeNonLetterThrows() {
        assertThrows(IllegalArgumentException.class, () -> SeedCode.decode("ABC1EF"));
        assertThrows(IllegalArgumentException.class, () -> SeedCode.decode("ABC-EF"));
    }

    @Test
    void isValidRejectsBadInput() {
        assertFalse(SeedCode.isValid(null));
        assertFalse(SeedCode.isValid("ABC"));
        assertFalse(SeedCode.isValid("ABCDEFG"));
        assertFalse(SeedCode.isValid("ABC1EF"));
        assertFalse(SeedCode.isValid(""));
    }
}
