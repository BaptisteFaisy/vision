package dev.sam3vision.core.share

import dev.sam3vision.core.distance.DistanceReading
import org.json.JSONArray
import org.json.JSONObject

/**
 * Position du téléphone partagée avec le site parent : coordonnées, précision,
 * cap boussole (null si inconnu — les angles objets restent alors relatifs au
 * nord supposé) et heuristique intérieur/extérieur.
 */
data class SharedFix(
    val lat: Double,
    val lon: Double,
    val accM: Float,
    val headingDeg: Float?,
    val atMs: Long,
    val indoor: Boolean,
)

/** Un objet autour du porteur : libellé, distance caméra et **cap absolu** (degrés). */
data class SharedObject(
    val label: String,
    val distM: Float,
    val angleDeg: Float,
)

/**
 * Angle d'un objet par rapport à l'axe de visée, depuis le centre de sa boîte :
 * `(centreX - 0,5) × champ`. `null` si la boîte ou l'image est inexploitable.
 */
fun angleDegFor(box: List<Float>, imageWidthPx: Int, widthFovDeg: Float): Float? {
    if (box.size < 4 || imageWidthPx <= 0) return null
    if (!widthFovDeg.isFinite() || widthFovDeg <= 0f) return null
    val centerX = ((box[0] + box[2]) / 2f) / imageWidthPx
    if (!centerX.isFinite()) return null
    return (centerX - 0.5f) * widthFovDeg
}

/** Ramène un cap dans ]-180, 180]. */
fun normAngleDeg(deg: Float): Float {
    var v = deg % 360f
    if (v <= -180f) v += 360f
    if (v > 180f) v -= 360f
    return v
}

/**
 * Cap absolu d'un objet : cap du téléphone + angle relatif caméra. Sans boussole,
 * l'angle relatif est conservé tel quel (le site suppose alors un cap nord).
 */
fun absoluteAngleDeg(relativeDeg: Float, headingDeg: Float?): Float =
    if (headingDeg == null || !headingDeg.isFinite()) relativeDeg
    else normAngleDeg(headingDeg + relativeDeg)

/** Objets partageables depuis des lectures de distance (au plus [max], ordre conservé). */
fun sharedObjectsOf(
    readings: List<DistanceReading>,
    imageWidthPx: Int,
    imageHeightPx: Int,
    widthFovDeg: Float,
    headingDeg: Float?,
    max: Int = MAX_SHARED_OBJECTS,
): List<SharedObject> =
    readings.take(max).mapNotNull { reading ->
        val relative = angleDegFor(reading.box, imageWidthPx, widthFovDeg) ?: return@mapNotNull null
        SharedObject(reading.label, reading.meters, absoluteAngleDeg(relative, headingDeg))
    }

/**
 * Image courante du téléphone accompagné : la frame JPEG de la caméra iSee
 * (base64, sans préfixe `data:`) et son instant de capture.
 */
data class SharedPhoto(
    val jpegBase64: String,
    val atMs: Long,
)

/**
 * Trajet guidé en cours, du point de départ à l'arrivée : ce que la page
 * caregiver montre en barre de progression. [progress] vaut 0 au départ, 1 à
 * l'arrivée ([progressOf]) ; [totalMeters] reste 0 tant que le résumé
 * d'itinéraire n'est pas connu (la barre n'a alors rien à remplir).
 */
data class SharedTrip(
    val destination: String,
    val totalMeters: Long,
    val remainingMeters: Long,
    val remainingSeconds: Long,
    val progress: Float,
)

/** Caractères d'un JPEG base64 : la page le colle derrière `data:image/jpeg;base64,`. */
private val BASE64 = Regex("[A-Za-z0-9+/]+={0,2}")

/** Longueur maximale d'une image partagée (frame 320 px ≈ 24 k caractères). */
const val MAX_SHARED_JPEG = 120_000

/** Longueur maximale d'un nom de destination. */
const val MAX_SHARED_DESTINATION = 80

/**
 * Corps JSON du relais : `{shareId, writeKey, fix, objects, scene?, photo?, trip?}`.
 * La clé d'écriture est vérifiée par le relais (jamais stockée côté site).
 * `photo` et `trip` sont optionnels : un client d'avant continue d'envoyer
 * position, objets et phrase, et le relais les accepte tels quels.
 */
fun shareJson(
    shareId: String,
    writeKey: String,
    fix: SharedFix,
    objects: List<SharedObject>,
    scene: SharedScene? = null,
    photo: SharedPhoto? = null,
    trip: SharedTrip? = null,
): JSONObject = JSONObject()
    .put("shareId", shareId)
    .put("writeKey", writeKey)
    .put(
        "fix",
        JSONObject()
            .put("lat", fix.lat)
            .put("lon", fix.lon)
            .put("accM", fix.accM.toDouble())
            .put("atMs", fix.atMs)
            .put("indoor", fix.indoor)
            .put("headingDeg", fix.headingDeg?.toDouble()),
    )
    .put(
        "objects",
        JSONArray().apply {
            objects.forEach { put(JSONObject().put("label", it.label).put("distM", it.distM.toDouble()).put("angleDeg", it.angleDeg.toDouble())) }
        },
    )
    .apply { scene?.let { put("scene", it.toJson()) } }
    .apply { photoJson(photo)?.let { put("photo", it) } }
    .apply { tripJson(trip)?.let { put("trip", it) } }

/** Image publiable, ou `null` si le base64 est inexploitable (jamais un 400 du relais). */
private fun photoJson(photo: SharedPhoto?): JSONObject? {
    val jpeg = photo?.jpegBase64?.takeIf { BASE64.matches(it) && it.length <= MAX_SHARED_JPEG } ?: return null
    return JSONObject().put("jpeg", jpeg).put("atMs", photo.atMs)
}

/** Trajet publiable ([MAX_SHARED_DESTINATION], bornes numériques), ou `null`. */
private fun tripJson(trip: SharedTrip?): JSONObject? {
    val destination = trip?.destination?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return JSONObject()
        .put("destination", destination.take(MAX_SHARED_DESTINATION))
        .put("totalM", trip.totalMeters.coerceAtLeast(0))
        .put("remainingM", trip.remainingMeters.coerceAtLeast(0))
        .put("remainingS", trip.remainingSeconds.coerceAtLeast(0))
        .put("progress", trip.progress.coerceIn(0f, 1f).toDouble())
}

/** Nombre d'objets envoyés au plus par rapport (le site n'en affiche pas plus). */
const val MAX_SHARED_OBJECTS = 8

/** Seuil de précision au-delà duquel la position est marquée « intérieur ». */
const val INDOOR_ACCURACY_M = 25f
