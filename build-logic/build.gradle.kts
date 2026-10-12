plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("kmpLibrary") {
            id = "watchaudio.kmp.library"
            implementationClass = "KmpLibraryConventionPlugin"
        }
        register("sqliteTests") {
            id = "watchaudio.kmp.sqlite-tests"
            implementationClass = "SqliteTestsConventionPlugin"
        }
        register("sqliteTestsJvmIos") {
            id = "watchaudio.kmp.sqlite-tests-jvm-ios"
            implementationClass = "SqliteTestsJvmIosConventionPlugin"
        }
        register("androidApp") {
            id = "watchaudio.android.app"
            implementationClass = "AndroidAppConventionPlugin"
        }
        register("debugInputGuard") {
            id = "watchaudio.android.debug-input-guard"
            implementationClass = "DebugInputGuardPlugin"
        }
        register("sherpaOnnxJni") {
            id = "watchaudio.android.sherpa-onnx-jni"
            implementationClass = "SherpaOnnxJniPlugin"
        }
    }
}
