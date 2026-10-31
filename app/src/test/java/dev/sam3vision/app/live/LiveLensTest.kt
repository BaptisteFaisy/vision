package dev.sam3vision.app.live

import dev.sam3vision.core.distance.LensSpec
import org.junit.Assert.assertEquals
import org.junit.Test

/** Priorité du calibrage des distances : champ forcé, capteur lu, repli grand-angle. */
class LiveLensTest {

    private val sensor = LensSpec(73.32f, 58.34f)

    @Test
    fun `le champ forcé prime sur le capteur`() {
        assertEquals(70f, chosenLens(70f, sensor).horizontalFovDeg, 0.001f)
    }

    @Test
    fun `le capteur prime sur le repli quand rien n'est forcé`() {
        assertEquals(sensor, chosenLens(0f, sensor))
    }

    @Test
    fun `sans capteur ni réglage le repli grand-angle s'applique`() {
        assertEquals(LensSpec.Default, chosenLens(0f, null))
    }
}
