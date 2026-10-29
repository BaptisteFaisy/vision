package dev.sam3vision.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ObjectLimitsTest {

    @Test
    fun `le defaut est de 8 objets`() {
        assertEquals(8, ObjectLimits.DEFAULT_ON_SCREEN)
        assertEquals(ObjectLimits.DEFAULT_ON_SCREEN, ObjectLimits.clamp(ObjectLimits.DEFAULT_ON_SCREEN))
    }

    @Test
    fun `le plafond est de 32 objets`() {
        assertEquals(32, ObjectLimits.MAX_ON_SCREEN)
        assertEquals(32, ObjectLimits.clamp(32))
        assertEquals(32, ObjectLimits.clamp(99))
    }

    @Test
    fun `clamp ramene les valeurs hors bornes dans l'intervalle`() {
        assertEquals(ObjectLimits.MIN_ON_SCREEN, ObjectLimits.clamp(0))
        assertEquals(ObjectLimits.MIN_ON_SCREEN, ObjectLimits.clamp(-7))
        assertEquals(ObjectLimits.MAX_ON_SCREEN, ObjectLimits.clamp(99))
        assertEquals(6, ObjectLimits.clamp(6))
    }

    @Test
    fun `step sature aux bornes sans deborder`() {
        assertEquals(ObjectLimits.MIN_ON_SCREEN, ObjectLimits.step(ObjectLimits.MIN_ON_SCREEN, -1))
        assertEquals(ObjectLimits.MAX_ON_SCREEN, ObjectLimits.step(ObjectLimits.MAX_ON_SCREEN, +1))
        assertEquals(5, ObjectLimits.step(4, +1))
        assertEquals(3, ObjectLimits.step(4, -1))
    }
}
