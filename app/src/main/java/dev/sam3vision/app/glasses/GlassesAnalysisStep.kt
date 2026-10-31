package dev.sam3vision.app.glasses

import dev.sam3vision.core.model.Analysis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Ce qu'une frame a produit dans la chaîne : une phrase à dire, ou la raison du silence.
 *
 * [Dit] porte exactement le texte remis à la voix (déjà nettoyé par `AnalyzeUseCase`).
 */
sealed interface FrameOutcome {

    data class Dit(val description: String) : FrameOutcome

    /** Analyse en échec ou description vide : rien n'est prononcé, rien ne remonte. */
    data class Muet(val raison: String) : FrameOutcome
}

/**
 * Maillon « frame → analyse → voix », partagé par le banc et le collecteur de
 * production ([GlassesFrameCollector]), sorti de l'activité pour être jouable
 * sur JVM : la frame part au pipeline de `:core` (`AnalyzeUseCase`, donc SAM
 * puis LLM), et seule une description non vide atteint [say].
 *
 * `analyze` est passé en fonction (et non en `AnalyzeUseCase` concret) pour que le
 * test puisse aussi éprouver la branche « description vide » sans réseau ; les
 * appelants branchent `AnalyzeUseCase::invoke` (banc : voix lunettes, production :
 * haut-parleur).
 */
class GlassesAnalysisStep(
    private val analyze: suspend (String) -> Result<Analysis>,
    private val say: suspend (String) -> String? = { null },
) {

    /**
     * Analyse [frame] hors du main et annonce la description. Aucune exception ne
     * remonte : un échec réseau devient [FrameOutcome.Muet] avec un message court.
     */
    suspend fun onFrame(frame: JpegFrame, log: (String) -> Unit): FrameOutcome {
        log("frame ${frame.width}x${frame.height} — ${frame.base64.length / 1024} KB base64")
        val outcome = withContext(Dispatchers.IO) { analyze(frame.base64) }
        val analysis = outcome.getOrNull()
        if (analysis == null || analysis.description.isBlank()) {
            return FrameOutcome.Muet(logCause(outcome.exceptionOrNull(), log))
        }
        log("objects: ${analysis.objects.map { it.label }.distinct().take(6)}")
        say(analysis.description)?.let { log("voice: $it") }
        return FrameOutcome.Dit(analysis.description)
    }
}

/**
 * Journalise l'échec avec la **classe** de l'exception et sa **cause**, puis rend le
 * message court qui sert de [FrameOutcome.Muet]. C'est ce détail — l'`IOException`
 * de connexion portant un `UnknownHostException` — qui a mis au jour le DNS du
 * téléphone ; un `message` seul ne le disait pas.
 */
internal fun logCause(error: Throwable?, log: (String) -> Unit): String {
    if (error == null) {
        log("analysis failed: empty description")
        return "empty description"
    }
    val cause = error.cause
    val detail = cause?.let { " — cause: ${it.javaClass.simpleName}: ${it.message}" }.orEmpty()
    log("analysis failed: ${error.javaClass.simpleName}: ${error.message}$detail")
    return error.message ?: "empty description"
}