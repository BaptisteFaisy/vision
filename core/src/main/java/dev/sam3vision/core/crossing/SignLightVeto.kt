package dev.sam3vision.core.crossing

import dev.sam3vision.core.hazard.HazardConcepts
import dev.sam3vision.core.model.SegObject
import kotlin.math.max
import kotlin.math.min

/**
 * Veto panneau : une boîte de feu faible, inscrite dans une boîte `sign` mieux
 * notée, n'est qu'un panneau — pas un feu éteint. Mesuré sur les vidéos Samsung
 * du 04/11 : le dos d'un panneau rond (`20261104_101016`) et une barre
 * (`20261104_100943`) sortent en « pedestrian traffic light » à 0,36–0,55,
 * toujours à ≥ 93 % dans un `sign` à 0,52–0,78 ; sur 186 frames de vrais feux
 * (trois vidéos), la règle ne fait perdre le feu sur aucune. Sans ce
 * veto, une seule de ces frames relançait 8 frames de « Don't cross. » sur un
 * passage sans feu.
 *
 * L'appelant n'écarte la boîte que si **aucune lampe n'y est allumée** : une
 * lampe vue l'emporte toujours sur la géométrie, un rouge reste un rouge.
 * Boîte illisible ⇒ pas de veto (même contrat que [CarLightVeto]).
 *
 * Pur Kotlin, testé en JVM (`SignLightVetoTest`).
 */
object SignLightVeto {

    /** Sous ce score SAM, une boîte de feu est assez faible pour n'être qu'un panneau. */
    const val WEAK_SCORE = 0.6f

    /** Contenance feu-dans-panneau à partir de laquelle le veto tranche. */
    private const val CONTAINMENT_MIN = 0.8f

    /** Vrai si [light] est faible et inscrite dans un panneau de [objects] mieux noté. */
    fun isVetoed(light: SegObject, objects: List<SegObject>): Boolean {
        if (light.score >= WEAK_SCORE) return false
        return objects.any { sign ->
            HazardConcepts.nameOf(sign.label)?.en == SIGN && sign.score > light.score &&
                containment(light.box, sign.box) >= CONTAINMENT_MIN
        }
    }

    /** Part de [inner] dans [outer] (0 si illisible ou disjoint). */
    private fun containment(inner: List<Float>, outer: List<Float>): Float {
        if (inner.size < 4 || outer.size < 4) return 0f
        val area = (inner[2] - inner[0]) * (inner[3] - inner[1])
        if (!area.isFinite() || area <= 0f) return 0f
        val width = min(inner[2], outer[2]) - max(inner[0], outer[0])
        val height = min(inner[3], outer[3]) - max(inner[1], outer[1])
        return if (width <= 0f || height <= 0f) 0f else width * height / area
    }

    /** Nom anglais du concept panneau ([HazardConcepts]). */
    private const val SIGN = "sign"
}
