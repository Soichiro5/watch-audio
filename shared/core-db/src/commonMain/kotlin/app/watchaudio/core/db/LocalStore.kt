package app.watchaudio.core.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.watchaudio.core.platform.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The on-device database. Everything stays local: there is no sync or backup to any server.
 *
 * Uses the bundled SQLite (same version and FTS5 support on Android, iOS and JVM) through a
 * single connection serialized by a mutex.
 */
class LocalStore private constructor(
    private val connection: SQLiteConnection,
    private val dispatcher: CoroutineDispatcher,
) : AutoCloseable {
    private val mutex = Mutex()
    private val changeCount = MutableStateFlow(0L)

    /**
     * Number of write transactions committed through this instance since it was opened.
     * SQLite has no change notifications, so observers (the UI) re-query when this moves.
     * Writes from another process or connection are not counted.
     */
    val changes: StateFlow<Long> = changeCount.asStateFlow()

    /** Runs [block] in a write transaction; rolls back if it throws. */
    suspend fun <T> transaction(block: StoreTransaction.() -> T): T = mutex.withLock {
        withContext(dispatcher) {
            connection.execSQL("BEGIN IMMEDIATE")
            try {
                StoreTransaction(connection).block().also {
                    connection.execSQL("COMMIT")
                    changeCount.value += 1
                }
            } catch (t: Throwable) {
                connection.execSQL("ROLLBACK")
                throw t
            }
        }
    }

    /** Read-only access (no explicit transaction). */
    suspend fun <T> read(block: StoreTransaction.() -> T): T = mutex.withLock {
        withContext(dispatcher) { StoreTransaction(connection).block() }
    }

    override fun close() = connection.close()

    companion object {
        const val FILE_NAME = "watch-audio.db"

        fun open(
            path: String,
            driver: SQLiteDriver = BundledSQLiteDriver(),
            dispatcher: CoroutineDispatcher = IoDispatcher,
        ): LocalStore {
            val connection = driver.open(path)
            connection.pragma("journal_mode = WAL")
            connection.execSQL("PRAGMA foreign_keys = ON")
            connection.execSQL("PRAGMA synchronous = NORMAL")
            migrate(connection)
            return LocalStore(connection, dispatcher)
        }

        private fun migrate(connection: SQLiteConnection) {
            val current = connection.pragma("user_version")?.toInt() ?: 0
            for (version in current until MIGRATIONS.size) {
                connection.execSQL("BEGIN IMMEDIATE")
                try {
                    MIGRATIONS[version].forEach { connection.execSQL(it.trimIndent()) }
                    connection.execSQL("PRAGMA user_version = ${version + 1}")
                    connection.execSQL("COMMIT")
                } catch (t: Throwable) {
                    connection.execSQL("ROLLBACK")
                    throw t
                }
            }
        }

        /** PRAGMAs that return a row must be stepped, not exec'd. */
        private fun SQLiteConnection.pragma(statement: String): String? =
            prepare("PRAGMA $statement").use { if (it.step()) it.getText(0) else null }
    }
}
