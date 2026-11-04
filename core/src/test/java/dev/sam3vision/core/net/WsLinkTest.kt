package dev.sam3vision.core.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Machine à états du lien, sans socket ni minuteur : horloge et aléa injectés, donc les
 * délais se prouvent en comparant des nombres, jamais en attendant.
 */
class WsLinkTest {

    private var now = 1_000L

    private fun link(
        random: () -> Double = { 0.5 },
        budget: LinkBudget = LinkBudget(),
    ) = WsLink(budget = budget, clock = { now }, random = random)

    @Test
    fun `le backoff double puis plafonne`() {
        val link = link()
        val delays = (1..7).map {
            now += 10_000 // toujours au-delà de la fenêtre armée précédente
            link.onFailure() - now
        }

        // 1ʳᵉ reprise immédiate (l'appelant n'a pas encore eu sa chance), puis 500, 1 000…
        assertEquals(listOf(0L, 500L, 1_000L, 2_000L, 4_000L, 8_000L, 8_000L), delays)
    }

    @Test
    fun `le jitter reste borne par le plafond`() {
        val budget = LinkBudget(baseDelayMs = 500, maxDelayMs = 8_000, jitterRatio = 0.25)
        val bas = link(random = { 0.0 }, budget = budget)
        val haut = link(random = { 1.0 }, budget = budget)

        repeat(6) { bas.onFailure() }
        repeat(6) { haut.onFailure() }

        assertEquals("−25 % du plafond", 6_000L, bas.nextAttempt - now)
        assertEquals("le plafond est une borne dure", 8_000L, haut.nextAttempt - now)
    }

    @Test
    fun `aucune tentative pendant la fenetre de backoff`() {
        val link = link()
        link.onFailure()
        link.onFailure()

        assertFalse("le backoff court", link.canAttempt())
        assertEquals(500L, link.waitMs())

        now += 500
        assertTrue("la fenêtre est écoulée", link.canAttempt())
        assertEquals(0L, link.waitMs())
    }

    @Test
    fun `un 101 ne remet pas le backoff, seule une reponse oui`() {
        val link = link()
        repeat(4) {
            now += 10_000
            link.onFailure()
        }
        assertEquals(4, link.attemptCount)
        assertEquals(WsLinkState.Degraded, link.current)

        // Un `101` tout seul ne prouve pas que le serveur répond (conteneur
        // accepté-mais-muet) : le remettre à zéro ici, c'était la boucle de
        // reconnexion à période fixe (chaque `101` retombait au délai nul).
        link.onOpen("wifi")

        assertEquals(WsLinkState.Open, link.current)
        assertEquals("le compteur survit au 101", 4, link.attemptCount)
        assertFalse("le backoff court toujours", link.canAttempt())
        assertEquals("wifi", link.snapshot().iface)

        link.markProof()

        assertEquals(0, link.attemptCount)
        assertTrue("reprise immédiate après une vraie réponse", link.canAttempt())
        // La coupure suivante repart d'un premier délai nul, pas du plafond atteint.
        now += 1
        assertEquals(0L, link.onFailure() - now)
    }

    @Test
    fun `le silence tolere ne descend pas sous le budget de la frame`() {
        val link = link(budget = LinkBudget(silenceMarginMs = 5_000))

        // Deux intervalles de ping + marge : 65 s, au-dessus du budget chaud de 5 s.
        assertEquals(65_000L, link.silenceBudgetMs(frameTimeoutMs = 5_000))
        // Une réponse lente (réveil du conteneur) reste légitime : le budget froid gagne.
        assertEquals(70_000L, link.silenceBudgetMs(frameTimeoutMs = 70_000))
        // Sans keepalive, seule la frame compte.
        assertEquals(60_000L, link.silenceBudgetMs(frameTimeoutMs = 60_000, pingIntervalMs = null))
    }

    @Test
    fun `le silence ecoule se mesure depuis la derniere preuve de vie`() {
        val link = link()
        now += 4_000
        assertEquals(4_000L, link.silentForMs())

        link.markProof()

        assertEquals("remis a zero par une reponse", 0L, link.silentForMs())
    }

    @Test
    fun `l interface changee est detectee, une inconnue ne compte pas`() {
        val link = link()

        assertFalse("aucun socket ouvert", link.ifaceChanged("wifi"))
        link.onOpen("wifi")
        assertFalse("meme interface", link.ifaceChanged("wifi"))
        assertTrue("bascule wifi → cellulaire", link.ifaceChanged("cell"))
        assertFalse("interface inconnue : on ne jette rien", link.ifaceChanged(null))
    }

