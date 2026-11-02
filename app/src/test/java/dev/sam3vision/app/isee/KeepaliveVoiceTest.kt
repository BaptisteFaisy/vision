package dev.sam3vision.app.isee

import dev.sam3vision.core.live.GuidanceWord
import dev.sam3vision.core.picto.PictoPlaceMode
import dev.sam3vision.core.speech.SpeechLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Porte-parole faux : niveau, texte et clip enregistrés. */
private class KeepaliveFakeSpeaker : VoiceBusSpeaker {
    val said = mutableListOf<Triple<Int, String, String?>>()

    override fun idle(): Boolean = true

    override fun say(level: Int, text: String, clipId: String?, clips: List<String>) {
        said += Triple(level, text, clipId)
    }

    override fun cut() = Unit
}

/**
 * Keepalive de positionnement (PUSH) : silencieux sous bavardage, mot courant
 * (dernier état connu, tout-droit à défaut) niveau 12 en clips après 3,5 s de
 * silence bus ; jamais de stop, jamais de coupe, jet bus = retenté sans
 * timbrer ; la boucle ne parle qu'en session active dehors hors Traversée et
 * hors parole user.
 */
class KeepaliveVoiceTest {

    private val speaker = KeepaliveFakeSpeaker()
    private val defaultSink = SpeechLog.sink
    private val journal = mutableListOf<Pair<String, String>>()

    @Before
    fun brancher() {
        KeepaliveVoice.reset()
        VoiceBus.reset()
        VoiceBus.speaker = speaker
        SpeechLog.sink = { tag, line -> journal += tag to line }
    }

    @After
    fun debrancher() {
        SpeechLog.sink = defaultSink
        VoiceBus.speaker = null
        VoiceBus.reset()
        KeepaliveVoice.reset()
    }

    @Test
    fun `silence requis 3,5 s, epingle`() {
        assertEquals(3_500L, KeepaliveVoice.SILENCE_MS)
    }

    @Test
    fun `bavardage = silence (sobre, jamais de spam)`() {
        VoiceBus.post(OutdoorLevel.HUMAN, "Personne à 3 mètres.")
        assertFalse(KeepaliveVoice.offer(SpeechLanguage.En))
        assertEquals(1, speaker.said.size)
        assertTrue(journal.isEmpty())
    }

    @Test
    fun `silence = tout-droit EN niveau 12 en clip`() {
        assertTrue(KeepaliveVoice.offer(SpeechLanguage.En))
        assertEquals(
            listOf(Triple(OutdoorLevel.FALLBACK, "Straight.", "guide_straight")),
            speaker.said,
        )
        assertEquals(listOf(SpeechLog.TAG to "EXPECT voie=repli phrase=[Straight.]"), journal)
    }

    @Test
    fun `dernier virage connu FR = Gauche en clip`() {
        KeepaliveVoice.report(GuidanceWord.Left)
        assertTrue(KeepaliveVoice.offer(SpeechLanguage.Fr))
        assertEquals(listOf(Triple(OutdoorLevel.FALLBACK, "Gauche.", "guide_left")), speaker.said)
    }

    @Test
    fun `dernier virage connu EN = Right en clip`() {
        KeepaliveVoice.report(GuidanceWord.Right)
        assertTrue(KeepaliveVoice.offer(SpeechLanguage.En))
        assertEquals(listOf(Triple(OutdoorLevel.FALLBACK, "Right.", "guide_right")), speaker.said)
    }

    @Test
    fun `stop signale = tout-droit (positionnement seul, jamais de stop)`() {
        KeepaliveVoice.report(GuidanceWord.Stop)
        assertTrue(KeepaliveVoice.offer(SpeechLanguage.En))
        assertEquals("Straight.", speaker.said.single().second)
    }

    @Test
    fun `deuxieme keepalive avant delai = tu (cadence propre)`() {
        assertTrue(KeepaliveVoice.offer(SpeechLanguage.En))
        VoiceBus.reset()
        VoiceBus.speaker = speaker
        assertFalse(KeepaliveVoice.offer(SpeechLanguage.En))
        assertEquals(1, speaker.said.size)
    }

    @Test
    fun `parole en cours = jete, jamais coupe`() {
        VoiceBus.post(OutdoorLevel.HUMAN, "Personne à 3 mètres.")
        assertFalse(KeepaliveVoice.offer(SpeechLanguage.En, System.currentTimeMillis() + 10_000L))
        assertEquals(1, speaker.said.size)
    }

    @Test
    fun `jet micro-user = retente sans timbrer`() {
        VoiceBus.userSpeaking(true)
        assertFalse(KeepaliveVoice.offer(SpeechLanguage.En))
        VoiceBus.userSpeaking(false)
        assertTrue(KeepaliveVoice.offer(SpeechLanguage.En))
        assertEquals(1, speaker.said.size)
    }

    @Test
    fun `boucle parle en session et en traversee, tait camera coupee`() = runTest {
        val h = demarreBoucle()
        try {
            tick()
            assertEquals(listOf(700L), h.calls)

            // Traversée affamée : la boucle propose (verdict sain toutes
            // les 2 s ⇒ pas de silence 3,5 s — le bus arbitre, sans coupe).
            tick()
            assertEquals(2, h.calls.size)

            // Caméra coupée (session fermée) : silence.
            h.ui.value = h.ui.value.copy(cameraActive = false)
            tick()
            assertEquals(2, h.calls.size)
        } finally {
            h.scope.cancel()
        }
    }

    @Test
    fun `boucle tait dedans et parole user, reparle a la reprise`() = runTest {
        val h = demarreBoucle()
        try {
            // Dedans : silence (chaîne dedans, pas de mots dehors).
            h.lieu = PictoPlaceMode.DEDANS
            tick()
            assertTrue(h.calls.isEmpty())
            h.lieu = PictoPlaceMode.DEHORS

            // Parole du user (micro tenu, transcription) : silence.
            h.ui.value = h.ui.value.copy(holding = true)
            tick()
            assertTrue(h.calls.isEmpty())
            h.ui.value = h.ui.value.copy(holding = false, transcribing = true)
            tick()
            assertTrue(h.calls.isEmpty())

            // Reprise : reparle.
            h.ui.value = h.ui.value.copy(transcribing = false)
            tick()
            assertEquals(1, h.calls.size)
        } finally {
            h.scope.cancel()
        }
    }

    /** Boucle câblée sur un faux état écran : offres horodatées en [BoucleHarnais.calls]. */
    private fun TestScope.demarreBoucle(): BoucleHarnais {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val h = BoucleHarnais(scope, MutableStateFlow(IseeUiState(cameraActive = true)))
        keepaliveJob(
            scope,
            h.ui,
            { SpeechLanguage.En },
            { h.lieu },
            nowMs = { testScheduler.currentTime },
            offer = { _, at -> h.calls += at; true },
        )
        return h
    }

    /** Un battement de 700 ms, exécuté. */
    private fun TestScope.tick() {
        advanceTimeBy(700)
        runCurrent()
    }
}

/** Harnais de la boucle : portée, état écran pilotable, offres reçues. */
private class BoucleHarnais(
    val scope: CoroutineScope,
    val ui: MutableStateFlow<IseeUiState>,
    val calls: MutableList<Long> = mutableListOf(),
    var lieu: PictoPlaceMode = PictoPlaceMode.DEHORS,
)
