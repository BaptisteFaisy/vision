package dev.sam3vision.app.isee

import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.speech.SystemTtsEngine
import dev.sam3vision.app.speech.TtsDriver
import dev.sam3vision.app.speech.VoicePack
import dev.sam3vision.core.net.ApiError
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Faux pilote TTS système : enregistre les appels, parole programmable. */
private class FakeDriver(var fail: Boolean = false) : TtsDriver {
    data class Call(val text: String, val flush: Boolean)

    val calls = mutableListOf<Call>()
    var speakingValue = false

    override val speaking: Boolean get() = speakingValue

    override fun speak(text: String, flush: Boolean, locale: Locale, rate: Float, pitch: Float): Boolean {
        calls += Call(text, flush)
        return !fail
    }

    override fun stop() = Unit

    override fun shutdown() = Unit
}

/** Dossier temporaire de travail (voix de proximité, niveau fichier : visible du banc `Rig`). */
private fun tempDir(): File = Files.createTempDirectory("voixfallback").toFile()

/**
 * Voix de proximité : repli TTS système sans clé (signal unique, oral seul),
 * urgences via la voix de sécurité (sans synthèse réseau), repli sur
 * 401/402/429, file sans perte (échec gardé), clips sans clé, file 5 s.
 */
class ProximityVoiceTest {

    private class Rig(
        val attempts: MutableList<String> = mutableListOf(),
        val spoken: MutableList<String> = mutableListOf(),
        val safetyDriver: FakeDriver = FakeDriver(),
        val systemDriver: FakeDriver = FakeDriver(),
        var systemBusy: Boolean = false,
        var fails: (String) -> Result<ByteArray> = { text -> Result.success("$text-audio".toByteArray()) },
    ) {
        fun voice(
            key: String = "cle-test",
            language: SpeechLanguage = SpeechLanguage.Fr,
            withSafety: Boolean = true,
            withSystem: Boolean = true,
            nowMs: () -> Long = { 0L },
        ): ProximityVoice {
            val player = SpeechPlayer(File(tempDir(), "play"))
            val settings = { Settings.Default.copy(ttsApiKey = key, speechLanguage = language) }
            val safety = if (withSafety) SafetyVoice(player, SystemTtsEngine(safetyDriver), settings) else null
            val system = if (withSystem) SystemTtsEngine(systemDriver) else null
            return ProximityVoice(
                player = player,
                settings = settings,
                onSpeaking = { if (it.isNotEmpty()) spoken += it },
                synthesize = { _, _, text ->
                    attempts += text
                    fails(text)
                },
                systemBusy = { systemBusy },
                nowMs = nowMs,
                safety = safety,
                system = system,
            )
        }
    }

    @Test
    fun `sans cle, doux parle au systeme avec signal unique`() = runTest {
        val rig = Rig()
        val voice = rig.voice(key = "")

        voice.onPhrase("Personne à 1 mètre.", playerIdle = true)
        voice.onPhrase("Chien à 2 mètres.", playerIdle = true)

        assertTrue(rig.attempts.isEmpty())
        assertTrue(rig.spoken.isEmpty())
        assertEquals(
            listOf(
                FakeDriver.Call("Voix réseau indisponible. Personne à 1 mètre.", false),
                FakeDriver.Call("Chien à 2 mètres.", false),
            ),
            rig.systemDriver.calls,
        )
    }

    @Test
    fun `sans cle, le signal se rearme au reset`() = runTest {
        val rig = Rig()
        val voice = rig.voice(key = "")

        voice.onPhrase("Personne à 1 mètre.", playerIdle = true)
        voice.reset()
        voice.onPhrase("Personne à 1 mètre.", playerIdle = true)

        assertEquals(2, rig.systemDriver.calls.size)
        assertTrue(rig.systemDriver.calls.all { it.text.startsWith("Voix réseau indisponible.") })
    }

    @Test
    fun `sans cle et sans moteur, silence sans appel reseau`() = runTest {
        val rig = Rig()
        val voice = rig.voice(key = "", withSafety = false, withSystem = false)

        voice.onPhrase("Personne à 1 mètre.", playerIdle = true)

        assertTrue(rig.attempts.isEmpty())
        assertTrue(rig.spoken.isEmpty())
    }

    @Test
    fun `urgent via safety, sans synthese ni sous-titre`() = runTest {
        val rig = Rig()
        val voice = rig.voice()

        voice.onPhrase("Stop, car moving.", playerIdle = false, urgent = true)

        assertTrue(rig.attempts.isEmpty())
        assertTrue(rig.spoken.isEmpty())
        assertEquals(listOf(FakeDriver.Call("Stop, car moving.", true)), rig.safetyDriver.calls)
        assertTrue(rig.systemDriver.calls.isEmpty())
    }

