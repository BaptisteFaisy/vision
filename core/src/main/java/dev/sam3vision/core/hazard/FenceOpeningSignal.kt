package dev.sam3vision.core.hazard

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.live.GuidanceSide
import dev.sam3vision.core.live.PlacedLabel
import dev.sam3vision.core.live.PositionText
import kotlin.math.tan

/** État d'une clôture : fermée (on ne passe pas) ou ouverte (on passe). */
enum class FenceOpeningState { Closed, Open }

/** Où se trouve l'état annoncé : devant, à gauche ou à droite (secteurs [PositionText.SIDE_EDGE]). */
enum class FenceOpeningPosition { Ahead, Left, Right }

/**
 * Signal grillage d'une frame : [detected] quand une clôture suivie
 * ([FenceOpeningConcepts]) barre le chemin ou l'ouvre à
 * [FenceOpeningWatcher.RANGE_METERS] ou moins, [state] son état, [position]
 * où il se trouve, [meters] sa distance, [centerX] son centre normalisé (le
 * cadran explicite, voir [FenceOpeningText]), [isGate] si un portail borde
 * l'annonce, [middle] si la clôture tient les deux côtés.
 */
data class FenceOpeningSignal(
    val detected: Boolean,
    val state: FenceOpeningState? = null,
    val position: FenceOpeningPosition? = null,
    val meters: Float? = null,
    val centerX: Float? = null,
    val isGate: Boolean = false,
    val middle: Boolean = false,
) {
    /**
     * Côté du trou vers lequel partir, `null` sans trou latéral : grillage
     * ouvert à gauche ou à droite — un intervalle d'au moins
     * [FenceOpeningWatcher.PASS_WIDTH_M] à [FenceOpeningWatcher.RANGE_METERS]
     * ou moins, proche de tout droit par construction. Le fermé, le passage
     * au milieu et l'absence de signal restent muets : le guidage garde son
     * évitement générique. L'humain prime toujours : l'appelant ne part vers
     * le trou que s'il garantit 80 cm à toute personne.
     */
    fun holeSide(): GuidanceSide? {
        if (!detected || state != FenceOpeningState.Open) return null
        return when (position) {
            FenceOpeningPosition.Left -> GuidanceSide.Left
            FenceOpeningPosition.Right -> GuidanceSide.Right
            else -> null
        }
    }

    companion object {
        /** Signal vide : jamais d'exception sur le chemin du direct. */
        fun none(): FenceOpeningSignal = FenceOpeningSignal(detected = false)

        /**
         * Signal des lectures affinées, dans l'ordre : fermé (une boîte au
         * couloir, [PositionText.SIDE_EDGE]) ; sinon passage au milieu
         * (clôture des deux côtés, centre libre) ; sinon intervalle entre
         * deux boîtes d'au moins [FenceOpeningWatcher.PASS_WIDTH_M] à sa
         * profondeur. Une clôture seule sur un côté reste muette : le
         * mobilier latéral parle déjà par `HazardWatcher` (« Attention à la
         * clôture … »). Boîte illisible, mesure absente ou dimensions nulles
         * = ignoré.
         */
        fun of(
            readings: List<DistanceReading>,
            frameW: Int,
            frameH: Int,
            hfovDeg: Float = LensSpec.DEFAULT_H_FOV_DEG,
        ): FenceOpeningSignal {
            if (frameW <= 0 || frameH <= 0) return none()
            return ofBoxes(readings.mapNotNull { near(it, frameW) }, hfovDeg)
        }

        /**
         * Signal des labels placés (voix du direct), miroir de [of] : mêmes
         * règles — fermé, milieu, intervalle — depuis les boîtes normalisées
         * et les distances déjà publiées par frame. Le guidage y lit le trou
         * vers lequel partir ([FenceOpeningSignal.holeSide]) ; la description
         * douce garde [of].
         */
        fun ofPlaced(
            labels: List<PlacedLabel>,
            hfovDeg: Float = LensSpec.DEFAULT_H_FOV_DEG,
        ): FenceOpeningSignal = ofBoxes(labels.mapNotNull(::nearPlaced), hfovDeg)

        /** Fermé, milieu, intervalle, dans l'ordre : le même verdict, deux sources. */
        private fun ofBoxes(boxes: List<FenceBox>, hfovDeg: Float): FenceOpeningSignal {
            if (boxes.isEmpty()) return none()
            closedOf(boxes)?.let { return it }
            middleOf(boxes)?.let { return it }
            return gapOf(boxes, hfovDeg) ?: none()
        }

        /** Clôture proche : boîte normalisée (x0 ≤ x1), centre, distance, portail ou non. */
        private data class FenceBox(
            val x0: Float,
            val x1: Float,
            val centerX: Float,
            val meters: Float,
            val isGate: Boolean,
        )

        /** Clôture proche mesurée ([FenceOpeningWatcher.RANGE_METERS] ou moins), `null` sinon. */
        private fun near(reading: DistanceReading, frameW: Int): FenceBox? {
            if (!FenceOpeningConcepts.isFence(reading.label)) return null
            val meters = reading.meters.takeIf { it.isFinite() && it > 0f } ?: return null
            if (meters > FenceOpeningWatcher.RANGE_METERS) return null
            val box = reading.box
            if (box.size < 4) return null
            val x0 = minOf(box[0], box[2]) / frameW
            val x1 = maxOf(box[0], box[2]) / frameW
            if (!x0.isFinite() || !x1.isFinite()) return null
            return FenceBox(x0, x1, (x0 + x1) / 2f, meters, FenceOpeningConcepts.isGate(reading.label))
        }

        /** Pan proche mesuré depuis un label placé (boîte déjà normalisée), `null` sinon. */
        private fun nearPlaced(label: PlacedLabel): FenceBox? {
            if (!FenceOpeningConcepts.isFence(label.label)) return null
            val meters = label.meters?.takeIf { it.isFinite() && it > 0f } ?: return null
            if (meters > FenceOpeningWatcher.RANGE_METERS) return null
            val x0 = label.x0
            val x1 = label.x1
            if (x0 == null || x1 == null || !x0.isFinite() || !x1.isFinite()) return null
            val left = minOf(x0, x1)
            val right = maxOf(x0, x1)
            return FenceBox(left, right, (left + right) / 2f, meters, FenceOpeningConcepts.isGate(label.label))
        }

        /**
         * Fermé : la boîte la plus proche dont le centre barre le couloir
         * ([PositionText.SIDE_EDGE], comme `SidewalkKeep`) — le trait central
         * est coupé, on ne passe pas tout droit.
         */
        private fun closedOf(boxes: List<FenceBox>): FenceOpeningSignal? {
            val edge = PositionText.SIDE_EDGE
            val closed = boxes
                .filter { it.centerX >= edge && it.centerX <= 1f - edge }
                .minByOrNull { it.meters } ?: return null
            return FenceOpeningSignal(
                detected = true,
                state = FenceOpeningState.Closed,
                position = FenceOpeningPosition.Ahead,
                meters = closed.meters,
                centerX = closed.centerX,
                isGate = closed.isGate,
            )
        }

        /**
         * Passage au milieu : clôture des deux côtés, centre libre (le fermé
         * a déjà parlé sinon) — la distance est celle du pan le plus proche.
         */
        private fun middleOf(boxes: List<FenceBox>): FenceOpeningSignal? {
            val edge = PositionText.SIDE_EDGE
            if (boxes.none { it.centerX < edge }) return null
            if (boxes.none { it.centerX > 1f - edge }) return null
            val nearest = boxes.minByOrNull { it.meters } ?: return null
            return FenceOpeningSignal(
                detected = true,
                state = FenceOpeningState.Open,
                position = FenceOpeningPosition.Ahead,
                meters = nearest.meters,
                centerX = 0.5f,
                isGate = boxes.any { it.isGate },
                middle = true,
            )
        }

        /**
         * Intervalle : le plus large entre deux boîtes consécutives, projeté
         * en mètres à la profondeur du pan le plus proche (miroir du trait de
         * `Guidance`) — ouvert s'il vaut au moins
         * [FenceOpeningWatcher.PASS_WIDTH_M]. Un intervalle centré entre deux
         * pans latéraux passe par [middleOf] (« Passage au milieu. »).
         */
        private fun gapOf(boxes: List<FenceBox>, hfovDeg: Float): FenceOpeningSignal? {
            if (boxes.size < 2) return null
            val sorted = boxes.sortedBy { it.x0 }
            var best: FenceOpeningSignal? = null
            var bestWidth = 0f
            for (i in 0 until sorted.size - 1) {
                val left = sorted[i]
                val right = sorted[i + 1]
                val gapNorm = right.x0 - left.x1
                if (gapNorm <= 0f || !gapNorm.isFinite()) continue
                val depth = minOf(left.meters, right.meters)
                val width = gapNorm * 2f * depth * halfTangent(hfovDeg)
                if (width >= FenceOpeningWatcher.PASS_WIDTH_M && width > bestWidth) {
                    bestWidth = width
                    val center = (left.x1 + right.x0) / 2f
                    best = FenceOpeningSignal(
                        detected = true,
                        state = FenceOpeningState.Open,
                        position = sectorOf(center),
                        meters = depth,
                        centerX = center,
                        isGate = left.isGate || right.isGate,
                    )
                }
            }
            return best
        }

        /** Secteur de [centerX] : les seuils du couloir ([PositionText.SIDE_EDGE]). */
        private fun sectorOf(centerX: Float): FenceOpeningPosition {
            val edge = PositionText.SIDE_EDGE
            return when {
                centerX < edge -> FenceOpeningPosition.Left
                centerX > 1f - edge -> FenceOpeningPosition.Right
                else -> FenceOpeningPosition.Ahead
            }
        }

        /** Demi-tangente du champ horizontal, bornée (miroir de `Guidance`). */
        private fun halfTangent(hfovDeg: Float): Float {
            val fov = hfovDeg.coerceIn(LensSpec.FOV_MIN, LensSpec.FOV_MAX)
            return tan(Math.toRadians(fov / 2.0)).toFloat()
        }
    }
}
