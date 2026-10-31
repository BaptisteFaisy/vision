package dev.sam3vision.app.voicenav

import dev.sam3vision.app.navigation.NavigationLauncher
import dev.sam3vision.app.speech.NavigationAnnouncer
import dev.sam3vision.app.speech.NavigationPhrases
import dev.sam3vision.app.speech.VoiceNavAnnouncer
import dev.sam3vision.core.nav.ConfirmAnswer
import dev.sam3vision.core.nav.ConfirmAnswerParser
import dev.sam3vision.core.nav.DestinationQuery
import dev.sam3vision.core.nav.GuidanceOrder
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
    Result.failure(IllegalStateException("route summary unavailable"))
}

/**
 * Écrans de la navigation vocale : repos, confirmation (adresse + résumé),
 * guidage actif (instruction, distances, progression), arrivée.
 *
 * Contrat d'état (lot reprise-fond) : la route ajoute la branche
 * `is VoiceNavUiState.Arrived -> ArrivedScreen(state, onNewDestination,
 * onClose)` — `onClose` appelle `session().dismissArrived()`, sans détour
 * par le ViewModel.
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

    data class Arrived(
        val destination: String,
        val summary: RouteSummary?,
    ) : VoiceNavUiState
}

/**
 * Session de navigation vocale : entrée dictée → guidage (ou confirmation
 * si [autoStartOnVoice] est faux).
 *
 * Même motif que `NavigationHook` : cette classe ne fait que déléguer —
 * [router] (transcript → demande, `null` si pas une navigation),
 * [launcher] (destination → `Result`), [announcer] + [voice] (phrases TTS),
 * [routeLoader] (destination → résumé). Elle-même ne parse que l'ordre stop
 * ([StopGuidanceParser]) et le oui/non ([ConfirmAnswerParser]), ne lance
 * rien, ne synthétise rien.
 *
 * Contrat :
 * - [onVoiceTranscript] blanc ou sans intention → `false`, l'appelant
 *   poursuit son chemin normal (question LLM) ; intention reconnue (routeur
 *   ou [extraParsers] ou ordre GPS) → `true` (consommé) et, si
 *   [autoStartOnVoice] est vrai (câblage iSee), démarrage direct sans
 *   bouton ni question : chargement affiché, moteur embarqué, sinon
 *   repos + annonce (jamais d'app externe) ;
 * - [autoStartOnVoice] faux → écran de confirmation + question vocale
 *   (« Do you really want to go to X? … », [ConfirmAnswerParser] : « yes »
 *   = [confirmDestination], « no » = annuler) ;
 * - moteur en panne : pas de repli externe — repos + annonce
 *   d'indisponibilité (le lanceur ne sert qu'à la fiche lieu,
 *   guidage éteint) ;
 * - ordre stop en session ([Confirming] ou [Guiding]) → retour au repos +
 *   annonce « Guidage arrêté », `true` ; sur [Arrived] → fermé sans annonce
 *   (l'arrivée est déjà dite), `true` ; hors session → `false` ;
 * - ordre « désactive le GPS » → session fermée au besoin, fonction éteinte
 *   (réglage persisté), accusé « GPS désactivé », toujours consommé ;
 * - ordre « active le GPS » → fonction rallumée (réglage persisté), accusé
 *   « GPS activé », toujours consommé (le guidage reste à la demande) ;
 * - [confirmDestination] : guidage actif → moteur embarqué ([engineStart])
 *   puis écran de guidage ; moteur en panne → motif sur l'écran + annonce
 *   d'indisponibilité ; guidage éteint → fiche lieu + annonce
 *   « Voici la fiche de X » + repos ; hors [Confirming]
 *   (dont [Arrived]) → rien ;
 * - [onGuidanceUpdate] : l'intégrateur pousse l'état du moteur ; arrivée
 *   signalée → écran [Arrived] + annonce « Arrivée à destination » (unique :
 *   un second `arrived` est ignoré, on n'est plus en [Guiding]) ;
 *   [dismissArrived] referme l'arrivée vers le repos (le pilote coupe le
 *   moteur à ce moment-là, pas à l'arrivée).
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
     * in-app (le moteur parle, pas d'annonce ici), `false`/`null` =
     * indisponible annoncée, sans app externe.
     */
    private val engineStart: (suspend (destination: String, summary: RouteSummary?) -> Boolean)? = null,
    /** Notifié à chaque retour au repos (le pilote coupe le moteur). */
    private val onSessionIdle: () -> Unit = {},
    /**
     * Démarre dès la dictée, sans écran de confirmation ni bouton
     * « Démarrer ». Défaut `false` : l'écran de confirmation reste l'API
     * historique ; `IseeVoiceNav` passe `true` pour l'usage mains libres.
     */
    private val autoStartOnVoice: Boolean = false,
    /** Ordre « désactive le GPS » : persiste l'état guidage éteint ([Settings]). */
    private val onGpsDisabled: suspend () -> Unit = {},
    /** Ordre « active le GPS » : persiste l'état guidage rallumé. */
    private val onGpsEnabled: suspend () -> Unit = {},
) {

    private val _state = MutableStateFlow<VoiceNavUiState>(VoiceNavUiState.Idle)

    /** État courant des écrans (repos, confirmation, guidage, arrivée). */
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
        // Ordres GPS : l'arrêt exige une session ouverte, la désactivation et
        // l'activation sont honorées tout de suite (fonction, pas guidage).
        when (runCatching { StopGuidanceParser.parse(text) }.getOrDefault(GuidanceOrder.NONE)) {
            GuidanceOrder.STOP -> return stopFromVoice()
            GuidanceOrder.DISABLE -> {
                disableFromVoice()
                return true
            }
            GuidanceOrder.ENABLE -> {
                enableFromVoice()
                return true
            }
            GuidanceOrder.NONE -> {}
        }
        val answer = runCatching { ConfirmAnswerParser.parse(text) }.getOrNull()
        if (answer != null && _state.value is VoiceNavUiState.Confirming) {
            return answerFromVoice(answer)
        }
        val destination = destinationOf(text) ?: return false
        if (autoStartOnVoice) {
            startFromVoice(destination)
        } else {
            openConfirmation(destination)
        }
        return true
    }

    /** Routeur d'abord, puis les formes hors routeur (« direction X »). */
    private fun destinationOf(text: String): String? {
        val request = runCatching { router.route(text) }.getOrNull()
        return request?.destination ?: parseExtra(text)
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

    /**
     * Démarrage direct : l'état de chargement s'affiche aussitôt (la boule
     * tourne pendant le calcul), le résumé est chargé d'abord (l'état
     * initial affiche ses totaux, jamais des zéros), puis le moteur
     * embarqué démarre — sans lui, repos + annonce d'indisponibilité,
     * jamais d'app externe. **Aucune annonce ici** en cas de succès : le
     * moteur dit « Navigation vers X » — une seule voix, jamais deux
     * annonces qui se chevauchent. Un ordre stop (ou une autre
     * destination) pendant le chargement annule ce démarrage : l'état
     * courant n'est plus le nôtre, on s'arrête là.
     */
    private suspend fun startFromVoice(destination: String) {
        if (!guidanceEnabled()) {
            openPlace(destination)
            return
        }
        val loading = VoiceNavUiState.Confirming(destination, null, true, null)
        _state.value = loading
        val summary = loadSummaryResult(destination).getOrNull()
        if (_state.value !== loading) return
        if (startEngine(destination, summary)) {
            if (_state.value === loading) _state.value = guiding(destination, summary)
            return
        }
        goIdle()
        announcer.announceGuidanceUnavailable()
    }

    /**
     * Ouvre la confirmation, y charge le résumé, puis pose la question vocale
     * (« Do you really want to go to X? Travel time: Y. Answer yes or no. »,
     * sans le temps si le résumé a échoué — le repli Maps reste possible).
     */
    private suspend fun openConfirmation(destination: String) {
        _state.value = VoiceNavUiState.Confirming(destination, null, true, null)
        val loaded = loadSummaryResult(destination)
        val summary = loaded.getOrNull()
        _state.value = loaded.fold(
            onSuccess = { VoiceNavUiState.Confirming(destination, it, false, null) },
            onFailure = { VoiceNavUiState.Confirming(destination, null, false, summaryError(it)) },
        )
        voice.announceConfirmQuestion(destination, summary?.durationEn())
    }

    /**
     * Réponse vocale sur la confirmation : « yes » = Démarrer (ou patienter
     * si le résumé charge encore), « no » = Annuler + « Cancelled. ».
     */
    private suspend fun answerFromVoice(answer: ConfirmAnswer): Boolean {
        val current = _state.value as? VoiceNavUiState.Confirming ?: return false
        if (answer == ConfirmAnswer.NO) {
            cancelConfirmation()
            voice.announceConfirmCancelled()
            return true
        }
        if (current.loading) {
            voice.announceConfirmLoading()
            return true
        }
        confirmDestination()
        return true
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

    /** Confirmation manuelle : moteur si possible, sinon motif + annonce, jamais Maps. */
    private suspend fun confirmGuided(current: VoiceNavUiState.Confirming) {
        if (startEngine(current.destination, current.summary)) {
            _state.value = guiding(current.destination, current.summary)
            return
        }
        fail(current)
        announcer.announceGuidanceUnavailable()
    }

    /** Tente le moteur embarqué ; `false` si absent, en échec ou s'il jette. */
    private suspend fun startEngine(destination: String, summary: RouteSummary?): Boolean {
        val engine = engineStart ?: return false
        return try {
            engine(destination, summary)
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun confirmPlace(current: VoiceNavUiState.Confirming) {
        openPlace(current.destination, current)
    }

    /** Fiche lieu (guidage éteint) puis repos, ou motif si le lancement échoue. */
    private suspend fun openPlace(
        destination: String,
        confirmation: VoiceNavUiState.Confirming? = null,
    ) {
        val outcome = runCatching {
            launcher.openPlace(destination)
        }.getOrElse { Result.failure(it) }
        outcome.fold(
            onSuccess = {
                goIdle()
                voice.announcePlace(destination)
            },
            onFailure = {
                fail(confirmation)
                announcer.announceGuidanceUnavailable()
            },
        )
    }

    /** Erreur affichable sur la confirmation, ou repos si aucun écran n'est ouvert. */
    private fun fail(confirmation: VoiceNavUiState.Confirming?) {
        if (confirmation == null) {
            goIdle()
        } else {
            _state.value = confirmation.copy(
                loading = false,
                error = NavigationPhrases.GUIDANCE_UNAVAILABLE,
            )
        }
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
        if (_state.value is VoiceNavUiState.Arrived) {
            // Guidage déjà terminé : on referme, sans « Guidage arrêté ».
            goIdle()
            return true
        }
        if (_state.value !is VoiceNavUiState.Guiding &&
            _state.value !is VoiceNavUiState.Confirming
        ) {
            return false
        }
        goIdle()
        voice.announceStopped()
        return true
    }

    /** Bouton « Fermer » : referme l'écran d'arrivée, retour au repos. */
    fun dismissArrived() {
        if (_state.value is VoiceNavUiState.Arrived) {
            goIdle()
        }
    }

    /**
     * Ordre « désactive le GPS » : guidage coupé si besoin (confirmation
     * ou guidage ouverts), fonction éteinte (réglage persisté), accusé
     * « GPS désactivé » — consommé même sans session ouverte.
     */
    private suspend fun disableFromVoice() {
        if (_state.value is VoiceNavUiState.Guiding || _state.value is VoiceNavUiState.Confirming) {
            goIdle()
        }
        runCatching { onGpsDisabled() }
        voice.announceDisabled()
    }

    /**
     * Ordre « active le GPS » : fonction rallumée (réglage persisté), le
     * guidage reprendra à la demande (« je veux aller à X »), accusé dit.
     */
    private suspend fun enableFromVoice() {
        runCatching { onGpsEnabled() }
        voice.announceEnabled()
    }

    /**
     * L'intégrateur pousse ici l'état du moteur d'itinéraire (instruction,
     * distances, progression). Arrivée signalée → écran d'arrivée + annonce.
     */
    suspend fun onGuidanceUpdate(update: GuidanceUpdate) {
        val current = _state.value as? VoiceNavUiState.Guiding ?: return
        if (update.arrived) {
            _state.value = VoiceNavUiState.Arrived(current.destination, current.summary)
            voice.announceArrived()
        } else {
            _state.value = current.copy(update = update)
        }
    }

    /**
     * Charge le résumé via `routeLoader` : ne lève jamais (un jeté devient
     * un échec) ; partagé par la confirmation et le démarrage voix.
     */
    private suspend fun loadSummaryResult(destination: String): Result<RouteSummary> =
        // `routeLoader` est `suspend` (`runCatching` ne l'est pas) : try/catch direct.
        try {
            routeLoader(destination)
        } catch (t: Exception) {
            Result.failure(t)
        }

    /** État de guidage initial : totaux du résumé, ou « calcul en cours ». */
    private fun guiding(destination: String, summary: RouteSummary?): VoiceNavUiState.Guiding =
        VoiceNavUiState.Guiding(destination, summary, initialUpdate(destination, summary))
}

/**
 * Premier état de guidage : résumé entier, ou « calcul en cours » quand le
 * chargeur a échoué mais que le moteur a quand même démarré — jamais des
 * zéros présentés comme des distances (« In 0 m »).
 */
private fun initialUpdate(destination: String, summary: RouteSummary?): GuidanceUpdate =
    summary?.let(::initialGuidance)
        ?: GuidanceUpdate("Calculating the route to $destination…", 0, 0, 0)

/** Texte d'échec du chargeur : son message, ou le repli anglais. */
private fun summaryError(cause: Throwable): String {
    val detail = cause.message?.trim().orEmpty()
    return detail.ifEmpty { "route summary unavailable" }
}
