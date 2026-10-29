package dev.sam3vision.app.voicenav

import dev.sam3vision.app.navigation.NavDestination
import dev.sam3vision.app.navigation.NavigationLauncher
import dev.sam3vision.app.navigation.TravelMode
import dev.sam3vision.app.speech.NavigationAnnouncer
import dev.sam3vision.app.speech.NavigationPhrases
import dev.sam3vision.app.speech.VoiceNavAnnouncer
import dev.sam3vision.core.nav.ConfirmAnswer
import dev.sam3vision.core.nav.ConfirmAnswerParser
import dev.sam3vision.core.nav.DestinationQuery
import dev.sam3vision.core.nav.GuidanceUpdate
import dev.sam3vision.core.nav.MapModeCommand
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

    /**
     * Mode navigation sans destination : la carte et la position GPS à
     * l'écran, ouverte par la voix (`MapModeOrder`, « navigation »,
     * « carte », « où suis-je »). Une destination dictée depuis cet état
     * ouvre la confirmation comme d'habitude ; « quitte la carte » ou
     * « arrête la navigation » referme.
     */
    data object Map : VoiceNavUiState
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
 *   ou [extraParsers]) → `true` (consommé) et, si [autoStartOnVoice] est
 *   vrai (câblage iSee), démarrage direct sans bouton : moteur embarqué si
 *   possible, sinon Maps piéton ;
 * - [autoStartOnVoice] faux → écran de confirmation + question vocale
 *   (« Do you really want to go to X? … », [ConfirmAnswerParser] : « yes »
 *   = [confirmDestination], « no » = annuler) ;
 * - repli Maps toujours piéton ([TravelMode.WALK]) et **sans** état
 *   [VoiceNavUiState.Guiding] : iSee ne montre jamais un faux guidage quand
 *   c'est Maps qui guide ;
 * - ordre stop en session ([Confirming] ou [Guiding]) → retour au repos +
 *   annonce « Guidage arrêté », `true` ; sur [Arrived] → fermé sans annonce
 *   (l'arrivée est déjà dite), `true` ; hors session → `false` ;
 * - [confirmDestination] : guidage actif → moteur embarqué ([engineStart])
 *   puis repli Maps ; écran de guidage + annonce « Navigation vers X » dans
 *   les deux cas ; guidage éteint → fiche lieu + annonce « Voici la fiche
 *   de X » + repos ; échec → motif affiché + annonce ; hors [Confirming]
 *   (dont [Arrived]) → rien ;
 * - [onGuidanceUpdate] : l'intégrateur pousse l'état du moteur ; arrivée
 *   signalée → écran [Arrived] + annonce « Arrivée à destination » (unique :
 *   un second `arrived` est ignoré, on n'est plus en [Guiding]) ;
 *   [dismissArrived] referme l'arrivée vers le repos (le pilote coupe le
 *   moteur à ce moment-là, pas à l'arrivée).
 *
 * - [enterMap] / [leaveMap] : mode navigation (carte + position GPS, sans
 *   destination). [enterMap] ne vole aucun état ouvert (guidage, confirmation,
 *   arrivée) ; [leaveMap] rend le repos sans annonce (l'appelant parle). En
 *   [VoiceNavUiState.Map], une destination dictée ouvre la confirmation et
 *   « quitte la carte » / un ordre stop referme sans « Guidage arrêté ».
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
     * Google Maps. `null` = Maps direct.
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
        if (runCatching { StopGuidanceParser.isStop(text) }.getOrDefault(false)) {
            return stopFromVoice()
        }
        if (_state.value is VoiceNavUiState.Map &&
            runCatching { MapModeCommand.isLeaveOrder(text) }.getOrDefault(false)
        ) {
            leaveMap()
            return true
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
     * Démarrage direct : résumé chargé d'abord (l'état initial affiche ses
     * totaux, jamais des zéros), puis moteur embarqué s'il peut démarrer,
     * sinon Maps piéton. Aucun écran de confirmation n'est ouvert et
     * **aucune annonce ici** : le moteur dit « Navigation vers X », le
     * repli Maps le dit aussi — une seule voix, jamais deux annonces qui
     * se chevauchent.
     */
    private suspend fun startFromVoice(destination: String) {
        if (!guidanceEnabled()) {
            openPlace(destination)
            return
        }
        val summary = loadSummaryResult(destination).getOrNull()
        if (startEngine(destination, summary)) {
            _state.value = guiding(destination, summary)
            return
        }
        launchMaps(destination)
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

    /** Confirmation manuelle : moteur si possible, sinon Maps piéton + repos. */
    private suspend fun confirmGuided(current: VoiceNavUiState.Confirming) {
        if (startEngine(current.destination, current.summary)) {
            _state.value = guiding(current.destination, current.summary)
            return
        }
        launchMaps(current.destination, current)
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

    /**
     * Repli Maps piéton. Succès : la session revient au repos, donc aucun
     * écran de guidage iSee fantôme. Échec : motif sur la confirmation si
     * elle existe, sinon annonce seule.
     */
    private suspend fun launchMaps(
        destination: String,
        confirmation: VoiceNavUiState.Confirming? = null,
    ) {
        val outcome = runCatching {
            launcher.launch(NavDestination.Query(destination), TravelMode.WALK)
        }.getOrElse { Result.failure(it) }
        outcome.fold(
            onSuccess = {
                announcer.announceStarted(destination)
                goIdle()
            },
            onFailure = {
                fail(confirmation)
                announcer.announceGuidanceUnavailable()
            },
        )
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

    /**
     * Entrée en mode navigation : la carte et la position, sans destination.
     * Ne vole aucun état ouvert — `false` si une confirmation, un guidage ou
     * une arrivée est déjà à l'écran (l'appelant ne parle alors pas).
     */
    fun enterMap(): Boolean {
        if (_state.value !is VoiceNavUiState.Idle) return false
        _state.value = VoiceNavUiState.Map
        return true
    }

    /**
     * Fermeture du mode navigation (bouton « Quitter », retour système ou
     * ordre dicté) : retour au repos, sans annonce — c'est l'appelant qui
     * parle ([MapModeText]). `false` si la carte n'était pas ouverte.
     */
    fun leaveMap(): Boolean {
        if (_state.value !is VoiceNavUiState.Map) return false
        goIdle()
        return true
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
        if (_state.value is VoiceNavUiState.Map) {
            // La carte se ferme sans « Guidage arrêté » : aucun guidage n'était actif.
            goIdle()
            return true
        }
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
 * zéros présentés comme des distances (« Dans 0 m »).
 */
private fun initialUpdate(destination: String, summary: RouteSummary?): GuidanceUpdate =
    summary?.let(::initialGuidance)
        ?: GuidanceUpdate("Calcul de l'itinéraire vers $destination…", 0, 0, 0)

/** Texte d'échec du chargeur : son message, ou le repli français. */
private fun summaryError(cause: Throwable): String {
    val detail = cause.message?.trim().orEmpty()
    return detail.ifEmpty { "résumé d'itinéraire indisponible" }
}
