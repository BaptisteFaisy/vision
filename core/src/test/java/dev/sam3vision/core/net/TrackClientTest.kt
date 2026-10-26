package dev.sam3vision.core.net

import dev.sam3vision.core.TestHttpServer
import dev.sam3vision.core.model.TrackFrame
import dev.sam3vision.core.model.TrackHealth
import dev.sam3vision.core.model.TrackedObject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackClientTest {

    private val validSession = """
        {"session_id":"sess-1","width":1280,"height":720,"objects":[
          {"id":7,"label":"person","score":0.91,"box":[1,2,30,40],"mask_b64":"QUJD"},
          {"id":9,"label":"dog","score":0.42,"box":[5,6,7,8],"mask_b64":"RERG"}
        ]}
    """.trimIndent()

    private val validFrame = """
        {"width":1280,"height":720,"objects":[
          {"id":9,"label":"dog","score":0.55,"box":[5,6,9,10],"mask_b64":"RERG"},
          {"id":7,"label":"person","score":0.88,"box":[1,2,33,44],"mask_b64":"QUJD"}
        ]}
    """.trimIndent()

    private var sessionBody = validSession
    private var sessionCode = 200
    private var frameCode = 200

    private val server = TestHttpServer { request ->
        when {
            request.path == "/session" && request.method == "POST" ->
                TestHttpServer.Response(sessionCode, if (sessionCode == 200) sessionBody else """{"detail":"bad"}""")
            request.path == "/frame" ->
                TestHttpServer.Response(frameCode, if (frameCode == 200) validFrame else """{"detail":"nope"}""")
            request.path.startsWith("/session/") && request.method == "DELETE" ->
                TestHttpServer.Response(200, """{"ok":true}""")
            request.path == "/health" -> TestHttpServer.Response(
                200,
                """{"ok":true,"model":"sam3.1-tracker","gpu":"A100","max_objects":16}""",
            )
            else -> TestHttpServer.Response(404, "{}")
        }
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `session ouvre une session et parse les ids des objets`() = runBlocking {
        val start = HttpTrackClient(server.baseUrl).start("SUVL", listOf("person", "dog")).getOrThrow()

        assertEquals("sess-1", start.sessionId)
        assertEquals(1280, start.width)
        assertEquals(720, start.height)
        assertEquals(2, start.objects.size)
        assertEquals(7, start.objects[0].id)
        assertEquals(9, start.objects[1].id)
        assertEquals("person", start.objects[0].label)
        assertEquals(0.91f, start.objects[0].score, 0.001f)
        assertEquals(listOf(1f, 2f, 30f, 40f), start.objects[0].box)
        assertEquals("QUJD", start.objects[0].maskPngB64)
    }

    @Test
    fun `session poste image et prompts avec un User-Agent propre`() = runBlocking {
        HttpTrackClient(server.baseUrl).start("SUVL", listOf("person")).getOrThrow()
        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/session", request.path)
        assertEquals(TRACK_USER_AGENT, request.headers["user-agent"])
        assertTrue(!request.headers.containsKey("authorization"))
        val sent = JSONObject(request.body)
        assertEquals("SUVL", sent.getString("image_b64"))
        assertEquals(1, sent.getJSONArray("prompts").length())
        assertEquals("person", sent.getJSONArray("prompts").getString(0))
    }

    @Test
    fun `session sans session_id est une reponse inattendue`() {
        sessionBody = """{"width":10,"height":10,"objects":[]}"""
        val failure = runBlocking { HttpTrackClient(server.baseUrl).start("QQ", listOf("car")) }.exceptionOrNull()
        assertTrue("attendu BadResponse, obtenu $failure", failure is ApiError.BadResponse)
    }

    @Test
    fun `session 400 donne une ApiError Http typée`() {
        sessionCode = 400
        val failure = runBlocking { HttpTrackClient(server.baseUrl).start("QQ", listOf("car")) }.exceptionOrNull()
        assertTrue(failure is ApiError.Http && failure.code == 400)
    }

    @Test
    fun `frame conserve les ids stables d'un appel a l autre`() = runBlocking {
        val client = HttpTrackClient(server.baseUrl)
        val first = client.frame("sess-1", "AAA=").getOrThrow()
        val second = client.frame("sess-1", "BBB=").getOrThrow()

        assertEquals(listOf(9, 7), first.objects.map { it.id })
        assertEquals(listOf(9, 7), second.objects.map { it.id })
        assertEquals(
            first.objects.associate { it.id to it.label },
            second.objects.associate { it.id to it.label },
        )
        assertEquals(0.88f, second.objects.first { it.id == 7 }.score, 0.001f)
        assertEquals(1280, second.width)
    }

    @Test
    fun `frame envoie le session_id recu`() = runBlocking {
        HttpTrackClient(server.baseUrl).frame("sess-42", "Qw==").getOrThrow()
        val sent = JSONObject(server.requests.single().body)
        assertEquals("sess-42", sent.getString("session_id"))
        assertEquals("Qw==", sent.getString("image_b64"))
    }

    @Test
    fun `frame 404 est une session inconnue`() {
        frameCode = 404
        val failure = runBlocking { HttpTrackClient(server.baseUrl).frame("perdue", "Qw==") }.exceptionOrNull()
        assertTrue("attendu Http 404, obtenu $failure", failure is ApiError.Http && failure.code == 404)
    }

    @Test
    fun `frame malformee donne BadResponse`() {
        val broken = TestHttpServer { TestHttpServer.Response(200, "pas du json") }
        try {
            val failure = runBlocking { HttpTrackClient(broken.baseUrl).frame("s", "Qw==") }.exceptionOrNull()
            assertTrue(failure is ApiError.BadResponse)
        } finally {
            broken.close()
        }
    }

    @Test
    fun `frame delai depasse donne ApiError Timeout`() {
        val slow = TestHttpServer { TestHttpServer.Response(200, validFrame, delayMs = 2_000) }
        val impatient = defaultHttpClient(TRACK_TIMEOUT_SECONDS).newBuilder()
            .callTimeout(300, TimeUnit.MILLISECONDS)
            .readTimeout(300, TimeUnit.MILLISECONDS)
            .build()
        try {
            val failure = runBlocking { HttpTrackClient(slow.baseUrl, impatient).frame("s", "Qw==") }
                .exceptionOrNull()
            assertNotNull(failure)
            assertTrue("attendu Timeout, obtenu $failure", failure is ApiError.Timeout)
        } finally {
            slow.close()
        }
    }

    @Test
    fun `close envoie un DELETE sur la session`() = runBlocking {
        HttpTrackClient(server.baseUrl).close("sess-9").getOrThrow()
        val request = server.requests.single()
        assertEquals("DELETE", request.method)
        assertEquals("/session/sess-9", request.path)
    }

    @Test
    fun `health parse le modele le gpu et le nombre d objets`() = runBlocking {
        val health = HttpTrackClient(server.baseUrl).health().getOrThrow()
        assertEquals(TrackHealth(true, "sam3.1-tracker", "A100", 16), health)
    }

    @Test
    fun `le delai du tracker est bien de quinze secondes`() {
        assertEquals(15L, TRACK_TIMEOUT_SECONDS)
        assertEquals(NO_TRACK_ID, TrackedObject(NO_TRACK_ID, "x", 0f, emptyList(), "").id)
        assertTrue(TrackFrame(0, 0, emptyList()).objects.isEmpty())
    }
}