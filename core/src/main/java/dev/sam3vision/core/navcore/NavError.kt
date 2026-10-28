package dev.sam3vision.core.navcore

import dev.sam3vision.core.nav.DestinationError
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Erreurs typées du parcours navigation vocale, directement affichables dans
 * l'UI. Jamais levées : renvoyées par [Result.failure] (voir [NavResults]).
 *
 * Chaque erreur porte [retryable] : `true` quand une nouvelle tentative a un
 * sens (panne réseau), consommé par [RetryPolicy.shouldRetry]. Les rédacteurs
 * frères (géocodage, itinéraire) convertissent leurs échecs via [of] au lieu
 * d'inventer leurs propres types — zéro dépendance en retour, ce package ne
 * connaît qu'eux par leurs exceptions standard.
 */
sealed class NavError(message: String, val retryable: Boolean) : Exception(message) {

    /** Appui long refusé : le micro est pris par la vidéo. Non réessayable. */
    class MicBusy : NavError(NavMessages.MIC_BUSY_VIDEO, false)

    /** Meta puis Scribe ont échoué ([NavMessages.asrBothFailed]). Non réessayable. */
    class AsrFailed(detail: String) : NavError(detail, false)

    /**
     * Rien à traiter (vide, blanc, ponctuation seule). Réutilise le message de
     * [DestinationError.BlankQuery] au lieu de le dupliquer. Non réessayable.
     */
    class EmptyDestination(cause: DestinationError) : NavError(navLabelOf(cause), false)

    /** Adresse introuvable ou service de géocodage en panne. Réessayable. */
    class GeocodeFailed(val query: String, detail: String) :
        NavError("géocodage impossible pour « ${query.trim()} » : $detail", true)

    /** Calcul d'itinéraire impossible (backend injoignable, pas de trajet…). Réessayable. */
    class RouteFailed(detail: String) : NavError("itinéraire impossible : $detail", true)

    /** Panne réseau générique (connexion refusée, DNS…). Réessayable. */
    class Network(detail: String) : NavError("réseau injoignable : $detail", true)

    /** Délai dépassé ([what] = étape, ex. « réseau »). Réessayable. */
    class Timeout(what: String) : NavError("délai dépassé : $what", true)

    /**
     * Lancement du guidage impossible. Le message affiché est le contrat
     * [NavMessages.GUIDANCE_UNAVAILABLE], le technique reste dans [detail].
     * Non réessayable (aucune app capable).
     */
    class LaunchFailed(val detail: String) : NavError(NavMessages.GUIDANCE_UNAVAILABLE, false)

    /** Échec inattendu, message technique replié. Non réessayable. */
    class Unknown(detail: String) : NavError(detail, false)

    companion object {
        /**
         * Convertit une exception quelconque en [NavError] typée. Ne lève jamais :
         * l'inconnu devient [Unknown]. Idempotente sur [NavError].
         */
        fun of(error: Throwable): NavError = when (error) {
            is NavError -> error
            is DestinationError -> EmptyDestination(error)
            is SocketTimeoutException -> Timeout("réseau")
            is IOException -> Network(navLabelOf(error))
            else -> Unknown(navLabelOf(error))
        }
    }
}

/**
 * Libellé affichable d'une exception : son message, ou son nom de classe en
 * repli (jamais vide, jamais « null »).
 */
internal fun navLabelOf(error: Throwable): String =
    error.message?.trim().orEmpty().ifBlank { error.javaClass.simpleName }
