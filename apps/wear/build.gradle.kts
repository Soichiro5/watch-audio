plugins {
    // applicationId, SDK levels, version (gradle.properties), Java, Compose, release: build-logic (#138)
    id("watchaudio.android.app")
    // verify<Variant>DebugInput: the debug audio file input never ships in a release build (#57)
    id("watchaudio.android.debug-input-guard")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.watchaudio.wear"

    defaultConfig {
        minSdk = libs.versions.wear.minSdk.get().toInt()
    }
}

dependencies {
    implementation(projects.shared.protocol)
    implementation(projects.shared.platform)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.concurrent.futures)
    implementation(libs.androidx.fragment)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.play.services.wearable)
    implementation(libs.wear.ongoing)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.tooling.preview)
    implementation(libs.wear.tiles)
    implementation(libs.wear.protolayout)
    implementation(libs.wear.protolayout.expression)
    implementation(libs.wear.complications.data.source)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
