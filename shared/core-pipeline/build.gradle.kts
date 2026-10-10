plugins {
    id("watchaudio.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.shared.coreDb)
            api(projects.shared.corePlatform)
        }
    }
}
