package dev.sam3vision.app.pose

import dev.sam3vision.core.distance.CameraPose
import org.junit.Assert.assertEquals
import org.junit.Test

/** [clampEyeHeight] : le pur des stores de hauteur, borné main, défaut yeux. */
class EyeHeightTest {

    @Test
    fun `illisible donne les yeux`() {
        assertEquals(CameraPose.GLASSES_HEIGHT_M, clampEyeHeight(Float.NaN), 0f)
        assertEquals(CameraPose.GLASSES_HEIGHT_M, clampEyeHeight(Float.POSITIVE_INFINITY), 0f)
    }

    @Test
    fun `tenue gardee, hors main bornee`() {
        assertEquals(1.8f, clampEyeHeight(1.8f), 0f)
        assertEquals(CameraPose.MAX_HEIGHT_M, clampEyeHeight(99f), 0f)
        assertEquals(CameraPose.MIN_HEIGHT_M, clampEyeHeight(0.1f), 0f)
    }

    @Test
    fun `le partage camera garde son defaut epaule`() {
        assertEquals(CameraPose.DEFAULT_HEIGHT_M, clampHeightPref(Float.NaN, CameraPose.DEFAULT_HEIGHT_M), 0f)
        assertEquals(1.5f, clampHeightPref(1.5f, CameraPose.DEFAULT_HEIGHT_M), 0f)
    }
}
