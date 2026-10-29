package dev.sam3vision.app.glasses

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La règle de routage de la voix, sans Android : seule la session décide, et elle
 * décide dans les deux sens.
 *
 * C'est ce qui empêche la voix de rester coincée dans les lunettes après leur
 * retrait : la sortie est relue à chaque phrase, mais elle n'est proposée que
 * pendant une session ouverte (`STARTED`). Le reste — quelles sorties Bluetooth
 * existent et laquelle est la paire — se lit sur le téléphone, pas en JVM
 * ([GlassesAudioRouteTest] garde cette décision-là).
 */
class GlassesVoiceRouteTest {

    @After
    fun reset() = GlassesVoiceRoute.forget()

    @Test
    fun `sans session la voix reste au telephone`() {
        GlassesVoiceRoute.follow(GlassesUiState(deviceName = "Ray-Ban Meta"))
        assertFalse(GlassesVoiceRoute.targetsGlasses)
    }

    @Test
    fun `une session ouverte confie la voix aux lunettes`() {
        GlassesVoiceRoute.follow(
            GlassesUiState(deviceName = "Ray-Ban Meta", phase = SessionPhase.STARTED),
        )
        assertTrue(GlassesVoiceRoute.targetsGlasses)
    }

    @Test
    fun `la fin de session rend la voix au telephone`() {
        GlassesVoiceRoute.follow(GlassesUiState(phase = SessionPhase.STARTED))
        assertTrue(GlassesVoiceRoute.targetsGlasses)

        GlassesVoiceRoute.follow(GlassesUiState(phase = SessionPhase.STOPPED))
        assertFalse(GlassesVoiceRoute.targetsGlasses)
    }

    @Test
    fun `une session en cours d ouverture ne prend pas encore la voix`() {
        GlassesVoiceRoute.follow(GlassesUiState(phase = SessionPhase.STARTING))
        assertFalse(GlassesVoiceRoute.targetsGlasses)
    }

    @Test
    fun `forget rend la voix au telephone`() {
        GlassesVoiceRoute.follow(GlassesUiState(phase = SessionPhase.STARTED))
        GlassesVoiceRoute.forget()
        assertFalse(GlassesVoiceRoute.targetsGlasses)
    }
}
