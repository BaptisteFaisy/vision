package dev.sam3vision.core.net

import dev.sam3vision.core.TestHttpServer
import dev.sam3vision.core.model.LiveHealth
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveClientTest {

    private val validLive = """
        {"width":640,"height":480,"elapsed_ms":512,"objects":[
          {"label":"person","score":0.91,"box":[1,2,30,40],"mask_b64":"QUJD"},
          {"label":"dog","score":0.42,"box":[5,6,7,8],"mask_b64":"RERG"}
        ]}
    """.trimIndent()

    private var liveBody = validLive
    private var liveCode = 200

    private val server = TestHttpServer { request ->
        when (request.path) {
            "/live" -> TestHttpServer.Response(liveCode, if (liveCode == 200) liveBody else """{"detail":"bad"}""")
            "/health" -> TestHttpServer.Response(200, """{"ok":true,"model":"sam3.1","device":"cuda","gpu":"A100"}""")
            else -> TestHttpServer.Response(404, "{}")
        }
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `live parse une reponse valide`() = runBlocking {
        val frame = HttpLiveClient(server.baseUrl).live("SUVLFw==", listOf("person", "dog"), 640, 12).getOrThrow()

        assertEquals(640, frame.width)
        assertEquals(480, frame.height)
        assertEquals(512L, frame.elapsedMs)
        assertEquals(2, frame.objects.size)
        assertEquals("person", frame.objects[0].label)
        assertEquals(0.91f, frame.objects[0].score, 0.001f)
        assertEquals(listOf(1f, 2f, 30f, 40f), frame.objects[0].box)
        assertEquals("QUJD", frame.objects[0].maskPngB64)
        assertEquals("RERG", frame.objects[1].maskPngB64)
    }

    @Test
    fun `live envoie le corps attendu avec un User-Agent propre`() = runBlocking {
        HttpLiveClient(server.baseUrl).live("SUVLFw==", listOf("person"), 512, 6).getOrThrow()
        val request = server.requests.single()
        assertEquals("/live", request.path)
        assertEquals("POST", request.method)
        assertEquals(LIVE_USER_AGENT, request.headers["user-agent"])
        assertTrue(!request.headers.containsKey("authorization"))
        val sent = JSONObject(request.body)
        assertEquals("SUVLFw==", sent.getString("image_b64"))
        assertEquals("person", sent.getJSONArray("prompts").getString(0))
        assertEquals(512, sent.getInt("work_edge"))
        assertEquals(6, sent.getInt("max_objects"))
    }

    @Test
    fun `live prompts null part a null dans le corps`() = runBlocking {
        HttpLiveClient(server.baseUrl).live("QQ==", null, 640, 12).getOrThrow()
        assertTrue(JSONObject(server.requests.single().body).isNull("prompts"))
    }

    @Test
    fun `live 400 donne une ApiError Http typée`() {
        liveCode = 400
        val failure = runBlocking { HttpLiveClient(server.baseUrl).live("QQ==", null, 640, 12) }.exceptionOrNull()
        assertTrue(failure is ApiError.Http)
        assertEquals(400, (failure as ApiError.Http).code)
    }

    @Test
    fun `live reponse malformee donne BadResponse`() {
        liveBody = "ceci n'est pas du json"
        val failure = runBlocking { HttpLiveClient(server.baseUrl).live("QQ==", null, 640, 12) }.exceptionOrNull()
        assertTrue("attendu BadResponse, obtenu $failure", failure is ApiError.BadResponse)
    }

    @Test
    fun `live delai depasse donne ApiError Timeout`() {
        val slow = TestHttpServer { TestHttpServer.Response(200, validLive, delayMs = 2_000) }
        val impatient = NetContract.impatientClient()
        try {
            val failure = runBlocking { HttpLiveClient(slow.baseUrl, impatient).live("QQ==", null, 640, 12) }
                .exceptionOrNull()
            assertNotNull(failure)
            assertTrue("attendu Timeout, obtenu $failure", failure is ApiError.Timeout)
        } finally {
            slow.close()
        }
    }

    @Test
    fun `health parse ok model device et gpu`() = runBlocking {
        val health = HttpLiveClient(server.baseUrl).health().getOrThrow()
        assertEquals(LiveHealth(true, "sam3.1", "cuda", "A100"), health)
    }

    @Test
    fun `health en code non 2xx donne une erreur typée`() {
        val down = TestHttpServer { TestHttpServer.Response(503, "{}") }
        try {
            val failure = runBlocking { HttpLiveClient(down.baseUrl).health() }.exceptionOrNull()
            assertTrue(failure is ApiError.Http && failure.code == 503)
        } finally {
            down.close()
        }
    }

    @Test
    fun `le client du direct est partage et regle pour le reveil`() {
        // Changement de cap : plus de callTimeout de 10 s (le réveil GPU mesuré à 26,8 s
        // était tué, frame perdue et inférence facturée quand même). Le direct partage un
        // seul client pour toute l'app : DNS + TCP + TLS (71,3 ms) payés une seule fois.
        assertEquals(10L, LIVE_TIMEOUT_SECONDS)
        assertEquals(3_000, liveHttpClient().connectTimeoutMillis)
        assertEquals(5_000, liveHttpClient().writeTimeoutMillis)
        assertEquals(60_000, liveHttpClient().readTimeoutMillis)
        assertEquals(0, liveHttpClient().callTimeoutMillis)
        // Aucun rejeu de POST : la frame n'est jamais renvoyée (voir OneShotJsonBody).
        assertFalse(liveHttpClient().retryOnConnectionFailure)
    }

    @Test
    fun `deux clients live successifs partagent le meme pool de connexions`() {
        val premier = HttpLiveClient(server.baseUrl)
        val second = HttpLiveClient(server.baseUrl)

        assertSame(liveHttpClient(), premier.httpClient)
        assertSame(premier.httpClient, second.httpClient)
        // newBuilder (WebSocket, client minuté) garde le meme pool : tunnel TLS partagé.
        assertSame(premier.httpClient.connectionPool, liveWsClient(60L).connectionPool)
        assertSame(premier.httpClient.dispatcher, liveWsClient(60L).dispatcher)
    }

    @Test
    fun `le corps du direct n'est jamais rejoue`() {
        val body = OneShotJsonBody("""{"a":1}""")

        assertTrue("un corps one-shot interdit le re-POST", body.isOneShot())
        assertEquals(7L, body.contentLength())
        assertEquals(JSON_MEDIA, body.contentType())
    }

    @Test
    fun `le parse depuis les octets donne le meme resultat que depuis une chaine`() {
        val depuisChaine = parse(validLive)
        val depuisOctets = parseBytes(validLive.toByteArray(Charsets.UTF_8))

        assertNotNull(depuisOctets)
        assertEquals(depuisChaine.toString(), depuisOctets.toString())
        assertEquals(2, depuisOctets!!.objects("objects").size)
        assertEquals("QUJD", depuisOctets.objects("objects")[0].maskedFields().maskB64)
        assertNull("un corps illisible reste null, sans exception", parseBytes("pas du json".toByteArray()))
    }

    @Test
    fun `live demande des masques bits et lit l encodage servi`() = runBlocking {
        // Le client demande explicitement le format M1 (1 bit + zlib) …
        val frame = HttpLiveClient(server.baseUrl).live("SUVLFw==", listOf("person"), 512, 6).getOrThrow()
        assertEquals("bits", JSONObject(server.requests.single().body).getString("mask_encoding"))
        // … et retient ce que le backend dit avoir réellement envoyé.
        assertEquals("png", frame.maskEncoding)
    }

    @Test
    fun `live lit mask_encoding et timings_ms de la reponse`() = runBlocking {
        liveBody = """
            {"width":64,"height":40,"elapsed_ms":9,"mask_encoding":"BITS",
             "timings_ms":{"vision":41.5,"total":88.2,"queue":"du texte"},
             "objects":[{"label":"chaise","score":0.5,"box":[1,1,2,2],"mask_b64":"TTEIAAIA"}]}
        """.trimIndent()
        val frame = HttpLiveClient(server.baseUrl).live("QQ==", null, 640, 12).getOrThrow()

        assertEquals("bits", frame.maskEncoding)
        assertEquals(41.5, frame.timingsMs.getValue("vision"), 0.0001)
        assertEquals(88.2, frame.timingsMs.getValue("total"), 0.0001)
        assertEquals("un poste non numerique est ignore, sans exception", 2, frame.timingsMs.size)
        assertEquals(9L, frame.elapsedMs)
    }

    @Test
    fun `live sur un backend sans champs garde le comportement d avant`() = runBlocking {
        // Backend déployé actuel : ni mask_encoding ni timings_ms.
        liveBody = validLive
        val frame = HttpLiveClient(server.baseUrl).live("QQ==", null, 640, 12).getOrThrow()

        assertEquals("png", frame.maskEncoding)
        assertTrue(frame.timingsMs.isEmpty())
        assertEquals(512L, frame.elapsedMs)
    }

    @Test
    fun `le reveil a un delai patient dedie`() {
        // Un conteneur Modal froid met 30-50 s (jusqu'a ~150 s) : /health doit attendre
        // plus longtemps que le delai court de /live, sinon chaque reveil echoue avant la fin.
        assertTrue(WAKE_TIMEOUT_SECONDS >= 120L)
        assertTrue(WAKE_TIMEOUT_SECONDS > LIVE_TIMEOUT_SECONDS)
    }
}
