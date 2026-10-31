package dev.sam3vision.app.glasses

import com.meta.wearable.dat.camera.types.AudioFrame
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/** Nombre de trames gardées pour la dictée : au-delà, on jette la plus ancienne. */
private const val DICTATION_BUFFER = 64

/**
 * Micro des lunettes **pendant toute la session** : une seule collecte alimente les deux
 * consommateurs — l'enregistreur vidéo (son du MP4) et la dictée ([GlassesAudio]).
 *
 * Une seule collecte, parce que deux abonnés au même flux du SDK se voleraient les trames :
 * la dictée n'entendrait alors que la moitié des syllabes. Ici, chaque trame est copiée une
 * fois, donnée à l'enregistreur, puis republiée pour la dictée — qui n'écoute que quand elle
 * parle (aucun abonné = aucune copie gardée).
 */
internal class GlassesAudioTap(
    private val frames: Flow<AudioFrame>,
    private val scope: CoroutineScope,
    private val recorder: GlassesVideoRecorder?,
    private val onLog: (String) -> Unit = {},
) {
    private val dictation = MutableSharedFlow<AudioFrame>(
        extraBufferCapacity = DICTATION_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Flux à donner à [GlassesAudio] ; les trames non écoutées sont simplement oubliées. */
    val dictationFrames: Flow<AudioFrame> = dictation

    private var job: Job? = null
    private var received = 0L

    /** Démarre la collecte unique ; sans effet si elle tourne déjà. */
    fun start() {
        if (job != null) return
        job = scope.launch {
            runCatching { frames.collect(::deliver) }
                .onFailure { onLog("glasses mic interrupted: ${it.message}") }
        }
    }

    /** Arrête la collecte : plus aucune trame ne part vers l'enregistreur ni la dictée. */
    fun stop() {
        job?.cancel()
        job = null
    }

    /** Copie défensive, puis deux destins : le MP4 et la dictée. */
    private fun deliver(frame: AudioFrame) {
        val bytes = runCatching {
            val buffer = frame.buffer
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        }.getOrNull() ?: return
        recorder?.consumeAudio(bytes, frame.presentationTimeUs)
        dictation.tryEmit(AudioFrame(ByteBuffer.wrap(bytes), frame.presentationTimeUs))
        received += 1
        if (received == 1L || received % 500 == 0L) {
            onLog("glasses mic: $received frames (${bytes.size} B)")
        }
    }
}
