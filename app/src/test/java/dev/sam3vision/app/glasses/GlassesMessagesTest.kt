package dev.sam3vision.app.glasses

import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.core.types.RegistrationError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Règle de voix : un échec des lunettes ne reste jamais silencieux ni déguisé en conseil.
 * Chaque message d'échec est donc au moins `ERROR`, et aucun ne recopie un secret.
 */
class GlassesMessagesTest {

    /** Tous les messages d'échec, avec la description d'erreur qu'ils reçoivent du SDK. */
    private val failures: List<GlassesMessage> = listOf(
        GlassesMessages.sdkUnavailable("init"),
        GlassesMessages.registrationFailed("refus"),
        GlassesMessages.sessionRefused("timeout"),
        GlassesMessages.sessionInterrupted("lien perdu"),
        GlassesMessages.noLinkedDevice(),
        GlassesMessages.cameraPermissionMissing(),
        GlassesMessages.cameraUnavailable("occupée"),
        GlassesMessages.streamRefused("qualité"),
        GlassesMessages.photoRefused("occupée"),
        GlassesMessages.photoUndecodable(),
        GlassesMessages.inputsUnavailable("source"),
        GlassesMessages.noActivity(),
        GlassesMessages.mockUnavailable(),
        GlassesMessages.mockConnectTimeout(),
    )

    @Test
    fun `tout echec est une erreur et jamais un simple conseil`() {
        failures.forEach { message ->
            assertEquals(message.text, MessageKind.ERROR, message.kind)
            assertTrue(message.text, message.text.isNotBlank())
        }
    }

    @Test
    fun `les degrades et les conseils ne se font pas passer pour des erreurs`() {
        assertEquals(MessageKind.WARNING, GlassesMessages.sessionAlreadyRunning().kind)
        assertEquals(MessageKind.WARNING, GlassesMessages.noCamera().kind)
        assertEquals(MessageKind.WARNING, GlassesMessages.streamError("gel").kind)
        assertEquals(MessageKind.WARNING, GlassesMessages.streamStopped().kind)
        assertEquals(MessageKind.WARNING, GlassesMessages.noMockPress().kind)
        assertEquals(MessageKind.WARNING, GlassesMessages.videoUndecodable().kind)
        assertEquals(MessageKind.INFO, GlassesMessages.liveNotOnGlasses().kind)
        assertEquals(MessageKind.INFO, GlassesMessages.waitingForLink().kind)
        assertEquals(MessageKind.INFO, GlassesMessages.registrationStarted().kind)
        assertEquals(MessageKind.INFO, GlassesMessages.mockReady().kind)
        assertEquals(MessageKind.INFO, GlassesMessages.framesVoiceRoute().kind)
    }

    @Test
    fun `la collecte active annonce la sortie de la voix`() {
        assertTrue(GlassesMessages.framesVoiceRoute().text.contains("glasses"))
        assertTrue(GlassesMessages.framesVoiceRoute().text.contains("speaker"))
    }

    @Test
    fun `deux erreurs d enregistrement sont en fait des etats normaux`() {
        // Le SDK les emet quand l'etat demande est deja le sien : les afficher en rouge
        // serait exactement le faux positif qu'on veut eviter.
        assertFalse(registrationErrorWorthTelling(RegistrationError.ALREADY_REGISTERED))
        assertFalse(registrationErrorWorthTelling(RegistrationError.ALREADY_UNREGISTERED))
        assertTrue(registrationErrorWorthTelling(RegistrationError.META_AI_NOT_INSTALLED))
        assertTrue(registrationErrorWorthTelling(RegistrationError.FAILED_TO_REGISTER))
        assertTrue(registrationErrorWorthTelling(RegistrationError.UNKNOWN))
    }

    @Test
    fun `le lien manquant demande de porter les lunettes, pas de les reappairer`() {
        // Mesure du 27/10 : le SDK rend « Aucun appareil éligible » tant que le lien n'est pas
        // monté. Envoyer l'utilisateur ré-appairer était la fausse piste à supprimer.
        val timeout = GlassesMessages.noLinkedDevice().text
        assertTrue(timeout, timeout.contains("not connected"))
        assertFalse(timeout, timeout.contains("pair"))
        assertFalse(timeout, timeout.contains("register"))
        val refusal = sessionErrorMessage(DeviceSessionError.NO_ELIGIBLE_DEVICE).text
        assertFalse(refusal, refusal.contains("pair them"))
        assertTrue(refusal, refusal.contains("unfold"))
    }

    @Test
    fun `le message garde la raison donnee par le SDK`() {
        assertTrue(GlassesMessages.sessionRefused("timeout").text.contains("timeout"))
        assertTrue(GlassesMessages.photoRefused("occupée").text.contains("occupée"))
    }

    @Test
    fun `le refus de camera dit d abord le bouton, puis le chemin manuel`() {
        // La permission est par paquet et se demande dans Meta AI : d'abord le bouton qui
        // ouvre la page du SDK, et seulement en repli le chemin de la fiche de l'app.
        val text = GlassesMessages.cameraPermissionMissing().text
        assertTrue(text, text.contains("Camera not allowed"))
        assertTrue(text, text.contains("Allow"))
        assertTrue(text, text.contains("developer mode apps"))
    }

    @Test
    fun `aucun message ne recopie un secret ni un jeton`() {
        val all = failures + listOf(
            GlassesMessages.sessionAlreadyRunning(),
            GlassesMessages.noCamera(),
            GlassesMessages.streamError("x"),
            GlassesMessages.streamStopped(),
            GlassesMessages.liveNotOnGlasses(),
            GlassesMessages.registrationStarted(),
            GlassesMessages.mockReady(),
            GlassesMessages.noMockPress(),
            GlassesMessages.framesVoiceRoute(),
            GlassesMessages.videoUndecodable(),
        )
        all.forEach { message ->
            listOf("sk-", "sk_", "ghp_", "Bearer").forEach { secret ->
                assertFalse("${message.text} contient $secret", message.text.contains(secret))
            }
        }
    }
}