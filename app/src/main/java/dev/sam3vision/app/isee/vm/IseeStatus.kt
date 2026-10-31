package dev.sam3vision.app.isee.vm

import dev.sam3vision.app.isee.IseeUiState

/** La seule façon dont les collaborateurs du ViewModel touchent l'état de l'écran. */
typealias StateUpdate = ((IseeUiState) -> IseeUiState) -> Unit

/**
 * Tous les textes que l'écran iSee peut afficher dans `status`, en un seul endroit :
 * une phrase n'est écrite qu'une fois, et les tests la comparent à l'identique
 * (aucun changement de ce que lit l'utilisateur ne doit passer par une découpe).
 */
object IseeStatusText {

    const val MIC_UNAVAILABLE = "Microphone unavailable"
    const val CAMERA_NOT_READY = "Camera not ready — try again"
    const val NOTHING_HEARD = "Nothing heard"
    const val ANSWER_PENDING = "Answer still coming — ask again after it"
    const val NO_LLM_KEY = "No LLM key configured (Settings)"
    const val RECORDING_LOST = "Recording lost"

    /** La photo du bouton « Picto » a raté : caméra pas prête ou fichier vide. */
    const val PICTO_PHOTO_FAILED = "Photo failed — try again"

    /** Un repère vient d'être validé : la détection le cherche déjà sur la vidéo. */
    const val PICTO_SAVED = "Picto saved"

    /** Micro jamais ouvert pour cette tenue (relâchement sans début d'enregistrement). */
    const val NOT_RECORDING = "Nothing was recorded"

    /** Aucun moteur de dictée réglé : le message exact affiché après « Dictation: ». */
    const val NO_DICTATION_ENGINE = "No dictation engine configured (Settings)"

    /**
     * Étapes affichées pendant une tenue : l'écran dit où il en est au lieu d'un
     * « … » muet — transcription, question au LLM, puis lecture de la réponse.
     */
    const val STEP_TRANSCRIBING = "Transcribing"
    const val STEP_ASKING = "Asking"
    const val STEP_SPEAKING = "Speaking"

    /** Une étape a dépassé son plafond : le réseau est coincé, pas l'app. */
    fun timedOut(step: String): String = "$step timed out — try again"

    /** Échec de transcription, tel qu'affiché. */
    fun dictationFailed(error: Throwable): String =
        if (error is StepTimeout) timedOut(error.step) else "Dictation: ${error.message ?: "failed"}"

    /** Échec de synthèse vocale, tel qu'affiché. */
    fun voiceFailed(error: Throwable): String =
        if (error is StepTimeout) timedOut(error.step) else "Voice: ${error.message ?: "unavailable"}"

    /** Échec de la question au LLM, tel qu'affiché. */
    fun questionFailed(error: Throwable): String =
        if (error is StepTimeout) timedOut(error.step) else "Question failed: ${error.message ?: "error"}"

    /** Échec de génération du pictogramme, tel qu'affiché (message du client). */
    fun pictoFailed(error: Throwable): String =
        if (error is StepTimeout) timedOut(error.step) else "Picto: ${error.message ?: "failed"}"
}

/**
 * Une étape réseau a dépassé son plafond ([step] = le texte affiché pendant
 * l'attente). Les motifs ci-dessus le traduisent en phrase pour l'écran.
 */
class StepTimeout(val step: String) : Exception(step)