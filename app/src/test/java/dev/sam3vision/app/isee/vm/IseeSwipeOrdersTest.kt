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
 * Le balayage vers le bas de la surface iSee : l'ordre GPS existant
 * « active le GPS » (+ maintien : l'endroit dicté part en destination) ; et
 * le flow autonome « Quoi chercher ? » : une question parlée puis le micro —
 * la chasse part avec l'objet entendu, jamais avec un silence.
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
        active: Boolean = true,
        logs: MutableList<String> = mutableListOf(),
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
        isActive = { active },
        log = { logs += it },
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
    fun `dehors, « Quoi chercher ? » refuse sans ouvrir le micro`() = runTest {
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
        assertTrue("tenue posée", state.holding)
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
        assertFalse("tenue rendue", state.holding)
        assertFalse("plus d'écoute", orders.asking())
    }

    @Test
    fun `hors ecran, le maintien n'ouvre rien`() = runTest {
        val listening = FakeListening(text = "gare de Lyon")
        val orders = swipe(listening, active = false)

        orders.askDestination()
        advanceUntilIdle()

        assertEquals("micro intact", 0, listening.starts)
        assertEquals("voix intacte", 0, silences)
        assertTrue("aucune haptique", haptics.isEmpty())
        assertFalse("pas de tenue", state.holding)
        assertFalse("pas d'écoute", orders.asking())
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
        assertEquals("voix intacte", 0, silences)
        assertTrue("aucune haptique", haptics.isEmpty())
        assertFalse("pas de tenue", state.holding)
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
        assertFalse("tenue rendue", state.holding)
        assertFalse("plus d'écoute", orders.asking())
    }

    @Test
    fun `hors ecran, le refus destination est loggue`() = runTest {
        val logs = mutableListOf<String>()
        val orders = swipe(FakeListening(text = "gare de Lyon"), active = false, logs = logs)

        orders.askDestination()

        assertEquals(1, logs.size)
        assertTrue("motif inactif", logs.single().contains("inactif"))
    }

    @Test
    fun `« Quoi chercher ? » en cours, le refus destination est loggue`() = runTest {
        val logs = mutableListOf<String>()
        val orders = swipe(FakeListening(text = "mes clés"), logs = logs)

        orders.askToFind()
        orders.askDestination()

        assertEquals(1, logs.size)
        assertTrue("motif micro tenu", logs.single().contains("tient le micro"))
        advanceUntilIdle()
    }

    @Test
    fun `destination deja en cours, le refus est loggue`() = runTest {
        val logs = mutableListOf<String>()
        val orders = swipe(FakeListening(text = "gare de Lyon"), logs = logs)

        orders.askDestination()
        orders.askDestination()

        assertEquals(1, logs.size)
        assertTrue("motif déjà en cours", logs.single().contains("déjà en cours"))
        orders.sendDestination()
        advanceUntilIdle()
    }

    @Test
    fun `micro indisponible, le refus destination est loggue`() = runTest {
        val logs = mutableListOf<String>()
        val orders = swipe(FakeListening(available = false), logs = logs)

        orders.askDestination()
        advanceUntilIdle()

        assertEquals(1, logs.size)
        assertTrue("motif micro", logs.single().contains("micro indisponible"))
    }

    @Test
    fun `ouverture reussie, rien n'est loggue`() = runTest {
        val logs = mutableListOf<String>()
        val orders = swipe(FakeListening(text = "gare de Lyon"), logs = logs)

        orders.askDestination()

        assertTrue("aucun refus", logs.isEmpty())
        orders.sendDestination()
        advanceUntilIdle()
    }
}
