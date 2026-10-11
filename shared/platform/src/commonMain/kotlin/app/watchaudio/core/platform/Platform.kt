package app.watchaudio.core.platform

import kotlinx.coroutines.CoroutineDispatcher

/**
 * OS name and version for diagnostics (e.g. "Android 15"). This module holds the small
 * expect/actual primitives the shared modules need ([IoDispatcher], [Sha256], [PlatformLock]);
 * larger adapters such as `Transcriber` are interfaces in the modules that use them.
 */
expect fun platformName(): String

/** Dispatcher for blocking file and database work. */
expect val IoDispatcher: CoroutineDispatcher
