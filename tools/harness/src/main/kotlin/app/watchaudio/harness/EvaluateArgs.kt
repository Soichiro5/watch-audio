package app.watchaudio.harness

import app.watchaudio.core.pipeline.FalsePositiveFilter
import app.watchaudio.core.pipeline.VadSettings
import java.io.File

/**
 * Arguments of the `evaluate` command:
 * `<manifest.json> <workDir> [--vad name=value[,name=value...]]... [--filter off|name=value[,...]]... [recordingIds...]`.
 *
 * `--vad` overrides [VadSettings] properties by name (e.g. `--vad threshold=0.3,minSilenceSeconds=1`),
 * so that settings can be compared on the test audio set without recompiling. Likewise `--filter`
 * overrides [FalsePositiveFilter] properties, or turns the filter off (`--filter off`: everything
 * the recognizer returns is stored). [filter] is null when it is off.
 */
data class EvaluateArgs(
    val manifest: File,
    val workDir: File,
    val recordings: Set<String>,
    val vad: VadSettings,
    val filter: FalsePositiveFilter? = FalsePositiveFilter.DEFAULT,
) {
    companion object {
        /** Parses [args] (without the command name); throws [IllegalArgumentException] on bad input. */
        fun parse(args: List<String>): EvaluateArgs {
            val positional = mutableListOf<String>()
            var vad = VadSettings.DEFAULT
            var filter: FalsePositiveFilter? = FalsePositiveFilter.DEFAULT
            fun filterOption(value: String) {
                filter = if (value == "off") null else (filter ?: FalsePositiveFilter.DEFAULT).withOverrides(value)
            }
            var index = 0
            while (index < args.size) {
                val arg = args[index++]
                when {
                    arg == "--vad" -> {
                        require(index < args.size) { "--vad needs name=value" }
                        vad = vad.withOverrides(args[index++])
                    }
                    arg.startsWith("--vad=") -> vad = vad.withOverrides(arg.removePrefix("--vad="))
                    arg == "--filter" -> {
                        require(index < args.size) { "--filter needs off or name=value" }
                        filterOption(args[index++])
                    }
                    arg.startsWith("--filter=") -> filterOption(arg.removePrefix("--filter="))
                    arg.startsWith("--") -> throw IllegalArgumentException("unknown option $arg")
                    else -> positional += arg
                }
            }
            require(positional.size >= 2) { "evaluate needs <manifest.json> <workDir>" }
            return EvaluateArgs(File(positional[0]), File(positional[1]), positional.drop(2).toSet(), vad, filter)
        }
    }
}

/**
 * These settings with the comma-separated `name=value` [overrides] applied, names being
 * [VadSettings] property names ([VAD_OPTIONS]).
 */
fun VadSettings.withOverrides(overrides: String): VadSettings = applyOverrides(this, overrides, VAD_OPTIONS, "VAD")

private fun <T> applyOverrides(initial: T, overrides: String, options: Map<String, (T, String) -> T>, kind: String): T =
    overrides.split(',').filter { it.isNotBlank() }.fold(initial) { settings, override ->
        val name = override.substringBefore('=').trim()
        val value = override.substringAfter('=', missingDelimiterValue = "").trim()
        val option = options[name] ?: throw IllegalArgumentException(
            "unknown $kind setting '$name' (one of ${options.keys.joinToString()})",
        )
        require(value.isNotEmpty()) { "$kind setting '$name' needs a value" }
        try {
            option(settings, value)
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException("bad value '$value' for $kind setting '$name'", e)
        }
    }

/** Every [VadSettings] property, by name, with how to set it from a string. */
val VAD_OPTIONS: Map<String, (VadSettings, String) -> VadSettings> = mapOf(
    "windowSize" to { s, v -> s.copy(windowSize = v.toInt()) },
    "threshold" to { s, v -> s.copy(threshold = v.toFloat()) },
    "negThreshold" to { s, v -> s.copy(negThreshold = v.toFloat()) },
    "minSilenceSeconds" to { s, v -> s.copy(minSilenceSeconds = v.toFloat()) },
    "minSpeechSeconds" to { s, v -> s.copy(minSpeechSeconds = v.toFloat()) },
    "startBackWindows" to { s, v -> s.copy(startBackWindows = v.toInt()) },
    "segmentPadSeconds" to { s, v -> s.copy(segmentPadSeconds = v.toFloat()) },
    "maxSegmentSeconds" to { s, v -> s.copy(maxSegmentSeconds = v.toFloat()) },
    "minPauseSeconds" to { s, v -> s.copy(minPauseSeconds = v.toFloat()) },
    "pauseDb" to { s, v -> s.copy(pauseDb = v.toFloat()) },
    "pauseFloorDb" to { s, v -> s.copy(pauseFloorDb = v.toFloat()) },
    "minNoisyPauseSeconds" to { s, v -> s.copy(minNoisyPauseSeconds = v.toFloat()) },
    "pauseMinContrastDb" to { s, v -> s.copy(pauseMinContrastDb = v.toFloat()) },
    "pauseLoudWindowSeconds" to { s, v -> s.copy(pauseLoudWindowSeconds = v.toFloat()) },
    "noiseFloorPercentile" to { s, v -> s.copy(noiseFloorPercentile = v.toInt()) },
    "noiseFloorContextSeconds" to { s, v -> s.copy(noiseFloorContextSeconds = v.toFloat()) },
)

/**
 * This filter with the comma-separated `name=value` [overrides] applied, names being
 * [FalsePositiveFilter] property names ([FILTER_OPTIONS]).
 */
fun FalsePositiveFilter.withOverrides(overrides: String): FalsePositiveFilter =
    applyOverrides(this, overrides, FILTER_OPTIONS, "filter")

/** Every [FalsePositiveFilter] property, by name, with how to set it from a string. */
val FILTER_OPTIONS: Map<String, (FalsePositiveFilter, String) -> FalsePositiveFilter> = mapOf(
    "minLevelAboveNoiseDb" to { f, v -> f.copy(minLevelAboveNoiseDb = v.toFloat()) },
    "shortTextMaxChars" to { f, v -> f.copy(shortTextMaxChars = v.toInt()) },
    "shortTextMinSpeechProbability" to { f, v -> f.copy(shortTextMinSpeechProbability = v.toFloat()) },
)
