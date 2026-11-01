package dev.sam3vision.app.isee

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verrouille les deux constantes de la politique de frame : côté envoyé et
 * qualité JPEG dans une plage utile.
 *
 * Historique : le 2026-10-26 la conversion coûtait 12-42 ms par frame parce
 * que la taille *demandée* (512) et la taille *envoyée* (448) avaient divergé.
 * Les helpers de calcul (sentEdge, requestedSize, repli capteur) ont été
 * supprimés le 01/11/2026 avec leurs appelants — seules EDGE et QUALITY
 * restent consommées (LiveScreen, GlassesProbeWiring, IseeGlassesLive).
 */
class FramePolicyTest {

    @Test
    fun `le cote envoye vaut 480 px`() {
        assertTrue("EDGE inattendu : ${FramePolicy.EDGE}", FramePolicy.EDGE == 480)
    }

    @Test
    fun `la qualite jpeg reste dans la plage utile`() {
        assertTrue("qualité trop basse : ${FramePolicy.QUALITY}", FramePolicy.QUALITY >= 40)
        assertTrue("qualité inutilement haute : ${FramePolicy.QUALITY}", FramePolicy.QUALITY <= 80)
    }
}
