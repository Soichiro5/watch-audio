rootProject.name = "watch-audio"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // sherpa-onnx (on-device ASR) is only published on JitPack; never look for it elsewhere.
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter { includeGroup("com.github.k2-fsa.sherpa-onnx") }
        }
    }
}

// Shared Kotlin Multiplatform logic (phone: Android + iOS; core-model/core-protocol also used by Wear OS)
include(":shared:core-model")
include(":shared:core-protocol")
include(":shared:core-platform")
include(":shared:core-db")
include(":shared:core-pipeline")
include(":shared:core-search")
include(":shared:core-ai")

// Phone UI (Compose Multiplatform) and app shells
include(":composeApp")
include(":androidApp")
include(":wearApp")

// Device-free verification on Linux/CI (#44)
include(":tools:harness")
