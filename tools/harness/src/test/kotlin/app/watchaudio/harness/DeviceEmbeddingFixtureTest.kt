package app.watchaudio.harness

import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Keeps the reference vectors of the Android device test (`EmbeddingDeviceTest`, #24) equal to what
 * this JVM computes with the same model and code: the device compares its own embeddings with them
 * (the runtime on the device computes the same model), and ranks queries over the passages.
 * Every ranking query must beat the other passages here by at least [MIN_MARGIN], so the device's
 * ranking check is never decided by a near-tie (the first version of the device test used
 * 「先週話した旅行の件」, which scores 0.8203 for the trip passage and 0.8267 for the dentist one on
 * the JVM as well as on the emulator).
 *
 * `UPDATE_EMBEDDING_FIXTURE=1` rewrites the fixture from [TEXTS] and [RANKING].
 */
class DeviceEmbeddingFixtureTest {
    private val models = File(System.getProperty("watchaudio.models") ?: "models")
    private val fixture = File("../../apps/android/src/androidTest/assets/embedding-reference.json")

    @Test
    fun fixtureMatchesTheJvmAndRankingsHaveAClearMargin() = runBlocking {
        if (!HarnessEmbedder.available(models)) {
            check(System.getProperty("watchaudio.requireModels") != "true") { "embedding model missing in $models" }
            println("SKIPPED: run tools/harness/download-models.sh to check the device test's reference vectors")
            return@runBlocking
        }
        val embedder = HarnessEmbedder.create(models)
        val vectors = try {
            TEXTS.map { (query, text) -> if (query) embedder.embedQuery(text) else embedder.embedDocuments(listOf(text)).single() }
        } finally {
            embedder.release()
        }
        for ((query, passage) in RANKING) {
            val scores = PASSAGES.map { dot(vectors[query], vectors[it]) }
            val best = scores[PASSAGES.indexOf(passage)]
            val margin = best - scores.filterIndexed { i, _ -> PASSAGES[i] != passage }.max()
            println("RANKING '${TEXTS[query].second}': %s margin %.4f".format(scores.joinToString { "%.4f".format(it) }, margin))
            assertTrue(margin >= MIN_MARGIN, "'${TEXTS[query].second}' wins by only $margin")
        }
        if (System.getenv("UPDATE_EMBEDDING_FIXTURE") == "1") write(vectors)
        val stored = Json.parseToJsonElement(fixture.readText()).jsonObject
        val texts = stored["texts"]!!.jsonArray.map { it.jsonObject }
        assertEquals(TEXTS.map { it.second }, texts.map { it["text"]!!.jsonPrimitive.content })
        assertEquals(TEXTS.map { it.first }, texts.map { it["query"]!!.jsonPrimitive.content.toBoolean() })
        assertEquals(RANKING.map { it.first to it.second }, stored["ranking"]!!.jsonArray.map { r -> r.jsonObject.let { it["query"]!!.jsonPrimitive.int to it["passage"]!!.jsonPrimitive.int } })
        texts.forEachIndexed { i, entry ->
            val expected = entry["vector"]!!.jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
            assertTrue(dot(expected, vectors[i]) > EmbedderParityTest.CROSS_MACHINE_COSINE, "fixture vector of '${TEXTS[i].second}' is out of date (UPDATE_EMBEDDING_FIXTURE=1)")
        }
    }

    private fun write(vectors: List<FloatArray>) {
        val json = buildJsonObject {
            put("about", "ruri-v3-30m int8 vectors computed on the JVM (tools/harness DeviceEmbeddingFixtureTest) for EmbeddingDeviceTest (#24)")
            put(
                "texts",
                buildJsonArray {
                    TEXTS.forEachIndexed { i, (query, text) ->
                        add(
                            buildJsonObject {
                                put("query", query)
                                put("text", text)
                                put("vector", JsonArray(vectors[i].map { JsonPrimitive("%.6f".format(Locale.ROOT, it).toFloat()) }))
                            },
                        )
                    }
                },
            )
            put(
                "ranking",
                buildJsonArray {
                    RANKING.forEach { (query, passage) ->
                        add(buildJsonObject {
                            put("query", query)
                            put("passage", passage)
                        })
                    }
                },
            )
        }
        fixture.parentFile.mkdirs()
        fixture.writeText(Json.encodeToString(JsonObject.serializer(), json) + "\n")
    }

    private fun dot(a: FloatArray, b: FloatArray): Double = a.indices.sumOf { a[it].toDouble() * b[it] }

    private companion object {
        const val MIN_MARGIN = 0.05

        /** (is a query, text). Passages are joined segments as the app embeds them (spaces between segments). */
        val TEXTS = listOf(
            false to "来月の沖縄だけどさ 飛行機は那覇行きの朝の便でいいかな ホテルは国際通りの近くにしよう",
            false to "歯医者の予約 来週の木曜の午後三時に取っといたからね",
            false to "サーバーの費用が月に12万円くらい増えるって 12万円か けっこう大きいですね",
            false to "年末は実家に帰るかも 北海道だっけ そう 札幌 冬は寒いけどご飯がおいしいんだ",
            true to "沖縄旅行の飛行機とホテル",
            true to "歯医者の予約いつだっけ",
            true to "サーバー代がいくら増えるって言ってた？",
            true to "年末に札幌の実家へ帰るかもって話",
        )
        val PASSAGES = listOf(0, 1, 2, 3)

        /** (query index, the passage index it must rank first). */
        val RANKING = listOf(4 to 0, 5 to 1, 6 to 2, 7 to 3)
    }
}
