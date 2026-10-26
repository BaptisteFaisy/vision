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
    fun imageUnreadable(file: File): String = "Image illisible : $file"

    /** Échec d'analyse sans message d'erreur exploitable. */
    const val ANALYSIS_FAILED = "analyse impossible"

    const val SETTINGS_SAVED = "Réglages enregistrés."

    const val LLM_TESTING = "Test du LLM en cours…"

    /** Verdict du test LLM : la description tronquée tient sur une ligne. */
    fun llmOk(description: String): String = "LLM OK — « ${description.take(70)} »"

    fun llmFailed(error: Throwable): String = "LLM KO — ${error.message}"

    const val SAM_NO_URL = "SAM KO — aucune base URL renseignée."

    fun samTesting(regionCode: String): String = "Test SAM en cours… ($regionCode)"

    const val SAM_OK = "SAM OK — endpoint joignable."
    const val SAM_HEALTH_FALSE = "SAM KO — /health a répondu faux."

    fun samFailed(error: Throwable): String = "SAM KO — ${error.message}"

    const val ASR_NO_ENGINE = "ASR KO — aucun moteur de dictée (Réglages)."
    const val ASR_TESTING = "Test ASR en cours…"

    fun asrOk(text: String): String = "ASR OK — endpoint joignable (${text.ifBlank { "texte vide" }.take(40)})"

    fun asrFailed(error: Throwable): String = "ASR KO — ${error.message}"

    /** Question restée sans réponse : le motif du LLM, jamais un écran figé. */
    fun questionFailed(error: Throwable): String = "Question sans réponse : ${error.message ?: "erreur"}"

    /** Motif d'une synthèse échouée sans message. */
    const val VOICE_UNAVAILABLE = "voix indisponible"

    fun playbackFailed(error: Throwable): String = "lecture impossible : ${error.message ?: "erreur"}"

    /**
     * Note affichée sous la photo, `null` quand la segmentation a donné ses masques.
     * Les deux motifs disent quoi faire, pas seulement que c'est vide.
     */
    fun segmentationNote(settings: Settings, analysis: Analysis): String? = when {
        !settings.segmentationEnabled -> "Segmentation désactivée — renseigne un endpoint SAM dans Réglages."
        analysis.objects.isEmpty() -> "Aucun objet segmenté sur cette image."
        else -> null
    }
}