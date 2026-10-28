package dev.sam3vision.core.traverse

import dev.sam3vision.core.crossing.CrossingState
import dev.sam3vision.core.crossing.CrossingText
import dev.sam3vision.core.net.ApiError
import dev.sam3vision.core.net.TtsClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ElevenLabs factice : octets ou échec programmés, appels comptés. */
private class FakeTts(private val outcome: Result<ByteArray>) : TtsClient {
    var calls = 0
    var lastText: String? = null

    override suspend fun synthesize(text: String): Result<ByteArray> {
        calls++
        lastText = text
        return outcome
    }
}

/** Lecteur factice : occupation programmable, ordre des événements rejouable. */
private class FakePlayer(
    override var isBusy: Boolean = false,
    private val playOk: Boolean = true,
    private val playFileOk: Boolean = true,
) : TraversePlayer {
    val events = mutableListOf<String>()
    var playedBytes: ByteArray? = null
    var playedPath: String? = null

    override fun stop() {
        events += "stop"
        isBusy = false
    }

    override suspend fun play(bytes: ByteArray): Boolean {
        events += "play"
        playedBytes = bytes
        return playOk
    }

    override suspend fun playFile(path: String): Boolean {
        events += "playFile"
        playedPath = path
        return playFileOk
    }
}

/** TTS système factice : langue effective et rendus programmés. */
private class FakeSystemTts(
    private val language: String = "fr-FR",
    private val failOn: Set<String> = emptySet(),
    private val store: MutableMap<String, String>,
) : TraverseSystemTts {
    var calls = 0

    override suspend fun renderToFile(phrase: String, path: String): Result<String> {
        calls++
        if (phrase in failOn) return Result.failure(RuntimeException("moteur indisponible"))
        store[phrase] = path
        return Result.success(path)
    }

    override fun effectiveLanguage(): String = language
}

/** Cache factice : présence = entrée non vide dans la carte. */
private class FakeStore(private val clips: MutableMap<String, String>) : TraverseClipStore {
    override fun pathFor(phrase: String): String = "/cache/${phrase.hashCode()}.wav"

    override fun clipFor(phrase: String): String? = clips[phrase]?.takeIf { it.isNotEmpty() }
}

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
        assertEquals(CrossingText.phrase(CrossingState.Unknown), TraversePhrases.UNKNOWN)
        assertEquals(
            listOf(TraversePhrases.RED, TraversePhrases.GREEN, TraversePhrases.UNKNOWN),
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
    fun `non urgent sur lecteur occupe - tu sans preempter ni reseau`() = runBlocking {
        val player = FakePlayer(isBusy = true)
        val tts = FakeTts(Result.success(mp3))
        val outcome = voice(tts = tts, player = player).speak(TraversePhrases.GREEN, urgent = false)

        assertEquals(TraverseVoiceOutcome.SKIPPED_BUSY, outcome)
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
    fun `doute sans reseau - clip hors-ligne du troisieme etat`() = runBlocking {
        clips[TraversePhrases.UNKNOWN] = "/cache/doute.wav"
        val tts = FakeTts(Result.failure(ApiError.Network()))
        val outcome = voice(tts = tts).speak(TraversePhrases.UNKNOWN, urgent = false)

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
    fun `premier lancement - les trois phrases fr pre-generees par le systeme`() = runBlocking {
        val systemTts = FakeSystemTts(language = "fr-FR", store = clips)
        val report = voice(systemTts = systemTts).prewarm()

        assertEquals(0, report.ready)
        assertEquals(3, report.generated)
        assertTrue(report.missing.isEmpty())
        assertTrue(report.languageOk)
        assertEquals(3, clips.size)
    }

    @Test
    fun `second lancement - clips presents, aucun rendu systeme`() = runBlocking {
        clips[TraversePhrases.RED] = "/cache/rouge.wav"
        clips[TraversePhrases.GREEN] = "/cache/vert.wav"
        clips[TraversePhrases.UNKNOWN] = "/cache/doute.wav"
        val systemTts = FakeSystemTts(store = clips)
        val report = voice(systemTts = systemTts).prewarm()

        assertEquals(3, report.ready)
        assertEquals(0, report.generated)
        assertEquals(0, systemTts.calls)
        assertTrue(report.languageOk)
    }

    @Test
    fun `moteur systeme non francais - langue signalee`() = runBlocking {
        val report = voice(systemTts = FakeSystemTts(language = "en-US", store = clips)).prewarm()

        assertTrue(!report.languageOk)
        assertEquals(3, report.generated)
    }

    @Test
    fun `rendu systeme impossible - phrase manquante`() = runBlocking {
        val systemTts = FakeSystemTts(failOn = setOf(TraversePhrases.RED), store = clips)
        val report = voice(systemTts = systemTts).prewarm()

        assertEquals(2, report.generated)
        assertEquals(listOf(TraversePhrases.RED), report.missing)
    }
}
