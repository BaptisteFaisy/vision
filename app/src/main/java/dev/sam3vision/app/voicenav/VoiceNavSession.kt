package dev.sam3vision.app.voicenav

import dev.sam3vision.app.navigation.NavDestination
import dev.sam3vision.app.navigation.NavigationLauncher
import dev.sam3vision.app.speech.NavigationAnnouncer
import dev.sam3vision.app.speech.NavigationPhrases
import dev.sam3vision.app.speech.VoiceNavAnnouncer
import dev.sam3vision.core.nav.DestinationQuery
import dev.sam3vision.core.nav.GuidanceUpdate
import dev.sam3vision.core.nav.RouteSummary
import dev.sam3vision.core.nav.StopGuidanceParser
import dev.sam3vision.core.nav.initialGuidance
import dev.sam3vision.core.usecase.NavigationRouter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Charge le résumé d'un itinéraire pour l'écran de confirmation.
 * L'implémentation est fournie par l'intégrateur (moteur Valhalla, …) ;
 * le défaut [UnavailableRouteLoader] échoue en français et l'écran montre
 * l'adresse seule. Ne doit jamais lever (voir [VoiceNavSession]).
 */
typealias RouteLoader = suspend (destination: String) -> Result<RouteSummary>

/** Chargeur par défaut : résumé indisponible, l'adresse reste affichable. */
val UnavailableRouteLoader: RouteLoader = {
    Result.failure(IllegalStateException("résumé d'itinéraire indisponible"))
}

/**
 * Écrans de la navigation vocale : repos, confirmation (adresse + résumé),
 * guidage actif (instruction, distances, progression).
 */
sealed interface VoiceNavUiState {
    data object Idle : VoiceNavUiState

    data class Confirming(
        val destination: String,
        val summary: RouteSummary?,
        val loading: Boolean,
        val error: String?,
    ) : VoiceNavUiState

    data class Guiding(
        val destination: String,
        val summary: RouteSummary?,
        val update: GuidanceUpdate,
    ) : VoiceNavUiState
}

/**
 * Session de navigation vocale : entrée dictée → confirmation → guidage.
 *
 * Même motif que `NavigationHook` : cette classe ne fait que déléguer —
 * [router] (transcript → demande, `null` si pas une navigation),
 * [launcher] (destination → `Result`), [announcer] + [voice] (phrases TTS),
 * [routeLoader] (destination → résumé). Elle-même ne parse rien d'autre que
 * l'ordre stop ([StopGuidanceParser]), ne lance rien, ne synthétise rien.
 *
 * Contrat :
 * - [onVoiceTranscript] blanc ou sans intention → `false`, l'appelant
 *   poursuit son chemin normal (question LLM) ; intention reconnue (routeur
 *   ou [extraParsers]) → écran de confirmation + annonce « Destination : X »,
 *   `true` (consommé) ;
 * - ordre stop en session ([Confirming] ou [Guiding]) → retour au repos +
 *   annonce « Guidage arrêté », `true` ; hors session → `false` ;
 * - [confirmDestination] : guidage actif → moteur embarqué ([engineStart])
 *   puis repli Maps ; écran de guidage + annonce « Navigation vers X » dans
 *   les deux cas ; guidage éteint → fiche lieu + annonce « Voici la fiche
 *   de X » + repos ; échec → motif affiché + annonce ;
 * - [onGuidanceUpdate] : l'intégrateur pousse l'état du moteur ; arrivée
 *   signalée → annonce « Arrivée à destination » + repos.
 *
 * Ne lève jamais : un collaborateur qui jetterait est replié (routeur qui
 * jette = pas d'intention, chargeur/lanceur qui jette = échec annoncé).
 */
