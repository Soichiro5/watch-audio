package app.watchaudio.app

import app.watchaudio.core.agent.AgentTools
import app.watchaudio.core.agent.PairOutcome
import app.watchaudio.core.agent.PairingUri
import app.watchaudio.core.agent.ToolContent
import app.watchaudio.core.agent.advertisementMatches
import app.watchaudio.core.db.AgentAccessOutcome
import app.watchaudio.core.db.NewSegment
import app.watchaudio.core.pipeline.Ingestor
import app.watchaudio.core.platform.sha256Of
import app.watchaudio.core.protocol.AckStatus
import app.watchaudio.core.protocol.AudioCodec
import app.watchaudio.core.protocol.AudioFile
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.ChunkManifest
import app.watchaudio.core.protocol.SourcePlatform
import kotlin.random.Random
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.TimeZone
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Agent access end to end in the app process, for the simulator check
 * `apps/ios/Tests/AgentAccessTests` (#185) against the real bridge CLI on the CI host: drives the
 * app's own [IosAgentAccess] (Keychain key, Network.framework, the shared connector) the way the
 * 「エージェント連携」 screen does, and reads the app's database for the evidence. Not used by the app.
 *
 * Every call is made on the main thread; callbacks run on the main thread.
 */
object IosAgentAccessCheck {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val access: IosAgentAccess get() = IosAppGraph.agent
    private val store get() = IosAppGraph.services.store

    fun now(): Long = Clock.System.now().toEpochMilliseconds()

    /**
     * Stores the AAC file at [audioPath] ([durationMs] long) as a watch chunk of yesterday 10:00
     * through the production [Ingestor], with two utterances, the first containing 「エージェント」
     * (what the host's agent searches for). Calls back with null, or the error.
     */
    fun seed(audioPath: String, durationMs: Long, callback: (String?) -> Unit) {
        scope.launch {
            callback(
                runCatching {
                    val tz = TimeZone.currentSystemDefault()
                    val today = Clock.System.now().toEpochMilliseconds()
                    val startedAt = (today - 86_400_000L).let { it - it % 3_600_000L }
                    val chunkId = "agent-check-" + Random.nextLong().toULong().toString(16)
                    val size = SystemFileSystem.metadataOrNull(Path(audioPath))?.size ?: error("no audio at $audioPath")
                    val manifest = ChunkManifest(
                        chunkId = chunkId,
                        sessionId = "agent-check",
                        deviceId = "simulator",
                        source = SourcePlatform.WATCHOS,
                        startedAtEpochMs = startedAt,
                        durationMs = durationMs,
                        timeZone = tz.id,
                        audio = AudioFile(Path(audioPath).name, AudioCodec.AAC, 16_000, 1, 32_000, size, sha256Of(Path(audioPath))),
                        endReason = ChunkEndReason.ROTATION,
                    )
                    val ack = Ingestor(store, Path(IosAppGraph.services.audioRoot)).ingest(manifest, Path(audioPath))
                    check(ack.status == AckStatus.STORED) { "the chunk was not stored: ${ack.status}" }
                    store.transaction {
                        saveTranscript(
                            chunkId,
                            listOf(
                                NewSegment(startedAt + 2_000, startedAt + 6_000, "エージェント連携の確認をしましょう", "ja"),
                                NewSegment(startedAt + 9_000, startedAt + 13_000, "来週の会議の資料を用意します", "ja"),
                            ),
                            "agent-check",
                        )
                    }
                }.exceptionOrNull()?.let { it.message ?: it.toString() },
            )
        }
    }

    /**
     * Pairs with the bridge of [uri] (the string `watch-audio-mcp pair` printed), confirming the
     * code as the user would once it matches. Calls back with (the code, null) once both sides
     * confirmed, or (code or null, what went wrong).
     */
    fun pair(uri: String, callback: (String?, String?) -> Unit) {
        scope.launch {
            var shown: String? = null
            val outcome = runCatching {
                access.pair(PairingUri.parse(uri.trim())) { code ->
                    shown = code
                    true
                }
            }
            val error = outcome.fold(
                onSuccess = { attempt ->
                    when (attempt) {
                        is PairAttempt.Done -> (attempt.outcome as? PairOutcome.Paired)?.let { null } ?: "pairing ended: ${attempt.outcome}"
                        else -> "pairing failed: $attempt"
                    }
                },
                onFailure = { "pairing threw: $it" },
            )
            callback(shown, error)
        }
    }

    /** The active pairings as `name@address scope_days=… audio=…`, comma-separated. */
    fun pairings(callback: (String) -> Unit) {
        scope.launch {
            callback(store.read { agentPairings() }.filter { it.isActive }.joinToString { "${it.name}@${it.address} scope_days=${it.scopeDays} audio=${it.allowAudio}" })
        }
    }

    /** Turns access on like the switch; calls back with the [TurnOnResult] name. */
    fun turnOn(minutes: Int, callback: (String) -> Unit) {
        scope.launch { callback(access.turnOn(minutes).name) }
    }

    /** Turns access off like the switch. */
    fun turnOff() = access.turnOff()

    /** The app leaves the screen (`scenePhase` background) or comes back. */
    fun setAppActive(active: Boolean) = IosAppGraph.setAppActive(active)

    fun isOn(): Boolean = access.state.value.isOn

    /** Some active pairing is connected to its bridge. */
    fun isConnected(): Boolean = access.state.value.links.values.any { it == app.watchaudio.core.agent.LinkState.CONNECTED }

    /** The connection state for the evidence. */
    fun describeState(): String = access.state.value.let { "on=${it.isOn} links=${it.links} local_network_denied=${it.localNetworkDenied}" }

    /**
     * Access log entries since [since] as `tool:outcome:count:'query'`, newest first, and how many
     * of them were answered (outcome OK), separated by `|`: `<answered>|<entries>`.
     */
    fun accessLog(since: Long, callback: (String) -> Unit) {
        scope.launch {
            val entries = store.read { agentAccessLog(500) }.filter { it.at >= since }
            val answered = entries.count { it.outcome == AgentAccessOutcome.OK }
            callback("$answered|" + entries.joinToString(" ") { "${it.tool}:${it.outcome}:${it.itemCount}:'${it.query}'" })
        }
    }

    /** Revokes every active pairing like the screen does (and turns access off with the last one). */
    fun revokeAll(callback: () -> Unit) {
        scope.launch {
            val now = now()
            store.transaction { agentPairings().filter { it.isActive }.forEach { revokeAgentPairing(it.id, now) } }
            access.turnOff()
            callback()
        }
    }

    /**
     * `get_audio_clip` for the first seeded utterance, through the shared tool with the iOS
     * [IosAudioClipSource] and a copy of the active pairing that allows audio (the stored pairing
     * is not changed). Calls back with `mime=… bytes=…`, or an error text.
     */
    fun audioClip(callback: (String) -> Unit) {
        scope.launch {
            callback(
                runCatching {
                    val pairing = store.read { agentPairings() }.firstOrNull { it.isActive } ?: error("no active pairing")
                    val since = now() - 3 * 86_400_000L
                    val segment = store.read { searchBetween("エージェント", since, now(), 1) }.firstOrNull() ?: error("no seeded utterance")
                    val tools = AgentTools(store, TimeZone.currentSystemDefault(), audio = IosAudioClipSource(IosAppGraph.agentPlatform, IosAppGraph.services.audioRoot))
                    val result = tools.call(pairing.copy(allowAudio = true), "get_audio_clip", buildJsonObject { put("utterance_id", segment.segment.id) })
                    val audio = result.content.filterIsInstance<ToolContent.Audio>().firstOrNull()
                    if (audio == null) "error: " + result.content.filterIsInstance<ToolContent.Text>().joinToString { it.text }
                    else "mime=${audio.mimeType} base64_chars=${audio.data.length}"
                }.getOrElse { "error: $it" },
            )
        }
    }

    /**
     * Browses mDNS for [seconds] and reports what was seen: `seen=<n> matching=<n> first=<host:port>`,
     * where matching counts advertisements of the active pairing's bridge.
     */
    fun discover(seconds: Int, callback: (String) -> Unit) {
        scope.launch {
            val report = runCatching {
                val fingerprint = store.read { agentPairings() }.firstOrNull { it.isActive }?.keyFingerprint
                var seen = 0
                var matching = 0
                var first: String? = null
                withTimeoutOrNull(seconds * 1_000L) {
                    access.transport().discover().takeWhile { matching == 0 }.collect { ad ->
                        seen++
                        if (fingerprint != null && advertisementMatches(ad.nonce, ad.hash, fingerprint)) {
                            matching++
                            first = "${ad.host}:${ad.port}"
                        }
                    }
                }
                "seen=$seen matching=$matching first=${first ?: "-"}"
            }.getOrElse { "error: $it" }
            callback(report)
        }
    }
}
