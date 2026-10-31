package dev.sam3vision.app.vm

import dev.sam3vision.core.model.Analysis
import dev.sam3vision.core.settings.Settings
import java.io.File

/**
 * Tous les textes que l'écran principal écrit dans `statusLine` ou `phase`, en un seul
 * endroit : une phrase n'est écrite qu'une fois et les tests la comparent à l'identique.
 * Découper le ViewModel ne doit rien changer de ce que lit l'utilisateur.
 */
object AppStatusText {

    /** Photo impossible à décoder. */
    fun imageUnreadable(file: File): String = "Unreadable image: $file"

    /** Échec d'analyse sans message d'erreur exploitable. */
    const val ANALYSIS_FAILED = "analysis failed"

    const val SETTINGS_SAVED = "Settings saved."

    const val LLM_TESTING = "Testing LLM…"

    /** Verdict du test LLM : la description tronquée tient sur une ligne. */
    fun llmOk(description: String): String = "LLM OK — \"${description.take(70)}\""

    fun llmFailed(error: Throwable): String = "LLM failed — ${error.message}"

    const val SAM_NO_URL = "SAM failed — no base URL entered."

    fun samTesting(regionCode: String): String = "Testing SAM… ($regionCode)"

    const val SAM_OK = "SAM OK — endpoint reachable."
    const val SAM_HEALTH_FALSE = "SAM failed — /health answered false."

    fun samFailed(error: Throwable): String = "SAM failed — ${error.message}"

    const val ASR_NO_ENGINE = "ASR failed — no dictation engine (Settings)."
    const val ASR_TESTING = "Testing ASR…"

    fun asrOk(text: String): String = "ASR OK — endpoint reachable (${text.ifBlank { "empty text" }.take(40)})"

    fun asrFailed(error: Throwable): String = "ASR failed — ${error.message}"

    /** Question restée sans réponse : le motif du LLM, jamais un écran figé. */
    fun questionFailed(error: Throwable): String = "Question unanswered: ${error.message ?: "error"}"

    /** Question sans photo : le refus local dit quoi faire, comme le direct. */
    const val ASK_NO_PHOTO = "No photo — take a photo before asking your question"

    /** Motif d'une synthèse échouée sans message. */
    const val VOICE_UNAVAILABLE = "voice unavailable"

    fun playbackFailed(error: Throwable): String = "cannot play: ${error.message ?: "error"}"

    /**
     * Note affichée sous la photo, `null` quand la segmentation a donné ses masques.
     * Les trois motifs disent quoi faire, pas seulement que c'est vide : un échec SAM
     * nomme la GPU injoignable au lieu de « aucun objet » (même motif que le direct).
     */
    fun segmentationNote(settings: Settings, analysis: Analysis, samFailure: Throwable? = null): String? = when {
        !settings.segmentationEnabled -> "Segmentation disabled — enter a SAM endpoint in Settings."
        samFailure != null -> samDownNote(samFailure.message ?: "unknown failure")
        analysis.objects.isEmpty() -> "No object segmented on this image."
        else -> null
    }

    /** Photo décrite sans masques parce que SAM n'a pas répondu : le motif, et la suite. */
    fun samDownNote(reason: String): String =
        "SAM detector unreachable ($reason) — photo described without segmentation"
}