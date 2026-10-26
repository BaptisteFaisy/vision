package dev.sam3vision.app.ihear

import dev.sam3vision.app.DistanceLayer
import dev.sam3vision.app.bitmapToJpegBase64
import dev.sam3vision.app.decodeScaledExact
import dev.sam3vision.app.samConfigured
import dev.sam3vision.app.samEndpoint
import dev.sam3vision.core.distance.DistanceMeter
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.model.SegmentResult
import dev.sam3vision.core.model.toDetections
import dev.sam3vision.core.net.HttpSamClient
import dev.sam3vision.core.settings.Settings
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Distances de la photo du double tap : la photo part au `/segment` de SAM, les
 * boîtes reviennent en pixels et le sténopé sur les hauteurs connues en tire une
 * pastille par objet.
 *
 * Une photo ne bouge pas : pas de journal de pistes, pas de lissage, une mesure
 * brute par objet. Calibrage retenu : champ de vision réglé à la main, sinon
 * celui lu sur le capteur lié, sinon le grand-angle de repli.
 */
class IhearPhotoDistances {

    private var sensorLens: LensSpec? = null

    /** Calibrage lu sur la caméra liée (une fois par liaison de la fenêtre). */
    fun setLens(spec: LensSpec?) {
        if (spec != null && spec.usable) sensorLens = spec
    }

    /**
     * Mesure [photo] en expliquant l'échec : le chat affiche le motif au lieu
     * de rester muet (endpoint absent, réseau, image illisible, rien de mesurable).
     */
    suspend fun measureOutcome(photo: File, settings: Settings): PhotoMeasure {
        if (!settings.distanceOverlayEnabled) return PhotoMeasure.OverlayOff
        if (!settings.samConfigured()) return PhotoMeasure.NoEndpoint
        val jpeg = withContext(Dispatchers.IO) { decodeScaledExact(photo)?.let(::bitmapToJpegBase64) }
        if (jpeg.isNullOrBlank()) return PhotoMeasure.Unreadable
        val segmented = runCatching { HttpSamClient(settings.samEndpoint()).segment(jpeg, null) }
            .getOrNull()?.getOrNull()
            ?: return PhotoMeasure.Failed("SAM unreachable")
        val layer = layerOf(segmented, chosenLens(settings))
        return if (layer == null) PhotoMeasure.NoObjects else PhotoMeasure.Measured(layer)
    }

    /** Réglage utilisateur, puis capteur, puis [LensSpec.Default] — comme le direct. */
    private fun chosenLens(settings: Settings): LensSpec {
        if (settings.cameraFovDeg > 0f) return LensSpec.fromHorizontalFov(settings.cameraFovDeg)
        return sensorLens ?: LensSpec.Default
    }
}

/**
 * Sort d'une analyse photo : mesurée, ou motif exact de l'absence de pastilles.
 */
sealed interface PhotoMeasure {
    data object OverlayOff : PhotoMeasure
    data object NoEndpoint : PhotoMeasure
    data object Unreadable : PhotoMeasure
    data class Failed(val reason: String) : PhotoMeasure
    data object NoObjects : PhotoMeasure
    data class Measured(val layer: DistanceLayer) : PhotoMeasure
}

/**
 * Pastilles de distance d'une photo segmentée : une lecture par objet dont le
 * concept a une hauteur de référence connue. `null` si aucune ne tient debout.
 */
fun layerOf(photo: SegmentResult, lens: LensSpec): DistanceLayer? {
    if (photo.width <= 0 || photo.height <= 0 || photo.objects.isEmpty()) return null
    val readings = DistanceMeter(lens).measure(
        photo.width,
        photo.height,
        photo.objects.toDetections(photo.width, photo.height),
    )
    if (readings.isEmpty()) return null
    return DistanceLayer(photo.width, photo.height, readings)
}
