package dev.sam3vision.app.isee.vm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Porte de la voix ambiante d'iSee : GPS actif → ouverte ; ordre « désactive
 * le GPS » → fermée pour le doux (l'urgence passe toujours) ; photo/caméra →
 * fenêtre de parole rouverte ; le réglage rallumé (« active le GPS ») →
 * rouverte. Pur, horloge injectée.
 */
class IseeVoiceGateTest {

    private var now = 0L

    private fun gate() = IseeVoiceGate(clock = { now }, windowMs = 6_000L)

    @Test
    fun `gps actif la porte est ouverte`() {
        val g = gate()

        assertTrue(g.ambientAllowed())
        assertTrue(g.maySpeak(urgent = false, busy = false))
        assertTrue(g.maySpeak(urgent = true, busy = false))
    }

    @Test
    fun `gps desactive la voix douce se tait, l urgence passe`() {
        val g = gate()
        g.muteGps()

        assertFalse(g.ambientAllowed())
        assertFalse(g.maySpeak(urgent = false, busy = false))
        // Urgence mise à part : la sécurité passe avant le silence demandé.
        assertTrue(g.maySpeak(urgent = true, busy = false))
    }

    @Test
    fun `gps desactive l urgence passe occupee ou pas`() {
        val g = gate()
        g.muteGps()

        // L'urgence coupe tout, même occupé : seule la douce respecte la porte.
        assertTrue(g.maySpeak(urgent = true, busy = true))
        assertTrue(g.maySpeak(urgent = true, busy = false))
    }

    @Test
    fun `action de l utilisateur rouvre la parole un temps`() {
        val g = gate()
        g.muteGps()

        // Photo prise : fenêtre ouverte, même la voix de scène passe.
        g.openWindow()
        assertTrue(g.ambientAllowed())
        assertTrue(g.maySpeak(urgent = false, busy = false))
        // Mais une voix déjà en cours n'est jamais sur-écrire le user.
        assertFalse(g.maySpeak(urgent = false, busy = true))

        // La fenêtre expire : retour au silence complet.
        now += 7_000L
        assertFalse(g.ambientAllowed())
        assertFalse(g.maySpeak(urgent = false, busy = false))
    }

    @Test
    fun `reactivation rouvre la porte sans limite de temps`() {
        val g = gate()
        g.muteGps()
        g.openWindow()
        now += 10_000L // la fenêtre a bien expiré
        assertFalse(g.ambientAllowed())

        g.unmuteGps()

        assertTrue(g.ambientAllowed())
        assertTrue(g.maySpeak(urgent = false, busy = false))
    }

    @Test
    fun `mute idempotent et unmute idempotent`() {
        val g = gate()
        g.muteGps()
        g.muteGps()
        assertFalse(g.ambientAllowed())
        g.unmuteGps()
        g.unmuteGps()
        assertTrue(g.ambientAllowed())
    }
}
