package dev.sam3vision.app.live

import dev.sam3vision.app.LiveUiState
import dev.sam3vision.core.live.HomeModes
import dev.sam3vision.core.live.LivePrompts
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ordres vocaux du direct : « maison » commute vers les essentiels.
 * Jamais de LLM sur ces chemins.
 */
class LiveAskVoiceTest {

    @Test
    fun `maison sur rue relance les essentiels et confirme`() = runTest {
        val fakes = pictoFakes()
        fakes.state.value = LiveUiState(prompts = LivePrompts.default, samEnabled = true)
        fakes.session.ask("passe en mode maison")
        advanceUntilIdle()
        assertEquals(listOf(HomeModes.DEFAULT.prompts), fakes.restarts)
        assertEquals(
            listOf("Mode maison."),
            fakes.spoken,
        )
        assertEquals(0, fakes.llm.calls)
    }

    @Test
    fun `maison en maison confirme sans relancer`() = runTest {
        val fakes = pictoFakes()
        fakes.session.ask("maison")
        advanceUntilIdle()
        assertEquals(listOf("Déjà actif."), fakes.spoken)
        assertTrue(fakes.restarts.isEmpty())
        assertEquals(0, fakes.llm.calls)
    }

    @Test
    fun `pngOf rend le PNG armé et null pour un inconnu`() = runTest {
        val fakes = pictoFakes()
        val saved = fakes.store.save("medics", "png-medics".toByteArray(), testTemplate())
        fakes.session.reset()
        advanceUntilIdle()
        assertArrayEquals("png-medics".toByteArray(), fakes.session.pngOf(saved.id))
        assertNull(fakes.session.pngOf("inconnu"))
    }
}
