package dev.sam3vision.app.traverse

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.core.traverse.TraverseClipStore
import dev.sam3vision.core.traverse.TraversePlayer
import dev.sam3vision.core.traverse.TraverseSystemTts
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Dossier cache du lecteur traversée, dédié (`play(bytes)` vide son répertoire). */
internal const val TRAVERSE_VOICE_DIR = "traverse-voice"

/** Dossier `filesDir` des clips hors-ligne, jamais vidé par le lecteur. */
internal const val TRAVERSE_CLIP_DIR = "traverse-clips"

/**
 * [SpeechPlayer] vu comme [TraversePlayer] : `isBusy` se dérive de
 * [SpeechPlayer.position] (non `null` pendant une lecture), les résultats
 * `Result<Unit>` deviennent des booléens. Instanciable uniquement sur
 * Android, donc sans test JVM (comme `AndroidTtsDriver`).
 */
class PlayerTraversePlayer(private val player: SpeechPlayer) : TraversePlayer {

    override val isBusy: Boolean get() = player.position() != null

    override fun stop() = player.stop()

    override suspend fun play(bytes: ByteArray): Boolean = player.play(bytes).isSuccess

    override suspend fun playFile(path: String): Boolean = player.playFile(File(path)).isSuccess
}

/**
 * `TextToSpeech` système vu comme [TraverseSystemTts] : rend les phrases
 * FR en fichiers WAV hors-ligne (`synthesizeToFile`, voix embarquée).
 *
 * Le moteur est possédé (contexte applicatif, jamais une activité) :
 * l'initialisation est asynchrone, [renderToFile] l'attend jusqu'à
 * [INIT_TIMEOUT_MS] puis échoue proprement. Chaque rendu a son propre
 * `utteranceId` suivi dans [UtteranceProgressListener], borné par
 * [RENDER_TIMEOUT_MS]. [close] est idempotent (appelé depuis `onCleared`).
 *
 * Instanciable uniquement sur Android, donc sans test JVM.
 */
class TtsTraverseSystemTts(
    appContext: Context,
    private val language: Locale,
) : TraverseSystemTts, TextToSpeech.OnInitListener {

    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val tts = TextToSpeech(appContext.applicationContext, this)

    init {
        tts.setOnUtteranceProgressListener(TraverseUtteranceListener(pending))
    }

    override fun onInit(status: Int) {
        ready.complete(status == TextToSpeech.SUCCESS)
    }

    override suspend fun renderToFile(phrase: String, path: String): Result<String> =
        runCatching { renderOrThrow(phrase, path) }

    /** Langue effective du moteur (`fr-FR`, `en-US`, …), vide si illisible. */
    override fun effectiveLanguage(): String =
        runCatching { tts.voice?.locale ?: tts.language }.getOrNull()?.toLanguageTag() ?: ""

    /** Libération du moteur, idempotente ; les rendus en vol échouent proprement. */
    fun close() {
        ready.complete(false)
        pending.values.forEach { it.complete(false) }
        pending.clear()
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
    }

    /** Rendu garanti non vide, ou levée (convertie en échec par [renderToFile]). */
    private suspend fun renderOrThrow(phrase: String, path: String): String {
        if (phrase.isBlank()) throw IllegalArgumentException("phrase vide")
        if (!awaitReady()) throw IllegalStateException("init TTS trop lente ou en echec")
        checkVoice()
        val file = File(path)
        file.parentFile?.mkdirs()
        awaitRender(phrase, file)
        if (!file.isFile || file.length() == 0L) throw IllegalStateException("clip vide")
        return file.absolutePath
    }

    /** Le moteur est-il prêt ? Faux après [close] ou au-delà du délai. */
    private suspend fun awaitReady(): Boolean =
        withTimeoutOrNull(INIT_TIMEOUT_MS) { ready.await() } == true

    /** La voix [language] existe-t-elle dans le moteur ? Sinon, levée. */
    private fun checkVoice() {
        val status = runCatching { tts.setLanguage(language) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (status == TextToSpeech.LANG_MISSING_DATA || status == TextToSpeech.LANG_NOT_SUPPORTED) {
            throw IllegalStateException("voix FR absente du moteur")
        }
    }

    /** Synthèse suivie par `utteranceId`, bornée par [RENDER_TIMEOUT_MS]. */
    private suspend fun awaitRender(phrase: String, file: File) {
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Boolean>()
        pending[id] = done
        try {
            val status = runCatching { tts.synthesizeToFile(phrase, Bundle(), file, id) }
                .getOrDefault(TextToSpeech.ERROR)
            if (status != TextToSpeech.SUCCESS) throw IllegalStateException("rendu refuse")
            if (withTimeoutOrNull(RENDER_TIMEOUT_MS) { done.await() } != true) {
                throw IllegalStateException("rendu trop lent ou en echec")
            }
        } finally {
            pending.remove(id)
        }
    }

    companion object {
        /** Attente max de l'init TTS (quelques centaines de ms en pratique). */
        const val INIT_TIMEOUT_MS = 5_000L

        /** Attente max d'un rendu (une phrase courte, voix embarquée). */
        const val RENDER_TIMEOUT_MS = 10_000L
    }
}

/** Aiguille `onDone`/`onError` vers le rendu qui attend son `utteranceId`. */
private class TraverseUtteranceListener(
    private val pending: ConcurrentHashMap<String, CompletableDeferred<Boolean>>,
) : UtteranceProgressListener() {

    override fun onStart(utteranceId: String) = Unit

    override fun onDone(utteranceId: String) {
        pending[utteranceId]?.complete(true)
    }

    override fun onError(utteranceId: String) {
        pending[utteranceId]?.complete(false)
    }
}

/**
 * Cache local des clips hors-ligne : `filesDir/traverse-clips`, un WAV au nom
 * stable (SHA-256 tronqué) par phrase. Pur `java.io`, testé en JVM.
 */
class DirTraverseClipStore(private val dir: File) : TraverseClipStore {

    override fun pathFor(phrase: String): String {
        dir.mkdirs()
        return clipFile(phrase).absolutePath
    }

    override fun clipFor(phrase: String): String? {
        val file = clipFile(phrase)
        return file.absolutePath.takeIf { file.isFile && file.length() > 0L }
    }

    /** Fichier réservé à [phrase] (16 hex + `.wav`). */
    private fun clipFile(phrase: String): File = File(dir, "${sha16(phrase)}.wav")

    companion object {
        /** 8 premiers octets du SHA-256, en hexadécimal (16 caractères). */
        fun sha16(phrase: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(phrase.toByteArray(Charsets.UTF_8))
            return digest.take(8).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }
    }
}
