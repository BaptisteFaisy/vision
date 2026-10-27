package dev.sam3vision.core.audio

/** Aperçu borné du micro ; le PCM complet reste réservé à la transcription finale. */
data class PcmPreview(val pcm: ByteArray, val totalBytes: Long, val speaking: Boolean)

/** Six secondes au plus, sans fichier ni deuxième ouverture du micro. */
class PcmPreviewBuffer(
    seconds: Int = 6,
    private val voiceThreshold: Double = 300.0,
) {
    init { require(seconds > 0) }

    private val bytesPerSecond = Wav.SAMPLE_RATE * 2
    private val ring = ByteArray(seconds * bytesPerSecond)
    private var total = 0L
    private var voiceUntil = 0L

    @Synchronized
    fun reset() {
        total = 0L
        voiceUntil = 0L
    }

    @Synchronized
    fun append(bytes: ByteArray, count: Int) {
        require(count in 0..bytes.size)
        val kept = minOf(count, ring.size)
        val offset = count - kept
        val destination = ((total + offset) % ring.size).toInt()
        val first = minOf(kept, ring.size - destination)
        bytes.copyInto(ring, destination, offset, offset + first)
        bytes.copyInto(ring, 0, offset + first, count)
        total += count
        if (rms(bytes, count) >= voiceThreshold) voiceUntil = total + bytesPerSecond * 55 / 100
    }

    @Synchronized
    fun snapshot(): PcmPreview {
        val size = minOf(total, ring.size.toLong()).toInt()
        val start = ((total - size) % ring.size).toInt()
        val first = minOf(size, ring.size - start)
        val pcm = ByteArray(size)
        ring.copyInto(pcm, 0, start, start + first)
        ring.copyInto(pcm, first, 0, size - first)
        return PcmPreview(pcm, total, total > 0 && total < voiceUntil)
    }

    /** Énergie PCM16 signée ; pas d'envoi ASR des périodes silencieuses. */
    private fun rms(bytes: ByteArray, count: Int): Double {
        var energy = 0.0
        var samples = 0
        for (i in 0 until count - 1 step 2) {
            val sample = ((bytes[i].toInt() and 255) or (bytes[i + 1].toInt() shl 8)).toShort().toDouble()
            energy += sample * sample
            samples++
        }
        return if (samples == 0) 0.0 else kotlin.math.sqrt(energy / samples)
    }
}
