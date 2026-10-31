package dev.sam3vision.app.glasses

import dev.sam3vision.app.MAX_IMAGE_EDGE
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Politique de frame des lunettes : arithmétique pure, sans Android.
 *
 * Partagée par le banc (`bench/GlassesFramePump.kt`, `bench/GlassesFrameEncoder.kt`)
 * et le collecteur de production ([GlassesFrameCollector]) : une seule
 * définition de l'étranglement, du verdict et des décisions d'image, prouvable
 * sur JVM par `GlassesFramePolicyTest` sans `Bitmap` ni `SystemClock`. Les
 * encodeurs gardent le travail de plateforme (`Bitmap`, `Base64`, `MediaCodec`).
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
 * Recadrage centré au format 16:9 paysage (hérité : voir [cropTo4x3]).
 *
 * Les lunettes livrent du portrait 9:16 (`VideoQuality.HIGH` = 720×1280 en production,
 * imposé par le SDK Meta : `StreamConfiguration` n'expose ni dimensions ni
 * orientation). En production, le 16:9 ne garde que 720×405 : 68 % de la hauteur
 * coupée — le sol et l'horizon sont perdus. Ne plus utiliser : [cropTo4x3]
 * (720×960) garde trois quarts de la hauteur ; bascule à faire dans
 * `GlassesFrameConvert.cropToLandscape` (hors propriété de ce correctif).
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

/**
 * Recadrage centré au format 4:3, **dans l'orientation de la source** : un portrait
 * reste portrait (3:4), un paysage reste paysage (4:3) — le 16:9 paysage coupait 68 %
 * de la hauteur du portrait des lunettes (720×405), le 4:3 n'en coupe que 25 %
 * (720×960 : le sol et l'horizon survivent). L'API accepte tous les ratios et
 * [scaledDimensions] borne déjà le grand côté : le recadrage ne sert qu'à alléger.
 *
 * @return `null` quand l'image est déjà au 4:3 exact de son orientation (rien à
 *   couper), quand ses dimensions sont inexploitables, ou quand le côté coupé
 *   tomberait à zéro (on garde l'image entière plutôt qu'un recadrage vide).
 */
internal fun cropTo4x3(width: Int, height: Int): CropRect? {
    if (width <= 0 || height <= 0) return null
    if (width >= height) {
        // Paysage → 4:3 paysage (comparaison sans flottant, comme le 16:9).
        if (width * 3 == height * 4) return null
        if (width * 3 > height * 4) {
            val cropped = height * 4 / 3
            if (cropped < 1) return null
            return CropRect((width - cropped) / 2, 0, cropped, height)
        }
        val cropped = width * 3 / 4
        if (cropped < 1) return null
        return CropRect(0, (height - cropped) / 2, width, cropped)
    }
    // Portrait → 3:4 portrait : le cas des lunettes (720×1280 → 720×960).
    if (width * 4 == height * 3) return null
    if (width * 4 < height * 3) {
        val cropped = width * 4 / 3
        if (cropped < 1) return null
        return CropRect(0, (height - cropped) / 2, width, cropped)
    }
    val cropped = height * 3 / 4
    if (cropped < 1) return null
    return CropRect((width - cropped) / 2, 0, cropped, height)
}

// ---- Étranglement et verdict (banc + production) ----------------------------
// Déplacés depuis `bench/GlassesSessionPolicy.kt` : le débit et le tri des
// frames sont les mêmes des deux côtés, une seule définition fait foi.

/** Étranglement du flux : une frame toutes les 1,5 s suffit au LLM, au-delà on paie pour rien. */
internal const val FRAME_THROTTLE_MS = 1_500L

/**
 * Attente à respecter avant d'envoyer la frame suivante, en millisecondes
 * (0 = envoi immédiat).
 *
 * L'horloge reste fournie par l'appelant (`SystemClock.elapsedRealtime()`), que
 * la JVM ne sait pas simuler sans Android : c'est la seule raison d'être de ce
 * paramètre. `lastSentAtMs = 0` (première frame) ne stérilise rien tant que
 * l'horloge a dépassé la fenêtre, ce qui est le cas d'`elapsedRealtime()`.
 *
 * Volontairement non borné vers le haut : une horloge qui recule fait attendre
 * plus que la fenêtre plutôt que d'envoyer deux frames dans la même seconde.
 */
internal fun throttleDelayMs(
    lastSentAtMs: Long,
    nowMs: Long,
    throttleMs: Long = FRAME_THROTTLE_MS,
): Long = (throttleMs - (nowMs - lastSentAtMs)).coerceAtLeast(0L)

/**
 * Cadence mesurée : [frames] images en [elapsedMs] millisecondes → images/s,
 * `null` quand le calcul est impossible (compteur pas encore parti). Pur, testé en JVM.
 */
internal fun measuredFps(frames: Long, elapsedMs: Long): Float? =
    if (frames <= 0L || elapsedMs <= 0L) null else frames * 1000f / elapsedMs

/**
 * Suffixe « (12.3 i/s) » pour le journal, vide quand la cadence est incalculable :
 * un seul format pour la pompe et le décodeur (recette : `logcat -s GlassesVideo,GlassesChain`).
 */
internal fun fpsSuffix(frames: Long, elapsedMs: Long): String =
    measuredFps(frames, elapsedMs)?.let { " (%.1f i/s)".format(Locale.US, it) } ?: ""

/** Verdict sur une frame brute, avant toute copie ou conversion. */
internal enum class FrameVerdict {
    /** La frame porte une image : elle peut être encodée. */
    ENCODE,

    /** Bitstream HEVC/H.264 : le banc le jette, la production le décode ([GlassesVideoDecoder]). */
    DROP_COMPRESSED,

    /** Frame de configuration / trame-clé : elle ne porte aucune image. */
    DROP_CODEC_CONFIG,

    /** Dimensions nulles : ni `IntArray` ni `Bitmap` ne sont dimensionnables. */
    DROP_DIMENSIONS,
}

/**
 * Faut-il encoder cette frame, et sinon pourquoi la jeter ?
 *
 * La compression est testée en premier : une frame compressée n'est jamais
 * décodée ici, même si le SDK y met aussi le drapeau de configuration. Le
 * banc jette [FrameVerdict.DROP_COMPRESSED] ; le collecteur de production le
 * route vers le décodeur `MediaCodec` — le verdict trie, l'appelant décide.
 */
internal fun frameVerdict(
    isCompressed: Boolean,
    isCodecConfig: Boolean,
    width: Int,
    height: Int,
): FrameVerdict = when {
    isCompressed -> FrameVerdict.DROP_COMPRESSED
    isCodecConfig -> FrameVerdict.DROP_CODEC_CONFIG
    width <= 0 || height <= 0 -> FrameVerdict.DROP_DIMENSIONS
    else -> FrameVerdict.ENCODE
}
