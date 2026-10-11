package app.watchaudio.core.platform

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Home of the OS-specific adapters (Transcriber, SecureStore, ...). Each adapter is declared
 * here and implemented per platform.
 */
expect fun platformName(): String

/** Dispatcher for blocking file and database work. */
expect val IoDispatcher: CoroutineDispatcher
