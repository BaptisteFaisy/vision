package dev.sam3vision.app.voicenav

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import dev.sam3vision.app.navigation.navFixOf
import dev.sam3vision.app.perms.hasPerm
import dev.sam3vision.core.nav.NavFix

/**
 * Position du mode navigation : écoute GPS + réseau ([LocationManager], sans
 * Play Services — même motif que `VoiceNavTracker` et `SharePosition`) et
 * publie chaque point à l'écran carte.
 *
 * Séparé du pilote d'itinéraire à dessein : la carte s'ouvre **sans**
 * destination, donc sans moteur à démarrer. Inerte avant [start] ; [stop]
 * coupe tout. Le premier point publié est la dernière position connue, pour
 * que la carte ne reste pas vide le temps du premier fix.
 *
 * Sans permission ou sans fournisseur, [start] rend `false` et publie `null`
 * une fois : l'écran écrit « position indisponible » au lieu d'attendre.
 */
class MapPositionWatcher(
    context: Context,
    private val onFix: (NavFix?) -> Unit,
) {

    private val appContext = context.applicationContext
    private val locations: LocationManager? =
        appContext.getSystemService(LocationManager::class.java)

    private val listener = FixListener()

    /** Vrai entre [start] et [stop] : un second [start] ne réabonne pas. */
    var listening: Boolean = false
        private set

    /**
     * Démarre l'écoute. `false` = aucune position ne viendra (permission
     * refusée, aucun fournisseur) : l'écran affiche l'indisponibilité.
     */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (listening) return true
        if (!hasLocationPermission()) {
            onFix(null)
            return false
        }
        lastKnown()?.let(onFix)
        listening = listen(LocationManager.GPS_PROVIDER) or listen(LocationManager.NETWORK_PROVIDER)
        if (!listening) onFix(null)
        return listening
    }

    /** Coupe l'écoute (sortie de l'écran carte). Idempotent. */
    fun stop() {
        if (!listening) return
        listening = false
        runCatching { locations?.removeUpdates(listener) }
    }

    /** Abonne un fournisseur ; absent, refusé ou sans gestionnaire = `false`. */
    private fun listen(provider: String): Boolean {
        val manager = locations ?: return false
        return try {
            manager.requestLocationUpdates(provider, MAP_GPS_MS, 0f, listener, Looper.getMainLooper())
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            // Pas de GPS sur l'appareil : le réseau suffit pour situer la carte.
            false
        }
    }

    /**
     * Dernière position connue (GPS puis réseau), `null` sans fix utilisable.
     *
     * `getLastKnownLocation` exige la permission : elle est vérifiée une fois
     * dans [start] (aucun appel n'arrive ici sans elle), comme dans
     * `VoiceNavTracker`. `runCatching` ne suffit pas à lint — il ne reconnaît
     * qu'un `try`/`catch` de `SecurityException` explicite — d'où la
     * suppression, identique à celle des autres lecteurs GPS du projet.
     */
    @SuppressLint("MissingPermission")
    private fun lastKnown(): NavFix? {
        val manager = locations ?: return null
        val known = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstNotNullOfOrNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }
        return known?.let(::fixOf)
    }

    private fun fixOf(location: Location): NavFix = navFixOf(
        lat = location.latitude,
        lon = location.longitude,
        accuracyM = location.accuracy,
        hasAccuracy = location.hasAccuracy(),
        atMs = location.time,
    )

    private fun hasLocationPermission(): Boolean =
        hasPerm(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPerm(appContext, Manifest.permission.ACCESS_COARSE_LOCATION)

    private inner class FixListener : LocationListener {
        override fun onLocationChanged(location: Location) = onFix(fixOf(location))

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    private companion object {
        /** Cadence de la carte : une position toutes les 2 s, comme le guidage. */
        const val MAP_GPS_MS = 2_000L
    }
}
