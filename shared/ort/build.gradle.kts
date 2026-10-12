import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// ONNX Runtime's C API through JNA, for the embedding model of semantic search (#24): used by the
// Android app and the Linux harness (a plain JVM library; Android consumes it like any jar). It
// binds to the libonnxruntime.so that sherpa-onnx already ships instead of adding ONNX Runtime's
// own Java package, whose native library clashes with sherpa-onnx's (see OrtApi and
// docs/platforms/android-phone.md).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
    // Android's D8 and the KMP modules' Android targets use Java 17 bytecode.
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // Consumers bring JNA: the jar on the JVM, the AAR (with libjnidispatch.so per ABI) on Android.
    compileOnly(libs.jna)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.jna)
    // ONNX Runtime 1.28.2 for Linux, as packaged by sherpa-onnx: the same build family the app loads.
    testRuntimeOnly(libs.sherpa.onnx.native.linux.x64)
}

tasks.test {
    // A tiny model made by testdata/make_test_model.py.
    systemProperty("watchaudio.ortTestModel", file("testdata/scale.onnx").absolutePath)
}
