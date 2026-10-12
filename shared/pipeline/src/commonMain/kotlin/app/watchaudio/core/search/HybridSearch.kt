package app.watchaudio.core.search

import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.db.PassageRecord
import app.watchaudio.core.db.SearchHit
import app.watchaudio.core.db.SegmentRecord
import kotlin.coroutines.cancellation.CancellationException

/** One result of [HybridSearch]. */
data class HybridHit(
    /** The segment to show and to open the day at (a passage's first segment for a meaning-only hit). */
    val segment: SegmentRecord,
    /** The text shown, keyword matches wrapped in `[` `]` (none for a meaning-only hit). */
    val snippet: String,
    /** The keyword search found it (the text contains the query). */
    val byKeyword: Boolean,
    /** The meaning search found it (or the passage it belongs to). */
    val byMeaning: Boolean,
)

data class HybridResults(
    val hits: List<HybridHit>,
    /** The keyword search hit its limit; there may be more matches. */
    val keywordLimited: Boolean,
)

/**
 * Keyword search plus search by meaning (#24): the FTS5 keyword hits (newest first, as before) and
 * the passages closest in meaning ([SemanticSearch]) are fused by rank with [Rrf]. A keyword hit
 * inside a passage the meaning search also found counts as the same result (the first such hit
 * per passage), so text that matches both ways comes first. Without [SemanticSearch] (or when it
 * fails) the result is exactly the keyword search.
 *
 * Meaning hits below [MIN_MEANING_SCORE] are dropped: the brute-force search always returns its
 * nearest passages, even when nothing is related.
 */
object HybridSearch {
    /** Meaning hits kept per query. */
    const val MEANING_LIMIT = 30

    /**
     * Lowest similarity (≈ cosine of the ruri-v3 vectors) a meaning hit needs. Unrelated
     * conversation passages score about 0.6–0.75 with ruri-v3-30m; see docs/research/m0-6 §9.
     */
    const val MIN_MEANING_SCORE = 0.78f

    /** Longest text shown for a meaning-only hit. */
    const val PASSAGE_SNIPPET_CHARS = 80

    suspend fun search(
        store: LocalStore,
        semantic: SemanticSearch?,
        query: String,
        keywordLimit: Int = 100,
        meaningLimit: Int = MEANING_LIMIT,
        minScore: Float = MIN_MEANING_SCORE,
    ): HybridResults {
        val q = query.trim()
        if (q.isEmpty()) return HybridResults(emptyList(), keywordLimited = false)
        val keyword = store.read { search(q, keywordLimit) }
        val limited = keyword.size >= keywordLimit
        val meaning = semantic?.let { meaningHits(it, q, meaningLimit, minScore) }.orEmpty()
        if (meaning.isEmpty()) return HybridResults(keyword.map { it.toHit(byMeaning = false) }, limited)

        val modelId = semantic!!.modelId
        val (passageOf, passages) = store.read {
            passagesOfSegments(keyword.map { it.segment.id }, modelId) to passages(meaning.map { it.passageId })
        }
        val passageById = passages.filter { it.segments.isNotEmpty() }.associateBy { it.id }
        val meaningKeys = meaning.mapNotNull { hit -> passageById[hit.passageId]?.let { Key.Passage(it.id) } }

        // The first keyword hit in each passage stands for that passage; the others stay single segments.
        val keywordKeys = ArrayList<Key>(keyword.size)
        val claimed = HashSet<Long>()
        val keywordByKey = HashMap<Key, SearchHit>()
        for (hit in keyword) {
            val passage = passageOf[hit.segment.id]
            val key = if (passage != null && claimed.add(passage)) Key.Passage(passage) else Key.Segment(hit.segment.id)
            keywordKeys += key
            keywordByKey[key] = hit
        }
        val meaningSet = meaningKeys.toSet()
        val hits = Rrf.fuse(listOf(keywordKeys, meaningKeys)).mapNotNull { (key, _) ->
            val byMeaning = key in meaningSet
            keywordByKey[key]?.toHit(byMeaning) ?: (key as? Key.Passage)?.let { passageById[it.id] }?.toHit()
        }
        return HybridResults(hits, limited)
    }

    private suspend fun meaningHits(semantic: SemanticSearch, query: String, limit: Int, minScore: Float): List<VectorHit> = try {
        semantic.search(query, limit).filter { it.score >= minScore }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // The keyword results still stand; the model may be missing or broken.
        emptyList()
    }

    private fun SearchHit.toHit(byMeaning: Boolean) = HybridHit(segment, snippet, byKeyword = true, byMeaning = byMeaning)

    private fun PassageRecord.toHit(): HybridHit {
        val text = segments.joinToString(" ") { it.text.trim() }.replace('[', '［').replace(']', '］')
        val snippet = if (text.length > PASSAGE_SNIPPET_CHARS) text.take(PASSAGE_SNIPPET_CHARS) + "…" else text
        return HybridHit(segments.first(), snippet, byKeyword = false, byMeaning = true)
    }

    private sealed interface Key {
        data class Passage(val id: Long) : Key

        data class Segment(val id: Long) : Key
    }
}
