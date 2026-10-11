package app.watchaudio.core.platform

import android.os.Build
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual fun platformName(): String = "Android ${Build.VERSION.RELEASE}"

actual val IoDispatcher: CoroutineDispatcher = Dispatchers.IO