    @Test
    fun `mot urgent sans clip via safety`() = runTest {
        val rig = Rig()
        val voice = rig.voice()

        voice.speakWord("guide_stop", "Stop.", SpeechLanguage.Fr, playerIdle = false, urgent = true)

        assertTrue(rig.attempts.isEmpty())
        assertEquals(listOf(FakeDriver.Call("Stop.", true)), rig.safetyDriver.calls)
    }

    @Test
    fun `urgent sans safety garde la synthese`() = runTest {
        val rig = Rig()
        val voice = rig.voice(withSafety = false)

        voice.onPhrase("Stop, car moving.", playerIdle = false, urgent = true)

        assertEquals(listOf("Stop, car moving."), rig.attempts)
        assertEquals(listOf("Stop, car moving."), rig.spoken)
    }

    @Test
    fun `401 402 429 basculent au systeme avec alerte`() = runTest {
        val errors = listOf(
            ApiError.InvalidKey("401"),
            ApiError.PaymentRequired(),
            ApiError.RateLimited(),
        )
        for (error in errors) {
            val rig = Rig()
            rig.fails = { Result.failure(error) }
            val voice = rig.voice()

            voice.onPhrase("Personne à 1 mètre.", playerIdle = true)

            assertEquals(listOf("Personne à 1 mètre."), rig.attempts)
            assertTrue(rig.spoken.isEmpty())
            assertEquals(1, rig.systemDriver.calls.size)
            assertTrue(rig.systemDriver.calls.single().text.startsWith("Voix réseau indisponible."))
        }
    }

    @Test
    fun `erreur transitoire ne bascule pas et se reessaie`() = runTest {
        val rig = Rig()
        var fails = true
        rig.fails = { text ->
            if (fails) Result.failure(ApiError.Network()) else Result.success("$text-audio".toByteArray())
        }
        val voice = rig.voice()

        voice.onPhrase("Personne à 1 mètre.", playerIdle = true)
        assertTrue(rig.spoken.isEmpty())
        assertTrue(rig.systemDriver.calls.isEmpty())

        fails = false
        voice.onPhrase("Personne à 1 mètre.", playerIdle = true)

        assertEquals(listOf("Personne à 1 mètre."), rig.spoken)
    }

    @Test
    fun `flush en echec garde la file pour le prochain silence`() = runTest {
        val rig = Rig()
        var fails = true
        rig.fails = { text ->
            if (fails) Result.failure(ApiError.Network()) else Result.success("$text-audio".toByteArray())
        }
        val voice = rig.voice()

        voice.onPhrase("Attention au poteau.", playerIdle = false, queue = true)
        voice.flushPending(playerIdle = true)
        assertTrue(rig.spoken.isEmpty())

        fails = false
        voice.flushPending(playerIdle = true)

        assertEquals(listOf("Attention au poteau."), rig.spoken)
    }

    @Test
    fun `clips parles sans cle`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val pack = VoicePack(
            filesDir = tempDir(),
            packVoiceId = { Settings.Default.ttsVoiceId },
            bundledClip = { "faux-mp3".toByteArray() },
        )
        val voice = ProximityVoice(
            player = SpeechPlayer(File(tempDir(), "play")),
            settings = { Settings.Default.copy(ttsApiKey = "") },
            onSpeaking = { if (it.isNotEmpty()) spoken += it },
            synthesize = { _, _, text ->
                attempts += text
                Result.success("$text-audio".toByteArray())
            },
            clips = pack,
        )

        voice.speakWord("guide_left", "Gauche.", SpeechLanguage.Fr, playerIdle = true)

        assertTrue(attempts.isEmpty())
        assertEquals(listOf("Gauche."), spoken)
    }

    @Test
    fun `systeme occupe, doux en file puis redit`() = runTest {
        val rig = Rig()
        rig.systemBusy = true
        val voice = rig.voice()

        voice.onPhrase("Attention au poteau.", playerIdle = true, queue = true)
        assertTrue(rig.spoken.isEmpty())
        assertTrue(rig.attempts.isEmpty())

        rig.systemBusy = false
        voice.flushPending(playerIdle = true)

        assertEquals(listOf("Attention au poteau."), rig.spoken)
    }

    @Test
    fun `file courte de 5 secondes, perimee au-dela`() = runTest {
        assertEquals(5_000L, ProximityVoice.PENDING_TTL_MS)
        val rig = Rig()
        var now = 0L
        val voice = rig.voice(nowMs = { now })

        voice.onPhrase("Attention au poteau.", playerIdle = false, queue = true)
        now = ProximityVoice.PENDING_TTL_MS + 1
        voice.flushPending(playerIdle = true)

        assertTrue(rig.spoken.isEmpty())
    }

    @Test
    fun `signal bilingue`() {
        assertEquals("Voix réseau indisponible.", VoiceOfflineFallback.networkVoiceDown(SpeechLanguage.Fr))
        assertEquals("Network voice unavailable.", VoiceOfflineFallback.networkVoiceDown(SpeechLanguage.En))
    }
}
