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
    }
}
