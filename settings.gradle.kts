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

// Shared Kotlin Multiplatform logic (phone: Android + iOS; protocol also used by Wear OS)
include(":shared:protocol")
include(":shared:platform")
include(":shared:db")
include(":shared:pipeline")
include(":shared:agent") // agent access: MCP tool logic, scope, access log, phone-bridge wire format (#182)
include(":shared:testing") // test setup shared by the modules' tests
include(":shared:ort") // ONNX Runtime C API through JNA (JVM + Android) for the embedding model (#24)

// Phone UI (Compose Multiplatform) and app shells
include(":shared:ui")
include(":apps:android")
include(":apps:wear")

// Device-free verification on Linux/CI (#44)
include(":tools:harness")
