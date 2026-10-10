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
            implementation(projects.shared.coreDb)
            implementation(projects.shared.corePipeline)
            implementation(projects.shared.coreSearch)
            implementation(projects.shared.coreAi)

            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            implementation(libs.compose.lifecycle.viewmodel)
        }
    }
}
