package dev.sam3vision.core.predict

import dev.sam3vision.core.mask.GrayMask
import dev.sam3vision.core.mask.MaskMotion
import dev.sam3vision.core.mask.MaskPropagator
import dev.sam3vision.core.mask.WarpFilter
import dev.sam3vision.core.motion.MotionSample
import kotlin.math.hypot

/**
 * Verdict du **résidu SAM** (PISTE 3) : le masque keyframe, compensé de l'ego-motion
 * caméra, coïncide-t-il encore avec le masque courant ?
 */
enum class ResiduVerdict {
    /** Fort résidu : l'objet bouge vraiment, la caméra n'explique pas l'écart. */
    OBJECT_MOVED,

    /**
     * Faible résidu : tout le mouvement apparent est expliqué par la caméra — **ou
     * nul** (scène fixe). Croiser avec [ResiduDecision.egoPx] pour l'arrêt : ego ≈ 0
     * ⇒ statique, ego franc ⇒ caméra mobile.
     */
    CAMERA_MOVED,

    /** Masque vide, confiance basse, ego inutilisable, ancre périmée : pas d'avis. */
    UNCERTAIN,
}

/**
 * Décision du résidu pour un id stable : [trackId] l'id suivi ([SamResidu.NO_TRACK]
 * sans suivi), [verdict] le verdict, [confidence] sa confiance (0..1, 0 si doute),
 * [iou] l'IoU(masque keyframe warpé, masque courant) ([SamResidu.NO_IOU] si
 * incalculable), [egoPx] l'amplitude de l'ego-motion en pixels de masque (pour
 * distinguer l'arrêt de la caméra mobile, voir [ResiduVerdict.CAMERA_MOVED]).
 */
data class ResiduDecision(
    val trackId: Int,
    val verdict: ResiduVerdict,
    val confidence: Float,
    val iou: Float,
    val egoPx: Float,
)

/**
 * Résidu de mouvement sur masque SAM (PISTE 3) : qui bouge, de l'objet ou de la
 * caméra ?
 *
 * On warpe le masque keyframe par l'ego-motion ([MotionSample] global de
 * `BlockMotionSource`, converti en pixels de masque) puis on mesure l'écart avec le
 * masque courant (IoU) : fort résidu ⇒ l'objet bouge vraiment ([ResiduVerdict.OBJECT_MOVED]),
 * faible résidu ⇒ c'est la caméra ([ResiduVerdict.CAMERA_MOVED]). Fonction pure JVM :
 * mêmes entrées ⇒ même décision, testable sans appareil ni GPU.
 *
 * Référence temporelle : l'id stable voyage dans [ResiduDecision.trackId] et, quand
 * l'appelant la fournit (`MotionModel.confidenceAt`), [SamResidu.decide] refuse une
 * ancre périmée (doute plutôt qu'un verdict sur une référence éventée).
 *
 * Seuils = priors à recaler sur téléphone, direction sûre : le doute ne tranche
 * jamais (ni « objet mobile » sur un bruit de segmentation, ni « caméra » sur un
 * masque vide). Mapping vers le chemin véhicule ([dev.sam3vision.core.traverse.Verdict]) :
 * [dev.sam3vision.core.traverse.toVerdict] (`null` = doute, garder le verdict V1).
 */
object SamResidu {

    /**
     * Sous cet IoU, l'écart n'est plus du bruit : un déplacement de 16 px d'un objet
     * de 64 px de large donne 0,60 (marge 0,15 au seuil).
     */
    const val IOU_MOVED_MAX = 0.75f

    /**
     * À partir de cet IoU, les masques coïncident : une érosion d'1 px sur 64×40
     * donne 0,92 (marge 0,07 au seuil). Entre les deux seuils : bande de doute.
     */
    const val IOU_STILL_MIN = 0.85f

    /** Score SAM minimal, miroir de `TraverseVeto.SCORE_MIN` : en dessous, doute. */
    const val SAM_SCORE_MIN = 0.5f

    /**
     * Aire objet minimale (pixels) : sous 8×8, une frange d'1 px fait ±25 % d'IoU
     * et le verdict ne veut plus rien dire.
     */
    const val MIN_MASK_PIXELS = 64

    /** Id porté quand l'appelant ne suit pas l'objet (pas d'id stable). */
    const val NO_TRACK = -1

    /** IoU porté quand il est incalculable (doute avant toute mesure). */
    const val NO_IOU = -1f

