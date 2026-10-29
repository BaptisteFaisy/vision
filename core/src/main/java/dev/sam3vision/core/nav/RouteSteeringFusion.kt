package dev.sam3vision.core.nav

import dev.sam3vision.core.curb.SidewalkKeep
import dev.sam3vision.core.curb.SidewalkPosition
import dev.sam3vision.core.live.GuidanceDecision
import dev.sam3vision.core.live.SteeringCap

/**
 * Direction effective de marche : le cap de l'itinéraire, sauf quand le
 * couloir est bouché (le passage conseillé prime) ou quand l'utilisateur n'est
 * plus sur le trottoir (le retour prime sur le cap, qui est suspendu).
 */
data class FusedSteering(
    /** Où marcher : le cap route, le passage si c'est bouché, le trottoir sinon. */
    val direction: SteeringCap,
    /** Vrai quand le cap route n'est pas suivi (passage obstacle, retour trottoir). */
    val detour: Boolean,
)

/**
 * Fusion itinéraire × obstacle × trottoir : le chaînon entre le moteur de route
 * (Valhalla, qui sait où tourner) et la sonde (SAM, qui voit ce qui bouche et où
 * est le trottoir).
 *
 * Priorités strictes : couloir bouché → le passage conseillé, même s'il contredit
 * le cap ([detour] vrai, la sécurité ne négocie jamais) ; sinon trottoir sur un
 * côté → le retour prime et le cap destination est suspendu ([detour] vrai) ;
 * sinon → le cap route. Un passage manquant sur voie bouchée garde le cap route
 * (mieux vaut une direction que rien), toujours en détour. Un trottoir absent ne
 * dévie jamais : dans le doute on garde le cap (la voix, elle, reste prudente).
 *
 * Pure et testée en JVM : aucun Android, aucun réseau, ne lève jamais.
 */
object RouteSteeringFusion {

    /**
     * Cap effectif pour [routeCap] (prochaine manœuvre), [decision] (couloir
     * marché à ce cap) et [sidewalk] (trottoir vu caméra) : bouché → passage,
     * sinon retour trottoir s'il est sur un côté, sinon cap route.
     */
    fun fuse(
        routeCap: SteeringCap,
        decision: GuidanceDecision,
        sidewalk: SidewalkPosition = SidewalkPosition.Ahead,
    ): FusedSteering {
        if (!decision.blocked) {
            val back = SidewalkKeep.returnCap(sidewalk)
            if (back != null) return FusedSteering(back, detour = true)
            return FusedSteering(routeCap, detour = false)
        }
        return FusedSteering(decision.passage ?: routeCap, detour = true)
    }
}
