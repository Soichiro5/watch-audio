package app.watchaudio.harness

import app.watchaudio.core.pipeline.AsrModelSpec
import app.watchaudio.core.pipeline.FalsePositiveFilter
import app.watchaudio.core.pipeline.LanguageRouting
import app.watchaudio.core.pipeline.VadSettings
import app.watchaudio.core.protocol.AudioCodec
import app.watchaudio.core.protocol.RecordingFormat
import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class EvaluateArgsTest {
    @Test
    fun withoutOptionsTheAppSettingsAreEvaluated() {
        val args = EvaluateArgs.parse(listOf("m.json", "out", "office", "cafe"))
        assertEquals(EvaluateArgs(File("m.json"), File("out"), setOf("office", "cafe"), VadSettings.DEFAULT), args)
    }

    @Test
    fun vadOptionsOverrideSettingsByName() {
        val args = EvaluateArgs.parse(
            listOf("m.json", "--vad", "threshold=0.3,negThreshold=0.2", "out", "--vad=minSilenceSeconds=1", "day", "--vad", "noiseFloorPercentile=20"),
        )
        assertEquals(File("out"), args.workDir)
        assertEquals(setOf("day"), args.recordings)
        assertEquals(
            VadSettings(threshold = 0.3f, negThreshold = 0.2f, minSilenceSeconds = 1f, noiseFloorPercentile = 20),
            args.vad,
        )
    }

    @Test
    fun everySettingCanBeOverridden() {
        val names = VadSettings::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()
        assertEquals(names, VAD_OPTIONS.keys)
        // Each option sets its own property (and only that one).
        for ((name, set) in VAD_OPTIONS) {
            val changed = set(VadSettings.DEFAULT, "3")
            assertEquals(1, names.count { field -> property(changed, field) != property(VadSettings.DEFAULT, field) }, name)
            assertEquals(3.0, (property(changed, name) as Number).toDouble(), name)
        }
    }

    @Test
    fun theAppFilterIsOnUnlessTurnedOff() {
        assertEquals(FalsePositiveFilter.DEFAULT, EvaluateArgs.parse(listOf("m.json", "out")).filter)
        assertNull(EvaluateArgs.parse(listOf("m.json", "out", "--filter", "off", "day")).filter)
        assertNull(EvaluateArgs.parse(listOf("m.json", "out", "--filter=off")).filter)
    }

    @Test
    fun filterOptionsOverrideTheFilterByName() {
        val args = EvaluateArgs.parse(
            listOf("m.json", "out", "--filter", "minLevelAboveNoiseDb=5,shortTextMaxChars=2", "--filter=shortTextMinSpeechProbability=0.1", "day"),
        )
        assertEquals(FalsePositiveFilter(minLevelAboveNoiseDb = 5f, shortTextMaxChars = 2, shortTextMinSpeechProbability = 0.1f), args.filter)
        assertEquals(setOf("day"), args.recordings)
    }

    @Test
    fun everyFilterPropertyCanBeOverridden() {
        val names = FalsePositiveFilter::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()
        assertEquals(names, FILTER_OPTIONS.keys)
        for ((name, set) in FILTER_OPTIONS) {
            val changed = set(FalsePositiveFilter.DEFAULT, "3")
            val field = FalsePositiveFilter::class.java.getDeclaredField(name).apply { isAccessible = true }
            assertEquals(3.0, (field.get(changed) as Number).toDouble(), name)
        }
    }

    @Test
    fun formatOptionsOverrideTheRecordingFormat() {
        assertEquals(RecordingFormat.CURRENT, EvaluateArgs.parse(listOf("m.json", "out")).format)
        val args = EvaluateArgs.parse(listOf("m.json", "out", "--format", "codec=opus,bitrateBps=16000", "--format=chunkDurationMs=60000", "day"))
        assertEquals(RecordingFormat.CURRENT.copy(codec = AudioCodec.OPUS, bitrateBps = 16_000, chunkDurationMs = 60_000), args.format)
        assertEquals(setOf("day"), args.recordings)
        val names = RecordingFormat::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()
        assertEquals(names, FORMAT_OPTIONS.keys)
    }

    @Test
    fun englishIsOffUnlessTurnedOnAndRoutingCanBeOverridden() {
        assertNull(EvaluateArgs.parse(listOf("m.json", "out")).english)
        val on = EvaluateArgs.parse(listOf("m.json", "out", "--english", "on", "mixed_wrist"))
        assertEquals(AsrModelSpec.SENSE_VOICE, on.english)
        assertEquals(LanguageRouting.DEFAULT, on.routing)
        assertEquals(setOf("mixed_wrist"), on.recordings)
        val tuned = EvaluateArgs.parse(listOf("m.json", "out", "--english=maxPrimaryCharsPerSecond=Infinity,languages=en+zh"))
        assertEquals(AsrModelSpec.SENSE_VOICE, tuned.english)
        assertEquals(LanguageRouting(languages = setOf("en", "zh"), maxPrimaryCharsPerSecond = Float.POSITIVE_INFINITY), tuned.routing)
        assertNull(EvaluateArgs.parse(listOf("m.json", "out", "--english", "on", "--english", "off")).english)
        val names = LanguageRouting::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()
        assertEquals(names, ROUTING_OPTIONS.keys)
    }

    @Test
    fun badInputIsReported() {
        assertFailsWith<IllegalArgumentException> { EvaluateArgs.parse(listOf("m.json")) }
        assertFailsWith<IllegalArgumentException> { EvaluateArgs.parse(listOf("m.json", "out", "--vad")) }
        assertFailsWith<IllegalArgumentException> { EvaluateArgs.parse(listOf("m.json", "out", "--vad", "loud=1")) }
        assertFailsWith<IllegalArgumentException> { EvaluateArgs.parse(listOf("m.json", "out", "--vad", "threshold=high")) }
        assertFailsWith<IllegalArgumentException> { EvaluateArgs.parse(listOf("m.json", "out", "--vad", "threshold")) }
        assertFailsWith<IllegalArgumentException> { EvaluateArgs.parse(listOf("m.json", "out", "--vda", "threshold=1")) }
        assertFailsWith<IllegalArgumentException> { EvaluateArgs.parse(listOf("m.json", "out", "--filter")) }
        assertFailsWith<IllegalArgumentException> { EvaluateArgs.parse(listOf("m.json", "out", "--filter", "threshold=1")) }
        assertFailsWith<IllegalArgumentException> { EvaluateArgs.parse(listOf("m.json", "out", "--filter", "shortTextMaxChars=two")) }
        assertFailsWith<IllegalArgumentException> { EvaluateArgs.parse(listOf("m.json", "out", "--format", "codec=mp3")) }
        assertFailsWith<IllegalArgumentException> { EvaluateArgs.parse(listOf("m.json", "out", "--format", "bitrateBps=0")) }
        assertFailsWith<IllegalArgumentException> { EvaluateArgs.parse(listOf("m.json", "out", "--format")) }
        assertFailsWith<IllegalArgumentException> { EvaluateArgs.parse(listOf("m.json", "out", "--english")) }
        assertFailsWith<IllegalArgumentException> { EvaluateArgs.parse(listOf("m.json", "out", "--english", "yes")) }
    }

    private fun property(settings: VadSettings, name: String): Any =
        VadSettings::class.java.getDeclaredField(name).apply { isAccessible = true }.get(settings)
}
