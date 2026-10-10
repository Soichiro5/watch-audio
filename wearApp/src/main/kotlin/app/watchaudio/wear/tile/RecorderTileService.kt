package app.watchaudio.wear.tile

import android.content.ComponentName
import android.content.Context
import androidx.concurrent.futures.SuspendToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import app.watchaudio.wear.AppGraph
import app.watchaudio.wear.MainActivity
import app.watchaudio.wear.R
import app.watchaudio.wear.recording.RecorderStore
import app.watchaudio.wear.recording.statusKey
import app.watchaudio.wear.ui.statusText
import com.google.common.util.concurrent.ListenableFuture

/**
 * Tile showing the recorder state and the number of chunks waiting for the phone. Tapping it opens
 * the app, which starts/resumes recording: a microphone foreground service may only be started
 * from the foreground on Android 14+, so the tile cannot start it directly.
 */
class RecorderTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        SuspendToFutureAdapter.launchFuture {
            val status = RecorderStatusSnapshot.read(this@RecorderTileService)
            TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setFreshnessIntervalMillis(FRESHNESS_MS)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(status)))
                .build()
        }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        SuspendToFutureAdapter.launchFuture { ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build() }

    private fun layout(status: RecorderStatusSnapshot): LayoutElementBuilders.LayoutElement {
        val recording = status.key == "recording"
        val stateColor = when {
            recording -> COLOR_RECORDING
            status.key.startsWith("paused") -> COLOR_PAUSED
            else -> COLOR_STOPPED
        }
        val launch = ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(packageName)
                    .setClassName(MainActivity::class.java.name)
                    .addKeyToExtraMapping(MainActivity.EXTRA_FROM_TILE, ActionBuilders.booleanExtra(true))
                    .build(),
            )
            .build()
        val clickable = ModifiersBuilders.Clickable.Builder().setId("open").setOnClick(launch).build()
        val buttonLabel = getString(
            when {
                !status.armed -> R.string.action_start
                status.serviceRunning -> R.string.action_open
                else -> R.string.action_resume
            },
        )

        return LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .addContent(
                LayoutElementBuilders.Column.Builder()
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                    .addContent(text(getString(R.string.app_name), 12f, COLOR_SECONDARY))
                    .addContent(spacer(4f))
                    .addContent(text(statusText(this, status.key, status.armed), 16f, stateColor, maxLines = 2))
                    .addContent(spacer(4f))
                    .addContent(text(getString(R.string.pending_uploads, status.pendingUploads), 12f, COLOR_SECONDARY))
                    .addContent(spacer(10f))
                    .addContent(
                        LayoutElementBuilders.Box.Builder()
                            .setWidth(dp(96f))
                            .setHeight(dp(40f))
                            .setModifiers(
                                ModifiersBuilders.Modifiers.Builder()
                                    .setClickable(clickable)
                                    .setBackground(
                                        ModifiersBuilders.Background.Builder()
                                            .setColor(argb(if (recording) COLOR_BUTTON_DIM else COLOR_RECORDING))
                                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(20f)).build())
                                            .build(),
                                    )
                                    .build(),
                            )
                            .addContent(text(buttonLabel, 14f, COLOR_ON_BUTTON))
                            .build(),
                    )
                    .build(),
            )
            .build()
    }

    private fun text(value: String, sizeSp: Float, color: Int, maxLines: Int = 1) = LayoutElementBuilders.Text.Builder()
        .setText(value)
        .setMaxLines(maxLines)
        .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(sizeSp)).setColor(argb(color)).build())
        .build()

    private fun spacer(heightDp: Float) = LayoutElementBuilders.Spacer.Builder().setHeight(dp(heightDp)).build()

    private companion object {
        const val RESOURCES_VERSION = "1"
        const val FRESHNESS_MS = 15 * 60_000L
        const val COLOR_RECORDING = 0xFFE53935.toInt()
        const val COLOR_PAUSED = 0xFFFFB300.toInt()
        const val COLOR_STOPPED = 0xFFBDBDBD.toInt()
        const val COLOR_SECONDARY = 0xFFBDBDBD.toInt()
        const val COLOR_BUTTON_DIM = 0xFF424242.toInt()
        const val COLOR_ON_BUTTON = 0xFFFFFFFF.toInt()
    }
}

/** State for the tile/complication, readable whether or not the recorder runs in this process. */
data class RecorderStatusSnapshot(val key: String, val armed: Boolean, val serviceRunning: Boolean, val pendingUploads: Int) {
    companion object {
        fun read(context: Context): RecorderStatusSnapshot {
            val graph = AppGraph.of(context)
            val state = RecorderStore.state.value
            val key = if (state.serviceRunning) state.mode.statusKey() else "stopped"
            return RecorderStatusSnapshot(key, graph.prefs.armed, state.serviceRunning, graph.outbox.count())
        }
    }
}

/** Asks the tile and complication to redraw after a state change. */
object StatusSurfaces {
    fun refresh(context: Context) {
        runCatching { TileService.getUpdater(context).requestUpdate(RecorderTileService::class.java) }
        runCatching {
            ComplicationDataSourceUpdateRequester
                .create(context, ComponentName(context, RecorderComplicationService::class.java))
                .requestUpdateAll()
        }
    }
}
