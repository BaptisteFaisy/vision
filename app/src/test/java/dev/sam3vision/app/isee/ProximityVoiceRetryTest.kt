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
 * Phrase avalée par un garde transitoire : non mémorisée, donc redite dès que
 * la voie est libre — seul ce qui a vraiment parlé est dédupliqué.
 */
class ProximityVoiceRetryTest {

    private fun tempDir(): File = Files.createTempDirectory("voixretry").toFile()

    private fun voice(
        spoken: MutableList<String>,
        canPlay: () -> Boolean = { true },
        synthesize: suspend (String, String, String) -> Result<ByteArray> = { _, _, text ->
            Result.success("$text-audio".toByteArray())
        },
    ) = ProximityVoice(
        player = SpeechPlayer(File(tempDir(), "play")),
        settings = { Settings.Default.copy(ttsApiKey = "cle-test", ttsVoiceId = "voix-test") },
        safety = testSafetyVoice(),
        onSpeaking = { spoken += it },
        canPlay = canPlay,
        synthesize = synthesize,
    )

    @Test
    fun `avalee par le chat, dite des que libre`() = runTest {
        val spoken = mutableListOf<String>()
        val voice = voice(spoken)

        voice.onPhrase("Escalier montant.", playerIdle = false)
        assertTrue(spoken.isEmpty())

        voice.onPhrase("Escalier montant.", playerIdle = true)
        assertEquals(1, spoken.count { it == "Escalier montant." })
    }

    @Test
    fun `dite une fois, pas repetee`() = runTest {
        val spoken = mutableListOf<String>()
        val voice = voice(spoken)

        voice.onPhrase("Escalier montant.", playerIdle = true)
        voice.onPhrase("Escalier montant.", playerIdle = true)

        assertEquals(1, spoken.count { it == "Escalier montant." })
    }

    @Test
    fun `synthese en echec, reessayee plus tard`() = runTest {
        val spoken = mutableListOf<String>()
        var fails = true
        val voice = voice(spoken) { _, _, text ->
            if (fails) Result.failure(RuntimeException("reseau"))
            else Result.success("$text-audio".toByteArray())
        }

        voice.onPhrase("Plus que 4 marches.", playerIdle = true)
        assertTrue(spoken.isEmpty())

        fails = false
        voice.onPhrase("Plus que 4 marches.", playerIdle = true)
        assertEquals(1, spoken.count { it == "Plus que 4 marches." })
    }

    @Test
    fun `porte fermee puis ouverte, dite a l ouverture`() = runTest {
        val spoken = mutableListOf<String>()
        var open = false
        val voice = voice(spoken, canPlay = { open })

        voice.onPhrase("Dernière marche.", playerIdle = true)
        assertTrue(spoken.isEmpty())

        open = true
        voice.onPhrase("Dernière marche.", playerIdle = true)
        assertEquals(1, spoken.count { it == "Dernière marche." })
    }
}
