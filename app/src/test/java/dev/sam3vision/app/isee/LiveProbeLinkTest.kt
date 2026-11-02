package dev.sam3vision.app.isee

import dev.sam3vision.app.live.LiveLatencyLog
import dev.sam3vision.core.net.ApiError
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le lien de la sonde dit la vérité : budget froid payé une seule fois par réveil,
 * silence compté depuis la dernière réponse aboutie, texte de panne qui distingue
 * réseau et réveil GPU, et log d'échec throttlé. Tout est piloté par des instants
 * explicites ou une horloge injectée : aucun test ne dort.
 */
class LiveProbeLinkTest {

    @After
    fun `le throttle ne fuit pas vers les autres tests`() {
        LiveLatencyLog.resetFailures()
    }

    @Test
    fun `le budget froid n_est arme qu_une fois par reveil`() {
        val link = ProbeLink()

        assertTrue("premier appel d'un endpoint : réveil", link.coldDue(ENDPOINT, 1_000))
        link.armCold()
        assertEquals("budget froid armé", ProbeLinkKind.Cold, link.snapshot(1_100).kind)

        link.markPaid(ENDPOINT, 1_200)
        assertFalse("un configure en séance ne réarme rien", link.coldDue(ENDPOINT, 2_000))
        assertFalse("ni une bascule de mode", link.coldDue(ENDPOINT, 3_000))
        assertTrue("autre endpoint : nouveau réveil", link.coldDue(AUTRE, 3_000))
        assertTrue(
            "conteneur rendormi : le budget redevient dû",
            link.coldDue(ENDPOINT, 1_200 + LINK_WAKE_STALE_MS + 1),
        )

        link.reset()
        assertTrue("arrêt du direct : le réveil suivant repaie", link.coldDue(ENDPOINT, 4_000))
        assertFalse("sans endpoint, rien n'est dû", link.coldDue("", 5_000))
    }

    @Test
    fun `le silence se compte depuis la derniere reponse`() {
        val link = ProbeLink()

        assertEquals("aucune réponse : on n'affirme rien", ProbeLinkKind.Idle, link.snapshot(0).kind)
        link.onAnswer(10_000)
        assertEquals("le lien vit", ProbeLinkKind.Warm, link.snapshot(12_900).kind)

        val silent = link.snapshot(13_500)
        assertEquals(ProbeLinkKind.Silent, silent.kind)
        assertEquals("âge de la dernière réponse", 3_500L, silent.lastAnswerAgeMs)
        assertTrue(silent.silent)

        link.onAnswer(14_000)
        assertEquals("une réponse efface le silence", ProbeLinkKind.Warm, link.snapshot(14_100).kind)
        assertEquals("100 ms depuis la réponse", 100L, link.snapshot(14_100).lastAnswerAgeMs)
    }

    @Test
    fun `une reponse apres deux echecs compte une reprise`() {
        val link = ProbeLink()
        link.onAnswer(1_000)

        assertEquals(1, link.onFailure())
        assertEquals(2, link.onFailure())
        assertEquals(2, link.snapshot(1_500).consecutiveFailures)

        link.onAnswer(2_000)
        val back = link.snapshot(2_100)
        assertEquals("échecs soldés", 0, back.consecutiveFailures)
        assertEquals("une reprise, pas deux", 1, back.reconnects)
    }

    @Test
    fun `l etat du lien ne s ecrit pas a chaque frame`() {
        val link = ProbeLink()
        link.onAnswer(0)

        assertNotNull("première écriture", link.tick(0))
        assertNull("500 ms plus tard, rien de neuf", link.tick(500))
        assertEquals("3 s de silence : la nature change", ProbeLinkKind.Silent, link.tick(3_500)!!.kind)
        assertNull("même seconde de silence : rien", link.tick(3_900))
        assertNotNull("seconde suivante : l'âge se dit", link.tick(4_500))
        assertNotNull("réécriture imposée", link.tick(4_600, force = true))
    }

