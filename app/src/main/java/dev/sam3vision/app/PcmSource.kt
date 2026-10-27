package dev.sam3vision.app

/**
 * Contrat d'un micro pour la dictée : ouvrir, couper en rendant le PCM capté, jeter.
 *
 * Deux implémentations seulement — [DictationRecorder] (micro du téléphone,
 * `AudioRecord`) et `GlassesAudio` (piste audio du flux lunettes) — et un seul
 * transcripteur, `MuseTranscript` : la règle de transcription n'est écrite qu'une fois.
 */
interface PcmSource {

    /** Ouvre la source ; `false` si elle est indisponible (permission, matériel, flux arrêté). */
    fun start(): Boolean

    /** Coupe la source et rend le PCM 16 bits mono 16 kHz capté (vide si rien). */
    fun stopAndCollect(): ByteArray

    /** Jette la capture sans transcription. */
    fun abort() {
        stopAndCollect()
    }
}