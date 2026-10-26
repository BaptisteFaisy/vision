package dev.sam3vision.core.audio

/**
 * Conteneur WAV minimal (RIFF/WAVE, PCM entier) — le format réclamé par l'API
 * Meta de transcription : `audioEncoding: "WAV"`, PCM 16 bits mono 16 kHz.
 * Pur Kotlin : testable en JVM, aucune dépendance Android.
 */
object Wav {
    /** Fréquence d'échantillonnage acceptée par Meta (16 ou 24 kHz). */
    const val SAMPLE_RATE = 16_000

    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16

    /** Octets par échantillon (PCM 16 bits). */
    const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8

    private const val HEADER_BYTES = 44

    /** En-tête RIFF/WAVE de 44 octets annonçant [pcmBytes] octets de données. */
    fun header(
        pcmBytes: Int,
        sampleRate: Int = SAMPLE_RATE,
        channels: Int = CHANNELS,
    ): ByteArray = ByteArray(HEADER_BYTES).apply {
        putAscii(0, "RIFF")
        putIntLe(4, 36 + pcmBytes)
        putAscii(8, "WAVE")
        putAscii(12, "fmt ")
        putIntLe(16, 16)
        putShortLe(20, 1)
        putShortLe(22, channels)
        putIntLe(24, sampleRate)
        putIntLe(28, sampleRate * channels * BYTES_PER_SAMPLE)
        putShortLe(32, channels * BYTES_PER_SAMPLE)
        putShortLe(34, BITS_PER_SAMPLE)
        putAscii(36, "data")
        putIntLe(40, pcmBytes)
    }

    /** PCM brut (16 bits, [channels] canaux) -> fichier WAV complet. */
    fun pcm16(
        pcm: ByteArray,
        sampleRate: Int = SAMPLE_RATE,
        channels: Int = CHANNELS,
    ): ByteArray = header(pcm.size, sampleRate, channels) + pcm

    /** Durée en millisecondes d'un PCM 16 bits, arrondie vers le bas. */
    fun durationMs(pcmBytes: Int, sampleRate: Int = SAMPLE_RATE, channels: Int = CHANNELS): Long =
        pcmBytes.toLong() * 1000L / (sampleRate.toLong() * channels * BYTES_PER_SAMPLE)

    private fun ByteArray.putAscii(offset: Int, text: String) {
        text.forEachIndexed { i, c -> this[offset + i] = c.code.toByte() }
    }

    private fun ByteArray.putIntLe(offset: Int, value: Int) {
        for (i in 0 until 4) this[offset + i] = ((value shr (8 * i)) and 0xFF).toByte()
    }

    private fun ByteArray.putShortLe(offset: Int, value: Int) {
        for (i in 0 until 2) this[offset + i] = ((value shr (8 * i)) and 0xFF).toByte()
    }
}