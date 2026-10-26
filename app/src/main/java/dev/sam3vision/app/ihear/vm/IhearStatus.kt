package dev.sam3vision.app.ihear.vm

import dev.sam3vision.app.ihear.IhearUiState
import dev.sam3vision.app.ihear.PhotoMeasure

/** La seule façon dont les collaborateurs du ViewModel touchent l'état de l'écran. */
typealias StateUpdate = ((IhearUiState) -> IhearUiState) -> Unit

/**
 * Tous les textes que l'écran iSee peut afficher dans `status`, en un seul endroit :
 * une phrase n'est écrite qu'une fois, et les tests la comparent à l'identique
 * (aucun changement de ce que lit l'utilisateur ne doit passer par une découpe).
 */
object IhearStatusText {

    const val MIC_UNAVAILABLE = "Microphone unavailable"
    const val CAMERA_NOT_READY = "Camera not ready — try again"
    const val BUSY_SPEAKING = "Busy speaking — try again"
    const val NOTHING_HEARD = "Nothing heard"
    const val ANSWER_PENDING = "Answer still coming — ask again after it"
    const val NO_LLM_KEY = "No LLM key configured (Settings)"
    const val RECORDING_LOST = "Recording lost"

    /** Micro jamais ouvert pour cette tenue (relâchement sans début d'enregistrement). */
    const val NOT_RECORDING = "Nothing was recorded"

    /** Aucun moteur de dictée réglé : le message exact affiché après « Dictation: ». */
    const val NO_DICTATION_ENGINE = "No dictation engine configured (Settings)"

    /** Échec de transcription, tel qu'affiché. */
    fun dictationFailed(error: Throwable): String = "Dictation: ${error.message ?: "failed"}"

    /** Échec de synthèse vocale, tel qu'affiché. */
    fun voiceFailed(error: Throwable): String = "Voice: ${error.message ?: "unavailable"}"

    /** Échec de la question au LLM, tel qu'affiché. */
    fun questionFailed(error: Throwable): String = "Question failed: ${error.message ?: "error"}"
}

/**
 * Motif affiché quand la photo du double tap reste sans pastilles, `null` quand elle
 * en a : chaque échec est dit dans le chat au lieu de rester muet.
 */
fun photoMeasureMessage(outcome: PhotoMeasure): String? = when (outcome) {
    is PhotoMeasure.Measured -> null
    PhotoMeasure.OverlayOff -> "Photo: distance overlay off (Settings)"
    PhotoMeasure.NoEndpoint -> "Photo: SAM endpoint missing (Settings → Region)"
    PhotoMeasure.Unreadable -> "Photo: image unreadable"
    is PhotoMeasure.Failed -> "Photo analysis failed: ${outcome.reason}"
    PhotoMeasure.NoObjects -> "Photo: no measurable objects"
}