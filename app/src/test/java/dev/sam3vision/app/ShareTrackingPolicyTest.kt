package dev.sam3vision.app

import dev.sam3vision.core.share.SHARE_INTERVAL_MS
import dev.sam3vision.core.share.ShareTrackingPolicy.SCREEN_BUSY_DELAY_MS
import dev.sam3vision.core.share.ShareTrackingPolicy.SCREEN_OFF_DELAY_MS
import dev.sam3vision.core.share.ShareTrackingPolicy.nextDelayMs
import org.junit.Assert.assertEquals
import org.junit.Test

/** Cadence du service de suivi en fonction de l'écran et du Direct. */
class ShareTrackingPolicyTest {

    @Test
    fun `screen on without direct uses the share interval`() {
        assertEquals(SHARE_INTERVAL_MS, nextDelayMs(interactive = true, directPublishing = false))
    }

    @Test
    fun `screen off slows down for battery`() {
        assertEquals(SCREEN_OFF_DELAY_MS, nextDelayMs(interactive = false, directPublishing = false))
    }

    @Test
    fun `direct publishing waits because the screen sends the full state itself`() {
        assertEquals(SCREEN_BUSY_DELAY_MS, nextDelayMs(interactive = true, directPublishing = true))
    }
}
