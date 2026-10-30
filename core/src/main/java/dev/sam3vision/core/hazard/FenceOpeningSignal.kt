package dev.sam3vision.core.hazard

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.distance.LensSpec
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
            val boxes = readings.mapNotNull { near(it, frameW) }
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
