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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Destination GPS en cours sur le direct : tant que le guidage est actif,
 * les questions partent au LLM avec la destination en contexte — en plus
 * du journal caméra, jamais à la place.
 */
class LiveAskDestinationTest {

    private val configured =
        Settings.Default.copy(llmBaseUrl = "https://llm.test", llmApiKey = "cle-test")

    /** Détection en pixels : [x] normalisé (0..1) sur une image 100×100. */
    private fun detection(label: String, x: Float): Detection {
        val px = x * 100f
        return Detection(label, 0.9f, listOf(px, 0f, px + 20f, 20f), 100, 100)
    }

    /** LLM espion : note le contexte envoyé à chaque question. */
    private class FakeLlm : LlmClient {
        val contexts = mutableListOf<String?>()

        override suspend fun describe(jpegB64: String, labels: List<String>): Result<Analysis> =
            error("describe n'est pas utilisé par les questions")

        override suspend fun ask(
            jpegB64: String,
            question: String,
            history: List<ChatTurn>,
            context: String?,
        ): Result<String> {
            contexts += context
            return Result.success("réponse")
        }
    }

    private fun harness(
        scope: TestScope,
        timeline: LiveTimeline,
        llm: LlmClient,
        destination: () -> String? = { null },
    ): LiveAskSession {
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
            destination = destination,
        )
    }

    @Test
    fun `destination seule — la ligne GPS est tout le contexte`() = runTest {
        val llm = FakeLlm()
        val asker = harness(this, LiveTimeline(), llm, destination = { "la gare" })

        asker.ask("c'est encore loin ?")
        advanceUntilIdle()

        assertEquals(listOf("Active GPS destination: \"la gare\"."), llm.contexts)
    }

    @Test
    fun `journal et destination — les deux blocs partent`() = runTest {
        val timeline = LiveTimeline()
        timeline.record(System.currentTimeMillis(), listOf(detection("personne", 0.1f)))
        val llm = FakeLlm()
        val asker = harness(this, timeline, llm, destination = { "la gare" })

        asker.ask("il y avait quoi il y a 5 secondes ?")
        advanceUntilIdle()

        val context = llm.contexts.single()
        assertTrue("le journal part : $context", context!!.contains("personne"))
        assertTrue("la destination part : $context", context.contains("Active GPS destination: \"la gare\"."))
    }

    @Test
    fun `sans destination — le journal seul`() = runTest {
        val timeline = LiveTimeline()
        timeline.record(System.currentTimeMillis(), listOf(detection("personne", 0.1f)))
        val llm = FakeLlm()
        val asker = harness(this, timeline, llm)

        asker.ask("question")
        advanceUntilIdle()

        val context = llm.contexts.single()
        assertTrue("le journal part : $context", context!!.contains("personne"))
        assertFalse("pas de destination : $context", context.contains("GPS"))
    }

    @Test
    fun `ni journal ni destination — aucun contexte`() = runTest {
        val llm = FakeLlm()
        val asker = harness(this, LiveTimeline(), llm)

        asker.ask("question")
        advanceUntilIdle()

        assertNull(llm.contexts.single())
    }

    @Test
    fun `fournisseur qui jette — la question part, journal seul`() = runTest {
        val timeline = LiveTimeline()
        timeline.record(System.currentTimeMillis(), listOf(detection("personne", 0.1f)))
        val llm = FakeLlm()
        val asker = harness(this, timeline, llm, destination = { error("mémoire en panne") })

        asker.ask("question")
        advanceUntilIdle()

        val context = llm.contexts.single()
        assertTrue("le journal part : $context", context!!.contains("personne"))
        assertFalse("pas de destination : $context", context.contains("GPS"))
    }
}
