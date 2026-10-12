package app.watchaudio.core.pipeline

/**
 * English transcription next to Japanese (#81, `docs/research/english-asr.md`).
 *
 * The Japanese model ([AsrModelSpec.JA_REAZONSPEECH]) cannot recognize English: it writes English
 * speech as Japanese-sounding nonsense. The multilingual model ([AsrModelSpec.SENSE_VOICE]) detects
 * the language of what it hears but is much less accurate on Japanese conversation. So with English
 * on, the multilingual model hears each speech span first; its text is kept when [keeps] it (it
 * detected one of [languages] and wrote mostly Latin letters), and otherwise the span goes to the
 * primary (Japanese) model as before. A span that mixes both languages is recognized as one of
 * them (a known limitation).
 */
data class LanguageRouting(
    /** BCP 47 languages whose text is taken from the multilingual model. */
    val languages: Set<String> = setOf("en"),
    /**
     * Least share of Latin letters among the letters of the multilingual model's text for it to be
     * kept; guards against a Japanese span tagged as English but written in kana or kanji.
     */
    val minLatinShare: Float = 0.5f,
) {
    /**
     * The recognition to store for one span: [detected] (the multilingual model's, with its
     * [Recognition.lang]) if [keeps] it, else [primary]'s.
     */
    inline fun route(detected: Recognition, primary: () -> Recognition): Recognition =
        if (keeps(detected)) detected else primary()

    /** True if [detected] is in one of [languages] and written mostly in Latin letters. */
    fun keeps(detected: Recognition): Boolean {
        if (detected.lang !in languages) return false
        val letters = detected.text.filter { it.isLetter() }
        if (letters.isEmpty()) return false
        return letters.count { it in 'a'..'z' || it in 'A'..'Z' } >= minLatinShare * letters.length
    }

    companion object {
        /** The routing the apps use. */
        val DEFAULT = LanguageRouting()

        /**
         * Engine id stored with segments transcribed by [primary] with [detector] routed in front
         * ([Transcriber.engineId]); the segment's language says which model wrote it.
         */
        fun engineId(primary: AsrModelSpec, detector: AsrModelSpec): String = "${primary.engineId}+${detector.engineId}"
    }
}

/**
 * The BCP 47 language of a sherpa-onnx SenseVoice language tag (`<|en|>` → `en`), or null for no
 * language (`<|nospeech|>`, blank or unknown).
 */
fun senseVoiceLanguage(tag: String?): String? {
    val code = tag?.trim()?.removePrefix("<|")?.removeSuffix("|>") ?: return null
    return code.takeIf { it in SENSE_VOICE_LANGUAGES }
}

private val SENSE_VOICE_LANGUAGES = setOf("zh", "en", "ja", "ko", "yue")
