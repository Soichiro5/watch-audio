plugins {
    id("watchaudio.kmp.sqlite-tests")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.shared.coreDb)
            api(projects.shared.corePlatform)
            api(projects.shared.coreProtocol)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
