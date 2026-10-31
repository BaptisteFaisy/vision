package dev.sam3vision.app.glasses

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import com.meta.wearable.dat.camera.types.VideoFrame
import java.nio.ByteBuffer

/**
 * Formats de sortie YUV du décodeur (`MediaCodecInfo.CodecCapabilities`,
 * recopiés en constantes pour rester testables sur JVM) : planaire I420 (pas
 * de chroma 1) et semi-planaire NV12 (pas 2).
 */
internal const val COLOR_FORMAT_YUV420_PLANAR = 19
internal const val COLOR_FORMAT_YUV420_SEMIPLANAR = 21

/**
 * Pas de chroma pour un format de sortie du décodeur, `null` si le format
 * n'est pas du YUV 4:2:0 géré (dont `COLOR_FormatYUV420Flexible`, qui exigerait
 * de deviner le plan par buffer).
 */
internal fun chromaStepFor(colorFormat: Int): Int? = when (colorFormat) {
    COLOR_FORMAT_YUV420_PLANAR -> 1
    COLOR_FORMAT_YUV420_SEMIPLANAR -> 2
    else -> null
}

/**
 * Disposition du YUV sorti du décodeur, `null` si elle n'est pas exprimable
 * ([Yuv420Layout] ne porte pas de hauteur de tranche : une tranche plus haute
 * que l'image est jetée, pas devinée).
 */
internal fun decoderOutputLayout(
    width: Int,
    height: Int,
    stride: Int,
    sliceHeight: Int,
    colorFormat: Int,
): Yuv420Layout? {
    if (width <= 0 || height <= 0 || stride < width || sliceHeight != height) return null
    val step = chromaStepFor(colorFormat) ?: return null
    val uvStride = if (step >= 2) stride else (stride / 2).coerceAtLeast((width + 1) / 2)
    return Yuv420Layout(width, height, stride, uvStride, step)
}

/**
 * Bitstream H.265 des lunettes → [ArgbImage], via `MediaCodec` (sortie
 * `ByteBuffer`, sans `Surface` : le YUV part directement dans [YuvConverter]).
 *
 * **Jamais essayé sur matériel** : aucune paire disponible, aucune frame
 * compressée jamais vue, permission caméra `CAPABILITY_DENIED` en suspens. En
 * conséquence tout est défensif : un `null` (frame jetée, cause journalisée)
 * plutôt qu'une exception, jamais. Après [MAX_FAILURES] créations ratées le
 * décodeur s'avoue vaincu (`broken`) jusqu'au prochain [release].
 *
 * Alimentation : les frames de configuration brutes vont à [stashCsd] (leur
 * charge devient le `csd-0` du `MediaFormat`) ; les frames compressées — y
 * compris celles qui portent aussi le drapeau de configuration, présentées
 * avec `BUFFER_FLAG_CODEC_CONFIG` — vont à [decode]. Une entrée ne rend une
 * image que si le décodeur sort un buffer à temps ([OUTPUT_WAIT_MS]).
 *
 * Classe ouverte pour les tests : le collecteur s'éprouve sur JVM avec un faux
 * décodeur (un `MediaCodec` réel n'existe pas hors Android).
 */
