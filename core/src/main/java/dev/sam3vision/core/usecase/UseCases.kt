package dev.sam3vision.core.usecase

import dev.sam3vision.core.model.Analysis
import dev.sam3vision.core.model.ChatTurn
import dev.sam3vision.core.model.ObjectLimits
import dev.sam3vision.core.model.Role
import dev.sam3vision.core.model.SegmentResult
import dev.sam3vision.core.net.ApiError
import dev.sam3vision.core.net.LlmClient
import dev.sam3vision.core.net.SamClient
import dev.sam3vision.core.text.stripMarkdown
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pipeline photo → (segmentation si un endpoint SAM est configuré) → description LLM.
 * La segmentation est optionnelle et non bloquante : si elle échoue, on décrit quand même.
 */
class AnalyzeUseCase(
    private val llm: LlmClient,
    private val sam: SamClient? = null,
    private val maxLabels: Int = 20,
    private val maxObjects: Int = ObjectLimits.DEFAULT_ON_SCREEN,
    /** Budget SAM : au-delà, on décrit sans labels plutôt que faire attendre la photo. */
    private val samBudgetMs: Long = SAM_BUDGET_MS,
    /**
     * Issue de l'appel SAM, `null` si SAM n'a pas été appelé (pas de client) : la photo
     * sait dire « GPU injoignable » au lieu de « aucun objet ». Un budget dépassé vaut
     * `failure(Timeout)` — la photo n'attend pas, mais l'issue reste un échec daté.
     */
    private val onSamOutcome: ((Result<SegmentResult>?) -> Unit)? = null,
) {
    companion object {
        /** 10 s : un GPU chaud répond en < 2 s ; au-delà, la photo n'attend pas. */
        const val SAM_BUDGET_MS = 10_000L
    }

    suspend fun invoke(jpegB64: String, prompts: List<String>? = null): Result<Analysis> {
        val cap = ObjectLimits.clamp(maxObjects)
        val segmented = sam?.let {
            withTimeoutOrNull(samBudgetMs) { it.segment(jpegB64, prompts) }
                ?: Result.failure(ApiError.Timeout())
        }
        onSamOutcome?.invoke(segmented)
        val objects = segmented?.getOrNull()?.objects.orEmpty().take(cap)
        val labels = objects.map { it.label }.filter { it.isNotBlank() }.distinct().take(maxLabels)
        return llm.describe(jpegB64, labels).map { analysis ->
            analysis.copy(
                description = stripMarkdown(analysis.description),
                tags = analysis.tags.map(::stripMarkdown).filter { it.isNotBlank() },
                objects = objects,
            )
        }
    }
}

/**
 * Question/réponse sur la photo courante. L'historique envoyé au modèle est
 * tronqué aux [MAX_HISTORY_TURNS] derniers tours.
 *
 * [context] (journal caméra, notes) est joint à la question : il permet de
 * répondre sur ce qui a été vu il y a quelques secondes, et pas seulement sur
 * l'image présente.
 */
class AskUseCase(
    private val llm: LlmClient,
    private val maxTurns: Int = MAX_HISTORY_TURNS,
) {
    suspend fun invoke(
        jpegB64: String,
        question: String,
        history: List<ChatTurn>,
        context: String? = null,
    ): Result<String> = llm.ask(jpegB64, question, history.takeLast(maxTurns), context).map { stripMarkdown(it) }

    /** Historique mis à jour après une réponse (borné à 2 × maxTurns messages). */
    fun nextHistory(history: List<ChatTurn>, question: String, answer: String): List<ChatTurn> =
        (history + ChatTurn(Role.USER, question) + ChatTurn(Role.ASSISTANT, answer))
            .takeLast(2 * maxTurns)

    companion object {
        const val MAX_HISTORY_TURNS = 20
    }
}
