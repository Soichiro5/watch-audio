package app.watchaudio.core.search

/**
 * Reciprocal Rank Fusion (Cormack et al. 2009) of ranked result lists (#24): an item scores
 * `Σ 1 / (k + rank)` over the lists it appears in (rank from 1), so items ranked well by both the
 * keyword and the meaning search come first, and either list alone still contributes. Only ranks
 * are used, never the lists' own scores, which are not comparable.
 */
object Rrf {
    /** The usual constant: damps the weight of the very first ranks. */
    const val K = 60

    /**
     * [lists] fused, best first. Equal scores keep the order of first appearance (lists in order,
     * then ranks). An item repeated within one list counts at its best rank only.
     */
    fun <T> fuse(lists: List<List<T>>, k: Int = K): List<Pair<T, Double>> {
        val scores = LinkedHashMap<T, Double>()
        for (list in lists) {
            val seen = HashSet<T>()
            list.forEachIndexed { index, item ->
                if (seen.add(item)) scores[item] = (scores[item] ?: 0.0) + 1.0 / (k + index + 1)
            }
        }
        val order = scores.keys.withIndex().associate { it.value to it.index }
        return scores.entries.sortedWith(compareByDescending<Map.Entry<T, Double>> { it.value }.thenBy { order.getValue(it.key) })
            .map { it.key to it.value }
    }
}
