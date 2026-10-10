package app.watchaudio.core.platform

/**
 * Home of the OS-specific adapters (Transcriber, Embedder, SecureStore, HealthSource, ...).
 * Each adapter is declared here and implemented per platform.
 */
expect fun platformName(): String
