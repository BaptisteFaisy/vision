package dev.sam3vision.app.navigation

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import dev.sam3vision.app.NO_ACCURACY_M
import dev.sam3vision.core.nav.NavEvent
import dev.sam3vision.core.nav.NavFix
import dev.sam3vision.core.nav.NavManeuver
import dev.sam3vision.core.nav.NavRoute
import dev.sam3vision.core.nav.NavState
import dev.sam3vision.core.nav.VoiceNavEngine

/**
 * Enveloppe Android du [VoiceNavEngine] : écoute GPS + réseau
 * ([LocationManager], sans Play Services — même motif que
 * [dev.sam3vision.app.SharePosition], cycle de vie indépendant du Direct)
 * et pousse chaque position au moteur, qui répond par [onEvent].
 *
 * Inerte tant qu'aucun écran ne l'utilise : [guide] charge l'itinéraire
 * (construit par `ValhallaRoute.parse` depuis `/route`), [start] écoute,
 * [stop] coupe tout. L'appelant parle les événements
 * ([dev.sam3vision.app.speech.VoiceNavPhrases] + `voice::say`, motif
 * [dev.sam3vision.app.speech.NavigationAnnouncer]) et recharge un
 * itinéraire sur [NavEvent.OffRoute] via [reroute].
 */
class VoiceNavTracker(
    context: Context,
    private val engine: VoiceNavEngine = VoiceNavEngine(),
    private val onEvent: (NavEvent) -> Unit = {},
) {

    private val locations: LocationManager? =
        context.applicationContext.getSystemService(LocationManager::class.java)

    private val listener = FixListener()

    /** État courant du moteur (repos, guidage, écart, arrivé). */
    val state: NavState get() = engine.state

    /** Prochaine manœuvre du moteur (`null` au repos ou après l'arrivée). */
    fun nextManeuver(): NavManeuver? = engine.nextManeuver()

    /**
     * Charge l'itinéraire à guider (validé, comme [VoiceNavEngine.start]).
     * Échec [dev.sam3vision.core.nav.RouteError] si invalide.
     */
    fun guide(route: NavRoute): Result<Unit> = engine.start(route)

    /** Recharge un itinéraire après [NavEvent.OffRoute] (recalcul). */
    fun reroute(route: NavRoute): Result<Unit> = engine.reroute(route)

    /** Démarre l'écoute GPS + réseau ; sans permission, ne fait rien. */
    @SuppressLint("MissingPermission")
    fun start() {
        try {
            locations?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                NAV_GPS_MS,
                0f,
                listener,
                Looper.getMainLooper(),
            )
        } catch (_: SecurityException) {
            return
        } catch (_: IllegalArgumentException) {
            // Pas de GPS sur l'appareil : le réseau suffit pour guider grossièrement.
        }
        try {
            locations?.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                NAV_GPS_MS,
                0f,
                listener,
                Looper.getMainLooper(),
            )
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        }
    }

    /** Coupe l'écoute et oublie l'itinéraire (fin du guidage). */
    fun stop() {
        try {
            locations?.removeUpdates(listener)
        } catch (_: SecurityException) {
        }
        engine.stop()
    }

    /** Pousse la position brute au moteur et publie ses événements. */
    private fun onLocation(location: Location) {
        val fix = navFixOf(
            lat = location.latitude,
            lon = location.longitude,
            accuracyM = location.accuracy,
            hasAccuracy = location.hasAccuracy(),
            atMs = location.time,
        )
        engine.onFix(fix).forEach(onEvent)
    }

    private inner class FixListener : LocationListener {
        override fun onLocationChanged(location: Location) = onLocation(location)

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    private companion object {
        /** Cadence GPS du guidage : une position toutes les 2 s. */
        const val NAV_GPS_MS = 2_000L
    }
}

/**
 * Fix moteur depuis une position brute : sans précision annoncée, repli
 * [NO_ACCURACY_M] (le moteur ignore les fixes trop grossiers). Pure,
 * testable en JVM sans capteur.
 */
fun navFixOf(
    lat: Double,
    lon: Double,
    accuracyM: Float,
    hasAccuracy: Boolean,
    atMs: Long,
): NavFix = NavFix(
    lat = lat,
    lon = lon,
    accM = if (hasAccuracy) accuracyM else NO_ACCURACY_M,
    atMs = atMs,
)
