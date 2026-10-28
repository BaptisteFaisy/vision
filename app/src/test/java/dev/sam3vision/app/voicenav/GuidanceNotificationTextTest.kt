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
        assertEquals("Guidage vocal actif", GuidanceNotification.title())
        assertEquals("Arrêter le guidage", GuidanceNotification.stopLabel())
        assertEquals("Guidage vocal", GuidanceNotification.channelName())
    }

    @Test
    fun `texte sans destination reste générique mais actionnable`() {
        assertEquals("Suivez les annonces vocales.", GuidanceNotification.contentText(null))
        assertEquals("Suivez les annonces vocales.", GuidanceNotification.contentText("  "))
    }

    @Test
    fun `texte avec destination la nomme`() {
        assertEquals(
            "Guidage vers la gare — suivez les annonces vocales.",
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
