package dev.sam3vision.core.share

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.picto.PictoHit
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phone position shared with the parent site: coordinates, accuracy,
 * compass heading (null when unknown — object angles then stay relative to
 * the assumed north) and indoor/outdoor heuristic.
 */
data class SharedFix(
    val lat: Double,
    val lon: Double,
    val accM: Float,
    val headingDeg: Float?,
    val atMs: Long,
    val indoor: Boolean,
)

/** An object around the wearer: label, camera distance and **absolute heading** (degrees). */
data class SharedObject(
    val label: String,
    val distM: Float,
    val angleDeg: Float,
)

/**
 * Angle of an object off the sight axis, from its box center:
 * `(centerX - 0.5) × fov`. `null` when the box or picture is unusable.
 */
fun angleDegFor(box: List<Float>, imageWidthPx: Int, widthFovDeg: Float): Float? {
    if (box.size < 4 || imageWidthPx <= 0) return null
    if (!widthFovDeg.isFinite() || widthFovDeg <= 0f) return null
    val centerX = ((box[0] + box[2]) / 2f) / imageWidthPx
    if (!centerX.isFinite()) return null
    return (centerX - 0.5f) * widthFovDeg
}

/** Folds a heading into ]-180, 180]. */
fun normAngleDeg(deg: Float): Float {
    var v = deg % 360f
    if (v <= -180f) v += 360f
    if (v > 180f) v -= 360f
    return v
}

/**
 * Absolute heading of an object: phone heading + relative camera angle. With
 * no compass, the relative angle is kept as-is (the site then assumes north).
 */
fun absoluteAngleDeg(relativeDeg: Float, headingDeg: Float?): Float =
    if (headingDeg == null || !headingDeg.isFinite()) relativeDeg
    else normAngleDeg(headingDeg + relativeDeg)

/** Shareable objects from distance readings (at most [max], order kept). */
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
 * Current picture of the accompanied phone: the iSee camera JPEG frame
 * (base64, no `data:` prefix) and its capture time.
 */
data class SharedPhoto(
    val jpegBase64: String,
    val atMs: Long,
)

/**
 * Guided trip in progress, from start to destination: what the caregiver
 * page shows as a progress bar. [progress] is 0 at the start, 1 at arrival
 * ([progressOf]); [totalMeters] stays 0 until the route summary is known
 * (the bar then has nothing to fill).
 */
data class SharedTrip(
    val destination: String,
    val totalMeters: Long,
    val remainingMeters: Long,
    val remainingSeconds: Long,
    val progress: Float,
)

/** A recognized landmark: its name and PNG drawing (base64, no `data:` prefix). */
data class SharedPicto(
    val label: String,
    val pngBase64: String,
)

/** Landmarks recognized at report time: the page shows them as drawings, never as bare text. */
data class SharedPictos(
    val atMs: Long,
    val items: List<SharedPicto>,
)

/** Landmarks sent at most per report (the page shows them in a strip). */
const val MAX_SHARED_PICTOS = 4

/** Max length of a landmark PNG in base64 (drawing ≈ 3-11k chars). */
const val MAX_SHARED_PICTO_PNG = 30_000

/** Max length of a landmark name (like the relay's object labels). */
const val MAX_SHARED_PICTO_LABEL = 40

/**
 * Shareable recognized landmarks: name + PNG drawing (base64), at most [max].
 * `null` when none has a drawing (demo, fake landmarks): the packet then
 * sends no `pictos` and the page shows nothing.
 */
