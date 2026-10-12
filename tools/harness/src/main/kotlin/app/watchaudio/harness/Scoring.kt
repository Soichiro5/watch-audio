package app.watchaudio.harness

import java.text.Normalizer

/**
 * A stored transcript segment, with times relative to the start of the recording, and its
 * language when the transcriber said (`ja`, `en`, ...).
 */
data class Hypothesis(val startMs: Long, val endMs: Long, val text: String, val lang: String? = null)

/**
 * Text normalization for Japanese CER: NFKC, lower case, punctuation/spaces/symbols removed, and
 * numbers written with kanji converted to Arabic digits (the ASR writes 「三千四百八十円」 where
 * the ground truth has 「3480円」). Other notation differences (kana vs kanji) count as errors.
 */
object CerText {
    private val kanjiDigits = mapOf(
        '〇' to 0, '零' to 0, '一' to 1, '二' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    )
    private val smallUnits = mapOf('十' to 10L, '百' to 100L, '千' to 1000L)
    private val largeUnits = mapOf('万' to 10_000L, '億' to 100_000_000L)

    fun normalize(text: String): String {
        val nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase()
        return numeralsToArabic(nfkc).filter { it.isKept() }
    }

    private fun Char.isKept(): Boolean = when (Character.getType(this).toByte()) {
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
        Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
        Character.OTHER_PUNCTUATION, Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR,
        Character.PARAGRAPH_SEPARATOR, Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL,
        Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL, Character.CONTROL, Character.FORMAT,
        Character.PRIVATE_USE, Character.SURROGATE, Character.UNASSIGNED,
        -> false
        else -> true
    }

    private fun Char.isNumeral() = this in '0'..'9' || this in kanjiDigits || this in smallUnits || this in largeUnits

    /**
     * Rewrites each run of numerals. Runs without unit characters are read digit by digit
     * (「〇九〇」→「090」); runs with units are evaluated (「1万2800」「千二百」→「12800」「1200」).
     */
    fun numeralsToArabic(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            if (!text[i].isNumeral()) {
                out.append(text[i++])
                continue
            }
            val start = i
            while (i < text.length && text[i].isNumeral()) i++
            val run = text.substring(start, i)
            if (run.none { it in smallUnits || it in largeUnits }) {
                run.forEach { out.append(kanjiDigits[it] ?: (it - '0')) }
            } else {
                out.append(valueOf(run))
            }
        }
        return out.toString()
    }

    private fun valueOf(run: String): Long {
        var total = 0L
        var section = 0L
        var current = 0L
        for (c in run) {
            val digit = kanjiDigits[c] ?: if (c in '0'..'9') c - '0' else null
            when {
                digit != null -> current = current * 10 + digit
                c in smallUnits -> {
                    section += (if (current == 0L) 1 else current) * smallUnits.getValue(c)
                    current = 0
                }
                else -> {
                    val value = section + current
                    total += (if (value == 0L) 1 else value) * largeUnits.getValue(c)
                    section = 0
                    current = 0
                }
            }
        }
        return total + section + current
    }
}

/**
 * Text normalization for English WER, as in the M0-5 spike (`research/asr-benchmark`): NFKC,
 * lower case, hyphens to spaces, everything but letters, digits and spaces removed, split into
 * words. Number notation ("twenty" vs "20") is not unified.
 */
object WerText {
    fun words(text: String): List<String> =
        Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase().replace('-', ' ')
            .filter { it.isLetterOrDigit() || it.isWhitespace() }
            .split(Regex("\\s+")).filter { it.isNotEmpty() }
}

/**
 * Character errors (substitutions + deletions + insertions) of [hyp] against [ref], attributed
 * to the label of the reference character involved (insertions: the preceding one).
 */
fun alignErrors(ref: List<Pair<Char, String>>, hyp: String): Map<String, Int> = alignErrors(ref, hyp.toList())

