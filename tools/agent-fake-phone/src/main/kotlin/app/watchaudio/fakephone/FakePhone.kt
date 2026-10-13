package app.watchaudio.fakephone

import app.watchaudio.core.agent.AgentChannel
import app.watchaudio.core.agent.AgentLink
import app.watchaudio.core.agent.AgentMessage
import app.watchaudio.core.agent.AgentService
import app.watchaudio.core.agent.AgentTools
import app.watchaudio.core.agent.AudioClipSource
import app.watchaudio.core.agent.Hello
import app.watchaudio.core.agent.PairOutcome
import app.watchaudio.core.agent.PairingUri
import app.watchaudio.core.agent.ServeEnd
import app.watchaudio.core.agent.splitHostPort
import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.db.NewSegment
import app.watchaudio.core.protocol.AudioCodec
import app.watchaudio.core.protocol.AudioFile
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.ChunkManifest
import app.watchaudio.core.protocol.SourcePlatform
import java.io.File
import java.io.IOException
import javax.net.ssl.SSLException
import kotlin.math.PI
import kotlin.math.sin
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.TimeZone

/**
 * A stand-in phone for the bridge CLI's end-to-end test. Prints one machine-readable line per
 * event on stdout (`CODE`, `PAIRED`, `CONNECTED`, `END`, `REFUSED`, `REJECTED`) and exits with
 * [Exit] codes. Usage:
 *
 * ```
 * agent-fake-phone seed   --db <file>
 * agent-fake-phone pair   --db <file> --identity <dir> --uri <pairing URI> [--name <phone>] [--decline]
 * agent-fake-phone serve  --db <file> --identity <dir> [--address host:port] [--seconds <n>]
 * agent-fake-phone allow-audio --db <file>
 * agent-fake-phone revoke --db <file>
 * agent-fake-phone log    --db <file>
 * ```
 */
fun main(args: Array<String>) {
    val options = Options(args.drop(1))
    val code = try {
        when (args.firstOrNull()) {
            "seed" -> seed(options)
            "pair" -> pair(options)
            "serve" -> serve(options)
            "allow-audio" -> allowAudio(options)
            "revoke" -> revoke(options)
            "log" -> log(options)
            else -> {
                System.err.println("usage: agent-fake-phone seed|pair|serve|allow-audio|revoke|log ...")
                Exit.USAGE
            }
        }
    } catch (e: Exception) {
        System.err.println("agent-fake-phone: ${e::class.simpleName}: ${e.message}")
        Exit.FAILED
    }
    System.out.flush()
    exitProcess(code)
}

object Exit {
    const val OK = 0
    const val FAILED = 1

    /** The bridge refused with an error message (e.g. an expired pairing value). */
    const val REFUSED = 2

    /** The TLS handshake failed or the bridge closed the connection before answering. */
    const val REJECTED = 3

    /** No active pairing on this phone. */
    const val NOT_PAIRED = 4
    const val USAGE = 64
}

private class Options(args: List<String>) {
    private val values = mutableMapOf<String, String>()
    private val flags = mutableSetOf<String>()

    init {
        var i = 0
        while (i < args.size) {
            val key = args[i].removePrefix("--")
            if (i + 1 < args.size && !args[i + 1].startsWith("--")) {
                values[key] = args[i + 1]
                i += 2
            } else {
                flags += key
                i += 1
            }
        }
    }

    fun value(key: String): String = values[key] ?: throw IllegalArgumentException("--$key is required")

    fun optional(key: String): String? = values[key]

    fun flag(key: String) = key in flags
}

private fun say(line: String) {
    println(line)
    System.out.flush()
}

private fun openStore(options: Options) = LocalStore.open(options.value("db"))

private val ZONE = TimeZone.of("Asia/Tokyo")

/** Demo conversations: yesterday, three days ago, and one older than the default 30-day scope. */
private fun seed(options: Options): Int = runBlocking {
    val now = System.currentTimeMillis()
    val day = 86_400_000L
    openStore(options).use { store ->
        suspend fun chunk(id: String, startedAt: Long, lines: List<String>) = store.transaction {
            val manifest = ChunkManifest(
                chunkId = id,
                sessionId = "demo",
                deviceId = "demo-watch",
                source = SourcePlatform.WEAROS,
                startedAtEpochMs = startedAt,
                durationMs = 300_000,
                timeZone = ZONE.id,
                audio = AudioFile("$id.m4a", AudioCodec.AAC, 16_000, 1, 24_000, 1_000, "00"),
                markOffsetsMs = emptyList(),
                endReason = ChunkEndReason.ROTATION,
            )
            insertChunk(manifest, "demo/$id.m4a", now)
            saveTranscript(id, lines.mapIndexed { i, text -> NewSegment(startedAt + i * 20_000L, startedAt + i * 20_000L + 15_000, text, "ja") }, "demo")
        }
        chunk(
            "meeting",
            now - day,
            listOf("おはようございます、始めましょうか", "来期の予算について確認しましょう", "予算は前年と同じ水準で申請します", "では金曜までに資料をまとめます"),
        )
        chunk("lunch", now - 3 * day, listOf("お昼どこに行きますか", "駅前のそば屋にしましょう"))
        chunk("old", now - 45 * day, listOf("四十五日前の予算の打ち合わせ"))
    }
    say("SEEDED")
    Exit.OK
}

