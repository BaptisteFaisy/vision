package dev.sam3vision.core.traverse

import dev.sam3vision.core.crossing.CrossingState
import dev.sam3vision.core.crossing.CrossingText
import dev.sam3vision.core.net.ApiError
import dev.sam3vision.core.net.TtsClient
import dev.sam3vision.core.speech.SpeechLanguage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TraverseVoiceTest {

    private val mp3 = ByteArray(64) { 0xFF.toByte() }
    private val clips = mutableMapOf<String, String>()

    private fun voice(
        tts: TtsClient = FakeTts(Result.success(mp3)),
        player: FakePlayer = FakePlayer(),
        systemTts: TraverseSystemTts = FakeSystemTts(store = clips),
        store: TraverseClipStore = FakeStore(clips),
    ) = TraverseVoice(tts, player, systemTts, store)

    @Test
    fun `phrases - libelles uniques de crossing, un etat une phrase`() {
        assertEquals(CrossingText.phrase(CrossingState.Red), TraversePhrases.RED)
        assertEquals(CrossingText.phrase(CrossingState.Green), TraversePhrases.GREEN)
        assertEquals("Sidewalk across the street.", TraversePhrases.SIDEWALK_ACROSS)
        assertEquals("Here.", TraversePhrases.ON_CROSSWALK)
        assertEquals(
            listOf(
                TraversePhrases.RED, TraversePhrases.GREEN,
                TraversePhrases.SIDEWALK_ACROSS, TraversePhrases.ON_CROSSWALK,
            ),
            TraversePhrases.ALL,
        )
    }

    @Test
    fun `urgent sur lecteur occupe - preempte puis joue la rouge`() = runBlocking {
        val player = FakePlayer(isBusy = true)
        val outcome = voice(player = player).speak(TraversePhrases.RED, urgent = true)

        assertEquals(TraverseVoiceOutcome.PRIMARY, outcome)
        assertEquals(listOf("stop", "play"), player.events)
        assertTrue(player.playedBytes!!.contentEquals(mp3))
    }

    @Test
    fun `urgent - jamais jete pour lecteur occupe, stop avant lecture`() = runBlocking {
        val player = FakePlayer(isBusy = true)
        val tts = FakeTts(Result.success(mp3))
        val outcome = voice(tts = tts, player = player).speak(TraversePhrases.RED, urgent = true)

        assertTrue(outcome != TraverseVoiceOutcome.SKIPPED_BUSY)
        assertTrue(player.events.indexOf("stop") < player.events.indexOf("play"))
        assertEquals(TraversePhrases.RED, tts.lastText)
    }

    @Test
    fun `non urgent sur lecteur occupe - garde sans preempter ni reseau`() = runBlocking {
        val player = FakePlayer(isBusy = true)
        val tts = FakeTts(Result.success(mp3))
        val outcome = voice(tts = tts, player = player).speak(TraversePhrases.GREEN, urgent = false)

        assertEquals(TraverseVoiceOutcome.QUEUED, outcome)
        assertTrue(player.events.isEmpty())
        assertEquals(0, tts.calls)
    }

    @Test
    fun `non urgent sur lecteur libre - elevenlabs`() = runBlocking {
        val outcome = voice().speak(TraversePhrases.GREEN, urgent = false)

        assertEquals(TraverseVoiceOutcome.PRIMARY, outcome)
    }

    @Test
    fun `elevenlabs en echec - repli automatique sur le clip hors-ligne`() = runBlocking {
        clips[TraversePhrases.RED] = "/cache/rouge.wav"
        val player = FakePlayer(isBusy = true)
        val tts = FakeTts(Result.failure(ApiError.PaymentRequired()))
        val outcome = voice(tts = tts, player = player).speak(TraversePhrases.RED, urgent = true)

        assertEquals(TraverseVoiceOutcome.OFFLINE, outcome)
        assertEquals(listOf("stop", "playFile"), player.events)
        assertEquals("/cache/rouge.wav", player.playedPath)
    }

    @Test
    fun `cle elevenlabs absente - le cache prend le relais`() = runBlocking {
        clips[TraversePhrases.GREEN] = "/cache/vert.wav"
        val tts = FakeTts(Result.failure(ApiError.InvalidKey("clé absente")))
        val outcome = voice(tts = tts).speak(TraversePhrases.GREEN, urgent = false)

        assertEquals(TraverseVoiceOutcome.OFFLINE, outcome)
    }

    @Test
    fun `ni reseau ni clip - indisponible meme en urgent`() = runBlocking {
        val tts = FakeTts(Result.failure(ApiError.PaymentRequired()))
        val outcome = voice(tts = tts).speak(TraversePhrases.RED, urgent = true)

        assertEquals(TraverseVoiceOutcome.UNAVAILABLE, outcome)
    }

    @Test
    fun `texte vide - indisponible sans appel reseau ni preemption`() = runBlocking {
        val player = FakePlayer(isBusy = true)
        val tts = FakeTts(Result.success(mp3))
        val outcome = voice(tts = tts, player = player).speak("  ", urgent = true)

        assertEquals(TraverseVoiceOutcome.UNAVAILABLE, outcome)
        assertEquals(0, tts.calls)
        assertTrue(player.events.isEmpty())
    }

    @Test
    fun `lecture primaire ratee - repli sur le clip hors-ligne`() = runBlocking {
        clips[TraversePhrases.GREEN] = "/cache/vert.wav"
        val player = FakePlayer(playOk = false)
        val outcome = voice(player = player).speak(TraversePhrases.GREEN, urgent = false)

        assertEquals(TraverseVoiceOutcome.OFFLINE, outcome)
        assertEquals(listOf("play", "playFile"), player.events)
    }

    @Test
    fun `clip hors-ligne injouable - indisponible`() = runBlocking {
        clips[TraversePhrases.GREEN] = "/cache/vert.wav"
        val player = FakePlayer(playFileOk = false)
        val tts = FakeTts(Result.failure(ApiError.PaymentRequired()))
        val outcome = voice(tts = tts, player = player).speak(TraversePhrases.GREEN, urgent = false)

        assertEquals(TraverseVoiceOutcome.UNAVAILABLE, outcome)
        assertEquals(listOf("playFile"), player.events)
    }

    @Test
    fun `premier lancement - les quatre phrases en pre-generees par le systeme`() = runBlocking {
        val systemTts = FakeSystemTts(language = "en-US", store = clips)
        val report = voice(systemTts = systemTts).prewarm()

        assertEquals(0, report.ready)
        assertEquals(4, report.generated)
        assertTrue(report.missing.isEmpty())
        assertTrue(report.languageOk)
        assertEquals(4, clips.size)
    }

    @Test
    fun `second lancement - clips presents, aucun rendu systeme`() = runBlocking {
        clips[TraversePhrases.RED] = "/cache/rouge.wav"
        clips[TraversePhrases.GREEN] = "/cache/vert.wav"
        clips[TraversePhrases.SIDEWALK_ACROSS] = "/cache/face.wav"
        clips[TraversePhrases.ON_CROSSWALK] = "/cache/passage.wav"
        val systemTts = FakeSystemTts(store = clips)
        val report = voice(systemTts = systemTts).prewarm()

        assertEquals(4, report.ready)
        assertEquals(0, report.generated)
        assertEquals(0, systemTts.calls)
        assertTrue(report.languageOk)
    }

    @Test
    fun `moteur systeme non anglais - langue signalee`() = runBlocking {
        val report = voice(systemTts = FakeSystemTts(language = "fr-FR", store = clips)).prewarm()

        assertTrue(!report.languageOk)
        assertEquals(4, report.generated)
    }

    @Test
    fun `rendu systeme impossible - phrase manquante`() = runBlocking {
        val systemTts = FakeSystemTts(failOn = setOf(TraversePhrases.RED), store = clips)
        val report = voice(systemTts = systemTts).prewarm()

        assertEquals(3, report.generated)
        assertEquals(listOf(TraversePhrases.RED), report.missing)
    }

    @Test
    fun `reponse elevenlabs vide - repli sur le clip hors-ligne`() = runBlocking {
        clips[TraversePhrases.RED] = "/cache/rouge.wav"
        val tts = FakeTts(Result.success(ByteArray(0)))
        val outcome = voice(tts = tts).speak(TraversePhrases.RED, urgent = false)

        assertEquals(TraverseVoiceOutcome.OFFLINE, outcome)
    }

    @Test
    fun `langue systeme - espaces et casse toleres`() = runBlocking {
        val spaced = mutableMapOf<String, String>()
        val okSpaced = voice(systemTts = FakeSystemTts(language = " en ", store = spaced)).prewarm()

        assertTrue(okSpaced.languageOk)
        val upper = mutableMapOf<String, String>()
        val okUpper = voice(systemTts = FakeSystemTts(language = "ENG", store = upper)).prewarm()

        assertTrue(okUpper.languageOk)
    }

    @Test
    fun `file - le doublon ne se garde pas deux fois`() = runBlocking {
        val player = FakePlayer(isBusy = true)
        val v = voice(player = player)

        assertEquals(TraverseVoiceOutcome.QUEUED, v.speak(TraversePhrases.GREEN, urgent = false))
        assertEquals(TraverseVoiceOutcome.SKIPPED_BUSY, v.speak(TraversePhrases.GREEN, urgent = false))
        player.isBusy = false
        assertEquals(TraverseVoiceOutcome.PRIMARY, v.speak(TraversePhrases.GREEN, urgent = false))

        assertEquals(listOf("play"), player.events)
    }

    @Test
    fun `file - la gardee se dit au silence avant la courante`() = runBlocking {
        val player = FakePlayer(isBusy = true)
        val tts = FakeTts(Result.success(mp3))
        val v = voice(tts = tts, player = player)

        assertEquals(TraverseVoiceOutcome.QUEUED, v.speak(TraversePhrases.GREEN, urgent = false))
        player.isBusy = false
        val outcome = v.speak(TraversePhrases.RED, urgent = false)

        assertEquals(TraverseVoiceOutcome.PRIMARY, outcome)
        assertEquals(listOf("play", "play"), player.events)
        assertEquals(2, tts.calls)
        assertEquals(TraversePhrases.RED, tts.lastText)
    }

    @Test
    fun `file - la plus fraiche seule, l ancienne est remplacee`() = runBlocking {
        val player = FakePlayer(isBusy = true)
        val tts = FakeTts(Result.success(mp3))
        val v = voice(tts = tts, player = player)

        assertEquals(TraverseVoiceOutcome.QUEUED, v.speak(TraversePhrases.GREEN, urgent = false))
        assertEquals(TraverseVoiceOutcome.QUEUED, v.speak(TraversePhrases.ON_CROSSWALK, urgent = false))
        player.isBusy = false
        assertEquals(TraverseVoiceOutcome.PRIMARY, v.speak(TraversePhrases.ON_CROSSWALK, urgent = false))

        assertEquals(listOf("play"), player.events)
        assertEquals(1, tts.calls)
        assertEquals(TraversePhrases.ON_CROSSWALK, tts.lastText)
    }

    @Test
    fun `urgent - la file est jetee, jamais de traversez apres coup`() = runBlocking {
        val player = FakePlayer(isBusy = true)
        val v = voice(player = player)

        assertEquals(TraverseVoiceOutcome.QUEUED, v.speak(TraversePhrases.GREEN, urgent = false))
        assertEquals(TraverseVoiceOutcome.PRIMARY, v.speak(TraversePhrases.RED, urgent = true))
        player.isBusy = false
        assertEquals(TraverseVoiceOutcome.PRIMARY, v.speak(TraversePhrases.RED, urgent = false))

        assertEquals(listOf("stop", "play", "play"), player.events)
    }

    @Test
    fun `prewarm - vide la file d une session precedente`() = runBlocking {
        val player = FakePlayer(isBusy = true)
        val v = voice(player = player)

        assertEquals(TraverseVoiceOutcome.QUEUED, v.speak(TraversePhrases.GREEN, urgent = false))
        v.prewarm()
        player.isBusy = false
        assertEquals(TraverseVoiceOutcome.PRIMARY, v.speak(TraversePhrases.RED, urgent = false))

        assertEquals(listOf("play"), player.events)
    }

    @Test
    fun `stationnement - distance mesuree en francais et anglais (source guetteur)`() {
        assertEquals("Voiture en stationnement, à 2 mètres.", TraverseParkedText.parkedPhrase(2f, null, SpeechLanguage.Fr))
        assertEquals("Car parked, 2 meters away.", TraverseParkedText.parkedPhrase(2f, null, SpeechLanguage.En))
    }

    @Test
    fun `stationnement - heure du cadran sans mesure, seule sans geometrie`() {
        assertEquals("Voiture en stationnement, à 9 heures.", TraverseParkedText.parkedPhrase(null, 0.1f, SpeechLanguage.Fr))
        assertEquals("Car parked, at 9 o'clock.", TraverseParkedText.parkedPhrase(null, 0.1f, SpeechLanguage.En))
        assertEquals("Car parked.", TraverseParkedText.parkedPhrase(null, null, SpeechLanguage.En))
    }
}
