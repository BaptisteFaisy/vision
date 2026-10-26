package dev.sam3vision.app

import dev.sam3vision.core.net.SpeechClient
import dev.sam3vision.core.usecase.DictationUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * **Muse Transcript** — moteur Meta `muse-voice-transcribe-1.0`.
 *
 * Classe unique qui encapsule « audio → texte » pour l'app : elle garde le micro
 * ouvert tant qu'aucun [stop] n'est demandé, accumule le PCM en mémoire par chunks
 * (jamais de fichier complet sur disque, aucune limite de durée) et n'envoie
 * l'audio au moteur qu'au relâchement.
 *
 * Toute la mécanique métier (WAV, taille minimale, texte vide) reste dans
 * [DictationUseCase] : cette classe n'est que le point d'entrée nommé du moteur.
 */
class MuseTranscript(private val recorder: DictationRecorder = DictationRecorder()) {

    /** Ouvre le micro ; `false` si le micro est indisponible (permission, matériel). */
    fun start(): Boolean = recorder.start()

    /** Coupe le micro puis transcrit le PCM capté (I/O hors du thread principal). */
    suspend fun stop(speech: SpeechClient): Result<String> = withContext(Dispatchers.IO) {
        DictationUseCase(speech).invoke(recorder.stopAndCollect())
    }

    /** Jette l'enregistrement en cours sans transcription (arrêt, changement d'état). */
    fun abort() {
        recorder.stopAndCollect()
    }
}