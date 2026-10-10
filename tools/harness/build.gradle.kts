plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(projects.shared.corePipeline)
    implementation(libs.sherpa.onnx.jvm)
    runtimeOnly(libs.sherpa.onnx.native.linux.x64)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlin.test)
}

application {
    mainClass.set("app.watchaudio.harness.HarnessMainKt")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    systemProperty("watchaudio.models", rootProject.file("tools/harness/models").absolutePath)
}

tasks.test {
    // Models are large and downloaded separately (tools/harness/download-models.sh).
    systemProperty("watchaudio.models", rootProject.file("tools/harness/models").absolutePath)
    // CI passes -PrequireModels=true so a missing model fails instead of skipping.
    systemProperty("watchaudio.requireModels", providers.gradleProperty("requireModels").getOrElse("false"))
    testLogging { showStandardStreams = true }
    // The end-to-end test decodes several minutes of audio.
    maxHeapSize = "2g"
}
