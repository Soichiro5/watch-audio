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

// Shared Kotlin Multiplatform logic (phone: Android + iOS; model and protocol also used by Wear OS)
include(":shared:model")
include(":shared:protocol")
include(":shared:platform")
include(":shared:db")
include(":shared:pipeline")

// Phone UI (Compose Multiplatform) and app shells
include(":shared:ui")
include(":apps:android")
include(":apps:wear")

// Device-free verification on Linux/CI (#44)
include(":tools:harness")
