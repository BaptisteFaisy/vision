package dev.sam3vision.app.live

import dev.sam3vision.core.live.FrameTimings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Journal de latence perçue : la ligne logcat est exactement la ligne d'écran
 * ([TimingsText.compact]), et seules les frames complètes partent — une frame sans
 * poste capteur ou sans pixel ne doit jamais entrer dans la série du 500/300.
 */
class LiveLatencyLogTest {

    private fun complete() = FrameTimings(
        captureToJpegMs = 22,
        jpegToSentMs = 61,
        sentToReceivedMs = 153,
        serverTotalMs = 92,
        receivedToOverlaysMs = 37,
        overlaysToPixelMs = 8,
        captureToPixelMs = 348,
    )

    @Test
    fun `ligne complete reprend la ligne d'ecran octet pour octet`() {
        assertEquals(
            "latency 348 · capt 22 · up 61 · srv 92 · rtt 153 · mask 37 · pixel 8",
            LiveLatencyLog.line(complete()),
        )
    }

    @Test
    fun `ligne complete respecte le contrat du parseur`() {
        val line = LiveLatencyLog.line(complete())!!
        assertTrue(
            "contrat scripts/parse_isee_lat.py : $line",
            line.matches(Regex("""latency \d+( · [a-z]+ (\d+|—))+""")),
        )
    }

    @Test
    fun `frame sans pixel ne part pas en logcat`() {
        assertNull(LiveLatencyLog.line(complete().copy(captureToPixelMs = -1)))
    }

    @Test
    fun `frame vide ne part pas en logcat`() {
        assertNull(LiveLatencyLog.line(FrameTimings()))
    }

    @Test
    fun `tag unique du journal`() {
        assertEquals("ISEE_LAT", LiveLatencyLog.TAG)
    }

    @Test
    fun `ligne serveur reprend le detail timings`() {
        val t = complete().copy(server = mapOf("detr" to 52.4, "preprocess" to 7.1))
        assertEquals("server: preprocess 7 ms · detr 52 ms", LiveLatencyLog.serverLine(t))
    }

    @Test
    fun `ligne serveur absente sans detail`() {
        assertNull(LiveLatencyLog.serverLine(complete()))
    }

    @Test
    fun `ligne serveur absente si frame incomplete`() {
        val t = complete().copy(captureToPixelMs = -1, server = mapOf("detr" to 52.0))
        assertNull(LiveLatencyLog.serverLine(t))
    }

    @Test
    fun `ligne serveur ignoree par le parseur`() {
        val t = complete().copy(server = mapOf("detr" to 52.4))
        val line = LiveLatencyLog.serverLine(t)!!
        assertFalse(
            "le parseur ne retient que les lignes latency N : $line",
            line.contains(Regex("""(^| )latency \d""")),
        )
    }

    @Test
    fun `l annonce de jambe ne leve jamais`() {
        // `Log.i` rend sa valeur par défaut en JVM (testOptions) : l'appel prouve
        // seulement le câblage, la chaîne est prouvée côté core (TimingsTextTest).
        LiveLatencyLog.leg(true)
        LiveLatencyLog.leg(false)
    }
}
