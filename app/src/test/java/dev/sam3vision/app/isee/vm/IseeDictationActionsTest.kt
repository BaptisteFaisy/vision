package dev.sam3vision.app.isee.vm

import dev.sam3vision.app.isee.IseeUiState
import dev.sam3vision.core.picto.PictoPlaceMode
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Voix de la dictée : « mode find my X » rejoue la recherche dans la chaîne
 * des ordres de mode — tout objet, dedans comme dehors (refus parlé dehors).
 */
class IseeDictationActionsTest {

    private val spoken = mutableListOf<String>()
    private val found = mutableListOf<String>()
    private var state = IseeUiState()

    private fun actions(placeMode: PictoPlaceMode): IseeDictationActions {
        val modes = IseeModeOrders(
            settings = { Settings.Default.copy(speechLanguage = SpeechLanguage.En) },
            speak = { spoken += it },
            update = { fn -> state = fn(state) },
            onFind = { found += it },
            placeMode = { placeMode },
        )
        return IseeDictationActions(
            modes = modes,
            runCrossing = { _ -> },
            onCrossingChanged = {},
            confirmFindStopped = {},
            gpsGo = { _ -> },
            gpsOff = {},
            glasses = {},
        )
    }

    @Test
    fun `onFind rejoue mode find my avec tout objet dedans`() = runBlocking {
        actions(PictoPlaceMode.DEDANS).onFind("mug")
        assertEquals(listOf("mug"), found)
        assertEquals(listOf("Finding mug."), spoken)
        assertEquals("Finding mug.", state.status)
    }

    @Test
    fun `onFind dehors refuse sans chasse`() = runBlocking {
        actions(PictoPlaceMode.DEHORS).onFind("mug")
        assertTrue(found.isEmpty())
        assertEquals(listOf("Indoors only."), spoken)
        assertEquals("Indoors only.", state.status)
    }

    @Test
    fun `onFind nommant un lieu ne bascule pas le mode`() = runBlocking {
        actions(PictoPlaceMode.DEDANS).onFind("indoor plant")
        assertEquals(listOf("indoor plant"), found)
        assertEquals(listOf("Finding indoor plant."), spoken)
    }
}
