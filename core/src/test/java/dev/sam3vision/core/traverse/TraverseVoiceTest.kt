package dev.sam3vision.core.traverse

import dev.sam3vision.core.crossing.CrossingState
import dev.sam3vision.core.crossing.CrossingText
import dev.sam3vision.core.speech.SpeechLanguage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TraverseVoiceTest {

    private val clips = mutableMapOf<String, String>()

    private fun voice(
        player: FakePlayer = FakePlayer(),
        store: TraverseClipStore = FakeStore(clips),
        bundled: TraverseBundled = FakeBundled(store = clips),
        onEvent: (String) -> Unit = {},
    ) = TraverseVoice(player, store, bundled, onEvent)

    /** Remplit le cache des 4 phrases (voix prête, sans préchauffage). */
    private fun ready() {
        clips[TraversePhrases.RED] = "/cache/rouge.mp3"
        clips[TraversePhrases.GREEN] = "/cache/vert.mp3"
        clips[TraversePhrases.SIDEWALK_ACROSS] = "/cache/face.mp3"
        clips[TraversePhrases.ON_CROSSWALK] = "/cache/passage.mp3"
    }

    @Test
    fun `phrases - libelles uniques de crossing, un etat une phrase`() {
        assertEquals(CrossingText.phrase(CrossingState.Red), TraversePhrases.RED)
        assertEquals(CrossingText.phrase(CrossingState.Green), TraversePhrases.GREEN)
        assertEquals("Sidewalk across.", TraversePhrases.SIDEWALK_ACROSS)
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
    fun `clipIdFor - les 4 phrases vers leurs mp3, inconnue nulle`() {
        assertEquals("cross_red", TraversePhrases.clipIdFor(TraversePhrases.RED))
        assertEquals("cross_green", TraversePhrases.clipIdFor(TraversePhrases.GREEN))
        assertEquals("sidewalk_across_short", TraversePhrases.clipIdFor(TraversePhrases.SIDEWALK_ACROSS))
        assertEquals("cross_here", TraversePhrases.clipIdFor(TraversePhrases.ON_CROSSWALK))
        assertNull(TraversePhrases.clipIdFor("Bonjour."))
    }

    @Test
    fun `urgent sur lecteur occupe - preempte puis joue la rouge`() = runBlocking {
        ready()
        val player = FakePlayer(isBusy = true)
        val outcome = voice(player = player).speak(TraversePhrases.RED, urgent = true)

        assertEquals(TraverseVoiceOutcome.OFFLINE, outcome)
        assertEquals(listOf("stop", "playFile"), player.events)
        assertEquals("/cache/rouge.mp3", player.playedPath)
    }

    @Test
    fun `urgent - jamais jete pour lecteur occupe, stop avant lecture`() = runBlocking {
        ready()
        val player = FakePlayer(isBusy = true)
        val outcome = voice(player = player).speak(TraversePhrases.RED, urgent = true)

        assertTrue(outcome != TraverseVoiceOutcome.SKIPPED_BUSY)
        assertTrue(player.events.indexOf("stop") < player.events.indexOf("playFile"))
    }

    @Test
    fun `non urgent sur lecteur occupe - garde sans preempter ni reseau`() = runBlocking {
        val player = FakePlayer(isBusy = true)
        val outcome = voice(player = player).speak(TraversePhrases.GREEN, urgent = false)

        assertEquals(TraverseVoiceOutcome.QUEUED, outcome)
        assertTrue(player.events.isEmpty())
    }

    @Test
    fun `non urgent sur lecteur libre - clip local`() = runBlocking {
        ready()
        val outcome = voice().speak(TraversePhrases.GREEN, urgent = false)

        assertEquals(TraverseVoiceOutcome.OFFLINE, outcome)
    }

    @Test
    fun `ni clip ni reseau - indisponible meme en urgent`() = runBlocking {
        val outcome = voice().speak(TraversePhrases.RED, urgent = true)

        assertEquals(TraverseVoiceOutcome.UNAVAILABLE, outcome)
    }

    @Test
    fun `texte vide - indisponible sans lecture ni preemption`() = runBlocking {
        ready()
        val player = FakePlayer(isBusy = true)
        val outcome = voice(player = player).speak("  ", urgent = true)

        assertEquals(TraverseVoiceOutcome.UNAVAILABLE, outcome)
        assertTrue(player.events.isEmpty())
    }

    @Test
    fun `clip hors-ligne injouable - indisponible`() = runBlocking {
        ready()
        val player = FakePlayer(playFileOk = false)
        val outcome = voice(player = player).speak(TraversePhrases.GREEN, urgent = false)

        assertEquals(TraverseVoiceOutcome.UNAVAILABLE, outcome)
        assertEquals(listOf("playFile"), player.events)
    }

    @Test
    fun `premier lancement - les quatre phrases copiees depuis l embarque`() = runBlocking {
        val bundled = FakeBundled(store = clips)
        val report = voice(bundled = bundled).prewarm()

        assertEquals(0, report.ready)
        assertEquals(4, report.generated)
        assertTrue(report.missing.isEmpty())
        assertEquals(4, clips.size)
        assertEquals(4, bundled.calls)
    }

    @Test
    fun `second lancement - clips presents, aucune copie`() = runBlocking {
        ready()
        val bundled = FakeBundled(store = clips)
        val report = voice(bundled = bundled).prewarm()

        assertEquals(4, report.ready)
        assertEquals(0, report.generated)
        assertEquals(0, bundled.calls)
    }

    @Test
    fun `copie impossible - phrase manquante`() = runBlocking {
        val bundled = FakeBundled(failOn = setOf(TraversePhrases.RED), store = clips)
        val report = voice(bundled = bundled).prewarm()

        assertEquals(3, report.generated)
        assertEquals(listOf(TraversePhrases.RED), report.missing)
    }

    @Test
    fun `file - le doublon ne se garde pas deux fois`() = runBlocking {
        ready()
        val player = FakePlayer(isBusy = true)
        val v = voice(player = player)

        assertEquals(TraverseVoiceOutcome.QUEUED, v.speak(TraversePhrases.GREEN, urgent = false))
        assertEquals(TraverseVoiceOutcome.SKIPPED_BUSY, v.speak(TraversePhrases.GREEN, urgent = false))
        player.isBusy = false
        assertEquals(TraverseVoiceOutcome.OFFLINE, v.speak(TraversePhrases.GREEN, urgent = false))

        assertEquals(listOf("playFile"), player.events)
    }

    @Test
    fun `file - la gardee se dit au silence avant la courante`() = runBlocking {
        ready()
        val player = FakePlayer(isBusy = true)
        val v = voice(player = player)

        assertEquals(TraverseVoiceOutcome.QUEUED, v.speak(TraversePhrases.GREEN, urgent = false))
        player.isBusy = false
        val outcome = v.speak(TraversePhrases.RED, urgent = false)

        assertEquals(TraverseVoiceOutcome.OFFLINE, outcome)
        assertEquals(listOf("playFile", "playFile"), player.events)
    }

    @Test
    fun `file - la plus fraiche seule, l ancienne est remplacee`() = runBlocking {
        ready()
        val player = FakePlayer(isBusy = true)
        val v = voice(player = player)

        assertEquals(TraverseVoiceOutcome.QUEUED, v.speak(TraversePhrases.GREEN, urgent = false))
        assertEquals(TraverseVoiceOutcome.QUEUED, v.speak(TraversePhrases.ON_CROSSWALK, urgent = false))
        player.isBusy = false
        assertEquals(TraverseVoiceOutcome.OFFLINE, v.speak(TraversePhrases.ON_CROSSWALK, urgent = false))

        assertEquals(listOf("playFile"), player.events)
    }

    @Test
    fun `urgent - la file est jetee, jamais de traversez apres coup`() = runBlocking {
        ready()
        val player = FakePlayer(isBusy = true)
        val v = voice(player = player)

        assertEquals(TraverseVoiceOutcome.QUEUED, v.speak(TraversePhrases.GREEN, urgent = false))
        assertEquals(TraverseVoiceOutcome.OFFLINE, v.speak(TraversePhrases.RED, urgent = true))
        player.isBusy = false
        assertEquals(TraverseVoiceOutcome.OFFLINE, v.speak(TraversePhrases.RED, urgent = false))

        assertEquals(listOf("stop", "playFile", "playFile"), player.events)
    }

    @Test
    fun `prewarm - vide la file d une session precedente`() = runBlocking {
        ready()
        val player = FakePlayer(isBusy = true)
        val v = voice(player = player)

        assertEquals(TraverseVoiceOutcome.QUEUED, v.speak(TraversePhrases.GREEN, urgent = false))
        v.prewarm()
        player.isBusy = false
        assertEquals(TraverseVoiceOutcome.OFFLINE, v.speak(TraversePhrases.RED, urgent = false))

        assertEquals(listOf("playFile"), player.events)
    }

    @Test
    fun `trace - EXPECT puis issue a chaque retour de speak`() = runBlocking {
        ready()
        val events = mutableListOf<String>()
        val v = voice(onEvent = { events += it })

        assertEquals(TraverseVoiceOutcome.OFFLINE, v.speak(TraversePhrases.GREEN, urgent = false))
        assertEquals(TraverseVoiceOutcome.UNAVAILABLE, v.speak("  ", urgent = false))

        assertEquals(listOf("EXPECT", "OFFLINE", "EXPECT", "UNAVAILABLE"), events)
    }

    @Test
    fun `trace - gardee drainee - DRAIN puis issue avant la courante`() = runBlocking {
        ready()
        val events = mutableListOf<String>()
        val player = FakePlayer(isBusy = true)
        val v = voice(player = player, onEvent = { events += it })

        assertEquals(TraverseVoiceOutcome.QUEUED, v.speak(TraversePhrases.GREEN, urgent = false))
        player.isBusy = false
        assertEquals(TraverseVoiceOutcome.OFFLINE, v.speak(TraversePhrases.RED, urgent = false))

        assertEquals(listOf("EXPECT", "QUEUED", "EXPECT", "DRAIN", "OFFLINE", "OFFLINE"), events)
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
