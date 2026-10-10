package app.watchaudio.core.platform

import android.os.Build

actual fun platformName(): String = "Android ${Build.VERSION.RELEASE}"
