package dev.sam3vision.app.live

import dev.sam3vision.app.LiveUiState
import dev.sam3vision.core.live.LiveTimeline
import dev.sam3vision.core.model.Analysis
import dev.sam3vision.core.model.ChatTurn
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.net.LlmClient
import dev.sam3vision.core.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Arrêt du direct : la mémoire du direct doit être **oubliée tout de suite**, pas au
 * démarrage suivant. Avant, `stop()` ne vidait que l'état affiché : le journal
 * ([LiveTimeline]) et l'historique de conversation survivaient, donc l'écran d'un
 * direct arrêté répondait encore sur ce que le direct précédent avait vu.
 *
 * [LiveAskSession.reset] est le point unique où cette mémoire s'efface — il est appelé
 * au démarrage **et** à l'arrêt, ce qui garantit qu'aucune des deux portes n'oublie
 * quelque chose que l'autre efface.
 */
class LiveAskForgetTest {

    private val configured =
        Settings.Default.copy(llmBaseUrl = "https://llm.test", llmApiKey = "cle-test")

    /** Détection en pixels : [x] normalisé (0..1) sur une image 100×100. */
    private fun detection(label: String, x: Float): Detection {
        val px = x * 100f
        return Detection(label, 0.9f, listOf(px, 0f, px + 20f, 20f), 100, 100)
    }

    /** LLM espion : note l'historique et le contexte envoyés à chaque question. */
    private class FakeLlm : LlmClient {
        val historySizes = mutableListOf<Int>()
        val contexts = mutableListOf<String?>()

        override suspend fun describe(jpegB64: String, labels: List<String>): Result<Analysis> =
            error("describe n'est pas utilisé par les questions")

        override suspend fun ask(
            jpegB64: String,
            question: String,
            history: List<ChatTurn>,
            context: String?,
        ): Result<String> {
            historySizes += history.size
            contexts += context
            return Result.success("réponse")
        }
    }

    private fun harness(scope: TestScope, timeline: LiveTimeline, llm: LlmClient): LiveAskSession {
        val state = MutableStateFlow(LiveUiState())
        return LiveAskSession(
            scope = scope,
            state = state,
            timeline = timeline,
            latestJpeg = { "jpeg-de-test" },
            refreshSettings = {},
            settings = { configured },
            llm = { llm },
            windowMs = 30_000L,
        )
    }

    @Test
    fun `l arret vide le journal et l historique du direct precedent`() = runTest {
        val timeline = LiveTimeline()
        timeline.record(System.currentTimeMillis(), listOf(detection("personne", 0.1f)))
        assertEquals("le direct a bien vu quelque chose", 1, timeline.size)

        val llm = FakeLlm()
        val asker = harness(this, timeline, llm)
        asker.ask("première question")
        advanceUntilIdle()
        assertEquals("le journal du direct part au LLM", 1, llm.contexts.size)

        asker.reset() // ce que fait « Stop »

        assertEquals("l'arrêt vide le journal", 0, timeline.size)
        asker.ask("et il y a cinq secondes ?")
        advanceUntilIdle()
        assertNull("aucun contexte du direct précédent", llm.contexts.last())
        assertEquals("la conversation repart de zéro", 0, llm.historySizes.last())
    }
}