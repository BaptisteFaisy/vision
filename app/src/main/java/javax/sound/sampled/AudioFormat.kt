package javax.sound.sampled

/**
 * Reproduit la surface de `javax.sound.sampled.AudioFormat` utilisée par jump3r
 * (port Java de LAME). Android ne livre pas `javax.sound.sampled` : cette stub
 * minimaliste, strictement limitée à ce que `LameEncoder` invoque (vérifié au
 * bytecode : getChannels, getEncoding, getSampleRate, getSampleSizeInBits,
 * isBigEndian, properties, un seul constructeur), permet l'encodage MP3 sur
 * Android. Sur JVM desktop, le JDK remplace cette classe par la sienne si les
 * deux sont présentes — sémantiquement identique ici.
 */
class AudioFormat(
    private val encoding0: Encoding,
    private val sampleRate0: Float,
    private val sampleSizeInBits0: Int,
    private val channels0: Int,
    private val frameSize0: Int,
    private val frameRate0: Float,
    private val bigEndian0: Boolean,
    private val props: Map<String, Any>,
) {

    class Encoding(val name: String) {
        override fun toString(): String = name

        override fun equals(other: Any?): Boolean = other is Encoding && other.name == name

        override fun hashCode(): Int = name.hashCode()
    }

    fun getEncoding(): Encoding = encoding0

    fun getSampleRate(): Float = sampleRate0

    fun getSampleSizeInBits(): Int = sampleSizeInBits0

    fun getChannels(): Int = channels0

    fun isBigEndian(): Boolean = bigEndian0

    /** Valeurs de propriété de service, non utilisées par notre encodage. */
    fun properties(): Map<String, Any> = props
}
