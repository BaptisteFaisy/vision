package dev.sam3vision.app.speech

import dev.sam3vision.core.speech.SpeechLanguage
import java.io.File

/**
 * Pack voix embarqué : clips MP3 ElevenLabs rejoués sans réseau ni latence.
 *
 * Le dossier ([dirFor]) est propre à la voix réglée (comme les annonces du
 * mode tap) : changer de voix change de dossier, jamais de clip d'une autre
 * voix. `clipFor` relit le cache (bundled ou synthèse runtime) puis installe
 * le clip embarqué (`res/raw`) quand la voix réglée est celle du pack
 * (`packVoiceId`, `assets/vp_voice.txt`) ; `put` met en cache une synthèse
 * runtime. Purge : [CLIP_TTL_MS], comme les annonces.
 *
 * 100 % injecté (`filesDir`, lecteurs) : testé en JVM sans Android.
 */
class VoicePack(
    private val filesDir: File,
    private val packVoiceId: () -> String?,
    private val bundledClip: (fileName: String) -> ByteArray?,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    /**
     * Clip frais pour ([id], [language]) sous la voix [voiceId] : cache
     * d'abord, clip embarqué ensuite (voix du pack uniquement), sinon `null`
     * (l'appelant synthétise en borné, puis [put]).
     */
    fun clipFor(id: String, language: SpeechLanguage, voiceId: String): File? {
        val file = File(dirFor(voiceId), fileNameFor(id, language))
        if (fresh(file)) return file
        if (packVoiceId()?.trim() != voiceId.trim()) return null
        return bundledClip(file.name)?.let { writeClip(file, it) }
    }

    /** Met en cache une synthèse runtime (voix courante, dossier dédié). */
    fun put(id: String, language: SpeechLanguage, voiceId: String, bytes: ByteArray?): File? {
        if (bytes == null || bytes.isEmpty()) return null
        return writeClip(File(dirFor(voiceId), fileNameFor(id, language)), bytes)
    }

    /** Dossier des clips de [voiceId] (8 premiers, comme les annonces). */
    fun dirFor(voiceId: String): File =
        File(filesDir, dirNameFor(voiceId.trim()))

    private fun fresh(file: File): Boolean =
        file.isFile && file.length() > 0 && nowMs() - file.lastModified() < CLIP_TTL_MS

    private fun writeClip(file: File, bytes: ByteArray): File {
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return file
    }

    companion object {
        /** Durée de vie d'un clip en cache (30 j, comme les annonces tap). */
        const val CLIP_TTL_MS: Long = 30L * 24L * 60L * 60L * 1000L

        /** `vp_<id>_<fr|en>.mp3` (noms `res/raw` : minuscules, chiffres, `_`). */
        fun fileNameFor(id: String, language: SpeechLanguage): String =
            "vp_${id}_${if (language == SpeechLanguage.En) "en" else "fr"}.mp3"

        /** `voicepack-<8 premiers de la voix>` (défaut si voix vide). */
        fun dirNameFor(voiceId: String): String =
            "voicepack-" + voiceId.take(8).ifBlank { "defaut" }
    }
}
