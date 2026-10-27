package dev.sam3vision.app.glasses.bench

import dev.sam3vision.app.MAX_IMAGE_EDGE
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Politique de frame du spike : arithmétique pure, sans Android.
 *
 * [GlassesFrameEncoder] garde le travail de plateforme (`Bitmap`, `Base64`) ;
 * ces fonctions gardent les décisions (quel horodatage, quel recadrage, quelle
 * taille), ce qui les rend prouvables sur JVM par `GlassesFramePolicyTest` sans
 * `Bitmap` ni `SystemClock`.
 */

/**
 * Horodatage d'une frame, en millisecondes.
 *
 * @param presentationTimeUs horloge média fournie par le SDK, en microsecondes ;
 *   valeur ≤ 0 quand le SDK n'en fournit pas.
 * @param fallbackMs horloge de repli, choisie par l'appelant (côté Android :
 *   `SystemClock.elapsedRealtime()`).
 *
 * ⚠️ Les deux branches ne sont pas dans le même référentiel (horloge média des
 * lunettes ou du décodeur vs horloge monotone du téléphone) : à unifier le jour
 * où l'aval compare ces valeurs entre frames.
 */
internal fun frameTimestampMs(presentationTimeUs: Long, fallbackMs: Long): Long =
    if (presentationTimeUs > 0L) presentationTimeUs / 1000L else fallbackMs

/**
 * Dimensions cibles d'une image dont le plus grand côté dépasse [maxEdge],
 * ratio conservé et arrondi au pixel le plus proche.
 *
 * @return `null` quand aucune réduction n'est nécessaire, ou quand les
 *   dimensions d'entrée sont inexploitables ; sinon des dimensions ≥ 1 (une
 *   image très allongée garde au moins un pixel sur son petit côté).
 *
 * Arrondi volontaire (`roundToInt`) + `null` si inutile : ne pas factoriser avec
 * `FramePolicy.requestedSize` (troncature, jamais `null`) — les deux politiques
 * diffèrent par contrat, pas par oubli.
 */
internal fun scaledDimensions(
    width: Int,
    height: Int,
    maxEdge: Int = MAX_IMAGE_EDGE,
): Pair<Int, Int>? {
    if (width <= 0 || height <= 0 || maxEdge <= 0) return null
    val longest = max(width, height)
    if (longest <= maxEdge) return null
    val ratio = maxEdge.toFloat() / longest
    return max(1, (width * ratio).roundToInt()) to max(1, (height * ratio).roundToInt())
}

/** Rectangle de recadrage centré, en pixels (origine en haut à gauche). */
internal data class CropRect(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * Recadrage centré au format 16:9 paysage.
 *
 * Les lunettes livrent du portrait 9:16 (`VideoQuality.MEDIUM` = 504×896,
 * imposé par le SDK Meta : `StreamConfiguration` n'expose ni dimensions ni
 * orientation) ; le banc envoie du 16:9 à l'API en coupant le haut et le bas.
 *
 * @return `null` quand l'image est déjà au 16:9 exact (rien à couper), quand
 *   ses dimensions sont inexploitables, ou quand le côté coupé tomberait à zéro
 *   (image d'un pixel : on garde l'image entière plutôt qu'un recadrage vide).
 */
internal fun cropTo16x9(width: Int, height: Int): CropRect? {
    if (width <= 0 || height <= 0) return null
    // Comparaison sans flottant : `largeur × 9` contre `hauteur × 16`.
    val wide = width * 9
    val tall = height * 16
    if (wide == tall) return null
    if (wide < tall) {
        val cropped = wide / 16
        if (cropped < 1) return null
        return CropRect(0, (height - cropped) / 2, width, cropped)
    }
    val cropped = tall / 9
    if (cropped < 1) return null
    return CropRect((width - cropped) / 2, 0, cropped, height)
}
