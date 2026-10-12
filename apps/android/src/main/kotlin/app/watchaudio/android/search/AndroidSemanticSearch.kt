package app.watchaudio.android.search

import app.watchaudio.android.asr.ModelManager
import app.watchaudio.core.search.SemanticIndex
import app.watchaudio.core.search.SemanticSearch
import app.watchaudio.core.search.SemanticStatus

/**
 * Semantic search (#24) as the search screen sees it on Android: the [index], plus the embedding
 * model's download, which starts (Wi-Fi only, ~44 MB) the first time the screen asks for the status,
 * i.e. when the user opens the search tab. People who never search download nothing.
 */
class AndroidSemanticSearch(private val index: SemanticIndex, private val models: ModelManager) : SemanticSearch by index {
    override suspend fun status(): SemanticStatus {
        models.downloadEmbeddingIfMissing()
        return index.status()
    }
}
