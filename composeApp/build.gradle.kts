plugins {
    id("watchaudio.kmp.library")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.shared.coreModel)
            implementation(projects.shared.coreProtocol)
            implementation(projects.shared.corePlatform)
            // AppServices exposes LocalStore, so hosts need core-db on their classpath too.
            api(projects.shared.coreDb)
            // AppServices exposes StorageManager.
            api(projects.shared.corePipeline)
            implementation(projects.shared.coreSearch)
            implementation(projects.shared.coreAi)

            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            implementation(libs.compose.lifecycle.viewmodel)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            // Headless Compose UI smoke tests (Skia for the host OS).
            implementation(libs.compose.ui.test)
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing) // Dispatchers.Main for view models
        }
    }
}
