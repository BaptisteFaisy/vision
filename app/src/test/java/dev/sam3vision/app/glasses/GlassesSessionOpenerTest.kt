package dev.sam3vision.app.glasses

import com.meta.wearable.dat.core.types.DeviceSessionError
import kotlinx.coroutines.test.runCurrent
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
        // Marge past la borne (la tâche à l'exact délai peut ne pas partir) + purge.
        testScheduler.advanceTimeBy(1_500L)
        runCurrent()
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

    @Test
    fun `trois morts rapides puis le refus se dit sans nouvel essai`() = runTest {
        val harness = Harness(clockMs = 1_000L)
        val opener = harness.opener(this)
        // `startMs` vaut 0 (jamais créée ici) : 1 s de vie, mort rapide.
        repeat(QUICK_DEATH_MAX_RETRIES) { opener.settled { harness.releases += 1 } }
        assertEquals(QUICK_DEATH_MAX_RETRIES, harness.notes.size)
        assertTrue(harness.notes[0].text, harness.notes[0].text.contains("(1/3)"))
        assertTrue(harness.notes[1].text, harness.notes[1].text.contains("(2/3)"))
        assertTrue(harness.notes[2].text, harness.notes[2].text.contains("(3/3)"))
        opener.settled { harness.releases += 1 }
        opener.cancel()
        assertEquals(QUICK_DEATH_MAX_RETRIES + 1, harness.notes.size)
        assertEquals(MessageKind.ERROR, harness.notes.last().kind)
        assertEquals(0, harness.starts)
    }

    @Test
    fun `la mort rapide nomme sa cause dans le nouvel essai`() = runTest {
        val harness = Harness(clockMs = 1_000L)
        val opener = harness.opener(this)
        opener.onSessionError(DeviceSessionError.SESSION_ENDED_BY_DEVICE)
        opener.settled { harness.releases += 1 }
        opener.cancel()
        assertEquals(1, harness.notes.size)
        assertEquals(MessageKind.INFO, harness.notes.last().kind)
        assertTrue(harness.notes.last().text, harness.notes.last().text.contains("(SESSION_ENDED_BY_DEVICE)"))
        assertTrue(harness.notes.last().text, harness.notes.last().text.contains("(1/3)"))
    }

    @Test
    fun `l echo inattendu post-stopped n ecrase pas la cause sue`() = runTest {
        val harness = Harness(clockMs = 1_000L)
        val opener = harness.opener(this)
        opener.onSessionError(DeviceSessionError.SESSION_ENDED_BY_DEVICE)
        opener.onSessionError(DeviceSessionError.UNEXPECTED_ERROR)
        opener.settled { harness.releases += 1 }
        opener.cancel()
        assertTrue(harness.notes.last().text, harness.notes.last().text.contains("(SESSION_ENDED_BY_DEVICE)"))
    }

    @Test
    fun `une erreur deterministe sur mort rapide se dit sans essai`() = runTest {
        val harness = Harness(clockMs = 1_000L)
        val opener = harness.opener(this)
        opener.onSessionError(DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED)
        opener.settled { harness.releases += 1 }
        testScheduler.advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(1, harness.notes.size)
        assertEquals(MessageKind.ERROR, harness.notes.last().kind)
        assertTrue(harness.notes.last().text, harness.notes.last().text.contains("must be updated"))
        assertEquals(0, harness.starts)
    }

    @Test
    fun `une erreur deterministe sur chute etablie se dit sans essai`() = runTest {
        val harness = Harness(clockMs = 120_000L)
        val opener = harness.opener(this)
        // 120 s de vie : chute établie pardonnée, mais le fatal coupe court.
        opener.onSessionError(DeviceSessionError.INSUFFICIENT_SDK_VERSION)
        opener.settled { harness.releases += 1 }
        testScheduler.advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(1, harness.notes.size)
        assertEquals(MessageKind.ERROR, harness.notes.last().kind)
        assertEquals(0, harness.starts)
    }

    @Test
    fun `open remet les compteurs a zero`() = runTest {
        val harness = Harness(clockMs = 1_000L)
        val opener = harness.opener(this)
        repeat(QUICK_DEATH_MAX_RETRIES) { opener.settled { harness.releases += 1 } }
        opener.open()
        opener.settled { harness.releases += 1 }
        opener.cancel()
        assertTrue(harness.notes.last().text, harness.notes.last().text.contains("(1/3)"))
    }
}
