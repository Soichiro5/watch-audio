package app.watchaudio.android

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick

/**
 * Taps a bottom navigation tab once it is on screen. The settings load asynchronously (an empty
 * surface until then) and the first launch after a fresh install is slow, so this waits; the tab's
 * label is merged into its navigation item, which is why the unmerged tree is searched.
 */
internal fun ComposeTestRule.openTab(label: String) {
    waitUntil(timeoutMillis = 20_000) {
        onAllNodes(hasText(label), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }
    onNodeWithText(label, useUnmergedTree = true).performClick()
}
