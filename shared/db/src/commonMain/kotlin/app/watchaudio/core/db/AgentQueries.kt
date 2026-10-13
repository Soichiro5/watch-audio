package app.watchaudio.core.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement

/**
 * A PC paired for agent access (#182): its bridge CLI's key and what it may read. Times are
 * epoch ms.
 */
data class AgentPairing(
    val id: Long,
    /** Name the PC gave itself at pairing (shown to the user; not trusted for anything else). */
    val name: String,
    /** Base64url SHA-256 of the bridge's public key (SubjectPublicKeyInfo), pinned for TLS. */
    val keyFingerprint: String,
    /** Last known `host:port` of the bridge, used when it cannot be found by mDNS. */
    val address: String?,
    /** How far back the PC may read: segments that started within this many days before now. */
    val scopeDays: Int,
    /** The PC may fetch audio clips (off unless the user turns it on for this pairing). */
    val allowAudio: Boolean,
    val createdAt: Long,
    /** When the user revoked the pairing, or null while it is active. */
    val revokedAt: Long? = null,
) {
    val isActive: Boolean get() = revokedAt == null
}

/** Result of one agent tool call, as recorded in the access log. */
enum class AgentAccessOutcome {
    /** Answered (possibly with zero items). */
    OK,

    /** Refused by the pairing's scope (e.g. audio not allowed) or invalid arguments. */
    DENIED,

    /** Refused because the pairing exceeded its calls per minute. */
    RATE_LIMITED,

    /** Refused because the pairing was revoked. */
    REVOKED,

    /** The tool failed on the phone. */
    ERROR,
}

/** One row of the agent access log (#182): what a paired PC asked for and what it got. */
data class AgentAccessEntry(
    val at: Long,
    val pairingId: Long,
    /** MCP tool name (`search_transcripts`, ...), or the JSON-RPC method for anything else. */
    val tool: String,
    val outcome: AgentAccessOutcome,
    /** Utterances, conversations or clips returned. */
    val itemCount: Int,
    /** The search terms or the asked-for date / utterance, as the agent sent them; null if none. */
    val query: String? = null,
    /** Row id (0 for an entry not stored yet). */
    val id: Long = 0,
)

/** Pairings and the access log of agent access (#182). Neither is part of backups or exports. */
interface AgentQueries {
    /** Stores a new active pairing. @return its id. */
    fun addAgentPairing(name: String, keyFingerprint: String, address: String?, scopeDays: Int, allowAudio: Boolean, createdAt: Long): Long

    fun agentPairing(id: Long): AgentPairing?

    /** All pairings, revoked ones included, newest first. */
    fun agentPairings(): List<AgentPairing>

    /** The active pairing with [keyFingerprint], if any. */
    fun activeAgentPairing(keyFingerprint: String): AgentPairing?

    /** Changes what the pairing may read. */
    fun setAgentScope(id: Long, scopeDays: Int, allowAudio: Boolean)

    /** Remembers where the pairing's bridge was last reached. */
    fun setAgentAddress(id: Long, address: String?)

    /** Revokes the pairing (no further calls are answered). @return false if it was not active. */
    fun revokeAgentPairing(id: Long, at: Long): Boolean

    fun logAgentAccess(entry: AgentAccessEntry): Long

    /** Access log entries before [before] (exclusive), newest first, at most [limit]. */
    fun agentAccessLog(limit: Int, before: Long = Long.MAX_VALUE): List<AgentAccessEntry>

    /** Deletes access log entries older than [before] (exclusive; retention, #184). @return how many. */
    fun deleteAgentAccessBefore(before: Long): Int
}

internal class AgentSql(private val db: SQLiteConnection) : AgentQueries {

    override fun addAgentPairing(
        name: String,
        keyFingerprint: String,
        address: String?,
        scopeDays: Int,
        allowAudio: Boolean,
        createdAt: Long,
    ): Long {
        require(scopeDays > 0) { "scopeDays must be positive" }
        db.execute(
            """
            INSERT INTO agent_pairing (name, key_fingerprint, address, scope_days, allow_audio, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            name,
            keyFingerprint,
            address,
            scopeDays,
            if (allowAudio) 1 else 0,
            createdAt,
        )
        return db.queryOne("SELECT last_insert_rowid()") { getLong(0) }!!
    }

    override fun agentPairing(id: Long): AgentPairing? =
        db.queryOne("SELECT $PAIRING_COLUMNS FROM agent_pairing WHERE id = ?", id) { toPairing() }

    override fun agentPairings(): List<AgentPairing> =
        db.queryList("SELECT $PAIRING_COLUMNS FROM agent_pairing ORDER BY id DESC") { toPairing() }

    override fun activeAgentPairing(keyFingerprint: String): AgentPairing? = db.queryOne(
        "SELECT $PAIRING_COLUMNS FROM agent_pairing WHERE key_fingerprint = ? AND revoked_at IS NULL",
        keyFingerprint,
    ) { toPairing() }

    override fun setAgentScope(id: Long, scopeDays: Int, allowAudio: Boolean) {
        require(scopeDays > 0) { "scopeDays must be positive" }
        db.execute("UPDATE agent_pairing SET scope_days = ?, allow_audio = ? WHERE id = ?", scopeDays, if (allowAudio) 1 else 0, id)
    }

    override fun setAgentAddress(id: Long, address: String?) {
        db.execute("UPDATE agent_pairing SET address = ? WHERE id = ?", address, id)
    }

    override fun revokeAgentPairing(id: Long, at: Long): Boolean =
        db.update("UPDATE agent_pairing SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL", at, id) > 0

    override fun logAgentAccess(entry: AgentAccessEntry): Long {
        db.execute(
            "INSERT INTO agent_access (at, pairing_id, tool, outcome, item_count, query) VALUES (?, ?, ?, ?, ?, ?)",
            entry.at,
            entry.pairingId,
            entry.tool,
            entry.outcome.name,
            entry.itemCount,
            entry.query,
        )
        return db.queryOne("SELECT last_insert_rowid()") { getLong(0) }!!
    }

    override fun agentAccessLog(limit: Int, before: Long): List<AgentAccessEntry> = db.queryList(
        "SELECT id, at, pairing_id, tool, outcome, item_count, query FROM agent_access WHERE at < ? ORDER BY at DESC, id DESC LIMIT ?",
        before,
        limit,
    ) {
        AgentAccessEntry(
            id = getLong(0),
            at = getLong(1),
            pairingId = getLong(2),
            tool = getText(3),
            outcome = AgentAccessOutcome.valueOf(getText(4)),
            itemCount = getInt(5),
            query = if (isNull(6)) null else getText(6),
        )
    }

    override fun deleteAgentAccessBefore(before: Long): Int = db.update("DELETE FROM agent_access WHERE at < ?", before)

    private companion object {
        const val PAIRING_COLUMNS = "id, name, key_fingerprint, address, scope_days, allow_audio, created_at, revoked_at"

        fun SQLiteStatement.toPairing() = AgentPairing(
            id = getLong(0),
            name = getText(1),
            keyFingerprint = getText(2),
            address = if (isNull(3)) null else getText(3),
            scopeDays = getInt(4),
            allowAudio = getLong(5) != 0L,
            createdAt = getLong(6),
            revokedAt = if (isNull(7)) null else getLong(7),
        )
    }
}
