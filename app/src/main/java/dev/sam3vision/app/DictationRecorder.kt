package dev.sam3vision.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import dev.sam3vision.core.audio.Wav
import dev.sam3vision.core.audio.PcmPreviewBuffer
import dev.sam3vision.core.audio.PcmPreview
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

/**
 * Micro -> PCM 16 bits mono 16 kHz (format exact attendu par Meta ASR).
 * Un seul enregistrement à la fois ; s'arrête seul à [maxDurationMs]
 * (`0` = aucune limite : appui long, l'arrêt vient du relâchement).
 * `start()` renvoie `false` si le micro est indisponible (permission, matériel).
 */
class DictationRecorder(
    private val sampleRate: Int = Wav.SAMPLE_RATE,
    private val maxDurationMs: Long = MAX_DURATION_MS,
) : PcmSource {
    private var record: AudioRecord? = null
    private var worker: Thread? = null
    private var startedAt = 0L
    private val captured = ByteArrayOutputStream()
    private val preview = PcmPreviewBuffer()

    /** Copie bornée du son récent, sans arrêter l'enregistrement principal. */
    fun preview(): PcmPreview = preview.snapshot()

    @SuppressLint("MissingPermission") // la permission RECORD_AUDIO est demandée par l'écran
    override fun start(): Boolean {
        if (record != null) return false
        return try {
            val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuffer <= 0) return false
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 2,
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                false
            } else {
                captured.reset()
                preview.reset()
                startedAt = System.currentTimeMillis()
                rec.startRecording()
                record = rec
                worker = thread(isDaemon = true, name = "dictation-pump") { pump(rec, minBuffer) }
                true
            }
        } catch (e: SecurityException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    /** Coupe le micro et rend le PCM capturé (vide si rien n'a été enregistré). */
    override fun stopAndCollect(): ByteArray {
        val rec = record ?: return ByteArray(0)
        record = null
        runCatching { rec.stop() }
        worker?.join(WORKER_JOIN_MS)
        worker = null
        runCatching { rec.release() }
        return captured.toByteArray()
    }

    private fun pump(rec: AudioRecord, bufferSize: Int) {
        val chunk = ByteArray(bufferSize)
        while (!Thread.currentThread().isInterrupted && !expired()) {
            val read = rec.read(chunk, 0, chunk.size)
            if (read <= 0) break
            // Flux continu : chaque chunk part dans le tampon mémoire, jamais sur disque.
            synchronized(captured) { captured.write(chunk, 0, read) }
            preview.append(chunk, read)
        }
        // Plafond atteint : on coupe la source, les données restent récupérables.
        if (record === rec) runCatching { rec.stop() }
    }

    /** Vrai quand le plafond est dépassé ; sans plafond ([maxDurationMs] <= 0), jamais. */
    private fun expired(): Boolean =
        maxDurationMs > 0 && System.currentTimeMillis() >= startedAt + maxDurationMs

    companion object {
        /** `0` = aucune limite : l'appui long dicte tant que le doigt reste posé. */
        const val MAX_DURATION_MS = 0L
        private const val WORKER_JOIN_MS = 1_500L
    }
}
