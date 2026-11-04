package dev.sam3vision.app.isee

import dev.sam3vision.core.model.LiveFrame
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Silence juste, veto câblé, jambe dans les diags, retry borné : le lien n'accuse
 * plus le réseau quand rien n'est envoyé (veto nuit, porte fermée), le veto remonte
 * à l'UI au lieu de rester dans les logs, et la jambe WS se lit dans le panneau.
 * Horloges injectées : aucun test ne dort.
 */
class LiveProbeSilenceTest {

    /** Ces tests lancent de vraies frames : les globaux de séance suivent. */
    @After
    fun remettreLaSeanceAZero() = seanceVide()

    @Test
    fun `sans envoi, jamais de silence`() {
        val link = ProbeLink()

        assertEquals("rien tenté : on n'affirme rien", ProbeLinkKind.Idle, link.snapshot(3_600_000).kind)
        link.onAnswer(10_000)
        assertEquals("vieille réponse, aucun envoi : pas de silence", ProbeLinkKind.Warm, link.snapshot(1_000_000).kind)
    }

    @Test
    fun `le silence exige un envoi sans reponse`() {
        val link = ProbeLink()
        link.onAnswer(10_000)

        link.onSent(20_000)
        val silent = link.snapshot(23_500)
        assertEquals(ProbeLinkKind.Silent, silent.kind)
        assertEquals("âge compté depuis la dernière réponse", 13_500L, silent.lastAnswerAgeMs)

        link.onAnswer(24_000)
        assertEquals("une réponse solde l'envoi", ProbeLinkKind.Warm, link.snapshot(24_100).kind)
    }

    @Test
    fun `reset oublie les envois`() {
        val link = ProbeLink()
        link.onAnswer(1_000)
        link.onSent(2_000)

        link.reset()

        assertEquals(ProbeLinkKind.Idle, link.snapshot(999_999).kind)
    }

    @Test
    fun `le veto nuit dit luminosite insuffisante, jamais link silent`() {
        val vetoed = LiveProbeState(dark = true, lowLightVetoes = 41)

        assertEquals(LOW_LIGHT_PHRASE, liveProbeNotice(vetoed))
        val stale = LiveProbeState(dark = true, error = "no reply for 9 s — link silent (retrying)")
        assertEquals("le veto prime sur une erreur périmée", LOW_LIGHT_PHRASE, liveProbeNotice(stale))
        assertFalse(liveProbeNotice(vetoed)!!.contains("link silent"))
        assertFalse(liveProbeNotice(vetoed)!!.contains("network cut"))
        assertTrue(liveProbeDetails(vetoed).contains("dark: true · vetoes 41"))
        assertTrue(liveProbeDetails(LiveProbeState()).none { it.startsWith("dark:") })
    }

    @Test
    fun `noteLowLight compte et noteLight efface`() = runTest {
        val (probe, _) = probeHarness(this)

        probe.noteLowLight()
        probe.noteLowLight()
        assertTrue(probe.state.value.dark)
        assertEquals(2L, probe.state.value.lowLightVetoes)

        probe.noteLight()
        assertFalse("la lumière efface le veto", probe.state.value.dark)
        assertEquals("le compteur survit", 2L, probe.state.value.lowLightVetoes)
    }

    @Test
    fun `un envoi prouve le retour de la lumiere`() = runTest {
        val (probe, fake) = probeHarness(this)
        probe.noteLowLight()

        probe.onFrame("A")
        advanceUntilIdle()
        fake.respond("A", LiveFrame(width = 512, height = 384, elapsedMs = 120L, objects = emptyList()))
        advanceUntilIdle()

        assertEquals("la frame est partie", 1, fake.sent)
        assertFalse("l'envoi lève le veto", probe.state.value.dark)
    }

    @Test
    fun `la jambe parait dans la ligne lien`() {
        val link = ProbeLinkState(ProbeLinkKind.Warm, 300)

        assertEquals(
            "link: warm · 0.3 s · fails 0 · reprises 0 · binary",
            liveProbeLinkLine(link, "binary"),
        )
        assertEquals(
            "sans jambe, la ligne ne change pas",
            "link: warm · 0.3 s · fails 0 · reprises 0",
            liveProbeLinkLine(link),
        )
        val details = liveProbeDetails(
            LiveProbeState(
                endpoint = "https://sam.test",
                leg = "text",
                link = ProbeLinkState(ProbeLinkKind.Silent, 4_200, 3, 1),
            ),
        )
        assertTrue(details.contains("link: silent · 4.2 s · fails 3 · reprises 1 · text"))
    }

    @Test
    fun `publish pose la jambe de la session`() = runTest {
        val (probe, fake) = probeHarness(this)

        probe.onFrame("A")
        advanceUntilIdle()
        fake.respond("A", LiveFrame(width = 512, height = 384, elapsedMs = 120L, objects = emptyList()))
        advanceUntilIdle()

        assertEquals("faux client sans socket : jambe http", "http", probe.state.value.leg)
    }

    @Test
    fun `le nouvel essai manuel est borne`() {
        val link = ProbeLink()

        assertTrue(link.retryDue(10_000))
        assertFalse("1 s après : refusé", link.retryDue(11_000))
        assertTrue("5 s après : dû", link.retryDue(15_000))
        assertTrue("horloge qui recule : on réessaie", link.retryDue(1_000))
    }

    @Test
    fun `retryNow trop tot ne touche ni session ni reveil`() = runTest {
        val (probe, fake) = probeHarness(this)
        advanceUntilIdle()
        val t0 = System.currentTimeMillis()
        val closed0 = fake.closed
        val health0 = fake.healthCalls

        // Accepté : le faux client rend false au preconnect, mais ferme + réveille.
        probe.retryNow(t0)
        advanceUntilIdle()
        assertEquals(closed0 + 1, fake.closed)
        assertEquals(health0 + 1, fake.healthCalls)

        assertFalse("trop tôt : refusé", probe.retryNow(t0 + 1_000))
        advanceUntilIdle()
        assertEquals("aucune fermeture", closed0 + 1, fake.closed)
        assertEquals("aucun réveil", health0 + 1, fake.healthCalls)

        probe.retryNow(t0 + RETRY_COOLDOWN_MS + 1)
        advanceUntilIdle()
        assertEquals("après le cooldown : refermé", closed0 + 2, fake.closed)
        assertEquals("après le cooldown : réveillé", health0 + 2, fake.healthCalls)
    }
}