    /**
     * Verdict du résidu pour un objet : [keyMask] le masque du keyframe, [curMask]
     * le masque courant (même grille), [ego] l'ego-motion inter-frames en pixels du
     * plan de luminance [lumaWidth]×… (`null` = estimateur muet), [trackId] l'id
     * stable (défaut [NO_TRACK]), [samConfidence] le score SAM courant, [anchorFreshness]
     * la fraîcheur de l'ancre (`MotionModel.confidenceAt`, `null` = non suivie).
     *
     * Alloue un tampon de grille par appel : rythme keyframe, pas chemin chaud caméra.
     */
    fun decide(
        keyMask: GrayMask,
        curMask: GrayMask,
        ego: MotionSample?,
        lumaWidth: Int,
        trackId: Int = NO_TRACK,
        samConfidence: Float = 1f,
        anchorFreshness: Float? = null,
    ): ResiduDecision {
        if (keyMask.width != curMask.width || keyMask.height != curMask.height) {
            return uncertain(trackId)
        }
        val motion = ego?.toMaskMotion(lumaWidth, keyMask.width)
        if (motion == null || !motion.isUsable || motion.confidence < Shift.TRUST_MIN) {
            return uncertain(trackId)
        }
        if (!samConfidence.isFinite() || samConfidence < SAM_SCORE_MIN) return uncertain(trackId)
        if (anchorFreshness != null &&
            (!anchorFreshness.isFinite() || anchorFreshness < MotionModel.MASK_FLOOR)
        ) {
            return uncertain(trackId)
        }
        if (countObject(keyMask) < MIN_MASK_PIXELS || countObject(curMask) < MIN_MASK_PIXELS) {
            return uncertain(trackId)
        }
        val warped = GrayMask(keyMask.width, keyMask.height, ByteArray(keyMask.size))
        if (!MaskPropagator().warpInto(keyMask, warped, motion, WarpFilter.NEAREST)) {
            return uncertain(trackId)
        }
        val (inter, union) = overlap(warped, curMask)
        if (union <= 0) return uncertain(trackId)
        val iou = inter.toFloat() / union
        val egoPx = hypot(motion.dx, motion.dy).toFloat()
        return when {
            iou < IOU_MOVED_MAX -> moved(trackId, iou, egoPx)
            iou >= IOU_STILL_MIN -> still(trackId, iou, egoPx)
            else -> ResiduDecision(trackId, ResiduVerdict.UNCERTAIN, 0f, iou, egoPx)
        }
    }

    /** Pixels objet (niveau ≥ 128) de [mask]. */
    private fun countObject(mask: GrayMask): Int {
        var pixels = 0
        for (level in mask.pixels) if (level.toInt() and 0xFF >= 128) pixels += 1
        return pixels
    }

    /** `(intersection, union)` des pixels objet de [a] et [b] (même grille). */
    private fun overlap(a: GrayMask, b: GrayMask): Pair<Int, Int> {
        var inter = 0
        var union = 0
        for (i in a.pixels.indices) {
            val left = a.pixels[i].toInt() and 0xFF >= 128
            val right = b.pixels[i].toInt() and 0xFF >= 128
            if (left && right) inter += 1
            if (left || right) union += 1
        }
        return inter to union
    }

    /** Verdict objet mobile : confiance = marge sous le seuil, ramenée à 0..1. */
    private fun moved(trackId: Int, iou: Float, egoPx: Float): ResiduDecision {
        val confidence = ((IOU_MOVED_MAX - iou) / IOU_MOVED_MAX).coerceIn(0f, 1f)
        return ResiduDecision(trackId, ResiduVerdict.OBJECT_MOVED, confidence, iou, egoPx)
    }

    /** Verdict caméra : confiance = marge au-dessus du seuil, ramenée à 0..1. */
    private fun still(trackId: Int, iou: Float, egoPx: Float): ResiduDecision {
        val confidence = ((iou - IOU_STILL_MIN) / (1f - IOU_STILL_MIN)).coerceIn(0f, 1f)
        return ResiduDecision(trackId, ResiduVerdict.CAMERA_MOVED, confidence, iou, egoPx)
    }

    /** Verdict de doute : confiance nulle, preuves conservées quand elles existent. */
    private fun uncertain(trackId: Int): ResiduDecision =
        ResiduDecision(trackId, ResiduVerdict.UNCERTAIN, 0f, NO_IOU, 0f)
}

/**
 * Le même ego-motion, exprimé en **pixels de la grille masque** ([maskWidth]) au lieu
 * des pixels du plan de luminance ([lumaWidth]) : c'est l'unité qu'attend le warp
 * (`MaskPropagator`). Dimensions invalides ⇒ mouvement inutilisable (échelle NaN) :
 * [SamResidu.decide] rendra [ResiduVerdict.UNCERTAIN], jamais un verdict sur une
 * conversion boiteuse.
 */
fun MotionSample.toMaskMotion(lumaWidth: Int, maskWidth: Int): MaskMotion {
    if (lumaWidth <= 0 || maskWidth <= 0) return MaskMotion(0.0, 0.0, Double.NaN, 0.0)
    val scaled = scaledBy(maskWidth.toFloat() / lumaWidth)
    return MaskMotion(
        scaled.dx.toDouble(),
        scaled.dy.toDouble(),
        scaled.scale.toDouble(),
        scaled.confidence.toDouble(),
    )
}
