plugins {
    id("watchaudio.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
        }
    }
}
