package dev.sam3vision.app.isee.vm

import dev.sam3vision.app.isee.IseeUiState
import dev.sam3vision.core.picto.PictoPlaceMode
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeFindVoiceTest {

    private val spoken = mutableListOf<String>()
    private val found = mutableListOf<String>()
    private var state = IseeUiState()

    private fun voice(
        placeMode: PictoPlaceMode = PictoPlaceMode.DEDANS,
        language: SpeechLanguage = SpeechLanguage.En,
    ) = ModeFindVoice(
        settings = { Settings.Default.copy(speechLanguage = language) },
        speak = { spoken += it },
        update = { fn -> state = fn(state) },
        onFind = { found += it },
        placeMode = { placeMode },
    )

    @Test
    fun `mode find my mug dedans chasse n importe quel objet`() = runBlocking {
        assertTrue(voice().run("mode find my mug"))
        assertEquals(listOf("mug"), found)
        assertEquals(listOf("Looking for mug."), spoken)
        assertEquals("Looking for mug.", state.status)
    }

    @Test
    fun `mode find my tolere casse ponctuation et please`() = runBlocking {
        assertTrue(voice().run("Mode find my tv remote, please!"))
        assertEquals(listOf("tv remote"), found)
        assertEquals(listOf("Looking for tv remote."), spoken)
    }

    @Test
    fun `mode find my dehors refuse sans chasse`() = runBlocking {
        assertTrue(voice(PictoPlaceMode.DEHORS).run("mode find my mug"))
        assertTrue(found.isEmpty())
        assertEquals(listOf("Find works indoors only."), spoken)
        assertEquals("Find works indoors only.", state.status)
    }

    @Test
    fun `la confirmation parle la langue de la voix`() = runBlocking {
        assertTrue(voice(language = SpeechLanguage.Fr).run("mode find my mug"))
        assertEquals(listOf("Je cherche mug."), spoken)
        assertEquals("Je cherche mug.", state.status)
    }

    @Test
    fun `le refus dehors parle la langue de la voix`() = runBlocking {
        assertTrue(voice(PictoPlaceMode.DEHORS, SpeechLanguage.Fr).run("mode find my mug"))
        assertEquals(listOf("La recherche ne marche que dedans."), spoken)
    }

    @Test
    fun `sans mode ou sans objet rien ne se passe`() = runBlocking {
        val orders = voice()
        assertFalse(orders.run("find my mug"))
        assertFalse(orders.run("mode find my"))
        assertFalse(orders.run("mode find my please"))
        assertFalse(orders.run("create a picto for my mug"))
        assertFalse(orders.run("where are my keys"))
        assertFalse(orders.run("où sont mes clés"))
        assertFalse(orders.run("mode indoor"))
        assertFalse(orders.run(""))
        assertTrue(found.isEmpty())
        assertTrue(spoken.isEmpty())
        assertTrue(state.status == null)
    }
}
