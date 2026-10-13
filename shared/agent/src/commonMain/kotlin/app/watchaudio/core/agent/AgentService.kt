package app.watchaudio.core.agent

import app.watchaudio.core.db.AgentAccessEntry
import app.watchaudio.core.db.AgentAccessOutcome
import app.watchaudio.core.db.LocalStore
import kotlin.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** The phone's answer to one JSON-RPC message from the bridge. */
data class RpcReply(
    /** The JSON-RPC response to send back, or null for a notification. */
    val response: JsonObject?,
    /** The pairing is no longer active: send [Bye] ([ByeReason.REVOKED]) and close. */
    val revoked: Boolean = false,
)

/**
 * Answers the bridge's MCP JSON-RPC messages for a pairing (#182): checks on every call that the
 * pairing is still active and within its calls per minute, runs the tool and records the call in
 * the access log, whatever its outcome. One instance per process, so the per-minute limit holds
 * across reconnections.
 */
class AgentService(
    private val store: LocalStore,
    private val tools: AgentTools,
    private val clock: Clock = Clock.System,
    private val callsPerMinute: Int = AgentLimits.CALLS_PER_MINUTE,
) {
    private val mutex = Mutex()
    private val limiters = HashMap<Long, RateLimiter>()

    suspend fun handle(pairingId: Long, message: JsonObject): RpcReply {
        val id = message["id"]
        val method = (message["method"] as? JsonPrimitive)?.contentOrNull
        if (id == null || id is JsonNull) return RpcReply(null) // a notification (or a response): nothing to answer
        if ((message["jsonrpc"] as? JsonPrimitive)?.contentOrNull != "2.0" || method == null) {
            return RpcReply(rpcError(id, INVALID_REQUEST, "Invalid request"))
        }
        if (method == "ping") return RpcReply(rpcResult(id, buildJsonObject { }))
        if (method != "tools/call") return RpcReply(rpcError(id, METHOD_NOT_FOUND, "Method not found: $method"))

        val params = message["params"] as? JsonObject
        val name = (params?.get("name") as? JsonPrimitive)?.contentOrNull
            ?: return RpcReply(rpcError(id, INVALID_PARAMS, "tools/call needs params.name"))
        val arguments = params["arguments"]?.let { it as? JsonObject ?: return RpcReply(rpcError(id, INVALID_PARAMS, "arguments must be an object")) }
            ?: JsonObject(emptyMap())

        val now = clock.now().toEpochMilliseconds()
        val pairing = store.read { agentPairing(pairingId) }
        if (pairing == null || !pairing.isActive) {
            if (pairing != null) log(AgentAccessEntry(now, pairingId, name, AgentAccessOutcome.REVOKED, 0, queryOf(arguments)))
            val result = ToolResult.error(
                "スマホでこの PC のペアリングが取り消されました。 (This PC's pairing was revoked on the phone.)",
                AgentAccessOutcome.REVOKED,
            )
            return RpcReply(rpcResult(id, result.toJson()), revoked = true)
        }
        val allowed = mutex.withLock { limiters.getOrPut(pairingId) { RateLimiter(callsPerMinute) }.tryAcquire(now) }
        if (!allowed) {
            log(AgentAccessEntry(now, pairingId, name, AgentAccessOutcome.RATE_LIMITED, 0, queryOf(arguments)))
            val result = ToolResult.error(
                "呼び出しが多すぎます（1 分に $callsPerMinute 回まで）。少し待ってから続けてください。 " +
                    "(Too many calls: at most $callsPerMinute per minute. Wait a moment.)",
                AgentAccessOutcome.RATE_LIMITED,
            )
            return RpcReply(rpcResult(id, result.toJson()))
        }
        val result = tools.call(pairing, name, arguments)
        log(AgentAccessEntry(now, pairingId, name, result.outcome, result.itemCount, result.query ?: queryOf(arguments)))
        return RpcReply(rpcResult(id, result.toJson()))
    }

    private suspend fun log(entry: AgentAccessEntry) {
        store.transaction { logAgentAccess(entry) }
    }

    /** What a refused call asked for, for the log: its search terms or the first argument. */
    private fun queryOf(arguments: JsonObject): String? =
        (arguments["query"] ?: arguments["date"] ?: arguments["utterance_id"])?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?.take(AgentLimits.MAX_QUERY_CHARS)

    companion object {
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602

        fun rpcResult(id: JsonElement, result: JsonObject): JsonObject = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }

        fun rpcError(id: JsonElement, code: Int, message: String): JsonObject = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put(
                "error",
                buildJsonObject {
                    put("code", code)
                    put("message", message)
                },
            )
        }
    }
}
