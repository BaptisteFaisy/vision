package dev.sam3vision.core.curb

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.live.PositionText

/**
 * Signal trottoir d'une frame : [detected] quand un bord est dans le couloir
 * marché à [CurbWatcher.CURB_METERS] ou moins, [meters] sa distance lissée
 * (la plus proche en cas de doublons), [centerX] son centre normalisé (l'heure
 * du cadran, `null` = inconnue), [edge] le sens du bord (`null` = construction
 * manuelle, lue comme une montée ; [CurbEdge.Uncertain] = zone grise, consigne
 * prudente), [confidence] sa confiance (basse pour un chemin/une route, voir
 * [CurbConcepts.curbConfidence]), [label] son libellé SAM brut (journal).
 *
 * [tier] dérive toujours de [detected]/[meters] : pas de signal incohérent.
 */
data class CurbSignal(
    val detected: Boolean,
    val meters: Float?,
    val centerX: Float? = null,
    val edge: CurbEdge? = null,
    val confidence: Float = 1f,
    val label: String? = null,
) {
    /** Palier instantané : imminent sous [CurbWatcher.STEP_NEAR_METERS]. */
    val tier: CurbTier
        get() = when {
            !detected -> CurbTier.NONE
            meters != null && meters <= CurbWatcher.STEP_NEAR_METERS -> CurbTier.NEAR
            else -> CurbTier.FAR
        }

    companion object {
        /** Signal vide : jamais d'exception sur le chemin du direct. */
        fun none(): CurbSignal = CurbSignal(detected = false, meters = null)

        /**
         * Signal des lectures affinées ([meters] sténopé ou profondeur, déjà
         * lissée) : un label [CurbConcepts] (bord, ou chemin/route en bord
         * incertain, confiance basse) dans le couloir ([PositionText.SIDE_EDGE]),
         * bas de boîte en bande basse et distance sous le seuil. Le plus proche
         * gagne. Boîte illisible ou dimensions nulles = ignoré, pas d'exception.
         */
        fun of(readings: List<DistanceReading>, frameW: Int, frameH: Int): CurbSignal {
            if (frameW <= 0 || frameH <= 0) return none()
            var best: Hit? = null
            for (reading in readings) {
                val hit = nearCurb(reading, frameW, frameH) ?: continue
                if (best == null || hit.meters < best.meters) best = hit
            }
            if (best != null) {
                return CurbSignal(
                    detected = true, meters = best.meters, centerX = best.centerX,
                    edge = best.edge, confidence = best.confidence, label = best.label,
                )
            }
            return none()
        }

        /** Candidat bordure : distance, centre, sens, confiance et libellé brut. */
        private data class Hit(
            val meters: Float,
            val centerX: Float,
            val edge: CurbEdge,
            val confidence: Float,
            val label: String,
        )

        /**
         * Bord proche candidat si [reading] en est un, `null` sinon. Les chemins
         * et routes y sont des bords incertains, confiance basse — jamais exclus
         * silencieusement (audit 31/10).
         */
        private fun nearCurb(reading: DistanceReading, frameW: Int, frameH: Int): Hit? {
            val confidence = CurbConcepts.curbConfidence(reading.label)
            if (confidence <= 0f) return null
            val meters = reading.meters.takeIf { it.isFinite() } ?: return null
            if (meters > CurbWatcher.CURB_METERS) return null
            val box = reading.box
            if (box.size < 4) return null
            val centerX = (box[0] + box[2]) / 2f / frameW
            if (centerX < PositionText.SIDE_EDGE || centerX > 1f - PositionText.SIDE_EDGE) return null
            if (box[3] / frameH < CurbWatcher.LOWER_BAND_FRACTION) return null
            val edge = if (CurbConcepts.isGround(reading.label)) {
                edgeKind(box, frameH) ?: return null
            } else if (CurbConcepts.isPathRoad(reading.label)) {
                CurbEdge.Uncertain
            } else {
                CurbEdge.Up
            }
            return Hit(meters, centerX, edge, confidence, reading.label)
        }

        /**
         * Plan (`sidewalk`…) : bord proche ou sol sous les pieds ? Montée = le
         * plan commence au-dessus des pieds (bas sous [CurbWatcher.PLANE_BOTTOM_MAX]) ;
         * descente = plan collé aux pieds mais borné en haut (haut au-dessus de
         * [CurbWatcher.PLANE_TOP_MIN]) ; plan plein cadre = le sol sur lequel on
         * marche (`null`, muet). Près des seuils (± [CurbWatcher.EDGE_GRAY_BAND]),
         * zone grise ([CurbEdge.Uncertain]) : la consigne prudente plutôt qu'un
         * sens faux (audit 31/10). Seuils : priors à valider sur captures téléphone.
         */
        private fun edgeKind(box: List<Float>, frameH: Int): CurbEdge? {
            val bottom = box[3] / frameH
            val top = box[1] / frameH
            if (bottom < CurbWatcher.PLANE_BOTTOM_MAX - CurbWatcher.EDGE_GRAY_BAND) return CurbEdge.Up
            if (bottom < CurbWatcher.PLANE_BOTTOM_MAX + CurbWatcher.EDGE_GRAY_BAND) return CurbEdge.Uncertain
            if (top >= CurbWatcher.PLANE_TOP_MIN + CurbWatcher.EDGE_GRAY_BAND) return CurbEdge.Down
            if (top >= CurbWatcher.PLANE_TOP_MIN - CurbWatcher.EDGE_GRAY_BAND) return CurbEdge.Uncertain
            return null
        }
    }
}
