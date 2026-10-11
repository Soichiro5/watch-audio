package app.watchaudio.core.platform

/**
 * A reentrant mutual-exclusion lock for short, blocking critical sections that may be entered
 * from any thread (e.g. appending to a log file). Not for suspending code.
 */
expect class PlatformLock() {
    fun <T> withLock(block: () -> T): T
}
