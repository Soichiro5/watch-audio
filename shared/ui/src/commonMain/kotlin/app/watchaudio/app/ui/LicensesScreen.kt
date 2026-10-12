package app.watchaudio.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.watchaudio.app.Strings
import app.watchaudio.app.licenses.License
import app.watchaudio.app.licenses.NoticeApp
import app.watchaudio.app.licenses.NoticeKind
import app.watchaudio.app.licenses.OpenSourceNotice
import app.watchaudio.app.licenses.OpenSourceNotices

/**
 * The third-party libraries and models of [apps] (#167): name, author, license and URL of each,
 * then every license once (the short permissive ones in full, collapsed). All text is selectable,
 * since links do not open from here.
 */
@Composable
internal fun LicensesScreen(apps: Set<NoticeApp>, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val notices = remember(apps) { OpenSourceNotices.forApps(apps) }
    Surface(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) {
                    Icon(AppIcons.ChevronLeft, contentDescription = null)
                    Text(Strings.LICENSES_BACK)
                }
                Text(Strings.LICENSES_TITLE, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
            }
            SelectionContainer {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(Strings.LICENSES_INTRO, style = MaterialTheme.typography.bodyMedium)
                    val models = notices.filter { it.kind == NoticeKind.MODEL }
                    if (models.isNotEmpty()) NoticeGroup(Strings.LICENSES_MODELS, models, apps)
                    NoticeGroup(Strings.LICENSES_LIBRARIES, notices.filter { it.kind == NoticeKind.LIBRARY }, apps)
                    LicenseTexts(OpenSourceNotices.licensesOf(notices))
                }
            }
        }
    }
}

@Composable
private fun NoticeGroup(title: String, notices: List<OpenSourceNotice>, apps: Set<NoticeApp>) {
    Section(title) {
        notices.forEachIndexed { index, notice ->
            if (index > 0) HorizontalDivider()
            NoticeEntry(notice, apps)
        }
    }
}

@Composable
private fun ColumnScope.NoticeEntry(notice: OpenSourceNotice, apps: Set<NoticeApp>) {
    Text(notice.name, style = MaterialTheme.typography.bodyLarge)
    Detail(Strings.licenseAuthor(notice.author))
    Detail(Strings.licenseName(notice.license.title))
    notice.copyright?.let { Detail(it) }
    notice.includedIn?.let { Detail(Strings.licenseIncludedIn(it)) }
    // Which app, only when the screen covers more than one (the phone app and its watch app).
    if (apps.size > 1) {
        Detail(Strings.licenseApps(notice.apps.filter { it in apps }.sortedBy { it.ordinal }.joinToString("・") { Strings.licenseApp(it) }))
    }
    Detail(Strings.licenseUrl(notice.url))
    notice.downloads.forEach { Detail(Strings.licenseDownloadedFrom(it)) }
    if (notice.license == License.FUNASR_MODEL_1_1) {
        Detail(Strings.FUNASR_TERMS_SUMMARY)
        Detail(Strings.licenseTextAt(notice.license.url))
    }
}

/** Each license once: the full text behind a toggle where we carry it, otherwise where to read it. */
@Composable
private fun LicenseTexts(licenses: List<License>) {
    val expanded = remember { mutableStateListOf<License>() }
    Section(Strings.LICENSES_TEXTS) {
        licenses.forEachIndexed { index, license ->
            if (index > 0) HorizontalDivider()
            Text(license.title, style = MaterialTheme.typography.bodyLarge)
            Detail(Strings.licenseUrl(license.url))
            val text = license.text
            if (text != null) {
                val open = license in expanded
                TextButton(onClick = { if (open) expanded.remove(license) else expanded.add(license) }) {
                    Text(if (open) Strings.HIDE_LICENSE_TEXT else Strings.SHOW_LICENSE_TEXT)
                }
                if (open) Text(text, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Detail(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
