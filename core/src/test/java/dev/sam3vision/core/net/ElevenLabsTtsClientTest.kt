package dev.sam3vision.core.net

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ElevenLabsTtsClientTest {

    @Test
    fun `audio 200 renvoye tel quel et requete conforme`() = runBlocking {
        NetContract.audioServer().use { server ->
            val client = ElevenLabsTtsClient(NetContract.KEY, NetContract.VOICE, baseUrl = server.baseUrl)
            val bytes = client.synthesize("Bonjour SAM Vision.").getOrThrow()

            assertArrayEquals(NetContract.AUDIO, bytes)
            val request = server.requests.single()
            assertEquals("POST", request.method)
            assertTrue(request.path, request.path.startsWith("/text-to-speech/${NetContract.VOICE}"))
            assertTrue(request.path, request.path.contains("output_format=mp3_44100_128"))
            assertEquals(NetContract.KEY, request.headers["xi-api-key"])
            val body = JSONObject(request.body)
            assertEquals("Bonjour SAM Vision.", body.getString("text"))
            assertEquals("eleven_v4_turbo", body.getString("model_id"))
            assertTrue(body.has("voice_settings"))
        }
    }

    @Test
    fun `402 sans credits remonte PaymentRequired avec un message clair`() = runBlocking {
        NetContract.server(code = 402).use { server ->
            val client = ElevenLabsTtsClient(NetContract.KEY, NetContract.VOICE, baseUrl = server.baseUrl)
            val error = client.synthesize("Bonjour.").exceptionOrNull()

            assertTrue(error.toString(), error is ApiError.PaymentRequired)
            assertTrue(error!!.message!!.contains("402"))
            assertTrue(error.message!!.contains("crédits"))
        }
    }

    @Test
    fun `401 remonte InvalidKey et 429 RateLimited`() = runBlocking {
        NetContract.server(code = 401).use { server ->
            val error = ElevenLabsTtsClient(NetContract.KEY, NetContract.VOICE, baseUrl = server.baseUrl)
                .synthesize("Bonjour.").exceptionOrNull()
            assertTrue(error.toString(), error is ApiError.InvalidKey)
        }
        NetContract.server(code = 429).use { server ->
            val error = ElevenLabsTtsClient(NetContract.KEY, NetContract.VOICE, baseUrl = server.baseUrl)
                .synthesize("Bonjour.").exceptionOrNull()
            assertTrue(error.toString(), error is ApiError.RateLimited)
        }
    }

    @Test
    fun `texte vide et cle absente echouent sans appel reseau`() = runBlocking {
        NetContract.audioServer().use { server ->
            val client = ElevenLabsTtsClient(NetContract.KEY, NetContract.VOICE, baseUrl = server.baseUrl)
            val vide = client.synthesize("   ").exceptionOrNull()
            val sansCle = ElevenLabsTtsClient("", NetContract.VOICE, baseUrl = server.baseUrl)
                .synthesize("Bonjour.").exceptionOrNull()

            assertTrue(vide.toString(), vide is ApiError.BadResponse)
            assertTrue(sansCle.toString(), sansCle is ApiError.InvalidKey)
            assertTrue(sansCle!!.message!!.contains("Réglages"))
            assertEquals(0, server.requests.size)
        }
    }

    @Test
    fun `voix absente refusee localement`() = runBlocking {
        NetContract.audioServer().use { server ->
            val error = ElevenLabsTtsClient(NetContract.KEY, "", baseUrl = server.baseUrl)
                .synthesize("Bonjour.").exceptionOrNull()

            assertTrue(error.toString(), error is ApiError.BadResponse)
            assertEquals(0, server.requests.size)
        }
    }

    @Test
    fun `reponse 200 sans audio remonte une erreur lisible`() = runBlocking {
        NetContract.server(body = """{"detail":"rate limited"}""").use { server ->
            val error = ElevenLabsTtsClient(NetContract.KEY, NetContract.VOICE, baseUrl = server.baseUrl)
                .synthesize("Bonjour.").exceptionOrNull()

            assertTrue(error.toString(), error is ApiError.BadResponse)
            assertTrue(error!!.message!!.contains("audio vide"))
        }
    }

    @Test
    fun `le voice id est echappe dans l URL`() = runBlocking {
        NetContract.audioServer().use { server ->
            ElevenLabsTtsClient(NetContract.KEY, "voix bizarro/../x", baseUrl = server.baseUrl)
                .synthesize("Bonjour.")

            val path = server.requests.single().path
            assertTrue(path, path.contains("voix%20bizarro"))
            assertTrue(path, !path.contains("../"))
        }
    }
}