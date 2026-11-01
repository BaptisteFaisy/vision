package dev.sam3vision.app.sim

import android.annotation.SuppressLint
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import android.util.Log

/** Tag logcat de la simulation : `adb logcat -s iSeeSim` suffit à suivre la recette. */
const val SIM_LOG_TAG = "iSeeSim"

/**
 * **Debug uniquement** — installe un *test provider* par fournisseur écouté par iSee
 * ([PROVIDERS]) puis y pousse les positions simulées.
 *
 * `LocationManager.addTestProvider` « replaces any provider with the same name that exists
 * prior to this call » (javadoc AOSP) : le test provider `gps` prend donc la place du vrai
 * GPS, et les `requestLocationUpdates(GPS_PROVIDER)` de `VoiceNavTracker` reçoivent nos
 * positions. Le provider `network` est simulé pour la même raison : iSee écoute les deux
 * flux, et un fix réseau réel (position par défaut de l'émulateur) intercalé entre deux fixes
 * simulés ferait sauter le guidage ailleurs — le moteur, lui, ne jette que les fixes plus
 * anciens (`VoiceNavEngine.onFix`) ou trop imprécis.
 *
 * Une installation refusée (appop `android:mock_location` absent) est journalisée une fois
 * puis n'est plus retentée : aucun broadcast ne doit faire tomber l'app.
 */
class SimLocationSink(
    private val manager: LocationManager?,
    private val clock: () -> Long = { SystemClock.elapsedRealtimeNanos() },
) {
    /** Providers déjà installés (valeur `false` = refus, on ne réessaie plus). */
    private val installed = HashMap<String, Boolean>()

    /**
     * Pousse [fix] dans chaque test provider disponible.
     *
     * @return le nombre de fournisseurs servis (0 = simulation inutilisable, la raison est
     *   dans logcat sous [SIM_LOG_TAG]).
     */
    fun push(fix: SimFix): Int {
        val locations = manager ?: run {
            Log.e(SIM_LOG_TAG, "LocationManager absent : position ignorée")
            return 0
        }
        var served = 0
        for (provider in PROVIDERS) {
            if (install(locations, provider) && send(locations, provider, fix)) served++
        }
        return served
    }

    /** Retire les test providers : les vrais GPS/réseau reprennent la main. */
    fun clear() {
        val locations = manager ?: return
        for (provider in PROVIDERS) {
            if (installed.remove(provider) != true) continue
            try {
                locations.removeTestProvider(provider)
                Log.i(SIM_LOG_TAG, "test provider « $provider » retiré")
            } catch (error: RuntimeException) {
                Log.e(SIM_LOG_TAG, "retrait de « $provider » refusé : ${error.message}")
            }
        }
    }

    /**
     * Installe le test provider une seule fois ; rend `false` si c'est impossible.
     *
     * `WrongConstant` est éteint localement : les deux `int` attendus sont exactement
     * `ProviderProperties.POWER_USAGE_LOW` (1) et `ACCURACY_FINE` (1), écrits en clair parce
     * que ces constantes datent de l'API 31 alors que `minSdk` vaut 29.
     */
    @SuppressLint("WrongConstant")
    private fun install(locations: LocationManager, provider: String): Boolean {
        installed[provider]?.let { return it }
        return try {
            // Mêmes caractéristiques qu'un GPS : satellite, altitude, vitesse, cap.
            locations.addTestProvider(
                provider, false, true, false, false, true, true, true, POWER_LOW, ACCURACY_FINE,
            )
            installed[provider] = true
            Log.i(SIM_LOG_TAG, "test provider « $provider » installé")
            true
        } catch (denied: SecurityException) {
            installed[provider] = false
            Log.e(
                SIM_LOG_TAG,
                "test provider « $provider » refusé (appop mock_location) : lancer " +
                    "`adb shell appops set dev.sam3vision.app android:mock_location allow`",
            )
            false
        } catch (error: RuntimeException) {
            installed[provider] = false
            Log.e(SIM_LOG_TAG, "test provider « $provider » impossible : ${error.message}")
            false
        }
    }

    /** Envoie une position ; rend `true` si le système l'a acceptée. */
    private fun send(locations: LocationManager, provider: String, fix: SimFix): Boolean = try {
        locations.setTestProviderLocation(provider, locationOf(provider, fix))
        Log.i(
            SIM_LOG_TAG,
            "fix $provider lat=${fix.lat} lon=${fix.lon} acc=${fix.accM} " +
                "bearing=${fix.bearingDeg} speed=${fix.speedMps}",
        )
        true
    } catch (error: RuntimeException) {
        Log.e(SIM_LOG_TAG, "fix $provider rejeté : ${error.message}")
        false
    }

    /**
     * `Location` complète : depuis Android 4.4 et pour toute cible ≥ 29,
     * `setTestProviderLocation` lève « missing timestamp or accuracy » sur une position
     * incomplète — d'où `time` et `accuracy` toujours renseignés.
     */
    private fun locationOf(provider: String, fix: SimFix): Location = Location(provider).apply {
        latitude = fix.lat
        longitude = fix.lon
        accuracy = fix.accM
        time = if (fix.atMs > 0L) fix.atMs else System.currentTimeMillis()
        elapsedRealtimeNanos = clock()
        fix.bearingDeg?.let { bearing = it }
        fix.speedMps?.let { speed = it }
    }

    companion object {
        /** Les deux fournisseurs écoutés par `VoiceNavTracker` (2 s, 0 m). */
        val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

        /** `ProviderProperties.POWER_USAGE_LOW` / `ACCURACY_FINE` : constantes d'API 31. */
        private const val POWER_LOW = 1
        private const val ACCURACY_FINE = 1
    }
}
