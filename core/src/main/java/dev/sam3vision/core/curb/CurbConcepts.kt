package dev.sam3vision.core.curb

import dev.sam3vision.core.distance.KnownSizes

/**
 * Concepts trottoir : ce que SAM doit suivre pour signaler un bord à monter.
 *
 * `sidewalk` et `curb` sont déjà dans les défauts du direct (dangers
 * extérieurs, ordre user du 28/10) ; les autres formes sont reconnues côté
 * client ([isCurb]) quand le backend les segmente. Le français reste reconnu :
 * « trottoir », « bordure », « rebord » et « bateau » comptent (le serveur
 * renvoie le label saisi, voir `concepts.py`). Les chemins et les routes
 * ([isPathRoad]) n'y signalent aucun bord : seul le guidage obstacles les
 * ignore, comme le plan du trottoir.
 *
 * `sidewalk` segmente le plan entier, pas le bord : la géométrie du signal
 * ([CurbSignal]) n'en retient que le proche (bande basse, ≤ 1 m). « marche »
 * seul n'est **pas** reconnu : ambigu avec la marche à pied, comme côté
 * [dev.sam3vision.core.traverse.StairConcepts] — seul « step » anglais compte.
 */
object CurbConcepts {

    private val KEYS = setOf(
        "curb", "kerb", "sidewalk", "pavement", "footpath", "step", "steps",
        "trottoir", "bordure", "rebord",
        "curb ramp", "bateau", "ramp", "rampe", "gutter", "caniveau",
    )

    /** Plan marchable du trottoir (pas le bord) : le guidage obstacles l'ignore. */
    private val GROUND = setOf("sidewalk", "pavement", "footpath", "trottoir", "curb ramp", "bateau")

    /**
     * Chemins et routes (FR/EN, canoniques : « allée » vaut « allee ») : le
     * guidage obstacles les ignore — on ne fait jamais esquiver à
     * l'utilisateur le chemin ou la route sur lesquels il marche (ordre user du
     * 30/10). « track », « rail » et « voie » exclus à dessein : une voie
     * ferrée reste un obstacle.
     */
    private val PATH_ROAD = setOf(
        "path", "pathway", "walkway", "trail", "chemin", "sentier", "allee",
        "road", "route", "street", "rue", "chaussee", "avenue", "boulevard",
        "alley", "ruelle",
    )

    /**
     * Vrai si [label] désigne un trottoir ou un bord à monter (casse, accents,
     * pluriel simple neutralisés).
     */
    fun isCurb(label: String): Boolean = matches(label, KEYS)

    /**
     * Vrai si [label] désigne le sol marchable (plan du trottoir, pas le bord) :
     * on ne contourne pas le sol sur lequel on marche. La bordure (`curb`) et la
     * marche (`step`) restent des obstacles — elles se montent, pas se traversent.
     */
    fun isGround(label: String): Boolean = matches(label, GROUND)

    /**
     * Vrai si [label] désigne un chemin ou une route : le guidage obstacles
     * l'ignore, comme le plan du trottoir. Hors détection bordure
     * ([CurbSignal]) : un chemin ou une route n'y est jamais un bord à
     * monter, seul le plan trottoir y parle (montée, descente, bateau).
     */
    fun isPathRoad(label: String): Boolean = matches(label, PATH_ROAD)

    /** [label] canonique dans [keys] (pluriel simple toléré). */
    private fun matches(label: String, keys: Set<String>): Boolean {
        val key = KnownSizes.canonical(label)
        if (key.isEmpty()) return false
        if (key in keys) return true
        return key.endsWith("s") && key.length > 3 && key.dropLast(1) in keys
    }
}
