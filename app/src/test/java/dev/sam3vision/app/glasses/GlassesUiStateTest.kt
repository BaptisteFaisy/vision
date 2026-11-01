package dev.sam3vision.app.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ce que l'écran « Lunettes » déduit de l'état, et les quatre lignes qu'il affiche. */
class GlassesUiStateTest {

    @Test
    fun `un etat vide ne pretend ni connexion ni appareil`() {
        val state = GlassesUiState()
        assertFalse(state.connected)
        assertFalse(state.canConnect)
        assertFalse(state.hasDevice)
        assertNull(state.message)
    }

    @Test
    fun `on ne connecte que si l enregistrement est fait et hors session`() {
        assertTrue(GlassesUiState(registered = true).canConnect)
        assertFalse(GlassesUiState(registered = false).canConnect)
        assertFalse(GlassesUiState(registered = true, phase = SessionPhase.STARTING).canConnect)
        assertFalse(GlassesUiState(registered = true, phase = SessionPhase.STARTED).canConnect)
        assertTrue(GlassesUiState(registered = true, phase = SessionPhase.STARTED).connected)
        // Session terminée : on peut en rouvrir une (le bouton ne reste pas grisé).
        assertTrue(GlassesUiState(registered = true, phase = SessionPhase.STOPPED).canConnect)
    }

    @Test
    fun `l appareil est la quand un nom est connu`() {
        assertTrue(GlassesUiState(deviceName = "Ray-Ban Meta").hasDevice)
        assertFalse(GlassesUiState(deviceName = null).hasDevice)
    }

    @Test
    fun `la gravite du message suit le message jusqu a l ecran`() {
        val state = GlassesUiState(message = GlassesMessages.photoUndecodable())
        assertEquals(MessageKind.ERROR, state.message?.kind)
        assertTrue(state.message?.text.orEmpty().startsWith("Glasses photo unreadable"))
    }

    @Test
    fun `les six lignes d etat existent toujours`() {
        listOf(GlassesUiState(), ready(), idleStream()).forEach { state ->
            val lines = statusLines(state)
            assertEquals(6, lines.size)
            assertTrue(lines.all { it.isNotBlank() })
        }
    }

    @Test
    fun `la ligne video dit le fichier en cours, sinon rien`() {
        assertEquals("Video: nothing running", statusLines(GlassesUiState())[5])
        val recording = GlassesUiState(recordingName = "isee-glasses-20261029-101500.mp4")
        assertTrue(statusLines(recording)[5].contains("Movies/iSee/isee-glasses-"))
    }

    @Test
    fun `les lignes disent l etat reel en anglais`() {
        val idle = statusLines(GlassesUiState())
        assertEquals("SDK: inactive", idle[0])
        assertEquals("Registration: not registered", idle[1])
        assertEquals("Device: no pair detected · battery unknown · not worn", idle[2])
        assertEquals("Session: stopped · camera not allowed · stream stopped", idle[3])
        assertEquals("Glasses mic: not allowed", idle[4])

        val live = statusLines(ready())
        assertEquals("SDK: ready (dev mode)", live[0])
        assertEquals("Device: Ray-Ban Meta · 62 % · worn", live[2])
        assertEquals("Session: running · camera allowed · stream active", live[3])
        assertEquals("Glasses mic: ready", live[4])
        // Aucun nom d'énumération du SDK ne fuit à l'écran.
        live.forEach { line -> assertFalse(line, line.contains("STARTED")) }
    }

    private fun ready() = GlassesUiState(
        sdkReady = true,
        devMode = true,
        registered = true,
        deviceName = "Ray-Ban Meta",
        battery = 62,
        worn = true,
        phase = SessionPhase.STARTED,
        cameraAllowed = true,
        micAllowed = true,
        streaming = true,
    )

    /** Session vivante mais flux arrêté : micro autorisé, donc « en veille ». */
    private fun idleStream() = GlassesUiState(
        phase = SessionPhase.STARTED,
        micAllowed = true,
    )

    @Test
    fun `la ligne du micro dit qui ecoute`() {
        assertEquals("Glasses mic: not allowed", statusLines(GlassesUiState())[4])
        assertEquals("Glasses mic: standby", statusLines(idleStream())[4])
        assertEquals("Glasses mic: ready", statusLines(ready())[4])
        val active = ready().copy(micActive = true)
        assertEquals("Glasses mic: active", statusLines(active)[4])
    }
}