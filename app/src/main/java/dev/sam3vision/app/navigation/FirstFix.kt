package dev.sam3vision.app.navigation

import android.annotation.SuppressLint
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** Attente bornée d'un premier fix frais : le réseau répond en secondes, un GPS froid est plus long. */
const val FIRST_FIX_TIMEOUT_MS = 15_000L

/**
 * Premier fix frais (GPS ou réseau) : le premier arrivé gagne, l'écoute est
 * coupée aussitôt. `null` sans gestionnaire, sans provider actif, sans
 * abonnement accepté, ou sans fix dans [timeoutMs] — jamais d'exception
 * (permission révoquée entre-temps, provider manquant : `false`/`null`).
 *
 * L'appelant a vérifié la permission avant : comme [VoiceNavTracker], la
 * demande est couverte par le lint (`MissingPermission`), pas re-vérifiée ici.
 * À appeler depuis une coroutine : l'attente suspend, sans bloquer de thread.
 */
@SuppressLint("MissingPermission")
suspend fun awaitFirstFix(
    manager: LocationManager?,
    timeoutMs: Long = FIRST_FIX_TIMEOUT_MS,
): Location? {
    if (manager == null || timeoutMs <= 0) return null
    // Localisation coupée (aucun provider actif) : attendre ne servirait à rien.
    if (!anyProviderEnabled(manager)) return null
    val first = CompletableDeferred<Location>()
    val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            first.complete(location)
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }
    val gps = subscribe(manager, LocationManager.GPS_PROVIDER, listener)
    val net = subscribe(manager, LocationManager.NETWORK_PROVIDER, listener)
    if (!gps && !net) return null
    return try {
        withTimeout(timeoutMs) { first.await() }
    } catch (_: TimeoutCancellationException) {
        null
    } finally {
        runCatching { manager.removeUpdates(listener) }
    }
}

/** Un provider au moins est actif : sinon aucun fix ne viendra jamais. */
private fun anyProviderEnabled(manager: LocationManager): Boolean =
    runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false) ||
        runCatching { manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)

/**
 * Abonne un fournisseur (`true` si accepté), même motif que
 * [VoiceNavTracker] : provider absent ou refusé = `false`, l'autre peut suffire.
 */
private fun subscribe(
    manager: LocationManager,
    provider: String,
    listener: LocationListener,
): Boolean =
    try {
        manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
        true
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }
