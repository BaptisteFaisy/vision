package dev.sam3vision.core.net

import dev.sam3vision.core.TestHttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SamClientTest {

    private val server = TestHttpServer { request ->
        when (request.path) {
            "/segment" -> segmentResponse(request)
            "/health" -> TestHttpServer.Response(200, """{"ok":true}""")
            else -> TestHttpServer.Response(404, "{}")
        }
    }

    private var segmentCode = 200

    private fun segmentResponse(@Suppress("UNUSED_PARAMETER") request: TestHttpServer.RecordedRequest) =
        if (segmentCode == 200) {
            TestHttpServer.Response(
                200,
                """
                {"width":640,"height":480,"objects":[
                  {"label":"chaise","score":0.93,"box":[10,20,110,220],"mask_b64":"AAAA"},
                  {"label":"table","score":0.81,"box":[0,0,50,50],"mask_b64":"BBBB"}
                ]}
                """.trimIndent(),
            )
        } else {
            TestHttpServer.Response(segmentCode, """{"detail":"bad"}""")
        }

    @After
    fun tearDown() = server.close()

    @Test
    fun `segment parse une reponse valide`() = runBlocking {
        val client = HttpSamClient(server.baseUrl)
        val result = client.segment("QUJD", listOf("chaise")).getOrThrow()

        assertEquals(640, result.width)
        assertEquals(480, result.height)
        assertEquals(2, result.objects.size)
        assertEquals("chaise", result.objects[0].label)
        assertEquals(0.93f, result.objects[0].score, 0.001f)
        assertEquals(listOf(10f, 20f, 110f, 220f), result.objects[0].box)
        assertEquals("AAAA", result.objects[0].maskPngB64)

        val sent = JSONObject(server.requests.single().body)
        assertEquals("QUJD", sent.getString("image_b64"))
        assertEquals("chaise", sent.getJSONArray("prompts").getString(0))
    }

    @Test
    fun `segment envoie un User-Agent et aucune cle`() = runBlocking {
        HttpSamClient(server.baseUrl).segment("QUJD", null).getOrThrow()
        val headers = server.requests.single().headers
        assertEquals(USER_AGENT, headers["user-agent"])
        assertTrue(!headers.containsKey("authorization"))
        assertTrue(JSONObject(server.requests.single().body).isNull("prompts"))
    }

    @Test
    fun `segment 400 donne une erreur typee Http`() {
        segmentCode = 400
        val failure = runBlocking { HttpSamClient(server.baseUrl).segment("QUJD", null) }.exceptionOrNull()
        assertTrue(failure is ApiError.Http)
        assertEquals(400, (failure as ApiError.Http).code)
    }

    @Test
    fun `health renvoie true quand le backend repond ok`() = runBlocking {
        assertEquals(true, HttpSamClient(server.baseUrl).health().getOrThrow())
    }

    @Test
    fun `health renvoie false sur un code non 2xx`() {
        val down = TestHttpServer { TestHttpServer.Response(503, "{}") }
        try {
            assertEquals(false, runBlocking { HttpSamClient(down.baseUrl).health().getOrThrow() })
        } finally {
            down.close()
        }
    }

    @Test
    fun `timeout court donne une erreur Timeout`() {
        val slow = TestHttpServer { TestHttpServer.Response(200, "{}", delayMs = 2_000) }
        val client = NetContract.impatientClient()
        try {
            val failure = runBlocking { HttpSamClient(slow.baseUrl, client).health() }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue("attendu Timeout, obtenu $failure", failure is ApiError.Timeout)
        } finally {
            slow.close()
        }
    }
}
