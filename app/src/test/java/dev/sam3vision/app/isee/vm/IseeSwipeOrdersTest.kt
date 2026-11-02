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
 * « active le GPS » (+ maintien : l'endroit dicté part en destination) ; vers
 * la droite, une question parlée puis le micro — la chasse part avec l'objet
 * entendu, jamais avec un silence.
 */
class IseeSwipeOrdersTest {

    private val spokenClips = mutableListOf<String>()
    private val modes = mutableListOf<String>()
    private val gpsOrders = mutableListOf<String>()
    private val destinations = mutableListOf<String>()
    private var silences = 0
    private val haptics = mutableListOf<String>()
    private var state = IseeUiState()
    private var voiceOn = false

    /** Micro factice : ce que l'écoute doit rendre, sans Android ni réseau. */
    private class FakeListening(
        var text: String = "",
        var available: Boolean = true,
        /** Rend un dicté blanc (transcription vide réelle) au lieu de `null`. */
        var finishBlank: Boolean = false,
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
            if (text.isBlank() && !finishBlank) return null
            return Dictated(text, ByteArray(0))
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
        runDestination = { destinations += it; true },
        silence = { silences++ },
        hapticStart = { haptics += "start" },
        hapticEnd = { haptics += "end" },
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

    @Test
    fun `le maintien apres slide-bas ouvre le micro destination`() = runTest {
        val listening = FakeListening(text = "gare de Lyon")
        val orders = swipe(listening)

        orders.askDestination()

        assertEquals("micro ouvert une fois", 1, listening.starts)
        assertEquals("voix coupée", 1, silences)
        assertEquals(listOf("start"), haptics)
        assertTrue("écoute tenue", orders.asking())
        assertEquals("aucun clip de question", emptyList<String>(), spokenClips)
        assertTrue("aucun ordre GPS rejoué", gpsOrders.isEmpty())
        orders.sendDestination()
        advanceUntilIdle()
    }

    @Test
    fun `le relachement envoie l'endroit brut en destination`() = runTest {
        val listening = FakeListening(text = "gare de Lyon")
        val orders = swipe(listening)

        orders.askDestination()
        orders.sendDestination()
        advanceUntilIdle()

        assertEquals("transcrit une fois", 1, listening.finishes)
        assertEquals(listOf("gare de Lyon"), destinations)
        assertEquals(listOf("start", "end"), haptics)
        assertFalse("plus d'écoute", orders.asking())
    }

    @Test
    fun `destination blanche, « Say again » et rien n'est envoye`() = runTest {
        val listening = FakeListening(text = "  ", finishBlank = true)
        val orders = swipe(listening)

        orders.askDestination()
        orders.sendDestination()
        advanceUntilIdle()

        assertEquals(IseeStatusText.NOTHING_HEARD, state.status)
        assertEquals(listOf(IseeClipsEn.FIND_NOTHING), spokenClips)
        assertTrue("aucune destination", destinations.isEmpty())
        assertFalse("plus d'écoute", orders.asking())
    }

    @Test
    fun `relachement sans maintien, aucun envoi`() = runTest {
        val listening = FakeListening(text = "gare de Lyon")
        val orders = swipe(listening)

        orders.sendDestination()
        advanceUntilIdle()

        assertEquals("rien de transcrit", 0, listening.finishes)
        assertTrue("aucune destination", destinations.isEmpty())
        assertTrue("aucune haptique", haptics.isEmpty())
    }

    @Test
    fun `destination, micro indisponible, motif pose et pas d'ecoute`() = runTest {
        val listening = FakeListening(available = false)
        val orders = swipe(listening)

        orders.askDestination()
        advanceUntilIdle()

        assertEquals(IseeStatusText.MIC_UNAVAILABLE, state.status)
        assertTrue("aucune destination", destinations.isEmpty())
        assertFalse("plus d'écoute", orders.asking())
    }

    @Test
    fun `l'ecoute destination bloque « Quoi chercher ? »`() = runTest {
        val listening = FakeListening(text = "gare de Lyon")
        val orders = swipe(listening)

        orders.askDestination()
        orders.askToFind()
        advanceUntilIdle()

        assertEquals("micro ouvert une fois (destination)", 1, listening.starts)
        assertEquals("aucune question posée", emptyList<String>(), spokenClips)
        assertTrue("aucune chasse", modes.isEmpty())
        orders.sendDestination()
        advanceUntilIdle()
    }

    @Test
    fun `« Quoi chercher ? » bloque l'ecoute destination`() = runTest {
        val listening = FakeListening(text = "mes clés")
        val orders = swipe(listening)

        orders.askToFind()
        // L'écoute tient déjà le micro : la destination ne l'ouvre pas.
        orders.askDestination()

        assertEquals("micro pas ouvert par la destination", 0, listening.starts)
        assertEquals("pas de coupe intempestive", 0, silences)
        advanceUntilIdle()
    }

    @Test
    fun `annuler le maintien jette sans transcrire`() = runTest {
        val listening = FakeListening(text = "gare de Lyon")
        val orders = swipe(listening)

        orders.askDestination()
        orders.cancelDestination()
        advanceUntilIdle()

        assertEquals("l'enregistrement est jeté", 1, listening.discards)
        assertEquals("rien de transcrit", 0, listening.finishes)
        assertTrue("aucune destination", destinations.isEmpty())
        assertFalse("plus d'écoute", orders.asking())
    }
}
