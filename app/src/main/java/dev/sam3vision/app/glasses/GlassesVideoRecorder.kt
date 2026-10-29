package dev.sam3vision.app.glasses

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Journal de l'enregistrement (recette : `logcat -s GlassesVideo`). */
private const val TAG = "GlassesVideo"

/** Dossier de la galerie où atterrissent les vidéos des lunettes. */
private const val RELATIVE_DIR = "Movies/iSee"

/** Images gardées le temps que la piste audio se déclare (~10 s à 15 i/s). */
private const val MAX_PENDING_VIDEO = 150

/** Paquets AAC gardés le temps que l'image arrive (~12 s de son). */
private const val MAX_PENDING_AUDIO = 600

/** Au-delà, on écrit la vidéo sans son plutôt que de ne rien écrire du tout. */
private const val AUDIO_WAIT_MS = 4_000L

/** Nom du fichier : horodatage lisible, unique à la seconde. */
fun recordingFileName(nowMs: Long): String =
    "isee-lunettes-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.FRANCE).format(Date(nowMs)) + ".mp4"

/** Une trame déjà copiée, en attente que le fichier soit ouvert. */
private class Sample(val bytes: ByteArray, val ptsUs: Long)

/**
 * Enregistre le flux des lunettes dans la galerie, **sans ré-encoder l'image** : le SDK livre
 * déjà du H.265 (`compressVideo = true`), donc chaque trame est écrite telle quelle dans un
 * `MediaMuxer`. Le son, lui, est encodé en AAC ([GlassesAudioEncoder]) et muxé dans le même
 * fichier : une seule vidéo, image et son.
 *
 * Le fichier démarre quand l'image **et** le son sont connus (ou après [AUDIO_WAIT_MS] si le
 * micro se tait), et se publie dans la galerie à la fermeture de la session.
 */
