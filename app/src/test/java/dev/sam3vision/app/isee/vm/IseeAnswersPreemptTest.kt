package dev.sam3vision.app.isee.vm

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Concurrence des réponses : une nouvelle parole écrase la précédente (overwrite)
 * au lieu d'être jetée — sans jamais lancer deux appels LLM en parallèle
 * (409 « limite atteinte »). Socle : [IseeAnswersTestBase].
 */
class IseeAnswersPreemptTest : IseeAnswersTestBase() {

    @Test
    fun `deux questions en meme temps — la seconde ecrase la premiere, jamais deux appels a la fois`() = runBlocking {
        val concurrent = AtomicInteger()
        val maxConcurrent = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val answers = answers({ _, _, _ ->
            maxConcurrent.accumulateAndGet(concurrent.incrementAndGet()) { a, b -> maxOf(a, b) }
            try {
                gate.await()
                Result.success("Devant toi")
            } finally {
                concurrent.decrementAndGet()
            }
        })
        val row = seedRow()
        val first = launch { answers.answer(row, "première") }
        // Le premier appel est en vol : la seconde parole l'écrase au lieu d'être jetée.
        withTimeout(2_000) { while (concurrent.get() == 0) delay(5) }

        val second = launch { answers.answer(row, "seconde") }
        withTimeout(2_000) { first.join() }

        gate.complete(Unit)
        withTimeout(2_000) { second.join() }

        assertTrue("la première est coupée", first.isCancelled)
        assertEquals("la seconde passe", "Devant toi", dao.byId(row.id)?.texteIA)
        assertEquals("jamais deux appels à la fois", 1, maxConcurrent.get())
        assertFalse("aucune erreur busy/thinking", state.value.status == IseeStatusText.ANSWER_PENDING)
    }

    @Test
    fun `reponse qui ignore l'annulation — la suivante renonce sans second appel`() = runBlocking {
        val calls = AtomicInteger()
        val never = CompletableDeferred<Unit>()
        val answers = IseeAnswers(
            media = media,
            settings = { settings },
            update = ::update,
            transcript = transcript,
            playback = playback(),
            photo = shots,
            prune = {},
            ask = { _, _, _, _ ->
                calls.incrementAndGet()
                withContext(NonCancellable) { never.await() }
                Result.success("jamais")
            },
            preemptTimeoutMs = 100L,
        )
        val row = seedRow()
        val first = launch { answers.answer(row, "première") }
        withTimeout(2_000) { while (calls.get() == 0) delay(5) }

        // La précédente est coincée : passé le plafond, la suivante renonce plutôt
        // que de lancer un second appel (409 « limite atteinte »).
        answers.answer(row, "seconde")

        assertEquals(IseeStatusText.ANSWER_PENDING, state.value.status)
        assertEquals("un seul appel LLM", 1, calls.get())
        never.complete(Unit)
        withTimeout(2_000) { first.join() }
    }
}
