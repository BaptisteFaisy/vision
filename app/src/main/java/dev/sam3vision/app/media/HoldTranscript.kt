package dev.sam3vision.app.media

import dev.sam3vision.app.DictationRecorder
import dev.sam3vision.core.net.SpeechClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Appui long iSee : garde le micro ouvert tant que le doigt est posé, puis rend
 * **le texte transcrit ET le PCM capté** (le PCM part en MP3 dans le cache via
 * [MediaCacheStore]). La mécanique micro est celle de [DictationRecorder], la
 * transcription celle de `DictationUseCase` (Meta Muse, repli Scribe) — le moteur
 * existant est réutilisé, rien n'est réimplémenté.
 */
class HoldTranscript(private val recorder: DictationRecorder = DictationRecorder()) {

    /** Ouvre le micro ; `false` si le micro est indisponible (permission, matériel). */
    fun start(): Boolean = recorder.start()

    /** Le même micro alimente les sous-titres pendant la tenue. */
    fun preview() = recorder.preview()

    /** Brancher/débrancher le stream temps réel sur la pompe du micro. */
    fun setChunkListener(listener: ((ByteArray, Int) -> Unit)?) {
        recorder.onChunk = listener
    }

    /** Coupe le micro puis rend le texte transcrit avec le PCM capté (thread I/O). */
    suspend fun stop(client: SpeechClient): Result<Pair<String, ByteArray>> = withContext(Dispatchers.IO) {
        val pcm = recorder.stopAndCollect()
        val text = dictation(pcm, client)
        text.map { it to pcm }
    }

    /** Jette l'enregistrement en cours (relâchement pendant un état interdit). */
    fun abort() {
        recorder.stopAndCollect()
    }

    /** Transcription + refus local des enregistrements trop courts. */
    private suspend fun dictation(pcm: ByteArray, client: SpeechClient): Result<String> =
        dev.sam3vision.core.usecase.DictationUseCase(client).invoke(pcm)
}
