package dev.sam3vision.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** Test **jetable** : rouge tant que [DemoDoublage.doubler] ne double pas (v2). */
class DemoDoublageTest {

    @Test
    fun `double une valeur`() {
        assertEquals(4, DemoDoublage.doubler(2))
    }
}
