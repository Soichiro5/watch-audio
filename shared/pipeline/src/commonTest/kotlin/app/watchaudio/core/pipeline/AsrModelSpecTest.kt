package app.watchaudio.core.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AsrModelSpecTest {
    @Test
    fun theShippedModelIsTheJapaneseReazonSpeechTransducer() {
        val spec = AsrModelSpec.DEFAULT
        assertEquals(AsrModelSpec.JA_REAZONSPEECH, spec)
        // Stored with every segment; changing it splits the archive into two engines.
        assertEquals("sherpa-zipformer-ja-reazonspeech-int8", spec.engineId)
        assertEquals("ja", spec.language)
        assertEquals(RecognizerKind.TRANSDUCER, spec.kind)
        assertEquals(
            "sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01/encoder-epoch-99-avg-1.int8.onnx",
            spec.path(AsrModelFile.ENCODER),
        )
        assertEquals("tokens.txt", spec.fileName(AsrModelFile.TOKENS))
    }

    @Test
    fun aSpecMustNameExactlyTheFilesItsKindNeeds() {
        val files = AsrModelSpec.JA_REAZONSPEECH.files
        assertFailsWith<IllegalArgumentException> {
            AsrModelSpec("m", "e", "ja", RecognizerKind.TRANSDUCER, files - AsrModelFile.JOINER)
        }
    }
}
