package app.watchaudio.app

import app.watchaudio.app.logic.PassphraseStrength
import app.watchaudio.app.logic.passphraseAccepted
import app.watchaudio.app.logic.passphraseStrength
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The backup passphrase hint (#40). */
class PassphraseTest {
    @Test
    fun strengthGrowsWithLengthAndVariety() {
        assertEquals(PassphraseStrength.TOO_SHORT, passphraseStrength(""))
        assertEquals(PassphraseStrength.TOO_SHORT, passphraseStrength("abc1234"))
        assertEquals(PassphraseStrength.WEAK, passphraseStrength("abcdefgh"))
        assertEquals(PassphraseStrength.WEAK, passphraseStrength("aaaaaaaaaaaaaaaaaaaa")) // long but repetitive
        assertEquals(PassphraseStrength.FAIR, passphraseStrength("abcdefghijkl"))
        assertEquals(PassphraseStrength.FAIR, passphraseStrength("Abcdef1!"))
        assertEquals(PassphraseStrength.STRONG, passphraseStrength("Abcdef12!xyz"))
        assertEquals(PassphraseStrength.STRONG, passphraseStrength("correct horse battery"))
        // Japanese counts per character.
        assertEquals(PassphraseStrength.TOO_SHORT, passphraseStrength("とけいのきろく"))
        assertEquals(PassphraseStrength.FAIR, passphraseStrength("とけいのきろくを2026"))
        assertEquals(PassphraseStrength.STRONG, passphraseStrength("とけいのきろく 2026"))
        assertEquals(PassphraseStrength.STRONG, passphraseStrength("あさのかいぎはじゅうじからですね"))
        // A character outside the BMP counts once.
        assertEquals(PassphraseStrength.TOO_SHORT, passphraseStrength("😀".repeat(7)))
    }

    @Test
    fun acceptedNeedsLengthAndTheSameConfirmation() {
        assertTrue(passphraseAccepted("abcdefgh", "abcdefgh"))
        assertFalse(passphraseAccepted("abcdefgh", "abcdefgi"))
        assertFalse(passphraseAccepted("abcdefg", "abcdefg"))
    }
}
