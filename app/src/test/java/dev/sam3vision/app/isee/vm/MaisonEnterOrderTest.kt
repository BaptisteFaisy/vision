package dev.sam3vision.app.isee.vm

import dev.sam3vision.app.isee.IseeUiState
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaisonEnterOrderTest {

    private val spoken = mutableListOf<String>()
    private var opened = 0
    private var state = IseeUiState()

    private fun order(language: SpeechLanguage = SpeechLanguage.Fr) = MaisonEnterOrder(
        settings = { Settings.Default.copy(speechLanguage = language) },
        speak = { spoken += it },
        update = { fn -> state = fn(state) },
        openLiveHome = { opened++ },
    )

    @Test
    fun `maison ouvre le direct maison et confirme a voix haute`() = runBlocking {
        assertTrue(order().run("maison"))
        assertEquals(1, opened)
        assertEquals(listOf("Mode maison activé. Je cherche les objets de l'appartement."), spoken)
        assertEquals("Mode maison activé. Je cherche les objets de l'appartement.", state.status)
    }

    @Test
    fun `house confirme en anglais quand la voix est anglaise`() = runBlocking {
        assertTrue(order(SpeechLanguage.En).run("house"))
        assertEquals(1, opened)
        assertEquals(listOf("Home mode on. Looking for household objects."), spoken)
    }

    @Test
    fun `une phrase n ouvre rien et ne dit rien`() = runBlocking {
        assertFalse(order().run("va à la maison"))
        assertFalse(order().run("quelle heure est-il ?"))
        assertEquals(0, opened)
        assertTrue(spoken.isEmpty())
    }
}
