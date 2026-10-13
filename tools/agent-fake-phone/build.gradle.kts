// A phone for the agent bridge's end-to-end test (#183): the shared tool logic (shared/agent) over a
// temporary SQLite store with demo data, behind a JVM mutual-TLS client like the Android app's
// SSLSocket. tools/agent-bridge/e2e drives it together with the bridge CLI (CI agent-bridge.yml).
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(projects.shared.agent)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)
}

application {
    mainClass.set("app.watchaudio.fakephone.FakePhoneKt")
    applicationName = "agent-fake-phone"
    // Japanese output must survive a POSIX locale (e.g. the cloud container).
    applicationDefaultJvmArgs = listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}
