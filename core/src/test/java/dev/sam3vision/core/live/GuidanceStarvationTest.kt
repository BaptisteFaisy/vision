package dev.sam3vision.core.live

import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Le guetteur de famine du guidage : pas de consigne fraîche alors qu'on
 * guide = un avertissement unique, puis un retour unique à la reprise.
 * Sans lui, une panne SAM/réseau tait la voix sans rien dire — l'aveugle
 * marche sans savoir que le guidage est coupé.
 */
class GuidanceStarvationTest {

    @Test
    fun `frais continu — jamais d'alerte`() {
        val watcher = GuidanceStarvation(warnAfterMs = 6_000L)

        (0L..20_000L step 500L).forEach { now ->
            assertNull(watcher.onTick(now, fresh = true, guiding = true))
        }
    }

    @Test
    fun `famine brève — silence (pas de bavardage sur un trou de 3 s)`() {
        val watcher = GuidanceStarvation(warnAfterMs = 6_000L)

        (0L..3_000L step 500L).forEach { now ->
            assertNull(watcher.onTick(now, fresh = false, guiding = true))
        }
        assertNull(watcher.onTick(3_500L, fresh = true, guiding = true))
    }

    @Test
    fun `famine longue — un seul avertissement`() {
        val watcher = GuidanceStarvation(warnAfterMs = 6_000L)
        val events = (0L..20_000L step 500L).mapNotNull { now ->
            watcher.onTick(now, fresh = false, guiding = true)
        }

        assertEquals(listOf(StarvationEvent.Warn), events)
    }

    @Test
    fun `reprise après avertissement — un seul retour`() {
        val watcher = GuidanceStarvation(warnAfterMs = 6_000L)
        (0L..10_000L step 500L).forEach { now -> watcher.onTick(now, fresh = false, guiding = true) }

        assertEquals(StarvationEvent.Resumed, watcher.onTick(10_500L, fresh = true, guiding = true))
        assertNull(watcher.onTick(11_000L, fresh = true, guiding = true))
    }

    @Test
    fun `pas de guidage — jamais d'alerte, même affamé`() {
        val watcher = GuidanceStarvation(warnAfterMs = 6_000L)

        (0L..20_000L step 500L).forEach { now ->
            assertNull(watcher.onTick(now, fresh = false, guiding = false))
        }
    }

    @Test
    fun `guidage coupé en pleine famine — reset silencieux, pas de retour fantôme`() {
        val watcher = GuidanceStarvation(warnAfterMs = 6_000L)
        (0L..10_000L step 500L).forEach { now -> watcher.onTick(now, fresh = false, guiding = true) }

        assertNull(watcher.onTick(10_500L, fresh = false, guiding = false))
        assertNull(watcher.onTick(11_000L, fresh = true, guiding = true))
    }

    @Test
    fun `phrases — anglais bref, français miroir`() {
        assertEquals("Guidance paused. Reconnecting.", GuidanceStarvation.warnPhrase(SpeechLanguage.En))
        assertEquals("Guidance resumed.", GuidanceStarvation.resumedPhrase(SpeechLanguage.En))
        assertEquals("Guidage en pause. Reconnexion en cours.", GuidanceStarvation.warnPhrase(SpeechLanguage.Fr))
        assertEquals("Guidage repris.", GuidanceStarvation.resumedPhrase(SpeechLanguage.Fr))
    }
}
