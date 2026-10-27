package dev.sam3vision.app.glasses.bench

import dev.sam3vision.app.MAX_IMAGE_EDGE
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Politique de frame du spike : arithmétique pure, sans Android.
 *
 * [GlassesFrameEncoder] garde le travail de plateforme (`Bitmap`, `Base64`) ;
 * ces deux fonctions gardent les décisions (quel horodatage, quelle taille), ce
 * qui les rend prouvables sur JVM par `FramePolicyTest` sans `Bitmap` ni
 * `SystemClock`.
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
