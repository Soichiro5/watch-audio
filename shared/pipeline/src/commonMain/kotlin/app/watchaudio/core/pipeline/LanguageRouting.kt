package app.watchaudio.core.pipeline

/**
 * English transcription next to Japanese (#81, `docs/research/english-asr.md`).
 *
 * The Japanese model ([AsrModelSpec.JA_REAZONSPEECH]) cannot recognize English: for English speech
 * it writes next to nothing (a 「はい」 or 「そう」 for a ten-second sentence). The multilingual model
 * ([AsrModelSpec.SENSE_VOICE]) detects the language of what it hears but is much less accurate on
 * Japanese conversation. So with English on, each speech span is recognized by the primary
 * (Japanese) model first; only when its text is suspiciously short for the span
 * ([suspectsOtherLanguage]) does the multilingual model hear it too, and its text is kept when
 * [keeps] it (it detected one of [languages] and wrote mostly Latin letters). Japanese speech thus
 * costs the multilingual model only on short or sparse spans. A span that mixes both languages is
 * recognized as one of them (a known limitation).
 */
data class LanguageRouting(
    /** BCP 47 languages whose text is taken from the multilingual model. */
    val languages: Set<String> = setOf("en"),
    /**
     * Least share of Latin letters among the letters of the multilingual model's text for it to be
     * kept; guards against a Japanese span tagged as English but written in kana or kanji.
     */
    val minLatinShare: Float = 0.5f,
    /**
     * The multilingual model hears a span only when the primary model wrote fewer letters and
     * digits than this per second of the span (padding included); infinity runs it on every span.
     */
    val maxPrimaryCharsPerSecond: Float = 1.5f,
) {
    /**
     * The recognition to store for one span of [seconds]: [primary]'s, unless it is suspiciously
     * short ([suspectsOtherLanguage]) and [detect] (the multilingual model, which states
     * [Recognition.lang]) heard one of [languages] ([keeps]).
     */
    inline fun route(seconds: Float, primary: () -> Recognition, detect: () -> Recognition): Recognition {
        val main = primary()
        if (!suspectsOtherLanguage(main, seconds)) return main
        val detected = detect()
        return if (keeps(detected)) detected else main
    }

    /** True if [primary]'s text is too short for a span of [seconds] to be sure it was Japanese. */
    fun suspectsOtherLanguage(primary: Recognition, seconds: Float): Boolean =
        FalsePositiveFilter.textLength(primary.text) < maxPrimaryCharsPerSecond * seconds

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
         * Engine id stored with segments transcribed by [primary] with [detector] routed in
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
