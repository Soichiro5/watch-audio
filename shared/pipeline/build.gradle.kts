plugins {
    id("watchaudio.kmp.sqlite-tests")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.shared.db)
            api(projects.shared.platform)
            api(projects.shared.protocol)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
