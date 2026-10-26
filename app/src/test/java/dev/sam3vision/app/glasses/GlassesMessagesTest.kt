package dev.sam3vision.app.glasses

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
        GlassesMessages.cameraPermissionMissing(),
        GlassesMessages.cameraUnavailable("occupée"),
        GlassesMessages.streamRefused("qualité"),
        GlassesMessages.photoRefused("occupée"),
        GlassesMessages.photoUndecodable(),
        GlassesMessages.inputsUnavailable("source"),
        GlassesMessages.noActivity(),
        GlassesMessages.mockUnavailable(),
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
        assertEquals(MessageKind.INFO, GlassesMessages.liveNotOnGlasses().kind)
        assertEquals(MessageKind.INFO, GlassesMessages.registrationStarted().kind)
        assertEquals(MessageKind.INFO, GlassesMessages.mockReady().kind)
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
    fun `le message garde la raison donnee par le SDK`() {
        assertTrue(GlassesMessages.sessionRefused("timeout").text.contains("timeout"))
        assertTrue(GlassesMessages.photoRefused("occupée").text.contains("occupée"))
    }

    @Test
    fun `le refus de camera dit ou cliquer dans l app Meta AI`() {
        val text = GlassesMessages.cameraPermissionMissing().text
        assertTrue(text, text.contains("Always allow"))
        assertTrue(text, text.contains("App connections"))
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
        )
        all.forEach { message ->
            listOf("sk-", "sk_", "ghp_", "Bearer").forEach { secret ->
                assertFalse("${message.text} contient $secret", message.text.contains(secret))
            }
        }
    }
}