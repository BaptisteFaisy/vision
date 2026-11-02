package dev.sam3vision.app.isee

import dev.sam3vision.app.PlaybackEnd
import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.safety.CrashGuard
import dev.sam3vision.app.speech.VoicePack
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Puits de lecture qui explose sur commande. Il lève un `Error` (pas une `Exception`) :
 * c'est exactement ce que le `catch (e: Exception)` de `speakGuarded` laissait filer jusqu'au
 * handler non capturé du fil principal — donc à la mort du process (P0-2).
 */
private class FaultySink : ClipSink {
    /** Vrai = chaque tentative de lecture échoue. */
    var faulty = true

    /** Nombre de tentatives de lecture (prouve que le lecteur a bien été appelé). */
    var attempts = 0

    private fun note() {
        attempts += 1
        if (faulty) throw AssertionError("lecteur-ko")
    }

    override suspend fun playFile(file: File): Result<Unit> {
        note()
        return Result.success(Unit)
    }

    override suspend fun playSlice(file: File, sliceMs: Long): Result<Unit> {
        note()
        return Result.success(Unit)
    }

    override suspend fun playQueued(bytes: ByteArray): Result<PlaybackEnd> {
        note()
        return Result.success(PlaybackEnd.Done)
    }

    override fun stop() = Unit
}

/**
 * La parole ne tue plus l'app (P0-2) : le scope du [BusSpeaker] est celui du ViewModel, sans
 * `CoroutineExceptionHandler` — une exception qui s'en échappait tuait le process. Le test
 * passe le scope de `runTest` lui-même (une faute non capturée échouerait donc ici) et
 * vérifie les deux propriétés attendues : rien ne remonte, et le bus est rendu (un niveau
 * supérieur, normalement écrasé, repasse).
 */
class BusSpeakerCrashTest {

    private var savedSpeaker: VoiceBusSpeaker? = null
    private var savedIdle: (() -> Unit)? = null

    @Before
    fun sauverBus() {
        savedSpeaker = VoiceBus.speaker
        savedIdle = VoiceBus.onIdle
        VoiceBus.reset()
        VoiceBus.onIdle = null
    }

    @After
    fun rendreBus() {
        VoiceBus.speaker = savedSpeaker
        VoiceBus.onIdle = savedIdle
        VoiceBus.reset()
    }

    /** Bus réel sur un lecteur JVM (aucun `MediaPlayer` : jamais occupé), clips en dur. */
    private class Rig {
        val dir: File = Files.createTempDirectory("bus-crash").toFile()
        val voiceId = "rachel-test"
        val pack = VoicePack(filesDir = dir, packVoiceId = { voiceId }, bundledClip = { null })
        val sink = FaultySink()
        val player = SpeechPlayer(dir)
        val clips = IseeClipPlayer(pack, { voiceId }, sink)
        val proximity = ProximityVoice(player, { Settings.Default }, userSpeaking = { false })

        init {
            pack.put("guide_left", SpeechLanguage.En, voiceId, "audio".toByteArray())
        }
    }

    @Test
    fun `une faute de parole ne propage pas et rend le bus`() = runTest {
        val rig = Rig()
        VoiceBus.speaker = BusSpeaker(this, rig.player, rig.clips, rig.proximity, sayTimeoutMs = 1_000L)

        VoiceBus.post(OutdoorLevel.HUMAN, "Left.", "guide_left")
        runCurrent()
        assertEquals("le lecteur a bien été appelé", 1, rig.sink.attempts)

        // Le lecteur est réparé : le niveau 4 (FENCE), supérieur au niveau 2 tenu par la
        // parole morte, doit repasser. S'il était encore collé à 2, `post` le jetterait.
        rig.sink.faulty = false
        VoiceBus.post(OutdoorLevel.FENCE, "Fence.", "guide_left")
        runCurrent()
        assertEquals("le bus est rendu après la faute", 2, rig.sink.attempts)
    }

    @Test
    fun `la faute est tracee sans casser le reste du scope`() = runTest {
        val rig = Rig()
        val before = CrashGuard.incidents()
        var frere = 0
        VoiceBus.speaker = BusSpeaker(this, rig.player, rig.clips, rig.proximity, sayTimeoutMs = 1_000L)

        VoiceBus.post(OutdoorLevel.HUMAN, "Left.", "guide_left")
        runCurrent()
        launch { frere += 1 }
        runCurrent()

        assertTrue("un incident est journalisé", CrashGuard.incidents() > before)
        assertEquals("les frères du scope continuent", 1, frere)
    }
}
