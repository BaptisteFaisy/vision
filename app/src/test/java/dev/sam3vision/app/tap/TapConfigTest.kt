package dev.sam3vision.app.tap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gate d'envoi du tap : raison explicite affichée, jamais un « Failed » silencieux. */
class TapConfigTest {

    @Test
    fun `endpoint vide donne la raison explicite`() {
        val reason = TapConfig.sendBlockedReason(endpoint = "  ", onDevice = false)
        assertTrue(reason.orEmpty().contains("sam31.endpoint"))
    }

    @Test
    fun `mode on-device donne la raison explicite`() {
        val reason = TapConfig.sendBlockedReason(endpoint = "https://x", onDevice = true)
        assertTrue(reason.orEmpty().contains("inférence locale"))
    }

    @Test
    fun `endpoint renseigne ne bloque rien`() {
        assertNull(TapConfig.sendBlockedReason(endpoint = "https://x", onDevice = false))
    }

    @Test
    fun `sendConfigured suit sendBlockedReason`() {
        assertEquals(
            TapConfig.sendBlockedReason() == null,
            TapConfig.sendConfigured,
        )
    }
}
