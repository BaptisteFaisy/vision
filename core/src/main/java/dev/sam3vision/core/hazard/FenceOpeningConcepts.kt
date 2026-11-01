package dev.sam3vision.core.hazard

import dev.sam3vision.core.distance.KnownSizes

/**
 * Concepts grillage (SPEC v4, niveau 4, moins de 6 m) : ce que SAM doit
 * suivre pour dire si la clôture qui barre le chemin est fermée ou ouverte
 * ([FenceOpeningSignal]) — clôtures, grillages et barrières. Le français
 * reste reconnu : « grillage » compte comme « fence ».
 *
 * Le portail (« gate », « portail ») est supprimé de la détection v4 :
 * [isGate] rend toujours faux (signature gardée — [FenceOpeningSignal] et
 * [FenceOpeningText] nomment encore un portail si l'appelant le dit, mais
 * plus aucun label ne s'y reconnaît).
 *
 * Distances : le sténopé ([KnownSizes], barrière 1,00 m, clôture 1,50 m)
 * mesure ces concepts — un grillage sans mesure reste muet
 * ([FenceOpeningSignal]).
 */
object FenceOpeningConcepts {

    /**
     * Concepts à suivre côté SAM : 2 prompts, chacun sous
     * [dev.sam3vision.core.live.LivePrompts.MAX_LENGTH] et bien sous
     * `PROMPT_LIMIT` comme [dev.sam3vision.core.live.LivePrompts.MAX].
     */
    val PROMPTS: List<String> = listOf("fence", "barrier")

    private val KEYS = setOf(
        "fence", "cloture", "grillage",
        "barrier", "barriere",
    )

    /**
     * Formes canoniques du portail : ensemble vide depuis la v4 (portail
     * supprimé de la détection) — [isGate] rend toujours faux.
     */
    private val GATES = emptySet<String>()

    /** Vrai si [label] désigne une clôture suivie (casse, accents, pluriel, composés neutralisés). */
    fun isFence(label: String): Boolean = matches(label, KEYS)

    /**
     * Vrai si [label] désigne un portail : toujours faux depuis la v4
     * (portail supprimé de la détection dehors).
     */
    fun isGate(label: String): Boolean = matches(label, GATES)

    /**
     * Hauteur réelle typique de [label] (`null` si inconnue) : le sténopé qui
     * mesure la clôture (barrière 1,00 m, clôture 1,50 m).
     */
    fun heightMeters(label: String): Float? = KnownSizes.heightMeters(label)

    /** [label] canonique dans [keys] : entier, pluriel simple, ou un de ses mots. */
    private fun matches(label: String, keys: Set<String>): Boolean {
        val key = KnownSizes.canonical(label)
        if (key.isEmpty()) return false
        if (key in keys) return true
        if (key.endsWith("s") && key.length > 3 && key.dropLast(1) in keys) return true
        return key.split(' ').any { it in keys }
    }
}
