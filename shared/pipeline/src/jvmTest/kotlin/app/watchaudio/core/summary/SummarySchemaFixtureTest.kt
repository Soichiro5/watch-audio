package app.watchaudio.core.summary

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The output schema as Swift receives it (#196): `testdata/summary/hour-summary-schema.json` is
 * what [SummaryPrompts.SCHEMA] encodes to, and `apps/ios/Tests/LanguageModelTests` builds Foundation
 * Models' schema from the same file. Changing the schema means updating the file (the failure shows
 * the new JSON), so both sides are tested on it.
 */
class SummarySchemaFixtureTest {
    @Test
    fun theFixtureIsTheSchemaJson() {
        assertEquals(File("testdata/summary/hour-summary-schema.json").readText().trim(), SummaryPrompts.SCHEMA.toJson())
    }
}
