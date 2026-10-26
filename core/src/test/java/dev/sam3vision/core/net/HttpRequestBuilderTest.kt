package dev.sam3vision.core.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la **fabrique unique de requêtes** ([request], [requestTo], [jsonBody]) :
 * ce sont les garanties que les dix clients appelaient à la main, désormais écrites
 * une seule fois — et donc vérifiables une seule fois.
 */
class HttpRequestBuilderTest {

    private val base = "https://api.example.test/v1"

    /** Octets réellement escritos par le corps, pour comparer à l'entrée. */
    private fun RequestBody.bytes(): ByteArray {
        val buffer = Buffer()
        writeTo(buffer)
        return buffer.readByteArray()
    }

    @Test
    fun `get sans corps joint la base et le chemin`() {
        val request = requestTo(base, "/health")

        assertEquals("GET", request.method)
        assertNull(request.body)
        assertEquals("$base/health", request.url.toString())
    }

    @Test
    fun `post json porte le content type json et le corps exact`() {
        val json = """{"image_b64":"QUJD"}"""
        val request = requestTo(base, "/segment", "POST", jsonBody(json))

        assertEquals("POST", request.method)
        assertNotNull(request.body)
        assertTrue(request.body!!.contentType().toString().startsWith("application/json"))
        assertEquals(json, request.body!!.bytes().toString(Charsets.UTF_8))
        assertEquals(json.toByteArray(Charsets.UTF_8).size.toLong(), request.body!!.contentLength())
    }

    @Test
    fun `headers multiples sont poses avec user agent`() {
        val request = requestTo(
            base,
            "/speech-to-text",
            "POST",
            jsonBody("{}"),
            headers = mapOf("xi-api-key" to "cle", "Authorization" to "Bearer jeton"),
        )

        assertEquals("cle", request.header("xi-api-key"))
        assertEquals("Bearer jeton", request.header("Authorization"))
        assertEquals(USER_AGENT, request.header("User-Agent"))
        assertEquals(3, request.headers.size)
    }

    @Test
    fun `base avec slash final et chemin avec query restent corrects`() {
        val joined = requestTo("$base/", "/search?q=un+deux")
        val absolute = request("$base/text-to-speech/voix?output_format=mp3_44100_128", "POST", jsonBody("{}"))

        assertEquals("$base/search?q=un+deux", joined.url.toString())
        assertEquals("mp3_44100_128", absolute.url.queryParameter("output_format"))
        assertEquals("voix", absolute.url.pathSegments.last())
    }

    @Test
    fun `multipart garde son type et ses parts`() {
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model_id", "scribe_v1")
            .addFormDataPart("file", "audio.wav", byteArrayOf(1, 2, 3).toRequestBody("audio/wav".toMediaType()))
            .build()
        val request = requestTo(base, "/speech-to-text", "POST", multipart)

        assertTrue(request.body!!.contentType().toString().startsWith("multipart/form-data"))
        val written = request.body!!.bytes().toString(Charsets.UTF_8)
        assertTrue(written.contains("name=\"model_id\""))
        assertTrue(written.contains("audio.wav"))
    }

    @Test
    fun `corps binaire passe octets par octets`() {
        val wav = ByteArray(256) { (it % 7).toByte() }
        val request = request(base + "/asr/transcribe", "POST", wav.toRequestBody("application/octet-stream".toMediaType()))

        assertEquals(256L, request.body!!.contentLength())
        assertTrue(request.body!!.bytes().contentEquals(wav))
    }

    @Test
    fun `user agent est present par defaut et surchargeable`() {
        val standard = request(base + "/health")
        val live = request(base + "/live", "POST", jsonBody("{}"), userAgent = LIVE_USER_AGENT)

        assertEquals(USER_AGENT, standard.header("User-Agent"))
        assertEquals("$USER_AGENT live", live.header("User-Agent"))
        assertFalse(live.header("User-Agent") == standard.header("User-Agent"))
    }

    @Test
    fun `methode inconnue sans corps est acceptee telle quelle`() {
        val request = request(base + "/purge", "PURGE")

        assertEquals("PURGE", request.method)
        assertNull(request.body)
    }

    @Test
    fun `methode declaree sans corps mais avec corps est refusee`() {
        val failure = runCatching { request(base + "/health", "GET", jsonBody("{}")) }.exceptionOrNull()

        assertTrue("attendu IllegalArgumentException, obtenu $failure", failure is IllegalArgumentException)
    }

    @Test
    fun `une methode sans corps garde son chemin tel quel`() {
        val request = requestTo(base, "/cache/", "DELETE")

        assertEquals("DELETE", request.method)
        assertNull(request.body)
        assertEquals("$base/cache/", request.url.toString())
    }
}
