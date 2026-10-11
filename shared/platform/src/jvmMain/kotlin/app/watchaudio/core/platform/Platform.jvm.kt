package app.watchaudio.core.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual fun platformName(): String = "JVM ${System.getProperty("os.name")}"

actual val IoDispatcher: CoroutineDispatcher = Dispatchers.IO
