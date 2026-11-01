package dev.sam3vision.app.isee

import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.core.settings.Settings
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Préemption du mouvement et du compteur de marches : une voiture en
 * mouvement (ou une personne, ou le compte des marches) coupe la parole
 * douce en cours (chat, route, autre annonce) au lieu d'attendre le
 * silence — mais jamais la sécurité (alerte système en cours ou fenêtre
 * P1/P2 : la phrase part en file). Dans la file, les marches gardent
 * leur rang devant la voiture, qui garde le sien devant la personne.
 */
class ProximityVoicePreemptTest {

    private fun tempDir(): File = Files.createTempDirectory("preempt").toFile()

    private fun voice(
        attempts: MutableList<String>,
        spoken: MutableList<String>,
        routeSpeaking: () -> Boolean = { false },
        systemBusy: () -> Boolean = { false },
        driver: SafetyTestDriver = SafetyTestDriver(),
    ) = ProximityVoice(
        player = SpeechPlayer(File(tempDir(), "play")),
        settings = { Settings.Default.copy(ttsApiKey = "cle-test", ttsVoiceId = "voix-test") },
        safety = testSafetyVoice(driver),
        onSpeaking = { if (it.isNotEmpty()) spoken += it },
        synthesize = { _, _, text ->
            attempts += text
            Result.success("$text-audio".toByteArray())
        },
        routeSpeaking = routeSpeaking,
        systemBusy = systemBusy,
    )

    @Test
    fun `preempt parle chat en lecture`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val voice = voice(attempts, spoken)

        voice.onPhrase("Car moving, 12 meters away.", playerIdle = false, preempt = VoicePreempt.VEHICLE)

        assertEquals(listOf("Car moving, 12 meters away."), spoken)
        assertEquals(listOf("Car moving, 12 meters away."), attempts)
    }

    @Test
    fun `preempt parle route en cours`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val voice = voice(attempts, spoken, routeSpeaking = { true })

        voice.onPhrase(
            "Attention à la personne, passez à droite.",
            playerIdle = true,
            preempt = VoicePreempt.PERSON,
        )

        assertEquals(listOf("Attention à la personne, passez à droite."), spoken)
    }

    @Test
    fun `preempt attend la fenetre p1p2 en file`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val voice = voice(attempts, spoken)

        voice.onPhrase(
            "Car moving, 12 meters away.",
            playerIdle = false,
            queue = true,
            busy = { true },
            preempt = VoicePreempt.VEHICLE,
        )
        assertTrue(spoken.isEmpty())

        voice.flushPending(playerIdle = true, busy = { false })

        assertEquals(listOf("Car moving, 12 meters away."), spoken)
    }

    @Test
    fun `preempt attend le tts systeme en file`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        var system = true
        val voice = voice(attempts, spoken, systemBusy = { system })

        voice.onPhrase(
            "Car moving, 12 meters away.",
            playerIdle = true,
            queue = true,
            preempt = VoicePreempt.VEHICLE,
        )
        assertTrue(spoken.isEmpty())

        system = false
        voice.flushPending(playerIdle = true)

        assertEquals(listOf("Car moving, 12 meters away."), spoken)
    }

    @Test
    fun `file voiture gardee devant personne`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val voice = voice(attempts, spoken)

        voice.onPhrase("Car moving, 12 meters away.", playerIdle = true, queue = true, busy = { true }, preempt = VoicePreempt.VEHICLE)
        voice.onPhrase(
            "Attention à la personne, passez à droite.",
            playerIdle = true,
            queue = true,
            busy = { true },
            preempt = VoicePreempt.PERSON,
        )
        voice.flushPending(playerIdle = true, busy = { false })

        assertEquals(listOf("Car moving, 12 meters away."), spoken)
    }

    @Test
    fun `file personne remplace le doux`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val voice = voice(attempts, spoken)

        voice.onPhrase("Attention à la barrière.", playerIdle = true, queue = true, busy = { true })
        voice.onPhrase(
            "Attention à la personne, passez à droite.",
            playerIdle = true,
            queue = true,
            busy = { true },
            preempt = VoicePreempt.PERSON,
        )
        voice.flushPending(playerIdle = true, busy = { false })

        assertEquals(listOf("Attention à la personne, passez à droite."), spoken)
    }

    @Test
    fun `file voiture remplace personne`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val voice = voice(attempts, spoken)

        voice.onPhrase(
            "Attention à la personne, passez à droite.",
            playerIdle = true,
            queue = true,
            busy = { true },
            preempt = VoicePreempt.PERSON,
        )
        voice.onPhrase("Car moving, 12 meters away.", playerIdle = true, queue = true, busy = { true }, preempt = VoicePreempt.VEHICLE)
        voice.flushPending(playerIdle = true, busy = { false })

        assertEquals(listOf("Car moving, 12 meters away."), spoken)
    }

    @Test
    fun `file escaliers remplace voiture`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val voice = voice(attempts, spoken)

        voice.onPhrase("Car moving, 12 meters away.", playerIdle = true, queue = true, busy = { true }, preempt = VoicePreempt.VEHICLE)
        voice.onPhrase("6 marches.", playerIdle = true, queue = true, busy = { true }, preempt = VoicePreempt.STAIRS)
        voice.flushPending(playerIdle = true, busy = { false })

        assertEquals(listOf("6 marches."), spoken)
    }

    @Test
    fun `file voiture ne remplace pas escaliers`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val voice = voice(attempts, spoken)

        voice.onPhrase("6 marches.", playerIdle = true, queue = true, busy = { true }, preempt = VoicePreempt.STAIRS)
        voice.onPhrase("Car moving, 12 meters away.", playerIdle = true, queue = true, busy = { true }, preempt = VoicePreempt.VEHICLE)
        voice.flushPending(playerIdle = true, busy = { false })

        assertEquals(listOf("6 marches."), spoken)
    }

    @Test
    fun `urgent garde la main sur preempt`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val driver = SafetyTestDriver()
        val voice = voice(attempts, spoken, driver = driver)

        voice.onPhrase("Stop, car moving.", playerIdle = false, urgent = true, preempt = VoicePreempt.VEHICLE)

        assertEquals(listOf("Stop, car moving."), driver.calls.map { it.text })
        assertTrue(spoken.isEmpty())
        assertTrue(attempts.isEmpty())
    }
}
