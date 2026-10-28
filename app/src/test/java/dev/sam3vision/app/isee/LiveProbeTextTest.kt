package dev.sam3vision.app.isee

import dev.sam3vision.app.DistanceLayer
import dev.sam3vision.core.live.FrameTimings
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.distance.DistanceText
import dev.sam3vision.core.net.ApiError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Formatage du panneau de debug : une seule implémentation, testée sans Android. */
class LiveProbeTextTest {

    private fun ready() = LiveProbeState(
        endpoint = "https://baptiste-faisy--sam3-eu-a100-sam3live-web.eu-west.modal.run",
        labels = listOf("person", "chair"),
        conceptsSent = listOf("person", "chair", "table", "laptop", "bottle", "cup", "bag", "door"),
        objects = 8,
        fps = 1.4f,
        latencyMs = 640,
        p95Ms = 910,
        serverMs = 520,
        width = 512,
        height = 384,
        gpu = "A100-SXM4-40GB",
        frames = 12,
    )

    @Test
    fun `ligne compacte avec des chiffres`() {
        val line = liveProbeHeadline(ready())
        assertTrue(line.startsWith("SAM · 1,4 ips"))
        assertTrue(line.contains("640 ms"))
        assertTrue(line.contains("8 objets"))
        assertTrue(line.endsWith("8 objets"))
    }

    @Test
    fun `ligne compacte avant la premiere frame`() {
        val line = liveProbeHeadline(LiveProbeState())
        assertEquals("SAM · … · … · …", line)
    }

    @Test
    fun `ligne compacte sur erreur sans frame`() {
        val line = liveProbeHeadline(LiveProbeState(error = "aucun endpoint (Réglages)"))
        assertEquals("SAM · aucun endpoint (Réglages)", line)
    }

    @Test
    fun `detail complet quand une frame est passee`() {
        val details = liveProbeDetails(ready())
        assertTrue(details.any { it.startsWith("endpoint : baptiste-faisy--") })
        assertTrue(details.contains("frame : 512×384"))
        assertTrue(details.contains("latence : 640 ms · p95 910 ms · serveur 520 ms"))
        assertTrue(details.contains("12 frames · 0 masques"))
        assertTrue(details.contains("reconnus : person, chair"))
        assertTrue(details.contains("gpu : A100-SXM4-40GB"))
    }

    @Test
    fun `detail vide quand rien n_a ete analyse`() {
        assertTrue(liveProbeDetails(LiveProbeState()).isEmpty())
    }

    @Test
    fun `erreur ajoutee au detail`() {
        val details = liveProbeDetails(LiveProbeState(frames = 1, error = "délai dépassé"))
        assertTrue(details.contains("erreur : délai dépassé"))
    }

    @Test
    fun `endpoint long raccourci au milieu`() {
        val short = shortEndpoint("https://baptiste-faisy--sam3-eu-a100-sam3live-web.eu-west.modal.run")
        assertTrue(short.length <= ENDPOINT_MAX_CHARS)
        assertTrue(short.contains("…"))
        assertFalse(short.startsWith("https"))
    }

    @Test
    fun `endpoint court garde tel quel`() {
        assertEquals("sam.local:8000", shortEndpoint("http://sam.local:8000/"))
    }

    @Test
    fun `decimal a la francaise`() {
        assertEquals("1,4", DistanceText.decimal(1.44f, 1, Locale.FRANCE))
        assertEquals("12,0", DistanceText.decimal(12f, 1, Locale.FRANCE))
    }

    private fun reading(label: String, meters: Float, trackId: Int = -1) =
        DistanceReading(trackId, label, meters, meters, listOf(0f, 0f, 10f, 10f))

    @Test
    fun `la ligne compacte ajoute la distance du plus proche`() {
        val state = ready().copy(
            distanceLayer = DistanceLayer(512, 384, listOf(reading("person", 3.2f), reading("chair", 1.1f))),
        )
        val line = liveProbeHeadline(state)
        assertTrue(line.contains("8 objets"))
        assertTrue(line.contains("plus proche ≈ 1,1 m"))
        assertTrue(line.endsWith("plus proche ≈ 1,1 m"))
    }

    @Test
    fun `sans distance mesuree rien n_est ajoute`() {
        assertEquals(
            "SAM · 1,4 ips · 640 ms · 8 objets",
            liveProbeHeadline(ready()),
        )
    }

