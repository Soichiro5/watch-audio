package app.watchaudio.core.agent

import app.watchaudio.core.db.AgentPairing
import app.watchaudio.core.db.LocalStore

/**
 * One TLS connection to the bridge as the phone sees it, already authenticated (the platform
 * pinned the bridge's key and presented the phone's). Frames are [AgentWire] frames.
 */
interface AgentChannel {
    /** The next message, or null once the bridge closed the connection. */
    suspend fun receive(): AgentMessage?

    suspend fun send(message: AgentMessage)
}

/** How a serving connection ended. */
sealed interface ServeEnd {
    /** The bridge said [Bye] or closed the connection. */
    data class Closed(val reason: String?) : ServeEnd

    /** The pairing was revoked while connected; [Bye] was sent. */
    data object Revoked : ServeEnd

    /** The bridge refused (an [ErrorMessage]) or broke the protocol. */
    data class Refused(val code: String, val message: String) : ServeEnd
}

/** How pairing ended on the phone. */
sealed interface PairOutcome {
    /** Both users confirmed; the pairing is stored. */
    data class Paired(val pairing: AgentPairing) : PairOutcome

    /** The phone's user declined the code. */
    data object Declined : PairOutcome

    /** The bridge refused ([AgentErrorCode]) or broke the protocol. */
    data class Refused(val code: String, val message: String) : PairOutcome
}

/** The phone's side of the conversations of [AgentWire]; the platform supplies the channel. */
object AgentLink {
    /**
     * Pairs with the bridge of [uri] over [channel] (whose TLS pinned [PairingUri.fingerprint]):
     * sends the one-time value, shows [confirmationCode] through [confirm] and, once both users
     * confirmed, stores the pairing with the default scope (text only, last 30 days). A pairing
     * with the same PC that is still active is revoked: pairing again replaces it.
     *
     * @param host the host of [uri]'s addresses that [channel] reached; stored with the port the
     *   bridge serves on.
     */
    suspend fun pair(
        channel: AgentChannel,
        store: LocalStore,
        uri: PairingUri,
        host: String,
        phoneName: String,
        phoneFingerprint: String,
        now: () -> Long,
        confirm: suspend (code: String) -> Boolean,
    ): PairOutcome {
        channel.send(PairRequest(AgentWire.PROTOCOL_VERSION, uri.token, phoneName))
        when (val reply = channel.receive()) {
            PairPending -> Unit
            is ErrorMessage -> return PairOutcome.Refused(reply.code, reply.message)
            else -> return PairOutcome.Refused(AgentErrorCode.BAD_MESSAGE, "unexpected reply ${reply?.let { it::class.simpleName }}")
        }
        val code = confirmationCode(uri.token, uri.fingerprint, phoneFingerprint)
        if (!confirm(code)) {
            channel.send(ErrorMessage(AgentErrorCode.PAIRING_REJECTED, "declined on the phone"))
            return PairOutcome.Declined
        }
        channel.send(PairConfirm)
        val result = when (val reply = channel.receive()) {
            is PairResult -> reply
            is ErrorMessage -> return PairOutcome.Refused(reply.code, reply.message)
            else -> return PairOutcome.Refused(AgentErrorCode.BAD_MESSAGE, "unexpected reply ${reply?.let { it::class.simpleName }}")
        }
        val address = if (':' in host) "[$host]:${result.port}" else "$host:${result.port}"
        val at = now()
        val pairing = store.transaction {
            activeAgentPairing(uri.fingerprint)?.let { revokeAgentPairing(it.id, at) }
            val id = addAgentPairing(result.name, uri.fingerprint, address, AgentLimits.DEFAULT_SCOPE_DAYS, allowAudio = false, createdAt = at)
            agentPairing(id)!!
        }
        return PairOutcome.Paired(pairing)
    }

    /**
     * Serves tool calls of the paired bridge until it leaves or the pairing is revoked. Ending
     * the connection (e.g. when the user turns agent access off) is the platform's job: it closes
     * [channel], which makes [AgentChannel.receive] return null.
     */
    suspend fun serve(
        channel: AgentChannel,
        service: AgentService,
        pairing: AgentPairing,
        phoneName: String,
        software: String,
    ): ServeEnd {
        channel.send(Hello(AgentWire.PROTOCOL_VERSION, AgentRole.PHONE, phoneName, software))
        when (val reply = channel.receive()) {
            null -> return ServeEnd.Closed(null)
            is Hello -> if (reply.protocol != AgentWire.PROTOCOL_VERSION || reply.role != AgentRole.BRIDGE) {
                channel.send(ErrorMessage(AgentErrorCode.UNSUPPORTED_PROTOCOL, "protocol ${AgentWire.PROTOCOL_VERSION} only"))
                return ServeEnd.Refused(AgentErrorCode.UNSUPPORTED_PROTOCOL, "bridge speaks protocol ${reply.protocol}")
            }
            is ErrorMessage -> return ServeEnd.Refused(reply.code, reply.message)
            is Bye -> return ServeEnd.Closed(reply.reason)
            else -> return ServeEnd.Refused(AgentErrorCode.BAD_MESSAGE, "expected hello")
        }
        while (true) {
            when (val message = channel.receive()) {
                null -> return ServeEnd.Closed(null)
                is Bye -> return ServeEnd.Closed(message.reason)
                is ErrorMessage -> return ServeEnd.Refused(message.code, message.message)
                is Rpc -> {
                    val reply = service.handle(pairing.id, message.message)
                    reply.response?.let { channel.send(Rpc(it)) }
                    if (reply.revoked) {
                        channel.send(Bye(ByeReason.REVOKED))
                        return ServeEnd.Revoked
                    }
                }
                else -> {
                    channel.send(ErrorMessage(AgentErrorCode.BAD_MESSAGE, "unexpected ${message::class.simpleName}"))
                    return ServeEnd.Refused(AgentErrorCode.BAD_MESSAGE, "unexpected ${message::class.simpleName}")
                }
            }
        }
    }
}
