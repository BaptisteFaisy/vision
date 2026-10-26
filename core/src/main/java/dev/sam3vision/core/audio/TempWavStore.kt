package dev.sam3vision.core.audio

import java.io.File

/**
 * Dictées en WAV temporaires : un fichier par enregistrement dans [dir], les [keep]
 * plus récents sont conservés et le reste est purgé. Le format reste celui de [Wav]
 * (PCM 16 bits mono 16 kHz), donc consommable tel quel par une transcription — et
 * lisible à l'œil en recette (`scripts/verify-longpress-port.sh` lit le dossier).
 *
 * Aucune dépendance Android : testable en JVM, sans appareil.
 */
class TempWavStore(private val dir: File, private val keep: Int = KEEP_RECENT) {

    private var sequence = 0

    /** Écrit le WAV complet et rend le fichier ; dossier inaccessible -> [Result.failure]. */
    fun write(pcm: ByteArray, sampleRate: Int = Wav.SAMPLE_RATE): Result<File> = runCatching {
        if (!dir.isDirectory && !dir.mkdirs()) error("dossier inaccessible : $dir")
        val target = File(dir, "dictee-${System.currentTimeMillis()}-${sequence++}.wav")
        target.writeBytes(Wav.pcm16(pcm, sampleRate))
        prune()
        target
    }

    /** Les WAV rangés, du plus récent au plus ancien (liste vide si le dossier manque). */
    fun recent(): List<File> = dir.listFiles()
        ?.filter { file -> file.isFile && file.name.endsWith(WAV_SUFFIX) }
        ?.sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
        .orEmpty()

    /** Chemin du dernier fichier écrit, ou null si aucune dictée n'a encore été rangée. */
    fun latest(): File? = recent().firstOrNull()

    private fun prune() {
        recent().drop(keep.coerceAtLeast(1)).forEach { file -> runCatching { file.delete() } }
    }

    companion object {
        /** Cinq dictées suffisent à rejouer la dernière ; au-delà, c'est du cache mort. */
        const val KEEP_RECENT = 5
        private const val WAV_SUFFIX = ".wav"
    }
}