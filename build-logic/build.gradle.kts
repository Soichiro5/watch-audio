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
    }
}
