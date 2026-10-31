package dev.sam3vision.app.voicenav

import dev.sam3vision.app.ShareTrackingService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Textes FR de la notification de guidage : jamais vides, jamais techniques. */
class GuidanceNotificationTextTest {

    @Test
    fun `titre et bouton nomment le guidage vocal`() {
        assertEquals("Voice guidance active", GuidanceNotification.title())
        assertEquals("Stop guidance", GuidanceNotification.stopLabel())
        assertEquals("Voice guidance", GuidanceNotification.channelName())
    }

    @Test
    fun `texte sans destination reste générique mais actionnable`() {
        assertEquals("Follow the voice announcements.", GuidanceNotification.contentText(null))
        assertEquals("Follow the voice announcements.", GuidanceNotification.contentText("  "))
    }

    @Test
    fun `texte avec destination la nomme`() {
        assertEquals(
            "Guiding to la gare — follow the voice announcements.",
            GuidanceNotification.contentText("  la gare  "),
        )
    }

    @Test
    fun `canal et notification distincts du suivi parent`() {
        assertNotEquals(ShareTrackingService.CHANNEL_ID, GuidanceNotification.CHANNEL_ID)
        assertNotEquals(ShareTrackingService.NOTIFICATION_ID, GuidanceNotification.NOTIFICATION_ID)
        assertNotEquals(ShareTrackingService.ACTION_STOP, GuidanceNotification.ACTION_STOP)
    }

    @Test
    fun `textes non vides sans terme technique`() {
        listOf(
            GuidanceNotification.title(),
            GuidanceNotification.contentText("mairie"),
            GuidanceNotification.stopLabel(),
        ).forEach { text ->
            assertTrue(text.isNotBlank())
            assertTrue(!text.contains("GPS", ignoreCase = true))
            assertTrue(!text.contains("foreground", ignoreCase = true))
        }
    }
}
