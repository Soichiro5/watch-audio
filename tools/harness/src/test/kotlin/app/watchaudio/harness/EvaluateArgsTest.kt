package app.watchaudio.harness

import app.watchaudio.core.pipeline.VadSettings
import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
    fun badInputIsReported() {
        assertFailsWith<IllegalArgumentException> { EvaluateArgs.parse(listOf("m.json")) }
        assertFailsWith<IllegalArgumentException> { EvaluateArgs.parse(listOf("m.json", "out", "--vad")) }
        assertFailsWith<IllegalArgumentException> { EvaluateArgs.parse(listOf("m.json", "out", "--vad", "loud=1")) }
        assertFailsWith<IllegalArgumentException> { EvaluateArgs.parse(listOf("m.json", "out", "--vad", "threshold=high")) }
        assertFailsWith<IllegalArgumentException> { EvaluateArgs.parse(listOf("m.json", "out", "--vad", "threshold")) }
        assertFailsWith<IllegalArgumentException> { EvaluateArgs.parse(listOf("m.json", "out", "--vda", "threshold=1")) }
    }

    private fun property(settings: VadSettings, name: String): Any =
        VadSettings::class.java.getDeclaredField(name).apply { isAccessible = true }.get(settings)
}
