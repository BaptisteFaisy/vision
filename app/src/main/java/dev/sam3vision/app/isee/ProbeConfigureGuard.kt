package dev.sam3vision.app.isee

import dev.sam3vision.app.TRANSPORT_WS
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.live.LivePrompts
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Configuration posée par [LiveProbe.configure], champ par champ : à part car
 * `LiveProbe.kt` est au plafond des 500 lignes (même raison que `ProbeWiring.kt`).
 */
internal data class ProbeAppliedConfig(
    val endpoint: String,
    val prompts: List<String>,
    val guide: Boolean,
    val lens: LensSpec,
    val distancesOn: Boolean,
    val maxObjects: Int,
    val language: SpeechLanguage,
    val crossingActive: Boolean,
    val modelsLine: String,
    val collectCrops: Boolean,
    val followed: List<String>,
)

/**
 * Faut-il appliquer [next] ? Non si une session vit déjà avec exactement la
 * même configuration (resync réglages, rotation, retour premier plan) : la
 * session, le pipeline et l'état affiché survivent — plus de « saute ».
 * Sinon mémorise et rend vrai. Toujours vrai sans session (premier appel,
 * après `stop`) : la séance suivante rouvre via `configure`.
 */
internal fun LiveProbe.shouldApply(next: ProbeAppliedConfig): Boolean {
    if (session != null && next == appliedConfig) return false
    appliedConfig = next
    return true
}

/**
 * État frais d'une (vraie) reconfiguration : endpoint, prompts normalisés et
 * mode posés, compteurs et affichages remis à zéro, GPU gardé. Extrait de
 * `LiveProbe.configure` (plafond 50 lignes par fonction).
 */
internal fun LiveProbe.freshConfiguredState(
    clean: String,
    prompts: List<String>,
    modelsLine: String,
    followed: List<String>,
    crossingActive: Boolean,
    cold: Boolean,
): LiveProbeState = LiveProbeState(
    endpoint = clean,
    gpu = state.value.gpu,
    modelsLine = modelsLine,
    conceptsSent = LivePrompts.normalize(prompts),
    followed = followed,
    transport = TRANSPORT_WS,
    timeoutSec = when {
        clean.isBlank() -> 0L
        cold -> COLD_TIMEOUT_SECONDS
        else -> DEFAULT_TIMEOUT_SECONDS
    },
    error = if (clean.isBlank()) "no endpoint (Settings)" else null,
    crossingActive = crossingActive,
)
