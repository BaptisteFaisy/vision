package dev.sam3vision.app.glasses

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Retombée de session ([GlassesSessionOpener.settled]) : une session établie qui
 * tombe se reprend seule (comme la mort rapide), en budget borné — et l'ordre de
 * l'utilisateur garde toujours le dessus. Aucune avance ne touche le SDK : la
 * phase reste `STOPPED`, donc la réouverture s'interrompt avant `createSession`.
 */
class GlassesSessionOpenerTest {

    private class Harness(clockMs: Long) {
        var phase: SessionPhase = SessionPhase.STOPPED
        var releases = 0
        var starts = 0
        val notes = mutableListOf<GlassesMessage>()
        val clock: () -> Long = { clockMs }

        fun opener(scope: kotlinx.coroutines.CoroutineScope) = GlassesSessionOpener(
            scope = scope,
            clock = clock,
            snapshot = { GlassesUiState(phase = phase) },
            idle = { },
            note = { notes += it },
            report = { _, _, _ -> },
            onCreated = { },
            starting = { starts += 1 },
        )
    }

    @Test
    fun `une session vecue qui tombe se reprend seule apres 1s`() = runTest {
        val harness = Harness(clockMs = 120_000L)
        val opener = harness.opener(this)
        // `startMs` vaut 0 (jamais créée ici) : 120 s de vie, pardonnée.
        opener.settled { harness.releases += 1 }
        assertEquals(1, harness.releases)
        assertEquals(1, harness.notes.size)
        assertEquals(MessageKind.INFO, harness.notes.last().kind)
        assertTrue(harness.notes.last().text, harness.notes.last().text.contains("reconnecting"))
        assertEquals(0, harness.starts)
        testScheduler.advanceTimeBy(1_000L)
        assertEquals(1, harness.starts)
    }

    @Test
    fun `onze chutes rapprochees concluent au refus durable`() = runTest {
        val harness = Harness(clockMs = 30_000L)
        val opener = harness.opener(this)
        // 30 s de vie : ni rapide, ni pardonnée — chaque chute accumule.
        repeat(RESCUE_MAX_RETRIES + 1) { opener.settled { harness.releases += 1 } }
        opener.cancel()
        assertEquals(RESCUE_MAX_RETRIES + 1, harness.releases)
        assertEquals(RESCUE_MAX_RETRIES + 1, harness.notes.size)
        assertEquals(MessageKind.ERROR, harness.notes.last().kind)
        assertEquals(0, harness.starts)
    }

    @Test
    fun `la reprise attendue s efface si l utilisateur reprend la main`() = runTest {
        val harness = Harness(clockMs = 120_000L)
        val opener = harness.opener(this)
        opener.settled { harness.releases += 1 }
        harness.phase = SessionPhase.IDLE
        testScheduler.advanceTimeBy(60_000L)
        assertEquals(0, harness.starts)
    }
}
