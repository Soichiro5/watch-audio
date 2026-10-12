plugins {
    id("watchaudio.kmp.sqlite-tests")
    // The export format (core/export, #155) is JSON through kotlinx.serialization, as in shared/protocol.
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.shared.db)
            api(projects.shared.platform)
            api(projects.shared.protocol)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(projects.shared.testing)
        }
    }
}
