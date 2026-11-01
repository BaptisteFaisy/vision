package dev.sam3vision.core.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import dev.sam3vision.core.settings.CaregiverRole
import dev.sam3vision.core.settings.Settings
import org.junit.Assert.assertTrue

class SharedSceneTest {
    private val fix = SharedFix(48.85, 2.35, 5f, 90f, 1L, false)

    @Test
    fun `a caregiver never publishes their own position even when sharing is on`() {
        assertTrue(canPublishShare(Settings.Default.copy(caregiverRole = CaregiverRole.Blind, shareEnabled = true)))
        assertFalse(canPublishShare(Settings.Default.copy(caregiverRole = CaregiverRole.Caregiver, shareEnabled = true)))
        assertFalse(canPublishShare(Settings.Default.copy(caregiverRole = CaregiverRole.Blind, shareEnabled = false)))
    }

    @Test
    fun `the sentence and its state travel with the same session objects`() {
        val scene = SharedScene("Door on the right.", fromUser = false, speaking = true)
        val json = shareJson("loved-a", "", fix, emptyList(), scene)
        assertEquals("loved-a", json.getString("shareId"))
        assertEquals("Door on the right.", json.getJSONObject("scene").getString("text"))
        assertEquals(true, json.getJSONObject("scene").getBoolean("speaking"))
    }

    @Test
    fun `old senders stay compatible and text is capped`() {
        assertFalse(shareJson("loved-a", "", fix, emptyList()).has("scene"))
        assertEquals(MAX_SHARED_TEXT, SharedScene("x".repeat(3_000)).toJson().getString("text").length)
    }

    @Test
    fun `the relay uses the link origin and drops its query`() {
        assertEquals("https://example.com/api/share", shareEndpoint("https://example.com/?share=loved-b#map"))
        assertNull(shareEndpoint("not a link"))
    }
}
