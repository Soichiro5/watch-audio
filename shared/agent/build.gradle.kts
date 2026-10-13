// Agent access (#182, docs/research/agent-access.md): the read-only MCP tools a paired PC's bridge
// (tools/agent-bridge) may call, their scope and limits, the access log, and the wire format
// between the phone and the bridge. Transport (TLS sockets) and key storage live in the apps.
plugins {
    id("watchaudio.kmp.sqlite-tests")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.shared.db)
            api(projects.shared.pipeline)
            api(projects.shared.platform)
            api(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(projects.shared.testing)
        }
    }
}
