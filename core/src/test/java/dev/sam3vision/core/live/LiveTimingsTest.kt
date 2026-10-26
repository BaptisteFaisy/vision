package dev.sam3vision.core.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chronologie d'une frame : les deltas entre postes, le repli sur `elapsed_ms` et la mise
 * en forme d'une seule ligne. Logique pure (aucune `Bitmap`, aucune horloge Android).
 */
class LiveTimingsTest {

    /** Horloge simulée : on avance la montre de [step] ns à chaque lecture. */
    private class FakeClock(startNs: Long = 0L, val stepNs: Long = 1_000_000L) {
        private var now = startNs
        fun read(): Long = now.also { now += stepNs }
    }

    @Test
    fun `les postes se lisent dans l'ordre du trajet`() {
        val clock = FakeClock()
        val chronology = LiveTimings(clock::read)
        LiveStage.entries.forEach { chronology.mark(it) }
        val stages = chronology.snapshot()
        // Une lecture = 1 ms : chaque intervalle vaut donc 1 ms, sauf capture→pixel.
        assertEquals(1L, stages.captureToJpegMs)
        assertEquals(1L, stages.jpegToSentMs)
        assertEquals(3L, stages.sentToReceivedMs)   // envoi → réponse, jalons réseau compris
        assertEquals(1L, stages.sentToHeadersMs)
        assertEquals(1L, stages.headersToBodyMs)
        assertEquals(1L, stages.bodyToReceivedMs)
        assertEquals(1L, stages.receivedToOverlaysMs)
        assertEquals(1L, stages.overlaysToPixelMs)
        // Huit postes, sept intervalles.
        assertEquals(7L, stages.captureToPixelMs)
        assertEquals(7L, stages.totalMs)
        assertTrue(stages.complete)
    }

    @Test
    fun `les jalons reseau separent attente, corps et parse`() {
        val clock = FakeClock()
        val chronology = LiveTimings(clock::read)
        chronology.mark(LiveStage.ENCODED)
        chronology.mark(LiveStage.SENT)
        chronology.mark(LiveStage.HEADERS)
        chronology.mark(LiveStage.BODY)
        chronology.mark(LiveStage.RECEIVED)
        val stages = chronology.snapshot()
        assertEquals(1L, stages.sentToHeadersMs)   // attente serveur + radio
        assertEquals(1L, stages.headersToBodyMs)   // descente du corps
        assertEquals(1L, stages.bodyToReceivedMs)  // parsing JSON
        assertEquals(3L, stages.sentToReceivedMs)  // l'aller-retour reste entier
        assertEquals(1L, stages.localMs)           // seul le parse est mesuré ici (CPU téléphone)
    }

    @Test
    fun `sans jalons reseau les postes restent absents`() {
        // WebSocket déjà ouvert : pas d'en-têtes par frame, et le message peut arriver
        // avant tout marquage — on n'invente ni corps ni parse.
        val chronology = LiveTimings(FakeClock()::read)
        chronology.mark(LiveStage.SENT)
        chronology.mark(LiveStage.RECEIVED)
        val stages = chronology.snapshot()
        assertEquals(-1L, stages.sentToHeadersMs)
        assertEquals(-1L, stages.headersToBodyMs)
        assertEquals(-1L, stages.bodyToReceivedMs)
        assertEquals(1L, stages.sentToReceivedMs)
        assertTrue(TimingsText.detail(stages).none { it.startsWith("envoi → en-têtes") })
    }

    @Test
    fun `le detail montre la decomposition quand le transport la date`() {
        val stages = fullStages().copy(
            sentToHeadersMs = 90, headersToBodyMs = 40, bodyToReceivedMs = 23,
        )
        val details = TimingsText.detail(stages)
        assertTrue(details.contains("envoi → en-têtes : 90 ms"))
        assertTrue(details.contains("en-têtes → corps : 40 ms"))
        assertTrue(details.contains("corps → réponse : 23 ms"))
        // La ligne compacte garde son format : la décomposition n'est que dans le détail.
        assertFalse(TimingsText.compact(stages).contains("down"))
    }

    @Test
    fun `un poste non pose reste a moins un`() {
        val chronology = LiveTimings(FakeClock()::read)
        chronology.mark(LiveStage.ENCODED)
        chronology.mark(LiveStage.SENT)
        val stages = chronology.snapshot()
        // La capture manque : rien à inventer ni en amont, ni au total perçu.
        assertEquals(-1L, stages.captureToJpegMs)
        assertEquals(-1L, stages.captureToPixelMs)
        assertEquals(1L, stages.jpegToSentMs)
        assertFalse(stages.complete)
        assertTrue(stages.known)
    }

