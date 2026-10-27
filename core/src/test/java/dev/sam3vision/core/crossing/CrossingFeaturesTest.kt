package dev.sam3vision.core.crossing

import org.junit.Assert.assertEquals
import org.junit.Test

class CrossingFeaturesTest {

    @Test
    fun `un cadre tout rouge donne les fractions attendues`() {
        val features = CrossingFeatures.extract(IntArray(4) { RED }, 2, 2)
        assertEquals(CrossingFeatures.COUNT, features.size)
        assertEquals(1.0f, features[0], EPS)   // hue_bin_0 : tout le rouge saturé
        assertEquals(0.5f, features[8], EPS)   // red_top
        assertEquals(0.5f, features[9], EPS)   // red_bottom
        assertEquals(0.0f, features[10], EPS)  // green_top
        assertEquals(0.0f, features[11], EPS)  // green_bottom
        assertEquals(1.0f, features[12], EPS)  // red_frac
        assertEquals(0.0f, features[13], EPS)  // green_frac
        assertEquals(1.0f, features[14], EPS)  // sat_frac
        assertEquals(1.0f, features[15], EPS)  // mean_value
        assertEquals(1.0f, features[16], EPS)  // mean_sat
    }

    @Test
    fun `un cadre noir donne des traits nuls`() {
        val features = CrossingFeatures.extract(IntArray(4) { BLACK }, 2, 2)
        assertEquals(FloatArray(CrossingFeatures.COUNT).toList(), features.toList())
    }

    @Test
    fun `un tableau incoherent donne des traits nuls`() {
        assertEquals(
            FloatArray(CrossingFeatures.COUNT).toList(),
            CrossingFeatures.extract(IntArray(10), 10, 10).toList(),
        )
        assertEquals(
            FloatArray(CrossingFeatures.COUNT).toList(),
            CrossingFeatures.extract(IntArray(0), 0, 0).toList(),
        )
    }

    @Test
    fun `les noms de traits correspondent au harnais`() {
        assertEquals(CrossingFeatures.COUNT, TrainedLightColor.FEATURE_NAMES.size)
        assertEquals("hue_bin_0", TrainedLightColor.FEATURE_NAMES.first())
        assertEquals("mean_sat", TrainedLightColor.FEATURE_NAMES.last())
    }

    companion object {
        private const val EPS = 0.001f
        private const val RED = 0xFFFF0000.toInt()
        private const val BLACK = 0xFF000000.toInt()
    }
}
