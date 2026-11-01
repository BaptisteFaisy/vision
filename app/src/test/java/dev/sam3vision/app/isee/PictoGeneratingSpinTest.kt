package dev.sam3vision.app.isee

import org.junit.Assert.assertEquals
import org.junit.Test

/** Rotation de la boule du bloc de génération : un tour par 1,2 s. */
class PictoGeneratingSpinTest {

    @Test
    fun `angle zero au depart`() {
        assertEquals(0f, pictoSpinAngle(0f), 0.001f)
    }

    @Test
    fun `demi-tour a mi-periode`() {
        assertEquals(180f, pictoSpinAngle(0.6f), 0.001f)
    }

    @Test
    fun `retour a zero au tour complet`() {
        assertEquals(0f, pictoSpinAngle(1.2f), 0.001f)
    }
}
