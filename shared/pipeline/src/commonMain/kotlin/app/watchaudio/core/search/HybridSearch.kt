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
 * The brute-force search always returns its nearest passages, even when nothing is related, so only
 * meaning hits within [MEANING_BAND] of the best one are kept (ruri-v3's similarities are high
 * across the board, 0.80–0.90 for unrelated conversation passages, so a fixed threshold does not
 * separate them; see docs/research/m0-6 §9).
 */
object HybridSearch {
    /** Meaning hits asked for per query (fewer remain after [MEANING_BAND]). */
    const val MEANING_LIMIT = 20

    /** How far below the best meaning hit (in similarity ≈ cosine) another one may score and still be shown. */
    const val MEANING_BAND = 0.04f

    /** Longest text shown for a meaning-only hit. */
    const val PASSAGE_SNIPPET_CHARS = 80

    /**
     * Searches [query]; [keywordHits] are the keyword search's results for it if the caller already
     * has them (the search screen shows them before the meaning search is done).
     */
    suspend fun search(
        store: LocalStore,
        semantic: SemanticSearch?,
        query: String,
        keywordLimit: Int = 100,
        meaningLimit: Int = MEANING_LIMIT,
        band: Float = MEANING_BAND,
        keywordHits: List<SearchHit>? = null,
    ): HybridResults {
        val q = query.trim()
        if (q.isEmpty()) return HybridResults(emptyList(), keywordLimited = false)
        val keyword = keywordHits ?: store.read { search(q, keywordLimit) }
        val limited = keyword.size >= keywordLimit
        val meaning = semantic?.let { meaningHits(it, q, meaningLimit, band) }.orEmpty()
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

    private suspend fun meaningHits(semantic: SemanticSearch, query: String, limit: Int, band: Float): List<VectorHit> = try {
        val hits = semantic.search(query, limit)
        val best = hits.firstOrNull()?.score
        if (best == null) emptyList() else hits.filter { it.score >= best - band }
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
