package dev.sam3vision.app.depth

import org.junit.Assert.assertEquals
import org.junit.Test

/** Fraîcheur de la carte embarquée : périmée signalée, jamais déguisée. */
class LiveDepthMeterTest {

    @Test
    fun `fraicheur - sans carte, fraiche et perimee`() {
        assertEquals(LiveDepthMeter.DepthFreshness.NO_MAP, LiveDepthMeter.freshnessOf(10_000L, 0L))
        assertEquals(LiveDepthMeter.DepthFreshness.EMBEDDED, LiveDepthMeter.freshnessOf(10_000L, 9_500L))
        assertEquals(LiveDepthMeter.DepthFreshness.STALE_MAP, LiveDepthMeter.freshnessOf(10_000L, 8_000L))
    }

    @Test
    fun `fraicheur - borne exacte d'une seconde encore fraiche`() {
        assertEquals(LiveDepthMeter.DepthFreshness.EMBEDDED, LiveDepthMeter.freshnessOf(10_000L, 9_000L))
        assertEquals(LiveDepthMeter.DepthFreshness.STALE_MAP, LiveDepthMeter.freshnessOf(10_000L, 8_999L))
    }
}
