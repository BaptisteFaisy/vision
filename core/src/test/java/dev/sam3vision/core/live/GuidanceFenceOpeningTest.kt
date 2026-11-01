package dev.sam3vision.core.live

import dev.sam3vision.core.hazard.FenceOpeningPosition
import dev.sam3vision.core.hazard.FenceOpeningSignal
import dev.sam3vision.core.hazard.FenceOpeningState
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guidage vers le trou de grillage, humains prioritaires : signal, décision, annonce. */
class GuidanceFenceOpeningTest {

    private fun placed(label: String, x: Float, meters: Float? = null) =
        PlacedLabel(label, x, 0.5f, meters)

    private fun panel(x0: Float, x1: Float, meters: Float) =
        PlacedLabel("fence", (x0 + x1) / 2f, 0.5f, meters, x0, x1)

    /** Couloir bouché sans chemin optimal : le poteau centre non mesuré bloque, le plan échoue. */
    private fun blockedNoPath() = listOf(
        placed("poteau", 0.5f),
        placed("plot", 0.1f, 2f),
        placed("plot", 0.15f, 2f),
    )

    @Test
    fun `trou lateral ouvert donne son cote`() {
        val panels = listOf(panel(0f, 0.05f, 4f), panel(0.21f, 0.28f, 4f))

        val signal = FenceOpeningSignal.ofPlaced(panels)

        assertTrue(signal.detected)
        assertEquals(FenceOpeningState.Open, signal.state)
        assertEquals(FenceOpeningPosition.Left, signal.position)
        assertEquals(GuidanceSide.Left, signal.holeSide())
    }

    @Test
    fun `trou lateral droit donne la droite`() {
        val panels = listOf(panel(0.72f, 0.79f, 4f), panel(0.95f, 1f, 4f))

        val signal = FenceOpeningSignal.ofPlaced(panels)

        assertTrue(signal.detected)
        assertEquals(FenceOpeningState.Open, signal.state)
        assertEquals(FenceOpeningPosition.Right, signal.position)
        assertEquals(GuidanceSide.Right, signal.holeSide())
    }

    @Test
    fun `grillage ferme devant ne donne aucun trou`() {
        val panels = listOf(panel(0.3f, 0.6f, 2f))

        val signal = FenceOpeningSignal.ofPlaced(panels)

        assertTrue(signal.detected)
        assertEquals(FenceOpeningState.Closed, signal.state)
        assertNull(signal.holeSide())
    }

    @Test
    fun `passage au milieu ne donne aucun trou lateral`() {
        val panels = listOf(panel(0f, 0.1f, 2f), panel(0.9f, 1f, 2f))

        val signal = FenceOpeningSignal.ofPlaced(panels)

        assertTrue(signal.detected)
        assertEquals(FenceOpeningState.Open, signal.state)
        assertEquals(FenceOpeningPosition.Ahead, signal.position)
        assertNull(signal.holeSide())
    }

    @Test
    fun `pan seul ou non mesure reste muet`() {
        assertFalse(FenceOpeningSignal.ofPlaced(listOf(panel(0f, 0.1f, 2f))).detected)
        assertNull(FenceOpeningSignal.ofPlaced(listOf(panel(0f, 0.1f, 2f))).holeSide())
        val unmeasured = listOf(
            PlacedLabel("fence", 0.05f, 0.5f, null, 0f, 0.1f),
            PlacedLabel("fence", 0.245f, 0.5f, null, 0.21f, 0.28f),
        )
        assertFalse(FenceOpeningSignal.ofPlaced(unmeasured).detected)
        assertNull(FenceOpeningSignal.ofPlaced(unmeasured).holeSide())
        assertNull(FenceOpeningSignal.none().holeSide())
    }

    @Test
    fun `decide part vers le trou sans chemin optimal`() {
        val labels = blockedNoPath()

        assertEquals(GuidanceSide.Right, Guidance.decide(labels).side)
        assertEquals(GuidanceSide.Left, Guidance.decide(labels, holeSide = GuidanceSide.Left).side)
        assertEquals(GuidanceSide.Right, Guidance.decide(labels, holeSide = GuidanceSide.Right).side)
    }

    @Test
    fun `decide veto humain prime sur le trou`() {
        val labels = blockedNoPath() + placed("personne", 0.15f, 2f)

        val decision = Guidance.decide(labels, holeSide = GuidanceSide.Left)

        assertTrue(decision.blocked)
        assertEquals(GuidanceSide.Right, decision.side)
    }

    @Test
    fun `decide chemin optimal garde la main sur le trou`() {
        val labels = listOf(PlacedLabel("poteau", 0.5f, 0.5f, 2f, 0.4f, 0.6f))

        val decision = Guidance.decide(labels, holeSide = GuidanceSide.Right)

        assertTrue(decision.blocked)
        assertEquals(GuidanceSide.Left, decision.side)
    }

    @Test
    fun `decide sans trou garde l evitement generique`() {
        val labels = blockedNoPath()

        assertEquals(Guidance.decide(labels), Guidance.decide(labels, holeSide = null))
    }

    @Test
    fun `annonceur dit le trou en voix TwinLite`() {
        val announcer = GuidanceAnnouncer()

        val phrase = announcer.onFrame(0L, blockedNoPath(), SpeechLanguage.Fr, holeSide = GuidanceSide.Left)

        assertEquals("gauche.", phrase)
    }

    @Test
    fun `annonceur sans trou garde l evitement generique`() {
        val announcer = GuidanceAnnouncer()

        val phrase = announcer.onFrame(0L, blockedNoPath(), SpeechLanguage.Fr)

        assertEquals("droite.", phrase)
    }

    @Test
    fun `annonceur veto humain prime sur le trou`() {
        val announcer = GuidanceAnnouncer()
        val labels = blockedNoPath() + placed("personne", 0.15f, 2f)

        val phrase = announcer.onFrame(0L, labels, SpeechLanguage.Fr, holeSide = GuidanceSide.Left)

        assertEquals("droite.", phrase)
    }
}
