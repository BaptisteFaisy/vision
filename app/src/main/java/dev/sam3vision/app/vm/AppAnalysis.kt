package dev.sam3vision.app.vm

import android.graphics.Bitmap
import dev.sam3vision.app.Phase
import dev.sam3vision.app.UiState
import dev.sam3vision.core.model.Analysis
import dev.sam3vision.core.model.SegObject
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
 * objet. Seul un échec **LLM** fait basculer la vue en [Phase.Failed] — la photo reste
 * en place, donc « Réessayer » peut repartir. C'est cette distinction que vérifient
 * les tests.
 */
class AppAnalysis(
    private val state: () -> UiState,
    private val update: StateUpdate,
    private val llm: (Settings) -> LlmClient,
    private val sam: (Settings) -> SamClient?,
    private val overlays: suspend (List<SegObject>) -> List<Bitmap>,
    private val announce: (List<SegObject>, Settings) -> Unit,
) {

    /** Analyse la photo courante (son base64) ; l'état porte le résultat ou l'échec. */
    suspend fun analyze(encoded: String) {
        val settings = state().settings
        AnalyzeUseCase(llm(settings), sam(settings), maxObjects = settings.objectsOnScreen)
            .invoke(encoded)
            .fold(onSuccess = { ready(settings, it) }, onFailure = { failed(it) })
    }

    /** Analyse reçue : masques, note de segmentation, chat remis à zéro, puis annonce. */
    private suspend fun ready(settings: Settings, analysis: Analysis) {
        val drawn = overlays(analysis.objects)
        update {
            it.copy(
                analysis = analysis,
                overlays = drawn,
                phase = Phase.Ready,
                segmentationActive = settings.segmentationEnabled && analysis.objects.isNotEmpty(),
                segmentationNote = AppStatusText.segmentationNote(settings, analysis),
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