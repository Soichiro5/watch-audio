// Test setup shared by the test source sets of shared/* modules (#94 R7): temporary
// directories and chunk manifests. Test-only: never a dependency of a main source set.
plugins {
    id("watchaudio.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.shared.protocol)
            api(libs.kotlinx.io.core)
        }
    }
}
