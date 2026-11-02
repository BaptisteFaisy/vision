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
 * au lieu d'être jetée — annulée puis attendue (jamais deux appels LLM en
 * parallèle, 409 « limite atteinte »), volée passé le plafond si elle ignore
 * l'annulation (overwrite forcé). Socle : [IseeAnswersTestBase].
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
        assertEquals("la seconde répond sans erreur", IseeStatusText.STEP_SPEAKING, state.value.status)
    }

    @Test
    fun `reponse qui ignore l'annulation — la suivante vole le drapeau et repond quand meme`() = runBlocking {
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
                if (calls.incrementAndGet() == 1) {
                    withContext(NonCancellable) { never.await() }
                    Result.success("jamais")
                } else {
                    Result.success("Écrasée")
                }
            },
            preemptTimeoutMs = 100L,
        )
        val row = seedRow()
        val first = launch { answers.answer(row, "première") }
        withTimeout(2_000) { while (calls.get() == 0) delay(5) }

        // La précédente est coincée : passé le plafond, la nouvelle vole le
        // drapeau et part quand même (overwrite forcé, ordre user) — jamais jetée.
        try {
            answers.answer(row, "seconde")

            assertEquals("la seconde répond malgré la précédente coincée", "Écrasée", dao.byId(row.id)?.texteIA)
            assertEquals("le second appel LLM part", 2, calls.get())
            assertEquals("la seconde répond sans erreur", IseeStatusText.STEP_SPEAKING, state.value.status)
            assertFalse("le « … » est retiré", state.value.thinking)
        } finally {
            // Échec comme succès : la première ignore l'annulation, seule cette
            // libération l'empêche de pendre le worker de test.
            never.complete(Unit)
        }
        withTimeout(2_000) { first.join() }
    }
}
