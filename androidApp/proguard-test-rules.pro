# R8 rules for the instrumented test APK when it is built against the release build type
# (`-Pwatchaudio.testBuildType=release`, ReleaseAsrTest in emulator.yml, #78). Not part of the app.

# Annotations that androidx.test refers to but does not ship.
-dontwarn com.google.errorprone.annotations.**
