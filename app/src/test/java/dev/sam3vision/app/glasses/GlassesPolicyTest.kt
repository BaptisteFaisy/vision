package dev.sam3vision.app.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Politique pure des lunettes : quand une session est joignable, ce que les libellés
 * disent, et pourquoi la photo ne peut pas aboutir. Aucun SDK, aucun Android.
 */
class GlassesPolicyTest {

    private fun state(
        registered: Boolean = true,
        phase: SessionPhase = SessionPhase.IDLE,
        cameraAllowed: Boolean = true,
        streaming: Boolean = true,
    ) = GlassesUiState(
        registered = registered,
        phase = phase,
        cameraAllowed = cameraAllowed,
        streaming = streaming,
    )

    @Test
    fun `une session terminee redevient joignable`() {
        // Régression : STOPPED laissait le bouton grisé pour toujours, sans issue.
        assertTrue(SessionPhase.STOPPED.allowsConnect())
        assertTrue(SessionPhase.STOPPED.isFinished())
        assertTrue(SessionPhase.IDLE.allowsConnect())
        assertFalse(SessionPhase.STARTING.allowsConnect())
        assertFalse(SessionPhase.STARTED.allowsConnect())
        assertFalse(SessionPhase.IDLE.isFinished())
        assertFalse(SessionPhase.STARTED.isFinished())
    }

    @Test
    fun `la ligne d etat parle francais et jamais le nom de l enum`() {
        assertEquals("arrêtée", SessionPhase.IDLE.labelFr())
        assertEquals("démarrage", SessionPhase.STARTING.labelFr())
        assertEquals("en cours", SessionPhase.STARTED.labelFr())
        assertEquals("terminée", SessionPhase.STOPPED.labelFr())
        SessionPhase.entries.forEach { phase ->
            assertFalse(phase.labelFr().contains(phase.name.lowercase()))
        }
    }

    @Test
    fun `le bouton de session dit l etape manquante`() {
        assertEquals("Arrêter la session", connectLabel(state(phase = SessionPhase.STARTED)))
        assertEquals("Session en cours de démarrage…", connectLabel(state(phase = SessionPhase.STARTING)))
        assertEquals("Enregistre l'app d'abord", connectLabel(state(registered = false)))
        assertEquals("Démarrer la session", connectLabel(state()))
    }

    @Test
    fun `la photo explique pourquoi elle est impossible`() {
        assertNull(photoHint(state(phase = SessionPhase.STARTED)))
        assertEquals("Photo : démarre la session d'abord.", photoHint(state()))
        assertEquals(
            "Photo : caméra non autorisée côté app Meta AI.",
            photoHint(state(phase = SessionPhase.STARTED, cameraAllowed = false)),
        )
        assertEquals(
            "Photo : flux caméra inactif.",
            photoHint(state(phase = SessionPhase.STARTED, streaming = false)),
        )
    }

    @Test
    fun `drapeau eteint, la non-collecte est un silence documente`() {
        // Éteinte, la collecte ne lit pas le flux : pas d'analyse, pas de voix,
        // et cette phrase en est la raison écrite.
        val reason = frameCollectionIdleReason(enabled = false, hasConsumer = true)
        assertTrue(reason.orEmpty().contains("désactivée"))
        assertTrue(reason.orEmpty().contains("n'est pas lu"))
    }

    @Test
    fun `sans consommateur, la non-collecte est un silence documente`() {
        val reason = frameCollectionIdleReason(enabled = true, hasConsumer = false)
        assertTrue(reason.orEmpty().contains("sans consommateur"))
        assertTrue(reason.orEmpty().contains("n'est pas lu"))
    }

    @Test
    fun `drapeau allume et consommateur present, la collecte tourne`() {
        assertNull(frameCollectionIdleReason(enabled = true, hasConsumer = true))
    }

    @Test
    fun `la perte d'enregistrement se dit une fois, et pas quand elle est voulue`() {
        // Démarrage : on n'a jamais été enregistré, rien à annoncer.
        assertNull(registrationLostMessage(wasRegistered = false, registered = false, wanted = false))
        // Toujours enregistré : rien non plus.
        assertNull(registrationLostMessage(wasRegistered = true, registered = true, wanted = false))
        // Enregistré puis perdu (mise à jour, réinstallation) : l'écran doit dire pourquoi.
        val lost = registrationLostMessage(wasRegistered = true, registered = false, wanted = false)
        assertEquals(MessageKind.WARNING, lost?.kind)
        assertTrue(lost?.text.orEmpty().contains("Enregistrer auprès de Meta AI"))
        // Désenregistrement demandé par l'utilisateur : pas une perte, on se tait.
        assertNull(registrationLostMessage(wasRegistered = true, registered = false, wanted = true))
    }
}