/** [alignErrors] over any tokens, e.g. words for WER. */
fun <T> alignErrors(ref: List<Pair<T, String>>, hyp: List<T>): Map<String, Int> {
    if (ref.isEmpty()) return if (hyp.isEmpty()) emptyMap() else mapOf("" to hyp.size)
    val n = ref.size
    val m = hyp.size
    val width = m + 1
    val d = IntArray((n + 1) * width)
    for (j in 0..m) d[j] = j
    for (i in 1..n) {
        d[i * width] = i
        val r = ref[i - 1].first
        for (j in 1..m) {
            val sub = d[(i - 1) * width + j - 1] + if (r == hyp[j - 1]) 0 else 1
            d[i * width + j] = minOf(sub, d[(i - 1) * width + j] + 1, d[i * width + j - 1] + 1)
        }
    }
    val errors = mutableMapOf<String, Int>()
    fun count(i: Int) = errors.merge(ref[maxOf(i, 0)].second, 1, Int::plus)
    var i = n
    var j = m
    while (i > 0 || j > 0) {
        val here = d[i * width + j]
        when {
            i > 0 && j > 0 && here == d[(i - 1) * width + j - 1] + (if (ref[i - 1].first == hyp[j - 1]) 0 else 1) -> {
                if (ref[i - 1].first != hyp[j - 1]) count(i - 1)
                i--
                j--
            }
            i > 0 && here == d[(i - 1) * width + j] + 1 -> {
                count(i - 1)
                i--
            }
            else -> {
                count(i - 1)
                j--
            }
        }
    }
    return errors
}

/** One aligned conversation: what was said, what was transcribed, and the errors. */
data class GroupScore(val id: String, val reference: String, val hypothesis: String, val errors: Int)

/**
 * Accuracy of one recording's transcript against its ground truth. Japanese (and any non-English)
 * groups are scored by characters (CER), English groups by words (WER).
 */
data class RecordingScore(
    val utterancesByRole: Map<String, Int>,
    val missedByRole: Map<String, Int>,
    val refCharsByRole: Map<String, Int>,
    val errorsByRole: Map<String, Int>,
    /** Characters transcribed where nobody spoke (noise, rubbing): counted as insertions. */
    val outsideChars: Int,
    val groups: List<GroupScore>,
    val outside: List<Hypothesis>,
    /** Words of English groups, and their word errors, by role. */
    val refWordsByRole: Map<String, Int> = emptyMap(),
    val wordErrorsByRole: Map<String, Int> = emptyMap(),
    /**
     * Per spoken language: utterances whose most overlapping segment states a language, and how
     * many of them state another one (e.g. Japanese stored as English).
     */
    val languageChecked: Map<String, Int> = emptyMap(),
    val languageErrors: Map<String, Int> = emptyMap(),
) {
    val utterances get() = utterancesByRole.values.sum()
    val missed get() = missedByRole.values.sum()
    val refChars get() = refCharsByRole.values.sum()
    val errors get() = errorsByRole.values.sum() + outsideChars

    /** CER including text transcribed outside any conversation. */
    val cer get() = errors.toDouble() / refChars.coerceAtLeast(1)

    /** [cer], or null for a recording without character-scored (non-English) speech. */
    val cerOrNull: Double? get() = if (refChars == 0) null else cer

    /** WER of the English groups, or null without English speech. */
    val wer: Double? get() = refWordsByRole.values.sum().takeIf { it > 0 }?.let { wordErrorsByRole.values.sum().toDouble() / it }
    val missRate get() = missed.toDouble() / utterances.coerceAtLeast(1)

    fun cerOf(roles: Set<String>): Double? {
        val chars = roles.sumOf { refCharsByRole[it] ?: 0 }
        return if (chars == 0) null else roles.sumOf { errorsByRole[it] ?: 0 }.toDouble() / chars
    }
}

