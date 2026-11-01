package dev.sam3vision.app.isee

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Le collecteur des phrases de la sonde : une répétition identique ne
 * ré-annule jamais sa propre synthèse en vol (§7 — le `spokenSeq` fait
 * passer chaque ré-émission, clips 2 s), seule une phrase fraîche écrase.
 */
class ProbeSpokenFlowTest {

    @Test
    fun `repetition identique en vol — pas de second appel`() = runTest {
        val calls = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val flow = MutableSharedFlow<SpokenEmission>(extraBufferCapacity = 8)
        val scope = avecCollecteur(flow) { text, _ ->
            calls += text
            gate.await()
        }
        try {
            flow.emit(SpokenEmission("left.", urgent = false, seq = 1))
            runCurrent()
            flow.emit(SpokenEmission("left.", urgent = false, seq = 2))
            advanceUntilIdle()

            assertEquals(listOf("left."), calls)
            gate.complete(Unit)
            advanceUntilIdle()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `phrase fraiche — l'ancienne est annulee`() = runTest {
        val started = mutableListOf<String>()
        val finished = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val flow = MutableSharedFlow<SpokenEmission>(extraBufferCapacity = 8)
        val scope = avecCollecteur(flow) { text, _ ->
            started += text
            gate.await()
            finished += text
        }
        try {
            flow.emit(SpokenEmission("left.", urgent = false, seq = 1))
            runCurrent()
            flow.emit(SpokenEmission("right.", urgent = false, seq = 2))
            advanceUntilIdle()
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("left.", "right."), started)
            assertEquals(listOf("right."), finished)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `repetition apres fin — rejoue (directions en continu)`() = runTest {
        val calls = mutableListOf<String>()
        val flow = MutableSharedFlow<SpokenEmission>(extraBufferCapacity = 8)
        val scope = avecCollecteur(flow) { text, _ -> calls += text }
        try {
            flow.emit(SpokenEmission("left.", urgent = false, seq = 1))
            advanceUntilIdle()
            flow.emit(SpokenEmission("left.", urgent = false, seq = 2))
            advanceUntilIdle()

            assertEquals(listOf("left.", "left."), calls)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `phrase nulle — ignoree`() = runTest {
        var calls = 0
        val flow = MutableSharedFlow<SpokenEmission>(extraBufferCapacity = 8)
        val scope = avecCollecteur(flow) { _, _ -> calls++ }
        try {
            flow.emit(SpokenEmission(null, urgent = false, seq = 1))
            advanceUntilIdle()

            assertEquals(0, calls)
        } finally {
            scope.cancel()
        }
    }


    /**
     * Câble le collecteur sur un scope au même ordonnanceur (temps virtuel
     * partagé), collecteur démarré : l'appelant annule en fin de test, le
     * collecteur infini ne doit ni fuir ni pendre.
     */
    private fun TestScope.avecCollecteur(
        flow: MutableSharedFlow<SpokenEmission>,
        say: suspend (text: String, urgent: Boolean) -> Unit,
    ): CoroutineScope {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        scope.observeSpokenEmissions(flow, say)
        runCurrent()
        return scope
    }
}
