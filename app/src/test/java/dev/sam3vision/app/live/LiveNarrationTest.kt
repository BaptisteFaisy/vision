package dev.sam3vision.app.live

import dev.sam3vision.app.LiveUiState
import dev.sam3vision.core.net.LiveLlmClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Narration continue : une frame par deux secondes au LLM vision, `narrationBusy` levé
 * dès l'envoi, et l'erreur du modèle visible sans casser la séance.
 */
class LiveNarrationTest {

    private val state = MutableStateFlow(LiveUiState())

    private class FakeLlm(var result: Result<String>) : LiveLlmClient {
        var calls = 0
        var lastLabels: List<String> = emptyList()

        override suspend fun describeFrame(jpegB64: String, labels: List<String>, previous: String): Result<String> {
            calls++
            lastLabels = labels
            return result
        }
    }

    private fun narration(scope: TestScope, llm: LiveLlmClient, labels: List<String> = listOf("personne")) =
        LiveNarration(scope, state) { labels }

    @Test
    fun `la description arrive dans l etat et le busy retombe`() = runTest {
        val llm = FakeLlm(Result.success("Une personne traverse."))
        val narration = narration(this, llm)
        narration.open(llm)
        narration.narrate("jpeg")
        assertTrue("le panneau doit montrer l'attente", state.value.narrationBusy)
        advanceUntilIdle()
        assertEquals("Une personne traverse.", state.value.narration)
        assertFalse(state.value.narrationBusy)
        assertNull(state.value.narrationError)
        assertEquals(1, llm.calls)
    }

    @Test
    fun `une frame par deux secondes`() = runTest {
        val llm = FakeLlm(Result.success("texte"))
        val narration = narration(this, llm)
        narration.open(llm)
        narration.narrate("jpeg-1")
        advanceUntilIdle()
        narration.narrate("jpeg-2")
        advanceUntilIdle()
        assertEquals("la seconde frame est trop tôt", 1, llm.calls)
    }

    @Test
    fun `un echec publie son message sans effacer la description precedente`() = runTest {
        val llm = FakeLlm(Result.failure(IllegalStateException("LLM KO")))
        val narration = narration(this, llm)
        narration.open(llm)
        narration.narrate("jpeg")
        advanceUntilIdle()
        assertEquals("LLM KO", state.value.narrationError)
        assertEquals("", state.value.narration)
        assertFalse(state.value.narrationBusy)
    }

    @Test
    fun `close arrete la narration`() = runTest {
        val llm = FakeLlm(Result.success("texte"))
        val narration = narration(this, llm)
        narration.open(llm)
        narration.close()
        narration.narrate("jpeg")
        advanceUntilIdle()
        assertEquals(0, llm.calls)
        assertFalse(state.value.narrationBusy)
    }

    @Test
    fun `les labels de la frame courante sont transmis au modele`() = runTest {
        val llm = FakeLlm(Result.success("texte"))
        val narration = narration(this, llm, labels = listOf("personne", "chaise"))
        narration.open(llm)
        narration.narrate("jpeg")
        advanceUntilIdle()
        assertEquals(listOf("personne", "chaise"), llm.lastLabels)
    }
}