    @Test
    fun `le detail liste les distances du plus proche au plus loin`() {
        val state = ready().copy(
            distanceLayer = DistanceLayer(
                512,
                384,
                listOf(reading("person", 3.2f), reading("chair", 1.1f), reading("laptop", 0.85f)),
            ),
            spoken = "Personne à gauche, à 3,2 mètres",
        )
        val details = liveProbeDetails(state)
        assertTrue(details.contains("distances : laptop ≈ 85 cm · chair ≈ 1,1 m · person ≈ 3,2 m"))
        assertTrue(details.contains("dit : Personne à gauche, à 3,2 mètres"))
    }

    @Test
    fun `resume vide quand aucune distance n_est exploitable`() {
        assertEquals(null, distanceSummary(emptyList()))
        assertEquals(null, distanceSummary(listOf(reading("person", 0f))))
    }

    @Test
    fun `sans masque on dit pourquoi`() {
        assertEquals(
            "réveil du GPU SAM… (≈ 30 s au premier lancement)",
            liveProbeNotice(LiveProbeState(warming = true)),
        )
        assertEquals("en attente de la première analyse…", liveProbeNotice(LiveProbeState()))
        assertEquals(
            "GPU SAM éteint — demande à b de l'allumer",
            liveProbeNotice(LiveProbeState(error = "GPU SAM éteint — demande à b de l'allumer")),
        )
    }

    @Test
    fun `des qu_une frame est analysee le message disparait`() {
        assertEquals(null, liveProbeNotice(LiveProbeState(frames = 3, warming = true)))
    }

    @Test
    fun `le panneau montre les huit concepts suivis`() {
        val details = liveProbeDetails(ready())
        assertTrue(details.contains("suivis (8) : person, chair, table, laptop, bottle, cup, bag, door"))
        assertTrue(details.contains("reconnus : person, chair"))
    }

    /** Chronologie complète d'une frame, telle que les marques réelles la produisent. */
    private fun timed() = FrameTimings(
        captureToJpegMs = 22,
        jpegToSentMs = 61,
        sentToReceivedMs = 153,
        receivedToOverlaysMs = 37,
        overlaysToPixelMs = 8,
        captureToPixelMs = 348,
        serverTotalMs = 92,
        server = mapOf("total" to 92.0, "vision_ms" to 47.0, "detr_ms" to 38.0),
    )

    @Test
    fun `aucune chronologie avant la premiere frame`() {
        assertEquals("", liveProbeTimingsLine(LiveProbeState()))
    }

    @Test
    fun `la chronologie tient sur une seule ligne du panneau`() {
        val line = liveProbeTimingsLine(ready().copy(timings = timed()))
        assertEquals("latence 348 · capt 22 · up 61 · srv 92 · rtt 153 · masque 37 · pixel 8", line)
        assertFalse(line.contains("\n"))
    }

    @Test
    fun `le detail ajoute la chronologie et les etages serveur`() {
        val details = liveProbeDetails(ready().copy(timings = timed()))
        assertTrue(details.contains("capture → jpeg : 22 ms"))
        assertTrue(details.contains("envoi → réponse : 153 ms (dont serveur 92 ms, réseau + file 61 ms)"))
        assertTrue(details.contains("latence perçue : 348 ms"))
        assertTrue(details.contains("serveur : vision 47 ms · detr 38 ms"))
    }

    @Test
    fun `sans chronologie le detail ne rajoute aucune ligne`() {
        val details = liveProbeDetails(ready())
        assertFalse(details.any { it.startsWith("capture → jpeg") })
        assertFalse(details.any { it.startsWith("serveur :") })
    }

    @Test
    fun `le panneau reste muet sur les concepts avant la premiere frame`() {
        val details = liveProbeDetails(LiveProbeState())
        assertTrue(details.none { it.startsWith("suivis") })
    }

    @Test
    fun `l'echec dit reveil gpu sur delai depasse`() {
        assertEquals(
            "réveil du GPU en cours… (nouvel essai à la frame suivante)",
            probeFailureText(ApiError.Timeout()),
        )
        assertEquals("réponse inattendue : boom", probeFailureText(ApiError.BadResponse("boom")))
        assertEquals("échec de l'appel SAM", probeFailureText(RuntimeException()))
    }

    @Test
    fun `les modeles effectifs apparaissent dans le detail`() {
        val line = "llm : meta-models/muse-spark-open-1026 · live : meta-models/muse-spark-open-1026 (clé OK)"
        assertTrue(liveProbeDetails(LiveProbeState(modelsLine = line)).contains(line))
        assertTrue(liveProbeDetails(LiveProbeState()).none { it.startsWith("llm :") })
    }
}
