package dev.sam3vision.core.nav

import dev.sam3vision.core.live.GuidanceDecision
import dev.sam3vision.core.live.SteeringCap

/**
 * Direction effective de marche : le cap de l'itinéraire, sauf quand le
 * couloir est bouché — le passage conseillé par la sonde prime alors.
 */
data class FusedSteering(
    /** Où marcher : le cap route, ou le passage si c'est bouché. */
    val direction: SteeringCap,
    /** Vrai quand le passage obstacle a primé sur le cap route. */
    val detour: Boolean,
)

/**
 * Fusion itinéraire × obstacle : le chaînon entre le moteur de route
 * (Valhalla, qui sait où tourner) et la sonde (SAM, qui voit ce qui bouche).
 *
 * Règle unique : voie libre → on suit le cap route ; couloir bouché → on
 * suit le passage conseillé, même s'il contredit le cap ([detour] vrai).
 * Un passage manquant sur voie bouchée garde le cap route (mieux vaut une
 * direction que rien), toujours en détour.
 *
 * Pure et testée en JVM : aucun Android, aucun réseau, ne lève jamais.
 */
object RouteSteeringFusion {

    /**
     * Cap effectif pour [routeCap] (prochaine manœuvre) et [decision]
     * (couloir marché à ce cap) : libre → cap, bouché → passage.
     */
    fun fuse(routeCap: SteeringCap, decision: GuidanceDecision): FusedSteering {
        if (!decision.blocked) return FusedSteering(routeCap, detour = false)
        return FusedSteering(decision.passage ?: routeCap, detour = true)
    }
}
