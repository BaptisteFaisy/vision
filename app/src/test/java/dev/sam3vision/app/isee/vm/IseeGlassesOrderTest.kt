package dev.sam3vision.app.isee.vm

import dev.sam3vision.app.glasses.GlassesSessionControl
import dev.sam3vision.app.glasses.SessionToggle
import dev.sam3vision.app.isee.IseeUiState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IseeGlassesOrderTest {

    private val spoken = mutableListOf<String>()
    private var toggles = 0
    private var state = IseeUiState()

    private fun order(action: SessionToggle, wired: Boolean = true) = IseeGlassesOrder(
        speak = { spoken += it },
        update = { fn -> state = fn(state) },
        control = {
            if (!wired) return@IseeGlassesOrder null
            GlassesSessionControl { toggles++; action }
        },
    )

    @Test
    fun `isee bascule la session et confirme a voix haute`() = runBlocking {
        assertTrue(order(SessionToggle.START).run("iSee"))
        assertEquals(1, toggles)
        assertEquals(listOf("Glasses on."), spoken)
        assertEquals("Glasses on.", state.status)
    }

    @Test
    fun `arret et refus disent leur propre phrase`() = runBlocking {
        assertTrue(order(SessionToggle.STOP).run("iSee"))
        assertTrue(order(SessionToggle.NOT_LINKED).run("iSee"))
        assertEquals(
            listOf("Glasses off.", "No glasses."),
            spoken,
        )
    }

    @Test
    fun `une question ordinaire ne bascule rien`() = runBlocking {
        assertFalse(order(SessionToggle.START).run("qu'est-ce qu'il y a ici ?"))
        assertFalse(order(SessionToggle.START).run("demande à iSee ce qu'il voit"))
        assertEquals(0, toggles)
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun `non cable, le texte poursuit son chemin`() = runBlocking {
        assertFalse(order(SessionToggle.START, wired = false).run("iSee"))
        assertEquals(0, toggles)
        assertTrue(spoken.isEmpty())
    }
}
