package dev.sam3vision.app.navigation

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.sam3vision.core.nav.MapsUri

/** Package de l'app Google Maps, cible du premier essai. */
const val MAPS_PACKAGE = "com.google.android.apps.maps"

/**
 * Lanceur d'itinéraires externes : app Google Maps, sinon lien universel Maps,
 * sinon navigateur. Jamais d'exception — tout échec remonte en [Result.failure].
 */
interface NavigationLauncher {
    /** Guidage toujours piéton : le GPS d'iSee ne propose ni voiture ni transport. */
    fun launch(destination: NavDestination, mode: TravelMode = TravelMode.WALK): Result<NavRoute>

    /** Fiche du lieu, sans lancer de guidage (réglage « Guidage GPS » éteint). */
    fun openPlace(query: String): Result<NavRoute>
}

/** Destination d'un itinéraire : coordonnées GPS ou requête libre (« Gare de Lyon »). */
sealed interface NavDestination {
    data class Coordinates(val latitude: Double, val longitude: Double) : NavDestination

    data class Query(val query: String) : NavDestination
}

/**
 * Mode de transport. [guided] = `false` quand le schéma `google.navigation`
 * n'a pas d'équivalent guidé (transports : l'URL suffit, on saute l'app).
 */
enum class TravelMode(val guided: Boolean, internal val core: MapsUri.Mode) {
    DRIVE(true, MapsUri.Mode.Driving),
    WALK(true, MapsUri.Mode.Walking),
    BIKE(true, MapsUri.Mode.Bicycling),
    TRANSIT(false, MapsUri.Mode.Transit),
}

/** Par quelle porte l'itinéraire est parti : app Maps, lien universel, ou navigateur. */
sealed interface NavRoute {
    data object MapsApp : NavRoute
    data object UniversalLink : NavRoute
    data object Browser : NavRoute
}

/** Échecs du lanceur, messages déjà affichables (même motif que `ApiError`). */
sealed class NavLaunchError(message: String) : Exception(message) {
    class InvalidDestination(detail: String) : NavLaunchError("destination invalide : $detail")

    data object NoHandler :
        NavLaunchError("aucune application pour ouvrir l'itinéraire (ni Maps, ni navigateur)")
}

/**
 * URI `google.navigation:q=…` de l'app Maps. Bâtie par [MapsUri] (implémentation
 * unique des URIs, côté `:core`) — ce fichier n'en duplique aucune.
 */
fun navigationAppUri(destination: NavDestination, mode: TravelMode = TravelMode.WALK): Result<String> =
    when (destination) {
        is NavDestination.Query -> MapsUri.navigation(destination.query.trim(), mode.core)
        is NavDestination.Coordinates ->
            MapsUri.navigation(destination.latitude, destination.longitude, mode.core)
    }

/** URL universelle `https://www.google.com/maps/dir/?api=1…` (repli sans l'app). */
fun universalMapsUrl(destination: NavDestination, mode: TravelMode = TravelMode.WALK): Result<String> =
    when (destination) {
        is NavDestination.Query -> MapsUri.directions(destination.query.trim(), mode.core)
        is NavDestination.Coordinates ->
            MapsUri.directions(destination.latitude, destination.longitude, mode.core)
    }

/** URL de fiche lieu `https://www.google.com/maps/search/?api=1…` (sans guidage). */
fun placeUrl(query: String): Result<String> = MapsUri.search(query.trim())

/** Rejette une destination vide ou hors limites ; `null` = valide. Ne lance jamais. */
internal fun validate(destination: NavDestination): NavLaunchError.InvalidDestination? =
    when (destination) {
        is NavDestination.Query ->
            if (destination.query.isBlank()) {
                NavLaunchError.InvalidDestination("requête vide")
            } else {
                null
            }
        is NavDestination.Coordinates -> when {
            !destination.latitude.isFinite() || destination.latitude !in -90.0..90.0 ->
                NavLaunchError.InvalidDestination(
                    "latitude ${destination.latitude} hors limites (-90..90)",
                )
            !destination.longitude.isFinite() || destination.longitude !in -180.0..180.0 ->
                NavLaunchError.InvalidDestination(
                    "longitude ${destination.longitude} hors limites (-180..180)",
                )
            else -> null
        }
    }

/**
 * Implémentation Android : ACTION_VIEW `google.navigation` ciblant Maps, repli URL
 * universelle (attrapée par Maps s'il est là, sinon le navigateur), repli navigateur
 * explicite ([Intent.CATEGORY_BROWSABLE], que les navigateurs exigent).
 *
 * Aucun `resolveActivity` volontairement : sans entrée `<queries>` dans le manifeste
 * (API 30+), il rendrait `null` même Maps installé — l'échec `ActivityNotFound` du
 * `startActivity` suffit à passer au repli suivant, sans jamais crasher.
 *
 * @param start porte d'injection (tests) : le [Result] dit si l'intent est parti ;
 *   une implémentation qui lancerait au lieu de répondre est aussi rattrapée.
 */
class AndroidNavigationLauncher(
    context: Context,
    private val start: (Intent) -> Result<Unit> = defaultStarter(context.applicationContext),
) : NavigationLauncher {

    override fun launch(destination: NavDestination, mode: TravelMode): Result<NavRoute> {
        validate(destination)?.let { return Result.failure(it) }
        if (mode.guided) {
            val appUri = navigationAppUri(destination, mode).getOrNull()
            if (appUri != null && attempt(mapsAppIntent(appUri))) {
                return Result.success(NavRoute.MapsApp)
            }
        }
        val url = universalMapsUrl(destination, mode).getOrNull()
            ?: return Result.failure(NavLaunchError.NoHandler)
        if (attempt(Intent(Intent.ACTION_VIEW, Uri.parse(url)).withTaskFlag())) {
            return Result.success(NavRoute.UniversalLink)
        }
        val browser = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE).withTaskFlag()
        if (attempt(browser)) return Result.success(NavRoute.Browser)
        return Result.failure(NavLaunchError.NoHandler)
    }

    override fun openPlace(query: String): Result<NavRoute> {
        if (query.isBlank()) {
            return Result.failure(NavLaunchError.InvalidDestination("requête vide"))
        }
        val url = placeUrl(query).getOrNull()
            ?: return Result.failure(NavLaunchError.NoHandler)
        if (attempt(Intent(Intent.ACTION_VIEW, Uri.parse(url)).withTaskFlag())) {
            return Result.success(NavRoute.UniversalLink)
        }
        val browser = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE).withTaskFlag()
        if (attempt(browser)) return Result.success(NavRoute.Browser)
        return Result.failure(NavLaunchError.NoHandler)
    }

    private fun mapsAppIntent(appUri: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(appUri))
            .setPackage(MAPS_PACKAGE).withTaskFlag()

    /**
     * Un essai : `true` si l'intent est parti. `runCatching` couvre aussi une porte
     * [start] injectée qui lancerait au lieu de répondre en [Result].
     */
    private fun attempt(intent: Intent): Boolean =
        runCatching { start(intent) }.getOrNull()?.isSuccess == true
}

/** Lancement depuis un contexte applicatif : il faut un nouveau task à chaque fois. */
private fun Intent.withTaskFlag(): Intent = addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** Porte par défaut : `startActivity` réel, échec (dont Maps absent) en [Result]. */
private fun defaultStarter(appContext: Context): (Intent) -> Result<Unit> = { intent ->
    runCatching { appContext.startActivity(intent) }
}
