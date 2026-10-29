package dev.sam3vision.app.isee.vm

import dev.sam3vision.app.isee.IseeUiState
import dev.sam3vision.app.navigation.NavDestination
import dev.sam3vision.app.navigation.NavigationLauncher
import dev.sam3vision.app.navigation.TravelMode
import dev.sam3vision.app.speech.NavigationAnnouncer
import dev.sam3vision.app.speech.NavigationPhrases
import dev.sam3vision.core.nav.DestinationQuery
import dev.sam3vision.core.usecase.NavigationRouter
import dev.sam3vision.core.voicenav.VoiceDestinationParser

/**
 * Hook dictée → navigation : appelé à la réception du transcript, avant le chemin
 * question → Room → LLM.
 *
 * Le hook ne fait que déléguer : [router] (transcript → demande de guidage, `null`
 * si pas d'intention), [launcher] (destination → `Result`), [announcer] (phrases
 * TTS [NavigationPhrases]). Lui-même ne parse rien, ne lance rien, ne synthétise rien.
 *
 * Contrat :
 * - pas d'intention navigation (routeur `null`, repli `direction X` / sans
 *   préposition `null`, transcript blanc) → `false`, l'appelant poursuit le
 *   chemin question normal ;
 * - intention reconnue → lancement tenté ([NavigationLauncher.launch] si
 *   [guidanceEnabled], sinon [NavigationLauncher.openPlace]), annonce TTS dans
 *   tous les cas, `true` (consommé — même si le lancement échoue, le texte
 *   n'est pas une question) ;
 * - succès → statut effacé (la navigation prend l'écran) + annonce du départ ;
 * - échec → motif affiché en statut + annonce de l'échec.
 *
 * Ne lève jamais : un collaborateur qui jetterait est replié (routeur qui jette
 * = pas d'intention, launcher qui jette = échec annoncé).
 */
class NavigationHook(
    private val router: NavigationRouter = NavigationRouter(),
    private val launcher: NavigationLauncher,
    private val guidanceEnabled: () -> Boolean,
    private val announcer: NavigationAnnouncer,
    private val update: (((IseeUiState) -> IseeUiState)) -> Unit,
) {

    /**
     * Route le transcript dicté vers la navigation.
     *
     * @return `true` si le transcript était une intention navigation (consommé),
     * `false` sinon (laisser suivre le chemin question).
     */
    suspend fun onTranscript(text: String): Boolean {
        if (text.isBlank()) return false
        val request = runCatching { router.route(text) }.getOrNull()
        // Routeur `null` (dont exception repliée) : seules les formes hors
        // routeur sont essayées — jamais ses propres formes (confinement).
        val destination = request?.destination ?: extraDestination(text) ?: return false
        val outcome = runCatching {
            if (guidanceEnabled()) {
                launcher.launch(NavDestination.Query(destination), TravelMode.WALK)
            } else {
                launcher.openPlace(destination)
            }
        }.getOrElse { Result.failure(it) }
        outcome.fold(
            onSuccess = {
                update { it.copy(status = null) }
                announcer.announceStarted(destination)
            },
            onFailure = {
                update { it.copy(status = NavigationPhrases.GUIDANCE_UNAVAILABLE) }
                announcer.announceGuidanceUnavailable()
            },
        )
        return true
    }

    /**
     * Formes hors routeur (« direction X », « emmène-moi X » sans
     * préposition) : mêmes formes que les `extraParsers` de la session
     * in-app, normalisées comme le routeur — le handoff Maps les ouvre
     * au lieu de les laisser décrire la scène.
     */
    private fun extraDestination(text: String): String? {
        val raw = runCatching { VoiceDestinationParser.parseExtra(text) }.getOrNull()
        if (raw.isNullOrBlank()) return null
        return runCatching { DestinationQuery.normalize(raw) }.getOrNull()?.getOrNull()
    }
}