    @Test
    fun `le reset efface tout et la chronologie devient vide`() {
        val chronology = LiveTimings(FakeClock()::read)
        LiveStage.entries.forEach { chronology.mark(it) }
        chronology.reset()
        assertTrue(chronology.snapshot().isEmpty)
        assertEquals("", TimingsText.compact(chronology.snapshot()))
    }

    @Test
    fun `une horloge qui recule ne produit aucun chiffre`() {
        val chronology = LiveTimings { 0L }
        chronology.mark(LiveStage.CAPTURE, 5_000_000L)
        chronology.mark(LiveStage.ENCODED, 1_000_000L)
        assertEquals(-1L, chronology.snapshot().captureToJpegMs)
    }

    @Test
    fun `la ligne compacte tient sur une seule ligne`() {
        val line = TimingsText.compact(fullStages())
        assertEquals(
            "latence 348 · capt 22 · up 61 · srv 92 · rtt 153 · masque 37 · pixel 8",
            line,
        )
        assertFalse(line.contains("\n"))
    }

    @Test
    fun `la ligne compacte affiche un tire quand un poste manque`() {
        val stages = FrameTimings(captureToJpegMs = 22, sentToReceivedMs = 153)
        val line = TimingsText.compact(stages)
        assertTrue(line.startsWith("latence 175"))
        assertTrue(line.contains("up ${TimingsText.MISSING}"))
        assertTrue(line.contains("srv ${TimingsText.MISSING}"))
        assertTrue(line.contains("pixel ${TimingsText.MISSING}"))
    }

    @Test
    fun `le total retombe sur la somme des postes connus`() {
        // Pas de dessin : on somme ce qui est mesuré, sans inventer le dernier poste.
        val stages = FrameTimings(captureToJpegMs = 20, jpegToSentMs = 5, sentToReceivedMs = 100)
        assertEquals(125L, stages.totalMs)
        assertFalse(stages.complete)
    }

    @Test
    fun `le detail decompose l'aller-retour quand le serveur parle`() {
        val details = TimingsText.detail(fullStages())
        assertTrue(details.contains("capture → jpeg : 22 ms"))
        assertTrue(
            details.contains("envoi → réponse : 153 ms (dont serveur 92 ms, réseau + file 61 ms)"),
        )
        assertTrue(details.contains("latence perçue : 348 ms"))
    }

    @Test
    fun `sans chiffre serveur l'aller-retour n'est pas decoupe`() {
        val stages = fullStages().copy(server = emptyMap(), serverTotalMs = -1)
        val line = TimingsText.detail(stages).first { it.startsWith("envoi → réponse") }
        assertEquals("envoi → réponse : 153 ms", line)
        assertEquals(-1L, stages.networkMs)
    }

    @Test
    fun `le serveur fournit son total par elapsed_ms a defaut de timings`() {
        val stages = fullStages().copy(server = emptyMap(), serverTotalMs = 118)
        assertEquals(118L, stages.serverMs)
        assertEquals("serveur : ${TimingsText.MISSING}", TimingsText.servers(stages.server))
    }

    @Test
    fun `les etages serveur sont remis en ordre et en millisecondes`() {
        val servers = TimingsText.servers(
            mapOf("masks_ms" to 4.4, "vision_ms" to 47.0, "detr_ms" to 38.6, "total" to 92.0),
        )
        assertEquals("serveur : vision 47 ms · detr 39 ms · masks 4 ms", servers)
    }

    @Test
    fun `le cout local ne melange pas telephone et reseau`() {
        // 22 d'encodage + 37 de masques + 8 de pixel, rien du réseau.
        assertEquals(67L, fullStages().localMs)
    }

    /** Chronologie complète d'une frame, telle que les marques réelles la produisent. */
    private fun fullStages() = FrameTimings(
        captureToJpegMs = 22,
        jpegToSentMs = 61,
        sentToReceivedMs = 153,
        receivedToOverlaysMs = 37,
        overlaysToPixelMs = 8,
        captureToPixelMs = 348,
        serverTotalMs = 92,
        server = mapOf("total" to 92.0, "vision" to 47.0),
    )
}
