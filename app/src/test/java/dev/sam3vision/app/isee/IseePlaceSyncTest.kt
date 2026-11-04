package dev.sam3vision.app.isee

import dev.sam3vision.core.live.LivePrompts
import dev.sam3vision.core.picto.PictoPlaceMode
import dev.sam3vision.core.settings.Settings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** La puce de lieu et les prompts de la sonde disent toujours le même monde. */
class IseePlaceSyncTest {

    private val dedans = Settings.Default.copy(outdoorMode = false)
    private val dehors = Settings.Default.copy(outdoorMode = true)

    @Test
    fun `un dedans d'avant la relance repart dehors et s'enregistre`() = runBlocking {
        val saved = mutableListOf<Settings>()
        val launched = outdoorsAtLaunch(dedans) { saved += it }
        assertTrue(launched.outdoorMode)
        assertEquals(listOf(launched), saved)
    }

    @Test
    fun `apres la relance la puce et les prompts disent dehors`() = runBlocking {
        val launched = outdoorsAtLaunch(dedans) {}
        assertEquals(IseeUiState().placeMode, placeOf(launched))
        val prompts = LivePrompts.forMode(launched.outdoorMode)
        assertTrue(listOf("pole", "barrier", "sidewalk", "car", "stairs").all { it in prompts })
    }

    @Test
    fun `deja dehors rien a enregistrer`() = runBlocking {
        val saved = mutableListOf<Settings>()
        assertSame(dehors, outdoorsAtLaunch(dehors) { saved += it })
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `un echec d'ecriture ne retient pas le lancement`() = runBlocking {
        val launched = outdoorsAtLaunch(dedans) { error("disque plein") }
        assertTrue(launched.outdoorMode)
    }

    @Test
    fun `le lieu suit le monde sonde`() {
        assertEquals(PictoPlaceMode.DEDANS, placeOf(dedans))
        assertEquals(PictoPlaceMode.DEHORS, placeOf(dehors))
    }
}
