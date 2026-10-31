package dev.sam3vision.core.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Profil de latence : p50/p95 par étage sur la fenêtre de vingt mesures, latence perçue
 * distincte de l'aller-retour, part serveur/réseau, comptes hors écran et échecs, et
 * ligne de journal en anglais sans locale.
 */
class LatencyProfileTest {

    private val profile = LatencyProfile()

    /** Les sept étages du banc, perçu 684,8 ms, A/R 426,2 ms, serveur 300 ms. */
    private val complete = longArrayOf(24_500L, 8_800L, 184_700L, 426_200L, 21_500L, 1_700L, 17_400L)

    private fun completeFrame(serverUs: Long = 300_000L): LatencyFrame = LatencyFrame.of(complete, serverUs)

    @Test
    fun `profil vide ne montre aucun chiffre`() {
        assertEquals(0, profile.samples)
        LatencyStage.entries.forEach {
            assertEquals(-1L, profile.p50Us(it))
            assertEquals(-1L, profile.p95Us(it))
        }
        assertEquals(-1L, profile.perceivedP50Us)
        assertEquals(-1L, profile.perceivedP95Us)
        assertEquals(-1L, profile.rttP50Us)
        assertEquals(-1L, profile.serverP50Us)
        assertEquals(-1L, profile.networkP50Us)
        assertEquals(0L, profile.offScreenFrames)
        assertEquals(0L, profile.failedFrames)
        assertEquals("", profile.summary().line())
    }

    @Test
    fun `une frame complete donne le meme p50 et p95 par etage`() {
        profile.record(completeFrame())
        val frame = completeFrame()
        LatencyStage.entries.forEach { stage ->
            assertEquals(frame.stageUs(stage), profile.p50Us(stage))
            assertEquals(frame.stageUs(stage), profile.p95Us(stage))
            assertEquals(1, profile.count(stage))
        }
        assertEquals(1, profile.samples)
        assertEquals(684_800L, profile.perceivedP50Us)
        assertEquals(684_800L, profile.perceivedP95Us)
        assertEquals(426_200L, profile.rttP50Us)
        assertEquals(300_000L, profile.serverP50Us)
        assertEquals(126_200L, profile.networkP50Us) // A/R − serveur
    }

    @Test
    fun `la fenetre de vingt mesures donne le p50 et le p95`() {
        (1..20).forEach { k -> profile.record(frameOfCapture(k * 1_000L)) }
        assertEquals(20, profile.count(LatencyStage.CAPTURE))
        assertEquals(10_000L, profile.p50Us(LatencyStage.CAPTURE)) // 10e sur 20
        assertEquals(19_000L, profile.p95Us(LatencyStage.CAPTURE)) // 19e sur 20
    }

    @Test
    fun `hors ecran et echecs sont comptes sans polluer la latence percue`() {
        profile.record(completeFrame(), FrameOutcome.OFF_SCREEN)
        assertEquals(1L, profile.offScreenFrames)
        assertEquals(1, profile.samples) // perçu mesuré : la frame était complète
        profile.record(LatencyFrame.of(longArrayOf()), FrameOutcome.FAILED)
        assertEquals(1L, profile.failedFrames)
        assertEquals(1, profile.samples) // une frame sans chronologie n'ajoute rien
    }

    @Test
    fun `une frame sans affichage ne pese pas dans le percu`() {
        // Six étages seulement : DISPLAY manque, la frame n'a pas été dessinée.
        profile.record(LatencyFrame.of(longArrayOf(1L, 2L, 3L, 4L, 5L, 6L)), FrameOutcome.OFF_SCREEN)
        assertEquals(0, profile.samples)
        assertEquals(1L, profile.offScreenFrames)
        assertEquals(1, profile.count(LatencyStage.CAPTURE)) // mais l'étage est mesuré
    }

    @Test
    fun `la ligne de journal est en anglais sans locale`() {
        profile.record(completeFrame(), FrameOutcome.FAILED)
        assertEquals(
            "LatencyProfile felt 684.8/684.8 ms · RTT 426.2/426.2 · server 300.0 · network 126.2" +
                " · queue 27% · off-screen 0 · failures 1",
            profile.summary().line(),
        )
    }

    @Test
    fun `les etages se lisent un par ligne`() {
        profile.record(completeFrame())
        val lines = profile.summary().stages()
        assertEquals(7, lines.size)
        assertEquals("LatencyProfile capt 24.5/24.5 ms", lines.first())
        assertEquals("LatencyProfile aff 17.4/17.4 ms", lines.last())
    }

    @Test
    fun `la part de file est un pourcentage entier`() {
        profile.record(completeFrame())
        assertEquals(27, profile.summary().queueSharePercent)
    }

    @Test
    fun `le resume est figé une fois pris`() {
        profile.record(completeFrame())
        val summary = profile.summary()
        profile.record(frameOfCapture(1_000_000L), FrameOutcome.OFF_SCREEN)
        assertEquals(684_800L, summary.perceivedP50Us)
        assertEquals(0L, summary.offScreenFrames)
        assertEquals(1L, profile.offScreenFrames)
    }

    @Test
    fun `reset oublie mesures et compteurs`() {
        profile.record(completeFrame(), FrameOutcome.FAILED)
        profile.reset()
        assertEquals(0, profile.samples)
        assertEquals(0L, profile.offScreenFrames)
        assertEquals(0L, profile.failedFrames)
        assertEquals(-1L, profile.perceivedP50Us)
    }

    /** Frame complète dont seul l'étage `capt` varie : contrôle direct du percentile. */
    private fun frameOfCapture(captureUs: Long): LatencyFrame = LatencyFrame.of(
        longArrayOf(captureUs, 1_000L, 1_000L, 1_000L, 1_000L, 1_000L, 1_000L),
    )

    /** Garde : sept étages, sinon les tests ci-dessus mesureraient un trajet tronqué. */
    @Test
    fun `la reference de test a bien sept etages`() {
        assertTrue(complete.size == LatencyStage.entries.size)
    }
}