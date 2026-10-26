package dev.sam3vision.core.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contrat de transport des deux clients vocaux ElevenLabs, hors ligne.
 *
 * TTS (`/text-to-speech/{voice}`) : l'audio est le corps, la clé passe par `xi-api-key`.
 * STT (`/speech-to-text`) : multipart `file` + `model_id`, même clé.
 * Les cas déjà verrouillés par [ElevenLabsTtsClientTest] et [ElevenLabsSttClientTest]
 * (402, 401/429 du TTS, refus locaux) ne sont pas rejoués ; cette batterie ajoute la
 * matrice 400/500, le corps vide, le corps balisé, le timeout et les en-têtes réels.
 */
class VoiceContractTest {

    private fun tts(url: String) =
        ElevenLabsTtsClient(NetContract.KEY, NetContract.VOICE, baseUrl = url)

    private fun stt(url: String) = ElevenLabsSttClient(NetContract.KEY, baseUrl = url)

    // ---------------------------------------------------------------- TTS

    @Test
    fun `le TTS poste la cle xi-api-key et le User-Agent sans Authorization`() = runBlocking {
        NetContract.audioServer().use { server ->
            assertArrayEquals(NetContract.AUDIO, tts(server.baseUrl).synthesize("Bonjour.").getOrThrow())

            val request = server.requests.single()
            assertEquals("POST", request.method)
            assertEquals(NetContract.KEY, request.headers["xi-api-key"])
            assertEquals(USER_AGENT, request.headers["user-agent"])
            assertEquals("application/json; charset=utf-8", request.headers["content-type"])
            assertFalse("ElevenLabs n'utilise pas Bearer", request.headers.containsKey("authorization"))
        }
    }

    @Test
    fun `le TTS 400 reste une erreur Http 400`() = runBlocking {
        NetContract.assertCode("tts", 400, ApiError.Http::class.java) { url -> tts(url).synthesize("Bonjour.") }
    }

    @Test
    fun `le TTS 500 reste une erreur Http 500`() = runBlocking {
        NetContract.assertCode("tts", 500, ApiError.Http::class.java) { url -> tts(url).synthesize("Bonjour.") }
    }

    @Test
    fun `le TTS refuse un 200 sans un seul octet d audio`() = runBlocking {
        NetContract.server(body = "").use { server ->
            val result = tts(server.baseUrl).synthesize("Bonjour.")
            NetContract.assertError(result, ApiError.BadResponse::class.java, "tts corps vide")
        }
    }

    @Test
    fun `le TTS refuse un 200 qui porte du balisage`() = runBlocking {
        NetContract.server(body = "  \n{\"detail\":\"quota\"}").use { server ->
            val result = tts(server.baseUrl).synthesize("Bonjour.")
            NetContract.assertError(result, ApiError.BadResponse::class.java, "tts balisé")
            assertTrue(NetContract.errorOf(result).message!!.contains("audio vide"))
        }
    }

    /**
     * Contre-épreuve : un vrai MP3 commence par `ID3` (ASCII pur) puis des octets binaires.
     * Refuser *tout* texte ne doit pas refuser un audio légitime.
     */
    @Test
    fun `le TTS accepte un MP3 qui commence par ID3`() = runBlocking {
        val mp3 = "ID3".toByteArray(Charsets.UTF_8) + ByteArray(512) { (it * 7 and 0xFF).toByte() }
        NetContract.audioServer(bytes = mp3).use { server ->
            assertArrayEquals(mp3, tts(server.baseUrl).synthesize("Bonjour.").getOrThrow())
        }
    }

    /**
     * Un 200 ne suffit pas : le corps doit être de l'audio. Une page d'erreur texte
     * (`Bad Gateway`, `rate limited`) renvoyée avec un code 200 ne doit jamais partir dans
     * le lecteur — elle devient une erreur lisible.
     */
    @Test
    fun `le TTS refuse un corps texte en 200 au lieu de le jouer`() = runBlocking {
        NetContract.server(body = "Bad Gateway", contentType = "text/plain").use { server ->
            val result = tts(server.baseUrl).synthesize("Bonjour.")
            NetContract.assertError(result, ApiError.BadResponse::class.java, "tts texte")
        }
    }

