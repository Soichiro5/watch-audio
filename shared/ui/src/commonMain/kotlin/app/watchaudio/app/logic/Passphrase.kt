package app.watchaudio.app.logic

import app.watchaudio.core.backup.BackupCipher

/** How hard a backup passphrase (#40) would be to guess, as the UI hints it. */
enum class PassphraseStrength {
    /** Shorter than [BackupCipher.MIN_PASSPHRASE_LENGTH]: not accepted. */
    TOO_SHORT,
    WEAK,
    FAIR,
    STRONG,
}

/**
 * A rough strength hint from length and variety (characters are Unicode code points, so a
 * Japanese passphrase counts each character once). It cannot know whether the text is a known
 * phrase; the backup's protection against guessing is the slow key derivation ([BackupCipher]).
 */
fun passphraseStrength(passphrase: String): PassphraseStrength {
    val codePoints = passphrase.count { !it.isLowSurrogate() }
    if (codePoints < BackupCipher.MIN_PASSPHRASE_LENGTH) return PassphraseStrength.TOO_SHORT
    val distinct = passphrase.toSet().size
    val kinds = listOf<(Char) -> Boolean>(
        { it in 'a'..'z' },
        { it in 'A'..'Z' },
        { it in '0'..'9' },
        { it.code < 128 && !it.isLetterOrDigit() },
        { it.code >= 128 },
    ).count { kind -> passphrase.any(kind) }
    return when {
        distinct < 5 -> PassphraseStrength.WEAK
        codePoints >= 16 || (codePoints >= 12 && kinds >= 3) -> PassphraseStrength.STRONG
        codePoints >= 12 || kinds >= 3 -> PassphraseStrength.FAIR
        else -> PassphraseStrength.WEAK
    }
}

/** Whether a backup can be made with [passphrase] confirmed by [confirmation]. */
fun passphraseAccepted(passphrase: String, confirmation: String): Boolean =
    passphraseStrength(passphrase) != PassphraseStrength.TOO_SHORT && passphrase == confirmation