private fun pair(options: Options): Int = runBlocking {
    val uri = PairingUri.parse(options.value("uri"))
    val identity = PhoneIdentity.loadOrCreate(File(options.value("identity")))
    val decline = options.flag("decline")
    openStore(options).use { store ->
        var lastError: Exception? = null
        for (address in uri.addresses) {
            val (host, port) = splitHostPort(address) ?: continue
            val socket = try {
                connectToBridge(host, port, identity, uri.fingerprint)
            } catch (e: IOException) {
                lastError = e
                continue
            }
            val channel = SocketChannel(socket)
            try {
                val outcome = AgentLink.pair(channel, store, uri, host, options.optional("name") ?: "Fake phone", identity.fingerprint, System::currentTimeMillis) { code ->
                    say("CODE $code")
                    !decline
                }
                return@runBlocking when (outcome) {
                    is PairOutcome.Paired -> {
                        say("PAIRED ${outcome.pairing.name} ${outcome.pairing.address}")
                        Exit.OK
                    }
                    PairOutcome.Declined -> {
                        say("DECLINED")
                        Exit.REFUSED
                    }
                    is PairOutcome.Refused -> {
                        say("REFUSED ${outcome.code}")
                        Exit.REFUSED
                    }
                }
            } catch (e: SSLException) {
                say("REJECTED ${e.message}")
                return@runBlocking Exit.REJECTED
            } finally {
                channel.close()
            }
        }
        say("REJECTED ${lastError?.message}")
        Exit.REJECTED
    }
}

private fun serve(options: Options): Int = runBlocking {
    val identity = PhoneIdentity.loadOrCreate(File(options.value("identity")))
    openStore(options).use { store ->
        val pairing = store.read { agentPairings() }.firstOrNull { it.isActive }
        if (pairing == null) {
            say("NOT_PAIRED")
            return@runBlocking Exit.NOT_PAIRED
        }
        val address = options.optional("address") ?: pairing.address ?: error("no address")
        val (host, port) = requireNotNull(splitHostPort(address)) { "bad address $address" }
        val socket = try {
            connectToBridge(host, port, identity, pairing.keyFingerprint)
        } catch (e: IOException) {
            say("REJECTED ${e::class.simpleName}: ${e.message}")
            return@runBlocking Exit.REJECTED
        }
        val channel = SocketChannel(socket)
        val tools = AgentTools(store, ZONE, audio = SineClips)
        val service = AgentService(store, tools)
        val seconds = options.optional("seconds")?.toLong() ?: 600
        val connected = GreetingChannel(channel)
        try {
            val end = withTimeoutOrNull(seconds * 1000) { AgentLink.serve(connected, service, pairing, "Fake phone", "agent-fake-phone/1") }
            when (end) {
                null -> {
                    say("END timeout")
                    Exit.OK
                }
                is ServeEnd.Closed -> {
                    // Closed before the bridge's hello: the bridge did not accept this phone.
                    if (!connected.greeted) {
                        say("REJECTED closed before hello")
                        Exit.REJECTED
                    } else {
                        say("END ${end.reason ?: "closed"}")
                        Exit.OK
                    }
                }
                ServeEnd.Revoked -> {
                    say("END revoked")
                    Exit.OK
                }
                is ServeEnd.Refused -> {
                    say("REFUSED ${end.code}")
                    Exit.REFUSED
                }
            }
        } catch (e: SSLException) {
            say("REJECTED ${e.message}")
            Exit.REJECTED
        } finally {
            channel.close()
        }
    }
}

/** Says `CONNECTED` once the bridge's hello arrives. */
private class GreetingChannel(private val channel: SocketChannel) : AgentChannel by channel {
    var greeted = false
        private set

    override suspend fun receive(): AgentMessage? = channel.receive().also {
        if (!greeted && it is Hello) {
            greeted = true
            say("CONNECTED ${it.name}")
        }
    }
}

private fun allowAudio(options: Options): Int = runBlocking {
    openStore(options).use { store ->
        store.transaction { agentPairings().filter { it.isActive }.forEach { setAgentScope(it.id, it.scopeDays, allowAudio = true) } }
    }
    say("AUDIO_ALLOWED")
    Exit.OK
}

private fun revoke(options: Options): Int = runBlocking {
    openStore(options).use { store ->
        val now = System.currentTimeMillis()
        store.transaction { agentPairings().filter { it.isActive }.forEach { revokeAgentPairing(it.id, now) } }
    }
    say("REVOKED")
    Exit.OK
}

private fun log(options: Options): Int = runBlocking {
    openStore(options).use { store ->
        store.read { agentAccessLog(100) }.forEach { say("ACCESS ${it.tool} ${it.outcome} ${it.itemCount} ${it.query ?: ""}") }
    }
    Exit.OK
}

/** A 440 Hz tone instead of decoding AAC (the demo chunks have no audio files). */
private object SineClips : AudioClipSource {
    override suspend fun read(audioPath: String, offsetMs: Long, durationMs: Long): FloatArray =
        FloatArray((durationMs * 16).toInt()) { i -> (0.2 * sin(2 * PI * 440 * i / 16_000.0)).toFloat() }
}