    @Test
    fun `le TTS leve Timeout quand ElevenLabs ne repond pas`() = runBlocking {
        NetContract.slowServer().use { server ->
            val client = ElevenLabsTtsClient(
                NetContract.KEY,
                NetContract.VOICE,
                baseUrl = server.baseUrl,
                http = NetContract.impatientClient(),
            )
            NetContract.assertError(
                client.synthesize("Bonjour."),
                ApiError.Timeout::class.java,
                "tts lent",
            )
        }
    }

    // ---------------------------------------------------------------- STT

    @Test
    fun `le STT poste un multipart xi-api-key identifie`() = runBlocking {
        NetContract.server(body = """{"text":"bonjour"}""").use { server ->
            assertEquals("bonjour", stt(server.baseUrl).transcribe(NetContract.AUDIO).getOrThrow())

            val request = server.requests.single()
            assertEquals("POST", request.method)
            assertEquals(ElevenLabsSttClient.STT_PATH, request.path)
            assertEquals(NetContract.KEY, request.headers["xi-api-key"])
            assertEquals(USER_AGENT, request.headers["user-agent"])
            assertTrue(request.headers["content-type"].orEmpty().startsWith("multipart/form-data"))
            assertFalse(request.headers.containsKey("authorization"))
        }
    }

    @Test
    fun `le STT 400 reste une erreur Http 400`() = runBlocking {
        NetContract.assertCode("stt", 400, ApiError.Http::class.java) { url ->
            stt(url).transcribe(NetContract.AUDIO)
        }
    }

    @Test
    fun `le STT 401 devient InvalidKey`() = runBlocking {
        NetContract.assertCode("stt", 401, ApiError.InvalidKey::class.java) { url ->
            stt(url).transcribe(NetContract.AUDIO)
        }
    }

    @Test
    fun `le STT 429 devient RateLimited`() = runBlocking {
        NetContract.assertCode("stt", 429, ApiError.RateLimited::class.java) { url ->
            stt(url).transcribe(NetContract.AUDIO)
        }
    }

    @Test
    fun `le STT 500 reste une erreur Http 500`() = runBlocking {
        NetContract.assertCode("stt", 500, ApiError.Http::class.java) { url ->
            stt(url).transcribe(NetContract.AUDIO)
        }
    }

    @Test
    fun `le STT refuse un 200 au corps vide`() = runBlocking {
        NetContract.assertBadBody("stt", "") { url -> stt(url).transcribe(NetContract.AUDIO) }
    }

    @Test
    fun `le STT refuse un JSON sans champ texte`() = runBlocking {
        NetContract.assertBadBody("stt", """{"ok":true}""") { url -> stt(url).transcribe(NetContract.AUDIO) }
    }

    /**
     * Un corps qui n'est pas du JSON n'est pas une transcription : une page HTML d'erreur
     * servie en 200 ne doit jamais devenir la dictée affichée.
     */
    @Test
    fun `le STT refuse un corps non JSON au lieu de le rendre`() = runBlocking {
        NetContract.server(body = "<html>Bad Gateway</html>", contentType = "text/html").use { server ->
            val result = stt(server.baseUrl).transcribe(NetContract.AUDIO)
            NetContract.assertError(result, ApiError.BadResponse::class.java, "stt html")
        }
    }

    @Test
    fun `le STT leve Timeout quand ElevenLabs ne repond pas`() = runBlocking {
        NetContract.slowServer().use { server ->
            val client = ElevenLabsSttClient(
                NetContract.KEY,
                baseUrl = server.baseUrl,
                http = NetContract.impatientClient(),
            )
            NetContract.assertError(
                client.transcribe(NetContract.AUDIO),
                ApiError.Timeout::class.java,
                "stt lent",
            )
        }
    }
}