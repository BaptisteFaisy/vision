package dev.sam3vision.core.net

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Trames et parsing de Scribe realtime, sans socket : URL et en-têtes du
 * handshake, forme des `input_audio_chunk`, lecture des événements serveur
 * et barème d'erreurs. Le cycle de vie (session, commit, replis) vit dans
 * [ScribeRealtimeContractTest].
 */
class ScribeRealtimeClientTest {

    @Test
    fun `l'URL realtime porte modele commit manuel et pcm 16000`() {
        assertEquals(
            "wss://api.elevenlabs.io/v1/speech-to-text/realtime" +
                "?model_id=scribe_v2_realtime&commit_strategy=manual&audio_format=pcm_16000",
            scribeRealtimeUrl("https://api.elevenlabs.io", "scribe_v2_realtime"),
        )
    }

    @Test
    fun `une base http devient un handshake ws`() {
        val url = scribeRealtimeUrl("http://127.0.0.1:9", "scribe_v2_realtime")

        assertTrue(url.startsWith("ws://127.0.0.1:9/v1/speech-to-text/realtime?"))
    }

    @Test
    fun `la trame chunk porte le pcm en base64 avec commit et sample rate`() {
        val sent = JSONObject(chunkMessage(byteArrayOf(1, 2, 3), commit = false))

        assertEquals("input_audio_chunk", sent.getString("message_type"))
        assertEquals("AQID", sent.getString("audio_base_64"))
        assertFalse(sent.getBoolean("commit"))
        assertEquals(16_000, sent.getInt("sample_rate"))
    }

    @Test
    fun `la trame de commit ne differe que par commit true`() {
        val sent = JSONObject(chunkMessage(byteArrayOf(1, 2, 3), commit = true))

        assertTrue(sent.getBoolean("commit"))
        assertEquals("AQID", sent.getString("audio_base_64"))
    }

    @Test
    fun `session started partiel et committed se lisent`() {
        assertTrue(parseScribeEvent("""{"message_type":"session_started","session_id":"s1"}""") is ScribeEvent.Started)

        val partial = parseScribeEvent("""{"message_type":"partial_transcript","text":"where is"}""")
        assertEquals(ScribeEvent.Partial("where is"), partial)

        val committed = parseScribeEvent("""{"message_type":"committed_transcript","text":"where is the door"}""")
        assertEquals(ScribeEvent.Committed("where is the door"), committed)

        val stamped = parseScribeEvent(
            """{"message_type":"committed_transcript_with_timestamps","text":"go","words":[]}""",
        )
        assertEquals(ScribeEvent.Committed("go"), stamped)
    }

    @Test
    fun `les refus typés suivent le barème des clients HTTP`() {
        val auth = parseScribeEvent("""{"message_type":"auth_error","error":"bad key"}""")
        assertTrue((auth as ScribeEvent.Failed).error is ApiError.InvalidKey)

        val quota = parseScribeEvent("""{"message_type":"quota_exceeded","error":"no credits"}""")
        assertTrue((quota as ScribeEvent.Failed).error is ApiError.PaymentRequired)

        listOf("rate_limited", "commit_throttled").forEach { type ->
            val limited = parseScribeEvent("""{"message_type":"$type","error":"slow down"}""")
            assertTrue("$type doit limiter", (limited as ScribeEvent.Failed).error is ApiError.RateLimited)
        }
    }

    @Test
    fun `les erreurs serveur nomment leur type`() {
        listOf("error", "queue_overflow", "chunk_size_exceeded", "input_error", "transcriber_error").forEach { type ->
            val event = parseScribeEvent("""{"message_type":"$type","error":"boom"}""") as ScribeEvent.Failed
            val error = event.error
            assertTrue("$type doit être BadResponse, obtenu $error", error is ApiError.BadResponse)
            assertTrue("le type doit rester lisible : ${error.message}", error.message!!.contains(type))
        }
    }

    @Test
    fun `warning futur et JSON illisible ignorés sans tuer la session`() {
        assertEquals(
            ScribeEvent.Ignored,
            parseScribeEvent("""{"message_type":"warning","warning":"noisy"}"""),
        )
        assertEquals(
            ScribeEvent.Ignored,
            parseScribeEvent("""{"message_type":"language_detected","language_code":"fr"}"""),
        )
        assertEquals(ScribeEvent.Ignored, parseScribeEvent("""{"message_type":""}"""))
        assertNull(parseScribeEvent("pas du json"))
    }

    @Test
    fun `le base64 du chunk rejoint le PCM d'origine`() {
        val pcm = ByteArray(5_000) { (it % 251).toByte() }

        val sent = JSONObject(chunkMessage(pcm, commit = false))
        val decoded = Base64.getDecoder().decode(sent.getString("audio_base_64"))

        assertTrue(pcm.contentEquals(decoded))
    }
}
