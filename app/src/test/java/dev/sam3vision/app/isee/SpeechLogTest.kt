package dev.sam3vision.app.isee

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Journal des phrases : format des lignes pures + envoi au sink injecté
 * (jamais d'appel direct à `Log` sous test — le sink est remplacé).
 */
class SpeechLogTest {

    private val sent = mutableListOf<Pair<String, String>>()
    private val defaultSink = SpeechLog.sink

    @Before
    fun capte() {
        sent.clear()
        SpeechLog.sink = { tag, line -> sent += tag to line }
    }

    @After
    fun restaure() {
        SpeechLog.sink = defaultSink
    }

    @Test
    fun `lignes pures au format journal`() {
        assertEquals("EXPECT voie=FENCE phrase=[Stop.]", lineExpect("FENCE", "Stop."))
        assertEquals("PLAY source=clip detail=[Stop.]", linePlay("clip", "Stop."))
        assertEquals("DONE phrase=[Stop.]", lineDone("Stop."))
        assertEquals("FAIL ou=bus cause=[écrasé]", lineFail("bus", "écrasé"))
    }

    @Test
    fun `expect et play partent au sink sous ISEE_VOIX`() {
        SpeechLog.expect("FENCE", "Stop.")
        SpeechLog.play("clip", "Stop.")
        assertEquals(
            listOf(
                SpeechLog.TAG to lineExpect("FENCE", "Stop."),
                SpeechLog.TAG to linePlay("clip", "Stop."),
            ),
            sent,
        )
        assertEquals("ISEE_VOIX", SpeechLog.TAG)
    }

    @Test
    fun `done et fail partent au sink sous ISEE_VOIX`() {
        SpeechLog.done("Stop.")
        SpeechLog.fail("bus", "écrasé")
        assertEquals(
            listOf(
                SpeechLog.TAG to lineDone("Stop."),
                SpeechLog.TAG to lineFail("bus", "écrasé"),
            ),
            sent,
        )
    }
}