internal class GlassesVideoRecorder(
    private val context: Context,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val onNote: (GlassesMessage) -> Unit,
    private val onRecording: (String?) -> Unit = {},
) {
    private val lock = Any()

    private var muxer: MediaMuxer? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var videoTrack = -1
    private var audioTrack = -1

    private var config: ByteArray? = null
    private var width = 0
    private var height = 0
    private var lastPtsUs = -1L
    private var frames = 0
    private var startedAtMs = 0L
    private var name: String? = null
    private var broken = false

    private var pendingVideo = ArrayDeque<Sample>()
    private var pendingAudio = ArrayDeque<Sample>()
    private var audio: GlassesAudioEncoder? = null
    private var audioFormat: MediaFormat? = null
    private var soundExpected = false
    private var soundGaveUp = false
    private var soundRate = 0
    private var soundChannels = 1
    private var wroteSound = false

    private var firstVideoPtsUs = -1L
    private var firstAudioPtsUs = -1L
    private var firstSampleAtMs = 0L
    private var basePtsUs = 0L

    /**
     * Ce que le flux a négocié : l'encodeur AAC sera ouvert au premier tampon PCM. Sans piste
     * audio (micro refusé côté Meta AI), le fichier s'ouvre dès la première image.
     */
    fun expectAudio(choice: AudioChoice) = synchronized(lock) {
        soundExpected = !choice.silent
        soundGaveUp = choice.silent
        soundRate = choice.hertz
        soundChannels = choice.channels.coerceAtLeast(1)
    }

    /** Une trame du flux : en-tête mémorisé, images écrites telles quelles. */
    fun consume(frame: GlassesFrame) = synchronized(lock) {
        if (broken || !frame.compressed) return
        if (frame.codecConfig) {
            // Un en-tête qui revient alors que le fichier est ouvert ne peut plus rien changer.
            if (muxer == null) {
                config = frame.bytes
                width = frame.width
                height = frame.height
                maybeOpen()
            }
            return
        }
        // Un horodatage qui recule fait échouer le muxage : on l'ignore, on ne le corrige pas.
        if (frame.presentationTimeUs <= lastPtsUs) return
        lastPtsUs = frame.presentationTimeUs
        if (firstVideoPtsUs < 0) firstVideoPtsUs = frame.presentationTimeUs
        if (firstSampleAtMs == 0L) firstSampleAtMs = clock()
        emitVideo(frame.bytes, frame.presentationTimeUs)
    }

    /** Un tampon PCM du micro des lunettes : il part dans l'encodeur AAC, jamais dans le MP4. */
    fun consumeAudio(pcm: ByteArray, presentationTimeUs: Long) = synchronized(lock) {
        if (broken || soundGaveUp || pcm.isEmpty()) return
        val active = audio ?: openAudio() ?: return
        if (firstAudioPtsUs < 0) firstAudioPtsUs = presentationTimeUs
        active.encode(pcm, presentationTimeUs)
        maybeOpen()
    }

    /** Fin de session : le son est vidé, le fichier finalisé et publié dans la galerie. */
    fun stop() = synchronized(lock) {
        // Rien n'a jamais été ouvert : ce n'est pas une panne d'enregistrement, on se taisait.
        if (muxer == null && name == null && frames == 0) return
        val saved = name
        val seconds = if (startedAtMs > 0) ((clock() - startedAtMs) / 1000).coerceAtLeast(0) else 0
        val written = frames
        val sound = wroteSound
        audio?.finish()
        audio?.close()
        audio = null
        close(keep = written > 0 && !broken)
        when {
            saved != null && written > 0 && !broken ->
                onNote(GlassesMessages.recordingSaved(saved, seconds, sound))
            !broken -> onNote(GlassesMessages.recordingEmpty())
        }
        reset()
        onRecording(null)
    }

    /** Ouvre l'encodeur AAC au format négocié ; `null` = plus aucun son possible. */
    private fun openAudio(): GlassesAudioEncoder? {
        if (!soundExpected) return null
        val created = GlassesAudioEncoder(
            onFormat = { format ->
                audioFormat = format
                maybeOpen()
            },
            onSample = ::emitAudio,
            onLog = { Log.w(TAG, it) },
        )
        if (!created.start(soundRate, soundChannels)) {
            soundGaveUp = true
            return null
        }
        audio = created
        return created
    }

    /** Le fichier attend-il encore le son ? Oui tant que le format AAC n'est pas connu. */
    private fun soundReady(): Boolean {
        if (!soundExpected || soundGaveUp || audioFormat != null) return true
        val waited = if (firstSampleAtMs == 0L) 0L else clock() - firstSampleAtMs
        if (waited < AUDIO_WAIT_MS && pendingVideo.size < MAX_PENDING_VIDEO) return false
        soundGaveUp = true
        Log.i(TAG, "enregistrement sans son : l'encodeur AAC n'a rien livré")
        return true
    }

    /** Ouvre le fichier quand les deux pistes sont prêtes (ou que le son a renoncé). */
    private fun maybeOpen() {
        if (muxer != null || broken) return
        val header = config ?: return
        if (width <= 0 || height <= 0) return
        if (!soundReady()) return
        open(header)
    }

    /** Crée le fichier de la galerie, déclare les pistes, puis vide l'attente. */
    private fun open(header: ByteArray): Boolean = runCatching {
        val fileName = recordingFileName(clock())
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_DIR)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val target = context.contentResolver.insert(collection, values)
            ?: error("MediaStore refuse le fichier")
        val fd = context.contentResolver.openFileDescriptor(target, "rw")
            ?: error("fichier vidéo inaccessible")
        // Aucune rotation : le capteur des lunettes est portrait natif et son contenu est
        // déjà droit dans ce cadre (720×1280 en qualité haute). Un quart de tour coucherait
        // la scène ; le fichier garde donc exactement ce que l'app affiche.
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(header))
        }
        val created = MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        videoTrack = created.addTrack(format)
        // Toutes les pistes se déclarent **avant** start() : après, le muxeur les refuse.
        audioTrack = audioFormat?.let { created.addTrack(it) } ?: -1
        basePtsUs = basePts()
        created.start()
        muxer = created
        descriptor = fd
        uri = target
        name = fileName
        flushPending()
        true
    }.onFailure { fail(it) }.getOrDefault(false)

    /** Horodatage commun aux deux pistes : le plus tôt connu, pour garder la synchronisation. */
    private fun basePts(): Long = when {
        firstVideoPtsUs >= 0 && firstAudioPtsUs >= 0 -> minOf(firstVideoPtsUs, firstAudioPtsUs)
        firstVideoPtsUs >= 0 -> firstVideoPtsUs
        else -> firstAudioPtsUs.coerceAtLeast(0)
    }

    /** Image : mise en attente tant que le fichier n'est pas ouvert, écrite ensuite. */
    private fun emitVideo(bytes: ByteArray, ptsUs: Long) {
        if (muxer != null) {
            writeVideo(bytes, ptsUs)
            return
        }
        pendingVideo.addLast(Sample(bytes, ptsUs))
        if (pendingVideo.size > MAX_PENDING_VIDEO) pendingVideo.removeFirst()
        maybeOpen()
    }

    /** Son : idem, avec une attente plus courte — un MP4 muet reste préférable à rien. */
    private fun emitAudio(bytes: ByteArray, ptsUs: Long) {
        if (muxer != null) {
            writeAudio(bytes, ptsUs)
            return
        }
        pendingAudio.addLast(Sample(bytes, ptsUs))
        if (pendingAudio.size > MAX_PENDING_AUDIO) pendingAudio.removeFirst()
    }

    private fun flushPending() {
        while (pendingVideo.isNotEmpty()) {
            val sample = pendingVideo.removeFirst()
            writeVideo(sample.bytes, sample.ptsUs)
        }
        while (pendingAudio.isNotEmpty()) {
            val sample = pendingAudio.removeFirst()
            writeAudio(sample.bytes, sample.ptsUs)
        }
    }

    private fun writeVideo(bytes: ByteArray, ptsUs: Long) {
        val active = muxer ?: return
        if (videoTrack < 0) return
        val info = MediaCodec.BufferInfo().apply {
            set(0, bytes.size, (ptsUs - basePtsUs).coerceAtLeast(0), MediaCodec.BUFFER_FLAG_KEY_FRAME)
        }
        runCatching { active.writeSampleData(videoTrack, ByteBuffer.wrap(bytes), info) }
            .onFailure { fail(it) }
            .onSuccess {
                if (frames == 0) {
                    startedAtMs = clock()
                    Log.i(TAG, "enregistrement démarré : $name")
                    onRecording(name)
                }
                frames += 1
            }
    }

    private fun writeAudio(bytes: ByteArray, ptsUs: Long) {
        val active = muxer ?: return
        if (audioTrack < 0) return
        val info = MediaCodec.BufferInfo().apply {
            set(0, bytes.size, (ptsUs - basePtsUs).coerceAtLeast(0), 0)
        }
        runCatching { active.writeSampleData(audioTrack, ByteBuffer.wrap(bytes), info) }
            .onFailure { fail(it) }
            .onSuccess { wroteSound = true }
    }

    /** Un échec est dit **une fois**, et le fichier à moitié écrit est retiré de la galerie. */
    private fun fail(error: Throwable) {
        if (broken) return
        broken = true
        Log.w(TAG, "enregistrement impossible : ${error.message}", error)
        onNote(GlassesMessages.recordingFailed(error.message ?: "erreur du muxage"))
        close(keep = false)
    }

    private fun close(keep: Boolean) {
        val active = muxer
        muxer = null
        if (active != null && keep) runCatching { active.stop() }
        runCatching { active?.release() }
        runCatching { descriptor?.close() }
        descriptor = null
        val target = uri
        uri = null
        if (target != null) {
            if (keep) publish(target) else runCatching { context.contentResolver.delete(target, null, null) }
        }
        videoTrack = -1
        audioTrack = -1
        lastPtsUs = -1L
        pendingVideo = ArrayDeque()
        pendingAudio = ArrayDeque()
    }

    /** Oublie l'enregistrement terminé, pour que la session suivante reparte à zéro. */
    private fun reset() {
        frames = 0
        startedAtMs = 0L
        name = null
        wroteSound = false
        audioFormat = null
        config = null
        firstVideoPtsUs = -1L
        firstAudioPtsUs = -1L
        firstSampleAtMs = 0L
        basePtsUs = 0L
    }

    /** Fichier visible dans la galerie : sans cela il reste « en cours » et invisible. */
    private fun publish(target: Uri) {
        val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        runCatching { context.contentResolver.update(target, values, null, null) }
            .onSuccess { Log.i(TAG, "vidéo publiée : $target") }
            .onFailure { Log.w(TAG, "publication impossible : ${it.message}") }
    }
}
