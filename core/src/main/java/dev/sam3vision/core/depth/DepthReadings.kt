package dev.sam3vision.core.depth

import dev.sam3vision.core.distance.DistanceMeter
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.distance.KnownSizes
import dev.sam3vision.core.distance.Pinhole
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.model.SegObject

/** Ancre de calibration : mesure sténopé et sa proximité médiane. */
data class DepthAnchor(val meters: Float, val closeness: Float)

/**
 * Distances par profondeur monoculaire : médiane de proximité par boîte,
 * calibrée en mètres par les ancres sténopé de la frame. Pur JVM.
 *
 * Le sténopé ne mesure que les labels à hauteur connue ; la carte mesure
 * tout ce qui est cadré (trottoirs, murs, poteaux) dès qu'une ancre fixe
 * l'échelle. Sans ancre ni proximité : le sténopé est gardé tel quel.
 */
object DepthReadings {

    /**
     * Médiane de proximité des pixels finis de [box] (pixels image 0..1
     * ramenés à la carte) ; `null` si la boîte est vide ou hors champ.
     */
    /** Érosion par côté avant la médiane : les bords portent le fond, le cœur porte l'objet. */
    const val EROSION_FRACTION = 0.25f

    /**
     * Côté minimal (px image) de la fenêtre érodée : en deçà, la médiane se lit
     * sur la [colonne centrale][centralColumn]. Même ordre que le plancher
     * sténopé ([Pinhole], 4 px : un pixel d'erreur y vaut des mètres).
     */
    const val MIN_ERODED_SIDE_PX = 4f

    fun boxCloseness(
        depth: DepthMap,
        box: List<Float>,
        frameW: Int,
        frameH: Int,
        maskBox: List<Float>? = null,
    ): Float? {
        if (box.size < 4 || frameW <= 0 || frameH <= 0) return null
        if (depth.width <= 0 || depth.height <= 0) return null
        if (box[2] <= 0f || box[0] >= frameW || box[3] <= 0f || box[1] >= frameH) return null
        val inner = robustWindow(box) ?: return null
        val window = maskedWindow(inner, maskBox)
        val x1 = ((window[0] / frameW) * depth.width).toInt().coerceIn(0, depth.width - 1)
        val x2 = ((window[2] / frameW) * depth.width).toInt().coerceIn(x1, depth.width - 1)
        val y1 = ((window[1] / frameH) * depth.height).toInt().coerceIn(0, depth.height - 1)
        val y2 = ((window[3] / frameH) * depth.height).toInt().coerceIn(y1, depth.height - 1)
        val kept = FloatArray((x2 - x1 + 1) * (y2 - y1 + 1))
        var count = 0
        for (y in y1..y2) {
            for (x in x1..x2) {
                val v = depth.values[y * depth.width + x]
                if (v.isFinite()) {
                    kept[count++] = v
                }
            }
        }
        if (count == 0) return null
        return DepthFusion.quickselectMedian(kept, count)
    }

    /**
     * Fenêtre centrale de [box] ([EROSION_FRACTION] rognée par côté) : la médiane
     * y lit l'objet, pas le fond qui déborde des bords détecteur. `null` si la
     * fenêtre dégénère (petite boîte : voir [robustWindow]).
     */
    fun erodedBox(box: List<Float>): List<Float>? {
        if (box.size < 4) return null
        val dx = (box[2] - box[0]) * EROSION_FRACTION
        val dy = (box[3] - box[1]) * EROSION_FRACTION
        if (dx <= 0f || dy <= 0f) return null
        val inner = listOf(box[0] + dx, box[1] + dy, box[2] - dx, box[3] - dy)
        return inner.takeIf { it[2] > it[0] && it[3] > it[1] }
    }

    /**
     * Fenêtre de médiane robuste : l'érodée quand elle tient ([MIN_ERODED_SIDE_PX]
     * par côté), la colonne centrale sinon — jamais la boîte entière, dont la
     * médiane lirait le fond sur une boîte fine (poteau). `null` si la boîte
     * est vide (hauteur nulle) ou non finie.
     */
    fun robustWindow(box: List<Float>): List<Float>? {
        erodedBox(box)?.let { inner ->
            if (inner[2] - inner[0] >= MIN_ERODED_SIDE_PX && inner[3] - inner[1] >= MIN_ERODED_SIDE_PX) return inner
        }
        return centralColumn(box)
    }

    /**
     * Colonne centrale de [box] : pleine hauteur, centrée, de demi-largeur le
     * quart de la boîte ([minHalfWidthPx] au moins — une boîte de largeur nulle
     * mesure encore sa ligne). `null` si la boîte est vide ou non finie.
     */
    fun centralColumn(box: List<Float>, minHalfWidthPx: Float = 1f): List<Float>? {
        if (box.size < 4 || minHalfWidthPx < 0f || minHalfWidthPx.isNaN()) return null
        val x1 = box[0]
        val y1 = box[1]
        val x2 = box[2]
        val y2 = box[3]
        if (!x1.isFinite() || !y1.isFinite() || !x2.isFinite() || !y2.isFinite()) return null
        if (x2 < x1 || y2 <= y1) return null
        val centerX = (x1 + x2) / 2f
        val half = maxOf((x2 - x1) / 4f, minHalfWidthPx)
        return listOf(centerX - half, y1, centerX + half, y2)
    }

