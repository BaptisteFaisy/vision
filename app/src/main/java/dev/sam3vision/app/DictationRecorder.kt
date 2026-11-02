package dev.sam3vision.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import dev.sam3vision.app.safety.CrashGuard
import dev.sam3vision.app.safety.redactSecrets
import dev.sam3vision.core.audio.Wav
import dev.sam3vision.core.audio.PcmPreviewBuffer
import dev.sam3vision.core.audio.PcmPreview
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

/** Étiquette du micro (`adb logcat -s DictationMic:*`). */
private const val TAG_MIC = "DictationMic"

/**
 * Motif lisible d'un refus micro (pur, testé en JVM). `IllegalStateException` = appareil non
 * initialisé ou pris par un appel / une autre app (`startRecording()` refuse) ; le reste garde
 * son type, jamais un message vide.
 */
internal fun micFailureHint(error: Throwable): String = when (error) {
    is SecurityException -> "permission"
    is IllegalStateException -> "occupe-ou-ferme"
    is IllegalArgumentException -> "materiel"
    else -> error.javaClass.simpleName
}

/**
 * Micro -> PCM 16 bits mono 16 kHz (format exact attendu par Meta ASR).
 * Un seul enregistrement à la fois ; s'arrête seul à [maxDurationMs]
 * (`0` = aucune limite : appui long, l'arrêt vient du relâchement).
 * `start()` renvoie `false` si le micro est indisponible (permission, matériel,
 * appareil occupé) et **ne lève jamais** : le motif du refus est dans
 * [lastFailure] (visible dans l'état, logcat `DictationMic`).
 *
 * Deux chemins fatals sont fermés : `startRecording()` (qui jette
 * `IllegalStateException` quand le micro vient d'être volé) et la pompe
 * `AudioRecord.read` (même cause, sur un fil nu : une exception y tuait le
 * process). Dans les deux cas le device est libéré — sinon la dictée suivante
 * tombait sur `record != null` et refusait de démarrer sans motif — et le PCM
 * déjà capté reste récupérable par [stopAndCollect].
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

    /**
     * Motif du dernier refus d'ouverture ou de pompe (`null` = rien à signaler) :
     * une dictée muette a toujours une cause lisible, au lieu de ressembler à
     * une panne réseau.
     */
    @Volatile
    var lastFailure: String? = null
        private set

    /**
     * Morceaux de PCM au fil de la capture (`count` octets valides) : la
     * dictée temps réel les streame pendant la tenue. Appelé sur le thread
     * de pompe ; **le tableau est réutilisé, copier avant de conserver**.
     * `null` (défaut) = comportement batch inchangé.
     */
    @Volatile
    var onChunk: ((chunk: ByteArray, count: Int) -> Unit)? = null

    /** Copie bornée du son récent, sans arrêter l'enregistrement principal. */
    fun preview(): PcmPreview = preview.snapshot()

    @SuppressLint("MissingPermission") // la permission RECORD_AUDIO est demandée par l'écran
    override fun start(): Boolean {
        if (record != null) {
            fail("deja-ouvert")
            return false
        }
        lastFailure = null
        val minBuffer = try {
            AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        } catch (t: Throwable) {
            fail("taille-tampon", t)
            return false
        }
        if (minBuffer <= 0) {
            fail("taille-tampon")
            return false
        }
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 2,
            )
        } catch (t: Throwable) {
            fail("creation", t)
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { rec.release() }
            fail("ininitialise")
            return false
        }
        return open(rec, minBuffer)
    }

    /**
     * Capture effective : `startRecording()` refuse dès que le micro est pris
     * (appel, assistant, autre app) — le device est alors libéré, jamais laissé
     * à l'état semi-ouvert, et l'échec est inscrit dans [lastFailure].
     */
    private fun open(rec: AudioRecord, minBuffer: Int): Boolean = try {
        captured.reset()
        preview.reset()
        startedAt = System.currentTimeMillis()
        rec.startRecording()
        worker = thread(isDaemon = true, name = "dictation-pump") { pump(rec, minBuffer) }
        record = rec
        true
    } catch (t: Throwable) {
        record = null
        runCatching { rec.release() }
        fail("demarrage", t)
        false
    }

    /**
     * Coupe le micro et rend le PCM capturé — y compris celui d'une pompe morte
     * avant l'arrêt (le son déjà pris ne se perd pas), puis vide le tampon.
     */
    override fun stopAndCollect(): ByteArray {
        val rec = record
        record = null
        if (rec != null) {
            runCatching { rec.stop() }
            worker?.join(WORKER_JOIN_MS)
            worker = null
            runCatching { rec.release() }
        }
        return synchronized(captured) {
            captured.toByteArray().also { captured.reset() }
        }
    }

    /**
     * Pompe de lecture : `read` jette dès que l'appareil n'est plus initialisé
     * (micro volé). Rien ne doit sortir d'ici — le fil est nu, donc une exception
     * tuait le process — et le device est rendu pour que la dictée suivante
     * puisse rouvrir.
     */
    private fun pump(rec: AudioRecord, bufferSize: Int) {
        val chunk = ByteArray(bufferSize)
        try {
            while (!Thread.currentThread().isInterrupted && !expired()) {
                val read = rec.read(chunk, 0, chunk.size)
                if (read <= 0) break
                // Flux continu : chaque chunk part dans le tampon mémoire, jamais sur disque.
                synchronized(captured) { captured.write(chunk, 0, read) }
                preview.append(chunk, read)
                onChunk?.invoke(chunk, read)
            }
        } catch (t: Throwable) {
            if (record === rec) record = null
            runCatching { rec.release() }
            fail("pompe", t)
        } finally {
            // Plafond atteint ou arrêt demandé : on coupe la source, les données restent récupérables.
            if (record === rec) runCatching { rec.stop() }
        }
    }

    /** Vrai quand le plafond est dépassé ; sans plafond ([maxDurationMs] <= 0), jamais. */
    private fun expired(): Boolean =
        maxDurationMs > 0 && System.currentTimeMillis() >= startedAt + maxDurationMs

    /** Inscrit le refus dans l'état ([lastFailure]), le logcat, et le journal d'incidents. */
    private fun fail(motif: String, error: Throwable? = null) {
        val detail = if (error == null) motif else "$motif/${micFailureHint(error)}"
        lastFailure = detail
        runCatching {
            android.util.Log.w(TAG_MIC, "micro-refus motif=$detail detail=[${redactSecrets(error?.message)}]")
        }
        if (error != null) CrashGuard.note("dictation-$motif", error)
    }

    companion object {
        /** `0` = aucune limite : l'appui long dicte tant que le doigt reste posé. */
        const val MAX_DURATION_MS = 0L
        private const val WORKER_JOIN_MS = 1_500L
    }
}
