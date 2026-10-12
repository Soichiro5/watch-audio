package app.watchaudio.core.search

/**
 * The [k] highest scores offered so far with their indexes: a min-heap, so each offer that does
 * not make it costs one comparison. Ties keep the earlier offer.
 */
internal class TopK(private val k: Int) {
    private val indexes = IntArray(k)
    private val scores = FloatArray(k)
    private var size = 0

    fun offer(index: Int, score: Float) {
        if (size < k) {
            indexes[size] = index
            scores[size] = score
            siftUp(size++)
        } else if (score > scores[0]) {
            indexes[0] = index
            scores[0] = score
            siftDown(0)
        }
    }

    fun forEach(action: (index: Int, score: Float) -> Unit) {
        for (i in 0 until size) action(indexes[i], scores[i])
    }

    /** The entries, highest score first (equal scores: lower index first). */
    fun sortedDescending(): List<Pair<Int, Float>> =
        (0 until size).map { indexes[it] to scores[it] }.sortedWith(compareByDescending<Pair<Int, Float>> { it.second }.thenBy { it.first })

    private fun siftUp(start: Int) {
        var i = start
        while (i > 0) {
            val parent = (i - 1) / 2
            if (scores[parent] <= scores[i]) return
            swap(parent, i)
            i = parent
        }
    }

    private fun siftDown(start: Int) {
        var i = start
        while (true) {
            val left = 2 * i + 1
            if (left >= size) return
            val right = left + 1
            val smaller = if (right < size && scores[right] < scores[left]) right else left
            if (scores[i] <= scores[smaller]) return
            swap(i, smaller)
            i = smaller
        }
    }

    private fun swap(a: Int, b: Int) {
        val index = indexes[a]
        indexes[a] = indexes[b]
        indexes[b] = index
        val score = scores[a]
        scores[a] = scores[b]
        scores[b] = score
    }
}