    /**
     * Fenêtre de médiane restreinte au masque : l'intersection (fenêtre érodée
     * ∩ étendues masque) quand le masque est présent et valide, la fenêtre
     * érodée sinon (masque absent, invalide, ou intersection vide/dégénérée).
     */
    fun maskedWindow(inner: List<Float>, maskBox: List<Float>?): List<Float> {
        if (inner.size < 4 || maskBox == null || maskBox.size < 4) return inner
        if (maskBox.take(4).any { !it.isFinite() }) return inner
        val x1 = maxOf(inner[0], maskBox[0])
        val y1 = maxOf(inner[1], maskBox[1])
        val x2 = minOf(inner[2], maskBox[2])
        val y2 = minOf(inner[3], maskBox[3])
        if (x2 <= x1 || y2 <= y1) return inner
        return listOf(x1, y1, x2, y2)
    }

    /**
     * Échelle mètres : médiane des (mètres × proximité) des ancres ;
     * `null` sans ancre exploitable.
     */
    fun scaleFromAnchors(anchors: List<DepthAnchor>): Float? {
        val scales = FloatArray(anchors.size)
        var count = 0
        for (anchor in anchors) {
            val scale = DepthFusion.calibrateScale(anchor.meters, anchor.closeness)
            if (scale != null) {
                scales[count++] = scale
            }
        }
        if (count == 0) return null
        return DepthFusion.quickselectMedian(scales, count)
    }

    /**
     * Lectures affinées, dans l'ordre des détections : la profondeur remplace
     * le sténopé quand elle mesure (même sans hauteur connue), l'ajoute quand
     * le sténopé est muet, et le garde sinon. Ni l'un ni l'autre : omis.
     */
    fun refine(
        frameW: Int,
        frameH: Int,
        detections: List<Detection>,
        pinhole: List<DistanceReading>,
        depth: DepthMap,
    ): List<DistanceReading> {
        return refineWithStatus(frameW, frameH, detections, pinhole, depth).readings
    }

    /**
     * Pourquoi une détection DEPTH_ONLY finit sans distance : la cause bloquante,
     * jamais un silence. Le sténopé ne parle jamais pour ces labels (pas de hauteur
     * typique, [KnownSizes.DEPTH_ONLY]) ; sans lecture sténopé, la branche sol était
     * muette elle aussi (coupée, sans pose, ou hors gardes).
     */
    enum class DepthMissReason {
        /** 0 ancre sténopé : la profondeur n'a pas d'échelle, la proximité ne vaut pas des mètres. */
        NO_ANCHOR,

        /** Calibré mais pas de médiane de proximité pour cette boîte. */
        NO_CLOSENESS,

        /** Proximité calibrée hors bornes plausibles : refusée, pas bornée déguisée. */
        OUT_OF_BOUNDS,
    }

    /**
     * Détection DEPTH_ONLY sans distance : le `null` typé du repli — pas de mètres,
     * mais le label, la boîte et la cause ([reason]). [cutBottom] dit si les pieds
     * sont sous le cadre : le plan de sol ne pouvait alors pas parler, même avec
     * une pose ([Pinhole.isCutBottom]).
     */
    data class DepthMiss(
        val label: String,
        val box: List<Float>,
        val reason: DepthMissReason,
        val cutBottom: Boolean,
    )

    /**
     * Affinage et sa calibration : sans ancre sténopé, [calibrated] est faux et
     * les lectures rendues sont le sténopé tel quel — la profondeur non calibrée
     * ne déguise jamais une mesure. [unmeasured] nomme les détections DEPTH_ONLY
     * restées sans distance ([DepthMiss]) : le repli explicite, jamais silencieux.
     */
    data class DepthRefinement(
        val readings: List<DistanceReading>,
        val calibrated: Boolean,
        val unmeasured: List<DepthMiss> = emptyList(),
    )

    /**
     * Même mesure que [refine], avec l'état de calibration : [DepthRefinement.calibrated]
     * faux sans ancre exploitable (pas d'échelle, sténopé gardé tel quel), et les
     * DEPTH_ONLY sans distance nommés dans [DepthRefinement.unmeasured] ([onUnmeasured]).
     */
    fun refineWithStatus(
        frameW: Int,
        frameH: Int,
        detections: List<Detection>,
        pinhole: List<DistanceReading>,
        depth: DepthMap,
        onUnmeasured: ((DepthMiss) -> Unit)? = null,
    ): DepthRefinement {
        val closeByBox = HashMap<List<Float>, Float>()
        for (detection in detections) {
            val closeness = boxCloseness(depth, detection.box, frameW, frameH, detection.maskBox)
            if (closeness != null) {
                closeByBox[detection.box] = closeness
            }
        }
        return refineStatusWithCloseness(detections, pinhole, closeByBox, onUnmeasured)
    }

