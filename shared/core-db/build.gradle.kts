plugins {
    id("watchaudio.kmp.sqlite-tests")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.shared.coreModel)
            api(projects.shared.coreProtocol)
            api(projects.shared.corePlatform)
            api(libs.androidx.sqlite)
            implementation(libs.androidx.sqlite.bundled)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// Search latency on a year of transcripts (#23). Kept out of jvmTest because it loads
// ~500k segments; CI runs it in its own step (android.yml).
val benchmarkClass = "app.watchaudio.core.db.SearchBenchmark"

tasks.named<Test>("jvmTest") {
    filter { excludeTestsMatching(benchmarkClass) }
}

tasks.register<Test>("jvmBenchmark") {
    description = "Measures search latency on ~500k generated transcript segments (#23)."
    group = "verification"
    val compilation = kotlin.jvm().compilations.getByName("test")
    testClassesDirs = compilation.output.classesDirs
    classpath = files(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
    useJUnit()
    filter { includeTestsMatching(benchmarkClass) }
    maxHeapSize = "1g"
    systemProperty("benchmark.report", layout.buildDirectory.file("reports/search-benchmark/summary.md").get().asFile.path)
    providers.gradleProperty("benchmark.segments").orNull?.let { systemProperty("benchmark.segments", it) }
    providers.gradleProperty("benchmark.rounds").orNull?.let { systemProperty("benchmark.rounds", it) }
    testLogging {
        showStandardStreams = true
        events("passed", "failed")
    }
    outputs.upToDateWhen { false }
}
