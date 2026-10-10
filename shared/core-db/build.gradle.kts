plugins {
    id("watchaudio.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.shared.coreModel)
        }
    }
}
