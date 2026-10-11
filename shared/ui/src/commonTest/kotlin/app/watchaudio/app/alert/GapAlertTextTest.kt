package app.watchaudio.app.alert

import app.watchaudio.app.logic.TOKYO
import app.watchaudio.app.logic.at
import kotlin.test.Test
import kotlin.test.assertEquals

class GapAlertTextTest {
    @Test
    fun messageNamesTheLastArrival() {
        assertEquals("録音が届いていません（最後の受信: 14:56）。Watch を確認してください", GapAlertText.message(at(14, 56), TOKYO))
    }
}
