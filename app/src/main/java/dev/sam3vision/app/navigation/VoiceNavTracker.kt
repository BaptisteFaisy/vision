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

    @Volatile
    private var listening = false

    /**
     * Dernier fix poussé au moteur (`null` avant la première position) :
     * point de reprise après une coupure d'écoute (fond, perte de focus).
     */
    @Volatile
    var lastFix: NavFix? = null
        private set

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

    /**
     * Démarre l'écoute GPS + réseau.
     *
     * Idempotent : un second appel pendant l'écoute est no-op (pas de
     * double abonnement après un retour de fond).
     *
     * @return `true` si au moins un fournisseur écoute réellement. Sans
     *   permission ou sans fournisseur, rien n'est demandé et `false` est
     *   rendu : l'appelant bascule sur Maps au lieu d'afficher un guidage
     *   qui ne recevrait jamais de position.
     */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (listening) return true
        listening = listen(LocationManager.GPS_PROVIDER) or listen(LocationManager.NETWORK_PROVIDER)
        return listening
    }

    /**
     * Reprend l'écoute après une coupure (fond, perte de focus) quand un
     * itinéraire est en cours ; au repos ou après l'arrivée, ne fait rien.
     * L'itinéraire chargé est conservé : seul le flux GPS reprend.
     */
    @SuppressLint("MissingPermission")
    fun resume() {
        if (shouldResume(listening, state)) start()
    }

    /**
     * Abonne un fournisseur (`true` si accepté) ; provider absent, refusé
     * ou gestionnaire manquant = `false` (l'autre peut suffire à guider).
     */
    private fun listen(provider: String): Boolean {
        val manager = locations ?: return false
        return try {
            manager.requestLocationUpdates(provider, NAV_GPS_MS, 0f, listener, Looper.getMainLooper())
            true
        } catch (_: SecurityException) {
            // Permission refusée : l'autre fournisseur peut encore suffire.
            false
        } catch (_: IllegalArgumentException) {
            // Pas de GPS sur l'appareil : le réseau suffit pour guider grossièrement.
            false
        }
    }

    /** Coupe l'écoute et oublie l'itinéraire (fin du guidage). */
    fun stop() {
        listening = false
        lastFix = null
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
        lastFix = fix
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
 * Reprise d'écoute après une coupure : seulement si le flux est coupé
 * alors qu'un itinéraire est en cours (guidage ou écart) — au repos ou
 * après l'arrivée, rouvrir le GPS ne servirait à rien. Pure, testée en JVM.
 */
fun shouldResume(listening: Boolean, state: NavState): Boolean =
    !listening && (state == NavState.Guiding || state == NavState.OffRoute)

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