fun sharedPictosOf(
    hits: List<PictoHit>,
    pngOf: (String) -> ByteArray?,
    atMs: Long,
    max: Int = MAX_SHARED_PICTOS,
): SharedPictos? {
    val items = hits.mapNotNull { hit ->
        val png = runCatching { pngOf(hit.pictoId) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: return@mapNotNull null
        val label = hit.label.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        SharedPicto(label, Base64.getEncoder().encodeToString(png))
    }.take(max)
    return items.takeIf { it.isNotEmpty() }?.let { SharedPictos(atMs, it) }
}

/** Chars of a base64 JPEG: the page glues it behind `data:image/jpeg;base64,`. */
private val BASE64 = Regex("[A-Za-z0-9+/]+={0,2}")

/** Max length of a shared picture (320 px frame ≈ 24k chars). */
const val MAX_SHARED_JPEG = 120_000

/** Max length of a destination name. */
const val MAX_SHARED_DESTINATION = 80

/**
 * Relay JSON body: `{shareId, writeKey, fix?, objects, scene?, photo?, trip?, pictos?}`.
 * The write key is checked by the relay (never stored site-side).
 * `fix` is optional: without GPS (indoors, starting up, refused permission),
 * camera, sentence and landmarks still go — otherwise the caregiver page
 * stayed OFFLINE while the camera ran. `photo`, `trip` and `pictos` are
 * optional: an older client keeps sending position, objects and sentence,
 * and the relay takes them as-is.
 */
fun shareJson(
    shareId: String,
    writeKey: String,
    fix: SharedFix?,
    objects: List<SharedObject>,
    scene: SharedScene? = null,
    photo: SharedPhoto? = null,
    trip: SharedTrip? = null,
    pictos: SharedPictos? = null,
): JSONObject = JSONObject()
    .put("shareId", shareId)
    .put("writeKey", writeKey)
    .apply { fix?.let { put("fix", fixJson(it)) } }
    .put(
        "objects",
        JSONArray().apply {
            objects.forEach { put(JSONObject().put("label", it.label).put("distM", it.distM.toDouble()).put("angleDeg", it.angleDeg.toDouble())) }
        },
    )
    .apply { scene?.let { put("scene", it.toJson()) } }
    .apply { photoJson(photo)?.let { put("photo", it) } }
    .apply { tripJson(trip)?.let { put("trip", it) } }
    .apply { pictoJson(pictos)?.let { put("pictos", it) } }

/** Publishable position, missing without GPS (the camera still goes). */
private fun fixJson(fix: SharedFix): JSONObject = JSONObject()
    .put("lat", fix.lat)
    .put("lon", fix.lon)
    .put("accM", fix.accM.toDouble())
    .put("atMs", fix.atMs)
    .put("indoor", fix.indoor)
    .put("headingDeg", fix.headingDeg?.toDouble())

/** Publishable picture, or `null` when the base64 is unusable (never a relay 400). */
private fun photoJson(photo: SharedPhoto?): JSONObject? {
    val jpeg = photo?.jpegBase64?.takeIf { BASE64.matches(it) && it.length <= MAX_SHARED_JPEG } ?: return null
    return JSONObject().put("jpeg", jpeg).put("atMs", photo.atMs)
}

/** Publishable landmarks (bounded names and PNGs), or `null` when none is valid. */
private fun pictoJson(pictos: SharedPictos?): JSONObject? {
    if (pictos == null) return null
    val items = JSONArray()
    pictos.items.take(MAX_SHARED_PICTOS).forEach { item ->
        val label = item.label.trim().takeIf { it.isNotEmpty() } ?: return@forEach
        val png = item.pngBase64.takeIf { BASE64.matches(it) && it.length <= MAX_SHARED_PICTO_PNG }
            ?: return@forEach
        items.put(JSONObject().put("label", label.take(MAX_SHARED_PICTO_LABEL)).put("png", png))
    }
    if (items.length() == 0) return null
    return JSONObject().put("atMs", pictos.atMs).put("items", items)
}

/** Publishable trip ([MAX_SHARED_DESTINATION], numeric bounds), or `null`. */
private fun tripJson(trip: SharedTrip?): JSONObject? {
    val destination = trip?.destination?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return JSONObject()
        .put("destination", destination.take(MAX_SHARED_DESTINATION))
        .put("totalM", trip.totalMeters.coerceAtLeast(0))
        .put("remainingM", trip.remainingMeters.coerceAtLeast(0))
        .put("remainingS", trip.remainingSeconds.coerceAtLeast(0))
        .put("progress", trip.progress.coerceIn(0f, 1f).toDouble())
}

/** Objects sent at most per report (the site shows no more). */
const val MAX_SHARED_OBJECTS = 8

/** Accuracy past which the position is flagged "indoor". */
const val INDOOR_ACCURACY_M = 25f
