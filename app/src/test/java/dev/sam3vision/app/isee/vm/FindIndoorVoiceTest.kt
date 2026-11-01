package dev.sam3vision.app.isee.vm

import dev.sam3vision.app.isee.IseeUiState
import dev.sam3vision.core.picto.FindIndoorClips
import dev.sam3vision.core.picto.FindIndoorLevels
import dev.sam3vision.core.picto.PictoPlaceMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FindIndoorVoiceTest {

    private val posted = mutableListOf<Triple<Int, String, String?>>()
    private val found = mutableListOf<String>()
    private var offCalls = 0
    private var state = IseeUiState()

    private fun voice(placeMode: PictoPlaceMode = PictoPlaceMode.DEDANS) = FindIndoorVoice(
        update = { fn -> state = fn(state) },
        onFind = { found += it },
        onFindOff = { offCalls += 1 },
        placeMode = { placeMode },
        post = { level, text, clipId -> posted += Triple(level, text, clipId) },
    )

    @Test
    fun `find my keys dedans chasse et poste le clip`() = runBlocking {
        assertTrue(voice().run("find my keys"))
        assertEquals(listOf("keys"), found)
        assertEquals(
            listOf(Triple(FindIndoorLevels.FIND, "Looking for keys.", "find_start_keys")),
            posted,
        )
        assertEquals("Looking for keys.", state.status)
    }

    @Test
    fun `find francais dedans chasse le concept anglais`() = runBlocking {
        assertTrue(voice().run("find mes chaussettes"))
        assertEquals(listOf("socks"), found)
        assertEquals(
            listOf(Triple(FindIndoorLevels.FIND, "Looking for socks.", "find_start_socks")),
            posted,
        )
    }

    @Test
    fun `find dehors refuse avec message parle sans chasse ni picto`() = runBlocking {
        assertTrue(voice(PictoPlaceMode.DEHORS).run("find my keys"))
        assertTrue(found.isEmpty())
        assertEquals(
            listOf(Triple(FindIndoorLevels.FIND, FindIndoorClips.REFUSED.text, FindIndoorClips.REFUSED.id)),
            posted,
        )
        assertEquals(FindIndoorClips.REFUSED.text, state.status)
    }

    @Test
    fun `find off dedans efface et confirme`() = runBlocking {
        assertTrue(voice().run("find off"))
        assertEquals(1, offCalls)
        assertTrue(found.isEmpty())
        assertEquals(
            listOf(Triple(FindIndoorLevels.FIND, FindIndoorClips.OFF.text, FindIndoorClips.OFF.id)),
            posted,
        )
        assertEquals(FindIndoorClips.OFF.text, state.status)
    }

    @Test
    fun `find off dehors efface aussi`() = runBlocking {
        assertTrue(voice(PictoPlaceMode.DEHORS).run("find off"))
        assertEquals(1, offCalls)
        assertEquals(
            listOf(Triple(FindIndoorLevels.FIND, FindIndoorClips.OFF.text, FindIndoorClips.OFF.id)),
            posted,
        )
    }

    @Test
    fun `find hors des 10 aide courte et liste au statut`() = runBlocking {
        assertTrue(voice().run("find the elephant"))
        assertTrue(found.isEmpty())
        assertEquals(0, offCalls)
        assertEquals(
            listOf(Triple(FindIndoorLevels.FIND, FindIndoorClips.UNKNOWN.text, FindIndoorClips.UNKNOWN.id)),
            posted,
        )
        assertEquals(FindIndoorClips.SUPPORTED, state.status)
    }

    @Test
    fun `le reste ne declenche rien`() = runBlocking {
        val orders = voice()
        assertFalse(orders.run("find"))
        assertFalse(orders.run("I found my keys"))
        assertFalse(orders.run("pourquoi ?"))
        assertFalse(orders.run("où sont mes clés"))
        assertFalse(orders.run(""))
        assertTrue(found.isEmpty())
        assertEquals(0, offCalls)
        assertTrue(posted.isEmpty())
        assertTrue(state.status == null)
    }
}
