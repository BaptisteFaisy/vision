package dev.sam3vision.app.isee

import dev.sam3vision.core.distance.CameraPose
import dev.sam3vision.core.distance.DistanceMeter
import dev.sam3vision.core.live.LiveSession
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.LiveHealth
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.net.LiveClient
import dev.sam3vision.core.net.WsLiveClient
import dev.sam3vision.core.net.WsTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Détections + distances d'une frame SAM : chaque objet segmenté part à la
 * voix (score 1, comme l'écran), mesuré ou non — une voiture vue est dite.
 */
class ProbeFrameTest {

    private fun frame(objects: List<SegObject>, width: Int = 640, height: Int = 480) =
        LiveFrame(width, height, elapsedMs = 100L, objects = objects)

    @Test
    fun `voiture confirmee score 1 et mesuree`() {
        val car = SegObject("car", 0.4f, listOf(270f, 200f, 370f, 280f), maskPngB64 = "")
        val (detections, readings) = detectionsAndReadingsOf(DistanceMeter(), frame(listOf(car)))

        assertEquals(1, detections.size)
        assertEquals(1f, detections.single().score, 0f)
        assertEquals("car", detections.single().label)
        assertEquals(1, readings.size)
    }

    @Test
    fun `frame vide ou sans dimensions - rien`() {
        val (emptyDetections, emptyReadings) = detectionsAndReadingsOf(DistanceMeter(), frame(emptyList()))
        assertTrue(emptyDetections.isEmpty())
        assertTrue(emptyReadings.isEmpty())

        val car = SegObject("car", 0.9f, listOf(270f, 200f, 370f, 280f), maskPngB64 = "")
        val (noSizeDetections, noSizeReadings) =
            detectionsAndReadingsOf(DistanceMeter(), frame(listOf(car), width = 0, height = 0))
        assertTrue(noSizeDetections.isEmpty())
        assertTrue(noSizeReadings.isEmpty())
    }

    @Test
    fun `sol ouvert, la pose effective est la pose IMU`() {
        val meter = DistanceMeter()
        meter.pose = CameraPose(1.5f, 0.1f)
        assertEquals(CameraPose(1.5f, 0.1f), meter.effectivePose())
    }

    @Test
    fun `sol coupe en lunettes-live, la pose effective est nulle`() {
        val meter = DistanceMeter()
        meter.pose = CameraPose(1.5f, 0.1f)
        meter.groundPlaneEnabled = false
        assertNull(meter.effectivePose())
    }

    @Test
    fun `sans pose, la pose effective est nulle sol ouvert ou coupe`() {
        val meter = DistanceMeter()
        assertNull(meter.effectivePose())
        meter.groundPlaneEnabled = false
        assertNull(meter.effectivePose())
    }

    @Test
    fun `libelle sonde dit le cellulaire impose en lunettes`() {
        val open = LiveSession(openWsClient(), clock = { 0L })
        assertEquals("WebSocket", transportLabel(open, cellular = false))
        assertEquals("WebSocket (cell)", transportLabel(open, cellular = true))
        open.close()
    }

    @Test
    fun `repli HTTP dit HTTP sur la sonde`() = runBlocking {
        val refusing = object : WsTransport {
            override fun open(
                url: String,
                onText: (String) -> Unit,
                onClosed: (Throwable?) -> Unit,
                onOpen: () -> Unit,
                headers: Map<String, String>,
            ): Boolean = false

            override fun send(text: String): Boolean = false

            override fun sendBytes(bytes: ByteArray): Boolean = false

            override fun close() = Unit
        }
        val http = object : LiveClient {
            override suspend fun live(jpegB64: String, prompts: List<String>?, workEdge: Int, maxObjects: Int) =
                Result.success(LiveFrame(512, 1138, 118, emptyList()))

            override suspend fun health(): Result<LiveHealth> =
                Result.success(LiveHealth(ok = true, model = "sam3.1", device = "cuda", gpu = "A100"))
        }
        val session = LiveSession(WsLiveClient("https://exemple.invalid", transport = refusing, http = http), clock = { 0L })

        session.runFrame("QQ==")

        assertTrue(session.httpFallback)
        assertEquals("HTTP", transportLabel(session, cellular = false))
        session.close()
    }

    /** Client WS optimiste : `preconnect` suffit à ouvrir le socket, sans réseau. */
    private fun openWsClient(): WsLiveClient {
        val transport = object : WsTransport {
            override fun open(
                url: String,
                onText: (String) -> Unit,
                onClosed: (Throwable?) -> Unit,
                onOpen: () -> Unit,
                headers: Map<String, String>,
            ): Boolean = true

            override fun send(text: String): Boolean = true

            override fun sendBytes(bytes: ByteArray): Boolean = true

            override fun close() = Unit
        }
        return WsLiveClient("https://exemple.invalid", transport = transport).also { it.preconnect() }
    }
}
