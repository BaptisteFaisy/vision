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
    fun `un caregiver ne publie jamais sa propre position meme si le partage est coche`() {
        assertTrue(canPublishShare(Settings.Default.copy(caregiverRole = CaregiverRole.Blind, shareEnabled = true)))
        assertFalse(canPublishShare(Settings.Default.copy(caregiverRole = CaregiverRole.Caregiver, shareEnabled = true)))
        assertFalse(canPublishShare(Settings.Default.copy(caregiverRole = CaregiverRole.Blind, shareEnabled = false)))
    }

    @Test
    fun `la phrase et son etat voyagent avec les objets de la meme session`() {
        val scene = SharedScene("Porte à droite.", fromUser = false, speaking = true)
        val json = shareJson("proche-a", "", fix, emptyList(), scene)
        assertEquals("proche-a", json.getString("shareId"))
        assertEquals("Porte à droite.", json.getJSONObject("scene").getString("text"))
        assertEquals(true, json.getJSONObject("scene").getBoolean("speaking"))
    }

    @Test
    fun `les anciens emetteurs restent compatibles et le texte est borne`() {
        assertFalse(shareJson("proche-a", "", fix, emptyList()).has("scene"))
        assertEquals(MAX_SHARED_TEXT, SharedScene("x".repeat(3_000)).toJson().getString("text").length)
    }

    @Test
    fun `seul un lien recu avec un identifiant valide selectionne un proche`() {
        assertEquals("https://example.com/?share=proche-b", validatedShareLink(" https://example.com/?share=proche-b "))
        assertNull(validatedShareLink("https://example.com/"))
        assertNull(validatedShareLink("https://example.com/?share=Autre"))
        assertNull(validatedShareLink("javascript:alert(1)"))
    }

    @Test
    fun `le relais utilise l origine du lien et oublie sa query`() {
        assertEquals("https://example.com/api/share", shareEndpoint("https://example.com/?share=proche-b#carte"))
        assertNull(shareEndpoint("pas un lien"))
    }
}
