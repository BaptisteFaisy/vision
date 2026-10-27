package dev.sam3vision.app

import dev.sam3vision.core.net.SpeechClient
import dev.sam3vision.core.usecase.DictationUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * **Muse Transcript** — moteur Meta `muse-voice-transcribe-1.0`.
 *
 * Classe unique qui encapsule « audio → texte » pour l'app : elle garde la source ouverte
 * tant qu'aucun [stop] n'est demandé, accumule le PCM en mémoire par chunks (jamais de
 * fichier complet sur disque, aucune limite de durée) et n'envoie l'audio au moteur
 * qu'au relâchement.
 *
 * La source est un [PcmSource] : le micro du téléphone ([DictationRecorder], défaut) ou
 * celui des lunettes (`GlassesAudio`). Une seule règle de transcription pour les deux.
 *
 * Toute la mécanique métier (WAV, taille minimale, texte vide) reste dans
 * [DictationUseCase] : cette classe n'est que le point d'entrée nommé du moteur.
 */
class MuseTranscript(private val recorder: PcmSource = DictationRecorder()) {

    /** Ouvre la source ; `false` si elle est indisponible (permission, matériel, flux). */
    fun start(): Boolean = recorder.start()

    /** Coupe la source puis transcrit le PCM capté (I/O hors du thread principal). */
    suspend fun stop(speech: SpeechClient): Result<String> = withContext(Dispatchers.IO) {
        DictationUseCase(speech).invoke(recorder.stopAndCollect())
    }

    /** Jette l'enregistrement en cours sans transcription (arrêt, changement d'état). */
    fun abort() {
        recorder.abort()
    }
}