plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
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
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
}

application {
    mainClass.set("app.watchaudio.harness.HarnessMainKt")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    systemProperty("watchaudio.models", rootProject.file("tools/harness/models").absolutePath)
    // `evaluate` decodes up to an hour of audio at once (tools/test-audio).
    maxHeapSize = "2g"
    // Japanese output must survive a POSIX locale (e.g. the cloud container).
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

tasks.test {
    // Models are large and downloaded separately (tools/harness/download-models.sh).
    systemProperty("watchaudio.models", rootProject.file("tools/harness/models").absolutePath)
    // Small committed sample of the test audio set (tools/test-audio/fixtures).
    systemProperty("watchaudio.fixtures", rootProject.file("tools/test-audio/fixtures").absolutePath)
    // CI passes -PrequireModels=true so a missing model fails instead of skipping.
    systemProperty("watchaudio.requireModels", providers.gradleProperty("requireModels").getOrElse("false"))
    testLogging { showStandardStreams = true }
    // The end-to-end test decodes several minutes of audio.
    maxHeapSize = "2g"
}
