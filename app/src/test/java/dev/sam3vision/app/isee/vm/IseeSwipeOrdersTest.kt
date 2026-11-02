package dev.sam3vision.app.isee.vm

import dev.sam3vision.app.isee.IseeUiState
import dev.sam3vision.core.picto.PictoPlaceMode
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.IseeClipsEn
import dev.sam3vision.core.speech.SpeechLanguage
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Les deux balayages de la surface iSee : vers le bas, l'ordre GPS existant
 * « active le GPS » ; vers la droite, une question parlée puis le micro — la
 * chasse part avec l'objet entendu, jamais avec un silence.
 */
class IseeSwipeOrdersTest {

    private val spokenClips = mutableListOf<String>()
    private val modes = mutableListOf<String>()
    private val gpsOrders = mutableListOf<String>()
    private var state = IseeUiState()
    private var voiceOn = false

    /** Micro factice : ce que l'écoute doit rendre, sans Android ni réseau. */
    private class FakeListening(
        var text: String = "",
        var available: Boolean = true,
    ) : FindListening {
        var starts = 0
        var discards = 0
        var finishes = 0

        override fun start(): Boolean {
            starts++
            return available
        }

        override suspend fun finish(settings: Settings): Dictated? {
            finishes++
            return if (text.isBlank()) null else Dictated(text, ByteArray(0))
        }

        override fun discard() {
            discards++
        }
    }

    /** Écoute branchée sur le scope de test : horloge et `delay` partagent le même temps. */
    private fun TestScope.swipe(
        listening: FindListening,
        placeMode: PictoPlaceMode = PictoPlaceMode.DEDANS,
        language: SpeechLanguage = SpeechLanguage.En,
    ) = IseeSwipeOrders(
        scope = this,
        runMode = { modes += it; true },
        runGpsOrder = { gpsOrders += it; true },
        dictation = listening,
        voiceDetected = { voiceOn },
        settings = { Settings.Default.copy(speechLanguage = language) },
        placeMode = { placeMode },
        sayClip = { spokenClips += it },
        update = { fn -> state = fn(state) },
        clock = { testScheduler.currentTime },
    )

    @Test
    fun `le balayage vers le bas rejoue l'ordre GPS existant`() = runTest {
        val orders = swipe(FakeListening())

        orders.gpsOn()
        advanceUntilIdle()

        assertEquals(listOf(IseeSwipeOrders.GPS_ENABLE_ORDER), gpsOrders)
        // L'accusé est le clip de l'ordre vocal (« GPS enabled »), pas un mot du geste.
        assertEquals("aucun clip propre au geste", emptyList<String>(), spokenClips)
    }

    @Test
    fun `dehors, le balayage vers la droite refuse sans ouvrir le micro`() = runTest {
        val listening = FakeListening(text = "mes clés")
        val orders = swipe(listening, placeMode = PictoPlaceMode.DEHORS)

        orders.askToFind()
        advanceUntilIdle()

        assertEquals("refus parlé", listOf(IseeClipsEn.FIND_REFUSED), spokenClips)
        assertEquals("micro intact", 0, listening.starts)
        assertTrue("aucune chasse", modes.isEmpty())
        assertFalse("plus d'écoute", orders.asking())
    }

    @Test
    fun `dedans, la question est dite puis l'objet entendu part en chasse`() = runTest {
        val listening = FakeListening(text = "mes clés")
        val orders = swipe(listening)
        voiceOn = true

        orders.askToFind()
        // Question, garde d'écho, puis un sondage où la parole est là.
        advanceTimeBy(400)
        voiceOn = false
        advanceUntilIdle()

        assertEquals("la question posée", IseeClipsEn.FIND_ASK, spokenClips.first())
        assertEquals("micro ouvert une fois", 1, listening.starts)
        assertEquals("transcrit une fois", 1, listening.finishes)
        assertEquals(listOf("mode find my cles"), modes)
        assertFalse("plus d'écoute", orders.asking())
    }

    @Test
    fun `un silence ne part jamais en chasse`() = runTest {
        val listening = FakeListening(text = "")
        val orders = swipe(listening)

        orders.askToFind()
        advanceUntilIdle()

        assertEquals("l'enregistrement est jeté", 1, listening.discards)
        assertEquals("rien de transcrit", 0, listening.finishes)
        assertTrue("aucune chasse", modes.isEmpty())
        assertEquals(listOf(IseeClipsEn.FIND_ASK, IseeClipsEn.FIND_NOTHING), spokenClips)
    }

    @Test
    fun `micro indisponible, le motif est pose et rien ne part`() = runTest {
        val listening = FakeListening(available = false)
        val orders = swipe(listening)

        orders.askToFind()
        advanceUntilIdle()

        assertEquals(IseeStatusText.MIC_UNAVAILABLE, state.status)
        assertTrue("aucune chasse", modes.isEmpty())
        assertFalse("plus d'écoute", orders.asking())
    }
}
