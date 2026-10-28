package dev.sam3vision.core.curb

import dev.sam3vision.core.distance.KnownSizes

/**
 * Concepts trottoir : ce que SAM doit suivre pour signaler un bord à monter.
 *
 * `sidewalk` est déjà dans les défauts du direct (dangers extérieurs, ordre
 * user du 28/10) ; les autres formes sont reconnues côté client ([isCurb])
 * quand le backend les segmente. Le français reste reconnu : un label
 * « trottoir » compte comme un « sidewalk ».
 *
 * `sidewalk` segmente le plan entier, pas le bord : la géométrie du signal
 * ([CurbSignal]) n'en retient que le proche (bande basse, ≤ 1 m). « marche »
 * seul n'est **pas** reconnu : ambigu avec la marche à pied, comme côté
 * [dev.sam3vision.core.traverse.StairConcepts] — seul « step » anglais compte.
 */
object CurbConcepts {

    private val KEYS = setOf(
        "curb", "kerb", "sidewalk", "pavement", "footpath", "step", "steps",
        "trottoir", "bordure",
    )

    /**
     * Vrai si [label] désigne un trottoir ou un bord à monter (casse, accents,
     * pluriel simple neutralisés).
     */
    fun isCurb(label: String): Boolean {
        val key = KnownSizes.canonical(label)
        if (key.isEmpty()) return false
        if (key in KEYS) return true
        return key.endsWith("s") && key.length > 3 && key.dropLast(1) in KEYS
    }
}
