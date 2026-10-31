package dev.sam3vision.app.isee.vm

import dev.sam3vision.app.PlaybackEnd
import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.isee.IseeCameraActions
import dev.sam3vision.app.isee.IseePlayback
import dev.sam3vision.app.isee.IseeUiState
import dev.sam3vision.app.media.MediaCacheStore
import dev.sam3vision.app.media.MessageMultimedia
import dev.sam3vision.core.model.ChatTurn
import dev.sam3vision.core.settings.Settings
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

/**
 * Socle des tests d'`IseeAnswers` : état, base factice, média, caméra factice et
 * fabriques partagées. Les suites ([IseeAnswersTest], [IseeAnswersPreemptTest]) n'y
 * ajoutent que leurs tests (garde 500 lignes).
 */
internal open class IseeAnswersTestBase {

    protected val state = MutableStateFlow(IseeUiState())
    protected val dao = FakeMessageDao()
    protected val media = MediaCacheStore(tempDir(), dao)
    protected val transcript = IseeTranscript(dao, ::update)
    protected val settings = Settings.Default.copy(llmApiKey = "test-key")
    protected val camera = FakeCamera()
    protected val shots = photoShot(encode = { null })

    protected fun answers(
        ask: suspend (String, String, List<ChatTurn>) -> Result<String>,
        synthesize: suspend (String) -> ByteArray = { ByteArray(0) },
        photo: IseePhotoShot = shots,
        askTimeoutMs: Long = IseeAnswers.ASK_TIMEOUT_MS,
        destination: String? = null,
        onContext: ((String?) -> Unit)? = null,
        testSettings: Settings = settings,
    ): IseeAnswers = IseeAnswers(
        media = media,
        settings = { testSettings },
        update = ::update,
        transcript = transcript,
        playback = playback(synthesize),
        photo = photo,
        prune = {},
        destination = { destination },
        ask = { image, question, history, context -> onContext?.invoke(context); ask(image, question, history) },
        askTimeoutMs = askTimeoutMs,
    )

    /** Réponses branchées sur une clé LLM absente : le contexte photo doit partir aussi. */
    protected fun answersWithoutKey(
        photo: IseePhotoShot = shots,
        ask: suspend (String, String, List<ChatTurn>) -> Result<String> = { _, _, _ -> Result.success("ok") },
    ): IseeAnswers = IseeAnswers(
        media = media,
        settings = { Settings.Default },
        update = ::update,
        transcript = transcript,
        playback = playback(),
        photo = photo,
        prune = {},
        destination = { null },
        ask = { image, question, history, _ -> ask(image, question, history) },
    )

    protected fun seedRow(): MessageMultimedia =
        dao.seed(MessageMultimedia(timestamp = 1L, texteUtilisateur = "question"))

    /**
     * Vrai quand une image est en mains. La sonde relit le contexte, ce qui
     * redéclenche une capture quand il est vide : les tests de fin de vie
     * coupent la caméra (`accept = false`) avant de sonder après `answer`.
     */
    protected fun imageHeld(photo: IseePhotoShot): Boolean =
        runBlocking { photo.imageBase64() } != null

    protected fun update(fn: (IseeUiState) -> IseeUiState) {
        state.value = fn(state.value)
    }

    protected fun playback(
        synthesize: suspend (String) -> ByteArray = { ByteArray(0) },
        playSlice: suspend (ByteArray) -> Result<PlaybackEnd> = { Result.success(PlaybackEnd.Done) },
    ): IseePlayback = IseePlayback(
        dao = dao,
        media = media,
        player = SpeechPlayer(File(tempDir(), "play")),
        update = ::update,
        synthesize = synthesize,
        playSlice = playSlice,
    )

    /** Contexte photo de test : [encode] simule le décodage JPEG, absent sur JVM. */
    protected fun photoShot(encode: (File) -> String?): IseePhotoShot = IseePhotoShot(
        media = media,
        camera = { camera },
        encode = encode,
    )

    /** PCM muet de 100 ms (16 kHz, 16 bits, mono) : l'encodeur MP3 a de quoi écrire. */
    protected fun pcm(): ByteArray = ByteArray(3_200)

    protected fun tempDir(): File =
        Files.createTempDirectory("isee-vm-answers").toFile().apply { deleteOnExit() }
}

/** Caméra factice : photo écrite sur disque et réussie, sauf `accept = false`. */
internal class FakeCamera : IseeCameraActions {
    var accept = true

    override val ready: Boolean = true

    override fun startRecording(file: File): Boolean = false

    override suspend fun stopRecording(): File? = null

    override fun takePhoto(target: File, onSaved: (Boolean) -> Unit): Boolean {
        if (!accept) return false
        target.parentFile?.mkdirs()
        target.writeBytes(PHOTO_BYTES)
        onSaved(true)
        return true
    }

    private companion object {
        /** Quatre octets suffisent : le contenu de la photo n'est jamais décodé ici. */
        val PHOTO_BYTES = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
    }
}
