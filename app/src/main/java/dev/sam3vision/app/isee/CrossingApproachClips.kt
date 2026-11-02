package dev.sam3vision.app.isee

import dev.sam3vision.core.speech.IseeClipsEn

/**
 * Repli dégradé de l'annonce d'approche (traversée R1, SPEC v4, clips seuls) :
 * « Left, 2 meters. » ne matche aucun texte du catalogue ([IseeClipsEn], pas
 * d'atome « mètre »), donc le bus le jetait en `sans-clip` (R1 100 % muette
 * dès que la distance est mesurée). Arbitrage user : parler la **direction
 * seule** avec les clips existants (« Left, 2 meters. » → `guide_left`,
 * « Left. ») ; la distance reviendra avec de futurs clips unité. Le texte
 * posté au bus est inchangé (l'affichage lit le même texte) : seule la
 * séquence parle à la place du texte.
 *
 * Devant, le mot dit quoi, pas où (ordre user du 02/11) : « Ahead » (avec ou
 * sans distance) parle `crosswalk` (« Crosswalk. », clip < 500 ms, redit
 * toutes les 2 s hors Traversée).
 *
 * Règle d'or : jamais de mensonge — pas d'arrondi vers les paliers ronds
 * (`meters_5` sur « 2 meters » dirait « 5 meters »). Anglais seul (le pack ne
 * porte que des clips EN) : direction sans distance (« Left. », déjà résolue
 * au catalogue), français et inconnu rendent une liste vide (comportement
 * existant : clip retrouvé, ou silence).
 */
internal fun crossingApproachClips(text: String): List<String> {
    if (!text.endsWith(".")) return emptyList()
    val head = text.substringBefore(", ").removeSuffix(".")
    if (head == "Ahead") return listOf(IseeClipsEn.CROSSWALK)
    // Sans virgule = sans distance (« Left. ») : le catalogue la parle déjà.
    if (!text.contains(", ")) return emptyList()
    return when (head) {
        "Left" -> listOf(IseeClipsEn.GO_LEFT)
        "Right" -> listOf(IseeClipsEn.GO_RIGHT)
        else -> emptyList()
    }
}
