package dev.sam3vision.app.media

import java.io.File

/**
 * Encodeur MP3 pur Java : PCM 16 bits mono 16 kHz -> fichier MP3 vrai, sans natif.
 * Repose sur jump3r (port Java de LAME) et la stub locale de `javax.sound.sampled`
 * (absente d'Android). Appelé sur un thread d'I/O : l'encodage d'un message vocal
 * de quelques dizaines de secondes reste en dessous d'une seconde.
 */
class PcmToMp3Encoder(
    private val sampleRate: Int = SampleRate.MUSE_HZ,
) {

    /** Fréquences acceptées par le moteur de transcription (et par MP3 en LSF). */
    object SampleRate {
        const val MUSE_HZ = 16_000
    }

    /**
     * Encode [pcm] (mono 16 bits little-endian) vers [out]. `rate` = [sampleRate].
     * Lève [IllegalStateException] si un appel de l'encodeur échoue brutalement.
     */
    fun encode(pcm: ByteArray, out: File, rate: Int = sampleRate) {
        out.parentFile?.mkdirs()
        val sourceFormat = javax.sound.sampled.AudioFormat(
            javax.sound.sampled.AudioFormat.Encoding("PCM_SIGNED"),
            rate.toFloat(),
            16,
            1,
            2,
            rate.toFloat(),
            false,
            emptyMap(),
        )
        // jump3r 1.0.4 mappe les modes explicites en minuscules ("mono", "stereo"…)
        // vers l'enum MPEGMode en majuscules : tout mode explicite lève
        // `No enum constant MPEGMode.<mode>`. AUTO est le seul chemin sain, et il
        // conserve le mono puisque la source déclare un seul canal.
        val encoder = de.sciss.jump3r.lowlevel.LameEncoder(
            sourceFormat,
            DEFAULT_BITRATE_KBPS,
            de.sciss.jump3r.lowlevel.LameEncoder.CHANNEL_MODE_AUTO,
            de.sciss.jump3r.lowlevel.LameEncoder.QUALITY_MIDDLE,
            false,
        )
        val pcmBuffer = ByteArray(encoder.getPCMBufferSize())
        val mp3Buffer = ByteArray(encoder.getMP3BufferSize())
        out.outputStream().use { output ->
            var offset = 0
            while (offset < pcm.size) {
                val len = minOf(pcmBuffer.size, pcm.size - offset)
                System.arraycopy(pcm, offset, pcmBuffer, 0, len)
                val written = encoder.encodeBuffer(pcmBuffer, 0, len, mp3Buffer)
                if (written > 0) output.write(mp3Buffer, 0, written)
                offset += len
            }
            val flushed = encoder.encodeFinish(mp3Buffer)
            if (flushed > 0) output.write(mp3Buffer, 0, flushed)
        }
        encoder.close()
    }

    companion object {
        private const val DEFAULT_BITRATE_KBPS = 96
    }
}
