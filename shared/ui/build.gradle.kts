plugins {
    // SQLite-backed tests in src/sqliteTest also run on the iOS simulator (#14).
    id("watchaudio.kmp.sqlite-tests-jvm-ios")
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
            implementation(projects.shared.protocol)
            implementation(projects.shared.platform)
            // AppServices exposes LocalStore, so hosts need shared/db on their classpath too.
            api(projects.shared.db)
            // AppServices exposes StorageManager.
            api(projects.shared.pipeline)

            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            // System back closes in-app screens such as the licenses (#167); already pulled in by compose.ui.
            implementation(libs.compose.ui.backhandler)
            implementation(libs.compose.material3)
            implementation(libs.compose.lifecycle.viewmodel)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(projects.shared.testing)
        }
        iosTest.dependencies {
            // Compose UI on the iOS simulator over the real IosAppServices player (#19, #25).
            implementation(libs.compose.ui.test)
        }
        jvmTest.dependencies {
            // Headless Compose UI smoke tests (Skia for the host OS).
            implementation(libs.compose.ui.test)
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing) // Dispatchers.Main for view models
        }
    }
}