/**
 * Scores [hypotheses] against [truth]:
 * - CER (WER for English groups) is aligned per group (conversation): the group's utterances in
 *   time order against the transcript segments that overlap the group's time span (± [marginMs])
 *   the most. Segments overlapping no group are errors too ([RecordingScore.outsideChars]).
 * - An utterance is missed when no transcript segment overlaps it in time.
 * - An utterance's language is wrong when its most overlapping segment states another language.
 */
fun scoreRecording(truth: List<TruthSegment>, hypotheses: List<Hypothesis>, marginMs: Long = 1000): RecordingScore {
    val groups = truth.groupBy { it.group }.mapValues { (_, segs) -> segs.sortedBy { it.startMs } }
    val windows = groups.mapValues { (_, segs) -> (segs.minOf { it.startMs } - marginMs) to (segs.maxOf { it.endMs } + marginMs) }
    val assigned = mutableMapOf<String, MutableList<Hypothesis>>()
    val outside = mutableListOf<Hypothesis>()
    for (h in hypotheses.sortedBy { it.startMs }) {
        val best = windows.maxByOrNull { (_, w) -> overlap(h.startMs, h.endMs, w.first, w.second) }
        if (best == null || overlap(h.startMs, h.endMs, best.value.first, best.value.second) <= 0) {
            outside += h
        } else {
            assigned.getOrPut(best.key) { mutableListOf() } += h
        }
    }
    val refChars = mutableMapOf<String, Int>()
    val errors = mutableMapOf<String, Int>()
    val refWords = mutableMapOf<String, Int>()
    val wordErrors = mutableMapOf<String, Int>()
    val groupScores = groups.map { (id, segs) ->
        val texts = assigned[id].orEmpty().map { it.text }
        val errs = if (segs.first().lang == ENGLISH) {
            val ref = segs.flatMap { s -> WerText.words(s.text).map { it to s.role } }
            ref.forEach { refWords.merge(it.second, 1, Int::plus) }
            alignErrors(ref, texts.flatMap(WerText::words)).onEach { (role, e) -> wordErrors.merge(role, e, Int::plus) }
        } else {
            val ref = segs.flatMap { s -> CerText.normalize(s.text).map { it to s.role } }
            ref.forEach { refChars.merge(it.second, 1, Int::plus) }
            alignErrors(ref, texts.joinToString("") { CerText.normalize(it) }).onEach { (role, e) -> errors.merge(role, e, Int::plus) }
        }
        GroupScore(id, segs.joinToString(" ") { it.text }, texts.joinToString(" "), errs.values.sum())
    }
    val utterances = truth.groupingBy { it.role }.eachCount()
    val missed = truth.filter { t -> hypotheses.none { overlap(t.startMs, t.endMs, it.startMs, it.endMs) > 0 } }
        .groupingBy { it.role }.eachCount()
    val languageChecked = mutableMapOf<String, Int>()
    val languageErrors = mutableMapOf<String, Int>()
    for (t in truth) {
        val best = hypotheses.maxByOrNull { overlap(t.startMs, t.endMs, it.startMs, it.endMs) } ?: continue
        val lang = best.lang ?: continue
        if (overlap(t.startMs, t.endMs, best.startMs, best.endMs) <= 0) continue
        languageChecked.merge(t.lang, 1, Int::plus)
        if (lang != t.lang) languageErrors.merge(t.lang, 1, Int::plus)
    }
    return RecordingScore(
        utterancesByRole = utterances,
        missedByRole = missed,
        refCharsByRole = refChars,
        errorsByRole = errors,
        outsideChars = outside.sumOf { CerText.normalize(it.text).length },
        groups = groupScores,
        outside = outside,
        refWordsByRole = refWords,
        wordErrorsByRole = wordErrors,
        languageChecked = languageChecked,
        languageErrors = languageErrors,
    )
}

/** The language of English ground truth ([TruthSegment.lang]), scored by words. */
const val ENGLISH = "en"

private fun overlap(a0: Long, a1: Long, b0: Long, b1: Long) = minOf(a1, b1) - maxOf(a0, b0)