internal open class GlassesVideoDecoder(
    private val onLog: (String) -> Unit = {},
) {
    private var codec: MediaCodec? = null
    private var codecWidth = 0
    private var codecHeight = 0
    private var csd0: ByteArray? = null
    private var failures = 0
    private var broken = false

    /** Dernier format de sortie annoncé (`INFO_OUTPUT_FORMAT_CHANGED`). */
    private var outStride = 0
    private var outSliceHeight = 0
    private var outColorFormat = 0
    private var formatWarned = false

    /** La charge d'une frame de configuration brute : futur `csd-0`. */
    open fun stashCsd(frame: VideoFrame) {
        frame.payloadBytes()?.let { csd0 = it }
    }

    /** Décode une frame compressée ; `null` = jetée (cause journalisée). Ne lève jamais. */
    open fun decode(frame: VideoFrame): ArgbImage? {
        if (broken || frame.width <= 0 || frame.height <= 0) return null
        val active = ensureCodec(frame) ?: return null
        if (!queueInput(active, frame)) return null
        return pollOutput(active, frame)
    }

    /** Libère le codec ; le prochain [decode] repart de zéro (abandon effacé). */
    open fun release() {
        dropCodec()
        failures = 0
        broken = false
    }

    /** Le codec courant s'il est aux bonnes dimensions, sinon un neuf (ou `null`). */
    private fun ensureCodec(frame: VideoFrame): MediaCodec? {
        val current = codec
        if (current != null && codecWidth == frame.width && codecHeight == frame.height) return current
        create(frame)
        return codec
    }

    /** (Re)crée le décodeur HEVC aux dimensions de [frame] ; échec compté. */
    private fun create(frame: VideoFrame) {
        dropCodec()
        val fresh = runCatching {
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_HEVC, frame.width, frame.height,
            )
            csd0?.let { format.setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC).also {
                it.configure(format, null, null, 0)
                it.start()
            }
        }.getOrNull()
        if (fresh == null) {
            failures += 1
            if (failures >= MAX_FAILURES) {
                broken = true
                onLog("H.265 decoder given up after $failures failures")
            }
        } else {
            failures = 0
            codecWidth = frame.width
            codecHeight = frame.height
            formatWarned = false
        }
        codec = fresh
    }

    /** Présente [frame] au décodeur ; `false` = codec tombé, recréé au prochain appel. */
    private fun queueInput(active: MediaCodec, frame: VideoFrame): Boolean {
        val payload = frame.payloadBytes() ?: return false
        val index = runCatching { active.dequeueInputBuffer(INPUT_TIMEOUT_US) }.getOrDefault(-1)
        if (index < 0) return false
        val queued = runCatching {
            val buffer = active.getInputBuffer(index)
            if (buffer == null) {
                active.queueInputBuffer(index, 0, 0, 0L, 0)
                return false
            }
            buffer.clear()
            if (payload.size > buffer.capacity()) {
                // Trop gros pour le codec : on rend le buffer (vide) au lieu de le perdre.
                active.queueInputBuffer(index, 0, 0, 0L, 0)
                return false
            }
            buffer.put(payload)
            val flags = if (frame.isCodecConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
            active.queueInputBuffer(index, 0, payload.size, frame.presentationTimeUs, flags)
        }
        if (queued.isFailure) dropCodec()
        return queued.isSuccess
    }

    /** Attend une image décodée jusqu'à [OUTPUT_WAIT_MS] ; `null` si rien ne sort. */
    private fun pollOutput(active: MediaCodec, frame: VideoFrame): ArgbImage? {
        val info = MediaCodec.BufferInfo()
        val deadline = SystemClock.uptimeMillis() + OUTPUT_WAIT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val index = runCatching {
                active.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US)
            }.getOrDefault(MediaCodec.INFO_TRY_AGAIN_LATER)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> readOutputFormat(active)
                index < 0 -> Unit
                else -> outputToArgb(active, index, info, frame)?.let { return it }
            }
        }
        return null
    }

    /**
     * Buffer de sortie → [ArgbImage], `null` si inexploitable. Le buffer est
     * toujours rendu (`finally`) : un buffer gardé finit par bloquer le décodeur.
     */
    private fun outputToArgb(
        active: MediaCodec,
        index: Int,
        info: MediaCodec.BufferInfo,
        frame: VideoFrame,
    ): ArgbImage? {
        try {
            if (info.size <= 0) return null
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return null
            val layout = decoderOutputLayout(
                frame.width, frame.height, outStride, outSliceHeight, outColorFormat,
            ) ?: return warnFormatOnce()
            val buffer = runCatching { active.getOutputBuffer(index) }.getOrNull() ?: return null
            val bytes = ByteArray(info.size)
            val view = buffer.duplicate()
            if (info.offset < 0 || info.offset + info.size > view.capacity()) return null
            view.position(info.offset)
            view.limit(info.offset + info.size)
            view.get(bytes)
            val argb = IntArray(frame.width * frame.height)
            if (!YuvConverter.yuv420ToArgb(bytes, layout, argb)) return null
            return ArgbImage(argb, frame.width, frame.height, info.presentationTimeUs)
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { active.releaseOutputBuffer(index, false) }
        }
    }

    /** Format de sortie annoncé par le décodeur : strides et format couleur retenus. */
    private fun readOutputFormat(active: MediaCodec) {
        val format = runCatching { active.outputFormat }.getOrNull() ?: return
        outStride = format.intOr("stride", 0)
        outSliceHeight = format.intOr("slice-height", 0)
        outColorFormat = format.intOr("color-format", 0)
    }

    /** Le format est inexprimable : une seule ligne de journal, puis `null` à chaque fois. */
    private fun warnFormatOnce(): ArgbImage? {
        if (!formatWarned) {
            formatWarned = true
            onLog("unsupported H.265 output format (stride=$outStride slice=$outSliceHeight cf=$outColorFormat)")
        }
        return null
    }

    /** Oublie le codec (arrêté et libéré) ; le `csd-0` survit, il resservira. */
    private fun dropCodec() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
    }

    private companion object {
        /** Attente d'un buffer d'entrée libre, en microsecondes. */
        const val INPUT_TIMEOUT_US = 50_000L

        /** Attente d'un buffer de sortie par tour, en microsecondes. */
        const val OUTPUT_TIMEOUT_US = 10_000L

        /** Attente totale d'une image décodée après chaque entrée, en millisecondes. */
        const val OUTPUT_WAIT_MS = 300L

        /** Créations ratées d'affilée avant l'abandon (effacé par [release]). */
        const val MAX_FAILURES = 3
    }
}

/** Entier d'un `MediaFormat`, ou [default] si la clé manque (elle manque souvent). */
private fun MediaFormat.intOr(key: String, default: Int): Int =
    runCatching { getInteger(key) }.getOrDefault(default)
