package app.watchaudio.wear.tile

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import app.watchaudio.wear.MainActivity
import app.watchaudio.wear.R
import app.watchaudio.wear.ui.statusShort
import app.watchaudio.wear.ui.statusText

/** SHORT_TEXT complication: REC / OFF / CHG / … at a glance; tap opens the app. */
class RecorderComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.SHORT_TEXT) return null
        val status = RecorderStatusSnapshot.read(this)
        return build(status.key, status.armed)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        if (type == ComplicationType.SHORT_TEXT) build("recording", armed = true) else null

    private fun build(key: String, armed: Boolean): ComplicationData {
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_FROM_TILE, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return ShortTextComplicationData.Builder(
            PlainComplicationText.Builder(statusShort(key, armed)).build(),
            PlainComplicationText.Builder(statusText(this, key, armed)).build(),
        )
            .setTitle(PlainComplicationText.Builder(getString(R.string.app_name)).build())
            .setTapAction(tap)
            .build()
    }
}
