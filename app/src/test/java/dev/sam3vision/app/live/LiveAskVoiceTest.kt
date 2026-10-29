package dev.sam3vision.app.live

import dev.sam3vision.app.LiveUiState
import dev.sam3vision.app.LiveVoiceRequest
import dev.sam3vision.core.live.HomeModes
import dev.sam3vision.core.live.LivePrompts
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ordres vocaux du direct : « maison » commute vers les essentiels, les
 * requêtes venues de l'appui long iSee (chasse, démo, création) s'appliquent
 * au démarrage. Jamais de LLM sur ces chemins.
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
            listOf("Mode maison activé. Je cherche les objets de l'appartement."),
            fakes.spoken,
        )
        assertEquals(0, fakes.llm.calls)
    }

    @Test
    fun `maison en maison confirme sans relancer`() = runTest {
        val fakes = pictoFakes()
        fakes.session.ask("maison")
        advanceUntilIdle()
        assertEquals(listOf("Déjà en mode maison."), fakes.spoken)
        assertTrue(fakes.restarts.isEmpty())
        assertEquals(0, fakes.llm.calls)
    }

    @Test
    fun `requete vocale chasse guide sans passer par ask`() = runTest {
        val fakes = pictoFakes(frame = frameWith(testTemplate()))
        fakes.store.save("medics", "png".toByteArray(), testTemplate())
        fakes.session.reset()
        advanceUntilIdle()
        fakes.session.applyVoiceRequest(LiveVoiceRequest.Request(pictoHunt = "medicament"))
        fakes.session.onLiveFrame(200, 200, 1_000L)
        advanceUntilIdle()
        assertEquals(1, fakes.spoken.size)
        assertTrue(fakes.spoken.single().startsWith("Medics "))
    }

    @Test
    fun `requete vocale demo active`() = runTest {
        val fakes = pictoFakes(jpeg = "")
        fakes.session.applyVoiceRequest(LiveVoiceRequest.Request(pictoDemo = true))
        fakes.session.onLiveFrame(200, 200, 1_000L)
        advanceUntilIdle()
        assertEquals(listOf("Démo picto activée."), fakes.spoken)
        assertEquals(2, fakes.state.value.pictoOverlay?.hits.orEmpty().size)
    }

    @Test
    fun `requete vocale creation attend la frame puis cree`() = runTest {
        val fakes = pictoFakes(frame = frameWith(testTemplate()), jpeg = "")
        fakes.session.applyVoiceRequest(LiveVoiceRequest.Request(pictoCreate = "medics"))
        fakes.session.onLiveFrame(200, 200, 1_000L)
        advanceUntilIdle()
        assertEquals("en attente de frame", 0, fakes.client.calls)
        fakes.setJpeg("jpeg-de-test")
        fakes.session.onLiveFrame(200, 200, 2_000L)
        advanceUntilIdle()
        assertEquals(1, fakes.client.calls)
        assertEquals(listOf("medics"), fakes.store.list().map { it.label })
        assertEquals(
            listOf("Je crée le pictogramme.", "Pictogramme medics prêt. Dis son nom pour être guidé."),
            fakes.spoken,
        )
    }
}
