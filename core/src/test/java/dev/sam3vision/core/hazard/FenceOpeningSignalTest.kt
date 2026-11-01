package dev.sam3vision.core.hazard

import dev.sam3vision.core.distance.DistanceReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Grillage fermé / ouvert : le couloir barré parle, l'intervalle passant parle, le reste est muet. */
class FenceOpeningSignalTest {

    private fun reading(label: String, meters: Float, box: List<Float>) =
        DistanceReading(trackId = -1, label = label, meters = meters, rawMeters = meters, box = box)

    /** Panneau centré (frame 512×1138), comme les guetteurs voisins. */
    private fun panel(label: String, meters: Float) =
        reading(label, meters, listOf(156f, 400f, 356f, 900f))

    @Test
    fun `grillage centre proche = ferme devant`() {
        val signal = FenceOpeningSignal.of(listOf(panel("fence", 2f)), 512, 1138)

        assertTrue(signal.detected)
        assertEquals(FenceOpeningState.Closed, signal.state)
        assertEquals(FenceOpeningPosition.Ahead, signal.position)
        assertEquals(2f, signal.meters!!, 0.001f)
        assertEquals(0.5f, signal.centerX!!, 0.001f)
    }

    @Test
    fun `barriere francaise centree = fermee`() {
        val signal = FenceOpeningSignal.of(listOf(panel("Barrière", 1.5f)), 512, 1138)

        assertTrue(signal.detected)
        assertEquals(FenceOpeningState.Closed, signal.state)
    }

    @Test
    fun `ferme a six metres, silence au-dela`() {
        assertTrue(FenceOpeningSignal.of(listOf(panel("grillage", 6f)), 512, 1138).detected)
        assertFalse(FenceOpeningSignal.of(listOf(panel("grillage", 6.1f)), 512, 1138).detected)
    }

    @Test
    fun `cloture des deux cotes et centre libre = passage au milieu`() {
        val left = reading("fence", 2f, listOf(5f, 400f, 60f, 900f))
        val right = reading("fence", 3f, listOf(450f, 400f, 505f, 900f))

        val signal = FenceOpeningSignal.of(listOf(left, right), 512, 1138)

        assertTrue(signal.detected)
        assertEquals(FenceOpeningState.Open, signal.state)
        assertEquals(FenceOpeningPosition.Ahead, signal.position)
        assertEquals(2f, signal.meters!!, 0.001f)
        assertEquals(0.5f, signal.centerX!!, 0.001f)
        assertTrue(signal.middle)
    }

    @Test
    fun `portails sortis, ignores des deux cotes`() {
        val left = reading("gate", 2f, listOf(5f, 400f, 60f, 900f))
        val right = reading("portail", 2.5f, listOf(450f, 400f, 505f, 900f))

        val signal = FenceOpeningSignal.of(listOf(left, right), 512, 1138)

        assertFalse(signal.detected)
    }

    @Test
    fun `intervalle passant a gauche = ouverture a gauche`() {
        // 95 px à 3,5 m ≈ 0,86 m ≥ 0,80 m (corps + marge d'épaule).
        val first = reading("fence", 3.5f, listOf(5f, 400f, 35f, 900f))
        val second = reading("fence", 3.5f, listOf(130f, 400f, 170f, 900f))

        val signal = FenceOpeningSignal.of(listOf(first, second), 512, 1138)

        assertTrue(signal.detected)
        assertEquals(FenceOpeningState.Open, signal.state)
        assertEquals(FenceOpeningPosition.Left, signal.position)
        assertEquals(3.5f, signal.meters!!, 0.001f)
        assertEquals(82.5f / 512f, signal.centerX!!, 0.001f)
    }

    @Test
    fun `intervalle passant a droite = ouverture a droite`() {
        val first = reading("barrier", 3.5f, listOf(342f, 400f, 382f, 900f))
        val second = reading("barrier", 3.5f, listOf(477f, 400f, 512f, 900f))

        val signal = FenceOpeningSignal.of(listOf(first, second), 512, 1138)

        assertTrue(signal.detected)
        assertEquals(FenceOpeningState.Open, signal.state)
        assertEquals(FenceOpeningPosition.Right, signal.position)
        assertEquals(429.5f / 512f, signal.centerX!!, 0.001f)
    }

    @Test
    fun `intervalle etroit ou proche = silence`() {
        // 20 px à 2 m ≈ 0,10 m : on ne passe pas.
        val narrow = listOf(
            reading("fence", 2f, listOf(5f, 400f, 60f, 900f)),
            reading("fence", 2f, listOf(80f, 400f, 140f, 900f)),
        )
        // 95 px à 1 m ≈ 0,25 m : le même intervalle en pixels ne passe plus de près.
        val near = listOf(
            reading("fence", 1f, listOf(5f, 400f, 35f, 900f)),
            reading("fence", 1f, listOf(130f, 400f, 170f, 900f)),
        )

        assertFalse(FenceOpeningSignal.of(narrow, 512, 1138).detected)
        assertFalse(FenceOpeningSignal.of(near, 512, 1138).detected)
    }

    @Test
    fun `panneau seul sur le cote = silence`() {
        val side = reading("fence", 2f, listOf(5f, 400f, 60f, 900f))

        assertFalse(FenceOpeningSignal.of(listOf(side), 512, 1138).detected)
    }

    @Test
    fun `ferme prioritaire sur l'intervalle lateral`() {
        val center = panel("fence", 2f)
        val first = reading("fence", 3.5f, listOf(5f, 400f, 35f, 900f))
        val second = reading("fence", 3.5f, listOf(130f, 400f, 170f, 900f))

        val signal = FenceOpeningSignal.of(listOf(center, first, second), 512, 1138)

        assertTrue(signal.detected)
        assertEquals(FenceOpeningState.Closed, signal.state)
    }

    @Test
    fun `chevauchement = pas d'intervalle`() {
        val first = reading("fence", 2f, listOf(5f, 400f, 100f, 900f))
        val second = reading("fence", 2f, listOf(60f, 400f, 160f, 900f))

        assertFalse(FenceOpeningSignal.of(listOf(first, second), 512, 1138).detected)
    }

    @Test
    fun `sans mesure ou mesure absurde = silence`() {
        assertFalse(FenceOpeningSignal.of(listOf(panel("fence", Float.NaN)), 512, 1138).detected)
        assertFalse(FenceOpeningSignal.of(listOf(panel("fence", 0f)), 512, 1138).detected)
        assertFalse(FenceOpeningSignal.of(listOf(panel("fence", -1f)), 512, 1138).detected)
    }

    @Test
    fun `autre label ou geometrie illisible = silence`() {
        val car = panel("car", 2f)
        val broken = reading("fence", 2f, listOf(1f, 2f))

        assertFalse(FenceOpeningSignal.of(listOf(car), 512, 1138).detected)
        assertFalse(FenceOpeningSignal.of(listOf(broken), 512, 1138).detected)
        assertFalse(FenceOpeningSignal.of(listOf(panel("fence", 2f)), 0, 1138).detected)
        assertFalse(FenceOpeningSignal.of(emptyList(), 512, 1138).detected)
    }
}