    @Test
    fun `le texte de silence remplace l erreur et s efface au retour`() {
        val silent = ProbeLinkState(ProbeLinkKind.Silent, 4_200)
        assertEquals(SILENCE_NOTICE, linkErrorOf(null, silent))
        assertEquals("il remplace le mensonge « GPU »", SILENCE_NOTICE, linkErrorOf("GPU waking up… (retrying)", silent))
        assertEquals("GPU SAM éteint", linkErrorOf("GPU SAM éteint", ProbeLinkState(ProbeLinkKind.Warm, 120)))
        assertNull("au retour du lien il s'efface", linkErrorOf(SILENCE_NOTICE, ProbeLinkState(ProbeLinkKind.Warm, 120)))
    }

    @Test
    fun `la notice distingue reseau et reveil gpu`() {
        assertEquals(
            "le silence se dit même après la première frame",
            SILENCE_NOTICE,
            liveProbeNotice(LiveProbeState(frames = 12, link = ProbeLinkState(ProbeLinkKind.Silent, 4_200))),
        )

        val network = probeFailureText(ApiError.Timeout(), ProbeLinkState(ProbeLinkKind.Silent, 4_200))
        assertEquals(SILENCE_NOTICE, network)
        assertFalse("le réseau n'est pas un réveil de GPU", network.contains("GPU"))

        val slow = probeFailureText(ApiError.Timeout(), ProbeLinkState(ProbeLinkKind.Warm, 120))
        assertTrue(slow.contains("slow answer"))
        assertFalse("un lien chaud qui répond n'est pas un réveil", slow.contains("GPU"))

        val cold = probeFailureText(ApiError.Timeout(), ProbeLinkState(ProbeLinkKind.Cold))
        assertTrue("aucune réponse encore : réveil du conteneur", cold.contains("GPU waking up"))

        assertEquals(
            "non-régression : première frame sans réponse",
            "GPU waking up… (retrying on the next frame)",
            probeFailureText(ApiError.Timeout()),
        )
        assertEquals("waiting for the first analysis…", liveProbeNotice(LiveProbeState()))
        assertNull("rien à dire quand tout va bien", liveProbeNotice(LiveProbeState(frames = 4, warming = true)))
    }

    @Test
    fun `le panneau resume le lien`() {
        assertEquals("link: warm · 0.3 s · fails 0 · reprises 0", liveProbeLinkLine(ProbeLinkState(ProbeLinkKind.Warm, 300)))
        val details = liveProbeDetails(
            LiveProbeState(endpoint = ENDPOINT, link = ProbeLinkState(ProbeLinkKind.Silent, 4_200, 3, 1)),
        )
        assertTrue(details.contains("link: silent · 4.2 s · fails 3 · reprises 1"))
    }

    @Test
    fun `le log d echec est throttle a une ligne par seconde`() {
        assertTrue("première ligne", LiveLatencyLog.failureDue(1_000, null))
        assertFalse("dans la même seconde", LiveLatencyLog.failureDue(1_400, 1_000))
        assertTrue("une seconde plus tard", LiveLatencyLog.failureDue(2_000, 1_000))
        assertTrue("horloge qui recule : on réécrit", LiveLatencyLog.failureDue(10, 1_000))

        val line = LiveLatencyLog.failureLine(ApiError.Timeout(), 3)
        assertTrue(line.startsWith("live KO: Timeout"))
        assertTrue("le compte d'échecs consécutifs est joint", line.endsWith("(x3)"))
    }

    @Test
    fun `le throttle du journal n ecrit qu une ligne par seconde`() {
        LiveLatencyLog.resetFailures()

        assertTrue("la première passe", LiveLatencyLog.failure(ApiError.Timeout(), 1, 5_000))
        assertFalse("la deuxième est écrasée", LiveLatencyLog.failure(ApiError.Timeout(), 2, 5_400))
        assertTrue("la seconde suivante passe", LiveLatencyLog.failure(ApiError.Timeout(), 3, 6_000))
    }

    private companion object {
        const val ENDPOINT = "https://sam.test"
        const val AUTRE = "https://autre.test"
        const val SILENCE_NOTICE = "no reply for 4 s — network cut? (retrying)"
    }
}
