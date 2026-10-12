package app.watchaudio.core.search

import kotlin.test.Test
import kotlin.test.assertEquals

/** Reciprocal Rank Fusion (#24). */
class RrfTest {
    @Test
    fun itemsInBothListsComeFirst() {
        val fused = Rrf.fuse(listOf(listOf("a", "b", "c"), listOf("c", "d")))
        // b and d tie (rank 2 in one list each): b appeared first.
        assertEquals(listOf("c", "a", "b", "d"), fused.map { it.first })
        assertEquals(1.0 / 63 + 1.0 / 61, fused[0].second, 1e-12)
        assertEquals(1.0 / 61, fused[1].second, 1e-12)
    }

    @Test
    fun aSingleListKeepsItsOrder() {
        assertEquals(listOf(5, 3, 9, 1), Rrf.fuse(listOf(listOf(5, 3, 9, 1))).map { it.first })
        assertEquals(listOf(5, 3, 9, 1), Rrf.fuse(listOf(listOf(5, 3, 9, 1), emptyList())).map { it.first })
    }

    @Test
    fun equalScoresKeepTheOrderOfFirstAppearance() {
        // Rank 1 in one list each: a (first list) before x.
        assertEquals(listOf("a", "x", "b", "y"), Rrf.fuse(listOf(listOf("a", "b"), listOf("x", "y"))).map { it.first })
    }

    @Test
    fun aRepeatedItemCountsOnceAtItsBestRank() {
        val fused = Rrf.fuse(listOf(listOf("a", "a", "b")), k = 0)
        assertEquals(listOf("a" to 1.0, "b" to 1.0 / 3), fused)
    }
}