    /**
     * Proximités mesurées par le serveur (`with_depth`) : médiane par boîte,
     * finies seulement — une valeur absente ou non finie ne mesure rien.
     */
    fun serverCloseness(objects: List<SegObject>): Map<List<Float>, Float> {
        val closeByBox = HashMap<List<Float>, Float>()
        for (obj in objects) {
            val closeness = obj.depth?.takeIf { it.isFinite() } ?: continue
            closeByBox[obj.box] = closeness
        }
        return closeByBox
    }

    /**
     * Même affinage que [refine], sur des proximités déjà mesurées (serveur) :
     * calibration par les ancres sténopé de la frame, puis remplace/ajoute/garde.
     * L'échelle restant calibrée par frame, la normalisation serveur (0..1) et la
     * carte brute embarquée se valent ici. Sans statut : les appelants qui veulent
     * le repli DEPTH_ONLY explicite ([DepthRefinement.unmeasured]) utilisent
     * [refineStatusWithCloseness].
     */
    fun refineWithCloseness(
        detections: List<Detection>,
        pinhole: List<DistanceReading>,
        closeByBox: Map<List<Float>, Float>,
    ): List<DistanceReading> {
        return refineStatusWithCloseness(detections, pinhole, closeByBox).readings
    }

    /**
     * Même affinage que [refineWithCloseness], avec l'état de calibration :
     * [DepthRefinement.calibrated] faux sans ancre exploitable. Les replis
     * bas-au-contact ([DistanceReading.cutFallback]) ne calibrent pas.
     * Une détection DEPTH_ONLY restée sans lecture n'est jamais tue : elle est
     * nommée dans [DepthRefinement.unmeasured] ([DepthMiss]) et signalée à
     * [onUnmeasured] (crochet de journalisation, le cœur restant pur JVM).
     */
    fun refineStatusWithCloseness(
        detections: List<Detection>,
        pinhole: List<DistanceReading>,
        closeByBox: Map<List<Float>, Float>,
        onUnmeasured: ((DepthMiss) -> Unit)? = null,
    ): DepthRefinement {
        val byBox = pinhole.associateBy { it.box }
        val anchors = ArrayList<DepthAnchor>(pinhole.size)
        for (reading in pinhole) {
            // Repli bas-au-contact : biaise loin, jamais une ancre.
            if (reading.cutFallback) continue
            val closeness = closeByBox[reading.box]
            val meters = reading.meters.takeIf { it.isFinite() }
            if (closeness != null && meters != null) {
                anchors += DepthAnchor(meters, closeness)
            }
        }
        val cal = AffineScale.calibrate(anchors)
        val refined = ArrayList<DistanceReading>(detections.size)
        val unmeasured = ArrayList<DepthMiss>()
        for (detection in detections) {
            val base = byBox[detection.box]
            val closeness = closeByBox[detection.box]
            val reading = refinedOne(detection, base, closeness, cal)
            if (reading != null) {
                refined += reading
            } else {
                missOf(detection, closeness, cal)?.let { miss ->
                    unmeasured += miss
                    onUnmeasured?.invoke(miss)
                }
            }
        }
        return DepthRefinement(refined, cal != null, unmeasured)
    }

    /**
     * Cause d'absence de distance pour une détection DEPTH_ONLY restée sans lecture
     * ([KnownSizes.isDepthOnly]) : sans ancre il n'y a pas d'échelle, sinon c'est la
     * proximité qui manque ou sort des bornes. `null` pour les autres labels — leur
     * silence garde ses causes amont (occlusion, coupe, classe inconnue).
     */
    private fun missOf(
        detection: Detection,
        closeness: Float?,
        cal: AffineCalibration?,
    ): DepthMiss? {
        if (!KnownSizes.isDepthOnly(detection.label)) return null
        val reason = when {
            cal == null -> DepthMissReason.NO_ANCHOR
            closeness == null -> DepthMissReason.NO_CLOSENESS
            else -> DepthMissReason.OUT_OF_BOUNDS
        }
        val cutBottom = Pinhole.isCutBottom(detection.box, detection.height)
        return DepthMiss(detection.label, detection.box, reason, cutBottom)
    }

    private fun refinedOne(
        detection: Detection,
        base: DistanceReading?,
        closeness: Float?,
        cal: AffineCalibration?,
    ): DistanceReading? {
        var meters: Float? = null
        if (closeness != null && cal != null) {
            meters = toMeters(closeness, cal)
        }
        if (meters != null) {
            val id = base?.trackId ?: DistanceMeter.UNTRACKED_ID
            // La confiance du sténopé traverse : les mètres viennent de la profondeur,
            // calibrée par ces mêmes ancres — pas une certitude neuve.
            return DistanceReading(id, detection.label, meters, meters, detection.box, base?.confidence ?: 1f)
        }
        return base
    }

    /** Proximité → mètres ; `null` si hors bornes (jamais borné déguisé, comme [Pinhole]). */
    private fun toMeters(closeness: Float, cal: AffineCalibration): Float? {
        val meters = AffineScale.apply(closeness, cal) ?: return null
        if (!meters.isFinite()) return null
        return meters.takeIf { it in Pinhole.MIN_METERS..Pinhole.MAX_METERS }
    }
}
