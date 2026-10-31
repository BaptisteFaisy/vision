package dev.sam3vision.core.traverse

import dev.sam3vision.core.depth.DepthConfirm
import dev.sam3vision.core.motion.MotionSample
import dev.sam3vision.core.predict.EgoCompensator

/**
 * Croissance d'une boîte véhicule entre deux frames (FUSION PISTE1+2) : la
 * surface doit croître ([EgoCompensator.areaGrowing], décote du zoom du fond,
 * repli brut sans fond prouvé) ET la profondeur doit confirmer le
 * rapprochement ([DepthConfirm.confirmsGrowth], repli géométrie sans mesure).
 * Un pan/zoom caméra ne fait donc plus passer une boîte qui gonfle pour un
 * véhicule qui roule — les deux vetos sont conservateurs (le doute ne tait
 * jamais). Pure, couverte via `VehicleStateWatcher`.
 */
internal fun isVehicleGrowth(
    prevArea: Float,
    prevAtMs: Long,
    prevCloseness: Float?,
    area: Float,
    closeness: Float?,
    atMs: Long,
    global: MotionSample?,
): Boolean = EgoCompensator.areaGrowing(prevArea, area, atMs - prevAtMs, global) &&
    DepthConfirm.confirmsGrowth(prevCloseness, closeness)
