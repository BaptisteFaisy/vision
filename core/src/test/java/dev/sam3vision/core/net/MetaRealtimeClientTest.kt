package dev.sam3vision.core.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Trames et parsing de Muse realtime, sans socket : URL, poignée
 * d'ouverture, lecture des événements serveur et typage des erreurs. Le
 * cycle de vie (session, fin de tour, replis) vit dans
 * [MetaRealtimeContractTest].
 */
class MetaRealtimeClientTest {

    @Test
    fun `l'URL realtime dérive de la base ASR`() {
        assertEquals(
            "wss://api.meta.ai/v1/asr/realtime",
            museRealtimeUrl("https://api.meta.ai/v1"),
        )
    }

    @Test
    fun `un pont local devient un handshake ws`() {
        assertEquals(
            "ws://127.0.0.1:8788/asr/realtime",
            museRealtimeUrl("http://127.0.0.1:8788"),
        )
    }

    @Test
    fun `la poignée porte mode clé encodage modèle et partiels cumulatifs`() {
        val sent = JSONObject(museHandshake("cle-meta", "muse-voice-transcribe-1.0"))

        assertEquals("PUSH_TO_TALK", sent.getString("mode"))
        assertEquals("cle-meta", sent.getJSONObject("authorization").getString("accessToken"))
        assertEquals("PCM_16KHZ", sent.getString("audioEncoding"))
        assertEquals("muse-voice-transcribe-1.0", sent.getString("model"))
        assertEquals("CUMULATIVE", sent.getString("partialMode"))
        assertFalse(sent.getBoolean("emitAudioProgress"))
    }

    @Test
    fun `la poignée échappe clé et modèle saisis`() {
        val sent = JSONObject(museHandshake("a\"b\\c", "m\"x"))

        assertEquals("a\"b\\c", sent.getJSONObject("authorization").getString("accessToken"))
        assertEquals("m\"x", sent.getString("model"))
    }

    @Test
    fun `la fin de tour est une trame endStream`() {
        assertEquals("""{"type":"endStream"}""", MetaRealtimeSession.END_STREAM)
    }

    @Test
    fun `sessionId nu partiel final et speechComplete se lisent`() {
        assertEquals(MuseEvent.Started, parseMuseEvent("""{"sessionId":"abc"}"""))

        val partial = parseMuseEvent("""{"type":"transcript","transcript":"where is","final":false}""")
        assertEquals(MuseEvent.Partial("where is"), partial)

        val done = parseMuseEvent("""{"type":"transcript","transcript":"where is the door","final":true}""")
        assertEquals(MuseEvent.Committed("where is the door"), done)

        val turn = parseMuseEvent("""{"type":"speechComplete","turnId":0,"speaker":"A","transcript":"go"}""")
        assertEquals(MuseEvent.Committed("go"), turn)
    }

    @Test
    fun `locuteur bornes de tour et JSON illisible ignorés sans tuer la session`() {
        assertEquals(MuseEvent.Ignored, parseMuseEvent("""{"type":"speaker","label":"A"}"""))
        assertEquals(MuseEvent.Ignored, parseMuseEvent("""{"type":"speechStart","turnId":0}"""))
        assertEquals(MuseEvent.Ignored, parseMuseEvent("""{"type":"speechEnd","turnId":0}"""))
        assertEquals(MuseEvent.Ignored, parseMuseEvent("""{"type":"futur","x":1}"""))
        assertEquals(MuseEvent.Ignored, parseMuseEvent("""{"type":"transcript","transcript":"x"}"""))
        assertNull(parseMuseEvent("pas du json"))
    }

    @Test
    fun `les erreurs serveur suivent le barème du batch par mots-clés`() {
        listOf("invalid auth token", "unauthorized", "bad credential", "error 401", "forbidden")
            .forEach { message ->
                val error = (parseMuseEvent(errorOf(message)) as MuseEvent.Failed).error
                assertTrue("$message doit être InvalidKey, obtenu $error", error is ApiError.InvalidKey)
            }
        listOf("billing_not_configured", "402 payment required", "no credits left", "quota exceeded")
            .forEach { message ->
                val error = (parseMuseEvent(errorOf(message)) as MuseEvent.Failed).error
                assertTrue("$message doit être PaymentRequired, obtenu $error", error is ApiError.PaymentRequired)
            }
        listOf("rate limited, back off", "429 too many requests", "throttled")
            .forEach { message ->
                val error = (parseMuseEvent(errorOf(message)) as MuseEvent.Failed).error
                assertTrue("$message doit être RateLimited, obtenu $error", error is ApiError.RateLimited)
            }
    }

    @Test
    fun `une erreur inconnue reste une BadResponse nommée`() {
        val event = parseMuseEvent(errorOf("kaput")) as MuseEvent.Failed
        val error = event.error

        assertTrue(error is ApiError.BadResponse)
        assertTrue("le message doit rester lisible : ${error.message}", error.message!!.contains("kaput"))
    }

    private fun errorOf(message: String): String =
        """{"type":"error","message":"$message"}"""
}
