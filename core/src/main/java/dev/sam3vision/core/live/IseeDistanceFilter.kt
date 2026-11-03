package dev.sam3vision.core.live

import dev.sam3vision.core.crossing.CrossingVerdictRule
import dev.sam3vision.core.curb.CurbConcepts

/**
 * Distances iSee dehors : voitures et bordures seules (ordre user 03/11).
 *
 * Les pastilles des autres concepts sont masquées — masques, voix et guidage
 * lisent toujours toute la couche. Dedans, aucun filtre (ni voitures ni
 * bordures à suivre).
 *
 * Voiture = [CrossingVerdictRule.isCarLabel] (voiture uniquement, bus et
 * camionnette exclus) ; bordure = [CurbConcepts.isCurbEdge] (le bord seul,
 * pas le plan du trottoir). [LABELS] en est la forme exacte pour le filtre
 * `followed` de l'overlay (comparaison insensible à la casse, pluriels inclus).
 */
object IseeDistanceFilter {

    /** Voitures, singulier et pluriel, FR/EN (miroir [CrossingVerdictRule.isCarLabel]). */
    val CAR_LABELS: List<String> = listOf("car", "cars", "voiture", "voitures")

    /** Bordures, singulier et pluriel, FR/EN (miroir [CurbConcepts.isCurbEdge]). */
    val CURB_LABELS: List<String> = listOf(
        "curb", "curbs", "kerb", "kerbs", "bordure", "bordures",
        "bord", "bords", "curbstone", "curbstones",
    )

    /** Pastilles affichées dehors : voitures puis bordures. */
    val LABELS: List<String> = CAR_LABELS + CURB_LABELS

    /** Vrai si [label] porte une pastille dehors (voiture ou bordure). */
    fun isShown(label: String): Boolean =
        CrossingVerdictRule.isCarLabel(label) || CurbConcepts.isCurbEdge(label)

    /**
     * Suivis affichés : dehors, les explicites ([LivePrompts.followedOf]) plus
     * [LABELS] ; dedans, les explicites seuls. Pur, testé en JVM.
     */
    fun followedFor(effective: List<String>, outdoorMode: Boolean): List<String> {
        val explicit = LivePrompts.followedOf(effective, outdoorMode)
        if (!outdoorMode) return explicit
        return (explicit + LABELS).distinctBy { it.lowercase() }
    }
}
