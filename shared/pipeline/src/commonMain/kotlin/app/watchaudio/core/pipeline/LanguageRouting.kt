package app.watchaudio.core.pipeline

/**
 * English transcription next to Japanese (#81, `docs/research/english-asr.md`).
 *
 * The Japanese model ([AsrModelSpec.JA_REAZONSPEECH]) cannot recognize English: for English speech
 * it writes next to nothing (a 「はい」 or 「そう」 for a ten-second sentence) or katakana gibberish
 * (「JVキャンストレーション」). The multilingual model
 * ([AsrModelSpec.SENSE_VOICE]) detects the language of what it hears but is much less accurate on
 * Japanese conversation. So with English on, each speech span is recognized by the primary
 * (Japanese) model first; only when its text is suspiciously short for the span
 * ([suspectsOtherLanguage]) does the multilingual model hear it too, and its text is kept when
 * [keeps] it (it detected one of [languages] and wrote at least a few words in (almost) only Latin letters). Japanese speech thus
 * costs the multilingual model only on short or sparse spans. A span that mixes both languages is
 * recognized as one of them (a known limitation).
 */
data class LanguageRouting(
    /** BCP 47 languages whose text is taken from the multilingual model. */
    val languages: Set<String> = setOf("en"),
    /**
     * Least share of Latin letters among the letters of the multilingual model's text for it to be
     * kept; guards against a Japanese span tagged as English but written partly in kana or kanji
     * (「どもお願 guys.」). Real English output is all Latin.
     */
    val minLatinShare: Float = 0.9f,
    /**
     * Least words in the multilingual model's text for it to be kept: one- and two-word results
     * (「The.」「Yeah.」) are mostly Japanese backchannels or noise heard as English, and the loss of
     * a real short English reply is small.
     */
    val minWords: Int = 3,
    /**
     * The multilingual model hears a span only when the primary model wrote fewer letters and
     * digits than this per second of the span (padding included); infinity runs it on every span.
     */
    val maxPrimaryCharsPerSecond: Float = 1.5f,
    /**
     * The multilingual model also hears a span whose primary text is at least this share katakana
     * and Latin letters (the Japanese model writes English as katakana); above 1 never.
     */
    val minForeignScriptShare: Float = 0.8f,
    /**
     * Least confidence ([Recognition.confidence]) of the multilingual model's text for it to be
     * kept, where the model states one. For a second model that cannot tell the language (iOS: an
     * English-only SpeechTranscriber, [SegmentRouting]) this stands in for the language check:
     * Japanese speech heard as English comes out as English words of low confidence. 0 keeps any.
     */
    val minConfidence: Float = 0f,
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

    /**
     * True if [primary]'s text is too short for a span of [seconds], or written mostly in katakana
     * and Latin letters, to be sure the span was Japanese.
     */
    fun suspectsOtherLanguage(primary: Recognition, seconds: Float): Boolean {
        if (FalsePositiveFilter.textLength(primary.text) < maxPrimaryCharsPerSecond * seconds) return true
        val letters = primary.text.filter { it.isLetter() }
        return letters.isNotEmpty() && letters.count(::isForeignScript) >= minForeignScriptShare * letters.length
    }

    /**
     * True if [detected] is in one of [languages], is not less confident than [minConfidence], has
     * at least [minWords] words and is written (almost) only in Latin letters.
     */
    fun keeps(detected: Recognition): Boolean {
        if (detected.lang !in languages) return false
        if ((detected.confidence ?: 1f) < minConfidence) return false
        if (detected.text.split(' ').count { word -> word.any { it.isLetterOrDigit() } } < minWords) return false
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
        fun engineId(primary: AsrModelSpec, detector: AsrModelSpec): String = engineId(primary.engineId, detector.engineId)

        /** Engine id of segments transcribed by the [primary] engine with the [detector] engine routed in. */
        fun engineId(primary: String, detector: String): String = "$primary+$detector"
    }
}

/** Katakana (full and half width, with 「ー」) or a Latin letter: how Japanese text spells foreign words. */
private fun isForeignScript(c: Char): Boolean =
    c in '\u30A0'..'\u30FF' || c in '\uFF66'..'\uFF9F' || c in 'a'..'z' || c in 'A'..'Z'

/**
 * The BCP 47 language of a sherpa-onnx SenseVoice language tag (`<|en|>` → `en`), or null for no
 * language (`<|nospeech|>`, blank or unknown).
 */
fun senseVoiceLanguage(tag: String?): String? {
    val code = tag?.trim()?.removePrefix("<|")?.removeSuffix("|>") ?: return null
    return code.takeIf { it in SENSE_VOICE_LANGUAGES }
}

private val SENSE_VOICE_LANGUAGES = setOf("zh", "en", "ja", "ko", "yue")
