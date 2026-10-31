package dev.sam3vision.app.vm

import dev.sam3vision.app.LLM_ABSENT_LINE
import dev.sam3vision.app.UiState
import dev.sam3vision.core.model.Role
import dev.sam3vision.core.net.ApiError
import dev.sam3vision.core.net.LlmClient
import dev.sam3vision.core.settings.Settings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le chat : rien ne part sans photo, sans question ou sans clé LLM ; une réponse pose
 * deux tours d'historique et se lit à voix haute si la voix est réglée ; un échec laisse
 * un motif lisible au lieu d'un « … » qui ne retombe jamais.
 */
class AppChatTest {

    private val spoken = mutableListOf<String>()

    private fun screen(
        settings: Settings = ConfiguredSettings,
        photoB64: String = "photo",
    ): FakeScreen = FakeScreen(UiState(photoB64 = photoB64, settings = settings))

    private fun chat(screen: FakeScreen, llm: LlmClient): AppChat = AppChat(
        state = { screen.value },
        update = screen.update,
        llm = { llm },
        speak = { spoken += it },
    )

    @Test
    fun `sans photo — refus explique, aucun appel au LLM`() = runTest {
        val screen = screen(photoB64 = "")
        val llm = FakeLlm()

        chat(screen, llm).ask("où est la porte ?")

        assertEquals(0, llm.askCalls)
        assertFalse(screen.value.asking)
        assertEquals(AppStatusText.ASK_NO_PHOTO, screen.value.statusLine)
    }

    @Test
    fun `sans cle LLM — refus local explique, aucun appel reseau`() = runTest {
        val screen = screen(settings = Settings.Default)
        val llm = FakeLlm()

        chat(screen, llm).ask("où est la porte ?")

        assertEquals(0, llm.askCalls)
        assertFalse("pas de « … » sur un refus", screen.value.asking)
        assertEquals(LLM_ABSENT_LINE, screen.value.statusLine)
        assertTrue(screen.value.chat.isEmpty())
    }

    @Test
    fun `question vide — aucun appel au LLM`() = runTest {
        val screen = screen()
        val llm = FakeLlm()

        chat(screen, llm).ask("   ")

        assertEquals(0, llm.askCalls)
        assertFalse(screen.value.asking)
    }

    @Test
    fun `reponse — deux tours d historique et phrase lue`() = runTest {
        val screen = screen()
        val llm = FakeLlm(onAsk = { question, _ -> Result.success("la porte est à droite ($question)") })

        chat(screen, llm).ask("  où est la porte ?  ")

        assertEquals(1, llm.askCalls)
        assertEquals(2, screen.value.chat.size)
        assertEquals(Role.USER, screen.value.chat.first().role)
        assertEquals("où est la porte ?", screen.value.chat.first().content)
        assertEquals("la porte est à droite (où est la porte ?)", screen.value.chat.last().content)
        assertFalse(screen.value.asking)
        assertEquals(listOf("la porte est à droite (où est la porte ?)"), spoken)
    }

    @Test
    fun `lecture auto desactivee — la reponse reste muette`() = runTest {
        val screen = screen(settings = ConfiguredSettings.copy(ttsAutoplay = false))
        val llm = FakeLlm()

        chat(screen, llm).ask("et là ?")

        assertEquals(1, llm.askCalls)
        assertTrue("aucune phrase lue", spoken.isEmpty())
    }

    @Test
    fun `echec LLM — motif lisible et asking retombe`() = runTest {
        val screen = screen()
        val llm = FakeLlm(onAsk = { _, _ -> Result.failure(ApiError.Timeout("délai dépassé")) })

        chat(screen, llm).ask("et là ?")

        assertFalse("plus de « … »", screen.value.asking)
        assertEquals("Question unanswered: délai dépassé", screen.value.statusLine)
        assertTrue("historique intact", screen.value.chat.isEmpty())
    }
}