package dev.sam3vision.app.vm

import android.graphics.Bitmap
import dev.sam3vision.app.Phase
import dev.sam3vision.app.UiState
import dev.sam3vision.app.samEndpoint
import dev.sam3vision.core.model.Analysis
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.model.SegmentResult
import dev.sam3vision.core.net.LlmClient
import dev.sam3vision.core.net.SamClient
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.usecase.AnalyzeUseCase

/**
 * Le chemin d'une analyse de photo : segmentation SAM (si un endpoint est réglé), puis
 * description LLM. Les deux appels sont menés par `AnalyzeUseCase` de `:core` ; ici on
 * ne fait que traduire le résultat en état d'écran.
 *
 * Un échec **SAM** ne bloque rien : `AnalyzeUseCase` décrit quand même l'image, sans
 * objet — mais la note sous la photo nomme la GPU injoignable au lieu de « aucun
 * objet », et l'issue part au moniteur GPU (`noteSam`). Seul un échec **LLM** fait
 * basculer la vue en [Phase.Failed] — la photo reste en place, donc « Réessayer » peut
 * repartir. C'est cette distinction que vérifient les tests.
 */
class AppAnalysis(
    private val state: () -> UiState,
    private val update: StateUpdate,
    private val llm: (Settings) -> LlmClient,
    private val sam: (Settings) -> SamClient?,
    private val overlays: suspend (List<SegObject>) -> List<Bitmap>,
    private val announce: (List<SegObject>, Settings) -> Unit,
    /** Issue de l'appel SAM vers le moniteur GPU (endpoint appelé + résultat). */
    private val noteSam: (String, Result<SegmentResult>) -> Unit = { _, _ -> },
    /** Jalons de chronologie (`sam`, `analyse`, `overlays`) : inerte par défaut. */
    private val mark: (String) -> Unit = {},
) {

    /** Analyse la photo courante (son base64) ; l'état porte le résultat ou l'échec. */
    suspend fun analyze(encoded: String) {
        val settings = state().settings
        var samFailure: Throwable? = null
        val result = AnalyzeUseCase(
            llm(settings),
            sam(settings),
            maxObjects = settings.objectsOnScreen,
            onSamOutcome = { outcome ->
                if (outcome != null) {
                    samFailure = outcome.exceptionOrNull()
                    noteSam(settings.samEndpoint(), outcome)
                    mark("sam")
                }
            },
        ).invoke(encoded)
        mark("analyse")
        result.fold(onSuccess = { ready(settings, it, samFailure) }, onFailure = { failed(it) })
    }

    /** Analyse reçue : masques, note de segmentation, chat remis à zéro, puis annonce. */
    private suspend fun ready(settings: Settings, analysis: Analysis, samFailure: Throwable?) {
        val drawn = overlays(analysis.objects)
        mark("overlays")
        update {
            it.copy(
                analysis = analysis,
                overlays = drawn,
                phase = Phase.Ready,
                segmentationActive = settings.segmentationEnabled && analysis.objects.isNotEmpty(),
                segmentationNote = AppStatusText.segmentationNote(settings, analysis, samFailure),
                chat = emptyList(),
            )
        }
        announce(analysis.objects, settings)
    }

    private fun failed(error: Throwable) = update {
        it.copy(
            phase = Phase.Failed(error.message ?: AppStatusText.ANALYSIS_FAILED),
            segmentationActive = false,
        )
    }
}