class VoiceNavSession(
    private val launcher: NavigationLauncher,
    private val guidanceEnabled: () -> Boolean,
    private val announcer: NavigationAnnouncer,
    private val voice: VoiceNavAnnouncer,
    private val routeLoader: RouteLoader = UnavailableRouteLoader,
    private val router: NavigationRouter = NavigationRouter(),
    /**
     * Formes hors routeur (« direction X », `VoiceDestinationParser`) :
     * essayées quand le routeur rend `null`, normalisées avant d'ouvrir
     * la confirmation. Vide = routeur seul (comportement d'origine).
     */
    private val extraParsers: List<(String) -> String?> = emptyList(),
    /**
     * Démarrage du moteur embarqué (destination, résumé) : `true` = guidé
     * in-app (le moteur parle, pas d'annonce ici), `false`/`null` = repli
     * Google Maps. `null` = Maps direct (comportement d'origine).
     */
    private val engineStart: (suspend (destination: String, summary: RouteSummary?) -> Boolean)? = null,
    /** Notifié à chaque retour au repos (le pilote coupe le moteur). */
    private val onSessionIdle: () -> Unit = {},
) {

    private val _state = MutableStateFlow<VoiceNavUiState>(VoiceNavUiState.Idle)

    /** État courant des écrans (repos, confirmation, guidage). */
    val state: StateFlow<VoiceNavUiState> = _state

    /**
     * Entrée vocale : route le transcript dicté vers la session.
     * À appeler depuis le pipeline de dictée **avant** le chemin question →
     * LLM (même place que `NavigationHook.onTranscript`).
     *
     * @return `true` si le transcript était un ordre de navigation (consommé).
     */
    suspend fun onVoiceTranscript(text: String): Boolean {
        if (text.isBlank()) return false
        if (runCatching { StopGuidanceParser.isStop(text) }.getOrDefault(false)) {
            return stopFromVoice()
        }
        val request = runCatching { router.route(text) }.getOrNull()
        if (request != null) {
            openConfirmation(request.destination)
            return true
        }
        openConfirmation(parseExtra(text) ?: return false)
        return true
    }

    /** Formes hors routeur : parsées brutes, puis normalisées comme le routeur. */
    private fun parseExtra(text: String): String? {
        for (parse in extraParsers) {
            val raw = runCatching { parse(text) }.getOrNull()?.takeIf { it.isNotBlank() } ?: continue
            val clean = runCatching { DestinationQuery.normalize(raw) }.getOrNull()?.getOrNull()
            if (!clean.isNullOrBlank()) return clean
        }
        return null
    }

    /** Ouvre la confirmation et y charge le résumé (annonce « Destination : X »). */
    private suspend fun openConfirmation(destination: String) {
        _state.value = VoiceNavUiState.Confirming(destination, null, true, null)
        // `routeLoader` est `suspend` (`runCatching` ne l'est pas) : try/catch direct.
        val loaded = try {
            routeLoader(destination)
        } catch (t: Exception) {
            Result.failure(t)
        }
        loaded.fold(
            onSuccess = { _state.value = VoiceNavUiState.Confirming(destination, it, false, null) },
            onFailure = { _state.value = VoiceNavUiState.Confirming(destination, null, false, summaryError(it)) },
        )
        voice.announceHeard(destination)
    }

    /** Bouton « Démarrer » : lance le guidage (ou la fiche lieu) depuis la confirmation. */
    suspend fun confirmDestination() {
        val current = _state.value as? VoiceNavUiState.Confirming ?: return
        if (current.loading) return
        if (guidanceEnabled()) {
            confirmGuided(current)
        } else {
            confirmPlace(current)
        }
    }

    private suspend fun confirmGuided(current: VoiceNavUiState.Confirming) {
        val engine = engineStart
        if (engine != null) {
            val started = try {
                engine(current.destination, current.summary)
            } catch (_: Exception) {
                false
            }
            if (started) {
                // Le moteur parle (« Navigation vers X ») : pas d'annonce ici.
                _state.value = VoiceNavUiState.Guiding(
                    current.destination,
                    current.summary,
                    departureUpdate(current),
                )
                return
            }
        }
        val outcome = runCatching {
            launcher.launch(NavDestination.Query(current.destination))
        }.getOrElse { Result.failure(it) }
        outcome.fold(
            onSuccess = {
                _state.value = VoiceNavUiState.Guiding(
                    current.destination,
                    current.summary,
                    departureUpdate(current),
                )
                announcer.announceStarted(current.destination)
            },
            onFailure = {
                _state.value = current.copy(loading = false, error = NavigationPhrases.GUIDANCE_UNAVAILABLE)
                announcer.announceGuidanceUnavailable()
            },
        )
    }

    private suspend fun confirmPlace(current: VoiceNavUiState.Confirming) {
        val outcome = runCatching {
            launcher.openPlace(current.destination)
        }.getOrElse { Result.failure(it) }
        outcome.fold(
            onSuccess = {
                goIdle()
                voice.announcePlace(current.destination)
            },
            onFailure = {
                _state.value = current.copy(loading = false, error = NavigationPhrases.GUIDANCE_UNAVAILABLE)
                announcer.announceGuidanceUnavailable()
            },
        )
    }

    /** Bouton « Annuler » : ferme la confirmation, retour au repos, sans annonce. */
    fun cancelConfirmation() {
        if (_state.value is VoiceNavUiState.Confirming) {
            goIdle()
        }
    }

    /** Retour au repos : le pilote est notifié (il coupe le moteur). */
    private fun goIdle() {
        _state.value = VoiceNavUiState.Idle
        runCatching { onSessionIdle() }
    }

    /** Bouton « Arrêter » : ferme le guidage + annonce « Guidage arrêté ». */
    suspend fun stopGuidance() {
        if (_state.value is VoiceNavUiState.Guiding) {
            goIdle()
            voice.announceStopped()
        }
    }

    /** Ordre stop dicté : ne consomme que si une session est ouverte. */
    private suspend fun stopFromVoice(): Boolean {
        if (_state.value !is VoiceNavUiState.Guiding &&
            _state.value !is VoiceNavUiState.Confirming
        ) {
            return false
        }
        goIdle()
        voice.announceStopped()
        return true
    }

    /**
     * L'intégrateur pousse ici l'état du moteur d'itinéraire (instruction,
     * distances, progression). Arrivée signalée → annonce + repos.
     */
    suspend fun onGuidanceUpdate(update: GuidanceUpdate) {
        val current = _state.value as? VoiceNavUiState.Guiding ?: return
        if (update.arrived) {
            goIdle()
            voice.announceArrived()
        } else {
            _state.value = current.copy(update = update)
        }
    }
}

/** Premier état de guidage : résumé entier, ou cap sur la destination seule. */
private fun departureUpdate(current: VoiceNavUiState.Confirming): GuidanceUpdate =
    current.summary?.let(::initialGuidance)
        ?: GuidanceUpdate("Direction ${current.destination}", 0, 0, 0)

/** Texte d'échec du chargeur : son message, ou le repli français. */
private fun summaryError(cause: Throwable): String {
    val detail = cause.message?.trim().orEmpty()
    return detail.ifEmpty { "résumé d'itinéraire indisponible" }
}