    @Test
    fun `l invalidation rend la main tout de suite`() {
        val link = link()
        now += 10_000
        link.onFailure()
        link.onFailure()
        assertFalse(link.canAttempt())

        link.onInvalidate("cell")

        assertTrue("reprise immédiate", link.canAttempt())
        assertEquals(WsLinkState.Degraded, link.current)
        assertEquals("cell", link.snapshot().iface)
    }

    @Test
    fun `le hors ligne n echauffe pas le compteur de tentatives`() {
        val link = link()
        link.onOffline()

        assertEquals(0, link.attemptCount)
        assertEquals(WsLinkState.Degraded, link.current)
        assertTrue(link.canAttempt())
    }

    @Test
    fun `close remet l etat a zero`() {
        val link = link()
        link.onOpen("wifi")
        now += 10_000
        link.onFailure()
        link.onFailure()

        link.reset()

        assertEquals(WsLinkState.Closed, link.current)
        assertEquals(0, link.attemptCount)
        assertEquals(null, link.snapshot().iface)
        assertTrue(link.canAttempt())
    }

    @Test
    fun `le handshake se mesure de dial a 101`() {
        val link = link()
        assertEquals(null, link.snapshot().lastHandshakeMs)

        link.startConnecting()
        now += 230
        link.onOpen("wifi")

        assertEquals(230L, link.snapshot().lastHandshakeMs)
    }

    @Test
    fun `le palier muet ralentit encore apres 8 echecs sans reponse`() {
        val link = link()
        val delays = (1..10).map {
            now += 70_000 // toujours au-delà de la fenêtre armée précédente
            link.onFailure() - now
        }

        assertEquals(
            "palier normal puis palier muet (32 s, 60 s plafonné)",
            listOf(0L, 500L, 1_000L, 2_000L, 4_000L, 8_000L, 8_000L, 32_000L, 60_000L, 60_000L),
            delays,
        )
    }

    @Test
    fun `panne muette a 8, sortie a la premiere reponse`() {
        val link = link()
        assertFalse(link.muteAccepted)

        repeat(7) {
            now += 10_000
            link.onFailure()
        }
        assertFalse("7 échecs : palier normal", link.muteAccepted)

        now += 10_000
        link.onFailure()
        assertTrue("8 échecs sans réponse : panne muette", link.muteAccepted)

        link.markProof()
        assertFalse(link.muteAccepted)
        assertEquals(0, link.attemptCount)
    }

    @Test
    fun `la sonde ne part qu en panne muette et fenetre ecoulee`() = runBlocking {
        val link = link()
        val sick = RecordingLiveClient(healthAnswer = { Result.failure(ApiError.Network("sonde malade")) })

        assertTrue("hors panne : rien fait, la frame part", link.mutedProbe(sick))
        assertEquals("aucune sonde hors panne", 0, sick.healthCalls)

        repeat(8) {
            now += 70_000
            link.onFailure()
        }
        now += 40_000 // au-delà de la fenêtre muette de 32 s
        assertTrue("fenêtre écoulée : sonde envoyée", !link.mutedProbe(sick))
        assertEquals(1, sick.healthCalls)
        assertEquals("échec de sonde : compteur incrémenté", 9, link.attemptCount)

        assertFalse("fenêtre pas écoulée : refus sans sonde", link.mutedProbe(sick))
        assertEquals(1, sick.healthCalls)

        val healthy = RecordingLiveClient()
        now += 70_000
        assertTrue("sonde saine : la frame part", link.mutedProbe(healthy))
        assertEquals(0, link.attemptCount)
        assertFalse(link.muteAccepted)
    }

    @Test
    fun `le dernier code observe survit aux pannes sans code`() {
        val link = link()
        assertEquals(null, link.snapshot().lastCloseCode)

        link.onFailure(1009)
        assertEquals(1009, link.snapshot().lastCloseCode)
        link.onFailure()
        assertEquals("silence : le précédent survit", 1009, link.snapshot().lastCloseCode)
        link.onInvalidate("cell", 1001)
        assertEquals("un idle reste un code observé", 1001, link.snapshot().lastCloseCode)

        link.reset()
        assertEquals(null, link.snapshot().lastCloseCode)
        assertEquals(null, link.snapshot().lastHandshakeMs)
    }
}
