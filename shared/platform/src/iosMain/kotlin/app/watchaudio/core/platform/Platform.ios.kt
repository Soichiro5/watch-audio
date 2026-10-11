package app.watchaudio.core.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import platform.UIKit.UIDevice

actual fun platformName(): String = "${UIDevice.currentDevice.systemName} ${UIDevice.currentDevice.systemVersion}"

actual val IoDispatcher: CoroutineDispatcher = Dispatchers.IO
