plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "app.watchaudio.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        // Must match wearApp's applicationId: the Wearable Data Layer only pairs apps
        // with the same package name and signing key.
        applicationId = "app.watchaudio"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Real phones (arm64) and the emulator (x86_64); sherpa-onnx ships ~30 MB per ABI.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    packaging {
        jniLibs {
            // sherpa-onnx's C/C++ API libraries; the Kotlin API only loads the JNI library.
            excludes += listOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(projects.composeApp)
    implementation(projects.shared.coreProtocol)
    implementation(projects.shared.coreDb)
    implementation(projects.shared.corePipeline)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    // play-services pulls in fragment 1.1; the Activity Result API needs 1.3+.
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.play.services.wearable)
    implementation(libs.sherpa.onnx.android)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)

    // Emulator checks (emulator.yml): real playback and the search -> play flow (#19, #25),
    // on-device ASR and the model wait (#22), resuming after a kill and the charging job (#20),
    // the gap alert notification (#43) and the refused permission (#32).
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.kotlin.test.junit)
}
