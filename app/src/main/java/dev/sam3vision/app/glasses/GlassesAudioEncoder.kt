package dev.sam3vision.app.glasses

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat

/** Débit du son enregistré : 64 kbit/s mono suffit très largement à de la parole. */
private const val BITRATE = 64_000

/** Taille maximale d'un tampon d'entrée : une trame PCM des lunettes tient dedans. */
private const val MAX_INPUT = 32_768

/** Attente d'un tampon codec : courte, on encode au fil de l'eau. */
private const val TIMEOUT_US = 10_000L

/** Nombre de tours de vidage en fin de piste, avant d'abandonner le reliquat. */
private const val DRAIN_ROUNDS = 64

/**
 * PCM 16 bits mono/multi → **AAC-LC**, pour que le son des lunettes entre dans le même MP4
 * que l'image. Un seul encodeur existe dans l'app, et il vit ici.
 *
 * Les rappels [onFormat] (`csd-0`, indispensable pour déclarer la piste) et [onSample]
 * (AAC prêt à muxer) sont appelés **sur le fil qui appelle [encode]**, jamais ailleurs :
 * le muxage n'a donc qu'un seul fil audio à protéger.
 */
internal class GlassesAudioEncoder(
    private val onFormat: (MediaFormat) -> Unit,
    private val onSample: (bytes: ByteArray, presentationTimeUs: Long) -> Unit,
    private val onLog: (String) -> Unit = {},
) {
    private var codec: MediaCodec? = null
    private var bytesPerSecond = 0
    private var inputEnded = false

    /** Vrai dès que MediaCodec a livré son format de sortie : la piste peut être déclarée. */
    var configured = false
        private set

    /** Ouvre le codec au format exact du flux ; `false` = pas d'encodeur, donc pas de son. */
    fun start(sampleRate: Int, channels: Int): Boolean {
        if (codec != null) return true
        if (sampleRate <= 0 || channels <= 0) return false
        return runCatching {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT)
            }
            val created = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            codec = created
            bytesPerSecond = sampleRate * channels * 2
            true
        }.onFailure { onLog("encodeur AAC indisponible : ${it.message}") }.getOrDefault(false)
    }

    /**
     * Une trame PCM 16 bits, découpée en autant de tampons que le codec en réclame. Son
     * horodatage est celui du flux : la synchronisation avec l'image ne se recalcule pas ici.
     */
    fun encode(pcm: ByteArray, presentationTimeUs: Long) {
        val active = codec ?: return
        if (inputEnded || bytesPerSecond <= 0) return
        var offset = 0
        while (offset < pcm.size) {
            val index = runCatching { active.dequeueInputBuffer(TIMEOUT_US) }.getOrDefault(-1)
            if (index < 0) break
            val buffer = active.getInputBuffer(index) ?: break
            buffer.clear()
            val take = minOf(buffer.remaining(), pcm.size - offset)
            buffer.put(pcm, offset, take)
            active.queueInputBuffer(index, 0, take, presentationTimeUs + offsetUs(offset), 0)
            offset += take
            drain(active)
        }
        drain(active)
    }

    /** Fin de la piste : EOS puis vidage, pour que les dernières syllabes ne soient pas perdues. */
    fun finish() {
        val active = codec ?: return
        if (!inputEnded) {
            val index = runCatching { active.dequeueInputBuffer(TIMEOUT_US) }.getOrDefault(-1)
            if (index >= 0) {
                active.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                inputEnded = true
            }
        }
        repeat(DRAIN_ROUNDS) { if (!drain(active)) return }
    }

    /** Libère le codec ; sans effet s'il n'a jamais démarré. */
    fun close() {
        val active = codec
        codec = null
        if (active == null) return
        runCatching { active.stop() }
        runCatching { active.release() }
    }

    /** Microsecondes écoulées pour `offset` octets de PCM : `bytesPerSecond` en est la règle. */
    private fun offsetUs(offset: Int): Long = offset * 1_000_000L / bytesPerSecond

    /** Vide ce que le codec a prêt ; `false` seulement quand il n'a plus rien. */
    private fun drain(active: MediaCodec): Boolean {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = runCatching { active.dequeueOutputBuffer(info, 0) }.getOrDefault(-1)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    configured = true
                    onFormat(active.outputFormat)
                }
                index >= 0 -> {
                    val buffer = active.getOutputBuffer(index)
                    if (buffer != null && info.size > 0 && !isConfig(info.flags)) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size)
                        buffer.get(bytes)
                        // Un horodatage négatif n'a pas de sens dans un MP4.
                        onSample(bytes, info.presentationTimeUs.coerceAtLeast(0))
                    }
                    active.releaseOutputBuffer(index, false)
                }
                else -> return false
            }
        }
    }

    /** Tampon d'en-tête (`csd-0`) : il part dans le format de piste, pas comme échantillon. */
    private fun isConfig(flags: Int): Boolean =
        flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0

}
