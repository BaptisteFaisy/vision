package dev.sam3vision.app.navigation

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import androidx.annotation.RequiresApi
import dev.sam3vision.core.nav.GeocodeError
import dev.sam3vision.core.nav.GeocodeSource
import dev.sam3vision.core.nav.GeocodedPlace
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Fournisseur principal : `android.location.Geocoder` (backend réseau GMS,
 * sans clé ni permission — la localisation reste à Google Maps, voir
 * `docs/NAVIGATION.md`). API 33+ : écouteur async ; en dessous : appel
 * bloquant sur IO. Gardé par [Geocoder.isPresent], jamais d'exception.
 */
class AndroidGeocoderSource(
    context: Context,
    private val geocoder: Geocoder = Geocoder(context, Locale.getDefault()),
) : GeocodeSource {

    override suspend fun geocode(query: String, limit: Int): Result<List<GeocodedPlace>> {
        if (query.isBlank()) return Result.failure(GeocodeError.BlankQuery())
        if (!Geocoder.isPresent()) return Result.failure(GeocodeError.ServiceUnavailable(
            "geocoder unavailable on this device",
        ))
        return try {
            val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                geocodeAsync(query, limit)
            } else {
                geocodeBlocking(query, limit)
            }
            Result.success(addresses.mapNotNull { it.toPlace(query) })
        } catch (c: CancellationException) {
            throw c
        } catch (e: IOException) {
            Result.failure(GeocodeError.Network("geocoding impossible: network unreachable", e))
        } catch (e: Exception) {
            Result.failure(GeocodeError.ServiceUnavailable(e.message ?: "geocoder unavailable", e))
        }
    }

    /** API 33+ : `getFromLocationName` à écouteur, ponté en `suspend`. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun geocodeAsync(query: String, limit: Int): List<Address> =
        suspendCancellableCoroutine { cont ->
            geocoder.getFromLocationName(query, limit, object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) {
                    if (!cont.isCompleted) cont.resume(addresses)
                }

                override fun onError(errorMessage: String?) {
                    if (!cont.isCompleted) {
                        cont.resumeWithException(IOException(errorMessage ?: "geocoder error"))
                    }
                }
            })
        }

    /** API 29-32 : variante bloquante (dépréciée en 33), hors thread principal. */
    @Suppress("DEPRECATION")
    private suspend fun geocodeBlocking(query: String, limit: Int): List<Address> =
        withContext(Dispatchers.IO) { geocoder.getFromLocationName(query, limit).orEmpty() }

    /** Adresse sans coordonnées = pas un candidat (jamais de lat/lon inventés). */
    private fun Address.toPlace(fallbackLabel: String): GeocodedPlace? {
        if (!hasLatitude() || !hasLongitude()) return null
        val label = pickAddressLabel(getAddressLine(0), locality, adminArea, fallbackLabel)
        return GeocodedPlace(latitude, longitude, label)
    }
}

/**
 * Libellé : ligne postale 0, sinon localité (+ région), sinon requête d'origine.
 * Pure sur des chaînes (pas d'`Address`) : testée sur JVM sans framework.
 */
internal fun pickAddressLabel(
    addressLine: String?,
    locality: String?,
    adminArea: String?,
    fallback: String,
): String {
    if (!addressLine.isNullOrBlank()) return addressLine
    val parts = listOf(locality, adminArea).filter { !it.isNullOrBlank() }
    if (parts.isNotEmpty()) return parts.joinToString(", ")
    return fallback
}
