package dev.sam3vision.app.live

import dev.sam3vision.app.NavOrderRequest
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.voicenav.NavClarifyText
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Le bug terrain : « je veux aller gare Montparnasse » dit sur le direct
 * partait au LLM, qui répondait par une description de la pièce — le direct
 * ne connaissait pas les ordres d'itinéraire, et la transcription mange le
 * « à ». Ces tests verrouillent les deux issues : destination comprise →
 * demande de navigation pour l'accueil ; ordre sans destination → redemande
 * de l'adresse. Aucune des deux ne touche au LLM.
 */
class LiveNavOrdersTest {

    @Before
    fun viderLaDemande() {
        NavOrderRequest.consume()
    }

    @Test
    fun `destination dictee sur le direct part a la navigation, pas au llm`() = runTest {
        val llm = FakeLlm(Result.success("une pièce avec une table"))
        val (session, _) = harness(this, llm)

        session.ask("je veux aller gare Montparnasse")

        assertEquals(0, llm.calls)
        assertEquals("je veux aller gare Montparnasse", NavOrderRequest.consume())
    }

    @Test
    fun `ordre sans destination redemande l adresse`() = runTest {
        val dites = mutableListOf<String>()

        val pris = LiveNavOrders.handle("je veux aller", Settings.Default, this) { _, phrase ->
            dites += phrase
        }
        advanceUntilIdle()

        assertTrue(pris)
        assertNull(NavOrderRequest.consume())
        assertEquals(
            listOf(NavClarifyText.ask(Settings.Default.speechLanguage)),
            dites,
        )
    }

    @Test
    fun `un cap n est pas une destination`() = runTest {
        val pris = LiveNavOrders.handle("va à gauche", Settings.Default, this) { _, _ -> }

        assertFalse(pris)
        assertNull(NavOrderRequest.consume())
    }

    @Test
    fun `une question ordinaire reste au llm`() = runTest {
        val pris = LiveNavOrders.handle("où est la gare Montparnasse ?", Settings.Default, this) { _, _ -> }

        assertFalse(pris)
        assertNull(NavOrderRequest.consume())
    }
}
