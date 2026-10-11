package app.watchaudio.core.db

import androidx.sqlite.SQLiteConnection

/** App settings (key-value strings in `app_setting`). */
interface SettingQueries {
    /** Value of an app setting, or null if it was never set. */
    fun setting(key: String): String?

    /** All stored (non-null) app settings. */
    fun settings(): Map<String, String>

    /** Stores an app setting; null removes it. */
    fun putSetting(key: String, value: String?)
}

internal class SettingSql(private val db: SQLiteConnection) : SettingQueries {

    override fun setting(key: String): String? =
        db.queryOne("SELECT value FROM app_setting WHERE key = ?", key) { if (isNull(0)) null else getText(0) }

    override fun settings(): Map<String, String> =
        db.queryList("SELECT key, value FROM app_setting WHERE value IS NOT NULL") { getText(0) to getText(1) }.toMap()

    override fun putSetting(key: String, value: String?) {
        if (value == null) {
            db.execute("DELETE FROM app_setting WHERE key = ?", key)
        } else {
            db.execute(
                "INSERT INTO app_setting (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                key,
                value,
            )
        }
    }
}
