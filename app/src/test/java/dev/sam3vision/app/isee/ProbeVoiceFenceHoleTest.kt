package dev.sam3vision.app.isee

import dev.sam3vision.core.live.PlacedLabel
import dev.sam3vision.core.live.SteeringCap
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Voix de la sonde devant un grillage ouvert : le trou proche oriente la
 * sortie (voix TwinLite), sauf humain à moins de 80 cm de ce côté.
 */
class ProbeVoiceFenceHoleTest {

    private fun panel(x0: Float, x1: Float, meters: Float) =
        PlacedLabel("fence", (x0 + x1) / 2f, 0.5f, meters, x0, x1)

    /** Poteau centre non mesuré (bouché, sans chemin optimal) + trou ouvert à gauche. */
    private fun holeLeft() = listOf(
        PlacedLabel("poteau", 0.5f, 0.5f, null),
        panel(0f, 0.05f, 4f),
        panel(0.21f, 0.28f, 4f),
    )

    private fun guided(): ProbeVoice = ProbeVoice().also { it.guide = true }

    @Test
    fun `guidage dit le trou en voix TwinLite`() {
        assertEquals("left.", guided().phrase(0L, holeLeft(), SteeringCap.Straight))
    }

    @Test
    fun `humain devant le trou garde l evitement generique`() {
        val labels = holeLeft() + PlacedLabel("personne", 0.15f, 0.5f, 2f)

        assertEquals("right.", guided().phrase(0L, labels, SteeringCap.Straight))
    }
}
